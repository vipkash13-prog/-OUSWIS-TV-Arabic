package io.ouswis.tv.standalone;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The films screen (movies.png): the sections on the right (continue watching, favourites, newest, then the
 * subscription's own), a grid of posters with the rating and the name, search by name and the sort order. Only the
 * section in front is held in memory; posters load for the rows on screen and the next one. With EXTRA_SERIES the same
 * screen lists the subscription's series, and a pick opens the series' page (SeriesActivity).
 */
public final class MoviesActivity extends Activity {
    private final Handler main=new Handler(Looper.getMainLooper());
    private XtreamAccount account;
    private VodData data;
    private GridView view;
    static final String EXTRA_SERIES="series";
    private boolean series;
    private String w(String film,String show){return series?show:film;}

    @Override public void finish(){super.finish();overridePendingTransition(0,0);}
    @Override protected void onCreate(Bundle b){
        super.onCreate(b);
        overridePendingTransition(0,0);
        Ui.init(this);
        getWindow().getDecorView().setSystemUiVisibility(5894);
        account=XtreamAccount.read(this);
        if(account==null){startActivity(new Intent(this,XtreamActivity.class));finish();return;}
        series=getIntent().getBooleanExtra(EXTRA_SERIES,false);
        data=new VodData(this,account,series);
        view=new GridView(this);
        FrameLayout root=new FrameLayout(this);
        root.addView(view,new FrameLayout.LayoutParams(-1,-1));
        setContentView(root);
        view.requestFocus();
        data.loadCategories(()->view.dataChanged());
    }
    private boolean busy;
    @Override protected void onResume(){super.onResume();if(!busy){busy=true;Busy.enter();}if(view!=null)view.resumed();}
    @Override protected void onPause(){if(busy){busy=false;Busy.leave();}super.onPause();}
    @Override protected void onDestroy(){if(busy){busy=false;Busy.leave();}main.removeCallbacksAndMessages(null);if(data!=null)data.shutdown();super.onDestroy();}

    static Intent details(Context c,VodData.Movie m,String sectionName){
        return new Intent(c,MovieActivity.class).putExtra("id",m.id).putExtra("name",m.name).putExtra("icon",m.icon).putExtra("ext",m.ext)
                .putExtra("cat",m.cat).putExtra("rating",m.rating).putExtra("added",m.added).putExtra("section",sectionName==null?"":sectionName);
    }

    private final class GridView extends View {
        private static final int COLS=6,ROWS=2;
        private static final float PW=214,PH=320,PITCH_X=237,PITCH_Y=348,TOP=143,RIGHT=1519;
        private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint title=Design.text(Design.TEXT,44,Design.bold()),sub=Design.text(Design.MUTED,20,Design.regular()),
                chip=Design.text(Design.TEXT,19,Design.regular()),side=Design.text(Design.MUTED,21,Design.regular()),
                sideOn=Design.text(Design.TEXT,22,Design.regular()),hint=Design.text(Design.MUTED,17,Design.regular()),
                msg=Design.text(Design.MUTED,26,Design.regular()),icon=Design.text(Design.TEXT,40,Design.regular());
        private final RectF r=new RectF(),g=new RectF();
        private final A11y a11y=new A11y(this);
        private final VodDraw poster=new VodDraw(PW,PH,21);
        private String focusId="";
        private long focusAt;
        private boolean marquee;
        private final Runnable marqueeTick=()->{marquee=false;invalidate();};
        @Override public android.view.accessibility.AccessibilityNodeProvider getAccessibilityNodeProvider(){return a11y.provider(null,null);}
        private Bitmap backdrop;
        private float k=1;
        private int sec=2,secTop,sel,top;   // sec: index in sections(); sel: index in the shown list; top: first row drawn
        private int area=1;                 // 0 sections, 1 grid, 2 header
        private int head;                   // 0 search, 1 sort (in the header)
        private int sort;                   // 0 newest, 1 name, 2 rating
        private List<VodData.Movie> shownCache=new ArrayList<>();
        private String shownFor="";
        private final Runnable loadLater=this::loadSection;

        GridView(Context c){super(c);setFocusable(true);setFocusableInTouchMode(true);}
        @Override protected void onSizeChanged(int w,int h,int ow,int oh){k=w/1920f;backdrop=Wallpaper.cinema(getContext(),w,h);}

        private List<VodData.Category> sections(){
            List<VodData.Category> out=new ArrayList<>();
            out.add(new VodData.Category(VodData.CONTINUE,"▶ تابع المشاهدة"));
            out.add(new VodData.Category(VodData.FAVORITES,"★ المفضلة"));
            out.add(new VodData.Category(VodData.NEWEST,"أحدث الإضافات"));
            if(!data.searchFor.isEmpty())out.add(3,new VodData.Category(VodData.SEARCH,"بحث: "+data.searchFor));
            synchronized(data.categories){out.addAll(data.categories);}
            return out;
        }
        private VodData.Category current(){List<VodData.Category> s=sections();return s.get(Math.max(0,Math.min(sec,s.size()-1)));}
        void dataChanged(){shownFor="";invalidate();loadSection();}
        void resumed(){shownFor="";invalidate();}   // continue watching and favourites may have changed while away
        private void loadSection(){
            final VodData.Category c=current();
            data.keepOnly(c.id,VodData.NEWEST);
            data.loadMovies(c.id,()->{shownFor="";invalidate();});
        }
        private void sectionMoved(){sel=0;top=0;shownFor="";main.removeCallbacks(loadLater);main.postDelayed(loadLater,250);invalidate();}

        /** The films of the section in front, in the chosen order (made again only when something changed). */
        private List<VodData.Movie> shown(){
            VodData.Category c=current();
            List<VodData.Movie> all=data.cached(c.id);
            String sig=c.id+"|"+sort+"|"+(all==null?-1:System.identityHashCode(all))+"|"+(all==null?0:all.size());
            if(sig.equals(shownFor))return shownCache;
            List<VodData.Movie> out=all==null?new ArrayList<>():new ArrayList<>(all);
            boolean ordered=c.id.equals(VodData.CONTINUE)||c.id.equals(VodData.FAVORITES)||c.id.equals(VodData.SEARCH);
            if(!ordered||sort!=0){
                if(sort==0)Collections.sort(out,(a,b)->Long.compare(b.added,a.added));
                else if(sort==1){final java.text.Collator coll=java.text.Collator.getInstance(Design.AR);Collections.sort(out,(a,b)->coll.compare(a.shown,b.shown));}
                else Collections.sort(out,(a,b)->Float.compare(b.rating,a.rating));
            }
            shownCache=out;shownFor=sig;
            if(sel>=out.size())sel=Math.max(0,out.size()-1);
            return out;
        }
        private String sortName(){return sort==0?"الأحدث":sort==1?"الاسم":"التقييم";}
        private String clock(){
            java.text.SimpleDateFormat f=new java.text.SimpleDateFormat("h:mm a",Design.AR);
            f.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Riyadh"));return f.format(new java.util.Date());
        }
        private String count(int n){return String.format(java.util.Locale.US,"%,d",n)+w(" فيلم"," مسلسل");}

        @Override protected void onDraw(Canvas c){
            int w=getWidth(),h=getHeight();
            if(backdrop!=null)c.drawBitmap(backdrop,null,new RectF(0,0,w,h),null);else c.drawColor(Design.BG);
            a11y.begin(k);
            c.save();c.scale(k,k);
            List<VodData.Category> secs=sections();
            if(sec>=secs.size())sec=secs.size()-1;
            VodData.Category cur=secs.get(sec);
            List<VodData.Movie> list=shown();
            // header: title, what is shown, and the chips
            Design.right(c,w("الأفلام","المسلسلات"),1780,82,title);
            Design.right(c,w("🎬","📺"),1848,82,icon);
            String what=cur.id.equals(VodData.NEWEST)?(data.total>=0?"أحدث الإضافات · "+count(data.total):"أحدث الإضافات"):
                    cur.shown+(data.cached(cur.id)!=null?" · "+count(list.size()):"");
            Design.right(c,what,1848,112,sub);
            a11y.add(1100,30,1848,122,w("الأفلام","المسلسلات")+" · "+what,false,false);
            String[] chips={"⌕ بحث","ترتيب: "+sortName(),clock()};
            float[][] cr={{362,460},{190,349},{72,177}};
            for(int i=0;i<3;i++){
                r.set(cr[i][0],54,cr[i][1],101);
                Design.card(c,r,24,1,p,Design.SURFACE);
                Design.center(c,chips[i],(cr[i][0]+cr[i][1])/2,85,chip);
                boolean on=area==2&&head==i;
                a11y.add(r,i==0?"بحث":chips[i],on,i<2);
                if(on){Design.grown(r,g);Design.focusRing(c,g,24,1,p);}
            }
            // sections
            r.set(1549,143,1847,1020);Design.card(c,r,26,1,p,Design.SURFACE);
            int visible=15;
            if(sec<secTop)secTop=sec;if(sec>=secTop+visible)secTop=sec-visible+1;
            for(int i=secTop;i<Math.min(secs.size(),secTop+visible);i++){
                float y=182+(i-secTop)*54;
                VodData.Category s=secs.get(i);
                boolean picked=i==sec,focus=picked&&area==0;
                if(picked){
                    r.set(1561,y-26,1835,y+28);Design.fill(c,r,14,p,0x29E9C46A);
                    r.set(1828,y-22,1835,y+24);Design.fill(c,r,4,p,Design.GOLD);
                    if(focus){r.set(1561,y-26,1835,y+28);Design.grown(r,g);Design.focusRing(c,g,14,1,p);}
                }
                Paint tp=picked?sideOn:side;
                Design.right(c,Design.fit(s.shown,tp,236),1814,y+8,tp);
                a11y.add(1561,y-26,1835,y+28,"قسم "+s.shown+(s.count>=0?" ("+s.count+")":"")+(picked?" — محدد":""),focus,true);
            }
            // the grid
            if(list.isEmpty()){
                boolean own=cur.id.equals(VodData.CONTINUE)||cur.id.equals(VodData.FAVORITES);
                String m;
                if(!own&&data.failed(cur.id))m=cur.id.equals(VodData.NEWEST)&&data.categoriesFailed?"تعذّر الاتصال بالاشتراك":w("تعذّر تحميل الأفلام","تعذّر تحميل المسلسلات")+" · اضغط OK على القسم لإعادة المحاولة";
                else if(!own&&(data.loading(cur.id)||data.cached(cur.id)==null))m=cur.id.equals(VodData.SEARCH)?"جارٍ البحث…":w("جارٍ تحميل الأفلام…","جارٍ تحميل المسلسلات…");
                else if(cur.id.equals(VodData.CONTINUE))m=w("لا توجد أفلام لمتابعتها","لا توجد مسلسلات لمتابعتها");
                else if(cur.id.equals(VodData.FAVORITES))m=w("لا توجد أفلام مفضلة: اضغط OK مطوّلاً على فيلم","لا توجد مسلسلات مفضلة: اضغط OK مطوّلاً على مسلسل");
                else if(cur.id.equals(VodData.SEARCH))m="لا توجد نتائج";
                else m=w("لا توجد أفلام في هذا القسم","لا توجد مسلسلات في هذا القسم");
                Design.center(c,m,(120+RIGHT)/2f,560,msg);
                a11y.add(120,520,RIGHT,600,m,false,false);
            }
            boolean moving=false;
            int row=sel/COLS;
            if(row<top)top=row;if(row>=top+ROWS)top=row-ROWS+1;
            for(int i=top*COLS;i<Math.min(list.size(),(top+ROWS)*COLS);i++){
                int rr=i/COLS-top,cc=i%COLS;
                float right=RIGHT-cc*PITCH_X,y=TOP+rr*PITCH_Y;
                r.set(right-PW,y,right,y+PH);
                boolean focus=i==sel&&area==1;
                RectF box=r;if(focus){Design.grown(r,g);box=g;}
                VodData.Movie m=list.get(i);
                Bitmap b=PosterCache.get(m.icon,PosterCache.GRID,this::invalidate);
                long scroll=-1;
                if(focus){if(!m.id.equals(focusId)){focusId=m.id;focusAt=android.os.SystemClock.uptimeMillis();}scroll=android.os.SystemClock.uptimeMillis()-focusAt;}
                if(poster.draw(c,box,b,m.shown,m.ratingText(),true,scroll))moving=true;
                a11y.add(box,m.shown+(m.rating>0?" · ★ "+m.ratingText():""),focus,true);
                if(focus)Design.focusRing(c,box,14,1,p);
            }
            if(moving&&!marquee){marquee=true;postDelayed(marqueeTick,33);}   // a long focused name scrolls: about 30 frames a second, only then
            // the next row, asked for ahead (not drawn)
            for(int i=(top+ROWS)*COLS;i<Math.min(list.size(),(top+ROWS+1)*COLS);i++)PosterCache.get(list.get(i).icon,PosterCache.GRID,null);
            // footer
            Design.right(c,"OK تفاصيل · ▲▼◀▶ تنقل · ضغطة مطوّلة = مفضلة",1848,1054,hint);
            Design.left(c,w("الأفلام","المسلسلات"),72,1054,hint);
            c.restore();
        }

        @Override public boolean onKeyDown(int key,KeyEvent e){
            List<VodData.Movie> list=shown();
            int n=list.size(),secs=sections().size();
            switch(key){
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if(area==2){area=n>0?1:0;}
                    else if(area==0){if(sec<secs-1){sec++;sectionMoved();}}
                    else if(sel+COLS<n)sel+=COLS;else if(n>0&&sel/COLS<(n-1)/COLS)sel=n-1;
                    invalidate();return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    if(area==0){if(sec>0){sec--;sectionMoved();}else{area=2;head=0;}}
                    else if(area==1){if(sel>=COLS)sel-=COLS;else{area=2;head=0;}}
                    invalidate();return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:   // RTL: the sections are on the right
                    if(area==2){if(head>0)head--;}
                    else if(area==1){if(sel%COLS==0||n==0)area=0;else sel--;}
                    invalidate();return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if(area==2){if(head<1)head++;}
                    else if(area==0){if(n>0)area=1;}
                    else if(sel%COLS!=COLS-1&&sel+1<n)sel++;
                    invalidate();return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:case KeyEvent.KEYCODE_ENTER:
                    if(e.getRepeatCount()==0)e.startTracking();
                    return true;
            }
            return super.onKeyDown(key,e);
        }
        @Override public boolean onKeyLongPress(int key,KeyEvent e){
            if(key!=KeyEvent.KEYCODE_DPAD_CENTER&&key!=KeyEvent.KEYCODE_ENTER)return super.onKeyLongPress(key,e);
            List<VodData.Movie> list=shown();
            if(area==1&&sel>=0&&sel<list.size()){
                boolean now=data.toggleFavorite(list.get(sel));
                android.widget.Toast.makeText(getContext(),now?"أُضيف إلى المفضلة":"أُزيل من المفضلة",android.widget.Toast.LENGTH_SHORT).show();
                shownFor="";invalidate();
            }
            return true;
        }
        @Override public boolean onKeyUp(int key,KeyEvent e){
            if((key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER)&&e.isTracking()&&!e.isCanceled()){
                if(area==2){
                    if(head==0)askSearch();
                    else{sort=(sort+1)%3;shownFor="";sel=0;top=0;invalidate();}
                }else if(area==0){
                    VodData.Category c=current();
                    if(data.failed(c.id))loadSection();
                    if(!shown().isEmpty())area=1;
                    invalidate();
                }else{
                    List<VodData.Movie> list=shown();
                    if(sel>=0&&sel<list.size()){
                        VodData.Movie m=list.get(sel);
                        String name=data.categoryName(m.cat);
                        startActivity(series?SeriesActivity.intent(getContext(),m,name):details(getContext(),m,name));
                    }
                }
                return true;
            }
            return super.onKeyUp(key,e);
        }
        private void askSearch(){
            final EditText input=new EditText(getContext());input.setSingleLine(true);input.setText(data.searchFor);
            input.setHint(w("اسم الفيلم","اسم المسلسل"));
            new AlertDialog.Builder(getContext()).setTitle(w("بحث في الأفلام","بحث في المسلسلات")).setView(input)
                    .setPositiveButton("بحث",(d,w)->{
                        String q=input.getText().toString().trim();
                        boolean had=!data.searchFor.isEmpty();
                        data.search(q,()->{shownFor="";invalidate();});
                        if(!q.isEmpty()){sec=3;area=1;}else if(had){if(sec==3)sec=2;else if(sec>3)sec--;}
                        sel=0;top=0;shownFor="";invalidate();
                    })
                    .setNegativeButton("مسح",(d,w)->{
                        boolean had=!data.searchFor.isEmpty();
                        data.search("",()->{shownFor="";invalidate();});
                        if(had){if(sec==3)sec=2;else if(sec>3)sec--;}
                        sel=0;top=0;shownFor="";invalidate();}).show();
        }
    }
}
