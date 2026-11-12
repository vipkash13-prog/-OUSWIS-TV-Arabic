package io.ouswis.tv.standalone;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Toast;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Matches (SPEC section 3, matches.png): day and competition filters, a grid of match cards and a detail panel that
 * follows the focus. The data is the existing ESPN source (UpcomingMatches); the "watch" button only exists when the
 * viewer's own subscription has the broadcasting channel. It refreshes every 60 s while a match is live (30 min
 * otherwise) and stops while the screen is hidden.
 */
public final class MatchesScreenActivity extends Activity {
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private MatchesView view;
    private boolean foreground;
    private final List<MatchCarouselView.Match> all=new ArrayList<>();
    private long updatedAt;
    private volatile boolean loading;
    private XtreamAccount account;
    private SharedPreferences store;
    private final Runnable refresh=new Runnable(){@Override public void run(){load();}};

    @Override protected void onCreate(Bundle b){
        super.onCreate(b);
        Ui.init(this);
        getWindow().getDecorView().setSystemUiVisibility(5894);
        store=getSharedPreferences("ouswis-live",Context.MODE_PRIVATE);
        account=XtreamAccount.read(this);
        view=new MatchesView(this);
        FrameLayout root=new FrameLayout(this);
        root.addView(view,new FrameLayout.LayoutParams(-1,-1));
        setContentView(root);
        view.requestFocus();
        if(account!=null)MatchChannels.prepare(this,account,()->view.postInvalidate());
    }
    @Override protected void onResume(){super.onResume();foreground=true;load();}
    @Override protected void onPause(){foreground=false;main.removeCallbacks(refresh);super.onPause();}
    @Override protected void onDestroy(){io.shutdownNow();super.onDestroy();}

    private void load(){
        if(!foreground||loading)return;
        loading=true;
        io.execute(()->{
            List<MatchCarouselView.Match> merged=new ArrayList<>();
            boolean any=false;
            try{
                for(String section:new String[]{"live","upcoming","today","yesterday"}){
                    UpcomingMatches.Result r=UpcomingMatches.load(null,section);
                    if(r.answered>0)any=true;
                    merged.addAll(r.matches);
                }
            }catch(Throwable ignored){}
            final boolean ok=any;
            main.post(()->{
                loading=false;
                if(ok){all.clear();all.addAll(merged);updatedAt=System.currentTimeMillis();}
                view.dataChanged(ok);
                if(foreground){
                    boolean live=false;for(MatchCarouselView.Match m:all)if(m.live())live=true;
                    main.postDelayed(refresh,live?60_000L:30*60_000L);
                }
            });
        });
    }

    private Set<String> saved(){return new HashSet<>(store.getStringSet("match-favorites",new HashSet<String>()));}

    private final class MatchesView extends View {
        private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint title=Design.text(Design.TEXT,40,Design.bold()),sub=Design.text(Design.MUTED,20,Design.regular()),
                chip=Design.text(Design.TEXT,20,Design.regular()),chipOn=Design.text(0xFF1A1408,20,Design.bold()),
                big=Design.text(Design.TEXT,50,Design.bold()),team=Design.text(Design.TEXT,19,Design.regular()),
                small=Design.text(Design.MUTED,16,Design.regular()),goldText=Design.text(Design.GOLD,16,Design.regular()),
                badge=Design.text(0xFFFFFFFF,16,Design.bold()),line=Design.text(Design.MUTED,21,Design.regular());
        private final RectF r=new RectF(),g=new RectF(),bm=new RectF();
        private Bitmap backdrop;
        private float k;
        private int day=1,league=0;              // day: 0 yesterday, 1 today, 2 tomorrow
        private int area=1,chipFocus=1,sel,scroll,panelFocus; // area: 0 filter chips, 1 grid, 2 detail panel
        private boolean offline;
        private final String[] days={"أمس","اليوم","غداً"};
        private final String[] leagues={"الكل","دوري روشن","أبطال آسيا","أبطال أوروبا","الدوري الإسباني","★ المفضلة"};
        private final String[] fragments={"","روشن","أبطال آسيا","أبطال أوروبا","الإسباني",""};

        private final A11y a11y=new A11y(this);
        private final Runnable lookupDone=this::postInvalidate;
        MatchesView(Context c){super(c);setFocusable(true);setFocusableInTouchMode(true);}
        @Override public android.view.accessibility.AccessibilityNodeProvider getAccessibilityNodeProvider(){return a11y.provider(null,null);}
        @Override protected void onAttachedToWindow(){super.onAttachedToWindow();MatchChannels.listen(lookupDone);}
        @Override protected void onDetachedFromWindow(){MatchChannels.unlisten(lookupDone);super.onDetachedFromWindow();}
        void dataChanged(boolean ok){offline=!ok&&all.isEmpty();invalidate();}

        @Override protected void onSizeChanged(int w,int h,int ow,int oh){
            k=w/1920f;
            backdrop=Bitmap.createBitmap(Math.max(1,w/2),Math.max(1,h/2),Bitmap.Config.RGB_565);
            Canvas c=new Canvas(backdrop);c.scale(0.5f,0.5f);Wallpaper.draw(getContext(),c,w,h,0.6f);   // the home wallpaper under a 60 % dark layer
        }
        private long riyadhDayStart(int offset){
            java.util.Calendar cal=java.util.Calendar.getInstance(TimeZone.getTimeZone("Asia/Riyadh"));
            cal.set(java.util.Calendar.HOUR_OF_DAY,0);cal.set(java.util.Calendar.MINUTE,0);cal.set(java.util.Calendar.SECOND,0);cal.set(java.util.Calendar.MILLISECOND,0);
            return cal.getTimeInMillis()+offset*24L*3600_000;
        }
        private List<MatchCarouselView.Match> visible(){
            long from=riyadhDayStart(day-1),to=from+24L*3600_000,todayStart=riyadhDayStart(0);
            Set<String> favorites=saved();
            List<MatchCarouselView.Match> out=new ArrayList<>();
            for(MatchCarouselView.Match m:all){
                boolean inDay;
                if(day==1)inDay=m.live()||(m.kickoffMillis>=from&&m.kickoffMillis<to);
                else inDay=m.kickoffMillis>=from&&m.kickoffMillis<to&&!m.live();
                if(!inDay)continue;
                if(league==5){if(!favorites.contains(m.key))continue;}
                else if(league>0&&!m.competition.contains(fragments[league]))continue;
                out.add(m);
            }
            // live first, then what is coming by time, then what finished (dimmed)
            java.util.Collections.sort(out,(a,b)->{
                int ra=a.live()?0:a.upcoming()?1:2,rb=b.live()?0:b.upcoming()?1:2;
                if(ra!=rb)return Integer.compare(ra,rb);
                return Long.compare(a.kickoffMillis,b.kickoffMillis);
            });
            return out;
        }
        private String timeOf(long at){
            SimpleDateFormat f=new SimpleDateFormat("h:mm a",Design.AR);
            f.setTimeZone(TimeZone.getTimeZone("Asia/Riyadh"));return f.format(new Date(at));
        }
        private LiveData.Channel channelFor(MatchCarouselView.Match m){
            if(account==null)return null;
            return MatchChannels.forMatch(getContext(),account,m);
        }
        private void crest(Canvas c,String url,String name,float cx,float cy,float radius){
            Bitmap b=ImageCache.get(url,()->invalidate());
            if(b!=null){
                p.setStyle(Paint.Style.FILL);p.setShader(null);p.setColor(0x14FFFFFF);c.drawCircle(cx,cy,radius,p);
                float s=Math.min(radius*1.5f/b.getWidth(),radius*1.5f/b.getHeight());
                float w=b.getWidth()*s,h=b.getHeight()*s;bm.set(cx-w/2,cy-h/2,cx+w/2,cy+h/2);p.setColor(0xFFFFFFFF);c.drawBitmap(b,null,bm,p);
            }else Design.initial(c,name,cx,cy,radius,p,badge);
        }

        @Override protected void onDraw(Canvas c){
            int w=getWidth(),h=getHeight();
            if(backdrop!=null)c.drawBitmap(backdrop,null,new RectF(0,0,w,h),null);
            a11y.begin(k);
            c.save();c.scale(k,k);
            List<MatchCarouselView.Match> list=visible();
            if(sel>=list.size())sel=Math.max(0,list.size()-1);
            // header
            Design.right(c,"⚽ المباريات",1848,76,title);
            SimpleDateFormat dayFormat=new SimpleDateFormat("EEEE d MMMM",Design.AR);dayFormat.setTimeZone(TimeZone.getTimeZone("Asia/Riyadh"));
            Design.right(c,dayFormat.format(new Date())+(updatedAt>0?" · آخر تحديث "+timeOf(updatedAt):offline?" · تعذّر التحديث":" · جارٍ التحميل…"),1848,113,sub);
            int liveCount=0;for(MatchCarouselView.Match m:all)if(m.live())liveCount++;
            a11y.add(1100,40,1848,126,"المباريات · "+dayFormat.format(new Date())+(liveCount>0?" · "+liveCount+" مباشر":""),false,false);
            float chipX=72;
            r.set(202,62,348,114);
            if(liveCount>0){
                Design.card(c,r,26,1,p,Design.SURFACE);
                Design.liveBadge(c,275,88,liveCount+" مباشر",19,p,badge);
            }
            r.set(72,62,190,114);Design.card(c,r,26,1,p,Design.SURFACE);Design.center(c,timeOf(System.currentTimeMillis()),131,95,chip);
            // filters (right to left): 3 day chips, a separator, the leagues
            float x=1848;
            int index=0;
            for(int i=0;i<3;i++,index++){x=chipRight(c,x,days[i],i==day,area==0&&chipFocus==index);}
            x-=10;r.set(x-1,164,x,212);Design.fill(c,r,0,p,0x1FFFFFFF);x-=20;
            for(int i=0;i<leagues.length;i++,index++){x=chipRight(c,x,leagues[i],i==league,area==0&&chipFocus==index);}
            // cards
            int cols=2,cardW=604,cardH=150,gapY=18;
            int rowsVisible=4;
            int row=sel/cols;
            if(row<scroll)scroll=row;if(row>=scroll+rowsVisible)scroll=row-rowsVisible+1;
            if(list.isEmpty()){
                Design.center(c,offline?"تعذّر تحميل المباريات. تحقق من الإنترنت":updatedAt==0?"جارٍ تحميل المباريات…":"لا توجد مباريات في هذا اليوم",1146,520,Design.text(Design.MUTED,26,Design.regular()));
            }
            for(int i=scroll*cols;i<Math.min(list.size(),(scroll+rowsVisible)*cols);i++){
                int rr=i/cols-scroll,cc=i%cols;
                float right=1847-cc*(cardW+16),left=right-cardW,top=242+rr*(cardH+gapY);
                r.set(left,top,right,top+cardH);
                MatchCarouselView.Match m=list.get(i);
                boolean done=!m.live()&&!m.upcoming();
                boolean focused=area==1&&i==sel;
                RectF card=r;
                if(focused){Design.grown(r,g);card=g;}
                Design.card(c,card,22,1,p,m.live()?0x1AEF4444:Design.SURFACE);
                int alpha=done?153:255;
                crest(c,m.homeLogo,m.home,right-81,top+62,32);crest(c,m.awayLogo,m.away,left+81,top+62,32);
                team.setAlpha(alpha);Design.center(c,Design.fit(m.home,team,150),right-81,top+120,team);
                Design.center(c,Design.fit(m.away,team,150),left+81,top+120,team);team.setAlpha(255);
                float cx=(left+right)/2;
                if(m.live()){
                    Design.liveBadge(c,cx,top+32,"'"+minuteOf(m),17,p,badge);
                    big.setTextSize(50);Design.center(c,m.awayScore+" - "+m.homeScore,cx,top+98,big);
                }else if(done){
                    Design.center(c,"انتهت",cx,top+34,small);
                    big.setAlpha(alpha);Design.center(c,m.awayScore+" - "+m.homeScore,cx,top+98,big);big.setAlpha(255);
                }else{
                    Design.center(c,m.competition.length()>16?m.competition.substring(0,16):m.competition,cx,top+34,small);
                    big.setTextSize(46);Design.center(c,timeOf(m.kickoffMillis),cx,top+98,big);
                }
                LiveData.Channel ch=channelFor(m);
                if(done){goldText.setAlpha(alpha);Design.center(c,"ملخص",cx,top+130,goldText);goldText.setAlpha(255);}
                else if(ch!=null)Design.center(c,(m.live()?"▶ ":"")+ch.name,cx,top+130,goldText);
                a11y.add(card,m.home+" ضد "+m.away+" · "+m.competition+" · "+(m.live()?"مباشر "+m.awayScore+" - "+m.homeScore:done?"انتهت "+m.awayScore+" - "+m.homeScore:timeOf(m.kickoffMillis))+(ch!=null&&!done?" · "+ch.name:""),focused,true);
                if(focused)Design.focusRing(c,card,22,1,p);
            }
            panel(c,list);
            Design.right(c,"OK للمشاهدة · ▲▼ بين المباريات · ◀▶ بين الأيام والدوريات · BACK للرئيسية",1848,1054,sub);
            Design.left(c,"المباريات",72,1054,sub);
            c.restore();
        }
        private String minuteOf(MatchCarouselView.Match m){
            String s=m.status==null?"":m.status;String digits=s.replaceAll("[^0-9+]","");
            return digits.isEmpty()?"مباشر":digits+"'";
        }
        private float chipRight(Canvas c,float right,String label,boolean on,boolean focused){
            Paint t=on?chipOn:chip;
            float width=t.measureText(label)+44;
            r.set(right-width,164,right,212);
            RectF box=r;if(focused){Design.grown(r,g);box=g;}
            if(on)Design.gold(c,box,24,p);else Design.card(c,box,24,1,p,Design.SURFACE);
            Design.center(c,label,right-width/2,195,t);
            a11y.add(box,on?label+" (محدد)":label,focused,true);
            if(focused)Design.focusRing(c,box,24,1,p);
            return right-width-14;
        }
        private void panel(Canvas c,List<MatchCarouselView.Match> list){
            r.set(72,243,591,881);Design.card(c,r,28,1,p,0x14FFFFFF);
            if(sel<0||sel>=list.size())return;
            MatchCarouselView.Match m=list.get(sel);
            a11y.add(72,243,591,490,"تفاصيل المباراة: "+m.home+" ضد "+m.away+" · "+m.competition,false,false);
            Design.right(c,Design.fit(m.competition,small,300),561,288,small);
            if(m.live())Design.liveBadge(c,160,283,"مباشر "+minuteOf(m),17,p,badge);
            crest(c,m.homeLogo,m.home,470,378,40);crest(c,m.awayLogo,m.away,158,378,40);
            boolean started=!m.upcoming();
            big.setTextSize(started?72:44);
            Design.center(c,started?m.awayScore+" - "+m.homeScore:timeOf(m.kickoffMillis),332,408,big);big.setTextSize(50);
            team.setTextSize(27);Design.center(c,Design.fit(m.home,team,190),470,468,team);Design.center(c,Design.fit(m.away,team,190),158,468,team);team.setTextSize(19);
            r.set(102,486,561,487);Design.fill(c,r,0,p,0x1AFFFFFF);
            List<String> events=new ArrayList<>();
            for(String s:m.goalsHome)events.add("⚽ "+s+" — "+m.home);
            for(String s:m.goalsAway)events.add("⚽ "+s+" — "+m.away);
            for(int i=0;i<Math.min(5,events.size());i++)Design.right(c,Design.fit(events.get(i),line,440),561,526+i*34,line);
            if(events.isEmpty())Design.right(c,started?"لا توجد تفاصيل أحداث":"تبدأ الساعة "+timeOf(m.kickoffMillis),561,526,line);
            LiveData.Channel ch=channelFor(m);
            if(ch!=null){
                r.set(103,690,561,758);
                RectF box=r;boolean f=area==2&&panelFocus==0;if(f){Design.grown(r,g);box=g;}
                Design.gold(c,box,22,p);
                Paint t=Design.text(0xFF1A1408,24,Design.bold());Design.center(c,"▶ شاهد على "+Design.fit(ch.name,t,300),332,735,t);
                a11y.add(box,"شاهد على "+ch.name,f,true);
                if(f)Design.focusRing(c,box,22,1,p);
            }
            int slot=ch!=null?1:0;
            r.set(103,770,561,820);
            RectF box=r;boolean f=area==2&&panelFocus==slot;if(f){Design.grown(r,g);box=g;}
            Design.card(c,box,22,1,p,0x14FFFFFF);
            boolean fav=saved().contains(m.key);
            String favLabel=fav?"★ في المفضلة · ذكّرني":"★ أضف للمفضلة · ذكّرني";
            Design.center(c,favLabel,332,803,chip);
            a11y.add(box,favLabel,f,true);
            if(f)Design.focusRing(c,box,22,1,p);
            r.set(103,830,561,874);
            box=r;f=area==2&&panelFocus==slot+1;if(f){Design.grown(r,g);box=g;}
            Design.card(c,box,22,1,p,0x0DFFFFFF);
            Design.center(c,"اختر القناة لهذا الدوري",332,860,line);
            a11y.add(box,"اختر القناة لهذا الدوري",f,true);
            if(f)Design.focusRing(c,box,22,1,p);
        }

        @Override public boolean onKeyDown(int key,KeyEvent e){
            List<MatchCarouselView.Match> list=visible();
            int chipsTotal=3+leagues.length;
            switch(key){
                case KeyEvent.KEYCODE_DPAD_UP:
                    if(area==1){if(sel>=2)sel-=2;else{area=0;}}
                    else if(area==2){if(panelFocus>0)panelFocus--;}
                    invalidate();return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if(area==0){area=1;sel=Math.min(sel,Math.max(0,list.size()-1));}
                    else if(area==1){if(sel+2<list.size())sel+=2;else if(sel+1<list.size()&&sel%2==0)sel++;}
                    else if(area==2){if(panelFocus<(channelFor(list.get(Math.min(sel,list.size()-1)))!=null?2:1))panelFocus++;}
                    invalidate();return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:   // RTL: the first chip and the first column are on the right
                    if(area==0){if(chipFocus>0)chipFocus--;}
                    else if(area==1){if(sel%2==1)sel--;}
                    else if(area==2){area=1;}
                    invalidate();return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if(area==0){if(chipFocus<chipsTotal-1)chipFocus++;}
                    else if(area==1){if(sel%2==0&&sel+1<list.size())sel++;else if(!list.isEmpty()){area=2;panelFocus=0;}}
                    invalidate();return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:case KeyEvent.KEYCODE_ENTER:
                    if(e.getRepeatCount()>0)return true;
                    activate(list);return true;
            }
            return super.onKeyDown(key,e);
        }
        private void activate(List<MatchCarouselView.Match> list){
            if(area==0){
                if(chipFocus<3)day=chipFocus;else league=chipFocus-3;
                sel=0;scroll=0;invalidate();return;
            }
            if(list.isEmpty())return;
            MatchCarouselView.Match m=list.get(Math.min(sel,list.size()-1));
            LiveData.Channel ch=channelFor(m);
            if(area==1){
                if(ch!=null)watch(ch);else{area=2;panelFocus=0;invalidate();}
                return;
            }
            int favoriteSlot=ch!=null?1:0;
            if(panelFocus==0&&ch!=null){watch(ch);return;}
            if(panelFocus==favoriteSlot+1){chooseChannel(m);return;}
            if(panelFocus==favoriteSlot){
                Set<String> s=saved();
                String message;
                if(s.remove(m.key)){MatchReminder.cancel(getContext(),m.key);message="أُزيلت من المفضلة";}
                else{
                    s.add(m.key);message="أُضيفت إلى المفضلة";
                    if(m.upcoming()){
                        if(android.os.Build.VERSION.SDK_INT>=33)
                            MatchesScreenActivity.this.requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},1);
                        if(MatchReminder.schedule(getContext(),m.key,m.home+" × "+m.away,m.kickoffMillis))message+=" · سيصلك تذكير قبل 10 دقائق";
                    }
                }
                store.edit().putStringSet("match-favorites",s).apply();
                Toast.makeText(getContext(),message,Toast.LENGTH_SHORT).show();
                invalidate();
            }
        }
        private void watch(LiveData.Channel ch){
            getContext().startActivity(PlayerActivity.intent(getContext(),ch,""));
        }
        /** A channel picked by hand is kept for the match's league: every match of it opens that channel from now on. */
        private void chooseChannel(MatchCarouselView.Match m){
            XtreamAccount account=XtreamAccount.read(getContext());
            if(account==null){Toast.makeText(getContext(),"سجّل دخول Xtream أولاً من البث المباشر",Toast.LENGTH_LONG).show();return;}
            final String key=MatchChannels.leagueKey(m.competition);
            ChannelPicker.pick(MatchesScreenActivity.this,account,"قناة "+key,ch->{
                MatchChannels.pick(getContext(),key,ch);
                Toast.makeText(getContext(),ch==null?"عادت القناة الافتراضية":"سيُفتح "+ch.name+" لمباريات هذا الدوري",Toast.LENGTH_LONG).show();
                invalidate();
            });
        }
    }
}
