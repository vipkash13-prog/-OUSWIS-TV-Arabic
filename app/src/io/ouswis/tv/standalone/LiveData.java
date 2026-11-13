package io.ouswis.tv.standalone;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Live TV data for the new screens (SPEC section 5): categories, one category's channels at a time (never all 1000+),
 * the short programme guide, favourites and recents. Categories and channels are cached in files and refreshed daily;
 * the guide is cached for 30 minutes per channel. Plain files and SharedPreferences instead of Room: no library.
 */
final class LiveData {
    static final class Channel {
        final String id,name,icon,num,ext;
        Channel(String id,String name,String icon,String num,String ext){this.id=id;this.name=name;this.icon=icon;this.num=num;this.ext=ext;}
        JSONObject json(){
            try{return new JSONObject().put("id",id).put("name",name).put("icon",icon).put("num",num).put("ext",ext);}
            catch(Exception e){return new JSONObject();}
        }
        static Channel from(JSONObject o){
            if(o==null)return null;String id=o.optString("id").trim();if(id.isEmpty())return null;
            if(o.optString("name").trim().isEmpty())return null;   // no name, no stream: never shown, read from storage or from the server
            return new Channel(id,o.optString("name"),o.optString("icon"),o.optString("num"),o.optString("ext","ts"));
        }
    }
    static final class Category {
        final String id,name;int count=-1;
        Category(String id,String name){this.id=id;this.name=name;}
    }
    static final class Program {
        final String title,shown;final long start,end;   // shown: the Arabic text of a match title, else the title itself
        Program(String title,long start,long end){this.title=title;this.shown=Titles.arabic(title);this.start=start;this.end=end;}
    }
    static final String FAVORITES="*fav",RECENT="*recent";
    private static final long DAY=24L*3600_000,EPG_TTL=30L*60_000;

    private final Context ctx;
    private final XtreamAccount account;
    private final SharedPreferences store;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newFixedThreadPool(2);
    private final Map<String,List<Channel>> channels=new HashMap<>();
    private final Map<String,Long> epgAt=new HashMap<>();
    private final Map<String,List<Program>> epg=new HashMap<>();
    private final Map<String,Boolean> epgBusy=new HashMap<>();
    final List<Category> categories=new ArrayList<>();
    volatile String expiry="",status="";
    volatile boolean failed;
    volatile int total=-1;

    LiveData(Context c,XtreamAccount account){
        this.ctx=c.getApplicationContext();this.account=account;
        store=ctx.getSharedPreferences("ouswis-live",Context.MODE_PRIVATE);
    }
    void shutdown(){io.shutdownNow();}
    private File file(String name){return new File(ctx.getFilesDir(),"live-"+name+".json");}
    private String key(){return Integer.toHexString((account.server+"|"+account.user).hashCode());}

    // MARK: categories

    /** Shows the cached categories at once and refreshes them in the background when they are over a day old. */
    void loadCategories(Runnable changed){
        io.execute(()->{
            boolean fresh=false;
            try{
                File f=file("categories-"+key());
                if(f.exists()){
                    JSONObject o=new JSONObject(new String(Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8));
                    readCategories(o.getJSONArray("items"));
                    expiry=o.optString("expiry");
                    fresh=System.currentTimeMillis()-o.optLong("at")<DAY;
                    main.post(changed);
                }
            }catch(Exception ignored){}
            if(!fresh)refreshCategories(changed);
            else try{accountInfo();main.post(changed);}catch(Exception ignored){}
        });
    }
    void refresh(Runnable changed){
        io.execute(()->{
            synchronized(channels){channels.clear();}
            refreshCategories(changed);
        });
    }
    private void accountInfo() throws Exception {
        JSONObject info=new JSONObject(XtreamAccount.get(account.api(null,null))).optJSONObject("user_info");
        if(info!=null){
            status=info.optString("status");
            long exp=info.optLong("exp_date",0);
            expiry=exp>0?new java.text.SimpleDateFormat("d MMMM yyyy",Design.AR).format(new java.util.Date(exp*1000)):"";
        }
    }
    private void refreshCategories(Runnable changed){
        try{
            try{accountInfo();}catch(Exception ignored){}
            JSONArray rows=new JSONArray(XtreamAccount.get(account.api("get_live_categories",null)));
            readCategories(rows);failed=false;
            JSONObject o=new JSONObject().put("at",System.currentTimeMillis()).put("expiry",expiry).put("items",rows);
            Files.write(file("categories-"+key()).toPath(),o.toString().getBytes(StandardCharsets.UTF_8));
        }catch(Exception e){failed=categories.isEmpty();AppLog.w("live categories failed");}
        main.post(changed);
    }
    private void readCategories(JSONArray rows){
        List<Category> list=new ArrayList<>();
        for(int i=0;i<rows.length();i++){
            JSONObject r=rows.optJSONObject(i);if(r==null)continue;
            String id=r.optString("category_id");if(id.isEmpty())continue;
            Category c=new Category(id,r.optString("category_name",id));
            List<Channel> known;synchronized(channels){known=channels.get(id);}
            if(known!=null)c.count=known.size();
            list.add(c);
        }
        list=SectionOrder.arrange(ctx,list);
        synchronized(categories){categories.clear();categories.addAll(list);}
    }

    // MARK: channels

    List<Channel> cached(String category){
        if(FAVORITES.equals(category))return stored("favorites");
        if(RECENT.equals(category))return stored("recent");
        synchronized(channels){return channels.get(category);}
    }
    /** One category's channels: from memory, else from its file (any age, shown at once), else from the network. */
    void loadChannels(String category,Runnable changed){
        if(FAVORITES.equals(category)||RECENT.equals(category)){main.post(changed);return;}
        synchronized(channels){if(channels.containsKey(category)){main.post(changed);return;}}
        io.execute(()->{
            boolean fresh=false;
            try{
                File f=file("cat-"+key()+"-"+category.replaceAll("[^A-Za-z0-9_-]",""));
                if(f.exists()){
                    JSONObject o=new JSONObject(new String(Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8));
                    putChannels(category,parse(o.getJSONArray("items")));
                    fresh=System.currentTimeMillis()-o.optLong("at")<DAY;
                    main.post(changed);
                }
            }catch(Exception ignored){}
            if(fresh)return;
            try{
                JSONArray rows=new JSONArray(XtreamAccount.get(account.api("get_live_streams","category_id="+XtreamAccount.enc(category))));
                List<Channel> list=parse(rows);
                putChannels(category,list);
                JSONArray slim=new JSONArray();for(Channel c:list)slim.put(c.json());
                JSONObject o=new JSONObject().put("at",System.currentTimeMillis()).put("items",slim);
                Files.write(file("cat-"+key()+"-"+category.replaceAll("[^A-Za-z0-9_-]","")).toPath(),o.toString().getBytes(StandardCharsets.UTF_8));
                MatchChannels.invalidate();
            }catch(Exception e){AppLog.w("live channels failed");}
            main.post(changed);
        });
    }
    private void putChannels(String category,List<Channel> list){
        synchronized(channels){channels.put(category,list);}
        synchronized(categories){for(Category c:categories)if(c.id.equals(category))c.count=list.size();}
    }
    private List<Channel> parse(JSONArray rows){
        List<Channel> out=new ArrayList<>();
        for(int i=0;i<rows.length();i++){
            JSONObject r=rows.optJSONObject(i);if(r==null)continue;
            String id=r.has("stream_id")?r.optString("stream_id"):r.optString("id");if(id.isEmpty())continue;
            String icon=r.has("stream_icon")?r.optString("stream_icon"):r.optString("icon");
            String num=r.has("num")?r.optString("num"):"";
            String ext=r.has("container_extension")?r.optString("container_extension","ts"):r.optString("ext","ts");
            out.add(new Channel(id,r.optString("name","Channel"),icon,num,ext));
        }
        return out;
    }

    // MARK: favourites and recents

    private List<Channel> stored(String name){
        List<Channel> out=new ArrayList<>();
        try{
            JSONArray a=new JSONArray(store.getString(name,"[]"));
            for(int i=0;i<a.length();i++){Channel c=Channel.from(a.optJSONObject(i));if(c!=null)out.add(c);}
        }catch(Exception ignored){}
        return out;
    }
    private void save(String name,List<Channel> list){
        JSONArray a=new JSONArray();for(Channel c:list)a.put(c.json());
        store.edit().putString(name,a.toString()).apply();
    }
    boolean isFavorite(String id){for(Channel c:stored("favorites"))if(c.id.equals(id))return true;return false;}
    /** Long press: adds or removes. Returns true when it is now a favourite. */
    boolean toggleFavorite(Channel channel){
        List<Channel> list=stored("favorites");
        for(int i=0;i<list.size();i++)if(list.get(i).id.equals(channel.id)){list.remove(i);save("favorites",list);return false;}
        list.add(0,channel);if(list.size()>200)list=list.subList(0,200);
        save("favorites",new ArrayList<>(list));return true;
    }
    /** The last 20 channels watched, newest first, and the one to open the app on. */
    static void noteWatched(Context c,Channel channel){
        SharedPreferences s=c.getSharedPreferences("ouswis-live",Context.MODE_PRIVATE);
        try{
            JSONArray a=new JSONArray(s.getString("recent","[]"));
            JSONArray out=new JSONArray();out.put(channel.json());
            for(int i=0;i<a.length()&&out.length()<20;i++){
                JSONObject o=a.optJSONObject(i);if(o!=null&&!channel.id.equals(o.optString("id"))&&Channel.from(o)!=null)out.put(o);   // an entry without a name or an id is dropped, not carried on
            }
            s.edit().putString("recent",out.toString()).putString("last",channel.json().toString()).apply();
        }catch(Exception ignored){}
    }
    /** The channel to show on the home screen's first card, or null when nothing has been watched. */
    static Channel lastWatched(Context c){
        try{
            SharedPreferences s=c.getSharedPreferences("ouswis-live",Context.MODE_PRIVATE);
            Channel last=Channel.from(new JSONObject(s.getString("last","{}")));
            if(last!=null&&!last.name.trim().isEmpty())return last;
        }catch(Exception ignored){}
        SharedPreferences x=XtreamAccount.prefs(c);
        String id=x.getString("lastChannel","");
        String name=x.getString("lastChannelName","");
        return id.isEmpty()||name.trim().isEmpty()?null:new Channel(id,name,"","","ts");
    }

    // MARK: guide

    List<Program> guide(String id){synchronized(epg){return epg.get(id);}}
    /** The next four programmes. Cached for 30 minutes; one request per channel at a time. */
    void loadGuide(String id,Runnable changed){
        long now=System.currentTimeMillis();
        synchronized(epg){
            Long at=epgAt.get(id);
            if(at!=null&&now-at<EPG_TTL){main.post(changed);return;}
            if(epgBusy.containsKey(id))return;
            epgBusy.put(id,true);
        }
        io.execute(()->{
            List<Program> out=new ArrayList<>();
            try{out=fetchGuide(account,id,4);}catch(Exception e){AppLog.w("live guide failed");}
            synchronized(epg){epg.put(id,out);epgAt.put(id,System.currentTimeMillis());epgBusy.remove(id);}
            main.post(changed);
        });
    }
    /** The next four programmes of a channel as the provider lists them (titles are Base64 there). Blocks: call off the UI thread. */
    static List<Program> fetchGuide(XtreamAccount account,String id,int limit) throws Exception {
        List<Program> out=new ArrayList<>();
        JSONObject o=new JSONObject(XtreamAccount.get(account.api("get_short_epg","stream_id="+XtreamAccount.enc(id)+"&limit="+limit)));
        JSONArray rows=o.optJSONArray("epg_listings");
        for(int i=0;rows!=null&&i<rows.length();i++){
            JSONObject r=rows.optJSONObject(i);if(r==null)continue;
            long s=r.optLong("start_timestamp",0)*1000,e=r.optLong("stop_timestamp",0)*1000;
            if(s==0||e==0)continue;
            String t=r.optString("title");
            try{t=new String(Base64.decode(t,Base64.DEFAULT),StandardCharsets.UTF_8);}catch(Exception ignored){}
            out.add(new Program(t.trim(),s,e));
        }
        return out;
    }
    static Program current(List<Program> list,long now){
        if(list!=null)for(Program p:list)if(p.start<=now&&now<p.end)return p;
        return null;
    }
    static Program following(List<Program> list,long now){
        if(list!=null)for(Program p:list)if(p.start>now)return p;
        return null;
    }
}
