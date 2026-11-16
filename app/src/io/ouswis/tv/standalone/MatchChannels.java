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
    private static final Pattern FHD=Pattern.compile("(?i)\\b(fhd|1080p?)\\b"),HD_ONLY=Pattern.compile("(?i)\\bhd\\b");
    private static final Pattern QUALITY=Pattern.compile("(?i)\\b(FHD|UHD|HD|SD|4K|RAW|LQ|HEVC|H\\.?265|X265)\\b");

    /** The leagues the settings table lists: {name shown, text that identifies it in a match's competition}. */
    static final String[][] LEAGUES={
        {"دوري روشن السعودي","روشن"},{"كأس الملك","كأس الملك"},{"دوري أبطال آسيا","أبطال آسيا"},
        {"دوري أبطال أوروبا","أبطال أوروبا"},{"الدوري الإسباني","الإسباني"},{"الدوري الإنجليزي","الإنجليزي"},
        {"الدوري الإيطالي","الإيطالي"},{"الدوري الألماني","الألماني"},{"الدوري الفرنسي","الفرنسي"},{"كأس العالم","كأس العالم"}};

    private static SharedPreferences store(Context c){return c.getApplicationContext().getSharedPreferences("ouswis-live",Context.MODE_PRIVATE);}

    // MARK: the viewer's own picks (a channel chosen by hand for a league)

    /** The channel picked by hand for this competition, or null. Picks are keyed by a league's identifying text. */
    private static volatile JSONObject leaguePicks,matchPicksJson;   // parsed once: the drawing code asks every frame
    private static JSONObject leaguePicks(Context c){
        JSONObject j=leaguePicks;
        if(j==null){try{j=new JSONObject(store(c).getString("picks","{}"));}catch(Exception e){j=new JSONObject();}leaguePicks=j;}
        return j;
    }
    private static JSONObject matchPicks(Context c){
        JSONObject j=matchPicksJson;
        if(j==null){try{j=new JSONObject(store(c).getString("matchPicks3","{}"));}catch(Exception e){j=new JSONObject();}matchPicksJson=j;}
        return j;
    }
    static LiveData.Channel picked(Context c,String competition){
        try{
            JSONObject picks=leaguePicks(c);
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
        leaguePicks=null;
        clearAnswers();
        fire();
    }
    /** The channel the viewer played from the sports list for this very match (saved by {@link #pickMatch}). */
    static LiveData.Channel matchPick(Context c,String matchKey){
        try{return LiveData.Channel.from(matchPicks(c).optJSONObject(matchKey));}catch(Exception e){return null;}
    }
    static void pickMatch(Context c,String matchKey,LiveData.Channel ch){
        if(matchKey==null||matchKey.isEmpty()||ch==null)return;
        try{
            JSONObject picks=new JSONObject(store(c).getString("matchPicks3","{}"));
            if(picks.length()>60)picks=new JSONObject();   // old matches don't matter
            picks.put(matchKey,ch.json());
            store(c).edit().putString("matchPicks3",picks.toString()).apply();
        }catch(Exception ignored){}
        matchPicksJson=null;
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
    private static final java.util.concurrent.atomic.AtomicBoolean firing=new java.util.concurrent.atomic.AtomicBoolean();
    /** Many answers arriving together tell the screens once (about 150 ms later). */
    private static void fire(){
        if(!firing.compareAndSet(false,true))return;
        main.postDelayed(()->{firing.set(false);for(Runnable r:listeners)r.run();},150);
    }
    /** The files changed (a category finished downloading): earlier "no channel" answers may be wrong now. */
    static void invalidate(){clearAnswers();guideAt=0;fire();}
    private static void clearAnswers(){confirmed.clear();merged=null;}
    private static volatile int epoch;   // raised when the subscription changes: a scan of the old one is thrown away
    /** The subscription changed: nothing read for the old one stays in memory (the files and preferences are AccountData's). */
    static void reset(){
        epoch++;
        guides.clear();guideChannels.clear();confirmed.clear();optionsMap.clear();
        merged=null;mergedFor=null;leaguePicks=null;matchPicksJson=null;bad=null;guideAt=0;
        fire();
    }
    /** A channel linked to this match answered 404 or 405 (the provider has no such stream): the link goes, the viewer picks. */
    static void unlink(Context c,String matchKey,String channelId){
        if(matchKey==null||matchKey.isEmpty())return;
        try{
            JSONObject picks=new JSONObject(store(c).getString("matchPicks3","{}"));
            picks.remove(matchKey);
            JSONObject bad=new JSONObject(store(c).getString("badLinks","{}"));
            if(channelId!=null&&!channelId.isEmpty())bad.put(matchKey,channelId);   // the guide must not link the same channel again
            store(c).edit().putString("matchPicks3",picks.toString()).putString("badLinks",bad.toString()).apply();
        }catch(Exception ignored){}
        matchPicksJson=null;
        confirmed.put(matchKey,NONE);
        merged=null;
        fire();
    }

    // MARK: the guide decides

    private static final long GUIDE_TTL=3*3600_000L;   // the whole day's guide is read every 3 hours, in the background only
    private static final java.util.Map<String,java.util.List<LiveData.Program>> guides=new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<String,LiveData.Channel> guideChannels=new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile long guideAt;
    private static volatile boolean scanning;
    static final long KICK_WINDOW=30*60_000L;
    private static final int GUIDE_CAP=200,GUIDE_PROGRAMMES=12;
    private static final java.util.Map<String,Object> confirmed=new java.util.concurrent.ConcurrentHashMap<>();   // match key → channel | NONE

    /**
     * Reads the guide of the sports channels already downloaded (at most {@value #GUIDE_CAP}, the next
     * {@value #GUIDE_PROGRAMMES} programmes each, four at a time): {@link #fromGuide} looks for the teams' names in
     * those titles and {@link #withGuide} turns match titles the ESPN feed doesn't have into matches. Repeats at most
     * every 15 minutes; never on the UI thread; the listeners are told when it finished.
     */
    static void scanGuides(Context c,XtreamAccount a){
        if(a==null||scanning||System.currentTimeMillis()-guideAt<GUIDE_TTL)return;
        scanning=true;
        final int ep=epoch;
        final Context app=c.getApplicationContext();
        worker.execute(()->{
            try{
                topUpSportCategories(app,a);
                if(guides.isEmpty()&&loadGuideFile(app,a))return;   // a recent copy on disk (after a restart): no requests at all
                java.util.List<LiveData.Channel> list=sportChannels(app,a);
                java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(4);   // never more than four requests at a time
                java.util.List<java.util.concurrent.Future<?>> jobs=new ArrayList<>();
                final long now=System.currentTimeMillis();
                for(LiveData.Channel ch:list)jobs.add(pool.submit(()->{
                    try{
                        if(ep!=epoch)return;   // the subscription changed: no more requests for the old one
                        java.util.List<LiveData.Program> day=null;
                        try{day=LiveData.fetchDay(a,ch.id);}catch(Exception ignored){}
                        if(day==null||day.isEmpty())day=LiveData.fetchGuide(a,ch.id,GUIDE_PROGRAMMES);   // a provider without the day table: the next programmes
                        java.util.List<LiveData.Program> keep=new ArrayList<>();
                        final long horizon=guideHorizon(now);
                        for(LiveData.Program p:day)if(p.end>now-3*3600_000L&&p.start<horizon)keep.add(p);
                        if(ep==epoch){guides.put(ch.id,keep);guideChannels.put(ch.id,ch);}   // not if the subscription changed meanwhile
                    }catch(Exception ignored){}
                }));
                for(java.util.concurrent.Future<?> j:jobs){try{j.get(60,java.util.concurrent.TimeUnit.SECONDS);}catch(Exception ignored){}}
                pool.shutdownNow();
                if(ep==epoch&&!guides.isEmpty())saveGuideFile(app,a);
            }catch(Throwable ignored){}
            // nothing read (no sports files yet): try again in a minute rather than in three hours
            guideAt=ep!=epoch?0:guides.isEmpty()?System.currentTimeMillis()-GUIDE_TTL+60_000L:System.currentTimeMillis();scanning=false;
            clearAnswers();
            fire();
        });
    }
    /** How far ahead the guide's programmes are kept: to the end of Riyadh's tomorrow (and an hour past it), so "غداً" matches can be linked too; at least 30 hours. */
    private static long guideHorizon(long now){
        java.util.Calendar cal=java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Riyadh"));
        cal.setTimeInMillis(now);
        cal.set(java.util.Calendar.HOUR_OF_DAY,0);cal.set(java.util.Calendar.MINUTE,0);cal.set(java.util.Calendar.SECOND,0);cal.set(java.util.Calendar.MILLISECOND,0);
        long tomorrowEnd=cal.getTimeInMillis()+48L*3600_000L;
        return Math.max(now+30*3600_000L,tomorrowEnd+3600_000L);
    }
    private static File guideFile(Context c,XtreamAccount a){return new File(c.getFilesDir(),"guide-day-"+AccountData.key(a)+".json");}
    private static void saveGuideFile(Context c,XtreamAccount a){
        try{
            JSONObject root=new JSONObject(),chs=new JSONObject();
            for(java.util.Map.Entry<String,java.util.List<LiveData.Program>> e:guides.entrySet()){
                LiveData.Channel ch=guideChannels.get(e.getKey());if(ch==null)continue;
                JSONObject o=new JSONObject();o.put("c",ch.json());
                JSONArray ps=new JSONArray();
                for(LiveData.Program p:e.getValue())ps.put(new JSONArray().put(p.title).put(p.start).put(p.end));
                o.put("p",ps);chs.put(e.getKey(),o);
            }
            root.put("at",System.currentTimeMillis()).put("ch",chs);
            Files.write(guideFile(c,a).toPath(),root.toString().getBytes(StandardCharsets.UTF_8));
        }catch(Exception ignored){}
    }
    /** Loads the day's guide saved by an earlier run when it is under three hours old. */
    private static boolean loadGuideFile(Context c,XtreamAccount a){
        try{
            File f=guideFile(c,a);if(!f.exists())return false;
            JSONObject root=new JSONObject(new String(Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8));
            long at=root.optLong("at");
            if(System.currentTimeMillis()-at>=GUIDE_TTL)return false;
            JSONObject chs=root.getJSONObject("ch");
            java.util.Iterator<String> it=chs.keys();
            while(it.hasNext()){
                String id=it.next();JSONObject o=chs.getJSONObject(id);
                LiveData.Channel ch=LiveData.Channel.from(o.getJSONObject("c"));if(ch==null)continue;
                java.util.List<LiveData.Program> ps=new ArrayList<>();
                JSONArray arr=o.getJSONArray("p");
                for(int i=0;i<arr.length();i++){JSONArray r=arr.getJSONArray(i);ps.add(new LiveData.Program(r.getString(0),r.getLong(1),r.getLong(2)));}
                guides.put(id,ps);guideChannels.put(id,ch);
            }
            if(guides.isEmpty())return false;
            guideAt=at;scanning=false;clearAnswers();fire();
            return true;
        }catch(Exception e){return false;}
    }
    /**
     * Once per install: the sports categories (beIN, SSC and the foreign "FR| beIN SPORTS" ones, up to twelve) that were not
     * downloaded yet are downloaded, so the guide scan reads their channels too. Runs in the background.
     */
    private static void topUpSportCategories(Context c,XtreamAccount a){
        final SharedPreferences sp=store(c);
        if(sp.getBoolean("sportTopUp1",false))return;
        final java.util.concurrent.CountDownLatch done=new java.util.concurrent.CountDownLatch(1);
        main.post(()->{
            final LiveData data=new LiveData(c,a);
            data.loadCategories(()->{
                List<LiveData.Category> sports=new ArrayList<>();
                synchronized(data.categories){
                    for(LiveData.Category cat:data.categories){
                        String n=cat.name.toLowerCase(java.util.Locale.ROOT);
                        if(n.contains("sport")||n.contains("ssc")||n.contains("bein")||n.contains("رياض")||n.contains("بي ان")||n.contains("مباريات"))sports.add(cat);
                    }
                }
                if(sports.isEmpty()){if(!data.categories.isEmpty())sp.edit().putBoolean("sportTopUp1",true).apply();done.countDown();return;}
                String key=Integer.toHexString((a.server+"|"+a.user).hashCode());
                final List<LiveData.Category> missing=new ArrayList<>();
                for(int i=0;i<Math.min(12,sports.size());i++)
                    if(!new File(c.getFilesDir(),"live-cat-"+key+"-"+sports.get(i).id+".json").exists())missing.add(sports.get(i));
                StringBuilder ids=new StringBuilder();
                for(int i=0;i<Math.min(12,sports.size());i++)ids.append(i>0?",":"").append(sports.get(i).id);
                sp.edit().putString("sportCats",ids.toString()).putBoolean("sportTopUp1",true).apply();
                if(missing.isEmpty()){done.countDown();return;}
                final int[] left={missing.size()};
                for(LiveData.Category cat:missing)data.loadChannels(cat.id,()->{if(--left[0]==0)done.countDown();});
            });
        });
        try{done.await(90,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException ignored){}
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
        // only beIN, SSC, AD and the other sports channels are read, beIN and SSC first
        java.util.Iterator<LiveData.Channel> it=out.iterator();
        while(it.hasNext())if(sportRank(it.next().name)>=2)it.remove();
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

    static int qualityPenalty(String name){return Titles.qualityPenalty(name);}
    // HD first, then FHD, then a name with no tag, then SD; 4K and H.265 last
    private static final Pattern FOREIGN=Pattern.compile("^\\s*(?!AR\\b)[A-Za-z]{2,3}\\s*[|:]");
    // an Arabic channel before a foreign one (FR| beIN SPORTS ...) for the same match, then quality
    private static int rank(LiveData.Channel ch){return (FOREIGN.matcher(ch.name==null?"":ch.name).find()?10_000_000:0)+qualityPenalty(ch.name)*1_000_000+(FHD.matcher(ch.name==null?"":ch.name).find()&&!HD_ONLY.matcher(ch.name).find()?500_000:0)+number(canonical(ch.name))*1000+(ch.name.hashCode()&511);}   // ties go the same way every time
    private static final java.util.Map<String,java.util.List<LiveData.Channel>> optionsMap=new java.util.concurrent.ConcurrentHashMap<>();
    /** Every sports channel whose programme names both teams near the kickoff, best first (HD before 4K). Uses only what the last scan read; off the UI thread. */
    static java.util.List<LiveData.Channel> guideOptions(MatchCarouselView.Match m){
        java.util.List<LiveData.Channel> out=new ArrayList<>();
        if(m==null||m.homeEn.isEmpty()||m.awayEn.isEmpty())return out;
        long now=System.currentTimeMillis(),kick=m.kickoffMillis;
        for(java.util.Map.Entry<String,java.util.List<LiveData.Program>> e:guides.entrySet()){
            LiveData.Channel ch=guideChannels.get(e.getKey());
            if(ch==null||out.contains(ch)||(bad!=null&&bad.contains(ch.id)))continue;
            for(LiveData.Program p:e.getValue()){
                if(p.end<now)continue;
                // the programme's time covers the kickoff (it may start up to 30 minutes after it, as a late "live" block, or earlier with a pre-match show)
                if(kick>0&&(p.start>kick+KICK_WINDOW||p.end<=kick||p.start<kick-4*3600_000L))continue;
                if(Titles.isRerun(p.title))continue;
                if(!Titles.sameMatch(p.title,m.homeEn,m.home,m.awayEn,m.away))continue;
                out.add(ch);break;
            }
        }
        // the regular copy of each channel (HD, then FHD, then plain, then SD; RAW, LQ, 4K and H.265 only when nothing else exists),
        // even when only the other copy's guide named the match, and one entry per channel as it reads
        java.util.List<LiveData.Channel> best=new ArrayList<>();
        java.util.Set<String> seen=new java.util.HashSet<>();
        for(LiveData.Channel ch:out){
            LiveData.Channel pick=bestCopy(ch);
            if(seen.add(Titles.channelKey(pick.name))&&!best.contains(pick))best.add(pick);
        }
        java.util.Collections.sort(best,(x,y)->Integer.compare(rank(x),rank(y)));
        return best;
    }
    /** The best-ranked copy of this channel that the subscription has and this device decodes (the channel itself when none is better). */
    private static LiveData.Channel bestCopy(LiveData.Channel ch){
        LiveData.Channel best=ch;
        String want=canonical(ch.name);
        for(LiveData.Channel x:guideChannels.values()){
            if(x==ch||!canonical(x.name).equals(want)||(bad!=null&&bad.contains(x.id)))continue;
            if(rank(x)<rank(best))best=x;
        }
        return best;
    }
    static LiveData.Channel fromGuide(MatchCarouselView.Match m){
        java.util.List<LiveData.Channel> o=guideOptions(m);
        if(m!=null)optionsMap.put(m.key,o);
        return o.isEmpty()?null:o.get(0);
    }
    /** The channels that carry this match (the best one first), from memory: for the details panel. */
    static java.util.List<LiveData.Channel> options(MatchCarouselView.Match m){
        java.util.List<LiveData.Channel> o=m==null?null:optionsMap.get(m.key);
        return o==null?new ArrayList<LiveData.Channel>():o;
    }
    static String shortName(String name){return Titles.channelName(name);}
    /** The player for a channel that came from a match's link: if the provider answers 404/405, the link is dropped and the sports list opens. */
    static android.content.Intent linkedIntent(Context c,LiveData.Channel ch,MatchCarouselView.Match m){
        return PlayerActivity.intent(c,ch,"").putExtra(PlayerActivity.EXTRA_MATCH_KEY,m.key).putExtra(PlayerActivity.EXTRA_LINKED,"1")
                .putExtra(PlayerActivity.EXTRA_SUGGESTED,android.text.TextUtils.join(",",suggestions(c,m)));
    }
    /** Told by {@link ChannelHints} when the sites' channels changed: the answers are worked out again (the guide is not read again). */
    static void hintsChanged(){clearAnswers();fire();}
    /**
     * The viewer's own channel for what the sites (Yallakora, Filgoal) say broadcasts the match: "beIN Sports MENA 4" is
     * the subscription's "beIN SPORTS 4 HD". The same channel under any prefix and quality tag counts; HD first, then the
     * other copies, RAW, LQ, 4K and H.265 only when nothing better exists. A copy this device can't decode is skipped.
     */
    static LiveData.Channel fromHints(Context c,MatchCarouselView.Match m){
        for(String raw:ChannelHints.channelsFor(m)){
            String key=Titles.channelKey(raw);
            if(key.isEmpty())continue;
            LiveData.Channel best=null;
            for(LiveData.Channel ch:guideChannels.values()){
                if(isUnsupported(c,ch.id)||!Titles.channelKey(ch.name).equals(key))continue;
                if(best==null||rank(ch)<rank(best))best=ch;
            }
            if(best!=null)return best;
        }
        return null;
    }
    /** A channel the feed itself names for the match ("beIN SPORTS 2"), when the subscription has exactly that channel. */
    static LiveData.Channel fromBroadcast(MatchCarouselView.Match m){
        if(m==null||m.broadcasts.isEmpty())return null;
        for(String name:m.broadcasts){
            String want=canonical(name);
            if(want.isEmpty())continue;
            for(LiveData.Channel ch:guideChannels.values())if(canonical(ch.name).equals(want))return bestCopy(ch);
        }
        return null;
    }
    /** True when the guide says this channel shows another match around the kickoff: the channel is wrong for this one. */
    static boolean contradicts(LiveData.Channel ch,MatchCarouselView.Match m){
        java.util.List<LiveData.Program> ps=ch==null||m==null?null:guides.get(ch.id);
        if(ps==null||m.kickoffMillis<=0)return false;
        long kick=m.kickoffMillis;
        for(LiveData.Program p:ps){
            if(p.end<=kick||p.start>kick+KICK_WINDOW)continue;   // not the programme on at the kickoff
            if(Titles.sameMatch(p.title,m.homeEn,m.home,m.awayEn,m.away))return false;
            if(Titles.parseMatch(Titles.stripLive(p.title))!=null)return true;
        }
        return false;
    }
    /** The ids of channels worth highlighting in the sports list for a match the guide could not confirm: the feed's broadcaster and the league's pick, unless the guide contradicts them. */
    static java.util.List<String> suggestions(Context c,MatchCarouselView.Match m){
        java.util.List<String> out=new ArrayList<>();
        if(m==null)return out;
        LiveData.Channel[] cand={fromBroadcast(m),picked(c,m.competition)};
        for(LiveData.Channel ch:cand)if(ch!=null&&!out.contains(ch.id)&&!contradicts(ch,m)&&!isUnsupported(c,ch.id))out.add(ch.id);
        return out;
    }
    // MARK: channels this device cannot decode
    private static volatile java.util.Set<String> bad;
    private static java.util.Set<String> bad(Context c){
        java.util.Set<String> s=bad;
        if(s==null){s=java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String,Boolean>());
            for(String id:store(c).getString("badDecoder","").split(","))if(!id.isEmpty())s.add(id);bad=s;}
        return s;
    }
    static boolean isUnsupported(Context c,String id){return id!=null&&bad(c).contains(id);}
    static void markUnsupported(Context c,String id){
        if(id==null||id.isEmpty())return;
        java.util.Set<String> s=bad(c);s.add(id);
        store(c).edit().putString("badDecoder",android.text.TextUtils.join(",",s)).apply();
        clearAnswers();
    }
    /** The best other copy of the same channel (same name without its quality tag) that this device can decode: HD first. */
    static LiveData.Channel hdSibling(Context c,java.util.List<LiveData.Channel> list,LiveData.Channel cur){
        LiveData.Channel best=null;
        String want=canonical(cur.name);
        for(LiveData.Channel x:list){
            if(x.id.equals(cur.id)||isUnsupported(c,x.id)||!canonical(x.name).equals(want))continue;
            if(qualityPenalty(x.name)>=3)continue;   // not another 4K / H.265
            if(best==null||rank(x)<rank(best))best=x;
        }
        return best;
    }
    /**
     * The channel for a match, only when confirmed: the one the viewer played for this very match, a guide match's own
     * channel, the guide, the feed's own broadcaster, or the channel picked by hand for its league. Otherwise null,
     * and the screens ask the viewer to pick one. Answers at once from memory (it is called while drawing): what has
     * to be worked out goes to a background thread, and the listeners are told when the answer is in.
     */
    /** The channel the guide found, unless the provider already answered 404/405 for it on this match. */
    private static LiveData.Channel notRefused(Context c,String matchKey,LiveData.Channel ch){
        if(ch==null)return null;
        try{if(ch.id.equals(new JSONObject(store(c).getString("badLinks","{}")).optString(matchKey)))return null;}catch(Exception ignored){}
        return ch;
    }
    static LiveData.Channel forMatch(Context c,XtreamAccount a,MatchCarouselView.Match m){
        if(m==null)return null;
        scanGuides(c,a);
        ChannelHints.refreshIfStale(c);   // background, at most every 30 minutes
        LiveData.Channel manual=matchPick(c,m.key);
        if(manual!=null&&!contradicts(manual,m)&&!isUnsupported(c,manual.id))return manual;
        if(!m.epgChannelId.isEmpty())return notRefused(c,m.key,guideChannels.get(m.epgChannelId));
        Object got=confirmed.get(m.key);
        if(got!=null)return got==NONE?null:(LiveData.Channel)got;
        if(a!=null&&!guideReady())return null;   // the scan decides first
        if(resolving.add(m.key)){
            final Context app=c.getApplicationContext();
            resolver.execute(()->{
                try{
                    // the sites' broadcaster first (unless the guide says that channel shows another match), the guide as the backup;
                    // the league's pick stays a suggestion (see suggestions)
                    LiveData.Channel guide=fromGuide(m),hint=fromHints(app,m);
                    final boolean notInSubscription=hint==null;
                    if(hint!=null&&guide!=null&&contradicts(hint,m))hint=null;
                    LiveData.Channel ch=notRefused(app,m.key,hint!=null?hint:guide);
                    if(notInSubscription&&!ChannelHints.channelsFor(m).isEmpty()&&hintMissed.add(m.key)){
                        try{PlayLog.add(app,-1,"قناة المباراة: "+m.home+" × "+m.away+" ← "+ChannelHints.channelsFor(m)+" ("+ChannelHints.siteFor(m)+") ليست في اشتراكك"+(guide!=null?" · استعملت الدليل":""));}catch(Throwable ignored){}
                    }
                    confirmed.put(m.key,ch==null?NONE:ch);
                    fire();
                }catch(Throwable ignored){}finally{resolving.remove(m.key);}
            });
        }
        return null;
    }
    /** False while the answer for this match is still being worked out (the card shows nothing then, not "اختر القناة"). */
    static boolean resolved(Context c,MatchCarouselView.Match m){
        return m!=null&&(!m.epgChannelId.isEmpty()||confirmed.containsKey(m.key)||matchPick(c,m.key)!=null);
    }
    private static final java.util.Set<String> hintMissed=java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String,Boolean>());   // each match is logged once
    private static final java.util.Set<String> resolving=java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String,Boolean>());
    private static final java.util.concurrent.ExecutorService resolver=java.util.concurrent.Executors.newSingleThreadExecutor();

    // MARK: a second source of matches: titles in the guide that read "team × team"

    private static volatile java.util.List<MatchCarouselView.Match> merged;
    private static volatile Object mergedFor;
    /**
     * The ESPN matches plus the matches found in the sports channels' guides (a title "A × B - League" or "A vs B"):
     * each with the programme's start as its kickoff, the league from the title and the channel it was found on. A
     * guide match is left out when the feed already has the same two teams around that time, and the same match on
     * two channels counts once (the lowest channel number wins). Cached until the feed list or the scan changes.
     */
    static synchronized java.util.List<MatchCarouselView.Match> withGuide(java.util.List<MatchCarouselView.Match> api){
        long now=System.currentTimeMillis();
        Object stamp=System.identityHashCode(api)+":"+api.size()+":"+guideAt+":"+now/60_000L+":"+(api.isEmpty()?"":api.get(0).key+api.get(api.size()-1).key);
        java.util.List<MatchCarouselView.Match> cached=merged;
        if(cached!=null&&stamp.equals(mergedFor))return cached;
        java.util.List<MatchCarouselView.Match> out=new ArrayList<>(api);
        // the guide adds a match only when its title says it is live ("Live", "مباشر", "(L)"): everything else in it is a repeat
        // or a programme, and is used only to find the channel of a match the feed already has. A match shown again later
        // (other start time) is a repeat: the first showing counts, and the other channels of that showing are options.
        java.util.List<Object[]> shows=new ArrayList<>();   // {programme, channel, parts}
        for(java.util.Map.Entry<String,java.util.List<LiveData.Program>> e:guides.entrySet()){
            LiveData.Channel ch=guideChannels.get(e.getKey());
            if(ch==null)continue;
            for(LiveData.Program p:e.getValue()){
                long len=p.end-p.start;
                if(p.end<now||len<60*60_000L||len>240*60_000L||Titles.isRerun(p.title)||Titles.isOtherSport(p.title)||Titles.isOtherSport(ch.name))continue;
                if(!Titles.isLiveTitle(p.title))continue;
                String[] parts=Titles.parseMatch(Titles.stripLive(p.title));
                if(parts==null)continue;
                boolean dup=false;
                for(MatchCarouselView.Match x:api){
                    if(Math.abs(x.kickoffMillis-p.start)>2*3600_000L)continue;
                    if(Titles.sameMatch(p.title,x.homeEn,x.home,x.awayEn,x.away)){dup=true;break;}
                }
                if(dup)continue;
                shows.add(new Object[]{p,ch,parts});
            }
        }
        java.util.Collections.sort(shows,(x,y)->Long.compare(((LiveData.Program)x[0]).start,((LiveData.Program)y[0]).start));
        java.util.Map<String,MatchCarouselView.Match> found=new java.util.LinkedHashMap<>();
        java.util.Map<String,Long> firstStart=new java.util.HashMap<>();
        java.util.Map<String,Integer> rank=new java.util.HashMap<>();
        java.util.Map<String,java.util.List<LiveData.Channel>> extra=new java.util.HashMap<>();
        for(Object[] sh:shows){
            LiveData.Program p=(LiveData.Program)sh[0];LiveData.Channel ch=(LiveData.Channel)sh[1];String[] parts=(String[])sh[2];
            String home=Titles.teamArabic(parts[0]),away=Titles.teamArabic(parts[1]);
            String a1=Titles.key(home),b1=Titles.key(away);
            String id=(a1.compareTo(b1)<0?a1+"|"+b1:b1+"|"+a1);
            Long first=firstStart.get(id);
            if(first==null)firstStart.put(id,p.start);
            else if(p.start-first>2*3600_000L)continue;   // shown again later: a repeat
            int r=rank(ch);
            java.util.List<LiveData.Channel> opts=extra.get(id);
            if(opts==null){opts=new ArrayList<>();extra.put(id,opts);}
            if(!opts.contains(ch))opts.add(ch);
            Integer had=rank.get(id);
            if(had!=null&&had<=r)continue;
            MatchCarouselView.Match m=new MatchCarouselView.Match(parts[2],home,away,"",p.start);
            m.homeEn=parts[0];m.awayEn=parts[1];m.key="epg:"+id+"|"+(first==null?p.start:first)/3600_000L;m.epgChannelId=ch.id;
            m.state="pre";   // the guide only knows when it starts: "live" and the score come from the feed alone
            found.put(id,m);rank.put(id,r);
        }
        for(java.util.Map.Entry<String,MatchCarouselView.Match> e:found.entrySet()){
            java.util.List<LiveData.Channel> o=new ArrayList<>(extra.get(e.getKey()));
            java.util.Collections.sort(o,(x,y)->Integer.compare(rank(x),rank(y)));
            optionsMap.put(e.getValue().key,o);
        }
        out.addAll(found.values());
        out=dedupe(out);
        // began over 2.5 h ago and the feed has no result: it is over (the card says so and it goes to the end of the list)
        for(MatchCarouselView.Match m:out)
            if(m.upcoming()&&m.kickoffMillis<now-150*60_000L){m.state="post";m.status="انتهت";}
        merged=out;mergedFor=stamp;
        return out;
    }

    private static String teamKey(String en,String ar){String k=Titles.norm(en==null||en.isEmpty()?ar:en);return k;}
    /** The same two teams on the same day are one match (the first one listed stays: the feed's before the guide's, live before the rest). */
    static java.util.List<MatchCarouselView.Match> dedupe(java.util.List<MatchCarouselView.Match> in){
        java.util.List<MatchCarouselView.Match> out=new ArrayList<>();
        java.util.Map<String,MatchCarouselView.Match> seen=new java.util.HashMap<>();
        java.text.SimpleDateFormat day=new java.text.SimpleDateFormat("yyyyMMdd",java.util.Locale.US);
        day.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Riyadh"));
        for(MatchCarouselView.Match m:in){
            String a=teamKey(m.homeEn,m.home),b=teamKey(m.awayEn,m.away);
            String k=(a.compareTo(b)<0?a+"|"+b:b+"|"+a)+"|"+day.format(new java.util.Date(m.kickoffMillis));
            MatchCarouselView.Match had=seen.get(k);
            if(had==null){seen.put(k,m);out.add(m);continue;}
            // the other copy's channel is one more option of the kept one
            java.util.List<LiveData.Channel> o=new ArrayList<>(options(had));
            if(!m.epgChannelId.isEmpty()){LiveData.Channel ch=guideChannels.get(m.epgChannelId);if(ch!=null&&!o.contains(ch))o.add(ch);}
            for(LiveData.Channel x:options(m))if(!o.contains(x))o.add(x);
            if(!o.isEmpty())optionsMap.put(had.key,o);
        }
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
