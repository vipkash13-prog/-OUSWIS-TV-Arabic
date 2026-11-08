package io.ouswis.tv.standalone;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class XtreamCatalogTest {
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static JSONObject channel(int id,String name,String category)throws Exception{
        return new JSONObject().put("stream_id",id).put("name",name).put("category_id",category);
    }
    private static JSONArray groups()throws Exception{
        return new JSONArray().put(new JSONObject().put("category_id","10").put("category_name","AR | OSN"))
                .put(new JSONObject().put("category_id","11").put("category_name","رياضة"));
    }
    private static JSONArray rows(String group)throws Exception{
        JSONArray rows=new JSONArray().put(channel(1,"OSN News","10")).put(channel(2,"Ｏ.Ｓ.Ｎ Cinema","10"));
        if(group==null)return rows; // Reproduce a provider's incomplete global response.
        if(group.equals("11"))return new JSONArray().put(channel(6,"Sports","11"));
        return rows.put(channel(3,"Movies Action","10")).put(channel(4,"Movies Comedy","10"))
                .put(channel(5,"Movies Action","10")); // Same name, different stream: keep both.
    }
    public static void main(String[] args)throws Exception{
        AtomicInteger active=new AtomicInteger(),peak=new AtomicInteger(),completed=new AtomicInteger();
        XtreamCatalog.Source source=(action,category)->{
            if(action.equals("get_live_categories"))return groups();
            if(category==null)return rows(null);
            int count=active.incrementAndGet();peak.accumulateAndGet(count,Math::max);
            try{Thread.sleep(25);return rows(category);}finally{active.decrementAndGet();}
        };
        XtreamCatalog.Result loaded=XtreamCatalog.load(source,(done,total)->{require(total==2,"Group count");completed.incrementAndGet();});
        require(loaded.channels.size()==6,"Incomplete global response must be reconciled with all groups");
        require(loaded.failedGroups==0&&!loaded.groupsUnavailable,"Complete catalogue");
        List<String> found=new ArrayList<>();
        for(XtreamCatalog.Entry entry:loaded.channels)if(entry.matches(XtreamCatalog.normalize(" O.S.N ")))found.add(entry.id);
        require(found.toString().equals("[1, 2, 3, 4, 5]"),"Group-name search must include members whose names do not contain OSN: "+found);
        require(loaded.channels.get(2).name.equals(loaded.channels.get(4).name),"Distinct streams with identical names survive");
        require(peak.get()>=1&&peak.get()<=2&&completed.get()==2,"Bound API requests to two concurrent workers");
        require(XtreamCatalog.normalize("ＯＳＮ — أَخـبار").equals("osnاخبار"),"Compatibility letters, punctuation, Arabic marks and kashida");
        XtreamCatalog.Result partial=XtreamCatalog.load((action,category)->{
            if(action.equals("get_live_categories"))return groups();
            if("11".equals(category))throw new IOException("HTTP 503");
            return rows(category);
        },(done,total)->{});
        require(partial.channels.size()==5&&partial.failedGroups==1,"Expose incomplete groups instead of silently calling them complete");
        XtreamCatalog.Result recovered=XtreamCatalog.load((action,category)->{
            if(action.equals("get_live_categories"))return groups();
            if(category==null)throw new IOException("Global endpoint unavailable");
            return rows(category);
        },(done,total)->{});
        require(recovered.channels.size()==6&&recovered.failedGroups==0,"Per-group recovery without global endpoint");
        JSONArray unfiltered=rows("10").put(channel(6,"Sports","11"));
        XtreamCatalog.Result ignoredFilter=XtreamCatalog.load((action,category)->action.equals("get_live_categories")?groups():unfiltered,(done,total)->{});
        require(!ignoredFilter.channels.get(5).matches("osn"),"Do not attach unrelated groups when a server ignores category_id");
        JSONArray large=new JSONArray();
        for(int i=0;i<1500;i++)large.put(channel(i,"Channel "+i,""));
        XtreamCatalog.Result bulk=XtreamCatalog.load((action,category)->action.equals("get_live_categories")?new JSONArray():large,(done,total)->{});
        require(bulk.channels.size()==1500,"No implicit channel limit, including stream ID zero");
        XtreamCatalog.Result old=XtreamCatalog.load((action,category)->{
            if(action.equals("get_live_categories"))throw new IOException("Unsupported");
            return rows(null);
        },(done,total)->{});
        require(old.channels.size()==2&&old.groupsUnavailable,"Preserve channels when group API fails, while reporting it");
        boolean refused=false;
        try{XtreamCatalog.load((action,category)->{throw new IOException("HTTP 503");},(done,total)->{});}
        catch(IOException e){refused=true;}
        require(refused,"Total provider failure must not look like a successful empty catalogue");
        System.out.println("PASS: per-group completeness, ID deduplication, duplicate names, normalized/group search, 1500 channels, bounded concurrency and partial/provider failures.");
    }
}
