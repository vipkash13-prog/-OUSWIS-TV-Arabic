package io.ouswis.tv.standalone;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.SurfaceView;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/**
 * Live TV browser (SPEC section 4, live.png): sections on the right, channels in the middle, a muted preview and the
 * programme guide on the left. OK plays full screen in XtreamActivity (its reconnect logic is unchanged), a long press
 * on OK adds or removes a favourite. Only one preview player exists at a time and it is released when the screen
 * leaves the foreground.
 */
public final class LiveActivity extends Activity {
    private LiveData data;
    private LiveView view;
    private SurfaceView preview;
    private ExoPlayer exo;
    private RectF previewBox=new RectF();
    private int previewAttempt;
    private TextView previewLabel;
    private Cover cover;
    /** Dark #0B0D1A with the channel's logo (or its name) in the middle: what the preview shows while it loads. */
    private static final class Cover extends View {
        private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        private final RectF dst=new RectF();
        private LiveData.Channel channel;
        Cover(Context c){super(c);}
        void set(LiveData.Channel c){channel=c;invalidate();}
        @Override protected void onDraw(Canvas c){
            c.drawColor(0xFF0B0D1A);
            if(channel==null)return;
            float w=getWidth(),h=getHeight();
            Bitmap logo=channel.icon==null||channel.icon.isEmpty()?null:ImageCache.get(channel.icon,this::postInvalidate);
            if(logo!=null&&logo.getWidth()>0&&logo.getHeight()>0){
                float sc=Math.min(w*0.4f/logo.getWidth(),h*0.4f/logo.getHeight());
                float lw=logo.getWidth()*sc,lh=logo.getHeight()*sc;
                dst.set(w/2-lw/2,h/2-lh/2,w/2+lw/2,h/2+lh/2);c.drawBitmap(logo,null,dst,p);
            }else{
                Paint t=Design.text(Design.TEXT,Math.max(14,h*0.1f),Design.bold());
                Design.center(c,Design.fit(RedesignHomeView.shortName(channel.name),t,w*0.8f),w/2,h/2+t.getTextSize()*0.35f,t);
            }
        }
    }
    private final Handler main=new Handler(Looper.getMainLooper());
    private boolean foreground;
    /** Set when a match without a confirmed channel opened this screen: the sports section, on its first beIN or SSC channel. */
    static final String EXTRA_MATCH_KEY="matchKey";
    private String matchKey="";
    private boolean pendingFocus;
    private int previewGeneration;

    @Override public void finish(){super.finish();overridePendingTransition(0,0);}   // and goes at once
    @Override protected void onCreate(Bundle b){
        super.onCreate(b);
        overridePendingTransition(0,0);   // opens at once: no fade or slide over the screen before
        Ui.init(this);
        getWindow().getDecorView().setSystemUiVisibility(5894);
        XtreamAccount account=XtreamAccount.read(this);
        if(account==null){
            // No login yet: the existing screen asks for it, then comes back here through the home screen.
            startActivity(new Intent(this,XtreamActivity.class));finish();return;
        }
        String mk=getIntent()==null?null:getIntent().getStringExtra(EXTRA_MATCH_KEY);
        matchKey=mk==null?"":mk;pendingFocus=!matchKey.isEmpty();
        data=new LiveData(this,account);
        FrameLayout root=new FrameLayout(this);
        view=new LiveView(this);
        root.addView(view,new FrameLayout.LayoutParams(-1,-1));
        preview=new SurfaceView(this);
        preview.setVisibility(View.GONE);
        root.addView(preview,new FrameLayout.LayoutParams(10,10));
        cover=new Cover(this);cover.setVisibility(View.GONE);
        root.addView(cover,new FrameLayout.LayoutParams(10,10));   // over the picture's surface until the first frame: no green or black square while a channel loads
        previewLabel=new TextView(this);
        previewLabel.setTextColor(Color.WHITE);previewLabel.setGravity(Gravity.CENTER_VERTICAL|Gravity.RIGHT);
        previewLabel.setVisibility(View.GONE);
        root.addView(previewLabel,new FrameLayout.LayoutParams(10,10));
        setContentView(root);
        view.requestFocus();
        data.loadCategories(()->view.dataChanged());
    }

    // MARK: preview

    /** Starts the muted preview of a channel after the focus has rested on it (the caller waits 800 ms). */
    void startPreview(LiveData.Channel c,RectF rect,String caption){
        stopPreview();
        if(!foreground||c==null)return;
        XtreamAccount a=XtreamAccount.read(this);if(a==null)return;
        final int generation=++previewGeneration;
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(Math.round(rect.width()),Math.round(rect.height()));
        lp.leftMargin=Math.round(rect.left);lp.topMargin=Math.round(rect.top);
        preview.setLayoutParams(lp);
        FrameLayout.LayoutParams cl=new FrameLayout.LayoutParams(Math.round(rect.width()),Math.round(rect.height()));
        cl.leftMargin=Math.round(rect.left);cl.topMargin=Math.round(rect.top);
        cover.setLayoutParams(cl);cover.set(c);cover.setVisibility(View.VISIBLE);
        FrameLayout.LayoutParams ll=new FrameLayout.LayoutParams(Math.round(rect.width()),Math.round(rect.height()*0.13f),Gravity.TOP|Gravity.LEFT);
        ll.leftMargin=Math.round(rect.left);ll.topMargin=Math.round(rect.bottom-rect.height()*0.13f);
        previewLabel.setLayoutParams(ll);previewLabel.setText(caption);
        previewLabel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,rect.height()*0.05f);
        previewLabel.setBackgroundColor(0x99000000);previewLabel.setPadding(24,0,24,0);
        // The surface is visible at once (a GONE one has no surface to render on); the label comes with the first frame.
        preview.setVisibility(View.VISIBLE);
        previewBox.set(rect);previewAttempt=0;
        previewChannel=c;
        if(exo==null){
            exo=LivePlayer.create(this);
            exo.setVideoSurfaceView(preview);
            exo.setVolume(0f);     // sound only after OK
            exo.addListener(new Player.Listener(){
                @Override public void onRenderedFirstFrame(){
                    cover.setVisibility(View.GONE);previewLabel.setVisibility(View.VISIBLE);view.previewShown(true);
                }
                @Override public void onVideoSizeChanged(VideoSize size){
                    int[] f=VideoFit.size(size.width,size.height,Math.round(previewBox.width()),Math.round(previewBox.height()));
                    FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(f[0],f[1]);
                    p.leftMargin=Math.round(previewBox.left+(previewBox.width()-f[0])/2f);p.topMargin=Math.round(previewBox.top+(previewBox.height()-f[1])/2f);
                    preview.setLayoutParams(p);
                }
                @Override public void onPlayerError(PlaybackException e){
                    // the other format once, then give up quietly: the preview is a nicety
                    if(previewChannel!=null&&previewAttempt==0){previewAttempt=1;playPreview(previewChannel,generation);}
                    else stopPreview();
                }
            });
        }
        playPreview(c,generation);
    }
    private LiveData.Channel previewChannel;
    private void playPreview(LiveData.Channel c,int generation){
        XtreamAccount a=XtreamAccount.read(this);if(a==null||exo==null)return;
        exo.setMediaItem(LivePlayer.item(a,c,LivePlayer.extension(c,previewAttempt)));
        exo.setVolume(0f);
        exo.prepare();exo.setPlayWhenReady(true);
    }
    void stopPreview(){
        previewGeneration++;
        if(exo!=null){try{exo.stop();exo.clearMediaItems();}catch(Exception ignored){}}
        if(preview!=null)preview.setVisibility(View.GONE);
        if(cover!=null)cover.setVisibility(View.GONE);
        if(previewLabel!=null)previewLabel.setVisibility(View.GONE);
        if(view!=null)view.previewShown(false);
    }
    /** One player at a time, and none once the screen leaves the front. */
    private void releasePreview(){
        stopPreview();
        if(exo!=null){try{exo.release();}catch(Exception ignored){}exo=null;}
    }

    void play(LiveData.Channel c,String section){
        releasePreview();     // the full-screen player is the only one now
        if(!matchKey.isEmpty()){MatchChannels.pickMatch(this,matchKey,c);android.widget.Toast.makeText(this,"حُفظت "+c.name+" لهذه المباراة",android.widget.Toast.LENGTH_SHORT).show();}
        startActivity(PlayerActivity.intent(this,c,section));
    }
    void askSearch(String current,java.util.function.Consumer<String> done){
        EditText input=new EditText(this);input.setSingleLine(true);input.setText(current);
        input.setHint("بحث في القنوات");
        new AlertDialog.Builder(this).setTitle("بحث").setView(input)
                .setPositiveButton("بحث",(d,w)->done.accept(input.getText().toString()))
                .setNegativeButton("مسح",(d,w)->done.accept("")).show();
    }

    @Override protected void onResume(){super.onResume();foreground=true;if(view!=null){view.dataChanged();if(data!=null)data.loadCategories(()->view.dataChanged());}}   // reload: Settings may have reordered or hidden sections
    @Override protected void onPause(){foreground=false;releasePreview();super.onPause();}
    @Override protected void onDestroy(){releasePreview();if(data!=null)data.shutdown();super.onDestroy();}

    // MARK: the drawn screen

    private final class LiveView extends View {
        private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint title,sub,muted,name,small,num,chip,badgeText;
        private final RectF r=new RectF(),g=new RectF(),bm=new RectF();
        private final A11y a11y=new A11y(this);
        @Override public android.view.accessibility.AccessibilityNodeProvider getAccessibilityNodeProvider(){return a11y.provider(null,null);}
        private Bitmap backdrop;
        private float k;
        private int cat,sel,top,catTop,headerFocus=-1;   // headerFocus: -1 none, 0 search, 1 refresh
        private int column=1;   // 0 sections, 1 channels, 2 preview: left/right move between them, up/down inside one
        private String search="";
        private boolean previewOn;
        private final Runnable startPreviewLater=()->{
            List<LiveData.Channel> list=shown();
            if(sel<0||sel>=list.size())return;
            LiveData.Channel c=list.get(sel);
            LiveData.Program now=LiveData.current(data.guide(c.id),System.currentTimeMillis());
            startPreview(c,previewRect(),c.name+" · معاينة — اضغط OK لملء الشاشة");
        };
        LiveView(Context context){
            super(context);setFocusable(true);setFocusableInTouchMode(true);
            title=Design.text(Design.TEXT,40,Design.bold());sub=Design.text(Design.MUTED,20,Design.regular());
            muted=Design.text(Design.MUTED,16,Design.regular());name=Design.text(Design.TEXT,23,Design.bold());
            small=Design.text(Design.TEXT,19,Design.regular());num=Design.text(Design.MUTED,19,Design.regular());
            chip=Design.text(Design.TEXT,19,Design.regular());badgeText=Design.text(0xFFFFFFFF,15,Design.bold());
        }
        void previewShown(boolean on){previewOn=on;invalidate();}
        private boolean picked;
        private java.util.Set<String> hiddenIds=new java.util.HashSet<>();
        void dataChanged(){
            hiddenIds=SectionOrder.hidden(getContext());
            if(cat>=sections().size())cat=Math.max(0,sections().size()-1);
            if(!picked&&data!=null&&!data.categories.isEmpty()){
                picked=true;
                // open on favourites or recents when there are some, otherwise on the first real section
                cat=!data.cached(LiveData.FAVORITES).isEmpty()?0:!data.cached(LiveData.RECENT).isEmpty()?1:2;
                if(pendingFocus){int s=sportsSection();if(s>=0)cat=s;else pendingFocus=false;column=1;}
                sel=0;top=0;
            }
            invalidate();ensureLoaded();
        }

        private List<LiveData.Category> sections(){
            List<LiveData.Category> out=new ArrayList<>();
            LiveData.Category fav=new LiveData.Category(LiveData.FAVORITES,"★ المفضلة");fav.count=data.cached(LiveData.FAVORITES).size();
            LiveData.Category recent=new LiveData.Category(LiveData.RECENT,"↺ الأخيرة");recent.count=data.cached(LiveData.RECENT).size();
            out.add(fav);out.add(recent);
            synchronized(data.categories){for(LiveData.Category cat:data.categories)if(!hiddenIds.contains(cat.id))out.add(cat);}
            return out;
        }
        /** The index (in {@link #sections}) of the sports section: one chosen as sports when the sports files were prepared, else one named like it; -1 if none. */
        private int sportsSection(){
            List<LiveData.Category> s=sections();
            java.util.Set<String> ids=MatchChannels.sportCategoryIds(getContext());
            for(int i=2;i<s.size();i++)if(ids.contains(s.get(i).id))return i;
            for(int i=2;i<s.size();i++)if(MatchChannels.sportRank(s.get(i).name)<2)return i;
            return -1;
        }
        private List<LiveData.Channel> shown(){
            List<LiveData.Category> s=sections();
            if(cat<0||cat>=s.size())return new ArrayList<>();
            List<LiveData.Channel> all=data.cached(s.get(cat).id);
            List<LiveData.Channel> out=new ArrayList<>();
            if(all==null)return out;
            String q=XtreamCatalog.normalize(search);
            for(LiveData.Channel c:all)if(q.isEmpty()||XtreamCatalog.normalize(c.name).contains(q))out.add(c);
            return out;
        }
        private void ensureLoaded(){
            List<LiveData.Category> s=sections();
            if(cat>=0&&cat<s.size())data.loadChannels(s.get(cat).id,()->{
                if(pendingFocus&&cat==sportsSection()&&!shown().isEmpty()){pendingFocus=false;sel=MatchChannels.firstSports(shown());top=Math.max(0,sel-2);}   // the first beIN or SSC channel
                invalidate();wantGuide();schedulePreview();});
        }
        /** Programme lines under visible rows and the guide card of the selected channel. */
        private void wantGuide(){
            List<LiveData.Channel> list=shown();
            for(int i=top;i<Math.min(list.size(),top+10);i++)data.loadGuide(list.get(i).id,()->invalidate());
            if(sel>=0&&sel<list.size())data.loadGuide(list.get(sel).id,()->invalidate());
        }
        private void schedulePreview(){
            main.removeCallbacks(startPreviewLater);
            stopPreview();
            if(headerFocus<0)main.postDelayed(startPreviewLater,800);
        }
        private RectF previewRect(){
            float x=getLocationOnScreenX();
            return new RectF(72*k,163*k,880*k,582*k);
        }
        private float getLocationOnScreenX(){return 0;}

        @Override protected void onSizeChanged(int w,int h,int ow,int oh){
            k=w/1920f;
            try{backdrop=Bitmap.createBitmap(Math.max(1,w/2),Math.max(1,h/2),Bitmap.Config.RGB_565);
            Canvas c=new Canvas(backdrop);c.scale(0.5f,0.5f);Wallpaper.draw(getContext(),c,w,h,0.6f,0x0B0D1A);}catch(Throwable noRoom){backdrop=null;AppLog.w("backdrop skipped: "+noRoom);}   // the inner wallpaper under #0B0D1A at 60 % (was black at 60 %) dark layer
        }
        private String clock(){
            java.text.SimpleDateFormat f=new java.text.SimpleDateFormat("h:mm a",Design.AR);
            f.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Riyadh"));return f.format(new java.util.Date());
        }
        private String hm(long at){
            java.text.SimpleDateFormat f=new java.text.SimpleDateFormat("h:mm a",Design.AR);
            f.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Riyadh"));return f.format(new java.util.Date(at));
        }

        @Override protected void onDraw(Canvas c){
            int w=getWidth(),h=getHeight();
            if(backdrop!=null)c.drawBitmap(backdrop,null,new RectF(0,0,w,h),null);
            a11y.begin(k);
            c.save();c.scale(k,k);
            float kk=1;   // everything below is in 1920x1080 reference units
            List<LiveData.Category> secs=sections();
            List<LiveData.Channel> list=shown();
            // header
            title.setTextSize(40);Design.right(c,"▣ البث المباشر",1848,76,title);
            String total=data.failed?"تعذّر تحميل الأقسام":"Xtream"+(data.expiry.isEmpty()?"":" · ينتهي "+data.expiry);
            Design.right(c,total,1848,113,sub);
            a11y.add(1100,40,1848,126,"البث المباشر · "+total,false,false);
            String[] chips={"بحث","↻ تحديث القائمة",clock()};
            float[][] cr={{392,490},{202,380},{72,190}};
            for(int i=0;i<3;i++){
                r.set(cr[i][0],64,cr[i][1],112);
                Design.card(c,r,24,1,p,Design.SURFACE);
                Design.center(c,chips[i],(cr[i][0]+cr[i][1])/2,95,chip);
                a11y.add(r,chips[i],i<2&&headerFocus==i,i<2);
                if(i<2&&headerFocus==i){Design.grown(r,g);Design.focusRing(c,g,24,1,p);}
            }
            // sections column
            r.set(1549,163,1847,981);Design.card(c,r,26,1,p,Design.SURFACE);
            int visibleCats=12;
            if(cat<catTop)catTop=cat;if(cat>=catTop+visibleCats)catTop=cat-visibleCats+1;
            for(int i=catTop;i<Math.min(secs.size(),catTop+visibleCats);i++){
                float y=205+(i-catTop)*62;
                LiveData.Category s=secs.get(i);
                if(i==cat){
                    r.set(1563,y-30,1833,y+26);Design.fill(c,r,16,p,0x29E9C46A);
                    r.set(1826,y-26,1833,y+22);Design.fill(c,r,4,p,Design.GOLD);
                }
                boolean secFocus=i==cat&&column==0&&headerFocus<0;
                if(secFocus){r.set(1563,y-30,1833,y+26);Design.grown(r,g);Design.focusRing(c,g,16,1,p);}
                a11y.add(1563,y-30,1833,y+26,"قسم "+s.name+(s.count>=0?" ("+s.count+")":"")+(i==cat?" — محدد":""),secFocus,true);
                small.setColor(i==cat?Design.TEXT:Design.MUTED);
                Design.right(c,Design.fit(s.name,small,190),1812,y+7,small);
                if(s.count>=0){Design.left(c,String.valueOf(s.count),1583,y+2,muted);}
            }
            // channels column
            r.set(905,163,1523,981);Design.card(c,r,26,1,p,Design.SURFACE);
            if(list.isEmpty()){
                List<LiveData.Category> ss=secs;
                boolean loading=cat>=2&&cat<ss.size()&&data.cached(ss.get(cat).id)==null;
                Design.center(c,loading?"جارٍ تحميل القنوات…":cat==0?"لا توجد قنوات مفضلة: اضغط OK مطوّلاً على قناة":cat==1?"لا توجد قنوات حديثة":"لا توجد قنوات",1214,560,small);
            }
            int rows=9;
            if(sel<top)top=sel;if(sel>=top+rows)top=sel-rows+1;if(top<0)top=0;
            long now=System.currentTimeMillis();
            for(int i=top;i<Math.min(list.size(),top+rows);i++){
                LiveData.Channel ch=list.get(i);
                float y=170+(i-top)*87;
                r.set(917,y,1511,y+80);
                boolean focused=i==sel&&headerFocus<0&&column==1;
                if(focused){Design.grown(r,g);Design.fill(c,g,18,p,0x1FE9C46A);}
                else Design.fill(c,r,18,p,i==sel?0x14FFFFFF:0x08FFFFFF);
                num.setTextSize(19);Design.right(c,ch.num.isEmpty()?String.valueOf(i+1):ch.num,1493,y+47,num);
                r.set(1367,y+14,1439,y+66);Design.fill(c,r,10,p,0x14FFFFFF);
                Bitmap logo=ImageCache.get(ch.icon,()->invalidate());
                if(logo!=null){
                    float s=Math.min(60f/logo.getWidth(),40f/logo.getHeight());
                    float lw=logo.getWidth()*s,lh=logo.getHeight()*s;
                    bm.set(1403-lw/2,y+40-lh/2,1403+lw/2,y+40+lh/2);p.setColor(0xFFFFFFFF);c.drawBitmap(logo,null,bm,p);
                }else{
                    String t=ch.name.length()>6?ch.name.substring(0,6):ch.name;
                    badgeText.setTextSize(15);badgeText.setColor(Design.GOLD);Design.center(c,t.trim(),1403,y+46,badgeText);
                }
                Design.right(c,Design.fit(ch.name,name,400),1351,y+36,name);
                LiveData.Program nowProg=LiveData.current(data.guide(ch.id),now);
                a11y.add(917,y,1511,y+80,(ch.num.isEmpty()?String.valueOf(i+1):ch.num)+" · "+ch.name+(nowProg!=null?" · "+nowProg.shown:"")+(data.isFavorite(ch.id)?" ★":""),focused,true);
                LiveData.Program prog=LiveData.current(data.guide(ch.id),now);
                muted.setTextSize(16);
                if(prog!=null){
                    Design.right(c,Design.fit(prog.shown,muted,380),1351,y+58,muted);
                    float f=Math.max(0,Math.min(1,(now-prog.start)/(float)Math.max(1,prog.end-prog.start)));
                    r.set(970,y+68,1351,y+72);Design.fill(c,r,2,p,0x1FFFFFFF);
                    r.set(1351-381*f,y+68,1351,y+72);Design.fill(c,r,2,p,Design.GOLD);
                }
                if(data.isFavorite(ch.id)){chip.setColor(Design.GOLD);Design.left(c,"★",935,y+46,chip);chip.setColor(Design.TEXT);}
                if(focused)Design.focusRing(c,g,18,1,p);
            }
            // preview
            r.set(72,163,880,582);
            g.set(r);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFFFFFFF);
            p.setShader(null);p.setColor(0xFF0B0D1A);   // the preview's empty place: #0B0D1A, never green
            c.drawRoundRect(r,26,26,p);
            if(!previewOn){
                if(sel>=0&&sel<list.size()){
                    LiveData.Channel ch=list.get(sel);
                    Bitmap logo=ImageCache.get(ch.icon,()->invalidate());
                    if(logo!=null){
                        float s=Math.min(220f/logo.getWidth(),130f/logo.getHeight());
                        float lw=logo.getWidth()*s,lh=logo.getHeight()*s;
                        bm.set(476-lw/2,330-lh/2,476+lw/2,330+lh/2);p.setColor(0xFFFFFFFF);c.drawBitmap(logo,null,bm,p);
                    }
                    Design.center(c,ch.name,476,450,name);
                    a11y.add(72,163,880,582,"معاينة "+ch.name,column==2&&headerFocus<0,true);
                    Design.center(c,"اضغط OK لملء الشاشة",476,490,muted);
                }
            }
            if(previewOn&&sel>=0&&sel<list.size())a11y.add(72,163,880,582,"معاينة "+list.get(sel).name,column==2&&headerFocus<0,true);
            if(column==2&&headerFocus<0){r.set(72,163,880,582);Design.grown(r,g);Design.focusRing(c,g,26,1,p);}
            // guide card
            r.set(72,603,880,981);Design.card(c,r,26,1,p,Design.SURFACE);
            if(sel>=0&&sel<list.size()){
                LiveData.Channel ch=list.get(sel);
                Design.right(c,Design.fit("دليل البرامج — "+ch.name,muted,700),851,645,muted);
                List<LiveData.Program> guide=data.guide(ch.id);
                if(guide==null||guide.isEmpty()){
                    Design.right(c,guide==null?"جارٍ تحميل الدليل…":"لا يوجد دليل برامج لهذه القناة",851,700,small);
                }else for(int i=0;i<Math.min(4,guide.size());i++){
                    LiveData.Program pr=guide.get(i);boolean cur=pr.start<=now&&now<pr.end;
                    float y=686+i*47;
                    chip.setColor(cur?Design.GOLD:Design.MUTED);chip.setTextSize(21);
                    Design.right(c,hm(pr.start),851,y,chip);
                    name.setColor(cur?Design.TEXT:Design.MUTED);name.setTextSize(21);
                    Design.right(c,Design.fit(pr.shown,name,560),723,y,name);
                    a11y.add(101,y-28,851,y+8,hm(pr.start)+" "+pr.shown,false,false);
                    name.setColor(Design.TEXT);name.setTextSize(23);chip.setColor(Design.TEXT);chip.setTextSize(19);
                    if(i<3){r.set(101,y+14,851,y+15);Design.fill(c,r,0,p,0x14FFFFFF);}
                }
            }
            // footer
            Design.right(c,"OK تشغيل · ▲▼ داخل القائمة · ◀▶ بين الأقسام والقنوات والمعاينة · ضغطة مطوّلة = مفضلة · CH+/CH-",1848,1054,muted);
            Design.left(c,"البث المباشر",72,1054,muted);
            c.restore();
        }

        @Override public boolean onKeyDown(int key,KeyEvent e){
            List<LiveData.Channel> list=shown();
            int secs=sections().size();
            switch(key){
                case KeyEvent.KEYCODE_CHANNEL_DOWN:case KeyEvent.KEYCODE_CHANNEL_UP:{
                    int d=key==KeyEvent.KEYCODE_CHANNEL_DOWN?1:-1;   // the channel keys always move through the channels
                    if(headerFocus>=0)headerFocus=-1;
                    if(sel+d>=0&&sel+d<list.size()){sel+=d;wantGuide();schedulePreview();}
                    invalidate();return true;}
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if(headerFocus>=0){headerFocus=-1;schedulePreview();}
                    else if(column==0){if(cat<secs-1){cat++;sel=0;top=0;switched();}}
                    else if(column==1){if(sel<list.size()-1){sel++;wantGuide();schedulePreview();}}
                    invalidate();return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    if(headerFocus>=0)return true;
                    if(column==0){
                        if(cat>0){cat--;sel=0;top=0;switched();}
                        else{headerFocus=0;main.removeCallbacks(startPreviewLater);stopPreview();}
                    }else if(column==1){
                        if(sel>0){sel--;wantGuide();schedulePreview();}
                        else{headerFocus=0;main.removeCallbacks(startPreviewLater);stopPreview();}
                    }
                    invalidate();return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:   // RTL: the sections are on the right, then the channels, then the preview
                    if(headerFocus>=0){headerFocus=0;invalidate();return true;}
                    if(column>0){column--;invalidate();}
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if(headerFocus>=0){headerFocus=1;invalidate();return true;}
                    if(column<2){column++;invalidate();}
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:case KeyEvent.KEYCODE_ENTER:
                    if(e.getRepeatCount()==0)e.startTracking();
                    return true;
            }
            return super.onKeyDown(key,e);
        }
        private void switched(){
            main.removeCallbacks(startPreviewLater);stopPreview();ensureLoaded();invalidate();wantGuide();schedulePreview();
        }
        @Override public boolean onKeyLongPress(int key,KeyEvent e){
            if(key!=KeyEvent.KEYCODE_DPAD_CENTER&&key!=KeyEvent.KEYCODE_ENTER)return super.onKeyLongPress(key,e);
            List<LiveData.Channel> list=shown();
            if(headerFocus<0&&column!=0&&sel>=0&&sel<list.size()){
                boolean now=data.toggleFavorite(list.get(sel));
                android.widget.Toast.makeText(getContext(),now?"أُضيفت إلى المفضلة":"أُزيلت من المفضلة",android.widget.Toast.LENGTH_SHORT).show();
                invalidate();
            }
            return true;
        }
        @Override public boolean onKeyUp(int key,KeyEvent e){
            if((key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER)&&e.isTracking()&&!e.isCanceled()){
                if(headerFocus==0){
                    askSearch(search,q->{search=q;sel=0;top=0;invalidate();schedulePreview();});
                }else if(headerFocus==1){
                    data.refresh(()->{invalidate();ensureLoaded();});
                }else if(column==0){
                    column=1;invalidate();   // OK on a section: go to its channels
                }else{
                    List<LiveData.Channel> list=shown();
                    if(sel>=0&&sel<list.size()){List<LiveData.Category> ss=sections();play(list.get(sel),cat>=0&&cat<ss.size()?ss.get(cat).id:"");}
                }
                return true;
            }
            return super.onKeyUp(key,e);
        }
    }
}
