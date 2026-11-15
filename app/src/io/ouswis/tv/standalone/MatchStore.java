package io.ouswis.tv.standalone;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The matches of the feed, kept once for the whole process: the home screen and the matches screen both read this list
 * and both ask it to refresh, so they can never disagree. A refresh that failed, or that came back with less than
 * before, never empties it: the last good data stays and {@link #failed()} says the update did not work.
 */
final class MatchStore {
    private static final Object LOCK=new Object(),REFRESH=new Object();
    private static final long MIN_GAP_MS=15_000;
    private static List<MatchCarouselView.Match> all=new ArrayList<>();
    private static long updatedAt,attemptAt;
    private static boolean failed;
    private static int answered;
    private MatchStore(){}

    static List<MatchCarouselView.Match> snapshot(){synchronized(LOCK){return new ArrayList<>(all);}}
    static long updatedAt(){synchronized(LOCK){return updatedAt;}}
    static boolean failed(){synchronized(LOCK){return failed;}}
    /** How many competitions answered in the last good refresh: 0 means the feed was never reached. */
    static int answered(){synchronized(LOCK){return answered;}}

    /** The competition a match belongs to: the part of its key before the colon ("eng.1:12345"). */
    static String leagueOf(MatchCarouselView.Match m){
        int i=m.key==null?-1:m.key.indexOf(':');
        return i>0?m.key.substring(0,i):"";
    }

    // The last complete refresh of a day: what a later, smaller one is measured against.
    private static long fullDay;
    private static int fullLeagues,fullMatches;
    private static final Set<String> MISSING=new LinkedHashSet<>();   // competitions whose matches could not be refreshed: their saved ones stay as they are
    private static boolean retryPending;
    private static int retries;
    private static final long RETRY_MS=2*60_000L;
    private static final double MIN_SHARE=0.70;
    private static final java.util.concurrent.ScheduledExecutorService LATER=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"ouswis-matches-retry");t.setDaemon(true);return t;});

    private static long dayOf(long now){return (now+3L*3600_000)/(24L*3600_000);}   // Riyadh's calendar day

    /** Loads every competition once (blocking: not on the UI thread). A call right after another one reuses its answer. */
    static void refresh(android.content.Context ctx){
        synchronized(REFRESH){
            long now=System.currentTimeMillis();
            if(now-attemptAt<MIN_GAP_MS)return;
            UpcomingMatches.Full f;
            try{f=UpcomingMatches.loadAll(null);}
            catch(Throwable t){f=new UpcomingMatches.Full();f.failed.put("*",t.getClass().getSimpleName());}
            if(f.aborted)return;   // the screen that asked went away mid-way: nothing was decided, nothing is replaced, the next call may start at once
            attemptAt=System.currentTimeMillis();
            apply(ctx,f,false,attemptAt);
        }
    }

    /** What the playback log says about a refresh: how many competitions failed and why. */
    static String failures(Map<String,String> failed){
        StringBuilder b=new StringBuilder();
        for(Map.Entry<String,String> e:failed.entrySet()){if(b.length()>0)b.append("، ");b.append(e.getKey()).append(" (").append(e.getValue()).append(")");}
        return b.toString();
    }

    /** Forgets everything (the offline tests start each case clean). */
    static void resetForTest(){synchronized(LOCK){all=new ArrayList<>();updatedAt=0;attemptAt=0;failed=false;answered=0;fullDay=0;fullLeagues=0;fullMatches=0;MISSING.clear();retryPending=false;retries=0;}}

    static void apply(android.content.Context ctx,UpcomingMatches.Full f,boolean retry,long now){
        int okN=f.ok.size(),badN=f.failed.size();
        boolean sameDay=!retry&&fullDay==dayOf(now)&&fullLeagues>0;
        boolean lowLeagues=sameDay&&okN<MIN_SHARE*fullLeagues;
        boolean lowMatches=sameDay&&f.matches.size()<MIN_SHARE*fullMatches;
        boolean emptyOverData=!retry&&f.matches.isEmpty()&&!snapshot().isEmpty();
        boolean complete=badN==0&&okN>0&&!lowLeagues&&!lowMatches&&!emptyOverData;
        // a competition that answered replaces its saved matches only when the refresh as a whole is trustworthy
        // (or, for a retry, the competition itself answered); a failed one keeps what it had
        // a partial answer is only added to what is saved (by key, so scores still update): replacing a competition's
        // matches with a smaller list is how the screen emptied
        Set<String> replace=complete?f.ok:new HashSet<String>();
        merge(f.matches,replace,complete||(retry&&MISSING.size()-f.ok.size()<=0&&badN==0),now);
        String what;
        int kept=snapshot().size();
        if(retry){
            MISSING.removeAll(f.ok);MISSING.addAll(f.failed.keySet());
            what="المباريات: إعادة المحاولة · عاد "+okN+" دوري"+(MISSING.isEmpty()?" · اكتمل التحديث":" · ما زال ناقصاً: "+failures(f.failed))+" · المحفوظ "+kept;
        }else if(okN==0){
            MISSING.clear();
            what="المباريات: فشل التحديث · فشل "+badN+" دوري: "+failures(f.failed)+" · المحفوظ "+kept+" مباراة";
        }else if(complete){
            MISSING.clear();
            fullDay=dayOf(now);fullLeagues=okN;fullMatches=f.matches.size();
            what="المباريات: تحديث ناجح · "+f.matches.size()+" مباراة جديدة · "+okN+" دوري · المحفوظ "+kept;
        }else{
            MISSING.clear();MISSING.addAll(f.failed.keySet());
            StringBuilder b=new StringBuilder("المباريات: تحديث جزئي · نجح "+okN+" من "+(okN+badN)+" دوري · "+f.matches.size()+" مباراة");
            if(badN>0)b.append(" · فشل ").append(badN).append(" دوري: ").append(failures(f.failed));
            if(lowLeagues||lowMatches)b.append(" · أقل من 70% من آخر تحديث كامل (").append(fullLeagues).append(" دوري و").append(fullMatches).append(" مباراة)");
            else if(emptyOverData)b.append(" · وصل بدون مباريات وفيه محفوظ");
            b.append(" · المحفوظ ").append(kept).append(" مباراة");
            what=b.toString();
        }
        if(okN>0)answered=Math.max(answered,okN);
        try{PlayLog.add(ctx,-1,what);}catch(Throwable ignored){}   // every refresh, good, partial or bad, goes into the playback log
        if(!MISSING.isEmpty()&&retries<6&&!retryPending&&ctx!=null){
            retryPending=true;retries++;
            final android.content.Context app=ctx.getApplicationContext();
            LATER.schedule(()->retry(app),RETRY_MS,java.util.concurrent.TimeUnit.MILLISECONDS);
        }else if(MISSING.isEmpty())retries=0;
    }

    /** The competitions that failed are asked again two minutes later, only those. */
    private static void retry(android.content.Context ctx){
        String[] only;
        synchronized(REFRESH){retryPending=false;only=MISSING.toArray(new String[0]);}
        if(only.length==0)return;
        try{
            UpcomingMatches.Full f=UpcomingMatches.loadAll(only);
            synchronized(REFRESH){
                if(f.aborted)return;
                apply(ctx,f,true,System.currentTimeMillis());
            }
        }catch(Throwable ignored){}
    }

    /**
     * The rule that keeps the list. `replace` are the competitions whose answer is trusted: their saved matches are
     * swapped for the new ones, one competition at a time. Every other saved match stays (a competition that failed, or a
     * whole refresh that came back too small), and the new matches are added by key. `complete`: the list is up to date.
     */
    static void merge(List<MatchCarouselView.Match> fresh,Set<String> replace,boolean complete,long now){
        synchronized(LOCK){
            if(replace.isEmpty()&&fresh.isEmpty()){failed=true;return;}   // nothing to add, nothing trusted: keep everything
            Map<String,MatchCarouselView.Match> by=new LinkedHashMap<>();
            for(MatchCarouselView.Match m:all)if(!replace.contains(leagueOf(m)))by.put(m.key,m);
            Set<String> freshKeys=new HashSet<>();
            for(MatchCarouselView.Match m:fresh){by.put(m.key,m);freshKeys.add(m.key);}
            List<MatchCarouselView.Match> out=new ArrayList<>();
            for(MatchCarouselView.Match m:by.values()){
                if(m.kickoffMillis<now-3L*24*3600_000)continue;                                   // older than the screens ever show
                if(m.live()&&!freshKeys.contains(m.key)&&m.kickoffMillis<now-4L*3600_000)continue;   // a match that cannot still be on
                out.add(m);
            }
            all=out;failed=!complete;
            if(complete||updatedAt==0&&!out.isEmpty())updatedAt=now;
        }
    }
}
