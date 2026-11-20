package io.ouswis.tv.standalone;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
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
 * A series' page (series.png): the poster, name, rating, seasons and year on the right with "continue: S2 · E3", the
 * season tabs and that season's episodes on the left, each with its picture, "✓ شوهدت" once watched, its length and how
 * far it got. ◀▶ change the season, ▲▼ the episode, OK plays it (from where it stopped).
 */
public final class SeriesActivity extends Activity {
    private XtreamAccount account;
    private VodData data;
    private VodData.Movie show;
    private String section;
    private PageView view;

    static Intent intent(Context c,VodData.Movie m,String sectionName){
        return new Intent(c,SeriesActivity.class).putExtra("id",m.id).putExtra("name",m.name).putExtra("icon",m.icon)
                .putExtra("cat",m.cat).putExtra("rating",m.rating).putExtra("added",m.added).putExtra("section",sectionName==null?"":sectionName);
    }

    @Override public void finish(){super.finish();overridePendingTransition(0,0);}
    @Override protected void onCreate(Bundle b){
        super.onCreate(b);
        overridePendingTransition(0,0);
        Ui.init(this);
        getWindow().getDecorView().setSystemUiVisibility(5894);
        account=XtreamAccount.read(this);
        if(account==null){startActivity(new Intent(this,XtreamActivity.class));finish();return;}
        Intent i=getIntent();
        show=new VodData.Movie(str("id"),str("name"),str("icon"),"",str("cat"),i.getFloatExtra("rating",0),i.getLongExtra("added",0));
        section=str("section");
        data=new VodData(this,account,true);
        view=new PageView(this);
        FrameLayout root=new FrameLayout(this);
        root.addView(view,new FrameLayout.LayoutParams(-1,-1));
        setContentView(root);
        view.requestFocus();
        data.loadSeries(show.id,()->view.infoReady());
    }
    private String str(String k){String s=getIntent().getStringExtra(k);return s==null?"":s;}
    private boolean busy;
    @Override protected void onResume(){super.onResume();if(!busy){busy=true;Busy.enter();}if(view!=null)view.resumed();}
    @Override protected void onPause(){if(busy){busy=false;Busy.leave();}super.onPause();}
    @Override protected void onDestroy(){if(busy){busy=false;Busy.leave();}if(data!=null)data.shutdown();super.onDestroy();}

    private static String label(VodData.Episode e){return "م"+e.season+" · ح"+e.num;}
    private void play(VodData.Episode e,long from){
        startActivity(VodPlayerActivity.episode(this,show,e,from,show.shown+" · "+label(e)));
    }

    private final class PageView extends View {
        private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        private final Paint title=Design.text(Design.TEXT,56,Design.bold()),chip=Design.text(Design.TEXT,21,Design.regular()),
                star=Design.text(Design.GOLD,22,Design.bold()),tab=Design.text(Design.TEXT,22,Design.regular()),tabOn=Design.text(0xFF1A1408,22,Design.bold()),
                epTitle=Design.text(Design.TEXT,25,Design.bold()),epText=Design.text(Design.MUTED,19,Design.regular()),epTime=Design.text(Design.MUTED,18,Design.regular()),
                badge=Design.text(0xFFFFFFFF,15,Design.regular()),btn=Design.text(0xFF1A1408,26,Design.bold()),hint=Design.text(Design.MUTED,17,Design.regular()),
                msg=Design.text(Design.MUTED,26,Design.regular());
        private final TextPaint plotPaint=new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r=new RectF(),g=new RectF(),t=new RectF(),tag=new RectF();
        private java.util.Map<String,VodData.Progress> progress=new java.util.HashMap<>();
        private boolean started;
        private final android.graphics.Rect srcI=new android.graphics.Rect();
        private final Path clip=new Path();
        private final A11y a11y=new A11y(this);
        private final VodDraw poster=new VodDraw(420,630,20);
        @Override public android.view.accessibility.AccessibilityNodeProvider getAccessibilityNodeProvider(){return a11y.provider(null,null);}
        private Bitmap backdrop;
        private float k=1;
        private int season,sel,top;          // season: index in info.seasons; sel: episode index in that season
        private boolean onButton;            // the focus is on "continue" (right of the season tabs' first season)
        private StaticLayout plot;
        private VodData.Episode next;        // what "continue" plays
        private long nextFrom;
        private boolean placed;

        PageView(Context c){
            super(c);setFocusable(true);setFocusableInTouchMode(true);
            plotPaint.setColor(0xD9F4EFE4);plotPaint.setTextSize(21);plotPaint.setTypeface(Design.regular());
        }
        @Override protected void onSizeChanged(int w,int h,int ow,int oh){k=w/1920f;backdrop=Wallpaper.inner(getContext(),w,h);}
        private VodData.SeriesInfo info(){return data.seriesInfo(show.id);}
        void resumed(){work();invalidate();}
        void infoReady(){
            VodData.SeriesInfo in=info();
            if(in!=null)VodData.tagEpisodes(getContext(),account,show,in);
            if(in!=null){
                String text=in.info.plot;
                plot=text.isEmpty()?null:StaticLayout.Builder.obtain(text,0,text.length(),plotPaint,420)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL).setTextDirection(android.text.TextDirectionHeuristics.ANYRTL_LTR)
                        .setLineSpacing(6,1).setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).build();
            }
            work();
            if(in!=null&&!placed&&next!=null){   // open on the episode "continue" would play
                placed=true;
                season=Math.max(0,in.seasons.indexOf(next.season));
                List<VodData.Episode> eps=in.of(next.season);
                for(int i=0;i<eps.size();i++)if(eps.get(i).id.equals(next.id))sel=i;
            }
            invalidate();
        }
        /** Which episode "continue" plays: the last one started if it is not finished, else the one after it; else the first. */
        private void work(){
            VodData.SeriesInfo in=info();
            progress=VodData.episodes(getContext(),account);
            String lastId=VodData.lastEpisode(getContext(),account,show.id);
            started=!lastId.isEmpty();
            if(in==null){next=null;return;}
            VodData.Episode last=lastId.isEmpty()?null:in.find(lastId);
            next=null;nextFrom=0;
            if(last!=null){
                VodData.Progress pr=progress.get(last.id);
                if(pr!=null&&!VodData.watched(pr)){next=last;nextFrom=pr.resumable()?pr.pos:0;}
                else next=in.after(last);
            }
            if(next==null)next=in.first();
        }
        private String buttonText(){
            if(next==null)return "▶ تشغيل";
            return (started?"▶ كمّل: ":"▶ تشغيل: ")+label(next);
        }

        @Override protected void onDraw(Canvas c){
            int w=getWidth(),h=getHeight();
            if(backdrop!=null)c.drawBitmap(backdrop,null,new RectF(0,0,w,h),null);else c.drawColor(Design.BG);
            a11y.begin(k);
            c.save();c.scale(k,k);
            VodData.SeriesInfo in=info();
            // the right column: poster, name, facts, story, continue
            r.set(1428,60,1848,690);
            String rate=show.rating>0?show.ratingText():in!=null&&in.info.rating>0?String.format(java.util.Locale.US,"%.1f",in.info.rating):"";
            Bitmap pb=PosterCache.get(show.icon,PosterCache.LARGE,this::invalidate);
            if(pb==null)pb=PosterCache.get(show.icon,PosterCache.GRID,this::invalidate);
            poster.draw(c,r,pb,show.shown,rate,true);
            Design.right(c,Design.fitShrink(show.shown,title,420),1848,756,title);
            a11y.add(1428,700,1848,770,show.shown,false,false);
            List<String> facts=new ArrayList<>();
            if(!rate.isEmpty())facts.add("★"+rate);
            if(in!=null&&!in.seasons.isEmpty())facts.add(VodData.seasons(in.seasons.size()));
            String year=in!=null&&!in.info.year.isEmpty()?in.info.year:show.year;
            if(!year.isEmpty())facts.add(year);
            float x=1848;
            for(String f:facts){
                boolean isRate=f.startsWith("★");String t=isRate?f.substring(1):f;
                float tw=chip.measureText(t)+(isRate?star.measureText("★")+8:0),bw=tw+44;
                r.set(x-bw,776,x,824);Design.card(c,r,24,1,p,Design.SURFACE);
                if(isRate){Design.right(c,"★",x-22,808,star);Design.right(c,t,x-22-star.measureText("★")-8,808,chip);}
                else Design.center(c,t,x-bw/2,808,chip);
                a11y.add(r,isRate?"التقييم "+t:t,false,false);
                x-=bw+12;
            }
            float by=896;
            if(plot!=null){
                c.save();c.translate(1428,842);plot.draw(c);c.restore();
                a11y.add(1428,842,1848,842+plot.getHeight(),plot.getText().toString(),false,false);
                by=Math.max(896,842+plot.getHeight()+14);
            }
            r.set(1428,by,1848,by+67);
            RectF box=r;if(onButton){Design.grown(r,g);box=g;}
            Design.gold(c,box,20,p);
            String bt=buttonText();
            Design.center(c,bt,box.centerX(),box.centerY()+9,btn);
            a11y.add(box,bt,onButton,true);
            if(onButton)Design.focusRing(c,box,20,1,p);
            // the left: seasons, then the episodes
            if(in==null){
                String m=data.seriesFailed(show.id)?"تعذّر تحميل الحلقات · اضغط OK لإعادة المحاولة":"جارٍ تحميل الحلقات…";
                Design.center(c,m,676,560,msg);a11y.add(72,520,1280,600,m,false,false);
            }else if(in.seasons.isEmpty()){
                Design.center(c,"لا توجد حلقات لهذا المسلسل",676,560,msg);a11y.add(72,520,1280,600,"لا توجد حلقات لهذا المسلسل",false,false);
            }else{
                if(season>=in.seasons.size())season=in.seasons.size()-1;
                float tx=1280;
                for(int i=0;i<in.seasons.size();i++){
                    int n=in.seasons.get(i);
                    String t=n==0?"إضافات":"الموسم "+n;
                    Paint tp=i==season?tabOn:tab;
                    float tw=tp.measureText(t)+48;
                    if(tx-tw<72)break;
                    r.set(tx-tw,60,tx,107);
                    if(i==season)Design.gold(c,r,24,p);else Design.card(c,r,24,1,p,0xCC12162B);
                    Design.center(c,t,r.centerX(),r.centerY()+8,tp);
                    a11y.add(r,t+(i==season?" — محدد":""),false,true);
                    tx-=tw+13;
                }
                List<VodData.Episode> eps=in.of(in.seasons.get(season));
                int rows=5;
                if(sel>=eps.size())sel=Math.max(0,eps.size()-1);
                if(sel<top)top=sel;if(sel>=top+rows)top=sel-rows+1;if(top<0)top=0;
                for(int i=top;i<Math.min(eps.size(),top+rows);i++){
                    VodData.Episode e=eps.get(i);
                    float y=130+(i-top)*184;
                    r.set(72,y,1280,y+168);
                    boolean focus=i==sel&&!onButton;
                    box=r;if(focus){Design.grown(r,g);box=g;}
                    Design.card(c,box,22,1,p,0xCC12162B);
                    // the picture: the episode's own, else the series poster
                    t.set(1015,y+15,1265,y+155);
                    Bitmap img=PosterCache.get(e.image.isEmpty()?show.icon:e.image,PosterCache.GRID,this::invalidate);
                    c.save();clip.reset();clip.addRoundRect(t,14,14,Path.Direction.CW);c.clipPath(clip);
                    p.setStyle(Paint.Style.FILL);p.setColor(0xFF1A1F3A);c.drawRect(t,p);
                    if(img!=null){
                        float s=Math.max(t.width()/img.getWidth(),t.height()/img.getHeight());
                        float sw=t.width()/s,sh=t.height()/s;
                        srcI.set(Math.round((img.getWidth()-sw)/2),Math.round((img.getHeight()-sh)/2),Math.round((img.getWidth()+sw)/2),Math.round((img.getHeight()+sh)/2));
                        p.setColor(0xFFFFFFFF);c.drawBitmap(img,srcI,t,p);
                    }
                    c.restore();
                    VodData.Progress pr=progress.get(e.id);
                    boolean seen=VodData.watched(pr);
                    if(seen){
                        float bw=badge.measureText("✓ شوهدت")+22;
                        tag.set(1023,y+23,1023+bw,y+51);Design.fill(c,tag,14,p,0xCC0B0D1A);   // not g: g holds the focused row's box
                        Design.center(c,"✓ شوهدت",tag.centerX(),y+42,badge);
                    }
                    String head="الحلقة "+e.num;
                    Design.right(c,head,993,y+64,epTitle);
                    String text=!e.plot.isEmpty()?e.plot:e.title;
                    Design.right(c,Design.fit(text,epText,880),993,y+96,epText);
                    long secs=e.seconds>0?e.seconds:pr!=null&&pr.dur>0?pr.dur/1000:0;
                    String len=VodData.duration(secs);
                    if(!len.isEmpty())Design.right(c,len,993,y+130,epTime);
                    boolean partly=pr!=null&&!seen&&pr.dur>0&&pr.pos>0;
                    if(partly){
                        float f=Math.max(0,Math.min(1,pr.pos/(float)pr.dur)),bx=993-epTime.measureText(len)-20;
                        r.set(bx-260,y+122,bx,y+127);Design.fill(c,r,3,p,0x33FFFFFF);
                        r.set(bx-260*f,y+122,bx,y+127);Design.fill(c,r,3,p,Design.GOLD);
                    }
                    a11y.add(box,head+(seen?" · شوهدت":partly?" · متوقفة عند "+VodData.clock(pr.pos):"")+(len.isEmpty()?"":" · "+len),focus,true);
                    if(focus)Design.focusRing(c,box,22,1,p);
                }
            }
            Design.right(c,"◀▶ المواسم · ▲▼ الحلقات · OK تشغيل",1848,1054,hint);
            Design.left(c,"تفاصيل المسلسل",72,1054,hint);
            c.restore();
        }

        @Override public boolean onKeyDown(int key,KeyEvent e){
            VodData.SeriesInfo in=info();
            int ns=in==null?0:in.seasons.size();
            int ne=in==null||ns==0?0:in.of(in.seasons.get(Math.min(season,ns-1))).size();
            switch(key){
                case KeyEvent.KEYCODE_DPAD_DOWN:if(!onButton&&sel<ne-1)sel++;invalidate();return true;
                case KeyEvent.KEYCODE_DPAD_UP:if(!onButton&&sel>0)sel--;invalidate();return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:   // RTL: season 1 is on the right; past it, "continue"
                    if(onButton)return true;
                    if(season>0){season--;sel=0;top=0;}else onButton=true;
                    invalidate();return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if(onButton){if(ns>0)onButton=false;}
                    else if(season<ns-1){season++;sel=0;top=0;}
                    invalidate();return true;
                case KeyEvent.KEYCODE_MEDIA_PLAY:case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                    if(e.getRepeatCount()==0&&next!=null)play(next,nextFrom);
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:case KeyEvent.KEYCODE_ENTER:
                    if(e.getRepeatCount()>0)return true;
                    if(in==null){if(data.seriesFailed(show.id))data.loadSeries(show.id,this::infoReady);return true;}
                    if(onButton){if(next!=null)play(next,nextFrom);return true;}
                    if(ns==0||ne==0)return true;
                    VodData.Episode ep=in.of(in.seasons.get(season)).get(sel);
                    VodData.Progress pr=progress.get(ep.id);
                    play(ep,pr!=null&&pr.resumable()?pr.pos:0);
                    return true;
            }
            return super.onKeyDown(key,e);
        }
    }
}
