package io.ouswis.tv.standalone;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import java.util.ArrayList;
import java.util.List;

/**
 * A film's page (movie.png): the poster, the name, year, length, rating, the story, the cast and the director (from
 * get_vod_info), "play" or "continue from…" with how far it got, and films of the same section under it.
 */
public final class MovieActivity extends Activity {
    private XtreamAccount account;
    private VodData data;
    private VodData.Movie movie;
    private String section;
    private PageView view;

    @Override public void finish(){super.finish();overridePendingTransition(0,0);}
    @Override protected void onCreate(Bundle b){
        super.onCreate(b);
        overridePendingTransition(0,0);
        Ui.init(this);
        getWindow().getDecorView().setSystemUiVisibility(5894);
        account=XtreamAccount.read(this);
        if(account==null){startActivity(new Intent(this,XtreamActivity.class));finish();return;}
        Intent i=getIntent();
        movie=new VodData.Movie(str("id"),str("name"),str("icon"),str("ext"),str("cat"),i.getFloatExtra("rating",0),i.getLongExtra("added",0));
        section=str("section");
        data=new VodData(this,account);
        view=new PageView(this);
        FrameLayout root=new FrameLayout(this);
        root.addView(view,new FrameLayout.LayoutParams(-1,-1));
        setContentView(root);
        view.requestFocus();
        data.loadInfo(movie.id,()->view.infoReady());
        if(!movie.cat.isEmpty())data.loadMovies(movie.cat,()->view.invalidate());
        if(section.isEmpty())data.loadCategories(()->{if(section.isEmpty()){section=data.categoryName(movie.cat);view.invalidate();}});
    }
    private String str(String k){String s=getIntent().getStringExtra(k);return s==null?"":s;}
    private boolean busy;
    @Override protected void onResume(){super.onResume();if(!busy){busy=true;Busy.enter();}if(view!=null)view.resumed();}
    @Override protected void onPause(){if(busy){busy=false;Busy.leave();}super.onPause();}
    @Override protected void onDestroy(){if(busy){busy=false;Busy.leave();}if(data!=null)data.shutdown();super.onDestroy();}

    private void play(long from){
        VodData.Info in=data.info(movie.id);
        String ext=in!=null&&!in.ext.isEmpty()?in.ext:movie.ext;
        long dur=in!=null&&in.seconds>0?in.seconds*1000L:0;
        startActivity(VodPlayerActivity.intent(this,movie,ext,from,dur));
    }

    private final class PageView extends View {
        private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint kind=Design.text(Design.MUTED,22,Design.regular()),title=Design.text(Design.TEXT,64,Design.bold()),
                chip=Design.text(Design.TEXT,21,Design.regular()),star=Design.text(Design.GOLD,22,Design.bold()),
                label=Design.text(Design.GOLD,21,Design.regular()),people=Design.text(Design.MUTED,21,Design.regular()),
                btn=Design.text(0xFF1A1408,26,Design.bold()),btnOff=Design.text(Design.TEXT,25,Design.bold()),
                left=Design.text(Design.MUTED,17,Design.regular()),heading=Design.text(Design.MUTED,24,Design.regular()),
                hint=Design.text(Design.MUTED,17,Design.regular());
        private final TextPaint plotPaint=new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r=new RectF(),g=new RectF();
        private final A11y a11y=new A11y(this);
        private final VodDraw big=new VodDraw(470,704,22),small=new VodDraw(150,223,17);
        private String focusId="";
        private long focusAt;
        private boolean marquee;
        private final Runnable marqueeTick=()->{marquee=false;invalidate();};
        @Override public android.view.accessibility.AccessibilityNodeProvider getAccessibilityNodeProvider(){return a11y.provider(null,null);}
        private Bitmap backdrop;
        private float k=1;
        private int row,col,simFirst;   // row 0 buttons, 1 similar
        private StaticLayout plot;
        private String castLine="",directorLine="";
        private VodData.Progress progress;

        PageView(Context c){
            super(c);setFocusable(true);setFocusableInTouchMode(true);
            plotPaint.setColor(0xD9F4EFE4);plotPaint.setTextSize(24);plotPaint.setTypeface(Design.regular());
        }
        @Override protected void onSizeChanged(int w,int h,int ow,int oh){k=w/1920f;backdrop=Wallpaper.cinema(getContext(),w,h);}
        void resumed(){progress=VodData.progress(getContext(),account,movie.id);if(col>=buttons().length)col=0;invalidate();}
        void infoReady(){
            VodData.Info in=data.info(movie.id);
            if(in!=null){
                String text=in.plot.isEmpty()?"":in.plot;
                plot=text.isEmpty()?null:StaticLayout.Builder.obtain(text,0,text.length(),plotPaint,990)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL).setTextDirection(android.text.TextDirectionHeuristics.ANYRTL_LTR)
                        .setLineSpacing(10,1).setMaxLines(4).setEllipsize(TextUtils.TruncateAt.END).build();
                castLine=Design.fit(in.cast,people,880);directorLine=Design.fit(in.director,people,880);
                if(!in.cat.isEmpty()&&movie.cat.isEmpty())data.loadMovies(in.cat,this::invalidate);
            }
            invalidate();
        }
        private boolean canResume(){return progress!=null&&progress.resumable();}
        private String[] buttons(){
            boolean fav=data.isFavorite(movie.id);
            String favText=fav?"★ في المفضلة":"☆ مفضلة";
            return canResume()?new String[]{"▶ كمّل من "+VodData.clock(progress.pos),"↺ من البداية",favText}:new String[]{"▶ تشغيل",favText};
        }
        private List<VodData.Movie> similar(){
            VodData.Info in=data.info(movie.id);
            String cat=!movie.cat.isEmpty()?movie.cat:in!=null?in.cat:"";
            List<VodData.Movie> all=cat.isEmpty()?null:data.cached(cat);
            List<VodData.Movie> out=new ArrayList<>();
            if(all!=null)for(VodData.Movie m:all){if(!m.id.equals(movie.id))out.add(m);if(out.size()>=30)break;}
            return out;
        }

        @Override protected void onDraw(Canvas c){
            int w=getWidth(),h=getHeight();
            if(backdrop!=null)c.drawBitmap(backdrop,null,new RectF(0,0,w,h),null);else c.drawColor(Design.BG);
            a11y.begin(k);
            c.save();c.scale(k,k);
            VodData.Info in=data.info(movie.id);
            // poster
            r.set(72,81,542,785);
            Bitmap pb=PosterCache.get(movie.icon,PosterCache.LARGE,this::invalidate);
            if(pb==null)pb=PosterCache.get(movie.icon,PosterCache.GRID,this::invalidate);
            String rate=movie.rating>0?movie.ratingText():in!=null&&in.rating>0?String.format(java.util.Locale.US,"%.1f",in.rating):"";
            big.draw(c,r,pb,movie.shown,rate,true);
            // kind, name and the facts
            String kindText="أفلام"+(section.isEmpty()?"":" · "+section);
            Design.right(c,kindText,1848,129,kind);
            Design.right(c,Design.fitShrink(movie.shown,title,1240),1848,205,title);
            a11y.add(560,140,1848,220,movie.shown,false,false);
            List<String> facts=new ArrayList<>();
            if(!rate.isEmpty())facts.add("★"+rate);
            String year=in!=null&&!in.year.isEmpty()?in.year:movie.year;   // the year shows here only, not under the posters
            if(!year.isEmpty())facts.add(year);
            if(in!=null&&in.seconds>0)facts.add(VodData.duration(in.seconds));
            if(in!=null&&!in.quality.isEmpty())facts.add(in.quality);
            float x=1848;
            for(String f:facts){
                boolean isRate=f.startsWith("★");String t=isRate?f.substring(1):f;
                float tw=chip.measureText(t)+(isRate?star.measureText("★")+8:0),bw=tw+48;
                r.set(x-bw,239,x,287);Design.card(c,r,24,1,p,Design.SURFACE);
                if(isRate){Design.right(c,"★",x-24,271,star);Design.right(c,t,x-24-star.measureText("★")-8,271,chip);}
                else Design.center(c,t,x-bw/2,271,chip);
                a11y.add(r,isRate?"التقييم "+t:t,false,false);
                x-=bw+14;
            }
            // story, cast, director
            float y=320;
            if(plot!=null){
                c.save();c.translate(1848-990,y);plot.draw(c);c.restore();
                a11y.add(858,y,1848,y+plot.getHeight(),plot.getText().toString(),false,false);
                y+=plot.getHeight()+26;
            }else if(in==null){Design.right(c,"جارٍ تحميل التفاصيل…",1848,y+30,people);y+=60;}
            if(!castLine.isEmpty()){
                float lw=label.measureText("بطولة: ");
                Design.right(c,"بطولة: ",1848,y+24,label);Design.right(c,castLine,1848-lw,y+24,people);
                a11y.add(858,y,1848,y+30,"بطولة: "+castLine,false,false);y+=34;
            }
            if(!directorLine.isEmpty()){
                float lw=label.measureText("إخراج: ");
                Design.right(c,"إخراج: ",1848,y+24,label);Design.right(c,directorLine,1848-lw,y+24,people);
                a11y.add(858,y,1848,y+30,"إخراج: "+directorLine,false,false);y+=34;
            }
            // buttons
            String[] bs=buttons();
            float[] widths=bs.length==3?new float[]{360,260,200}:new float[]{360,200};
            float bx=1848,by=Math.max(522,y+30);
            for(int i=0;i<bs.length;i++){
                r.set(bx-widths[i],by,bx,by+67);
                boolean focus=row==0&&col==i;
                RectF box=r;if(focus){Design.grown(r,g);box=g;}
                if(i==0)Design.gold(c,box,22,p);else Design.card(c,box,22,1,p,0xCC12162B);
                Paint tp=i==0?btn:btnOff;
                Design.center(c,bs[i],box.centerX(),box.centerY()+9,tp);
                a11y.add(box,bs[i],focus,true);
                if(focus)Design.focusRing(c,box,22,1,p);
                bx-=widths[i]+20;
            }
            float after=by+67;
            if(canResume()&&progress.dur>0){
                float f=Math.max(0,Math.min(1,progress.pos/(float)progress.dur));
                r.set(1188,by+90,1848,by+95);Design.fill(c,r,3,p,0x33FFFFFF);
                r.set(1848-660*f,by+90,1848,by+95);Design.fill(c,r,3,p,Design.GOLD);
                String rest="باقي "+VodData.duration((progress.dur-progress.pos)/1000);
                Design.right(c,rest,1848,by+120,left);
                a11y.add(1188,by+80,1848,by+126,rest,false,false);
                after=by+126;
            }
            // films of the same section
            List<VodData.Movie> sim=similar();
            if(!sim.isEmpty()){
                float sy=Math.max(703,after+50);
                Design.right(c,"مقترحة لك",1848,sy,heading);
                a11y.add(1500,sy-30,1848,sy+6,"مقترحة لك",false,false);
                int slots=6;
                if(row==1){if(col<simFirst)simFirst=col;if(col>=simFirst+slots)simFirst=col-slots+1;}
                for(int i=simFirst;i<Math.min(sim.size(),simFirst+slots);i++){
                    float right=1848-(i-simFirst)*170,top=sy+27;
                    r.set(right-150,top,right,top+223);
                    boolean focus=row==1&&col==i;
                    RectF box=r;if(focus){Design.grown(r,g);box=g;}
                    VodData.Movie m=sim.get(i);
                    long scroll=-1;
                    if(focus){if(!m.id.equals(focusId)){focusId=m.id;focusAt=android.os.SystemClock.uptimeMillis();}scroll=android.os.SystemClock.uptimeMillis()-focusAt;}
                    if(small.draw(c,box,PosterCache.get(m.icon,PosterCache.GRID,this::invalidate),m.shown,m.ratingText(),true,scroll)&&!marquee){marquee=true;postDelayed(marqueeTick,33);}
                    a11y.add(box,m.shown,focus,true);
                    if(focus)Design.focusRing(c,box,14,1,p);
                }
            }
            Design.right(c,"OK تشغيل · BACK رجوع",1848,1054,hint);
            Design.left(c,"تفاصيل الفيلم",72,1054,hint);
            c.restore();
        }

        @Override public boolean onKeyDown(int key,KeyEvent e){
            int nb=buttons().length,ns=similar().size();
            switch(key){
                case KeyEvent.KEYCODE_DPAD_DOWN:if(row==0&&ns>0){row=1;col=simFirst;}invalidate();return true;
                case KeyEvent.KEYCODE_DPAD_UP:if(row==1){row=0;col=0;}invalidate();return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:if(col>0)col--;invalidate();return true;   // RTL
                case KeyEvent.KEYCODE_DPAD_LEFT:if(col<(row==0?nb:ns)-1)col++;invalidate();return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:case KeyEvent.KEYCODE_ENTER:
                    if(e.getRepeatCount()==0)activate();
                    return true;
                case KeyEvent.KEYCODE_MEDIA_PLAY:case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                    if(e.getRepeatCount()==0)play(canResume()?progress.pos:0);
                    return true;
            }
            return super.onKeyDown(key,e);
        }
        private void activate(){
            if(row==0){
                String[] bs=buttons();
                boolean favButton=col==bs.length-1;
                if(favButton){
                    boolean now=data.toggleFavorite(movie);
                    android.widget.Toast.makeText(getContext(),now?"أُضيف إلى المفضلة":"أُزيل من المفضلة",android.widget.Toast.LENGTH_SHORT).show();
                    invalidate();
                }else if(col==0)play(canResume()?progress.pos:0);
                else play(0);
            }else{
                List<VodData.Movie> sim=similar();
                if(col<sim.size()){startActivity(MoviesActivity.details(getContext(),sim.get(col),section));finish();}
            }
        }
    }
}
