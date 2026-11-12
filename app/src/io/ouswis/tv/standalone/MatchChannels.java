package io.ouswis.tv.standalone;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Links a match to a channel of the viewer's own Xtream subscription (SPEC section 5). ESPN's feed does not name the
 * broadcaster, so a replaceable table maps competition to a channel family ("ssc", "bein"); the family's channels are
 * found by normalised name (HD/FHD/4K/SD and symbols dropped, so "SSC 1 HD" and "ssc1" are the same). When several
 * match: favourites first, then the lowest number. The answer is remembered per match and nothing is shown when the
 * subscription has no such channel.
 */
final class MatchChannels {
    private MatchChannels(){}
    /** Competition (substring of the Arabic name) → channel family. Edit the "map" key in ouswis-live to override. */
    private static final String[][] DEFAULT_MAP={
        {"روشن","ssc"},{"كأس الملك","ssc"},{"أبطال آسيا","ssc"},{"كأس آسيا","ssc"},
        {"أبطال أوروبا","bein"},{"الأوروبي","bein"},{"المؤتمر","bein"},{"الإسباني","bein"},{"إسبانيا","bein"},
        {"الإنجليزي","bein"},{"الإيطالي","bein"},{"الألماني","bein"},{"الفرنسي","bein"},{"كأس العالم","bein"}};
    private static final Pattern QUALITY=Pattern.compile("(?i)\\b(FHD|UHD|HD|SD|4K)\\b");

    /** The leagues the settings table lists: {name shown, text that identifies it in a match's competition}. */
    static final String[][] LEAGUES={
        {"دوري روشن السعودي","روشن"},{"كأس الملك","كأس الملك"},{"دوري أبطال آسيا","أبطال آسيا"},
        {"دوري أبطال أوروبا","أبطال أوروبا"},{"الدوري الإسباني","الإسباني"},{"الدوري الإنجليزي","الإنجليزي"},
        {"الدوري الإيطالي","الإيطالي"},{"الدوري الألماني","الألماني"},{"الدوري الفرنسي","الفرنسي"},{"كأس العالم","كأس العالم"}};

    private static SharedPreferences store(Context c){return c.getApplicationContext().getSharedPreferences("ouswis-live",Context.MODE_PRIVATE);}

    /** Channel family from the editable text map ("map") or the defaults; "" when the competition isn't known. */
    static String family(Context c,String competition){
        try{
            JSONObject custom=new JSONObject(store(c).getString("map","{}"));
            java.util.Iterator<String> keys=custom.keys();
            while(keys.hasNext()){String k=keys.next();if(competition.contains(k))return custom.optString(k);}
        }catch(Exception ignored){}
        for(String[] row:DEFAULT_MAP)if(competition.contains(row[0]))return row[1];
        return "";
    }

    // MARK: the viewer's own picks (a channel chosen by hand for a league)

    /** The channel picked by hand for this competition, or null. Picks are keyed by a league's identifying text. */
    static LiveData.Channel picked(Context c,String competition){
        try{
            JSONObject picks=new JSONObject(store(c).getString("picks","{}"));
            java.util.Iterator<String> keys=picks.keys();
            while(keys.hasNext()){String k=keys.next();if(competition.contains(k))return LiveData.Channel.from(picks.optJSONObject(k));}
        }catch(Exception ignored){}
        return null;
    }
    /** Remember a channel for a league (key = the identifying text); null forgets it and the default applies again. */
    static void pick(Context c,String key,LiveData.Channel ch){
        try{
            JSONObject picks=new JSONObject(store(c).getString("picks","{}"));
            if(ch==null)picks.remove(key);else picks.put(key,ch.json());
            store(c).edit().putString("picks",picks.toString()).apply();
        }catch(Exception ignored){}
        synchronized(memo){memo.clear();}
        fire();
    }
    /** The league text a competition falls under in the settings table, else the competition itself. */
    static String leagueKey(String competition){
        for(String[] l:LEAGUES)if(competition.contains(l[1]))return l[1];
        return competition;
    }

    static String canonical(String name){
        return XtreamCatalog.normalize(QUALITY.matcher(name==null?"":name).replaceAll(""));
    }
    private static int number(String canonical){
        int i=0;while(i<canonical.length()&&!Character.isDigit(canonical.charAt(i)))i++;
        int j=i;while(j<canonical.length()&&Character.isDigit(canonical.charAt(j)))j++;
        try{return i<j?Integer.parseInt(canonical.substring(i,j)):999;}catch(Exception e){return 999;}
    }

    // MARK: lookup (never blocks the UI thread, never holds the catalogue in memory)

    private static final Object NONE=new Object();
    private static final java.util.Map<String,Object> memo=new java.util.HashMap<>();
    private static final java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final java.util.List<Runnable> listeners=new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final android.os.Handler main=new android.os.Handler(android.os.Looper.getMainLooper());
    /** A screen that shows channels asks to be told when a lookup finished or the table changed. */
    static void listen(Runnable r){if(!listeners.contains(r))listeners.add(r);}
    static void unlisten(Runnable r){listeners.remove(r);}
    private static void fire(){main.post(()->{for(Runnable r:listeners)r.run();});}
    /** The files changed (a category finished downloading): earlier "no channel" answers may be wrong now. */
    static void invalidate(){synchronized(memo){memo.clear();}fire();}

    /**
     * The channel for this competition: the one picked by hand for its league, else the best of the default family.
     * Answers at once from memory; the first time for a competition it answers null, looks in the background (one
     * downloaded category file at a time, only the best candidate kept) and calls the listeners when done.
     */
    static LiveData.Channel find(Context c,XtreamAccount a,String competition,java.util.Collection<String> favorites){
        if(competition==null)competition="";
        LiveData.Channel by_hand=picked(c,competition);
        if(by_hand!=null)return by_hand;
        final String family=family(c,competition);
        if(family.isEmpty())return null;
        synchronized(memo){
            Object got=memo.get(family);
            if(got!=null)return got==NONE?null:(LiveData.Channel)got;
            memo.put(family,NONE);   // a lookup is on its way; until then, nothing
        }
        final Context app=c.getApplicationContext();
        worker.execute(()->{
            LiveData.Channel best=search(app,a,family);
            synchronized(memo){memo.put(family,best==null?NONE:best);}
            if(best!=null)fire();
        });
        return null;
    }

    private static LiveData.Channel search(Context c,XtreamAccount a,String family){
        java.util.Set<String> favorites=new java.util.HashSet<>();
        try{
            JSONArray f=new JSONArray(store(c).getString("favorites","[]"));
            for(int i=0;i<f.length();i++)favorites.add(f.getJSONObject(i).optString("id"));
        }catch(Exception ignored){}
        LiveData.Channel best=null;int bestScore=Integer.MAX_VALUE;
        String key=Integer.toHexString((a.server+"|"+a.user).hashCode());
        File[] files=c.getFilesDir().listFiles();
        if(files==null)return null;
        for(File f:files){
            if(!f.getName().startsWith("live-cat-"+key+"-"))continue;
            try{
                JSONArray items=new JSONObject(new String(Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8)).getJSONArray("items");
                for(int i=0;i<items.length();i++){
                    LiveData.Channel ch=LiveData.Channel.from(items.optJSONObject(i));
                    if(ch==null)continue;
                    String canon=canonical(ch.name);
                    if(!canon.startsWith(family))continue;
                    // "beIN SPORTS 2" before "beIN SPORTS XTRA 1" or "beIN SPORTS NEWS": only plain numbered channels are the match channels
                    String core=canon.substring(family.length()).replaceFirst("^sports?","");
                    int score=number(canon)+(core.matches("\\d*")?0:5000)+(favorites.contains(ch.id)?-1000:0);
                    if(score<bestScore){bestScore=score;best=ch;}
                }
            }catch(Exception ignored){}
        }
        return best;
    }

    // MARK: the guide decides (SPEC: a title naming both teams on a sports channel beats the competition's default)

    private static final long GUIDE_TTL=12*60_000L;
    private static final java.util.Map<String,java.util.List<LiveData.Program>> guides=new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<String,LiveData.Channel> guideChannels=new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile long guideAt;
    private static volatile boolean scanning;
    private static final int GUIDE_CAP=90;

    /**
     * Reads the guide of the sports channels already downloaded (at most {@value #GUIDE_CAP}, four programmes each, a
     * few at a time) so {@link #forMatch} can look for the teams' names in the current and next titles. Repeats at most
     * every 12 minutes; never on the UI thread; the listeners are told when it finished.
     */
    static void scanGuides(Context c,XtreamAccount a){
        if(a==null||scanning||System.currentTimeMillis()-guideAt<GUIDE_TTL)return;
        scanning=true;
        final Context app=c.getApplicationContext();
        worker.execute(()->{
            try{
                java.util.List<LiveData.Channel> list=sportChannels(app,a);
                java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(4);
                java.util.List<java.util.concurrent.Future<?>> jobs=new ArrayList<>();
                for(LiveData.Channel ch:list)jobs.add(pool.submit(()->{
                    try{guides.put(ch.id,LiveData.fetchGuide(a,ch.id));guideChannels.put(ch.id,ch);}catch(Exception ignored){}
                }));
                for(java.util.concurrent.Future<?> j:jobs){try{j.get(40,java.util.concurrent.TimeUnit.SECONDS);}catch(Exception ignored){}}
                pool.shutdownNow();
            }catch(Throwable ignored){}
            guideAt=System.currentTimeMillis();scanning=false;
            synchronized(memo){memo.clear();}
            fire();
        });
    }
    /** Channels of the downloaded category files, plain numbered ones of the known families first; the cap keeps the scan short. */
    private static java.util.List<LiveData.Channel> sportChannels(Context c,XtreamAccount a){
        java.util.List<LiveData.Channel> out=new ArrayList<>();
        String key=Integer.toHexString((a.server+"|"+a.user).hashCode());
        File[] files=c.getFilesDir().listFiles();
        if(files==null)return out;
        for(File f:files){
            if(!f.getName().startsWith("live-cat-"+key+"-"))continue;
            try{
                JSONArray items=new JSONObject(new String(Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8)).getJSONArray("items");
                for(int i=0;i<items.length()&&out.size()<GUIDE_CAP;i++){
                    LiveData.Channel ch=LiveData.Channel.from(items.optJSONObject(i));
                    if(ch!=null)out.add(ch);
                }
            }catch(Exception ignored){}
        }
        return out;
    }

    /** The sports channel whose current or next programme names both teams, or null. Uses only what the last scan read. */
    static LiveData.Channel fromGuide(MatchCarouselView.Match m){
        if(m==null||m.homeEn.isEmpty()||m.awayEn.isEmpty())return null;
        long now=System.currentTimeMillis();
        LiveData.Channel best=null;int bestScore=Integer.MAX_VALUE;
        for(java.util.Map.Entry<String,java.util.List<LiveData.Program>> e:guides.entrySet()){
            for(LiveData.Program p:e.getValue()){
                if(p.end<now)continue;
                long kick=m.kickoffMillis;
                if(kick>0&&(p.start>kick+3*3600_000L||p.end<kick-3600_000L))continue;   // a rerun on another day isn't this match
                if(!Titles.mentions(p.title,m.homeEn,m.home)||!Titles.mentions(p.title,m.awayEn,m.away))continue;
                LiveData.Channel ch=guideChannels.get(e.getKey());
                if(ch==null)continue;
                String core=canonical(ch.name);
                int score=number(core)+(core.matches("[a-z]+\\d*|.*sports?\\d*")?0:2000);
                if(score<bestScore){bestScore=score;best=ch;}
            }
        }
        return best;
    }
    /** The channel for a match: the guide first, then the league's picked channel or default family. */
    static LiveData.Channel forMatch(Context c,XtreamAccount a,MatchCarouselView.Match m){
        scanGuides(c,a);
        LiveData.Channel viaGuide=fromGuide(m);
        if(viaGuide!=null)return viaGuide;
        return find(c,a,m.competition,null);
    }

    /** The downloaded section that holds this channel ("" when none does). Reads one file at a time; call off the UI thread. */
    static String sectionOf(Context c,XtreamAccount a,String channelId){
        String prefix="live-cat-"+Integer.toHexString((a.server+"|"+a.user).hashCode())+"-";
        File[] files=c.getFilesDir().listFiles();
        if(files==null||channelId==null)return "";
        for(File f:files){
            String n=f.getName();
            if(!n.startsWith(prefix)||!n.endsWith(".json"))continue;
            try{
                JSONArray items=new JSONObject(new String(Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8)).getJSONArray("items");
                for(int i=0;i<items.length();i++)if(channelId.equals(items.optJSONObject(i).optString("id")))return n.substring(prefix.length(),n.length()-5);
            }catch(Exception ignored){}
        }
        return "";
    }

    private static boolean hasFiles(Context c,XtreamAccount a){
        String key=Integer.toHexString((a.server+"|"+a.user).hashCode());
        File[] files=c.getFilesDir().listFiles();
        if(files!=null)for(File f:files)if(f.getName().startsWith("live-cat-"+key+"-"))return true;
        return false;
    }
    /** Downloads the sports-looking categories once, so the first lookup has something to search. */
    static void prepare(Context c,XtreamAccount a,Runnable done){
        if(hasFiles(c,a)){done.run();return;}
        final LiveData data=new LiveData(c,a);
        final boolean[] started={false};
        data.loadCategories(()->{
            if(started[0])return;
            List<LiveData.Category> sports=new ArrayList<>();
            synchronized(data.categories){
                for(LiveData.Category cat:data.categories){
                    String n=cat.name.toLowerCase(java.util.Locale.ROOT);
                    if(n.contains("sport")||n.contains("ssc")||n.contains("bein")||n.contains("رياض")||n.contains("بي ان")||n.contains("مباريات"))sports.add(cat);
                }
            }
            if(sports.isEmpty()){if(!data.categories.isEmpty()||data.failed)started[0]=true;done.run();return;}
            started[0]=true;
            final int[] left={Math.min(6,sports.size())};
            for(int i=0;i<left[0];i++)data.loadChannels(sports.get(i).id,()->{if(--left[0]==0){invalidate();done.run();}});
        });
    }
}
