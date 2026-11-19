package io.ouswis.tv.standalone;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import java.util.ArrayList;
import java.util.List;
import androidx.media3.exoplayer.ExoPlayer;

/**
 * A film, full screen, on the app's own player (Media3 ExoPlayer from LivePlayer, the same one the channels use) from
 * /movie/USER/PASS/ID.ext. The remote moves through it (▶ forward and ◀ back, the bar filling from left to right; held,
 * the steps grow), OK pauses, ▼ opens the sound and subtitles menu (TrackChoice), and where it stopped is saved every
 * 10 s and on leaving. Subtitles are drawn here (the app has no Media3 UI module).
 */
public final class VodPlayerActivity extends Activity {
    private final Handler main=new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private SurfaceView surface;
    private Bar bar;
    private ExoPlayer player;
    private XtreamAccount account;
    private VodData.Movie movie;
    private String ext;
    private long startAt,knownDur;
    private boolean ended,failedOnce;
    private String error="";
    private VodData.Movie show;   // set when this is an episode of that series
    private boolean tracksApplied;
    private List<Cue> cues=new ArrayList<>();
    private TrackMenu menu;

    /** An episode: `show` is the series (saved as its last episode), `title` what the bar shows (the series · S2 · E3). */
    static Intent episode(Context c,VodData.Movie show,VodData.Episode e,long from,String title){
        return new Intent(c,VodPlayerActivity.class).putExtra("id",e.id).putExtra("name",title).putExtra("ext",e.ext).putExtra("from",from).putExtra("dur",e.seconds*1000L)
                .putExtra("show",show.id).putExtra("showName",show.name).putExtra("showIcon",show.icon).putExtra("showCat",show.cat).putExtra("showRating",show.rating).putExtra("showAdded",show.added);
    }
    static Intent intent(Context c,VodData.Movie m,String ext,long from,long dur){
        return new Intent(c,VodPlayerActivity.class).putExtra("id",m.id).putExtra("name",m.name).putExtra("icon",m.icon).putExtra("ext",ext)
                .putExtra("cat",m.cat).putExtra("rating",m.rating).putExtra("added",m.added).putExtra("from",from).putExtra("dur",dur);
    }

    @Override public void finish(){super.finish();overridePendingTransition(0,0);}
    @Override protected void onCreate(Bundle b){
        super.onCreate(b);
        overridePendingTransition(0,0);
        Ui.init(this);
        getWindow().getDecorView().setSystemUiVisibility(5894);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        account=XtreamAccount.read(this);
        if(account==null){startActivity(new Intent(this,XtreamActivity.class));finish();return;}
        Intent i=getIntent();
        movie=new VodData.Movie(str("id"),str("name"),str("icon"),str("ext"),str("cat"),i.getFloatExtra("rating",0),i.getLongExtra("added",0));
        ext=str("ext").isEmpty()?movie.ext:str("ext");
        startAt=i.getLongExtra("from",0);knownDur=i.getLongExtra("dur",0);
        if(!str("show").isEmpty())show=new VodData.Movie(str("show"),str("showName"),str("showIcon"),"",str("showCat"),i.getFloatExtra("showRating",0),i.getLongExtra("showAdded",0));
        root=new FrameLayout(this);root.setBackgroundColor(Color.BLACK);
        surface=new SurfaceView(this);
        root.addView(surface,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));
        bar=new Bar(this);
        root.addView(bar,new FrameLayout.LayoutParams(-1,-1));
        menu=new TrackMenu(this);menu.setVisibility(View.GONE);
        root.addView(menu,new FrameLayout.LayoutParams(-1,-1));
        setContentView(root);
    }
    private String str(String k){String s=getIntent().getStringExtra(k);return s==null?"":s;}

    private boolean busy;
    @Override protected void onStart(){
        super.onStart();
        if(!busy){busy=true;Busy.enter();}
        if(account==null)return;
        player=LivePlayer.create(this,true);
        player.setVideoSurfaceView(surface);
        player.addListener(new Player.Listener(){
            @Override public void onPlaybackStateChanged(int state){
                if(state==Player.STATE_READY){error="";if(knownDur<=0&&player.getDuration()>0)knownDur=player.getDuration();}
                if(state==Player.STATE_ENDED){ended=true;save();finish();}
                bar.invalidate();
            }
            @Override public void onIsPlayingChanged(boolean playing){bar.invalidate();}
            @Override public void onTracksChanged(Tracks tracks){
                if(!tracksApplied&&!tracks.getGroups().isEmpty()&&player!=null){
                    tracksApplied=true;
                    try{log("المسارات: "+TrackChoice.applyDefaults(VodPlayerActivity.this,player,tracks));}catch(RuntimeException x){log("المسارات: تعذّر الاختيار "+x.getClass().getSimpleName());}
                }
                if(menu.getVisibility()==View.VISIBLE)menu.refresh();
            }
            @Override public void onCues(CueGroup group){cues=group.cues;bar.invalidate();}
            @Override public void onVideoSizeChanged(VideoSize size){
                int[] s=VideoFit.size(size.width,size.height,root.getWidth(),root.getHeight());
                surface.setLayoutParams(new FrameLayout.LayoutParams(s[0],s[1],Gravity.CENTER));
            }
            @Override public void onPlayerError(PlaybackException e){
                log("الفيلم: توقف ["+e.getErrorCodeName()+"]");
                if(!failedOnce){   // one more try from where it was
                    failedOnce=true;
                    final long at=player==null?startAt:Math.max(0,player.getCurrentPosition());
                    main.postDelayed(()->open(at),1500);
                    return;
                }
                error=show!=null?"تعذّر تشغيل الحلقة":"تعذّر تشغيل الفيلم";bar.show(true);
            }
        });
        open(Math.max(0,startAt));
        bar.show(false);
        main.postDelayed(saver,10_000);
    }
    private void open(long at){
        if(player==null)return;
        player.setMediaItem(new MediaItem.Builder().setUri(show!=null?account.seriesUrl(movie.id,ext):account.movieUrl(movie.id,ext)).build(),at);
        player.prepare();player.setPlayWhenReady(true);
        log((show!=null?"فتح حلقة":"فتح فيلم")+" · الصيغة "+ext+(at>0?" · من "+VodData.clock(at):""));
    }
    private final Runnable saver=new Runnable(){@Override public void run(){save();main.postDelayed(this,10_000);}};
    private void save(){
        if(player==null||movie==null)return;
        long dur=player.getDuration()>0?player.getDuration():knownDur;
        long pos=ended?dur:player.getCurrentPosition();
        if(pos<=0&&!ended)return;
        if(show!=null)VodData.saveEpisode(this,account,show,movie.id,pos,dur);
        else VodData.saveProgress(this,account,movie,pos,dur);
    }
    @Override protected void onStop(){
        if(busy){busy=false;Busy.leave();}
        main.removeCallbacksAndMessages(null);
        if(player!=null){
            save();
            try{player.release();}catch(Exception ignored){}
            LivePlayer.released(player);player=null;
        }
        super.onStop();
    }
    private void log(String what){PlayLog.add(this,-1,PlayLog.clean(what,account));}

    // MARK: the remote

    private long seekTarget=-1;
    private final Runnable applySeek=()->{if(player!=null&&seekTarget>=0){player.seekTo(seekTarget);}seekTarget=-1;bar.invalidate();};
    private void step(int direction,int repeat){
        if(player==null)return;
        long dur=player.getDuration()>0?player.getDuration():knownDur;
        long base=seekTarget>=0?seekTarget:player.getCurrentPosition();
        long size=repeat<4?10_000:repeat<12?30_000:60_000;
        long to=Math.max(0,base+direction*size);
        if(dur>0)to=Math.min(to,dur-2_000);
        seekTarget=to;
        main.removeCallbacks(applySeek);main.postDelayed(applySeek,repeat>0?350:150);
        bar.show(false);
    }
    @Override public boolean onKeyDown(int key,KeyEvent e){
        if(menu.getVisibility()==View.VISIBLE)return menu.key(key,e)||super.onKeyDown(key,e);
        switch(key){
            case KeyEvent.KEYCODE_DPAD_DOWN:case KeyEvent.KEYCODE_CAPTIONS:case KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK:
                if(e.getRepeatCount()==0)menu.open();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:   // the bar fills from left to right: right is forward
                step(1,e.getRepeatCount());return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:case KeyEvent.KEYCODE_MEDIA_REWIND:
                step(-1,e.getRepeatCount());return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:case KeyEvent.KEYCODE_ENTER:case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if(e.getRepeatCount()==0&&player!=null){
                    if(!error.isEmpty()){error="";failedOnce=false;open(Math.max(0,player.getCurrentPosition()));}
                    else{player.setPlayWhenReady(!player.getPlayWhenReady());if(!player.getPlayWhenReady())save();}
                    bar.show(false);
                }
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:if(player!=null){player.setPlayWhenReady(false);save();bar.show(false);}return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:if(player!=null){player.setPlayWhenReady(true);bar.show(false);}return true;
            case KeyEvent.KEYCODE_DPAD_UP:case KeyEvent.KEYCODE_INFO:bar.show(false);return true;
        }
        return super.onKeyDown(key,e);
    }

    /** The bar at the bottom: name, the time, how far it is (filling from left to right) and the keys. Shown for 4 s after a key, and while paused. */
    private final class Bar extends View {
        private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG),fade=new Paint();
        private final Paint name=Design.text(Design.TEXT,34,Design.bold()),time=Design.text(Design.TEXT,24,Design.regular()),
                hint=Design.text(Design.MUTED,19,Design.regular()),big=Design.text(Design.TEXT,30,Design.bold());
        private final RectF r=new RectF();
        private final A11y a11y=new A11y(this);
        @Override public android.view.accessibility.AccessibilityNodeProvider getAccessibilityNodeProvider(){return a11y.provider(null,null);}
        private long until;
        private float k=1;
        private final Runnable tick=new Runnable(){@Override public void run(){invalidate();if(visible())main.postDelayed(this,500);}};
        Bar(Context c){super(c);}
        @Override protected void onSizeChanged(int w,int h,int ow,int oh){
            k=w/1920f;
            fade.setShader(new android.graphics.LinearGradient(0,h*0.62f,0,h,0x00000000,0xE6000000,android.graphics.Shader.TileMode.CLAMP));
        }
        void show(boolean stay){until=stay?Long.MAX_VALUE:android.os.SystemClock.uptimeMillis()+4000;main.removeCallbacks(tick);main.post(tick);invalidate();}
        private boolean visible(){
            boolean paused=player!=null&&!player.getPlayWhenReady();
            return paused||!error.isEmpty()||seekTarget>=0||android.os.SystemClock.uptimeMillis()<until;
        }
        private final android.text.TextPaint cueText=new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint cueEdge=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF cr=new RectF();
        {cueText.setColor(0xFFFFFFFF);cueText.setTextSize(44);cueText.setTypeface(Design.bold());
         cueEdge.setStyle(Paint.Style.FILL);cueEdge.setColor(0x99000000);}
        /** The subtitles of now: text lines centred low on the screen (above the bar when it shows), pictures where the file puts them. */
        private void drawCues(Canvas c,boolean barUp){
            if(cues==null||cues.isEmpty())return;
            float bottom=barUp?830:1010;
            List<String> lines=new ArrayList<>();
            for(Cue q:cues){
                if(q.bitmap!=null){
                    float w=(q.size==Cue.DIMEN_UNSET?0.5f:q.size)*1920,h=q.bitmapHeight==Cue.DIMEN_UNSET?w*q.bitmap.getHeight()/Math.max(1f,q.bitmap.getWidth()):q.bitmapHeight*1080;
                    float x=(q.position==Cue.DIMEN_UNSET?0.5f:q.position)*1920,y=(q.line==Cue.DIMEN_UNSET||q.lineType!=Cue.LINE_TYPE_FRACTION?0.85f:q.line)*1080;
                    if(q.positionAnchor==Cue.ANCHOR_TYPE_MIDDLE)x-=w/2;else if(q.positionAnchor==Cue.ANCHOR_TYPE_END)x-=w;
                    if(q.lineAnchor==Cue.ANCHOR_TYPE_MIDDLE)y-=h/2;else if(q.lineAnchor==Cue.ANCHOR_TYPE_END)y-=h;
                    cr.set(x,y,x+w,y+h);c.drawBitmap(q.bitmap,null,cr,null);
                }else if(q.text!=null)for(String l:q.text.toString().split("\n"))if(!l.trim().isEmpty())lines.add(l.trim());
            }
            float lh=58,y=bottom-(lines.size()-1)*lh;
            for(String l:lines){
                String t=Design.fit(l,cueText,1700);float tw=cueText.measureText(t);
                cr.set(960-tw/2-16,y-44,960+tw/2+16,y+14);c.drawRoundRect(cr,8,8,cueEdge);
                Design.center(c,t,960,y,cueText);
                a11y.add(cr,"ترجمة: "+t,false,false);
                y+=lh;
            }
        }
        @Override protected void onDraw(Canvas c){
            a11y.begin(k);
            boolean buffering=player!=null&&player.getPlaybackState()==Player.STATE_BUFFERING;
            boolean up=visible();
            c.save();c.scale(k,k);drawCues(c,up);c.restore();
            if(!up){
                if(buffering){c.save();c.scale(k,k);Design.center(c,"جارٍ التحميل…",960,560,big);a11y.add(700,520,1220,580,"جارٍ التحميل",false,false);c.restore();}
                return;
            }
            c.drawRect(0,getHeight()*0.62f,getWidth(),getHeight(),fade);
            c.save();c.scale(k,k);
            if(!error.isEmpty()){Design.center(c,error+" · OK لإعادة المحاولة",960,540,big);a11y.add(500,500,1420,560,error,false,false);}
            else if(buffering){Design.center(c,"جارٍ التحميل…",960,560,big);}
            long dur=player==null?knownDur:(player.getDuration()>0?player.getDuration():knownDur);
            long pos=seekTarget>=0?seekTarget:player==null?0:player.getCurrentPosition();
            Design.right(c,Design.fit(movie.name,name,1200),1848,880,name);
            a11y.add(600,840,1848,890,movie.name,false,false);
            boolean paused=player!=null&&!player.getPlayWhenReady();
            if(paused)Design.left(c,"❚❚ متوقف",72,880,time);
            float f=dur>0?Math.max(0,Math.min(1,pos/(float)dur)):0;
            r.set(72,925,1848,933);Design.fill(c,r,4,p,0x40FFFFFF);
            r.set(72,925,72+1776*f,933);Design.fill(c,r,4,p,Design.GOLD);
            p.setColor(0xFFFFFFFF);c.drawCircle(72+1776*f,929,11,p);
            String now=VodData.clock(pos),all=dur>0?VodData.clock(dur):"";
            Design.left(c,now,72,975,time);
            if(!all.isEmpty())Design.right(c,all,1848,975,time);
            a11y.add(72,915,1848,985,now+(all.isEmpty()?"":" من "+all)+(paused?" · متوقف":""),false,false);
            Design.center(c,"▶ تقديم · ◀ رجوع · OK إيقاف/تشغيل · ▼ الصوت والترجمة · BACK خروج",960,1040,hint);
            c.restore();
        }
    }

    /** The sound and subtitles menu (▼): every sound track and subtitle of the file by language, ✓ on the ones playing. */
    private final class TrackMenu extends View {
        private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint head=Design.text(Design.TEXT,34,Design.bold()),section=Design.text(Design.GOLD,24,Design.bold()),
                item=Design.text(Design.TEXT,26,Design.regular()),note=Design.text(Design.MUTED,21,Design.regular()),hint=Design.text(Design.MUTED,18,Design.regular());
        private final RectF r=new RectF(),g=new RectF();
        private final A11y a11y=new A11y(this);
        @Override public android.view.accessibility.AccessibilityNodeProvider getAccessibilityNodeProvider(){return a11y.provider(null,null);}
        private List<TrackChoice.Option> audio=new ArrayList<>(),text=new ArrayList<>();
        private int sel;   // over the audio options, then "no subtitles", then the subtitles
        private float k=1;
        TrackMenu(Context c){super(c);}
        @Override protected void onSizeChanged(int w,int h,int ow,int oh){k=w/1920f;}
        void open(){refresh();sel=0;for(int i=0;i<audio.size();i++)if(audio.get(i).selected)sel=i;setVisibility(View.VISIBLE);invalidate();}
        void refresh(){
            Tracks t=player==null?null:player.getCurrentTracks();
            audio=TrackChoice.options(t,androidx.media3.common.C.TRACK_TYPE_AUDIO);
            text=TrackChoice.options(t,androidx.media3.common.C.TRACK_TYPE_TEXT);
            if(sel>=count())sel=Math.max(0,count()-1);
            invalidate();
        }
        private int count(){return audio.size()+1+text.size();}
        private boolean textOff(){for(TrackChoice.Option o:text)if(o.selected)return false;return true;}
        boolean key(int key,KeyEvent e){
            switch(key){
                case KeyEvent.KEYCODE_BACK:case KeyEvent.KEYCODE_DPAD_RIGHT:case KeyEvent.KEYCODE_DPAD_LEFT:
                    setVisibility(View.GONE);bar.show(false);return true;
                case KeyEvent.KEYCODE_DPAD_UP:if(sel>0)sel--;invalidate();return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:if(sel<count()-1)sel++;invalidate();return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:case KeyEvent.KEYCODE_ENTER:
                    if(e.getRepeatCount()>0||player==null)return true;
                    if(sel<audio.size())TrackChoice.pick(getContext(),player,androidx.media3.common.C.TRACK_TYPE_AUDIO,audio.get(sel));
                    else if(sel==audio.size())TrackChoice.pick(getContext(),player,androidx.media3.common.C.TRACK_TYPE_TEXT,null);
                    else TrackChoice.pick(getContext(),player,androidx.media3.common.C.TRACK_TYPE_TEXT,text.get(sel-audio.size()-1));
                    main.postDelayed(this::refresh,400);
                    return true;
            }
            return true;
        }
        private float row(Canvas c,float y,String label,boolean selected,boolean focus,String a11yLabel){
            r.set(1290,y,1830,y+64);
            if(focus){Design.grown(r,g);Design.fill(c,g,16,p,0x29E9C46A);Design.focusRing(c,g,16,1,p);}
            Design.right(c,label,1790,y+43,item);
            if(selected)Design.left(c,"✓",1320,y+44,section);
            a11y.add(r,a11yLabel+(selected?" — مختار":""),focus,true);
            return y+72;
        }
        @Override protected void onDraw(Canvas c){
            a11y.begin(k);
            c.drawColor(0x66000000);
            c.save();c.scale(k,k);
            r.set(1260,60,1860,1020);Design.card(c,r,28,1,p,0xF00E1124);
            Design.right(c,"الصوت والترجمة",1820,128,head);
            a11y.add(1270,90,1850,140,"الصوت والترجمة",false,false);
            float y=170;
            Design.right(c,"الصوت",1820,y+28,section);y+=46;
            for(int i=0;i<audio.size();i++)y=row(c,y,audio.get(i).label,audio.get(i).selected,sel==i,"الصوت: "+audio.get(i).label);
            if(audio.size()<=1){String m=audio.isEmpty()?"لا يوجد مسار صوت في هذا الملف":"مسار صوت واحد فقط · لا توجد خيارات أخرى";Design.right(c,m,1810,y+30,note);a11y.add(1290,y,1830,y+40,m,false,false);y+=50;}
            y+=20;
            Design.right(c,"الترجمة",1820,y+28,section);y+=46;
            y=row(c,y,"بدون ترجمة",textOff(),sel==audio.size(),"الترجمة: بدون ترجمة");
            for(int i=0;i<text.size();i++)y=row(c,y,text.get(i).label,text.get(i).selected,sel==audio.size()+1+i,"الترجمة: "+text.get(i).label);
            if(text.isEmpty()){String m="لا توجد ترجمات في هذا الملف";Design.right(c,m,1810,y+30,note);a11y.add(1290,y,1830,y+40,m,false,false);}
            Design.center(c,"▲▼ تنقل · OK اختيار · BACK إغلاق",1560,995,hint);
            c.restore();
        }
    }
}
