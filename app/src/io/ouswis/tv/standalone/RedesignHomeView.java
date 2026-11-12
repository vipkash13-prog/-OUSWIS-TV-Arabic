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
            clock=Design.text(Design.TEXT,100,Design.light()),ampm=Design.text(Design.MUTED,36,Design.light()),
            bold=Design.text(Design.TEXT,27,Design.bold()),goldText=Design.text(Design.GOLD,18,Design.regular()),
            badge=Design.text(0xFFFFFFFF,16,Design.bold()),muted=Design.text(Design.MUTED,19,Design.regular()),
            dark=Design.text(0xFF1A1408,22,Design.bold());
    private final RectF r=new RectF(),g=new RectF(),arc=new RectF(),clip=new RectF();
    private final Path path=new Path();
    private Bitmap backdrop,wallpaper;
    private float k=1;
    private int row=1;
    private final int[] col=new int[5];
    private final List<MatchCarouselView.Match> today=new ArrayList<>();
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

    RedesignHomeView(Context c,Host host){
        super(c);this.host=host;
        setFocusable(true);setFocusableInTouchMode(true);
        account=XtreamAccount.read(c);
        if(account!=null)live=new LiveData(c,account);
        try(InputStream in=c.getAssets().open("themes/gold-sunset.png")){wallpaper=BitmapFactory.decodeStream(in);}catch(Exception ignored){}
    }

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
                    if(!found.isEmpty()||today.isEmpty()){today.clear();today.addAll(shown);}
                    clampColumns();invalidate();
                });
            });
            if(account!=null)MatchChannels.prepare(getContext(),account,this::postInvalidate);
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
        col[3]=Math.min(col[3],Math.min(apps.size(),7));
    }

    // MARK: drawing

    @Override protected void onSizeChanged(int w,int h,int ow,int oh){
        k=w/1920f;
        backdrop=Bitmap.createBitmap(Math.max(1,w/2),Math.max(1,h/2),Bitmap.Config.ARGB_8888);
        Canvas c=new Canvas(backdrop);c.scale(0.5f,0.5f);
        Design.background(c,w,h,new Paint(Paint.ANTI_ALIAS_FLAG),k);
        if(wallpaper!=null){
            // the old horizon, kept in the top third at ~40 %, melting into the background
            Paint wp=new Paint(Paint.FILTER_BITMAP_FLAG);wp.setAlpha(102);
            c.save();c.clipRect(0,0,w,h*0.34f);
            c.drawBitmap(wallpaper,null,new RectF(0,0,w,h),wp);
            c.restore();
            Paint fade=new Paint();
            fade.setShader(new LinearGradient(0,h*0.14f,0,h*0.34f,0x000B0D1A,Design.BG,Shader.TileMode.CLAMP));
            c.drawRect(0,h*0.14f,w,h*0.34f,fade);
        }
    }
    private String clockText(){return fmt("h:mm");}
    private String fmt(String pattern){
        SimpleDateFormat f=new SimpleDateFormat(pattern,Locale.forLanguageTag("ar-SA-u-nu-latn"));
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
        if(backdrop!=null)c.drawBitmap(backdrop,null,new RectF(0,0,w,h),null);
        c.save();c.scale(k,k);
        drawTop(c);drawHero(c);drawPrayer(c);
        boolean hasMatches=!today.isEmpty();
        if(hasMatches)drawMatches(c);
        drawApps(c);drawShortcuts(c);
        Design.right(c,"◀ ▶ ▲ ▼ للتنقل · OK للفتح",1848,1054,Design.text(0x73F4EFE4,18,Design.regular()));
        Design.left(c,"الشاشة الرئيسية",72,1054,Design.text(0x73F4EFE4,18,Design.regular()));
        c.restore();
    }

    private void drawTop(Canvas c){
        r.set(1788,58,1848,118);Design.gold(c,r,16,p);
        dark.setTextSize(34);Design.center(c,"ق",1818,100,dark);
        Design.right(c,"OUSWIS TV",1770,84,title);
        Design.right(c,fmt("EEEE")+" · جدة",1770,112,sub);
        String time=clockText();
        clock.setTextAlign(Paint.Align.CENTER);
        float tw=clock.measureText(time);
        Design.center(c,time,1050,112,clock);
        Design.right(c,fmt("a"),1050-tw/2-10,112,ampm);
        SaudiDates dates=new SaudiDates();dates.update(System.currentTimeMillis(),new Locale("ar"));
        SimpleDateFormat g2=new SimpleDateFormat("d MMMM yyyy",new Locale("ar"));g2.setTimeZone(TimeZone.getTimeZone("Asia/Riyadh"));
        Design.center(c,g2.format(new Date())+" · "+dates.hijri,1015,148,Design.text(Design.MUTED,21,Design.regular()));
        // chips: weather, connection, settings
        HomeData.Weather wx=HomeData.get(getContext()).weather;
        r.set(272,60,436,116);RectF box=ring(r,focus(0,0));Design.card(c,box,28,1,p,Design.SURFACE);
        p.setStyle(Paint.Style.FILL);p.setShader(null);p.setColor(Design.GOLD);
        c.drawCircle(box.right-30,box.centerY(),13,p);
        String t=Double.isNaN(wx.temp)?"—":Math.round(wx.temp)+"° "+shortCondition(wx);
        Design.center(c,t,box.left+66,box.centerY()+7,chip);
        if(focus(0,0))Design.focusRing(c,box,28,1,p);
        r.set(147,60,260,116);Design.card(c,r,28,1,p,Design.SURFACE);
        p.setStyle(Paint.Style.FILL);p.setColor(online()?Design.OK:Design.LIVE);c.drawCircle(238,88,6,p);
        Design.center(c,online()?"متصل":"غير متصل",190,95,chip);
        r.set(72,60,135,116);box=ring(r,focus(0,1));Design.card(c,box,28,1,p,Design.SURFACE);
        Design.center(c,"⚙",box.centerX(),box.centerY()+8,chip);
        if(focus(0,1))Design.focusRing(c,box,28,1,p);
    }
    private String shortCondition(HomeData.Weather wx){
        String s=HomeData.condition(wx.code,wx.isDay);
        return s.length()>8?s.substring(0,8):s;
    }

    private void drawHero(Canvas c){
        r.set(611,163,1850,397);RectF box=ring(r,focus(1,0));
        p.setStyle(Paint.Style.FILL);
        p.setShader(new LinearGradient(box.left,box.top,box.right,box.bottom,0xFF0F4B33,0xFF11172E,Shader.TileMode.CLAMP));
        c.drawRoundRect(box,30,30,p);p.setShader(null);
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1);p.setColor(Design.STROKE);c.drawRoundRect(box,30,30,p);
        LiveData.Channel ch=LiveData.lastWatched(getContext());
        // 200x113 thumbnail (the channel logo on a pitch-green tile), with the "live" badge
        r.set(box.right-300,box.top+32,box.right-28,box.top+185);
        Design.fill(c,r,18,p,0xFF1E7A4B);
        if(ch!=null){
            Bitmap logo=ImageCache.get(ch.icon,this::postInvalidate);
            if(logo!=null){
                float s=Math.min(180f/logo.getWidth(),90f/logo.getHeight());
                float lw=logo.getWidth()*s,lh=logo.getHeight()*s;
                g.set(r.centerX()-lw/2,r.centerY()-lh/2,r.centerX()+lw/2,r.centerY()+lh/2);c.drawBitmap(logo,null,g,p);
            }else Design.initial(c,ch.name,r.centerX(),r.centerY(),36,p,badge);
            Design.liveBadge(c,r.right-52,r.top+26,"مباشر",15,p,badge);
        }
        float x=box.right-330;
        if(ch==null){
            Design.right(c,"ابدأ المشاهدة",x,box.top+110,Design.text(Design.TEXT,40,Design.bold()));
            Design.right(c,"افتح البث المباشر واختر قناة",x,box.top+150,Design.text(Design.MUTED,22,Design.regular()));
        }else{
            Design.right(c,"آخر قناة شاهدتها",x,box.top+56,Design.text(Design.GOLD,22,Design.regular()));
            Design.right(c,Design.fit(ch.name.isEmpty()?"القناة":ch.name,Design.text(Design.TEXT,50,Design.bold()),700),x,box.top+112,Design.text(Design.TEXT,50,Design.bold()));
            long now=System.currentTimeMillis();
            List<LiveData.Program> guide=live==null?null:live.guide(ch.id);
            LiveData.Program cur=LiveData.current(guide,now),nxt=LiveData.following(guide,now);
            Paint line=Design.text(Design.MUTED,23,Design.regular());
            if(cur!=null){
                Design.right(c,Design.fit("الآن: "+cur.title,line,700),x,box.top+150,line);
                float f=Math.max(0,Math.min(1,(now-cur.start)/(float)Math.max(1,cur.end-cur.start)));
                r.set(box.left+86,box.bottom-34,x,box.bottom-30);Design.fill(c,r,2,p,0x1FFFFFFF);
                r.set(x-(x-box.left-86)*f,box.bottom-34,x,box.bottom-30);Design.fill(c,r,2,p,Design.GOLD);
            }
            if(nxt!=null)Design.right(c,Design.fit("التالي "+fmt("h:mm a",nxt.start)+": "+nxt.title,line,700),x,box.top+186,line);
        }
        if(focus(1,0))Design.focusRing(c,box,30,1,p);
    }
    private String fmt(String pattern,long at){
        SimpleDateFormat f=new SimpleDateFormat(pattern,Locale.forLanguageTag("ar-SA-u-nu-latn"));
        f.setTimeZone(TimeZone.getTimeZone("Asia/Riyadh"));return f.format(new Date(at));
    }

    private void drawPrayer(Canvas c){
        r.set(72,167,591,396);RectF box=ring(r,focus(1,1));
        Design.card(c,box,30,1,p,0x14FFFFFF);
        PrayerTimes pt=PrayerTimes.get(getContext());
        PrayerSchedule day=pt.today();long now=System.currentTimeMillis();
        PrayerCountdown next=PrayerCountdown.next(day,pt.tomorrow(),now);
        if(next==null||day==null){
            Design.center(c,pt.status(),box.centerX(),box.centerY(),Design.text(Design.MUTED,26,Design.regular()));
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
            Design.right(c,"الصلاة القادمة",385,226,Design.text(Design.MUTED,21,Design.regular()));
            Paint nameP=Design.text(Design.TEXT,52,Design.bold());
            String name=PrayerTimes.name(next.index);
            Design.right(c,name,385,284,nameP);
            Design.right(c,PrayerTimes.time(next.schedule,next.index),385-nameP.measureText(name)-14,284,Design.text(Design.GOLD,28,Design.bold()));
            List<String> others=new ArrayList<>();
            for(int i=0;i<5;i++)if(i!=next.index)others.add(PrayerTimes.name(i)+" "+PrayerTimes.time(day,i));
            Paint line=Design.text(Design.MUTED,21,Design.regular());
            Design.right(c,others.get(0)+" · "+others.get(2),385,328,line);
            Design.right(c,others.get(1)+" · "+others.get(3),385,362,line);
        }
        if(focus(1,1))Design.focusRing(c,box,30,1,p);
    }

    private void drawMatches(Canvas c){
        Design.right(c,"مباريات اليوم",1842,448,section);
        goldText.setTextSize(20);
        boolean linkFocus=focus(2,Math.min(today.size(),4));
        Design.left(c,"عرض الكل ←",78,446,linkFocus?Design.text(0xFFFFFFFF,22,Design.bold()):goldText);
        int n=Math.min(4,today.size());
        for(int i=0;i<n;i++){
            MatchCarouselView.Match m=today.get(i);
            float right=1847-i*425,left=right-404;
            r.set(left,466,right,592);RectF box=ring(r,focus(2,i));
            boolean done=!m.live()&&!m.upcoming();
            Design.card(c,box,22,1,p,m.live()?0x1AEF4444:Design.SURFACE);
            crest(c,m.homeLogo,m.home,right-67,516,26);crest(c,m.awayLogo,m.away,left+67,516,26);
            Paint team=Design.text(Design.TEXT,17,Design.regular());
            Design.center(c,Design.fit(m.home,team,120),right-67,566,team);
            Design.center(c,Design.fit(m.away,team,120),left+67,566,team);
            float cx=(left+right)/2;
            if(m.live()){
                Design.liveBadge(c,cx,490,"'"+minute(m),15,p,badge);
                Design.center(c,m.awayScore+" - "+m.homeScore,cx,543,Design.text(Design.TEXT,42,Design.bold()));
            }else if(done){
                Design.center(c,"انتهت",cx,492,Design.text(Design.MUTED,15,Design.regular()));
                Design.center(c,m.awayScore+" - "+m.homeScore,cx,543,Design.text(0x99F4EFE4,42,Design.bold()));
            }else{
                Design.center(c,"اليوم",cx,492,Design.text(Design.MUTED,15,Design.regular()));
                Design.center(c,fmt("h:mm a",m.kickoffMillis),cx,543,Design.text(Design.TEXT,38,Design.bold()));
            }
            LiveData.Channel ch=channelFor(m);
            if(ch!=null&&!done)Design.center(c,(m.live()?"▶ ":"")+ch.name,cx,575,Design.text(Design.GOLD,15,Design.regular()));
            if(focus(2,i))Design.focusRing(c,box,22,1,p);
        }
    }
    private LiveData.Channel channelFor(MatchCarouselView.Match m){
        return account==null?null:MatchChannels.find(getContext(),account,m.competition,null);
    }
    private String minute(MatchCarouselView.Match m){
        String d=m.status==null?"":m.status.replaceAll("[^0-9+]","");return d.isEmpty()?"مباشر":d;
    }
    private void crest(Canvas c,String url,String name,float cx,float cy,float radius){
        Bitmap b=ImageCache.get(url,this::postInvalidate);
        if(b!=null){
            p.setStyle(Paint.Style.FILL);p.setShader(null);p.setColor(0x14FFFFFF);c.drawCircle(cx,cy,radius,p);
            float s=Math.min(radius*1.5f/b.getWidth(),radius*1.5f/b.getHeight());
            float w=b.getWidth()*s,h=b.getHeight()*s;g.set(cx-w/2,cy-h/2,cx+w/2,cy+h/2);c.drawBitmap(b,null,g,p);
        }else Design.initial(c,name,cx,cy,radius,p,badge);
    }

    private void drawApps(Canvas c){
        Design.right(c,"تطبيقاتي",1842,646,section);
        int n=Math.min(7,apps.size());
        for(int i=0;i<n;i++){
            HomeApps.Item it=apps.get(i);
            float right=1848-i*198;
            r.set(right-178,662,right,770);RectF box=ring(r,focus(3,i));
            if(it.logo!=null){
                Design.card(c,box,22,1,p,0xFF121528);
                float s=Math.min(box.width()*0.7f/it.logo.getWidth(),box.height()*0.55f/it.logo.getHeight());
                float lw=it.logo.getWidth()*s,lh=it.logo.getHeight()*s;
                g.set(box.centerX()-lw/2,box.centerY()-lh/2,box.centerX()+lw/2,box.centerY()+lh/2);
                RectF keep=new RectF(g);c.drawBitmap(it.logo,null,keep,p);
            }else if(it.tile!=null){
                path.reset();path.addRoundRect(box,22,22,Path.Direction.CW);
                c.save();c.clipPath(path);c.drawBitmap(it.tile,null,box,p);c.restore();
            }else Design.card(c,box,22,1,p,Design.SURFACE);
            if(focus(3,i))Design.focusRing(c,box,22,1,p);
            Design.center(c,Design.fit(it.label,Design.text(Design.MUTED,18,Design.regular()),170),right-89,796,Design.text(focus(3,i)?Design.TEXT:Design.MUTED,18,Design.regular()));
        }
        // the dashed "+ add" tile closes the row
        float right=1848-n*198;
        r.set(right-178,662,right,770);RectF box=ring(r,focus(3,n));
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2);p.setColor(0x4DFFFFFF);
        p.setPathEffect(new android.graphics.DashPathEffect(new float[]{10,8},0));
        c.drawRoundRect(box,22,22,p);p.setPathEffect(null);
        Design.center(c,"+",box.centerX(),box.centerY()+14,Design.text(Design.TEXT,48,Design.light()));
        if(focus(3,n))Design.focusRing(c,box,22,1,p);
        Design.center(c,"إضافة",right-89,796,Design.text(Design.MUTED,18,Design.regular()));
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
            if(focus(4,i))Design.focusRing(c,box,26,1,p);
        }
    }

    // MARK: keys

    private int rowSize(int rw){
        switch(rw){
            case 0:return 2;
            case 1:return 2;
            case 2:return Math.min(today.size(),4)+1;
            case 3:return Math.min(7,apps.size())+1;
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
                    if(ch!=null&&account!=null){
                        LiveData.noteWatched(ctx,ch);
                        start(new Intent(ctx,XtreamActivity.class).putExtra(XtreamActivity.EXTRA_STREAM_ID,ch.id));
                    }else start(new Intent(ctx,LiveActivity.class));
                }else prayerDetails();
                break;
            case 2:{
                int n=Math.min(4,today.size());
                if(cl>=n){start(new Intent(ctx,MatchesScreenActivity.class));break;}
                MatchCarouselView.Match m=today.get(cl);
                LiveData.Channel ch=channelFor(m);
                if(m.live()&&ch!=null){
                    LiveData.noteWatched(ctx,ch);
                    start(new Intent(ctx,XtreamActivity.class).putExtra(XtreamActivity.EXTRA_STREAM_ID,ch.id));
                }else start(new Intent(ctx,MatchesScreenActivity.class));
                break;}
            case 3:{
                int n=Math.min(7,apps.size());
                if(cl<n&&ctx instanceof android.app.Activity)HomeApps.open((android.app.Activity)ctx,apps.get(cl),this,0,0,0,0);
                else start(new Intent().setComponent(new ComponentName(ctx,SettingsActivity.class)));
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
    private void weatherDetails(){
        HomeData.Weather wx=HomeData.get(getContext()).weather;
        if(Double.isNaN(wx.temp))return;
        String text=HomeData.condition(wx.code,wx.isDay)+"\n"
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
        for(int i=0;i<5;i++)sb.append(PrayerTimes.name(i)).append("  ").append(PrayerTimes.time(day,i)).append("\n");
        new AlertDialog.Builder(getContext()).setTitle("مواقيت الصلاة · جدة · أم القرى").setMessage(sb.toString().trim()).setPositiveButton("حسناً",null).show();
    }
}
