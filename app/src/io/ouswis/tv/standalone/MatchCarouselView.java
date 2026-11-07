package io.ouswis.tv.standalone;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The matches carousel: one large glass card (crests, score or kick-off, status, goals) with the previous and next
 * cards behind it. Left/right move between matches, OK opens the details. Drawn by hand (no allocations in onDraw
 * beyond rounded-rect bounds); crests load in the background. It never invents matches: the data comes from
 * {@link UpcomingMatches}.
 */
public final class MatchCarouselView extends View {
    public static final class Match {
        public final String competition, home, away, kickoff;
        public final long kickoffMillis;
        public String key="", state="", status="", leagueLogo="", homeLogo="", awayLogo="", homeScore="", awayScore="";
        public final List<String> goalsHome=new ArrayList<>(), goalsAway=new ArrayList<>();
        public Match(String competition, String home, String away, String kickoff) {
            this(competition,home,away,kickoff,0L);
        }
        public Match(String competition,String home,String away,String kickoff,long millis){
            this.competition=competition; this.home=home; this.away=away; this.kickoff=kickoff; this.kickoffMillis=millis;
        }
        boolean live(){return "in".equals(state);}
        boolean upcoming(){return "pre".equals(state)||state.isEmpty();}
    }
    public interface OnMatchSelected { void onSelected(Match match); }

    private static final LruCache<String,Bitmap> CRESTS=new LruCache<>(96);
    private static final Set<String> LOADING=new HashSet<>();
    private static final ExecutorService LOADER=Executors.newFixedThreadPool(3);
    private final Handler main=new Handler(Looper.getMainLooper());
    private final List<Match> matches=new ArrayList<>();
    private final Paint fill=new Paint(Paint.ANTI_ALIAS_FLAG), line=new Paint(Paint.ANTI_ALIAS_FLAG),
            ink=new Paint(Paint.ANTI_ALIAS_FLAG), bmp=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final RectF box=new RectF();
    private int index;
    private float slide;           // animates from ±1 to 0 when the selected card changes
    private ValueAnimator animator;
    private String message="جارٍ التحميل…";
    private OnMatchSelected listener;

    public MatchCarouselView(Context context) {
        super(context);
        setFocusable(true);
        setClickable(true);
        // The Android installer leaves touch mode active; remote playback must still start focused.
        setFocusableInTouchMode(true);
        setOnFocusChangeListener((v,focused)->invalidate());
        line.setStyle(Paint.Style.STROKE);
        ink.setTypeface(Typeface.create(Typeface.DEFAULT,Typeface.BOLD));
    }

    public void setMessage(String text){message=text;invalidate();}
    public void setMatches(List<Match> data) {
        matches.clear();if(data!=null)matches.addAll(data);
        index=0;slide=0;if(animator!=null)animator.cancel();
        invalidate();
    }
    Match selectedMatch(){return matches.isEmpty()?null:matches.get(index);}
    public void setOnMatchSelected(OnMatchSelected callback){listener=callback;}

    private void go(int step){
        if(matches.size()<2)return;
        index=(index+step+matches.size())%matches.size();
        if(animator!=null)animator.cancel();
        slide=step>0?1f:-1f;
        animator=ValueAnimator.ofFloat(slide,0f);
        animator.setDuration(300);animator.setInterpolator(new DecelerateInterpolator(1.6f));
        animator.addUpdateListener(a->{slide=(float)a.getAnimatedValue();invalidate();});
        animator.start();
    }
    @Override public boolean onKeyDown(int keyCode,KeyEvent event){
        if(keyCode==KeyEvent.KEYCODE_DPAD_LEFT){go(-1);return true;}
        if(keyCode==KeyEvent.KEYCODE_DPAD_RIGHT){go(1);return true;}
        if((keyCode==KeyEvent.KEYCODE_DPAD_CENTER||keyCode==KeyEvent.KEYCODE_ENTER)&&listener!=null&&!matches.isEmpty()){
            listener.onSelected(matches.get(index));return true;
        }
        return super.onKeyDown(keyCode,event);
    }
    @Override protected void onDetachedFromWindow(){if(animator!=null)animator.cancel();super.onDetachedFromWindow();}

    // ---------------------------------------------------------------- drawing

    @Override protected void onDraw(Canvas canvas){
        int w=getWidth(),h=getHeight();
        if(w<=0||h<=0)return;
        if(matches.isEmpty()){drawMessage(canvas,w,h);return;}
        float cw=Math.min(w*0.66f,h*1.9f),ch=h*0.94f,step=cw*0.72f;
        // Positions -2..2 around the selected card, farthest first so the middle one ends on top.
        for(int d=2;d>=0;d--){
            for(int sign=-1;sign<=1;sign+=2){
                if(d==0&&sign==1)continue;
                int offset=d*sign;
                float pos=offset+slide;                       // where this card sits now, in card steps
                int at=((index+offset)%matches.size()+matches.size())%matches.size();
                if(matches.size()==1&&offset!=0)continue;
                if(matches.size()==2&&Math.abs(offset)==2)continue;
                float near=Math.min(1f,Math.abs(pos));
                float scale=1f-0.20f*near-0.08f*Math.max(0f,Math.abs(pos)-1f);
                float alpha=Math.max(0f,1f-0.55f*near-0.3f*Math.max(0f,Math.abs(pos)-1f));
                if(alpha<=0.02f)continue;
                float cx=w/2f+pos*step*(1f-0.1f*near);
                int save=canvas.save();
                canvas.translate(cx,h/2f);
                canvas.scale(scale,scale);
                canvas.translate(-cw/2f,-ch/2f);
                if(Math.abs(pos)>0.05f)save=Math.min(save,canvas.saveLayerAlpha(0,0,cw,ch,(int)(alpha*255)));
                card(canvas,matches.get(at),cw,ch,Math.abs(pos)<0.5f);
                canvas.restoreToCount(save);
            }
        }
        // Position and arrows.
        ink.setColor(0xFF505058);ink.setTextAlign(Paint.Align.CENTER);ink.setTextSize(h*0.045f);
        canvas.drawText((index+1)+" / "+matches.size(),w/2f,h-h*0.012f,ink);
        if(matches.size()>1){
            ink.setTextSize(h*0.12f);ink.setColor(0xFF686870);
            canvas.drawText("‹",h*0.06f,h*0.56f,ink);canvas.drawText("›",w-h*0.06f,h*0.56f,ink);
        }
    }

    private void drawMessage(Canvas canvas,int w,int h){
        float cw=Math.min(w*0.66f,h*1.9f),ch=h*0.7f;
        int save=canvas.save();canvas.translate((w-cw)/2f,(h-ch)/2f);
        glass(canvas,cw,ch,false);
        ink.setColor(0xFF242428);ink.setTextAlign(Paint.Align.CENTER);ink.setTextSize(ch*0.11f);
        fit(canvas,message,cw/2f,ch/2f+ch*0.04f,cw*0.86f,ink);
        canvas.restoreToCount(save);
    }

    private void glass(Canvas canvas,float cw,float ch,boolean focused){
        float r=Math.min(cw,ch)*0.09f;
        box.set(0,0,cw,ch);
        fill.setShader(new LinearGradient(0,0,cw,ch,new int[]{0xF5F5F5F7,0xF5E0E0E5,0xF5C9C9CF},new float[]{0f,.55f,1f},Shader.TileMode.CLAMP));
        canvas.drawRoundRect(box,r,r,fill);
        fill.setShader(new LinearGradient(0,0,0,ch*0.45f,0x38FFFFFF,0x00FFFFFF,Shader.TileMode.CLAMP));
        canvas.drawRoundRect(box,r,r,fill);
        fill.setShader(null);
        line.setStrokeWidth(focused?Math.max(3f,ch*0.012f):Math.max(1.5f,ch*0.005f));
        line.setColor(focused?0xFF606068:0xFFB0B0B8);
        box.inset(line.getStrokeWidth()/2f,line.getStrokeWidth()/2f);
        canvas.drawRoundRect(box,r,r,line);
    }

    private void card(Canvas canvas,Match m,float cw,float ch,boolean front){
        glass(canvas,cw,ch,front&&hasFocus());
        // League.
        float top=ch*0.075f,small=ch*0.058f;
        ink.setTextAlign(Paint.Align.CENTER);ink.setColor(0xFF56565E);ink.setTextSize(small);
        float nameWidth=ink.measureText(m.competition);
        float icon=ch*0.075f,gap=ch*0.02f,total=Math.min(cw*0.8f,nameWidth)+(m.leagueLogo.isEmpty()?0:icon+gap);
        float x0=cw/2f-total/2f;
        if(!m.leagueLogo.isEmpty()){crest(canvas,m.leagueLogo,x0,top,icon);x0+=icon+gap;}
        ink.setTextAlign(Paint.Align.LEFT);
        fitLeft(canvas,m.competition,x0,top+icon*0.78f,cw*0.8f-(m.leagueLogo.isEmpty()?0:icon+gap),ink);
        // Crests and names.
        float crestSize=ch*0.30f,cy=ch*0.20f,side=cw*0.25f;
        crest(canvas,m.homeLogo,side-crestSize/2f,cy,crestSize);
        crest(canvas,m.awayLogo,cw-side-crestSize/2f,cy,crestSize);
        ink.setColor(0xFF242428);ink.setTextAlign(Paint.Align.CENTER);ink.setTextSize(ch*0.08f);
        fit(canvas,m.home,side,cy+crestSize+ch*0.10f,cw*0.38f,ink);
        fit(canvas,m.away,cw-side,cy+crestSize+ch*0.10f,cw*0.38f,ink);
        // Centre: score or kick-off.
        float mid=cy+crestSize*0.52f;
        if(m.upcoming()){
            ink.setColor(0xFF242428);ink.setTextSize(ch*0.15f);
            canvas.drawText("VS",cw/2f,mid,ink);
            ink.setColor(0xFF825600);ink.setTextSize(ch*0.058f);
            fit(canvas,m.kickoff,cw/2f,mid+ch*0.10f,cw*0.26f,ink);
        }else{
            ink.setColor(0xFF242428);ink.setTextSize(ch*0.17f);
            String score=m.homeScore+" - "+m.awayScore;
            fit(canvas,score,cw/2f,mid,cw*0.26f,ink);
            pill(canvas,m,cw/2f,mid+ch*0.12f,ch);
        }
        // Goals.
        float goalsTop=cy+crestSize+ch*0.19f;
        ink.setTextSize(ch*0.05f);ink.setColor(0xFF505058);
        goals(canvas,m.goalsHome,side,goalsTop,cw*0.4f,ch*0.062f);
        goals(canvas,m.goalsAway,cw-side,goalsTop,cw*0.4f,ch*0.062f);
    }

    private void goals(Canvas canvas,List<String> lines,float cx,float y,float width,float lead){
        int shown=Math.min(3,lines.size());
        for(int i=0;i<shown;i++)fit(canvas,"⚽ "+lines.get(i),cx,y+i*lead,width,ink);
        if(lines.size()>shown)fit(canvas,"+"+(lines.size()-shown),cx,y+shown*lead,width,ink);
    }

    private void pill(Canvas canvas,Match m,float cx,float y,float ch){
        if(m.status.isEmpty())return;
        ink.setTextSize(ch*0.052f);ink.setTextAlign(Paint.Align.CENTER);
        float tw=ink.measureText(m.status),padX=ch*0.04f,ph=ch*0.085f;
        box.set(cx-tw/2f-padX,y-ph*0.68f,cx+tw/2f+padX,y+ph*0.32f);
        fill.setColor(m.live()?0xFFE23B3B:0xFFBEBEC6);
        canvas.drawRoundRect(box,ph/2f,ph/2f,fill);
        ink.setColor(m.live()?0xFFFFFFFF:0xFF242428);
        canvas.drawText(m.status,cx,y,ink);
    }

    /** Draws text centred at x, shrinking it until it fits the width. */
    private void fit(Canvas canvas,String text,float x,float y,float width,Paint paint){
        float size=paint.getTextSize();
        while(paint.measureText(text)>width&&paint.getTextSize()>size*0.5f)paint.setTextSize(paint.getTextSize()-1f);
        Paint.Align was=paint.getTextAlign();paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(text,x,y,paint);paint.setTextAlign(was);paint.setTextSize(size);
    }
    private void fitLeft(Canvas canvas,String text,float x,float y,float width,Paint paint){
        float size=paint.getTextSize();
        while(paint.measureText(text)>width&&paint.getTextSize()>size*0.5f)paint.setTextSize(paint.getTextSize()-1f);
        canvas.drawText(text,x,y,paint);paint.setTextSize(size);
    }

    // ---------------------------------------------------------------- crests

    private void crest(Canvas canvas,String url,float x,float y,float size){
        Bitmap b=url==null||url.isEmpty()?null:CRESTS.get(url);
        box.set(x,y,x+size,y+size);
        if(b!=null){canvas.drawBitmap(b,null,box,bmp);return;}
        // Placeholder disc while the crest loads (or when the feed has none).
        fill.setColor(0x22606068);canvas.drawCircle(x+size/2f,y+size/2f,size*0.42f,fill);
        if(url!=null&&!url.isEmpty())load(url);
    }
    private void load(final String url){
        synchronized(LOADING){if(!url.startsWith("https://")||!LOADING.add(url))return;}
        LOADER.execute(()->{
            Bitmap decoded=null;HttpURLConnection c=null;
            try{
                c=(HttpURLConnection)new URL(url).openConnection();
                c.setConnectTimeout(6000);c.setReadTimeout(8000);
                if(c.getResponseCode()==200){
                    BitmapFactory.Options probe=new BitmapFactory.Options();probe.inJustDecodeBounds=true;
                    byte[] data;
                    try(InputStream in=c.getInputStream();java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){
                        byte[] buf=new byte[8192];int n;while((n=in.read(buf))>0&&out.size()<2_000_000)out.write(buf,0,n);data=out.toByteArray();
                    }
                    BitmapFactory.decodeByteArray(data,0,data.length,probe);
                    BitmapFactory.Options o=new BitmapFactory.Options();
                    o.inSampleSize=Math.max(1,Math.max(probe.outWidth,probe.outHeight)/256);
                    decoded=BitmapFactory.decodeByteArray(data,0,data.length,o);
                }
            }catch(Exception ignored){}finally{if(c!=null)c.disconnect();}
            final Bitmap result=decoded;
            synchronized(LOADING){LOADING.remove(url);}
            if(result!=null){CRESTS.put(url,result);main.post(this::invalidate);}
        });
    }
}
