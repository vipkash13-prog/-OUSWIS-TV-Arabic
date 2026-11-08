package io.ouswis.tv.standalone;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
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
    interface Observer { void ready(Result snapshot,boolean complete); }
    static final class Entry {
        final String id, name, extension;
        private final Set<String> terms=new LinkedHashSet<>();
        Entry(String id,String name,String extension){
            this.id=id;this.name=name;this.extension=extension;terms.add(normalize(name));
        }
        Entry copy(){Entry copy=new Entry(id,name,extension);copy.terms.addAll(terms);return copy;}
        Entry retaining(Entry previous){Entry copy=copy();if(previous!=null)copy.terms.addAll(previous.terms);return copy;}
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

    static Result accumulate(List<Entry> previous,Result update){
        Map<String,Entry> entries=new LinkedHashMap<>();
        for(Entry entry:previous)entries.put(entry.id,entry);
        for(Entry entry:update.channels)entries.put(entry.id,entry.retaining(entries.get(entry.id)));
        return new Result(new ArrayList<>(entries.values()),update.failedGroups,update.groupsUnavailable);
    }
    private static Result snapshot(Map<String,Entry> entries,int failures,boolean unavailable){
        List<Entry> copies=new ArrayList<>(entries.size());
        for(Entry entry:entries.values())copies.add(entry.copy());
        return new Result(copies,failures,unavailable);
    }
    static Result load(Source source,Progress progress)throws Exception{
        return load(source,progress,(snapshot,complete)->{});
    }
    static Result load(Source source,Progress progress,Observer observer) throws Exception {
        Map<String,String> groups=new LinkedHashMap<>();
        Map<String,Entry> merged=new LinkedHashMap<>();
        boolean unavailable=false,generalFailed=false;
        JSONArray general=null;
        // Publish the first response before even requesting group metadata.
        try{general=source.get("get_live_streams",null);merge(merged,general,groups,null);}
        catch(InterruptedException e){throw e;}catch(Exception e){generalFailed=true;}
        if(!merged.isEmpty())observer.ready(snapshot(merged,0,false),false);
        try{
            JSONArray rows=source.get("get_live_categories",null);
            for(int i=0;i<rows.length();i++){
                JSONObject row=rows.optJSONObject(i);if(row==null)continue;
                String id=row.optString("category_id","");
                if(!id.isEmpty())groups.put(id,row.optString("category_name",""));
            }
        }catch(InterruptedException e){throw e;}catch(Exception e){unavailable=true;}
        if(general!=null)merge(merged,general,groups,null);
        general=null;
        if(!merged.isEmpty())observer.ready(snapshot(merged,0,unavailable),false);
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
                long publishedAt=0;
                for(int i=0;i<pending.size();i++){
                    Future<GroupResult> future=pending.get(i);
                    try{
                        GroupResult group=future.get();
                        merge(merged,group.rows,groups,group.id);
                        long now=System.nanoTime();
                        if(publishedAt==0||now-publishedAt>=1_000_000_000L){
                            observer.ready(snapshot(merged,failures,unavailable),false);publishedAt=now;
                        }
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
        Result result=snapshot(merged,failures,unavailable);
        observer.ready(result,true);
        return result;
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
    static final class Cached {
        final Result result;
        final long savedAt;
        final boolean complete;
        Cached(Result result,long savedAt,boolean complete){this.result=result;this.savedAt=savedAt;this.complete=complete;}
        boolean fresh(long now){return complete&&result.failedGroups==0&&!result.groupsUnavailable&&now>=savedAt&&now-savedAt<3600_000L;}
    }
    static String accountKey(String server,String user,String password)throws Exception{
        String identity=new JSONArray().put(server).put(user).put(password).toString();
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
        StringBuilder key=new StringBuilder(64);
        for(byte b:digest)key.append(String.format(Locale.ROOT,"%02x",b&255));
        return key.toString();
    }
    static Cached readCache(File file,String key){
        try{
            JSONObject data=new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
            if(data.optInt("schema")!=1||!key.equals(data.optString("account")))return null;
            List<Entry> entries=new ArrayList<>();Set<String> ids=new LinkedHashSet<>();
            JSONArray rows=data.getJSONArray("channels");
            for(int i=0;i<rows.length();i++){
                JSONObject row=rows.getJSONObject(i);
                String id=row.getString("id");if(id.isEmpty()||!ids.add(id))return null;
                Entry entry=new Entry(id,row.getString("name"),row.getString("extension"));
                JSONArray terms=row.getJSONArray("terms");
                for(int j=0;j<terms.length();j++)entry.terms.add(normalize(terms.getString(j)));
                entries.add(entry);
            }
            if(entries.isEmpty())return null;
            return new Cached(new Result(entries,data.optInt("failed"),data.optBoolean("unavailable")),
                    data.getLong("saved_at"),data.optBoolean("complete"));
        }catch(Exception ignored){return null;}
    }
    static synchronized void writeCache(File file,String key,Result result,boolean complete,long now)throws Exception{
        JSONArray rows=new JSONArray();
        for(Entry entry:result.channels)rows.put(new JSONObject().put("id",entry.id).put("name",entry.name)
                .put("extension",entry.extension).put("terms",new JSONArray(entry.terms)));
        JSONObject data=new JSONObject().put("schema",1).put("account",key).put("saved_at",now)
                .put("complete",complete).put("failed",result.failedGroups).put("unavailable",result.groupsUnavailable).put("channels",rows);
        File temporary=new File(file.getPath()+".tmp");
        try{
            try(FileOutputStream out=new FileOutputStream(temporary)){
                out.write(data.toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();
            }
            if(!temporary.renameTo(file))throw new IOException("Cannot save channel catalogue");
        }finally{temporary.delete();}
    }
    private XtreamCatalog(){}
}
