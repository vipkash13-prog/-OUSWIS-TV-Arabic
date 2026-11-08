package io.ouswis.tv.standalone;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/** Provider catalogues can limit the unfiltered response; reconcile it with every live group. */
final class XtreamCatalog {
    interface Source { JSONArray get(String action,String category) throws Exception; }
    interface Progress { void update(int done,int total); }
    static final class Entry {
        final String id, name, extension;
        private final Set<String> terms=new LinkedHashSet<>();
        Entry(String id,String name,String extension){
            this.id=id;this.name=name;this.extension=extension;terms.add(normalize(name));
        }
        boolean matches(String normalizedQuery){
            if(normalizedQuery.isEmpty())return true;
            for(String term:terms)if(term.contains(normalizedQuery))return true;
            return false;
        }
    }
    static final class Result {
        final List<Entry> channels;
        final int failedGroups;
        final boolean groupsUnavailable;
        Result(List<Entry> channels,int failedGroups,boolean groupsUnavailable){
            this.channels=channels;this.failedGroups=failedGroups;this.groupsUnavailable=groupsUnavailable;
        }
    }
    private static final class GroupResult {
        final String id;
        final JSONArray rows;
        GroupResult(String id,JSONArray rows){this.id=id;this.rows=rows;}
    }

    static String normalize(String text){
        String decomposed=Normalizer.normalize(text,Normalizer.Form.NFKD).toLowerCase(Locale.ROOT);
        StringBuilder out=new StringBuilder(decomposed.length());
        for(int i=0;i<decomposed.length();){
            int point=decomposed.codePointAt(i);i+=Character.charCount(point);
            if(point==0x0640)continue; // Arabic kashida is decoration, not part of a name.
            if(point==0x0649)point=0x064A;
            if(Character.isLetterOrDigit(point))out.appendCodePoint(point);
        }
        return out.toString();
    }

    static Result load(Source source,Progress progress) throws Exception {
        Map<String,String> groups=new LinkedHashMap<>();
        boolean unavailable=false;
        try{
            JSONArray rows=source.get("get_live_categories",null);
            for(int i=0;i<rows.length();i++){
                JSONObject row=rows.optJSONObject(i);if(row==null)continue;
                String id=row.optString("category_id","");
                if(!id.isEmpty())groups.put(id,row.optString("category_name",""));
            }
        }catch(InterruptedException e){throw e;}catch(Exception e){unavailable=true;}
        Map<String,Entry> merged=new LinkedHashMap<>();
        boolean generalFailed=false;
        try{merge(merged,source.get("get_live_streams",null),groups,null);}
        catch(InterruptedException e){throw e;}catch(Exception e){generalFailed=true;}
        int failures=0;
        if(!groups.isEmpty()){
            // Two API requests at most; never create a thread per group or a live stream connection.
            ExecutorService workers=Executors.newFixedThreadPool(2);
            List<Future<GroupResult>> pending=new ArrayList<>();
            AtomicInteger done=new AtomicInteger();
            try{
                for(String id:groups.keySet())pending.add(workers.submit(()->{
                    try{
                        if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                        return new GroupResult(id,source.get("get_live_streams",id));
                    }finally{progress.update(done.incrementAndGet(),groups.size());}
                }));
                for(int i=0;i<pending.size();i++){
                    Future<GroupResult> future=pending.get(i);
                    try{
                        GroupResult group=future.get();
                        merge(merged,group.rows,groups,group.id);
                    }catch(InterruptedException e){throw e;}catch(Exception e){failures++;}
                    finally{pending.set(i,null);} // Release parsed group JSON as soon as it is merged.
                }
            }finally{
                for(Future<GroupResult> future:pending)if(future!=null)future.cancel(true);
                workers.shutdownNow();
            }
        }
        if(generalFailed&&(groups.isEmpty()||failures==groups.size()))
            throw new IOException("Unable to load live channels");
        return new Result(new ArrayList<>(merged.values()),failures,unavailable);
    }

    private static void merge(Map<String,Entry> target,JSONArray rows,Map<String,String> groups,String requestedGroup){
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.optJSONObject(i);if(row==null)continue;
            String id=row.optString("stream_id","");if(id.isEmpty())continue;
            Entry entry=target.get(id);
            if(entry==null){
                entry=new Entry(id,row.optString("name","Channel"),row.optString("container_extension","ts"));
                target.put(id,entry);
            }else entry.terms.add(normalize(row.optString("name","")));
            String declared=row.optString("category_id","");
            boolean knownGroup=groups.containsKey(declared);
            addGroup(entry,groups,declared);
            JSONArray ids=row.optJSONArray("category_ids");
            for(int j=0;ids!=null&&j<ids.length();j++){
                String idGroup=ids.optString(j);knownGroup|=groups.containsKey(idGroup);
                addGroup(entry,groups,idGroup);
            }
            // Use the request's group for providers that omit membership. Explicit membership wins if a server ignores the filter.
            if(!knownGroup)addGroup(entry,groups,requestedGroup);
        }
    }
    private static void addGroup(Entry entry,Map<String,String> groups,String id){
        String name=groups.get(id);
        if(name!=null&&!name.isEmpty())entry.terms.add(normalize(name));
    }
    private XtreamCatalog(){}
}
