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
import android.util.Log;
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
        if(account!=null)MatchChannels.prepare(this,account,()->{MatchChannels.scanGuides(this,account);view.postInvalidate();});
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
        private int day=1;                        // day: 0 yesterday, 1 today, 2 tomorrow
        private String league="";                 // "" all, FAV the favourites, else the label of a league that is in the day's matches
        private static final String FAV="★ المفضلة";
        private int chipScroll;                   // first league chip drawn (the row can be longer than the screen)
        private int area=1,chipFocus=1,sel,scroll,panelFocus; // area: 0 filter chips, 1 grid, 2 detail panel
        private boolean offline;
        private final String[] days={"أمس","اليوم","غداً"};
        private final List<String> chips=new ArrayList<>();   // "الكل", then the leagues of the chosen day, then the favourites

        private final A11y a11y=new A11y(this);
        private final Runnable lookupDone=this::postInvalidate;
        MatchesView(Context c){super(c);setFocusable(true);setFocusableInTouchMode(true);}
        @Override public android.view.accessibility.AccessibilityNodeProvider getAccessibilityNodeProvider(){return a11y.provider(null,null);}
        @Override protected void onAttachedToWindow(){super.onAttachedToWindow();MatchChannels.listen(lookupDone);}
        @Override protected void onDetachedFromWindow(){MatchChannels.unlisten(lookupDone);super.onDetachedFromWindow();}
        void dataChanged(boolean ok){offline=!ok&&all.isEmpty();invalidate();}

        @Override protected void onSizeChanged(int w,int h,int ow,int oh){
            k=w/1920f;
            try{backdrop=Bitmap.createBitmap(Math.max(1,w/2),Math.max(1,h/2),Bitmap.Config.RGB_565);
            Canvas c=new Canvas(backdrop);c.scale(0.5f,0.5f);Wallpaper.draw(getContext(),c,w,h,0.6f,0x0B0D1A);}catch(Throwable noRoom){backdrop=null;AppLog.w("backdrop skipped: "+noRoom);}   // the home wallpaper under #0B0D1A at 60 %
        }
        private long riyadhDayStart(int offset){
            java.util.Calendar cal=java.util.Calendar.getInstance(TimeZone.getTimeZone("Asia/Riyadh"));
            cal.set(java.util.Calendar.HOUR_OF_DAY,0);cal.set(java.util.Calendar.MINUTE,0);cal.set(java.util.Calendar.SECOND,0);cal.set(java.util.Calendar.MILLISECOND,0);
            return cal.getTimeInMillis()+offset*24L*3600_000;
        }
        /** The matches of the chosen day from the feed and from the guide, before the league filter. */
        private List<MatchCarouselView.Match> ofDay(){
            long from=riyadhDayStart(day-1),to=from+24L*3600_000;
            List<MatchCarouselView.Match> out=new ArrayList<>();
            for(MatchCarouselView.Match m:MatchChannels.withGuide(all)){
                boolean inDay;
                if(day==1)inDay=m.live()||(m.kickoffMillis>=from&&m.kickoffMillis<to);
                else inDay=m.kickoffMillis>=from&&m.kickoffMillis<to&&!m.live();
                if(inDay)out.add(m);
            }
            return out;
        }
        /** The filter chips: only leagues that have a match on the chosen day, in Arabic, between "الكل" and the favourites. */
        private void buildChips(){
            java.util.LinkedHashMap<String,Integer> count=new java.util.LinkedHashMap<>();
            for(MatchCarouselView.Match m:ofDay()){
                String label=Titles.filterLabel(m.competition);
                if(label.isEmpty())continue;
                Integer n=count.get(label);count.put(label,n==null?1:n+1);
            }
            List<String> names=new ArrayList<>(count.keySet());
            java.util.Collections.sort(names,(a,b)->count.get(b).equals(count.get(a))?a.compareTo(b):Integer.compare(count.get(b),count.get(a)));
            chips.clear();chips.add("الكل");chips.addAll(names);chips.add(FAV);
            if(!league.isEmpty()&&!chips.contains(league))league="";   // that league has no match on this day
        }
        private List<MatchCarouselView.Match> visible(){
            buildChips();
            Set<String> favorites=saved();
            List<MatchCarouselView.Match> out=new ArrayList<>();
            for(MatchCarouselView.Match m:ofDay()){
                if(FAV.equals(league)){if(!favorites.contains(m.key))continue;}
                else if(!league.isEmpty()&&!Titles.filterLabel(m.competition).equals(league))continue;
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
            return Design.clock(at,"h:mm a");
        }
        /** The other channels that carry the match (at most two, the chosen one left out). */
        private List<LiveData.Channel> extraOptions(MatchCarouselView.Match m,LiveData.Channel chosen){
            List<LiveData.Channel> out=new ArrayList<>();
            for(LiveData.Channel o:MatchChannels.options(m)){if(chosen!=null&&o.id.equals(chosen.id))continue;out.add(o);if(out.size()==2)break;}
            return out;
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
            try{drawScreen(c);}
            catch(RuntimeException e){AppLog.w("matches-screen draw failed: "+Log.getStackTraceString(e));}   // one bad match never closes the screen
        }
        private void drawScreen(Canvas c){
            int w=getWidth(),h=getHeight();
            if(backdrop!=null)c.drawBitmap(backdrop,null,new RectF(0,0,w,h),null);
            a11y.begin(k);
            c.save();c.scale(k,k);
            List<MatchCarouselView.Match> list=visible();
            if(sel>=list.size())sel=Math.max(0,list.size()-1);
            // header
            Design.right(c,"⚽ المباريات",1848,76,title);
            String dayText=Design.clock(System.currentTimeMillis(),"EEEE d MMMM");
            Design.right(c,dayText+(updatedAt>0?" · آخر تحديث "+timeOf(updatedAt):offline?" · تعذّر التحديث":" · جارٍ التحميل…"),1848,113,sub);
            int liveCount=0;for(MatchCarouselView.Match m:MatchChannels.withGuide(all))if(m.live())liveCount++;   // the same list the cards come from: feed and guide, each match once
            a11y.add(1100,40,1848,126,"المباريات · "+dayText+(liveCount>0?" · "+liveCount+" مباشر":""),false,false);
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
            chipFocus=Math.min(chipFocus,3+chips.size()-1);
            // the league chips can be more than the row holds: the row scrolls so the focused one is always drawn
            int focusedLeague=area==0&&chipFocus>=3?chipFocus-3:-1;
            chipScroll=Math.max(0,Math.min(chipScroll,chips.size()-1));
            if(focusedLeague>=0){
                if(focusedLeague<chipScroll)chipScroll=focusedLeague;
                while(chipScroll<focusedLeague&&chipsWidth(chipScroll,focusedLeague)>x-96)chipScroll++;
            }
            for(int i=chipScroll;i<chips.size();i++){
                String label=chips.get(i);
                if(i>chipScroll&&x-(chip.measureText(label)+44)<96)break;
                boolean on=i==0?league.isEmpty():label.equals(league);
                x=chipRight(c,x,label,on,focusedLeague==i);
            }
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
                Design.card(c,card,22,1,p,0xFF151A2E);   // opaque: the wallpaper never shows through a result; a live card differs only by the red badge
                int alpha=done?153:255;
                crest(c,m.homeLogo,m.home,right-81,top+62,32);crest(c,m.awayLogo,m.away,left+81,top+62,32);
                team.setAlpha(alpha);Design.center(c,Design.fitShrink(m.home,team,170),right-81,top+120,team);
                Design.center(c,Design.fitShrink(m.away,team,170),left+81,top+120,team);team.setAlpha(255);
                float cx=(left+right)/2;
                if(m.live()){
                    Design.liveBadge(c,cx,top+32,"'"+minuteOf(m),17,p,badge);
                    big.setTextSize(50);Design.center(c,scoreText(m),cx,top+98,big);
                }else if(done){
                    Design.center(c,"انتهت",cx,top+34,small);
                    big.setAlpha(alpha);Design.center(c,scoreText(m),cx,top+98,big);big.setAlpha(255);
                }else{
                    Design.center(c,Design.fit(Titles.shortLeague(m.competition),small,cardW-270),cx,top+34,small);
                    big.setTextSize(46);Design.center(c,timeOf(m.kickoffMillis),cx,top+98,big);
                }
                LiveData.Channel ch=channelFor(m);
                if(done){goldText.setAlpha(alpha);Design.center(c,"ملخص",cx,top+130,goldText);goldText.setAlpha(255);}
                else if(ch!=null)Design.center(c,(m.live()?"▶ ":"")+Design.fit(MatchChannels.shortName(ch.name),goldText,cardW-270),cx,top+130,goldText);
                else if(MatchChannels.resolved(getContext(),m)){small.setAlpha(200);Design.center(c,"اختر القناة",cx,top+130,small);small.setAlpha(255);}
                a11y.add(card,m.home+" ضد "+m.away+" · "+m.competition+" · "+(m.live()?"مباشر "+scoreText(m):done?"انتهت "+m.awayScore+" - "+m.homeScore:timeOf(m.kickoffMillis))+(ch!=null&&!done?" · "+MatchChannels.shortName(ch.name):!done&&MatchChannels.resolved(getContext(),m)?" · اختر القناة":""),focused,true);
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
        private float chipsWidth(int from,int to){
            float w=0;
            for(int i=from;i<=to&&i<chips.size();i++)w+=Math.max(chip.measureText(chips.get(i)),chipOn.measureText(chips.get(i)))+44+14;
            return w;
        }
        private String scoreText(MatchCarouselView.Match m){return m.awayScore.isEmpty()&&m.homeScore.isEmpty()?"—":m.awayScore+" - "+m.homeScore;}
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
            r.set(72,243,591,881);Design.card(c,r,28,1,p,0xFF141829);
            if(sel<0||sel>=list.size())return;
            MatchCarouselView.Match m=list.get(sel);
            a11y.add(72,243,591,490,"تفاصيل المباراة: "+m.home+" ضد "+m.away+" · "+m.competition,false,false);
            Design.right(c,Design.fit(m.competition,small,300),561,288,small);
            if(m.live()){String mn=minuteOf(m);Design.liveBadge(c,160,283,mn.equals("مباشر")?"مباشر":"مباشر "+mn,17,p,badge);}
            crest(c,m.homeLogo,m.home,470,378,40);crest(c,m.awayLogo,m.away,158,378,40);
            boolean started=!m.upcoming();
            big.setTextSize(started?72:44);
            Design.center(c,started?scoreText(m):timeOf(m.kickoffMillis),332,408,big);big.setTextSize(50);
            team.setTextSize(27);Design.center(c,Design.fitShrink(m.home,team,190),470,468,team);Design.center(c,Design.fitShrink(m.away,team,190),158,468,team);team.setTextSize(19);
            r.set(102,486,561,487);Design.fill(c,r,0,p,0x1AFFFFFF);
            List<String> events=new ArrayList<>();
            for(String s:m.goalsHome)events.add("⚽ "+s+" — "+m.home);
            for(String s:m.goalsAway)events.add("⚽ "+s+" — "+m.away);
            for(int i=0;i<Math.min(3,events.size());i++)Design.right(c,Design.fit(events.get(i),line,440),561,526+i*34,line);
            if(events.isEmpty())Design.right(c,started?"لا توجد تفاصيل أحداث":"تبدأ الساعة "+timeOf(m.kickoffMillis),561,526,line);
            LiveData.Channel ch=channelFor(m);
            List<LiveData.Channel> opts=extraOptions(m,ch);
            if(ch!=null){
                r.set(103,704,561,760);
                RectF box=r;boolean f=area==2&&panelFocus==0;if(f){Design.grown(r,g);box=g;}
                Design.gold(c,box,22,p);
                Paint t=Design.text(0xFF1A1408,24,Design.bold());Design.center(c,"▶ شاهد على "+Design.fit(MatchChannels.shortName(ch.name),t,300),332,740,t);
                a11y.add(box,"شاهد على "+MatchChannels.shortName(ch.name),f,true);
                if(f)Design.focusRing(c,box,22,1,p);
            }
            int base=ch!=null?1:0;
            if(!opts.isEmpty())Design.right(c,"قنوات أخرى لنفس المباراة",561,603,small);
            for(int i=0;i<opts.size();i++){   // the match's other channels, as choices
                r.set(103,610+i*46,561,648+i*46);
                RectF ob=r;boolean of=area==2&&panelFocus==base+i;if(of){Design.grown(r,g);ob=g;}
                Design.card(c,ob,19,1,p,0x14FFFFFF);
                Design.center(c,"▶ "+Design.fit(MatchChannels.shortName(opts.get(i).name),chip,360),332,636+i*46,chip);
                a11y.add(ob,"شاهد على "+MatchChannels.shortName(opts.get(i).name),of,true);
                if(of)Design.focusRing(c,ob,19,1,p);
            }
            int slot=base+opts.size();
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
            try{return handleKey(key,e);}
            catch(RuntimeException ex){AppLog.w("matches-screen key failed: "+Log.getStackTraceString(ex));return true;}
        }
        private boolean handleKey(int key,KeyEvent e){
            List<MatchCarouselView.Match> list=visible();
            int chipsTotal=3+chips.size();
            switch(key){
                case KeyEvent.KEYCODE_DPAD_UP:
                    if(area==1){if(sel>=2)sel-=2;else{area=0;}}
                    else if(area==2){if(panelFocus>0)panelFocus--;}
                    invalidate();return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if(area==0){area=1;sel=Math.min(sel,Math.max(0,list.size()-1));}
                    else if(area==1){if(sel+2<list.size())sel+=2;else if(sel+1<list.size()&&sel%2==0)sel++;}
                    else if(area==2){if(list.isEmpty()){area=1;invalidate();return true;}{MatchCarouselView.Match pm=list.get(Math.min(sel,list.size()-1));LiveData.Channel pc=channelFor(pm);if(panelFocus<(pc!=null?1:0)+extraOptions(pm,pc).size()+1)panelFocus++;}}
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
                if(chipFocus<3)day=chipFocus;else{int i=chipFocus-3;league=i==0||i>=chips.size()?"":chips.get(i);}
                sel=0;scroll=0;invalidate();return;
            }
            if(list.isEmpty())return;
            MatchCarouselView.Match m=list.get(Math.min(sel,list.size()-1));
            LiveData.Channel ch=channelFor(m);
            if(area==1){
                if(ch!=null)watch(ch);else openSports(m);
                return;
            }
            List<LiveData.Channel> opts=extraOptions(m,ch);
            int favoriteSlot=(ch!=null?1:0)+opts.size();
            if(panelFocus==0&&ch!=null){watch(ch);return;}
            int first=ch!=null?1:0;
            if(panelFocus>=first&&panelFocus<first+opts.size()){watch(opts.get(panelFocus-first));return;}
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
        /** No confirmed channel: the live screen opens on the sports section, on its first beIN or SSC channel; a channel played there is kept for this match. */
        private void openSports(MatchCarouselView.Match m){
            getContext().startActivity(new Intent(getContext(),LiveActivity.class).putExtra(LiveActivity.EXTRA_MATCH_KEY,m.key));
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
