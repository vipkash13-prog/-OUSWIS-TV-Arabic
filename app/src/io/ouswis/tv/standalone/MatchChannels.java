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
 * Links a match to a channel of the viewer's own Xtream subscription (SPEC section 5), and only when it is confirmed:
 * by the programme guide of the sports channels (a title naming both teams), by what the feed itself says carries the
 * match, or by the channel the viewer picked by hand for the league. With none of the three there is no channel: the
 * screens say "اختر القناة" and never guess one. The guide is also a second source of matches (see {@link #withGuide}).
 */
final class MatchChannels {
    private MatchChannels(){}
    private static final Pattern QUALITY=Pattern.compile("(?i)\\b(FHD|UHD|HD|SD|4K)\\b");

    /** The leagues the settings table lists: {name shown, text that identifies it in a match's competition}. */
    static final String[][] LEAGUES={
        {"دوري روشن السعودي","روشن"},{"كأس الملك","كأس الملك"},{"دوري أبطال آسيا","أبطال آسيا"},
        {"دوري أبطال أوروبا","أبطال أوروبا"},{"الدوري الإسباني","الإسباني"},{"الدوري الإنجليزي","الإنجليزي"},
        {"الدوري الإيطالي","الإيطالي"},{"الدوري الألماني","الألماني"},{"الدوري الفرنسي","الفرنسي"},{"كأس العالم","كأس العالم"}};

    private static SharedPreferences store(Context c){return c.getApplicationContext().getSharedPreferences("ouswis-live",Context.MODE_PRIVATE);}

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
    /** Remember a channel for a league (key = the identifying text); null forgets it (then only the guide or the feed can name a channel). */
    static void pick(Context c,String key,LiveData.Channel ch){
        try{
            JSONObject picks=new JSONObject(store(c).getString("picks","{}"));
            if(ch==null)picks.remove(key);else picks.put(key,ch.json());
            store(c).edit().putString("picks",picks.toString()).apply();
        }catch(Exception ignored){}
        clearAnswers();
        fire();
    }
    /** The channel the viewer played from the sports list for this very match (saved by {@link #pickMatch}). */
    static LiveData.Channel matchPick(Context c,String matchKey){
        try{return LiveData.Channel.from(new JSONObject(store(c).getString("matchPicks","{}")).optJSONObject(matchKey));}catch(Exception e){return null;}
    }
    static void pickMatch(Context c,String matchKey,LiveData.Channel ch){
        if(matchKey==null||matchKey.isEmpty()||ch==null)return;
        try{
            JSONObject picks=new JSONObject(store(c).getString("matchPicks","{}"));
            if(picks.length()>60)picks=new JSONObject();   // old matches don't matter
            picks.put(matchKey,ch.json());
            store(c).edit().putString("matchPicks",picks.toString()).apply();
        }catch(Exception ignored){}
        clearAnswers();fire();
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
    private static final java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final java.util.List<Runnable> listeners=new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final android.os.Handler main=new android.os.Handler(android.os.Looper.getMainLooper());
    /** A screen that shows channels asks to be told when a lookup finished or the table changed. */
    static void listen(Runnable r){if(!listeners.contains(r))listeners.add(r);}
    static void unlisten(Runnable r){listeners.remove(r);}
    private static void fire(){main.post(()->{for(Runnable r:listeners)r.run();});}
    /** The files changed (a category finished downloading): earlier "no channel" answers may be wrong now. */
    static void invalidate(){clearAnswers();guideAt=0;fire();}
    private static void clearAnswers(){confirmed.clear();merged=null;}

    // MARK: the guide decides

    private static final long GUIDE_TTL=12*60_000L;
    private static final java.util.Map<String,java.util.List<LiveData.Program>> guides=new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<String,LiveData.Channel> guideChannels=new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile long guideAt;
    private static volatile boolean scanning;
    static final long KICK_WINDOW=30*60_000L;
    private static final int GUIDE_CAP=170,GUIDE_PROGRAMMES=12;
    private static final java.util.Map<String,Object> confirmed=new java.util.concurrent.ConcurrentHashMap<>();   // match key → channel | NONE

    /**
     * Reads the guide of the sports channels already downloaded (at most {@value #GUIDE_CAP}, the next
     * {@value #GUIDE_PROGRAMMES} programmes each, four at a time): {@link #fromGuide} looks for the teams' names in
     * those titles and {@link #withGuide} turns match titles the ESPN feed doesn't have into matches. Repeats at most
     * every 12 minutes; never on the UI thread; the listeners are told when it finished.
     */
    static void scanGuides(Context c,XtreamAccount a){
        if(a==null||scanning||System.currentTimeMillis()-guideAt<GUIDE_TTL)return;
        scanning=true;
        final Context app=c.getApplicationContext();
        worker.execute(()->{
            try{
                java.util.List<LiveData.Channel> list=sportChannels(app,a);
                java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(6);
                java.util.List<java.util.concurrent.Future<?>> jobs=new ArrayList<>();
                for(LiveData.Channel ch:list)jobs.add(pool.submit(()->{
                    try{guides.put(ch.id,LiveData.fetchGuide(a,ch.id,GUIDE_PROGRAMMES));guideChannels.put(ch.id,ch);}catch(Exception ignored){}
                }));
                for(java.util.concurrent.Future<?> j:jobs){try{j.get(40,java.util.concurrent.TimeUnit.SECONDS);}catch(Exception ignored){}}
                pool.shutdownNow();
            }catch(Throwable ignored){}
            // nothing read (no sports files yet): try again in a minute rather than in twelve
            guideAt=guides.isEmpty()?System.currentTimeMillis()-GUIDE_TTL+60_000L:System.currentTimeMillis();scanning=false;
            clearAnswers();
            fire();
        });
    }
    /** Channels of the sports categories already downloaded (the ones {@link #prepare} chose, else every downloaded one); the cap keeps the scan short. */
    private static java.util.List<LiveData.Channel> sportChannels(Context c,XtreamAccount a){
        java.util.List<LiveData.Channel> out=new ArrayList<>();
        String key=Integer.toHexString((a.server+"|"+a.user).hashCode());
        String only=store(c).getString("sportCats","");
        File[] files=c.getFilesDir().listFiles();
        if(files==null)return out;
        for(File f:files){
            String n=f.getName(),prefix="live-cat-"+key+"-";
            if(!n.startsWith(prefix)||!n.endsWith(".json"))continue;
            if(!only.isEmpty()&&!(","+only+",").contains(","+n.substring(prefix.length(),n.length()-5)+","))continue;
            try{
                JSONArray items=new JSONObject(new String(Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8)).getJSONArray("items");
                for(int i=0;i<items.length();i++){
                    LiveData.Channel ch=LiveData.Channel.from(items.optJSONObject(i));
                    if(ch!=null)out.add(ch);
                }
            }catch(Exception ignored){}
        }
        // sports channels by name first, so the cap never leaves out beIN or SSC
        java.util.Collections.sort(out,(x,y)->Integer.compare(sportRank(x.name),sportRank(y.name)));
        return out.size()>GUIDE_CAP?new ArrayList<>(out.subList(0,GUIDE_CAP)):out;
    }

    /** True once a guide scan has finished (even one that found nothing). */
    static boolean guideReady(){return guideAt!=0&&!scanning;}
    /** The ids of the sports sections {@link #prepare} chose (empty when it hasn't run). */
    static java.util.Set<String> sportCategoryIds(Context c){
        java.util.Set<String> out=new java.util.HashSet<>();
        for(String id:store(c).getString("sportCats","").split(","))if(!id.isEmpty())out.add(id);
        return out;
    }
    /** 0 for beIN / SSC / Arab sports channels, 1 for other "sport" names, 2 for the rest. */
    static int sportRank(String name){
        String n=name==null?"":name.toLowerCase(java.util.Locale.ROOT);
        if(n.contains("bein")||n.contains("be in")||n.contains("ssc")||n.contains("بي ان")||n.contains("بين")||n.contains("الكاس"))return 0;
        if(n.contains("sport")||n.contains("سبورت")||n.contains("رياض")||n.contains("match")||n.contains("dazn")||n.contains("arena")||n.contains("ad sports")||n.contains("abu dhabi"))return 1;
        return 2;
    }
    /** The first beIN or SSC channel's index in a list (else the first sports-looking one, else 0). */
    static int firstSports(List<LiveData.Channel> list){
        int best=-1,bestRank=3;
        for(int i=0;i<list.size();i++){int r=sportRank(list.get(i).name);if(r<bestRank){bestRank=r;best=i;if(r==0)break;}}
        return Math.max(0,best);
    }

    /** The sports channel whose programme (current, or coming up near the kickoff) names both teams, or null. Uses only what the last scan read. */
    static LiveData.Channel fromGuide(MatchCarouselView.Match m){
        if(m==null||m.homeEn.isEmpty()||m.awayEn.isEmpty())return null;
        long now=System.currentTimeMillis(),kick=m.kickoffMillis;
        LiveData.Channel best=null;int bestScore=Integer.MAX_VALUE;
        for(java.util.Map.Entry<String,java.util.List<LiveData.Program>> e:guides.entrySet()){
            for(LiveData.Program p:e.getValue()){
                if(p.end<now)continue;
                if(kick>0&&Math.abs(p.start-kick)>KICK_WINDOW)continue;   // the programme starts within 30 minutes of the kickoff
                if(Titles.isRerun(p.title))continue;
                if(!Titles.sameMatch(p.title,m.homeEn,m.home,m.awayEn,m.away))continue;
                LiveData.Channel ch=guideChannels.get(e.getKey());
                if(ch==null)continue;
                int score=number(canonical(ch.name));
                if(score<bestScore){bestScore=score;best=ch;}
            }
        }
        return best;
    }
    /** A channel the feed itself names for the match ("beIN SPORTS 2"), when the subscription has exactly that channel. */
    static LiveData.Channel fromBroadcast(MatchCarouselView.Match m){
        if(m==null||m.broadcasts.isEmpty())return null;
        for(String name:m.broadcasts){
            String want=canonical(name);
            if(want.isEmpty())continue;
            for(LiveData.Channel ch:guideChannels.values())if(canonical(ch.name).equals(want))return ch;
        }
        return null;
    }
    /**
     * The channel for a match, only when confirmed: a guide match (or the channel an EPG-made match came from), the
     * feed's own broadcaster, or the channel picked by hand for its league. Otherwise null, and the screens ask the
     * viewer to pick one. Answers at once from memory.
     */
    static LiveData.Channel forMatch(Context c,XtreamAccount a,MatchCarouselView.Match m){
        scanGuides(c,a);
        if(m==null)return null;
        if(!m.epgChannelId.isEmpty())return guideChannels.get(m.epgChannelId);
        LiveData.Channel manual=matchPick(c,m.key);
        if(manual!=null)return manual;
        Object got=confirmed.get(m.key);
        if(got!=null)return got==NONE?null:(LiveData.Channel)got;
        LiveData.Channel ch=fromGuide(m);
        if(ch==null)ch=fromBroadcast(m);
        if(ch==null)ch=picked(c,m.competition);
        if(!scanning&&!guides.isEmpty())confirmed.put(m.key,ch==null?NONE:ch);   // before the first scan ends, answers aren't final
        return ch;
    }

    // MARK: a second source of matches: titles in the guide that read "team × team"

    private static volatile java.util.List<MatchCarouselView.Match> merged;
    private static volatile Object mergedFor;
    /**
     * The ESPN matches plus the matches found in the sports channels' guides (a title "A × B - League" or "A vs B"):
     * each with the programme's start as its kickoff, the league from the title and the channel it was found on. A
     * guide match is left out when the feed already has the same two teams around that time, and the same match on
     * two channels counts once (the lowest channel number wins). Cached until the feed list or the scan changes.
     */
    static java.util.List<MatchCarouselView.Match> withGuide(java.util.List<MatchCarouselView.Match> api){
        Object stamp=System.identityHashCode(api)+":"+api.size()+":"+guideAt+":"+(api.isEmpty()?"":api.get(0).key+api.get(api.size()-1).key);
        java.util.List<MatchCarouselView.Match> cached=merged;
        if(cached!=null&&stamp.equals(mergedFor))return cached;
        java.util.List<MatchCarouselView.Match> out=new ArrayList<>(api);
        long now=System.currentTimeMillis();
        java.util.Map<String,MatchCarouselView.Match> found=new java.util.LinkedHashMap<>();
        java.util.Map<String,Integer> rank=new java.util.HashMap<>();
        for(java.util.Map.Entry<String,java.util.List<LiveData.Program>> e:guides.entrySet()){
            LiveData.Channel ch=guideChannels.get(e.getKey());
            if(ch==null)continue;
            for(LiveData.Program p:e.getValue()){
                long len=p.end-p.start;
                if(p.end<now||len<60*60_000L||len>240*60_000L||Titles.isRerun(p.title))continue;
                String[] parts=Titles.parseMatch(p.title);
                if(parts==null)continue;
                boolean dup=false;
                for(MatchCarouselView.Match x:api){
                    if(Math.abs(x.kickoffMillis-p.start)>2*3600_000L)continue;
                    if(Titles.sameMatch(p.title,x.homeEn,x.home,x.awayEn,x.away)){dup=true;break;}
                }
                if(dup)continue;
                String id=Titles.key(Titles.teamArabic(parts[0]))+"|"+Titles.key(Titles.teamArabic(parts[1]))+"|"+(p.start/(3600_000L));
                int r=number(canonical(ch.name))*1000+(ch.name.hashCode()&511);   // ties go the same way every time
                Integer had=rank.get(id);
                if(had!=null&&had<=r)continue;
                String home=Titles.teamArabic(parts[0]),away=Titles.teamArabic(parts[1]);
                MatchCarouselView.Match m=new MatchCarouselView.Match(parts[2],home,away,"",p.start);
                m.homeEn=parts[0];m.awayEn=parts[1];m.key="epg:"+id;m.epgChannelId=ch.id;
                m.state=p.start<=now?"in":"pre";
                if(m.live())m.status="مباشر";
                found.put(id,m);rank.put(id,r);
            }
        }
        out.addAll(found.values());
        merged=out;mergedFor=stamp;
        return out;
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
            StringBuilder ids=new StringBuilder();
            for(int i=0;i<Math.min(6,sports.size());i++)ids.append(i>0?",":"").append(sports.get(i).id);
            store(c).edit().putString("sportCats",ids.toString()).apply();
            final int[] left={Math.min(6,sports.size())};
            for(int i=0;i<left[0];i++)data.loadChannels(sports.get(i).id,()->{if(--left[0]==0){invalidate();done.run();}});
        });
    }
}
