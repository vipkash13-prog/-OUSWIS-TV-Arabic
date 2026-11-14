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
    private static final Pattern QUALITY=Pattern.compile("(?i)\\b(FHD|UHD|HD|SD|4K)\\b");

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

    // MARK: the guide decides

    private static final long GUIDE_TTL=15*60_000L;
    private static final java.util.Map<String,java.util.List<LiveData.Program>> guides=new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<String,LiveData.Channel> guideChannels=new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile long guideAt;
    private static volatile boolean scanning;
    static final long KICK_WINDOW=30*60_000L;
    private static final int GUIDE_CAP=120,GUIDE_PROGRAMMES=12;
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
        final Context app=c.getApplicationContext();
        worker.execute(()->{
            try{
                java.util.List<LiveData.Channel> list=sportChannels(app,a);
                java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(4);   // never more than four requests at a time
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
    private static int rank(LiveData.Channel ch){return qualityPenalty(ch.name)*1_000_000+(FHD.matcher(ch.name==null?"":ch.name).find()&&!HD_ONLY.matcher(ch.name).find()?500_000:0)+number(canonical(ch.name))*1000+(ch.name.hashCode()&511);}   // ties go the same way every time
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
                if(kick>0&&Math.abs(p.start-kick)>KICK_WINDOW)continue;   // the programme starts within 30 minutes of the kickoff
                if(Titles.isRerun(p.title))continue;
                if(!Titles.sameMatch(p.title,m.homeEn,m.home,m.awayEn,m.away))continue;
                out.add(ch);break;
            }
        }
        java.util.Collections.sort(out,(x,y)->Integer.compare(rank(x),rank(y)));
        return out;
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
    static LiveData.Channel forMatch(Context c,XtreamAccount a,MatchCarouselView.Match m){
        if(m==null)return null;
        scanGuides(c,a);
        LiveData.Channel manual=matchPick(c,m.key);
        if(manual!=null&&!contradicts(manual,m)&&!isUnsupported(c,manual.id))return manual;
        if(!m.epgChannelId.isEmpty())return guideChannels.get(m.epgChannelId);
        Object got=confirmed.get(m.key);
        if(got!=null)return got==NONE?null:(LiveData.Channel)got;
        if(a!=null&&!guideReady())return null;   // the scan decides first
        if(resolving.add(m.key)){
            final Context app=c.getApplicationContext();
            resolver.execute(()->{
                try{
                    // only the guide confirms a channel: the feed's broadcaster and the league's pick are suggestions (see suggestions)
                    LiveData.Channel ch=fromGuide(m);
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
