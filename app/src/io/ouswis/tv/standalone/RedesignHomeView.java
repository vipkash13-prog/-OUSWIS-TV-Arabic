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
    private int row=1;
    private final int[] col=new int[5];
    private final List<MatchCarouselView.Match> today=new ArrayList<>(),apiToday=new ArrayList<>();   // today: the feed's matches plus the guide's
    private List<HomeApps.Item> apps=new ArrayList<>();
    private LiveData live;
    private XtreamAccount account;
    private boolean resumed;
    private long matchesAt;
    private final Runnable tick=new Runnable(){@Override public void run(){
        if(!resumed)return;
        invalidate();
        refreshData();
        main.postDelayed(this,30_000);
    }};
    private final Runnable prayerChanged=this::postInvalidate;
    private final Runnable guideChanged=()->{rebuildToday();clampColumns();postInvalidate();};
    /** The feed's matches of today plus those found in the sports channels' guides, live first, then by kickoff. */
    private void rebuildToday(){
        long start=dayStart(),end=start+24L*3600_000;
        List<MatchCarouselView.Match> list=new ArrayList<>();
        for(MatchCarouselView.Match m:MatchChannels.withGuide(apiToday))if(m.live()||(m.kickoffMillis>=start&&m.kickoffMillis<end))list.add(m);
        java.util.Collections.sort(list,(a,b)->{
            int ra=a.live()?0:a.upcoming()?1:2,rb=b.live()?0:b.upcoming()?1:2;
            return ra!=rb?Integer.compare(ra,rb):Long.compare(a.kickoffMillis,b.kickoffMillis);
        });
        today.clear();today.addAll(list);
    }
    private String scoreText(MatchCarouselView.Match m){return m.awayScore.isEmpty()&&m.homeScore.isEmpty()?"—":m.awayScore+" - "+m.homeScore;}

    RedesignHomeView(Context c,Host host){
        super(c);this.host=host;
        setFocusable(true);setFocusableInTouchMode(true);
        dateLine.setShadowLayer(3f,0,1.5f,0x99000000);
        account=XtreamAccount.read(c);
        if(account!=null)live=new LiveData(c,account);
    }

    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();MatchChannels.listen(prayerChanged);MatchChannels.listen(guideChanged);}
    @Override protected void onDetachedFromWindow(){MatchChannels.unlisten(prayerChanged);MatchChannels.unlisten(guideChanged);super.onDetachedFromWindow();}

    // MARK: lifecycle

    void resume(){
        resumed=true;
        account=XtreamAccount.read(getContext());
        if(account!=null&&live==null)live=new LiveData(getContext(),account);
        PrayerTimes.get(getContext()).addListener(prayerChanged);
        main.removeCallbacks(tick);main.post(tick);
        loadApps();
    }
    void pause(){resumed=false;main.removeCallbacks(tick);PrayerTimes.get(getContext()).removeListener(prayerChanged);}
    void destroy(){pause();io.shutdownNow();if(live!=null)live.shutdown();}
    void reset(){row=1;col[1]=0;invalidate();}

    private void refreshData(){
        HomeData.get(getContext()).refreshIfStale();
        PrayerTimes.get(getContext()).refreshIfStale();
        long now=System.currentTimeMillis();
        LiveData.Channel last=LiveData.lastWatched(getContext());
        if(live!=null&&last!=null)live.loadGuide(last.id,this::postInvalidate);
        if(now-matchesAt>(anyLive()?60_000L:15*60_000L)){
            matchesAt=now;
            io.execute(()->{
                List<MatchCarouselView.Match> found=new ArrayList<>();
                try{
                    for(String section:new String[]{"live","upcoming","today"})found.addAll(UpcomingMatches.load(null,section).matches);
                }catch(Throwable ignored){}
                final List<MatchCarouselView.Match> shown=new ArrayList<>();
                long start=dayStart(),end=start+24L*3600_000;
                java.util.Set<String> seen=new java.util.HashSet<>();
                for(MatchCarouselView.Match m:found)
                    if((m.live()||(m.kickoffMillis>=start&&m.kickoffMillis<end))&&seen.add(m.key))shown.add(m);
                java.util.Collections.sort(shown,(a,b)->{
                    int ra=a.live()?0:a.upcoming()?1:2,rb=b.live()?0:b.upcoming()?1:2;
                    return ra!=rb?Integer.compare(ra,rb):Long.compare(a.kickoffMillis,b.kickoffMillis);
                });
                main.post(()->{
                    // a failed load keeps what was there rather than hiding the section
                    if(!found.isEmpty()||apiToday.isEmpty()){apiToday.clear();apiToday.addAll(shown);}
                    rebuildToday();clampColumns();invalidate();
                });
            });
            if(account!=null)MatchChannels.prepare(getContext(),account,()->{MatchChannels.scanGuides(getContext(),account);postInvalidate();});
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
            main.post(()->{apps=result;clampColumns();invalidate();});
        });
    }
    private void clampColumns(){
        col[2]=Math.min(col[2],Math.min(today.size(),4));          // cards 0..n-1, "view all" = n
        col[3]=Math.min(col[3],apps.size());          // apps 0..n-1, "+" = n; the row scrolls, so no cap
    }

    // MARK: drawing

    @Override protected void onSizeChanged(int w,int h,int ow,int oh){
        k=w/1920f;
        // the supplied wallpaper, nothing laid over it; the 4K file only on a 4K panel with memory to spare
        boolean big=w>=3000&&!HomeApps.lowRam(getContext());
        String name=big?"backgrounds/ouswis_bg_4k.webp":"backgrounds/ouswis_bg_1080p.webp";
        try(InputStream in=getContext().getAssets().open(name)){wallpaper=BitmapFactory.decodeStream(in);}catch(Exception ignored){wallpaper=null;}
    }
    private String clockText(){return fmt("h:mm");}
    private String fmt(String pattern){
        SimpleDateFormat f=new SimpleDateFormat(pattern,Design.AR);
        f.setTimeZone(TimeZone.getTimeZone("Asia/Riyadh"));return f.format(new Date());
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

    @Override protected void onDraw(Canvas c){
        int w=getWidth(),h=getHeight();
        if(wallpaper!=null){   // centerCrop
            float s=Math.max(w/(float)wallpaper.getWidth(),h/(float)wallpaper.getHeight());
            float sw=w/s,sh=h/s;
            android.graphics.Rect src=cropRect;src.set((int)((wallpaper.getWidth()-sw)/2),(int)((wallpaper.getHeight()-sh)/2),(int)((wallpaper.getWidth()+sw)/2),(int)((wallpaper.getHeight()+sh)/2));
            cropDst.set(0,0,w,h);c.drawBitmap(wallpaper,src,cropDst,wallPaint);
        }else c.drawColor(Design.BG);
        a11y.begin(k);
        c.save();c.scale(k,k);
        drawTop(c);
        boolean hasMatches=!today.isEmpty();
        spread(hasMatches);
        // #0B0D1A at 55 % from the matches row down, with a short ramp above it, so the skyline never shows behind the cards
        float layerTop=(hasMatches?400+dy[1]+10:626+dy[2]-20);
        layer.setShader(new LinearGradient(0,layerTop-40,0,layerTop,0x000B0D1A,0x8C0B0D1A,Shader.TileMode.CLAMP));
        c.drawRect(0,layerTop-40,1920,layerTop,layer);
        layer.setShader(null);layer.setColor(0x8C0B0D1A);
        c.drawRect(0,layerTop,1920,getHeight()/k+1,layer);
        block(c,dy[0]);drawHero(c);drawPrayer(c);unblock(c);
        if(hasMatches){block(c,dy[1]);drawMatches(c);unblock(c);}
        block(c,dy[2]);drawApps(c);unblock(c);
        block(c,dy[3]);drawShortcuts(c);unblock(c);
        Design.right(c,"◀ ▶ ▲ ▼ للتنقل · OK للفتح",1848,1034,Design.text(0x73F4EFE4,18,Design.regular()));
        Design.left(c,"الشاشة الرئيسية",72,1034,Design.text(0x73F4EFE4,18,Design.regular()));
        c.restore();
    }

    private void block(Canvas c,float by){c.save();c.translate(0,by);a11y.shift(by);}
    private void unblock(Canvas c){c.restore();a11y.shift(0);}
    /** Hero (with the prayer card), matches, apps and shortcuts get equal gaps between the top bar and the hint line. */
    private void spread(boolean withMatches){
        final float[] h={229.5f,172f,175f,76f},top={163f,400f,626f,828f};
        float sum=0;int n=0;
        for(int i=0;i<4;i++)if(i!=1||withMatches){sum+=h[i];n++;}
        float gap=(998f-118f-sum)/n,y=118f+gap;
        for(int i=0;i<4;i++){
            if(i==1&&!withMatches){dy[1]=0;continue;}
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
        SaudiDates dates=new SaudiDates();dates.update(System.currentTimeMillis(),new Locale("ar"));
        SimpleDateFormat g2=new SimpleDateFormat("d MMMM yyyy",Design.AR);g2.setTimeZone(TimeZone.getTimeZone("Asia/Riyadh"));
        // the date sits under the clock's own descent with a 6dp (9 reference px) gap: the two lines never touch
        float dateBase=104+9+dateLine.getTextSize()*0.8f;   // the digits have no descender: their baseline is their bottom
        Design.center(c,g2.format(new Date())+" · "+dates.hijri,1015,dateBase,dateLine);
        a11y.add(760,30,1340,dateBase+dateLine.descent(),"الساعة "+time+" "+fmt("a")+" · "+g2.format(new Date())+" · "+dates.hijri,false,false);
        // chips: weather, connection, settings
        HomeData.Weather wx=HomeData.get(getContext()).weather;
        r.set(272,60,436,116);RectF box=ring(r,focus(0,0));Design.card(c,box,28,1,p,Design.SURFACE);
        p.setStyle(Paint.Style.FILL);p.setShader(null);p.setColor(Design.GOLD);
        c.drawCircle(box.right-30,box.centerY(),13,p);
        String t=Double.isNaN(wx.temp)?"—":Math.round(wx.temp)+"° "+shortCondition(wx);
        Design.center(c,t,box.left+66,box.centerY()+7,chip);
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
    private String shortCondition(HomeData.Weather wx){
        String s=Design.weather(wx.code,wx.isDay);
        return s.length()>8?s.substring(0,8):s;
    }

    /** dp of the reference design: 1920 reference px across 1280 dp. */
    private static final float DP=1.5f;
    private static final int CARD_DARK=0xBF0B0D1A;   // #0B0D1A at 75 %: the skyline behind never competes with the text
    private final float[] dy=new float[4];   // vertical shift of each block so the rows share the screen evenly
    private final Paint heroFill=new Paint(Paint.ANTI_ALIAS_FLAG),heroStroke=new Paint(Paint.ANTI_ALIAS_FLAG);
    private float heroL,heroT,heroR,heroB;   // the box the gradient was made for
    private final Path heroClip=new Path();
    private final Paint layer=new Paint(),goldText15=Design.text(Design.GOLD,15,Design.regular());

    /** The last-channel card: 153 dp tall, always, and everything inside it is clipped to it, so it can't cover the row below. */
    private void drawHero(Canvas c){
        r.set(611,163,1850,163+153*DP);RectF box=ring(r,focus(1,0));
        // separate paints, so no later change to one can touch what is already recorded for the other
        if(box.left!=heroL||box.top!=heroT||box.right!=heroR||box.bottom!=heroB){
            heroL=box.left;heroT=box.top;heroR=box.right;heroB=box.bottom;
            heroFill.setShader(new LinearGradient(heroR,heroT,heroL,heroB,0xF2123D5C,0xF2121A3A,Shader.TileMode.CLAMP));   // #123D5C to #121A3A at 95 %: the city behind never shows through
        }
        heroFill.setStyle(Paint.Style.FILL);c.drawRoundRect(box,30,30,heroFill);
        heroStroke.setStyle(Paint.Style.STROKE);heroStroke.setStrokeWidth(1);heroStroke.setColor(Design.STROKE);c.drawRoundRect(box,30,30,heroStroke);
        LiveData.Channel ch=LiveData.lastWatched(getContext());
        c.save();c.clipRect(box.left+2,box.top+2,box.right-2,box.bottom-2);
        // 200x113 dp thumbnail carrying the channel's logo (stream_icon), with the "live" badge
        float tw=200*DP,th=113*DP;
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
                String shortName=shortName(ch.name);
                Paint sn=Design.text(Design.TEXT,56,Design.bold());
                while(sn.getTextSize()>26&&sn.measureText(shortName)>tw-40)sn.setTextSize(sn.getTextSize()-2);   // smaller, never cut
                Design.center(c,shortName,thumb.centerX(),thumb.centerY()+sn.getTextSize()*0.35f,sn);
            }
            Design.liveBadge(c,thumb.right-52,thumb.top+26,"مباشر",15,p,badge);
        }
        if(ch==null){
            Design.right(c,"ابدأ المشاهدة",x,box.top+box.height()*0.5f,Design.text(Design.TEXT,40,Design.bold()));
            Design.right(c,"افتح البث المباشر واختر قناة",x,box.top+box.height()*0.5f+40,Design.text(Design.MUTED,22,Design.regular()));
        }else{
            Design.right(c,"آخر قناة شاهدتها",x,box.top+50,Design.text(Design.GOLD,22,Design.regular()));
            Paint nameP=Design.text(Design.TEXT,44,Design.bold());
            Design.right(c,Design.fit(ch.name,nameP,x-box.left-40),x,box.top+104,nameP);
            long now=System.currentTimeMillis();
            List<LiveData.Program> guide=live==null?null:live.guide(ch.id);
            LiveData.Program cur=LiveData.current(guide,now),nxt=LiveData.following(guide,now);
            if(cur!=null){
                programLine(c,"الآن","",cur.shown,x,box.left+40,box.top+142);
                float f=Math.max(0,Math.min(1,(now-cur.start)/(float)Math.max(1,cur.end-cur.start)));
                r.set(box.left+40,box.bottom-24,x,box.bottom-20);Design.fill(c,r,2,p,0x1FFFFFFF);
                r.set(x-(x-box.left-40)*f,box.bottom-24,x,box.bottom-20);Design.fill(c,r,2,p,Design.GOLD);
            }
            if(nxt!=null)programLine(c,"التالي",fmt("h:mm a",nxt.start),nxt.shown,x,box.left+40,box.top+178);
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
    private String heroLabel(LiveData.Channel ch){
        if(ch==null)return "ابدأ المشاهدة · افتح البث المباشر واختر قناة";
        long now=System.currentTimeMillis();
        List<LiveData.Program> guide=live==null?null:live.guide(ch.id);
        LiveData.Program cur=LiveData.current(guide,now);
        return "آخر قناة شاهدتها: "+ch.name+(cur==null?"":" · الآن: "+cur.shown);
    }
    private String fmt(String pattern,long at){
        SimpleDateFormat f=new SimpleDateFormat(pattern,Design.AR);
        f.setTimeZone(TimeZone.getTimeZone("Asia/Riyadh"));return f.format(new Date(at));
    }

    private void drawPrayer(Canvas c){
        r.set(72,167,591,396);RectF box=ring(r,focus(1,1));
        Design.card(c,box,30,1,p,CARD_DARK);
        PrayerTimes pt=PrayerTimes.get(getContext());
        PrayerSchedule day=pt.today();long now=System.currentTimeMillis();
        PrayerCountdown next=PrayerCountdown.next(day,pt.tomorrow(),now);
        if(next==null||day==null){
            Design.center(c,pt.failed?"تعذّر تحميل مواقيت الصلاة":"جارٍ تحميل المواقيت…",box.centerX(),box.centerY(),Design.text(Design.MUTED,26,Design.regular()));
        }else{
            long remaining=Math.max(0,next.at-now);
            long minutes=(remaining+59_999)/60_000;
            long prev=next.index>0?next.schedule.time(next.index-1):next.at-7*3600_000L;
            float f=Math.max(0,Math.min(1,(now-prev)/(float)Math.max(1,next.at-prev)));
            float cx=486,cy=281,rad=70;
            arc.set(cx-rad,cy-rad,cx+rad,cy+rad);
            p.setShader(null);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(13);p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(0x1AFFFFFF);c.drawArc(arc,0,360,false,p);
            p.setColor(Design.GOLD);c.drawArc(arc,-90,360*f,false,p);p.setStrokeCap(Paint.Cap.BUTT);
            Design.center(c,String.format(Locale.US,"%d:%02d",minutes/60,minutes%60),cx,cy+10,Design.text(Design.TEXT,44,Design.bold()));
            Design.center(c,"متبقي",cx,cy+36,Design.text(Design.MUTED,15,Design.regular()));
            boolean tomorrow=!Design.clock(next.at,"yyyyMMdd").equals(Design.clock(now,"yyyyMMdd"));
            Design.right(c,tomorrow?"الصلاة القادمة · غدًا":"الصلاة القادمة",385,226,Design.text(Design.MUTED,21,Design.regular()));
            Paint nameP=Design.text(Design.TEXT,52,Design.bold());
            String name=Design.prayer(next.index);
            Design.right(c,name,385,284,nameP);
            Design.right(c,Design.clock(next.at,"h:mm a"),385-nameP.measureText(name)-14,284,Design.text(Design.GOLD,28,Design.bold()));
            List<String> others=new ArrayList<>();
            for(int i=0;i<5;i++)if(i!=next.index)others.add(Design.prayer(i)+" "+Design.clock(day.time(i),"h:mm"));
            Paint line=Design.text(Design.MUTED,21,Design.regular());
            Design.right(c,others.get(0)+" · "+others.get(1),385,328,line);
            Design.right(c,others.get(2)+" · "+others.get(3),385,362,line);
            String left=minutes<60?minutes+" دقيقة":(minutes/60)+" س "+(minutes%60)+" د";
            StringBuilder all=new StringBuilder("مواقيت الصلاة · الصلاة القادمة: "+name+(tomorrow?" · غدًا":"")+" "+Design.clock(next.at,"h:mm a")+" · المتبقي "+left);
            for(int i=0;i<5;i++)if(i!=next.index)all.append(" · ").append(Design.prayer(i)).append(" ").append(Design.clock(day.time(i),"h:mm"));
            a11y.add(box,all.toString(),focus(1,1),true);
        }
        if(focus(1,1))Design.focusRing(c,box,30,1,p);
    }

    private void drawMatches(Canvas c){
        Design.right(c,"مباريات اليوم",1842,448,section);
        goldText.setTextSize(20);
        boolean linkFocus=focus(2,Math.min(today.size(),4));
        Design.left(c,"عرض الكل ←",78,446,linkFocus?Design.text(0xFFFFFFFF,22,Design.bold()):goldText);
        a11y.add(1500,420,1842,456,"مباريات اليوم",false,false);
        a11y.add(70,420,200,456,"عرض الكل",linkFocus,true);
        int n=Math.min(4,today.size());
        for(int i=0;i<n;i++){
            MatchCarouselView.Match m=today.get(i);
            float right=1847-i*425,left=right-404;
            r.set(left,466,right,592);RectF box=ring(r,focus(2,i));
            boolean done=!m.live()&&!m.upcoming();
            Design.card(c,box,22,1,p,m.live()?0xBF2A0F1A:CARD_DARK);
            crest(c,m.homeLogo,m.home,right-67,516,26);crest(c,m.awayLogo,m.away,left+67,516,26);
            Paint team=Design.text(Design.TEXT,17,Design.regular());
            Design.center(c,Design.fit(m.home,team,120),right-67,566,team);
            Design.center(c,Design.fit(m.away,team,120),left+67,566,team);
            float cx=(left+right)/2;
            if(m.live()){
                Design.liveBadge(c,cx,490,"'"+minute(m),15,p,badge);
                Design.center(c,scoreText(m),cx,543,Design.text(Design.TEXT,42,Design.bold()));
            }else if(done){
                Design.center(c,"انتهت",cx,492,Design.text(Design.MUTED,15,Design.regular()));
                Design.center(c,scoreText(m),cx,543,Design.text(0x99F4EFE4,42,Design.bold()));
            }else{
                Design.center(c,"اليوم",cx,492,Design.text(Design.MUTED,15,Design.regular()));
                Design.center(c,fmt("h:mm a",m.kickoffMillis),cx,543,Design.text(Design.TEXT,38,Design.bold()));
            }
            LiveData.Channel ch=channelFor(m);
            if(ch!=null&&!done)Design.center(c,(m.live()?"▶ ":"")+Design.fit(ch.name,goldText15,360),cx,575,goldText15);
            else if(!done)Design.center(c,"اختر القناة",cx,575,Design.text(Design.MUTED,15,Design.regular()));
            a11y.add(box,m.home+" ضد "+m.away+" · "+(m.live()?"مباشر "+m.awayScore+" - "+m.homeScore:done?"انتهت "+m.awayScore+" - "+m.homeScore:fmt("h:mm a",m.kickoffMillis))+(ch!=null&&!done?" · "+ch.name:!done?" · اختر القناة":""),focus(2,i),true);
            if(focus(2,i))Design.focusRing(c,box,22,1,p);
        }
    }
    private LiveData.Channel channelFor(MatchCarouselView.Match m){
        return account==null?null:MatchChannels.forMatch(getContext(),account,m);
    }
    private String minute(MatchCarouselView.Match m){
        String d=m.status==null?"":m.status.replaceAll("[^0-9+]","");return d.isEmpty()?"مباشر":d;
    }
    private void crest(Canvas c,String url,String name,float cx,float cy,float radius){
        Bitmap b=ImageCache.get(url,this::postInvalidate);
        if(b!=null){
            p.setStyle(Paint.Style.FILL);p.setShader(null);p.setColor(0x14FFFFFF);c.drawCircle(cx,cy,radius,p);
            float s=Math.min(radius*1.5f/b.getWidth(),radius*1.5f/b.getHeight());
            float w=b.getWidth()*s,h=b.getHeight()*s;bm.set(cx-w/2,cy-h/2,cx+w/2,cy+h/2);p.setColor(0xFFFFFFFF);c.drawBitmap(b,null,bm,p);
        }else Design.initial(c,name,cx,cy,radius,p,badge);
    }

    private void drawApps(Canvas c){
        Design.right(c,"تطبيقاتي",1842,646,section);
        a11y.add(1600,620,1842,656,"تطبيقاتي",false,false);
        int n=apps.size();
        // the row scrolls sideways: keep the focused tile inside the window of APP_SLOTS tiles
        if(col[3]<appFirst)appFirst=col[3];
        if(col[3]>=appFirst+APP_SLOTS)appFirst=col[3]-APP_SLOTS+1;
        appFirst=Math.max(0,Math.min(appFirst,Math.max(0,n+1-APP_SLOTS)));
        Paint more=Design.text(Design.GOLD,44,Design.bold());
        if(appFirst>0)Design.center(c,"›",1888,732,more);
        if(appFirst+APP_SLOTS<n+1)Design.center(c,"‹",32,732,more);
        for(int i=appFirst;i<n&&i<appFirst+APP_SLOTS;i++){
            HomeApps.Item it=apps.get(i);
            float right=1848-(i-appFirst)*198;
            r.set(right-178,662,right,770);RectF box=ring(r,focus(3,i));
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
            a11y.add(box,it.label,focus(3,i),true);
            if(focus(3,i))Design.focusRing(c,box,22,1,p);
            Design.center(c,Design.fit(it.label,Design.text(Design.MUTED,18,Design.regular()),170),right-89,796,Design.text(focus(3,i)?Design.TEXT:Design.MUTED,18,Design.regular()));
        }
        // the dashed "+ add" tile closes the row
        if(n<appFirst+APP_SLOTS){
        float right=1848-(n-appFirst)*198;
        r.set(right-178,662,right,770);RectF box=ring(r,focus(3,n));
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2);p.setColor(0x4DFFFFFF);
        p.setPathEffect(new android.graphics.DashPathEffect(new float[]{10,8},0));
        c.drawRoundRect(box,22,22,p);p.setPathEffect(null);
        Design.center(c,"+",box.centerX(),box.centerY()+14,Design.text(Design.TEXT,48,Design.light()));
        a11y.add(box,"إضافة تطبيق",focus(3,n),true);
        if(focus(3,n))Design.focusRing(c,box,22,1,p);
        Design.center(c,"إضافة",right-89,796,Design.text(Design.MUTED,18,Design.regular()));
        }
    }

    private static final String[] SHORTCUT_LABELS={"البث المباشر","المباريات","كل التطبيقات","الأخيرة","التحديثات","الإعدادات"};
    private static final String[] SHORTCUT_ICONS={"▣","⚽","⠿","↻","⤓","⚙"};
    private void drawShortcuts(Canvas c){
        for(int i=0;i<6;i++){
            float right=1847-i*299;
            r.set(right-283,828,right,904);RectF box=ring(r,focus(4,i));
            Design.card(c,box,26,1,p,Design.SURFACE);
            Paint lab=Design.text(Design.TEXT,23,Design.bold());
            float tw=lab.measureText(SHORTCUT_LABELS[i]);
            Design.center(c,SHORTCUT_LABELS[i],box.centerX()-12,box.centerY()+8,lab);
            Design.center(c,SHORTCUT_ICONS[i],box.centerX()-12+tw/2+30,box.centerY()+9,Design.text(Design.GOLD,26,Design.bold()));
            a11y.add(box,SHORTCUT_LABELS[i],focus(4,i),true);
            if(focus(4,i))Design.focusRing(c,box,26,1,p);
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
            case 3:return apps.size()+1;
            default:return 6;
        }
    }
    private boolean rowShown(int rw){return rw!=2||!today.isEmpty();}
    @Override public boolean onKeyDown(int key,KeyEvent e){
        switch(key){
            case KeyEvent.KEYCODE_DPAD_DOWN:{
                int nx=row+1;while(nx<5&&!rowShown(nx))nx++;
                if(nx<5)row=nx;invalidate();return true;}
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
    private void start(Intent i){getContext().startActivity(i);}
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
                    LiveData.Channel ch=LiveData.lastWatched(ctx);
                    if(ch!=null&&account!=null)start(PlayerActivity.intent(ctx,ch,""));   // the player finds the channel's own section
                    else start(new Intent(ctx,LiveActivity.class));
                }else prayerDetails();
                break;
            case 2:{
                int n=Math.min(4,today.size());
                if(cl>=n){start(new Intent(ctx,MatchesScreenActivity.class));break;}
                MatchCarouselView.Match m=today.get(cl);
                LiveData.Channel ch=channelFor(m);
                if(m.live()&&ch!=null)start(PlayerActivity.intent(ctx,ch,""));
                else start(new Intent(ctx,MatchesScreenActivity.class));
                break;}
            case 3:{
                int n=apps.size();
                if(cl<n&&ctx instanceof android.app.Activity)HomeApps.open((android.app.Activity)ctx,apps.get(cl),this,0,0,0,0);
                else addApp();
                break;}
            default:
                switch(cl){
                    case 0:start(new Intent(ctx,LiveActivity.class));break;
                    case 1:start(new Intent(ctx,MatchesScreenActivity.class));break;
                    case 2:host.openClassic(false);break;
                    case 3:host.openClassic(true);break;
                    case 4:start(new Intent(ctx,UpdatesActivity.class));break;
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
