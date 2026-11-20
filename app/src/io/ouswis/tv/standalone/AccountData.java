package io.ouswis.tv.standalone;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.JsonReader;
import android.util.JsonToken;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Everything the app keeps about a subscription belongs to that subscription (its key: server + user). The first time
 * the app sees another subscription than the one the data was made for, the old subscription's data goes: channels,
 * sections, the day's guide (also the copy on disk), the short guide, the match links, the league picks and the
 * channels this TV cannot decode. Favourites, recents and the last channel are not thrown away: only their NAMES are
 * kept (never a channel number of the old subscription) and are matched against the new subscription's channels in
 * the background; a channel with no match there is dropped, the others come back with the new numbers.
 */
final class AccountData {
    private AccountData(){}
    private static final Object LOCK=new Object();
    private static final String STAMP="dataFor",STASH="stash";
    /** The keys of the "ouswis-live" preferences that hold something of one subscription. */
    private static final String[] KEYS={"recent","favorites","last","picks","matchPicks","matchPicks2","matchPicks3","badDecoder","sportCats","sportTopUp1","sectionOrder","sectionHidden","badLinks"};
    private static volatile String synced="";
    private static volatile boolean migrating,pending=true;
    private static volatile long lastTry;
    // films and series: their history and favourites follow by name too (VodData.stash / carryOver)
    private static volatile boolean vodPending=true,vodMigrating;
    private static volatile long vodLastTry;
    /** The subscription the data on this TV belongs to now. */
    static String stamp(Context c){return live(c).getString(STAMP,"");}

    static String key(String server,String user){return Integer.toHexString((server+"|"+user).hashCode());}
    static String key(XtreamAccount a){return key(a.server,a.user);}
    private static SharedPreferences live(Context c){return c.getApplicationContext().getSharedPreferences("ouswis-live",Context.MODE_PRIVATE);}

    /** Called whenever the login is read: if the data on this TV was made for another subscription, it is switched first. */
    static void sync(Context c,XtreamAccount a){
        if(a==null||c==null)return;
        final Context app=c.getApplicationContext()!=null?c.getApplicationContext():c;
        String now=key(a);
        if(!now.equals(synced)){
            synchronized(LOCK){
                String was=live(app).getString(STAMP,"");
                if(!was.equals(now))switchTo(app,was,now);
                synced=now;
            }
        }
        if(pending&&!migrating&&System.currentTimeMillis()-lastTry>60_000L)migrate(app,a);
        if(vodPending&&!vodMigrating&&System.currentTimeMillis()-vodLastTry>10*60_000L)migrateVod(app,a);
    }

    /** The subscription was removed: nothing of it stays. */
    static void wipeAll(Context c){
        final Context app=c.getApplicationContext()!=null?c.getApplicationContext():c;
        synchronized(LOCK){
            wipe(app,"");
            SharedPreferences.Editor e=live(app).edit();
            e.remove(STAMP).remove(STASH);e.commit();
            synced="";pending=false;
            MatchChannels.reset();
            PlayLog.clear(app);
        }
    }

    private static boolean owned(String n){return n.startsWith("live-")||n.startsWith("guide-day-")||n.startsWith("xtream-catalog-cache")||n.startsWith("vod-")||n.startsWith("series-");}
    private static boolean belongsTo(String n,String key){
        if(key.isEmpty())return false;
        if(n.startsWith("vod-")||n.startsWith("series-"))return n.contains("-"+key+".")||n.contains("-"+key+"-");   // films and series (VodData): vod-cat-KEY-ID.json, series-newest-KEY.json...
        return n.equals("live-categories-"+key+".json")||n.startsWith("live-cat-"+key+"-")||n.equals("guide-day-"+key+".json");
    }
    /** Deletes the subscription files and preference keys that are not {@code keep}'s. */
    private static void wipe(Context app,String keep){
        File[] files=app.getFilesDir().listFiles();
        if(files!=null)for(File f:files){String n=f.getName();if(owned(n)&&!belongsTo(n,keep))f.delete();}
        SharedPreferences.Editor e=live(app).edit();
        for(String k:KEYS)e.remove(k);
        e.commit();
        XtreamAccount.prefs(app).edit().remove("lastChannel").remove("lastChannelName").commit();
        // films and series: where each stopped and the favourites are kept per subscription (its key at the end of the name)
        SharedPreferences vod=app.getSharedPreferences("ouswis-vod",Context.MODE_PRIVATE);
        SharedPreferences.Editor ve=vod.edit();
        for(String k:vod.getAll().keySet())if(keep.isEmpty()||!k.endsWith("-"+keep))ve.remove(k);
        ve.commit();
    }

    private static void switchTo(Context app,String from,String to){
        SharedPreferences s=live(app);
        JSONArray stash=names(app,s);
        JSONObject vod=VodData.stash(app,from);   // names only, read before the old keys go
        wipe(app,to);
        VodData.putStash(app,to,vod);
        vodPending=VodData.stashSize(vod)>0;vodLastTry=0;
        s.edit().putString(STAMP,to).putString(STASH,stash.toString()).commit();
        pending=stash.length()>0;
        if(!from.isEmpty()||stash.length()>0){
            PlayLog.clear(app);
            PlayLog.add(app,0,"تغيير الاشتراك: مُسحت بيانات الاشتراك القديم ("+(pending?"المفضلة والأخيرة تُنقل بالاسم":"لا شيء يُنقل")+")");
        }
        MatchChannels.reset();
    }

    /** Names only, from the favourites, the recents, the last channel and what an earlier switch had not carried over yet. */
    private static JSONArray names(Context app,SharedPreferences s){
        JSONArray out=new JSONArray();
        Set<String> seen=new HashSet<>();
        try{
            JSONArray old=new JSONArray(s.getString(STASH,"[]"));
            for(int i=0;i<old.length();i++)add(out,seen,old.optJSONObject(i));
        }catch(Exception ignored){}
        for(String kind:new String[]{"favorites","recent"}){
            try{
                JSONArray a=new JSONArray(s.getString(kind,"[]"));
                for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o!=null&&!o.optString("name").trim().isEmpty())add(out,seen,new JSONObject().put("k",kind).put("n",o.optString("name")));}
            }catch(Exception ignored){}
        }
        try{
            LiveData.Channel last=LiveData.Channel.from(new JSONObject(s.getString("last","{}")));
            String name=last!=null?last.name:XtreamAccount.prefs(app).getString("lastChannelName","");
            if(name!=null&&!name.trim().isEmpty())add(out,seen,new JSONObject().put("k","last").put("n",name));
        }catch(Exception ignored){}
        return out;
    }
    private static void add(JSONArray out,Set<String> seen,JSONObject o){
        if(o==null)return;
        String k=o.optString("k"),n=o.optString("n");
        if(k.isEmpty()||n.trim().isEmpty()||!seen.add(k+"\n"+n))return;
        try{out.put(new JSONObject().put("k",k).put("n",n));}catch(Exception ignored){}
    }

    // MARK: matching by name

    /** A channel's name for matching: no prefix (FR|, 8K:), no quality tag, lower case, plain spaces. */
    static String canon(String name){
        return Titles.channelName(name==null?"":name).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+"," ").trim();
    }
    private static String prefixOf(String name){
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("^\\s*([A-Za-z0-9]{2,3})\\s*[|:]").matcher(name==null?"":name);
        return m.find()?m.group(1).toUpperCase(Locale.ROOT):"";
    }
    /** The closest channel of the new subscription: the same name, then the same prefix, then the same quality. */
    static LiveData.Channel best(String name,List<LiveData.Channel> candidates){
        LiveData.Channel pick=null;int bestScore=Integer.MAX_VALUE;
        for(LiveData.Channel c:candidates){
            int score=(c.name.trim().equalsIgnoreCase(name.trim())?0:4)+(prefixOf(c.name).equals(prefixOf(name))?0:2)+(Titles.qualityPenalty(c.name)==Titles.qualityPenalty(name)?0:1);
            if(score<bestScore){bestScore=score;pick=c;}
        }
        return pick;
    }

    private static void migrate(final Context app,final XtreamAccount a){
        migrating=true;lastTry=System.currentTimeMillis();
        new Thread(()->{
            try{
                SharedPreferences s=live(app);
                final String forKey=key(a);
                JSONArray st=new JSONArray(s.getString(STASH,"[]"));
                if(st.length()==0){pending=false;return;}
                Set<String> wanted=new HashSet<>();
                for(int i=0;i<st.length();i++)wanted.add(canon(st.getJSONObject(i).optString("n")));
                Map<String,List<LiveData.Channel>> found=streamLive(a,wanted);
                JSONArray fav=new JSONArray(),recent=new JSONArray();JSONObject last=null;
                Set<String> inFav=new HashSet<>(),inRecent=new HashSet<>();
                for(int i=0;i<st.length();i++){
                    JSONObject o=st.getJSONObject(i);
                    List<LiveData.Channel> cands=found.get(canon(o.optString("n")));
                    if(cands==null||cands.isEmpty())continue;   // no such channel in the new subscription: only this one goes
                    LiveData.Channel ch=best(o.optString("n"),cands);
                    if(ch==null)continue;
                    String k=o.optString("k");
                    if("favorites".equals(k)&&inFav.add(ch.id))fav.put(ch.json());
                    else if("recent".equals(k)&&inRecent.add(ch.id))recent.put(ch.json());
                    else if("last".equals(k))last=ch.json();
                }
                synchronized(LOCK){
                    if(!forKey.equals(live(app).getString(STAMP,"")))return;   // the subscription changed again meanwhile: the next switch does it
                    SharedPreferences.Editor e=live(app).edit();
                    // what the viewer added since the switch stays in front
                    e.putString("favorites",joined(s.getString("favorites","[]"),fav,200));
                    e.putString("recent",joined(s.getString("recent","[]"),recent,20));
                    if(last!=null&&s.getString("last","").isEmpty())e.putString("last",last.toString());
                    e.remove(STASH);e.commit();
                    pending=false;
                }
            }catch(Exception e){AppLog.w("account data carry-over failed, tries again");}
            finally{migrating=false;}
        },"ouswis-account-carry").start();
    }
    /** The films and series history: matched by name in the background; tried again in 10 minutes when the subscription can't be read, three times at most. */
    private static void migrateVod(final Context app,final XtreamAccount a){
        if(!VodData.hasStash(app,key(a))){vodPending=false;return;}
        vodMigrating=true;vodLastTry=System.currentTimeMillis();
        new Thread(()->{
            try{
                String done=VodData.carryOver(app,a,LOCK);
                vodPending=false;
                if(!done.isEmpty())PlayLog.add(app,0,"تغيير الاشتراك: نُقل سجل الأفلام والمسلسلات ومفضلتها بالاسم: "+done);
            }catch(Exception e){
                AppLog.w("films and series carry-over failed, tries again");
                VodData.giveUpIfTried(app,key(a));
                if(!VodData.hasStash(app,key(a))){vodPending=false;PlayLog.add(app,0,"تغيير الاشتراك: تعذّر نقل سجل الأفلام والمسلسلات بعد 3 محاولات");}
            }finally{vodMigrating=false;}
        },"ouswis-vod-carry").start();
    }
    private static String joined(String now,JSONArray carried,int cap){
        JSONArray out=new JSONArray();Set<String> ids=new HashSet<>();
        try{
            JSONArray cur=new JSONArray(now);
            for(int i=0;i<cur.length()&&out.length()<cap;i++){JSONObject o=cur.optJSONObject(i);if(o!=null&&ids.add(o.optString("id")))out.put(o);}
        }catch(Exception ignored){}
        for(int i=0;i<carried.length()&&out.length()<cap;i++){JSONObject o=carried.optJSONObject(i);if(o!=null&&ids.add(o.optString("id")))out.put(o);}
        return out.toString();
    }

    /** Reads the whole live list as a stream (it can be tens of megabytes) and keeps only the channels whose clean name is wanted. */
    private static Map<String,List<LiveData.Channel>> streamLive(XtreamAccount a,Set<String> wanted) throws Exception {
        Map<String,List<LiveData.Channel>> out=new HashMap<>();
        HttpURLConnection c=(HttpURLConnection)new URL(a.api("get_live_streams",null)).openConnection();
        c.setConnectTimeout(15000);c.setReadTimeout(60000);c.setInstanceFollowRedirects(true);
        try{
            if(c.getResponseCode()!=200)throw new Exception("HTTP "+c.getResponseCode());
            try(JsonReader r=new JsonReader(new InputStreamReader(c.getInputStream(),"UTF-8"))){
                r.beginArray();
                while(r.hasNext()){
                    String id="",name="",icon="",num="",ext="ts";
                    r.beginObject();
                    while(r.hasNext()){
                        String f=r.nextName();
                        JsonToken t=r.peek();
                        if(t!=JsonToken.STRING&&t!=JsonToken.NUMBER){r.skipValue();continue;}
                        String v=r.nextString();
                        switch(f){
                            case "stream_id":id=v;break;
                            case "name":name=v;break;
                            case "stream_icon":icon=v;break;
                            case "num":num=v;break;
                            case "container_extension":if(!v.isEmpty())ext=v;break;
                            default:break;
                        }
                    }
                    r.endObject();
                    if(id.isEmpty()||name.trim().isEmpty())continue;
                    String k=canon(name);
                    if(!wanted.contains(k))continue;
                    List<LiveData.Channel> l=out.get(k);
                    if(l==null){l=new ArrayList<>();out.put(k,l);}
                    if(l.size()<20)l.add(new LiveData.Channel(id,name,icon,num,ext));
                }
                r.endArray();
            }
        }finally{c.disconnect();}
        return out;
    }
}
