package io.ouswis.tv.standalone;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The redesigned home screen (SPEC section 2, home.png): top bar, the "last channel" and next-prayer cards, today's
 * matches, my apps, shortcuts and a hint line. All data comes from what the app already had: HomeData (weather),
 * PrayerTimes (AlAdhan, Umm al-Qura), UpcomingMatches (ESPN), HomeApps.shelf (the current apps) and the viewer's Xtream
 * account. Drawn by hand like the rest; the weather panel is gone, its details open from the weather chip.
 */
final class RedesignHomeView extends View {
    interface Host {
        void openClassic(boolean recents);
    }
    private final Host host;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final Paint title=Design.text(Design.TEXT,30,Design.bold()),sub=Design.text(Design.MUTED,18,Design.regular()),
            chip=Design.text(Design.TEXT,20,Design.regular()),section=Design.text(Design.MUTED,24,Design.regular()),
            dateLine=Design.text(0xCCF4EFE4,21,Design.regular()),   // 14sp, 80 % opaque, a light shadow for legibility
            clock=Design.text(Design.TEXT,100,Design.light()),ampm=Design.text(Design.MUTED,40,Design.light()),
            bold=Design.text(Design.TEXT,27,Design.bold()),goldText=Design.text(Design.GOLD,18,Design.regular()),
            badge=Design.text(0xFFFFFFFF,16,Design.bold()),muted=Design.text(Design.MUTED,19,Design.regular()),
            dark=Design.text(0xFF1A1408,22,Design.bold());
    private final RectF r=new RectF(),g=new RectF(),bm=new RectF(),arc=new RectF();
    private final Path path=new Path();
    private final A11y a11y=new A11y(this);
    @Override public android.view.accessibility.AccessibilityNodeProvider getAccessibilityNodeProvider(){return a11y.provider(null,null);}
    private Bitmap wallpaper;
    private final RectF cropDst=new RectF();
    private final android.graphics.Rect cropRect=new android.graphics.Rect();
    private final Paint wallPaint=new Paint(Paint.FILTER_BITMAP_FLAG);
    private float k=1;
    private LiveData.Channel lastCh;
    private long lastAt;
    /** The last-watched channel, read at most once a second (it was parsed from its JSON on every frame). */
    private LiveData.Channel lastChannel(){
        long now=android.os.SystemClock.uptimeMillis();
        if(lastAt==0||now-lastAt>1000){
            lastAt=now;
            LiveData.Channel ch=LiveData.lastWatched(getContext());
            lastCh=ch!=null&&better!=null&&ch.id.equals(betterFor)?better:ch;
            if(ch!=null&&!ch.id.equals(betterFor)&&!searching&&live!=null){
                searching=true;
                final LiveData.Channel want=ch;
                new Thread(()->{
                    LiveData.Channel found=betterCopy(want);
                    post(()->{better=found;betterFor=want.id;searching=false;lastAt=0;invalidate();});
                }).start();
            }
        }
        return lastCh;
    }
    private volatile LiveData.Channel better;
    private volatile String betterFor;
    private volatile boolean searching;
    /**
     * The card plays the best copy of the channel that is already in memory: HD or FHD over an unmarked or SD one
     * (never 4K or H.265, and never a copy the device failed to decode). Worked out on a background thread, once per channel.
     */
    private LiveData.Channel betterCopy(LiveData.Channel ch){
        if(ch==null||live==null)return ch;
        try{
            String key=Titles.channelKey(ch.name);
            if(key.isEmpty())return ch;
            LiveData.Channel best=ch;
            java.util.List<LiveData.Category> cats;
            synchronized(live.categories){cats=new ArrayList<>(live.categories);}
            for(LiveData.Category cat:cats){
                java.util.List<LiveData.Channel> list=live.cached(cat.id);
                if(list==null)continue;
                for(LiveData.Channel x:new ArrayList<>(list)){
                    if(x.id.equals(best.id)||Titles.qualityPenalty(x.name)>=Titles.qualityPenalty(best.name)||Titles.qualityPenalty(x.name)>=3)continue;
                    if(!key.equals(Titles.channelKey(x.name))||MatchChannels.isUnsupported(getContext(),x.id))continue;
                    best=x;
                }
            }
            return best;
        }catch(RuntimeException e){return ch;}
    }
    private final java.util.HashMap<String,Paint> texts=new java.util.HashMap<>();
    /** A text paint made once per (colour, size, weight): drawing never makes one. Callers must not change what they get. */
    private Paint tx(int color,float size,android.graphics.Typeface face){
        String key=color+":"+size+":"+System.identityHashCode(face);
        Paint t=texts.get(key);
        if(t==null){t=Design.text(color,size,face);texts.put(key,t);}
        return t;
    }
    private int row=1;
    private final int[] col=new int[6];
    // the matches live in the process, not in the view: coming back from another screen (Back, a new view) shows them at once and only refreshes them
    private static final List<MatchCarouselView.Match> today=new ArrayList<>(),apiToday=new ArrayList<>();   // today: the feed's matches plus the guide's
    private List<HomeApps.Item> apps=new ArrayList<>();
    private LiveData live;
    private XtreamAccount account;
    private boolean resumed;
    private static long matchesAt;
    private long drawnMinute=-1;
    private static final long startedAt=System.currentTimeMillis();
    private static boolean feedDone;       // the feed answered at least once
    private static int feedAnswered;       // how many competitions answered: 0 means it could not be reached, not that there are no matches
    /** True only when it is certain there are no matches today: the feed answered and the guides were read. Until then the row shows empty cards. */
    private boolean confirmedEmpty(){
        if(!today.isEmpty()||everHadData)return false;
        long waited=System.currentTimeMillis()-startedAt;
        boolean guidesIn=account==null||MatchChannels.guideReady();
        return feedDone&&feedAnswered>0&&(guidesIn||waited>30_000L);
    }
    private final Runnable tick=new Runnable(){@Override public void run(){
        if(!resumed)return;
        long minute=System.currentTimeMillis()/60_000L;
        if(minute!=drawnMinute){drawnMinute=minute;invalidate();logStats();}   // the clock, the countdown and the progress bar change once a minute
        refreshData();
        main.postDelayed(this,30_000);
    }};
    private final Runnable prayerChanged=this::postInvalidate;
    private final Runnable guideChanged=this::rebuildToday;
    // what the home screen did about its data, for the stability check (logged by logStats)
    private int todayChanges,skeletonAfterData,focusResets,sameDataSkips;
    private static boolean everHadData;
    private static long dataDay;
    private static String todaySig="",apiSig="";
    /**
     * The feed's matches of today plus those found in the sports channels' guides, live first, then by kickoff. Worked out
     * on the background thread; the screen is told only when what it would draw is different (same data: nothing happens).
     */
    private volatile boolean destroyed;
    private void rebuildToday(){
        if(destroyed)return;   // Back closed the home screen: its worker is gone and a late callback must not use it
        if(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()){main.post(this::rebuildToday);return;}   // the lists belong to the UI thread
        final List<MatchCarouselView.Match> api=new ArrayList<>(apiToday);
        final long start=dayStart();
        try{io.execute(()->{
            long end=start+24L*3600_000;
            List<MatchCarouselView.Match> list=new ArrayList<>();
            for(MatchCarouselView.Match m:MatchChannels.withGuide(api))if(m.live()||(m.upcoming()&&m.kickoffMillis>=start&&m.kickoffMillis<end))list.add(m);   // the row shows what is on and what is coming, never what finished
            java.util.Collections.sort(list,(a,b)->{
                int ra=a.live()?0:a.upcoming()?1:2,rb=b.live()?0:b.upcoming()?1:2;
                return ra!=rb?Integer.compare(ra,rb):Long.compare(a.kickoffMillis,b.kickoffMillis);
            });
            StringBuilder sig=new StringBuilder();
            for(int i=0;i<Math.min(4,list.size());i++){
                MatchCarouselView.Match m=list.get(i);
                LiveData.Channel ch=account==null?null:MatchChannels.forMatch(getContext(),account,m);
                sig.append(m.key).append('|').append(m.state).append('|').append(m.homeScore).append('-').append(m.awayScore).append('|').append(m.status)
                   .append('|').append(ch==null?(MatchChannels.resolved(getContext(),m)?"none":"wait"):ch.id).append(';');
            }
            sig.append(list.size());
            final String s2=sig.toString();
            main.post(()->{if(!destroyed)applyToday(list,s2,start);});
        });}catch(java.util.concurrent.RejectedExecutionException gone){}
    }
    private void applyToday(List<MatchCarouselView.Match> list,String sig,long start){
        if(list.isEmpty()&&everHadData&&dataDay==start){sameDataSkips++;return;}   // a failed or partial load never empties a row that has shown data
        if(sig.equals(todaySig)){sameDataSkips++;return;}                           // the same data again: nothing to update
        String focusedKey=row==2&&col[2]<today.size()&&col[2]<4?today.get(col[2]).key:null;
        today.clear();today.addAll(list);todaySig=sig;todayChanges++;
        logRow(homeLogWhy.isEmpty()?"تحدّث (بعد تحديث الشبكة)":"تحدّث (من المحفوظ: "+homeLogWhy+")",Math.min(4,list.size()),list.size());homeLogWhy="";
        if(!list.isEmpty()){everHadData=true;dataDay=start;}
        if(focusedKey!=null)for(int i=0;i<Math.min(4,today.size());i++)if(today.get(i).key.equals(focusedKey)){col[2]=i;break;}   // the focus stays on the same match
        clampColumns();invalidate();
    }
    /** What the row shows from a list of matches: live ones and those still to come today, live first, then by kickoff. */
    private List<MatchCarouselView.Match> todayOf(List<MatchCarouselView.Match> found){
        final List<MatchCarouselView.Match> shown=new ArrayList<>();
        long start=dayStart(),end=start+24L*3600_000;
        java.util.Set<String> seen=new java.util.HashSet<>();
        for(MatchCarouselView.Match m:found)
            if((m.live()||(m.upcoming()&&m.kickoffMillis>=start&&m.kickoffMillis<end))&&seen.add(m.key))shown.add(m);
        java.util.Collections.sort(shown,(a,b)->{
            int ra=a.live()?0:a.upcoming()?1:2,rb=b.live()?0:b.upcoming()?1:2;
            return ra!=rb?Integer.compare(ra,rb):Long.compare(a.kickoffMillis,b.kickoffMillis);
        });
        return shown;
    }
    private static int storeSeen=-1;
    /**
     * The row reads the saved list (MatchStore, the one the matches screen shows) as soon as it differs from what it last read,
     * without waiting for its own network refresh: whatever the matches screen or an earlier refresh saved appears at once.
     */
    private void loadSaved(String why){
        if(destroyed)return;
        try{io.execute(()->{
            final List<MatchCarouselView.Match> shown=todayOf(MatchStore.snapshot());
            if(shown.isEmpty())return;   // nothing saved to show: the row waits for the feed
            final StringBuilder sig=new StringBuilder();
            for(MatchCarouselView.Match m:shown)sig.append(m.key).append('|').append(m.state).append('|').append(m.homeScore).append('-').append(m.awayScore).append('|').append(m.status).append(';');
            main.post(()->{
                if(destroyed||shown.isEmpty())return;
                String sigNow=sig.toString();
                if(sigNow.equals(apiSig)&&!today.isEmpty())return;
                apiToday.clear();apiToday.addAll(shown);apiSig=sigNow;
                homeLogWhy=why;
                rebuildToday();
            });
        });}catch(java.util.concurrent.RejectedExecutionException gone){}
    }
    private String homeLogWhy="";
    private void logRow(String what,int shownCount,int totalCount){
        try{PlayLog.add(getContext().getApplicationContext(),-1,"الرئيسية: صف المباريات "+what+" · عرض "+shownCount+" من "+totalCount+" مباراة · المحفوظ "+MatchStore.snapshot().size()+" · آخر تحديث للمحفوظ "+(MatchStore.updatedAt()==0?"لم يحدث":((System.currentTimeMillis()-MatchStore.updatedAt())/1000)+" ث قبل"));}catch(Throwable ignored){}
    }
    /** One line the stability test reads: how often the data really changed, and that nothing relapsed. */
    private void logStats(){AppLog.w("home-stats: todayChanges="+todayChanges+" sameDataSkips="+sameDataSkips+" skeletonAfterData="+skeletonAfterData+" focusResets="+focusResets);}
    private String scoreText(MatchCarouselView.Match m){return m.awayScore.isEmpty()&&m.homeScore.isEmpty()?"—":m.awayScore+" - "+m.homeScore;}

    RedesignHomeView(Context c,Host host){
        super(c);this.host=host;
        setFocusable(true);setFocusableInTouchMode(true);
        setDefaultFocusHighlightEnabled(false);   // the system's translucent focus highlight (a veil over the whole view while it holds the focus) would lift the dark cards: the home draws its own ring
        dateLine.setShadowLayer(3f,0,1.5f,0x99000000);
        account=XtreamAccount.read(c);
        if(account!=null)live=new LiveData(c,account);
    }

    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();MatchChannels.listen(guideChanged);}
    @Override protected void onDetachedFromWindow(){MatchChannels.unlisten(guideChanged);super.onDetachedFromWindow();}

    // MARK: lifecycle

    void resume(){
        resumed=true;lastAt=0;
        storeSeen=MatchStore.version();loadSaved("عند فتح الرئيسية");
        account=XtreamAccount.read(getContext());
        if(account!=null&&live==null)live=new LiveData(getContext(),account);
        PrayerTimes.get(getContext()).addListener(prayerChanged);
        main.removeCallbacks(tick);main.post(tick);
        loadApps();
        loadVod();
    }
    void pause(){logStats();resumed=false;main.removeCallbacks(tick);PrayerTimes.get(getContext()).removeListener(prayerChanged);}
    void destroy(){destroyed=true;pause();main.removeCallbacks(vodRefresh);io.shutdownNow();if(live!=null)live.shutdown();if(vodFilms!=null)vodFilms.shutdown();if(vodSeries!=null)vodSeries.shutdown();vodRefreshing=false;}
    void reset(){row=1;col[1]=0;invalidate();}   // only the Home button asks for this

    private void refreshData(){
        HomeData.get(getContext()).refreshIfStale();
        PrayerTimes.get(getContext()).refreshIfStale();
        long now=System.currentTimeMillis();
        int storeNow=MatchStore.version();
        if(storeNow!=storeSeen){storeSeen=storeNow;loadSaved("تغيّر المحفوظ");}
        LiveData.Channel last=lastChannel();
        if(live!=null&&last!=null)live.loadGuide(last.id,this::postInvalidate);
        if(now-matchesAt>(anyLive()?60_000L:(today.isEmpty()&&!confirmedEmpty())?2*60_000L:15*60_000L)){   // nothing shown and not known to be empty: ask again in 2 minutes, not 15
            matchesAt=now;
            if(!destroyed)try{io.execute(()->{
                List<MatchCarouselView.Match> found=new ArrayList<>();
                int answered=0;
                try{
                    MatchStore.refresh(getContext().getApplicationContext());   // the one list the matches screen reads too
                    found.addAll(MatchStore.snapshot());answered=MatchStore.answered();
                }catch(Throwable ignored){}
                final int answeredNow=answered;
                final List<MatchCarouselView.Match> shown=todayOf(found);
                final StringBuilder fresh=new StringBuilder();
                for(MatchCarouselView.Match m:shown)fresh.append(m.key).append('|').append(m.state).append('|').append(m.homeScore).append('-').append(m.awayScore).append('|').append(m.status).append(';');
                main.post(()->{
                    boolean first=!feedDone||answeredNow>feedAnswered;
                    feedDone=true;feedAnswered=Math.max(feedAnswered,answeredNow);
                    // a failed load keeps what was there rather than hiding the section
                    String sigNow=fresh.toString();
                    boolean changed=(!found.isEmpty()||apiToday.isEmpty())&&!sigNow.equals(apiSig);
                    // the row is emptied only by an answer that really came (competitions answered) and listed nothing live or coming today
                    if(changed&&shown.isEmpty()&&!apiToday.isEmpty()&&answeredNow==0)changed=false;
                    int liveN=0;for(MatchCarouselView.Match m:shown)if(m.live())liveN++;
                    String decision=changed?(shown.isEmpty()?"يفرغ الصف: الخادم ردّ ولا مباراة جارية أو قادمة اليوم":"يحدّث الصف"):(shown.isEmpty()&&!apiToday.isEmpty()?"يبقي آخر بيانات محفوظة (التحديث فشل أو لم يرد)":"بلا تغيير");
                    try{PlayLog.add(getContext().getApplicationContext(),-1,"الوصول إلى الرئيسية: "+found.size()+" مباراة في المحفوظ · "+shown.size()+" لليوم (جارية "+liveN+") · ردّت "+answeredNow+" بطولة · القرار: "+decision);}catch(Throwable ignored){}
                    if(changed){apiToday.clear();apiToday.addAll(shown);apiSig=sigNow;}
                    if(changed||first)rebuildToday();else sameDataSkips++;
                });
            });}catch(java.util.concurrent.RejectedExecutionException gone){matchesAt=0;}
            if(account!=null)MatchChannels.prepare(getContext(),account,()->{MatchChannels.scanGuides(getContext(),account);});
        }
    }
    private boolean anyLive(){for(MatchCarouselView.Match m:today)if(m.live())return true;return false;}
    private long dayStart(){
        java.util.Calendar cal=java.util.Calendar.getInstance(TimeZone.getTimeZone("Asia/Riyadh"));
        cal.set(java.util.Calendar.HOUR_OF_DAY,0);cal.set(java.util.Calendar.MINUTE,0);cal.set(java.util.Calendar.SECOND,0);cal.set(java.util.Calendar.MILLISECOND,0);
        return cal.getTimeInMillis();
    }
    private void loadApps(){
        io.execute(()->{
            List<HomeApps.Item> list;
            try{list=HomeApps.shelf(getContext(),false);}catch(Throwable t){list=new ArrayList<>();}
            final List<HomeApps.Item> result=list;
            main.post(()->{
                boolean same=apps.size()==result.size();
                for(int i=0;same&&i<result.size();i++)same=java.util.Objects.equals(apps.get(i).label,result.get(i).label)&&java.util.Objects.equals(apps.get(i).pkg,result.get(i).pkg);
                if(same){sameDataSkips++;return;}
                apps=result;clampColumns();invalidate();
            });
        });
    }
    // MARK: films and series (home_vod.png): what is being watched, then the newest; read from saved data only

    // kept in the process like the matches: coming back to the home shows the row at once, then only updates it
    private static final List<VodData.RowItem> vodItems=new ArrayList<>();
    private static String vodFor="";            // the subscription the row belongs to
    private static boolean vodSettled;          // the saved row was read and either is fresh or a refresh finished
    private static volatile boolean vodRefreshing;
    private static long vodRefreshAt;
    private VodData vodFilms,vodSeries;
    private final Runnable vodRefresh=this::refreshVod;
    /** Shown while there is something in it, and (as empty cards) until it is known whether there is. */
    private boolean vodShown(){return account!=null&&(!vodItems.isEmpty()||!vodSettled);}
    private void loadVod(){
        final XtreamAccount a=account;
        if(a==null||destroyed)return;
        final String key=AccountData.key(a);
        if(!key.equals(vodFor)){vodFor=key;vodItems.clear();vodSettled=false;vodRefreshAt=0;}
        final Context ctx=getContext().getApplicationContext();
        try{io.execute(()->{
            List<VodData.RowItem> list;
            try{list=VodData.homeRow(ctx,a);}catch(Throwable t){list=null;}
            final boolean stale=VodData.newestStale(ctx,a,false)||VodData.newestStale(ctx,a,true);
            final List<VodData.RowItem> got=list;
            main.post(()->{
                if(destroyed||!key.equals(vodFor))return;
                if(got!=null)applyVod(got);
                if(!stale||!vodItems.isEmpty())vodSettled=true;
                if(stale&&!vodRefreshing&&System.currentTimeMillis()-vodRefreshAt>30*60_000L){main.removeCallbacks(vodRefresh);main.postDelayed(vodRefresh,vodItems.isEmpty()?500:6000);}
                invalidate();
            });
        });}catch(java.util.concurrent.RejectedExecutionException gone){}
    }
    private void applyVod(List<VodData.RowItem> got){
        boolean same=got.size()==vodItems.size();
        for(int i=0;same&&i<got.size();i++)same=got.get(i).same(vodItems.get(i));
        if(same){sameDataSkips++;return;}
        VodData.RowItem held=row==3&&col[3]<vodItems.size()?vodItems.get(col[3]):null;
        vodItems.clear();vodItems.addAll(got);
        if(held!=null)for(int i=0;i<vodItems.size();i++)if(vodItems.get(i).series==held.series&&vodItems.get(i).m.id.equals(held.m.id)){col[3]=i;break;}   // the focus stays on the same poster
        clampColumns();
        if(row==3&&vodItems.isEmpty())row=4;
    }
    /** The newest films and series, from the subscription, on their own threads, at most once a day (every 30 minutes after a failure). */
    private void refreshVod(){
        final XtreamAccount a=account;
        if(a==null||destroyed||vodRefreshing||!resumed)return;
        vodRefreshing=true;vodRefreshAt=System.currentTimeMillis();
        Context ctx=getContext().getApplicationContext();
        if(vodFilms!=null)vodFilms.shutdown();
        if(vodSeries!=null)vodSeries.shutdown();
        vodFilms=new VodData(ctx,a,false);vodSeries=new VodData(ctx,a,true);   // new each time: one keeps what it read
        final int[] left={2};
        final Runnable one=()->{
            if(--left[0]>0)return;
            vodRefreshing=false;vodSettled=true;
            if(!destroyed)loadVod();
        };
        for(VodData d:new VodData[]{vodFilms,vodSeries}){
            d.loadCategories(()->{});
            d.loadMovies(VodData.NEWEST,one);
        }
    }

    private void clampColumns(){
        col[2]=Math.min(col[2],Math.min(today.size(),4));          // cards 0..n-1, "view all" = n
        col[3]=Math.min(col[3],vodItems.size());      // posters 0..n-1, "view all" = n
        col[4]=Math.min(col[4],apps.size());          // apps 0..n-1, "+" = n; the row scrolls, so no cap
    }

    // MARK: drawing

    @Override protected void onSizeChanged(int w,int h,int ow,int oh){
        k=w/1920f;
        loadWallpaper(w,h);
    }
    /** The home wallpaper (already dark inside the picture): the one RGB_565 bitmap Wallpaper keeps, cropped and sized to the screen; nothing is drawn over it. */
    private void loadWallpaper(int w,int h){
        if(wallpaper!=null&&wallW==w&&wallH==h)return;
        wallpaper=Wallpaper.home(getContext(),w,h);
        wallW=w;wallH=h;
    }
    private int wallW,wallH;
    private long dateStamp=-1;
    private String dateText="";
    private String clockText(){return fmt("h:mm");}
    private String fmt(String pattern){
        return Design.clock(System.currentTimeMillis(),pattern);
    }
    private boolean online(){
        try{
            ConnectivityManager cm=(ConnectivityManager)getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkCapabilities nc=cm.getNetworkCapabilities(cm.getActiveNetwork());
            return nc!=null&&nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        }catch(Throwable t){return true;}
    }
    private boolean focus(int rw,int cl){return hasFocus()&&row==rw&&col[rw]==cl;}
    private RectF ring(RectF src,boolean on){if(on){Design.grown(src,g);return g;}return src;}

    // perf probe: how long drawing the home screen takes on the UI thread (logged every 5 s, read by tools/test-redesign.py)
    private long pfFrames,pfOver16,pfOver32,pfMax,pfSum,pfLog;
    @Override public void draw(Canvas canvas){
        long t=System.nanoTime();
        super.draw(canvas);
        long us=(System.nanoTime()-t)/1000;
        pfFrames++;pfSum+=us;if(us>16000)pfOver16++;if(us>32000)pfOver32++;if(us>pfMax)pfMax=us;
        long now=android.os.SystemClock.uptimeMillis();
        if(now-pfLog>5000){pfLog=now;android.util.Log.i("FreeMyTV","perf-draw: frames="+pfFrames+" sumUs="+pfSum+" maxUs="+pfMax+" over16ms="+pfOver16+" over32ms="+pfOver32);}
    }

    @Override protected void onDraw(Canvas c){
        int w=getWidth(),h=getHeight();
        if(wallpaper!=null){cropDst.set(0,0,w,h);c.drawBitmap(wallpaper,null,cropDst,wallPaint);}   // already cropped to the screen's shape
        else c.drawColor(Design.BG);
        a11y.begin(k);
        c.save();c.scale(k,k);
        drawTop(c);
        boolean hasMatches=!today.isEmpty()||!confirmedEmpty();   // (after data has shown once, the row never goes back to empty cards)   // while loading the row shows empty cards of the same size
        boolean hasVod=vodShown();
        spread(hasMatches,hasVod);
        block(c,dy[0]);drawHero(c);drawPrayer(c);unblock(c);
        if(hasMatches){block(c,dy[1]);drawMatches(c);unblock(c);}
        if(hasVod){block(c,dy[2]);drawVod(c);unblock(c);}
        block(c,dy[3]);drawApps(c);unblock(c);
        block(c,dy[4]);drawShortcuts(c);unblock(c);
        Design.right(c,"◀ ▶ ▲ ▼ للتنقل · OK للفتح",1848,1034,tx(0x73F4EFE4,18,Design.regular()));
        Design.left(c,"الشاشة الرئيسية",72,1034,tx(0x73F4EFE4,18,Design.regular()));
        c.restore();
    }

    private void block(Canvas c,float by){c.save();c.translate(0,by);a11y.shift(by);}
    private void unblock(Canvas c){c.restore();a11y.shift(0);}
    private final boolean[] blockOn=new boolean[5];
    /**
     * Hero (with the prayer card), matches, films and series, apps and shortcuts get equal gaps between the top bar and
     * the hint line. The hero never starts above 142, clear of the date line under the clock (its letters reach about 136).
     */
    private void spread(boolean withMatches,boolean withVod){
        final float[] h={HERO_H+4,146f,VOD_BLOCK,175f,76f},top={163f,424f,0f,626f,828f};
        blockOn[0]=true;blockOn[1]=withMatches;blockOn[2]=withVod;blockOn[3]=true;blockOn[4]=true;
        float sum=0;int n=0;
        for(int i=0;i<5;i++)if(blockOn[i]){sum+=h[i];n++;}
        float gap=(998f-118f-sum)/n,y=118f+gap;
        if(y<142f){gap=(998f-142f-sum)/(n-1);y=142f;}
        for(int i=0;i<5;i++){
            if(!blockOn[i]){dy[i]=0;continue;}
            dy[i]=y-top[i];y+=h[i]+gap;
        }
    }

    private void drawTop(Canvas c){
        r.set(1788,58,1848,118);Design.gold(c,r,16,p);
        dark.setTextSize(34);Design.center(c,"ق",1818,100,dark);
        Design.right(c,"OUSWIS TV",1770,84,title);
        Design.right(c,fmt("EEEE")+" · جدة",1770,112,sub);
        a11y.add(1500,58,1848,118,"OUSWIS TV · "+fmt("EEEE")+" · جدة",false,false);
        String time=clockText();
        clock.setTextAlign(Paint.Align.CENTER);
        float tw=clock.measureText(time);
        Design.center(c,time,1050,104,clock);
        Design.right(c,fmt("a"),1050-tw/2-10,104,ampm);
        long nowMs=System.currentTimeMillis();
        if(dateStamp!=nowMs/60_000L){   // the date line (with the Hijri date) is worked out once a minute, not every frame
            dateStamp=nowMs/60_000L;
            SaudiDates dates=new SaudiDates();dates.update(nowMs,new Locale("ar"));
            dateText=Design.clock(nowMs,"d MMMM yyyy")+" · "+dates.hijri;
        }
        // the date sits under the clock's own descent with a 6dp (9 reference px) gap: the two lines never touch
        float dateBase=104+9+dateLine.getTextSize()*0.8f;   // the digits have no descender: their baseline is their bottom
        Design.center(c,dateText,1015,dateBase,dateLine);
        a11y.add(760,30,1340,dateBase+dateLine.descent(),"الساعة "+time+" "+fmt("a")+" · "+dateText,false,false);
        // chips: weather, connection, settings
        HomeData.Weather wx=HomeData.get(getContext()).weather;
        r.set(272,60,436,116);RectF box=ring(r,focus(0,0));Design.card(c,box,28,1,p,Design.SURFACE);
        p.setStyle(Paint.Style.FILL);p.setShader(null);p.setColor(Design.GOLD);
        c.drawCircle(box.right-30,box.centerY(),13,p);
        String t=Double.isNaN(wx.temp)?"—":Math.round(wx.temp)+"° "+shortCondition(wx);
        Design.center(c,Design.fitShrink(t,chip,104),box.left+66,box.centerY()+7,chip);
        a11y.add(box,"الطقس "+t,focus(0,0),true);
        if(focus(0,0))Design.focusRing(c,box,28,1,p);
        r.set(147,60,260,116);Design.card(c,r,28,1,p,Design.SURFACE);
        p.setStyle(Paint.Style.FILL);p.setColor(online()?Design.OK:Design.LIVE);c.drawCircle(238,88,6,p);
        Design.center(c,online()?"متصل":"غير متصل",190,95,chip);
        a11y.add(r,online()?"متصل":"غير متصل",false,false);
        r.set(72,60,135,116);box=ring(r,focus(0,1));Design.card(c,box,28,1,p,Design.SURFACE);
        Design.center(c,"⚙",box.centerX(),box.centerY()+8,chip);
        a11y.add(box,"الإعدادات",focus(0,1),true);
        if(focus(0,1))Design.focusRing(c,box,28,1,p);
    }
    /** One word for the chip (it is small): clear, cloudy, rainy... */
    private String shortCondition(HomeData.Weather wx){
        int code=wx.code;
        if(code<0)return "";
        if(code<=1)return wx.isDay?"صافٍ":"صافية";
        if(code<=3)return "غائم";
        if(code==45||code==48)return "ضباب";
        if(code>=71&&code<=77||code==85||code==86)return "ثلج";
        if(code>=95)return "عاصف";
        return "ممطر";
    }

    /** dp of the reference design: 1920 reference px across 1280 dp. */
    private static final float DP=1.5f;
    private static final int CARD_DARK=0xBF0B0D1A;   // #0B0D1A at 75 %: the skyline behind never competes with the text
    private final float[] dy=new float[5];   // vertical shift of each block so the rows share the screen evenly
    /** The last-channel and prayer cards' height (was 153 dp, 229.5 px): lower, so the films and series row fits (home_vod.png). */
    private static final float HERO_H=146f;
    private final RectF heroBox=new RectF();
    private final Paint heroFill=new Paint(Paint.ANTI_ALIAS_FLAG),heroStroke=new Paint(Paint.ANTI_ALIAS_FLAG);
    private float heroL,heroT,heroR,heroB;   // the box the gradient was made for
    private final Path heroClip=new Path();
    private final Paint goldText15=Design.text(Design.GOLD,15,Design.regular());

    /** The last-channel card: HERO_H tall, always, and everything inside it is clipped to it, so it can't cover the row below. */
    private void drawHero(Canvas c){
        heroBox.set(611,163,1850,163+HERO_H);RectF box=heroBox;   // its own rectangle: the shared r is changed by the lines drawn inside the card (that moved the ring and drew a stray dash). Focused or not the card is the same size and colour: only the ring appears
        Design.card(c,box,30,1,p,CARD_DARK);   // exactly the prayer card's colour, flat: no blue gradient
        heroStroke.setStyle(Paint.Style.STROKE);heroStroke.setStrokeWidth(1);heroStroke.setColor(Design.STROKE);c.drawRoundRect(box,30,30,heroStroke);
        LiveData.Channel ch=lastChannel();
        c.save();c.clipRect(box.left+2,box.top+2,box.right-2,box.bottom-2);
        // a 16:9 thumbnail as tall as the card allows, carrying the channel's logo (stream_icon), with the "live" badge
        float th=box.height()-28,tw=th*200f/113f;
        float pad=(box.height()-th)/2;
        RectF thumb=new RectF(box.right-pad-tw,box.top+pad,box.right-pad,box.top+pad+th);
        Design.fill(c,thumb,18,p,0xFF1A1F3A);   // dark tile: no green anywhere
        float x=thumb.left-30;
        if(ch!=null){
            Bitmap logo=ch.icon.isEmpty()?null:ImageCache.get(ch.icon,this::postInvalidate);
            if(logo!=null){
                float sc=Math.min(tw*0.72f/logo.getWidth(),th*0.62f/logo.getHeight());
                float lw=logo.getWidth()*sc,lh=logo.getHeight()*sc;
                bm.set(thumb.centerX()-lw/2,thumb.centerY()-lh/2,thumb.centerX()+lw/2,thumb.centerY()+lh/2);p.setColor(0xFFFFFFFF);c.drawBitmap(logo,null,bm,p);
            }else{   // no logo: the channel's short name on the dark tile
                String shortName=shortName(Titles.channelName(ch.name));
                Paint sn=Design.text(Design.TEXT,46,Design.bold());
                while(sn.getTextSize()>26&&sn.measureText(shortName)>tw-40)sn.setTextSize(sn.getTextSize()-2);   // smaller, never cut
                Design.center(c,shortName,thumb.centerX(),thumb.centerY()+sn.getTextSize()*0.35f,sn);
            }
            Design.liveBadge(c,thumb.right-52,thumb.top+26,"مباشر",15,p,badge);
        }
        if(ch==null){
            Design.right(c,"ابدأ المشاهدة",x,box.top+box.height()*0.5f+2,tx(Design.TEXT,38,Design.bold()));
            Design.right(c,"افتح البث المباشر واختر قناة",x,box.top+box.height()*0.5f+40,tx(Design.MUTED,22,Design.regular()));
        }else{
            Design.right(c,"آخر قناة شاهدتها",x,box.top+30,tx(Design.GOLD,20,Design.regular()));
            Paint nameP=tx(Design.TEXT,36,Design.bold());
            Design.right(c,Design.fit(Titles.channelName(ch.name),nameP,x-box.left-40),x,box.top+70,nameP);
            long now=System.currentTimeMillis();
            List<LiveData.Program> guide=live==null?null:live.guide(ch.id);
            LiveData.Program cur=LiveData.current(guide,now),nxt=LiveData.following(guide,now);
            if(cur!=null){guideFor=ch.id;guideCur=cur;guideNext=nxt;}
            else if(guideFor!=null&&guideFor.equals(ch.id)&&guideCur!=null&&guideCur.end>now){cur=guideCur;nxt=guideNext;}   // the guide is read again in the new view: show what was known meanwhile
            if(cur==null)programLine(c,"الآن","","بث مباشر",x,box.left+40,box.top+100);   // a channel with no programme guide still says what it is
            if(cur!=null){
                programLine(c,"الآن","",cur.shown,x,box.left+40,box.top+100);
                float f=Math.max(0,Math.min(1,(now-cur.start)/(float)Math.max(1,cur.end-cur.start)));
                r.set(box.left+40,box.bottom-8,x,box.bottom-5);Design.fill(c,r,2,p,0x1FFFFFFF);
                r.set(x-(x-box.left-40)*f,box.bottom-8,x,box.bottom-5);Design.fill(c,r,2,p,Design.GOLD);
            }
            if(nxt!=null)programLine(c,"التالي",fmt("h:mm a",nxt.start),nxt.shown,x,box.left+40,box.top+128);
        }
        c.restore();
        a11y.add(box,heroLabel(ch),focus(1,0),true);
        if(focus(1,0))Design.focusRing(c,box,30,1,p);
    }
    private final Paint programText=Design.text(Design.MUTED,19.5f,Design.regular()),tag=Design.text(Design.GOLD,15,Design.bold());   // 13sp, one line
    /**
     * One programme line: a small pill ("الآن" / "التالي") at the right, then the title after it on a single line,
     * cut with "…" when it is too long. The title sits inside a bidi isolate so English words can't reorder the line.
     */
    private void programLine(Canvas c,String label,String time,String title,float right,float leftLimit,float base){
        float pw=tag.measureText(label)+22;
        r.set(right-pw,base-19,right,base+6);Design.fill(c,r,13,p,0x2EF2C14E);
        Design.center(c,label,right-pw/2,base-1,tag);
        float x=right-pw-12,room=x-leftLimit;
        String prefix=time.isEmpty()?"":time+"  ";
        String body=Design.fit(title==null?"":title,programText,Math.max(40,room-programText.measureText(prefix)));
        Design.right(c,prefix+"\u2068"+body+"\u2069",x,base,programText);
    }
    /** "beIN SPORTS 2 HD" → "beIN 2", "FR|TF1 HD" → "TF1": the country prefix, quality words and "SPORTS" go. */
    static String shortName(String name){
        String n=name==null?"":name;
        int bar=n.indexOf('|');if(bar>=0&&bar<6)n=n.substring(bar+1);
        n=n.replaceAll("(?i)(?<=\\d)(FHD|UHD|HD|SD|4K)\\b","").replaceAll("(?i)\\b(FHD|UHD|HD|SD|4K|HEVC|RAW|VIP|SPORTS?|TV)\\b"," ");
        n=n.replaceAll("[\\[\\]()]"," ").replaceAll("\\s{2,}"," ").trim();
        return n.isEmpty()?(name==null?"":name.trim()):n;
    }
    private static String guideFor;   // the last channel's programmes, kept across views so the card never changes text on coming back
    private static LiveData.Program guideCur,guideNext;
    private String heroLabel(LiveData.Channel ch){
        if(ch==null)return "ابدأ المشاهدة · افتح البث المباشر واختر قناة";
        long now=System.currentTimeMillis();
        List<LiveData.Program> guide=live==null?null:live.guide(ch.id);
        LiveData.Program cur=LiveData.current(guide,now);
        return "آخر قناة شاهدتها: "+Titles.channelName(ch.name)+(cur==null?" · الآن: بث مباشر":" · الآن: "+cur.shown);
    }
    private String fmt(String pattern,long at){
        return Design.clock(at,pattern);
    }

    private void drawPrayer(Canvas c){
        r.set(72,167,591,167+HERO_H);RectF box=ring(r,focus(1,1));
        Design.card(c,box,30,1,p,CARD_DARK);
        PrayerTimes pt=PrayerTimes.get(getContext());
        PrayerSchedule day=pt.today();long now=System.currentTimeMillis();
        PrayerCountdown next=PrayerCountdown.next(day,pt.tomorrow(),now);
        if(next==null||day==null){
            Design.center(c,pt.failed?"تعذّر تحميل مواقيت الصلاة":"جارٍ تحميل المواقيت…",box.centerX(),box.centerY(),tx(Design.MUTED,26,Design.regular()));
        }else{
            long remaining=Math.max(0,next.at-now);
            long minutes=(remaining+59_999)/60_000;
            long prev=next.index>0?next.schedule.time(next.index-1):next.at-7*3600_000L;
            float f=Math.max(0,Math.min(1,(now-prev)/(float)Math.max(1,next.at-prev)));
            float cx=490,cy=167+HERO_H/2,rad=50;
            arc.set(cx-rad,cy-rad,cx+rad,cy+rad);
            p.setShader(null);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(10);p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(0x1AFFFFFF);c.drawArc(arc,0,360,false,p);
            p.setColor(Design.GOLD);c.drawArc(arc,-90,360*f,false,p);p.setStrokeCap(Paint.Cap.BUTT);
            Design.center(c,String.format(Locale.US,"%d:%02d",minutes/60,minutes%60),cx,cy+8,tx(Design.TEXT,32,Design.bold()));
            Design.center(c,"متبقي",cx,cy+27,tx(Design.MUTED,13,Design.regular()));
            boolean tomorrow=!Design.clock(next.at,"yyyyMMdd").equals(Design.clock(now,"yyyyMMdd"));
            Design.right(c,tomorrow?"الصلاة القادمة · غدًا":"الصلاة القادمة",405,167+32,tx(Design.MUTED,19,Design.regular()));
            Paint nameP=tx(Design.TEXT,42,Design.bold());
            String name=Design.prayer(next.index);
            Design.right(c,name,405,167+78,nameP);
            Design.right(c,Design.clock(next.at,"h:mm a"),405-nameP.measureText(name)-14,167+78,tx(Design.GOLD,24,Design.bold()));
            List<String> others=new ArrayList<>();
            for(int i=0;i<5;i++)if(i!=next.index)others.add(Design.prayer(i)+" "+Design.clock(day.time(i),"h:mm"));
            Paint line=tx(Design.MUTED,18,Design.regular());
            Design.right(c,others.get(0)+" · "+others.get(1),405,167+107,line);
            Design.right(c,others.get(2)+" · "+others.get(3),405,167+132,line);
            String left=minutes<60?minutes+" دقيقة":(minutes/60)+" س "+(minutes%60)+" د";
            StringBuilder all=new StringBuilder("مواقيت الصلاة · الصلاة القادمة: "+name+(tomorrow?" · غدًا":"")+" "+Design.clock(next.at,"h:mm a")+" · المتبقي "+left);
            for(int i=0;i<5;i++)if(i!=next.index)all.append(" · ").append(Design.prayer(i)).append(" ").append(Design.clock(day.time(i),"h:mm"));
            a11y.add(box,all.toString(),focus(1,1),true);
        }
        if(focus(1,1))Design.focusRing(c,box,30,1,p);
    }

    private void drawMatches(Canvas c){
        Design.right(c,"مباريات اليوم",1842,442,section);   // above the cards with room for the focused card, which grows
        goldText.setTextSize(20);
        boolean linkFocus=focus(2,Math.min(today.size(),4));
        Design.left(c,"عرض الكل ←",78,440,linkFocus?tx(0xFFFFFFFF,22,Design.bold()):goldText);
        a11y.add(1500,420,1842,452,"مباريات اليوم",false,false);
        a11y.add(70,420,200,452,"عرض الكل",linkFocus,true);
        int n=Math.min(4,today.size());
        if(today.isEmpty()){
            if(everHadData)skeletonAfterData++;
            // placeholders: same size and place as the cards, nothing to focus
            for(int i=0;i<4;i++){
                float right=1847-i*425,left=right-404;
                r.set(left,466,right,570);
                Design.card(c,r,22,1,p,CARD_DARK);
                p.setStyle(Paint.Style.FILL);p.setShader(null);p.setColor(0x14FFFFFF);
                c.drawCircle(right-67,506,22,p);c.drawCircle(left+67,506,22,p);
                bm.set((left+right)/2-60,478,(left+right)/2+60,498);Design.fill(c,bm,10,p,0x1AFFFFFF);
                bm.set((left+right)/2-80,512,(left+right)/2+80,538);Design.fill(c,bm,13,p,0x14FFFFFF);
            }
            return;
        }
        for(int i=0;i<n;i++){
            MatchCarouselView.Match m=today.get(i);
            float right=1847-i*425,left=right-404;
            r.set(left,466,right,570);RectF box=ring(r,focus(2,i));
            boolean done=!m.live()&&!m.upcoming();
            Design.card(c,box,22,1,p,CARD_DARK);   // a live card is the regular card: only the red badge says it is live
            crest(c,m.homeLogo,m.home,m.homeEn,right-67,506,22);crest(c,m.awayLogo,m.away,m.awayEn,left+67,506,22);
            Paint team=tx(Design.TEXT,17,Design.regular());
            Design.center(c,Design.fitShrink(m.home,team,140),right-67,552,team);
            Design.center(c,Design.fitShrink(m.away,team,140),left+67,552,team);
            float cx=(left+right)/2;
            if(m.live()){
                Design.liveBadge(c,cx,484,minute(m),15,p,badge);
                Design.center(c,scoreText(m),cx,530,tx(Design.TEXT,38,Design.bold()));
            }else if(done){
                if(m.awayScore.isEmpty()&&m.homeScore.isEmpty())Design.center(c,"انتهت",cx,526,tx(0x99F4EFE4,32,Design.bold()));   // no score known: the word alone, no dash
                else{
                    Design.center(c,"انتهت",cx,486,tx(Design.MUTED,15,Design.regular()));
                    Design.center(c,scoreText(m),cx,530,tx(0x99F4EFE4,38,Design.bold()));
                }
            }else{
                Design.center(c,"اليوم",cx,486,tx(Design.MUTED,15,Design.regular()));
                Design.center(c,fmt("h:mm a",m.kickoffMillis),cx,528,tx(Design.TEXT,34,Design.bold()));
            }
            LiveData.Channel ch=channelFor(m);
            boolean known=MatchChannels.resolved(getContext(),m);   // until it is known the line stays empty: no "اختر القناة" that then turns into a name
            if(ch!=null&&!done)Design.center(c,(m.live()?"▶ ":"")+Design.fit(MatchChannels.shortName(ch.name),goldText15,360),cx,556,goldText15);
            else if(!done&&known)Design.center(c,"اختر القناة",cx,556,tx(Design.MUTED,15,Design.regular()));
            a11y.add(box,m.home+" ضد "+m.away+" · "+(m.live()?"مباشر "+scoreText(m):done?"انتهت "+scoreText(m):fmt("h:mm a",m.kickoffMillis))+(ch!=null&&!done?" · "+MatchChannels.shortName(ch.name):!done&&known?" · اختر القناة":""),focus(2,i),true);
            if(focus(2,i))Design.focusRing(c,box,22,1,p);
        }
    }
    private LiveData.Channel channelFor(MatchCarouselView.Match m){
        return account==null?null:MatchChannels.forMatch(getContext(),account,m);
    }
    private String minute(MatchCarouselView.Match m){
        String d=m.status==null?"":m.status.replaceAll("[^0-9+]","");return d.isEmpty()?"مباشر":d+"'";   // the minute, or just the word: never a bare mark
    }
    private void crest(Canvas c,String url,String name,String nameEn,float cx,float cy,float radius){
        Bitmap b=ClubBadges.get(url,nameEn,this::postInvalidate);
        if(b!=null){
            p.setStyle(Paint.Style.FILL);p.setShader(null);p.setColor(0x14FFFFFF);c.drawCircle(cx,cy,radius,p);
            float s=Math.min(radius*1.5f/b.getWidth(),radius*1.5f/b.getHeight());
            float w=b.getWidth()*s,h=b.getHeight()*s;bm.set(cx-w/2,cy-h/2,cx+w/2,cy+h/2);p.setColor(0xFFFFFFFF);c.drawBitmap(b,null,bm,p);
        }else if(ClubBadges.none(url,nameEn))Design.shield(c,cx,cy,radius,p);
            else Design.initial(c,name,cx,cy,radius,p,badge);
    }

    private void drawApps(Canvas c){
        Design.right(c,"تطبيقاتي",1842,646,section);
        a11y.add(1600,620,1842,656,"تطبيقاتي",false,false);
        int n=apps.size();
        // the row scrolls sideways: keep the focused tile inside the window of APP_SLOTS tiles
        if(col[4]<appFirst)appFirst=col[4];
        if(col[4]>=appFirst+APP_SLOTS)appFirst=col[4]-APP_SLOTS+1;
        appFirst=Math.max(0,Math.min(appFirst,Math.max(0,n+1-APP_SLOTS)));
        Paint more=tx(Design.GOLD,44,Design.bold());
        if(appFirst>0)Design.center(c,"›",1888,732,more);
        if(appFirst+APP_SLOTS<n+1)Design.center(c,"‹",32,732,more);
        for(int i=appFirst;i<n&&i<appFirst+APP_SLOTS;i++){
            HomeApps.Item it=apps.get(i);
            float right=1848-(i-appFirst)*198;
            r.set(right-178,662,right,770);RectF box=ring(r,focus(4,i));
            if(it.logo!=null){
                Design.card(c,box,22,1,p,0xFF121528);
                float s=Math.min(box.width()*0.7f/it.logo.getWidth(),box.height()*0.55f/it.logo.getHeight());
                float lw=it.logo.getWidth()*s,lh=it.logo.getHeight()*s;
                bm.set(box.centerX()-lw/2,box.centerY()-lh/2,box.centerX()+lw/2,box.centerY()+lh/2);
                p.setColor(0xFFFFFFFF);c.drawBitmap(it.logo,null,bm,p);
            }else if(it.tile!=null){
                path.reset();path.addRoundRect(box,22,22,Path.Direction.CW);
                p.setColor(0xFFFFFFFF);c.save();c.clipPath(path);c.drawBitmap(it.tile,null,box,p);c.restore();
            }else Design.card(c,box,22,1,p,Design.SURFACE);
            a11y.add(box,it.label,focus(4,i),true);
            if(focus(4,i))Design.focusRing(c,box,22,1,p);
            Design.center(c,Design.fit(it.label,tx(Design.MUTED,18,Design.regular()),170),right-89,796,tx(focus(4,i)?Design.TEXT:Design.MUTED,18,Design.regular()));
        }
        // the dashed "+ add" tile closes the row
        if(n<appFirst+APP_SLOTS){
        float right=1848-(n-appFirst)*198;
        r.set(right-178,662,right,770);RectF box=ring(r,focus(4,n));
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2);p.setColor(0x4DFFFFFF);
        p.setPathEffect(new android.graphics.DashPathEffect(new float[]{10,8},0));
        c.drawRoundRect(box,22,22,p);p.setPathEffect(null);
        Design.center(c,"+",box.centerX(),box.centerY()+14,tx(Design.TEXT,48,Design.light()));
        a11y.add(box,"إضافة تطبيق",focus(4,n),true);
        if(focus(4,n))Design.focusRing(c,box,22,1,p);
        Design.center(c,"إضافة",right-89,796,tx(Design.MUTED,18,Design.regular()));
        }
    }

    private static final float VOD_W=137f,VOD_H=205f,VOD_PITCH=153f,VOD_TOP=40f,VOD_BLOCK=VOD_TOP+VOD_H;
    private static final int VOD_SLOTS=11;
    private int vodFirst;   // index of the first poster showing in the row
    private final VodDraw vodPoster=new VodDraw(VOD_W,VOD_H,18);
    private final Runnable posterArrived=this::postInvalidate;
    private int vodFocus=-1;
    private long vodFocusAt;
    private boolean vodMarquee;
    private final Runnable vodMarqueeTick=()->{vodMarquee=false;invalidate();};
    /** The films and series row: a heading with "عرض الكل", then posters (right to left), scrolling sideways with the focus. */
    private void drawVod(Canvas c){
        Paint icon=tx(Design.GOLD,22,Design.regular());
        Design.right(c,"🎬",1842,18,icon);
        float x=1842-icon.measureText("🎬")-10;
        Design.right(c,"أفلام ومسلسلات",x,18,section);
        Paint note=tx(Design.MUTED,17,Design.regular());
        float after=x-section.measureText("أفلام ومسلسلات");
        Design.center(c,"·",after-14,18,note);   // the dot on its own: at the start of the line it would sit against the heading
        Design.right(c,"تابع المشاهدة ثم أحدث الإضافات",after-28,18,note);
        a11y.add(1300,-6,1842,26,"أفلام ومسلسلات",false,false);
        int n=vodItems.size();
        boolean linkFocus=focus(3,n);
        goldText.setTextSize(20);
        Design.left(c,"عرض الكل ←",78,16,linkFocus?tx(0xFFFFFFFF,22,Design.bold()):goldText);
        a11y.add(70,-6,200,26,"عرض الكل · الأفلام",linkFocus,n>0);
        if(n==0){   // not known yet: empty cards of the same size, nothing to focus
            for(int i=0;i<VOD_SLOTS;i++){
                float right=1848-i*VOD_PITCH;
                r.set(right-VOD_W,VOD_TOP,right,VOD_TOP+VOD_H);Design.card(c,r,14,1,p,CARD_DARK);
            }
            return;
        }
        if(!(hasFocus()&&row==3))vodFocus=-1;
        int at=col[3];
        if(at<n){if(at<vodFirst)vodFirst=at;if(at>=vodFirst+VOD_SLOTS)vodFirst=at-VOD_SLOTS+1;}
        else vodFirst=Math.max(0,n-VOD_SLOTS);
        vodFirst=Math.max(0,Math.min(vodFirst,Math.max(0,n-VOD_SLOTS)));
        Paint more=tx(Design.GOLD,44,Design.bold());
        if(vodFirst>0)Design.center(c,"›",1888,VOD_TOP+VOD_H/2+14,more);
        if(vodFirst+VOD_SLOTS<n)Design.center(c,"‹",32,VOD_TOP+VOD_H/2+14,more);
        for(int i=vodFirst;i<n&&i<vodFirst+VOD_SLOTS;i++){
            VodData.RowItem it=vodItems.get(i);
            float right=1848-(i-vodFirst)*VOD_PITCH;
            r.set(right-VOD_W,VOD_TOP,right,VOD_TOP+VOD_H);RectF box=ring(r,focus(3,i));
            Bitmap b=PosterCache.get(it.m.icon,PosterCache.GRID,posterArrived);
            long scroll=-1;
            if(focus(3,i)){if(i!=vodFocus){vodFocus=i;vodFocusAt=android.os.SystemClock.uptimeMillis();}scroll=android.os.SystemClock.uptimeMillis()-vodFocusAt;}
            if(vodPoster.drawHome(c,box,b,it.m.shown,it.tag,it.progress,scroll)&&!vodMarquee){vodMarquee=true;postDelayed(vodMarqueeTick,33);}   // a long focused name scrolls, about 30 frames a second
            a11y.add(box,it.m.shown+" · "+it.tag+(it.progress>=0?" · تابع المشاهدة "+Math.round(it.progress*100)+"%":""),focus(3,i),true);
            if(focus(3,i))Design.focusRing(c,box,14,1,p);
        }
        for(int i=vodFirst+VOD_SLOTS;i<Math.min(n,vodFirst+VOD_SLOTS+2);i++)PosterCache.get(vodItems.get(i).m.icon,PosterCache.GRID,null);   // the next two, ahead
    }

    private static final String[] SHORTCUT_LABELS={"البث المباشر","أفلام","مسلسلات","المباريات","كل التطبيقات","الأخيرة","التحديثات","الإعدادات"};
    private static final String[] SHORTCUT_ICONS={"▣","🎬","📺","⚽","⠿","↻","⤓","⚙"};
    private final float[] shortcutRight=new float[SHORTCUT_LABELS.length],shortcutWidth=new float[SHORTCUT_LABELS.length];
    /** Eight across the row: each as wide as its words and icon, the room left shared out evenly, 16 apart. Worked out once. */
    private void layoutShortcuts(Paint lab){
        if(shortcutWidth[0]>0)return;
        int n=SHORTCUT_LABELS.length;float sum=0;
        for(int i=0;i<n;i++){shortcutWidth[i]=lab.measureText(SHORTCUT_LABELS[i])+30+56;sum+=shortcutWidth[i];}
        float extra=Math.max(0,(1775-16*(n-1)-sum)/n),right=1847;
        for(int i=0;i<n;i++){shortcutWidth[i]+=extra;shortcutRight[i]=right;right-=shortcutWidth[i]+16;}
    }
    private void drawShortcuts(Canvas c){
        Paint lab=tx(Design.TEXT,23,Design.bold());
        layoutShortcuts(lab);
        for(int i=0;i<SHORTCUT_LABELS.length;i++){
            float right=shortcutRight[i];
            r.set(right-shortcutWidth[i],828,right,904);RectF box=ring(r,focus(5,i));
            Design.card(c,box,26,1,p,Design.SURFACE);
            float tw=lab.measureText(SHORTCUT_LABELS[i]);
            Design.center(c,SHORTCUT_LABELS[i],box.centerX()-12,box.centerY()+8,lab);
            Design.center(c,SHORTCUT_ICONS[i],box.centerX()-12+tw/2+30,box.centerY()+9,tx(Design.GOLD,26,Design.bold()));
            a11y.add(box,SHORTCUT_LABELS[i],focus(5,i),true);
            if(focus(5,i))Design.focusRing(c,box,26,1,p);
        }
    }

    // MARK: keys

    private static final int APP_SLOTS=8;
    private int appFirst;   // index of the first tile showing in the apps row
    private int rowSize(int rw){
        switch(rw){
            case 0:return 2;
            case 1:return 2;
            case 2:return Math.min(today.size(),4)+1;
            case 3:return vodItems.size()+1;
            case 4:return apps.size()+1;
            default:return SHORTCUT_LABELS.length;
        }
    }
    private boolean rowShown(int rw){return rw==2?!today.isEmpty():rw==3?vodShown()&&!vodItems.isEmpty():true;}
    @Override public boolean onKeyDown(int key,KeyEvent e){
        switch(key){
            case KeyEvent.KEYCODE_DPAD_DOWN:{
                int nx=row+1;while(nx<6&&!rowShown(nx))nx++;
                if(nx<6)row=nx;invalidate();return true;}
            case KeyEvent.KEYCODE_DPAD_UP:{
                int nx=row-1;while(nx>=0&&!rowShown(nx))nx--;
                if(nx>=0)row=nx;invalidate();return true;}
            case KeyEvent.KEYCODE_DPAD_RIGHT:   // RTL: index 0 is on the right
                if(col[row]>0)col[row]--;invalidate();return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if(col[row]<rowSize(row)-1)col[row]++;invalidate();return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:case KeyEvent.KEYCODE_ENTER:
                if(e.getRepeatCount()==0)activate();return true;
        }
        return super.onKeyDown(key,e);
    }
    private void start(Intent i){
        try{getContext().startActivity(i,android.app.ActivityOptions.makeCustomAnimation(getContext(),0,0).toBundle());}   // no slide or fade: the new screen is simply there
        catch(RuntimeException e){getContext().startActivity(i);}
    }
    private void activate(){
        Context ctx=getContext();
        int cl=col[row];
        switch(row){
            case 0:
                if(cl==0)weatherDetails();
                else start(new Intent().setComponent(new ComponentName(ctx,SettingsActivity.class)));
                break;
            case 1:
                if(cl==0){
                    LiveData.Channel ch=lastChannel();
                    if(ch!=null&&account!=null)start(PlayerActivity.intent(ctx,ch,""));   // the player finds the channel's own section
                    else start(new Intent(ctx,LiveActivity.class));
                }else prayerDetails();
                break;
            case 2:{
                int n=Math.min(4,today.size());
                if(cl>=n){start(new Intent(ctx,MatchesScreenActivity.class));break;}
                MatchCarouselView.Match m=today.get(cl);
                LiveData.Channel ch=channelFor(m);
                if(ch==null)start(new Intent(ctx,LiveActivity.class).putExtra(LiveActivity.EXTRA_MATCH_KEY,m.key).putExtra(LiveActivity.EXTRA_SUGGESTED,android.text.TextUtils.join(",",MatchChannels.suggestions(ctx,m))));   // not confirmed: pick it from the sports list
                else if(m.live())start(MatchChannels.linkedIntent(ctx,ch,m));
                else start(new Intent(ctx,MatchesScreenActivity.class));
                break;}
            case 3:{
                int n=vodItems.size();
                if(cl>=n){start(new Intent(ctx,MoviesActivity.class));break;}   // "عرض الكل" opens the films screen
                VodData.RowItem it=vodItems.get(cl);
                start(it.series?SeriesActivity.intent(ctx,it.m,""):MoviesActivity.details(ctx,it.m,""));
                break;}
            case 4:{
                int n=apps.size();
                if(cl<n&&ctx instanceof android.app.Activity)HomeApps.open((android.app.Activity)ctx,apps.get(cl),this,0,0,0,0);
                else addApp();
                break;}
            default:
                switch(cl){
                    case 0:start(new Intent(ctx,LiveActivity.class));break;
                    case 1:start(new Intent(ctx,MoviesActivity.class));break;
                    case 2:start(new Intent(ctx,MoviesActivity.class).putExtra(MoviesActivity.EXTRA_SERIES,true));break;
                    case 3:start(new Intent(ctx,MatchesScreenActivity.class));break;
                    case 4:start(new Intent(ctx,AllAppsActivity.class));break;
                    case 5:host.openClassic(true);break;
                    case 6:start(new Intent(ctx,UpdatesActivity.class));break;
                    default:start(new Intent().setComponent(new ComponentName(ctx,SettingsActivity.class)));
                }
        }
    }
    /** The "+ إضافة" tile: the installed apps that aren't in the row yet, in a list; the one picked joins the row. */
    private void addApp(){
        final Context ctx=getContext();
        io.execute(()->{
            final List<String> have=HomeApps.shelfPackages(ctx);
            final List<String[]> free=new ArrayList<>();
            try{for(String[] a:HomeApps.shelfCandidates(ctx))if(!have.contains(a[0]))free.add(a);}catch(Throwable ignored){}
            final int room=HomeApps.maxShelf(ctx)-apps.size();
            main.post(()->{
                if(!resumed)return;
                if(room<=0){
                    new AlertDialog.Builder(ctx).setTitle("صف التطبيقات ممتلئ").setMessage("هذا التلفاز قليل الذاكرة ويتّسع الصف فيه لـ "+HomeApps.maxShelf(ctx)+" تطبيقات. أزل تطبيقًا من الإعدادات ثم أضف غيره.").setPositiveButton("حسناً",null).show();
                    return;
                }
                if(free.isEmpty()){
                    new AlertDialog.Builder(ctx).setTitle("لا توجد تطبيقات لإضافتها").setMessage("كل التطبيقات المثبّتة في الصف بالفعل.").setPositiveButton("حسناً",null).show();
                    return;
                }
                String[] names=new String[free.size()];
                for(int i=0;i<names.length;i++)names[i]=free.get(i)[1];
                new AlertDialog.Builder(ctx).setTitle("اختر تطبيقًا لإضافته").setItems(names,(d,which)->{
                    List<String> list=new ArrayList<>(have);list.add(free.get(which)[0]);
                    Prefs.setShelf(list);
                    loadApps();
                }).setNegativeButton("إلغاء",null).show();
            });
        });
    }
    private void weatherDetails(){
        HomeData.Weather wx=HomeData.get(getContext()).weather;
        if(Double.isNaN(wx.temp))return;
        String text=Design.weather(wx.code,wx.isDay)+"\n"
                +"الصغرى "+Math.round(wx.min)+"° · العظمى "+Math.round(wx.max)+"°\n"
                +(Double.isNaN(wx.humidity)?"":"الرطوبة "+Math.round(wx.humidity)+"%\n")
                +(Double.isNaN(wx.wind)?"":"الرياح "+Math.round(wx.wind)+" كم/س\n")
                +(Double.isNaN(wx.rain)?"":"احتمال المطر "+Math.round(wx.rain)+"%");
        new AlertDialog.Builder(getContext()).setTitle("الطقس في جدة · "+Math.round(wx.temp)+"°").setMessage(text).setPositiveButton("حسناً",null).show();
    }
    private void prayerDetails(){
        PrayerSchedule day=PrayerTimes.get(getContext()).today();
        if(day==null)return;
        StringBuilder sb=new StringBuilder();
        for(int i=0;i<5;i++)sb.append(Design.prayer(i)).append("  ").append(Design.clock(day.time(i),"h:mm a")).append("\n");
        new AlertDialog.Builder(getContext()).setTitle("مواقيت الصلاة · جدة · أم القرى").setMessage(sb.toString().trim()).setPositiveButton("حسناً",null).show();
    }
}
