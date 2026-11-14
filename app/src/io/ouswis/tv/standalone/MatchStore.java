package io.ouswis.tv.standalone;

import java.util.ArrayList;
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

    /** Loads the four sections once (blocking: not on the UI thread). A call right after another one reuses its answer. */
    static void refresh(android.content.Context ctx){
        synchronized(REFRESH){
            long now=System.currentTimeMillis();
            if(now-attemptAt<MIN_GAP_MS)return;
            List<MatchCarouselView.Match> fresh=new ArrayList<>();
            int ok=0,bad=0;
            String err="";
            try{
                for(String section:new String[]{"live","upcoming","today","yesterday"}){
                    UpcomingMatches.Result r=UpcomingMatches.load(null,section);
                    fresh.addAll(r.matches);ok+=r.answered;bad+=r.failed;if(!r.error.isEmpty())err=r.error;
                }
            }catch(Throwable t){bad++;err=t.getClass().getSimpleName();}
            attemptAt=System.currentTimeMillis();
            merge(fresh,ok,bad,attemptAt);
            try{   // every refresh, good or bad, goes into the playback log (the HTTP code is the feed's own answer, never an address)
                int kept=snapshot().size();
                String what=bad==0&&ok>0?"المباريات: تحديث ناجح · "+fresh.size()+" مباراة جديدة · "+ok+" دوري · المحفوظ "+kept
                        :"المباريات: فشل التحديث · "+(err.isEmpty()?"لا ردّ":err)+" · نجح "+ok+" وفشل "+bad+" · المحفوظ "+kept+" مباراة";
                PlayLog.add(ctx,-1,what);
            }catch(Throwable ignored){}
        }
    }

    /** The rule that keeps the list: only a complete load replaces it; an incomplete one adds to it. Package-private for the test. */
    static void merge(List<MatchCarouselView.Match> fresh,int ok,int bad,long now){
        synchronized(LOCK){
            if(ok==0){failed=true;return;}                       // nothing answered: keep everything
            boolean complete=bad==0;
            if(complete&&(!fresh.isEmpty()||all.isEmpty())){all=new ArrayList<>(fresh);failed=false;updatedAt=now;answered=ok;return;}
            // incomplete, or complete but empty while there was data: new matches win by key, the old ones stay
            Map<String,MatchCarouselView.Match> by=new LinkedHashMap<>();
            for(MatchCarouselView.Match m:all)by.put(m.key,m);
            for(MatchCarouselView.Match m:fresh)by.put(m.key,m);
            List<MatchCarouselView.Match> out=new ArrayList<>();
            for(MatchCarouselView.Match m:by.values()){
                if(m.kickoffMillis<now-3L*24*3600_000)continue;                          // older than the screens ever show
                if(m.live()&&!fresh.contains(m)&&m.kickoffMillis<now-4L*3600_000)continue;   // a match that cannot still be on
                out.add(m);
            }
            all=out;failed=true;answered=Math.max(answered,ok);
            if(updatedAt==0&&!out.isEmpty())updatedAt=now;
        }
    }
}
