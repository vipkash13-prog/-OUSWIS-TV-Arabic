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
                    int score=number(canon)+(favorites.contains(ch.id)?-1000:0);
                    if(score<bestScore){bestScore=score;best=ch;}
                }
            }catch(Exception ignored){}
        }
        return best;
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
