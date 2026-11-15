package io.ouswis.tv.standalone;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * A club's crest. The matches feed's own logo comes first, exactly as before; only when the feed gave none (or its
 * address does not load) the badge is searched by the team's English name on TheSportsDB (searchteams.php?t=). What is
 * found is kept on the device: the name -> badge address in the settings file (a name with no badge is not asked for
 * again for a week) and the picture in {@link ImageCache}'s disk copy (128 px at most). Lookups run one at a time in the
 * background, spaced out, so with the three image downloads there are never more than four requests at once.
 */
final class ClubBadges {
    private ClubBadges(){}
    private static final long NONE_MS=7L*24*3600_000,RETRY_MS=10*60_000L,GAP_MS=1200;
    private static final String PREFS="ouswis-badges";
    private static final ExecutorService LOOKUP=Executors.newSingleThreadExecutor();
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final java.util.Map<String,String> BADGE=new java.util.concurrent.ConcurrentHashMap<>();   // name key -> badge address
    private static final java.util.Map<String,Long> NONE=new java.util.concurrent.ConcurrentHashMap<>(),SOFT=new java.util.concurrent.ConcurrentHashMap<>();   // no badge (week) / lookup failed (minutes)
    private static final Set<String> QUEUED=new HashSet<>(),FEED_FAILED=new HashSet<>();
    private static volatile boolean loaded,loading;
    private static long lastLookup;

    /** Normalized in the way the key and the comparison need: lower case, no accents, letters and digits only. */
    static String key(String name){
        if(name==null)return "";
        String s=Normalizer.normalize(name,Normalizer.Form.NFD).replaceAll("\\p{M}+","").toLowerCase(Locale.ROOT);
        return s.replaceAll("[^a-z0-9]","");
    }

    /** The feed's addresses are sometimes plain http for ESPN's own image host: that host answers https. */
    static String secure(String url){
        if(url!=null&&url.startsWith("http://")&&url.contains("espncdn.com"))return "https://"+url.substring(7);
        return url;
    }

    /**
     * The crest to draw, or null while it loads (then `done` runs on the UI thread). `url` is what the feed gave
     * ("" when nothing), `name` the team's English name.
     */
    static Bitmap get(String url,String name,Runnable done){
        url=secure(url);
        if(url!=null&&!url.isEmpty()){
            Bitmap b=ImageCache.get(url,done);
            if(b!=null)return b;
            if(!ImageCache.failed(url)&&!FEED_FAILED.contains(url))return null;   // still loading: the feed's logo comes first
            FEED_FAILED.add(url);
        }
        return byName(name,done);
    }

    private static Bitmap byName(String name,Runnable done){
        String key=key(name);
        if(key.length()<3||name==null||!name.matches(".*[A-Za-z].*"))return null;   // an Arabic name is no key for this search
        if(!loaded){load(done);return null;}
        String badge=BADGE.get(key);
        if(badge!=null)return ImageCache.get(badge,done);
        long now=System.currentTimeMillis();
        Long none=NONE.get(key);if(none!=null&&now-none<NONE_MS)return null;
        Long soft=SOFT.get(key);if(soft!=null&&now-soft<RETRY_MS)return null;
        synchronized(QUEUED){if(!QUEUED.add(key))return null;}
        final String n=name;
        LOOKUP.execute(()->lookup(key,n,done));
        return null;
    }

    private static SharedPreferences prefs(){
        Context c=Prefs.context();
        return c==null?null:c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
    }

    /** The saved answers are read once, off the UI thread. */
    private static void load(Runnable done){
        synchronized(QUEUED){if(loading)return;loading=true;}
        LOOKUP.execute(()->{
            try{
                SharedPreferences p=prefs();
                if(p!=null)for(java.util.Map.Entry<String,?> e:p.getAll().entrySet()){
                    String v=String.valueOf(e.getValue());
                    if(v.startsWith("none:")){try{NONE.put(e.getKey(),Long.parseLong(v.substring(5)));}catch(NumberFormatException ignored){}}
                    else if(v.startsWith("http"))BADGE.put(e.getKey(),v);
                }
            }catch(Throwable ignored){}
            loaded=true;
            if(done!=null)MAIN.post(done);
        });
    }

    private static void lookup(String key,String name,Runnable done){
        try{
            java.util.List<String> variants=variants(name);
            String badge=null,why="";
            int asked=0;
            for(String v:variants){
                long wait=lastLookup+GAP_MS-System.currentTimeMillis();
                if(wait>0)try{Thread.sleep(wait);}catch(InterruptedException e){Thread.currentThread().interrupt();throw e;}
                lastLookup=System.currentTimeMillis();
                asked++;
                badge=search(v,variants);
                if(badge!=null){why=v.equals(name)?"":" · بالصيغة: "+v;break;}
            }
            SharedPreferences p=prefs();
            if(badge==null){
                NONE.put(key,System.currentTimeMillis());
                if(p!=null)p.edit().putString(key,"none:"+System.currentTimeMillis()).apply();
                note("شعار "+name+": ما لقى في TheSportsDB (جُرّبت "+asked+" صيغ)");
            }else{
                BADGE.put(key,badge);
                if(p!=null)p.edit().putString(key,badge).apply();
                note("شعار "+name+": لقى في TheSportsDB"+why);
                ImageCache.get(badge,done);   // the picture itself, in the image pool
            }
        }catch(Throwable t){
            SOFT.put(key,System.currentTimeMillis());   // no network, a busy server: asked again in a few minutes
            note("شعار "+name+": خطأ في البحث ("+TheError.of(t)+")");
        }finally{
            synchronized(QUEUED){QUEUED.remove(key);}
            if(done!=null)MAIN.post(done);
        }
    }

    /** One line in the playback log per club looked up: found, not found, or an error. */
    private static void note(String line){
        try{Context c=Prefs.context();if(c!=null)PlayLog.add(c,-1,line);}catch(Throwable ignored){}
    }
    private static final class TheError{
        static String of(Throwable t){
            String m=t.getMessage();
            if(t instanceof java.net.SocketTimeoutException)return "انتهت المهلة";
            if(m!=null&&m.contains("answered "))return "HTTP "+m.substring(m.indexOf("answered ")+9).trim();
            return t.getClass().getSimpleName();
        }
    }

    private static final java.util.regex.Pattern AFFIX=java.util.regex.Pattern.compile("(?i)^(?:fc|cf|afc|sc|ac|as|us|ss|sv|fk|sk|bk|if|ud|cd|ca|rc|rcd|sd|ssc|1\\.|1)$");
    /** The name as given, then without FC / CF / AC / AS / US ... at either end, then without numbers ("Mainz 05" -> "Mainz"). */
    static java.util.List<String> variants(String name){
        java.util.LinkedHashMap<String,String> out=new java.util.LinkedHashMap<>();
        String clean=name.replaceAll("[.]"," ").replaceAll("\\s+"," ").trim();
        out.put(key(name),name.trim());
        java.util.List<String> words=new java.util.ArrayList<>(java.util.Arrays.asList(clean.split(" ")));
        java.util.List<String> noAffix=new java.util.ArrayList<>(words);
        while(noAffix.size()>1&&AFFIX.matcher(noAffix.get(0)).matches())noAffix.remove(0);
        while(noAffix.size()>1&&AFFIX.matcher(noAffix.get(noAffix.size()-1)).matches())noAffix.remove(noAffix.size()-1);
        String a=String.join(" ",noAffix);
        if(key(a).length()>=3)out.putIfAbsent(key(a),a);
        java.util.List<String> noDigits=new java.util.ArrayList<>();
        for(String w:noAffix)if(!w.matches("\\d+"))noDigits.add(w);
        String d=String.join(" ",noDigits);
        if(!noDigits.isEmpty()&&key(d).length()>=3)out.putIfAbsent(key(d),d);
        return new java.util.ArrayList<>(out.values());
    }

    /** True when this team has no crest to find: nothing to search with, or TheSportsDB has none (the screens draw a shield instead of a letter). */
    static boolean none(String url,String name){
        url=secure(url);
        if(url!=null&&!url.isEmpty()&&!FEED_FAILED.contains(url)&&!ImageCache.failed(url))return false;   // the feed's own logo is still loading
        String key=key(name);
        if(key.length()<3||name==null||!name.matches(".*[A-Za-z].*"))return true;
        Long t=NONE.get(key);
        return t!=null&&System.currentTimeMillis()-t<NONE_MS&&!BADGE.containsKey(key);
    }

    private static String base(){
        try{   // only the emulator test points it at a local mock (a plain http://127.0.0.1 address saved in the live prefs)
            String b=Prefs.context().getSharedPreferences("ouswis-live",Context.MODE_PRIVATE).getString("sportsDbBase","");
            if(b.startsWith("http://127.0.0.1"))return b;
        }catch(RuntimeException ignored){}
        return "https://www.thesportsdb.com";
    }

    /** The badge address for this team, null when TheSportsDB has none; an exception when it could not be asked. */
    private static String search(String name,java.util.List<String> variants) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(base()+"/api/v1/json/3/searchteams.php?t="+URLEncoder.encode(name,"UTF-8")).openConnection();
        c.setConnectTimeout(6000);c.setReadTimeout(8000);c.setInstanceFollowRedirects(false);
        try{
            int code=c.getResponseCode();
            if(code==404)return null;
            if(code!=200)throw new java.io.IOException("TheSportsDB answered "+code);
            java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
            try(java.io.InputStream in=c.getInputStream()){
                byte[] buf=new byte[4096];int n;
                while((n=in.read(buf))!=-1){out.write(buf,0,n);if(out.size()>512*1024)return null;}
            }
            JSONArray teams=new JSONObject(out.toString("UTF-8")).optJSONArray("teams");
            java.util.Set<String> keys=new java.util.HashSet<>();for(String v:variants)keys.add(key(v));
            return pick(teams,keys);
        }finally{c.disconnect();}
    }

    /** A football team whose name is the one asked (or one of its alternate names); never a different club with a similar name. */
    static String pick(JSONArray teams,String key){return pick(teams,java.util.Collections.singleton(key));}
    static String pick(JSONArray teams,java.util.Set<String> keys){
        if(teams==null)return null;
        String best=null;
        for(int i=0;i<teams.length();i++){
            JSONObject t=teams.optJSONObject(i);if(t==null)continue;
            String sport=t.optString("strSport","Soccer");
            if(!sport.isEmpty()&&!sport.equalsIgnoreCase("Soccer")&&!sport.equalsIgnoreCase("Football"))continue;
            String badge=t.optString("strBadge","");
            if(badge.isEmpty()||"null".equals(badge))badge=t.optString("strTeamBadge","");
            if(badge.isEmpty()||"null".equals(badge)||!badge.startsWith("http"))continue;
            boolean same=keys.contains(key(t.optString("strTeam")));
            if(!same){
                String alt=t.optString("strAlternate","");
                if(!alt.isEmpty()&&!"null".equals(alt))for(String a:alt.split(","))if(keys.contains(key(a))){same=true;break;}
            }
            if(!same)continue;
            best=badge;break;
        }
        if(best==null)return null;
        // TheSportsDB serves a 200 px copy at /small; a local mock has no such path
        return best.contains("127.0.0.1")||best.endsWith("/small")||best.endsWith("/tiny")?best:best+"/small";
    }
}
