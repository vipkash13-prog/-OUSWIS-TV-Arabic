package io.ouswis.tv.standalone;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.content.pm.ActivityInfo;
import android.graphics.BitmapShader;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * All Apps: every app the TV can start, in a grid the remote walks (right to left, like the rest). It draws on the
 * inner wallpaper under a dark layer, and it replaces the old drawer of the classic home view, which came up empty.
 */
public class AllAppsActivity extends Activity {
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private AppsView view;
    private final List<String[]> apps=new ArrayList<>();   // package, label
    private volatile boolean loaded;
    // Kept for the life of the process: the list after its first load, and the cards. Only an app being installed or removed changes them.
    private static List<String[]> cache;
    private static final Map<String,Bitmap> TILES=new java.util.LinkedHashMap<String,Bitmap>(48,0.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<String,Bitmap> e){
            if(size()>40){ASKED.remove(e.getKey());return true;}   // about 10 MB at most: the ones scrolled far away are made again
            return false;
        }
    };
    private static final java.util.Set<String> ASKED=new java.util.HashSet<>();
    private static boolean watching;
    private static volatile Runnable onPackages;   // the open screen, told to read the list again
    private static void watchPackages(Context c){
        if(watching)return;
        watching=true;
        android.content.IntentFilter f=new android.content.IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);f.addAction(Intent.ACTION_PACKAGE_REMOVED);f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addDataScheme("package");
        c.getApplicationContext().registerReceiver(new android.content.BroadcastReceiver(){
            @Override public void onReceive(Context ctx,Intent i){
                final String pkg=i.getData()==null?null:i.getData().getSchemeSpecificPart();
                new Handler(Looper.getMainLooper()).post(()->{
                    cache=null;
                    if(pkg!=null){TILES.remove(pkg);ASKED.remove(pkg);}else{TILES.clear();ASKED.clear();}
                    Runnable r=onPackages;if(r!=null)r.run();
                });
            }
        },f);
    }

    @Override public void finish(){super.finish();overridePendingTransition(0,0);}   // and goes at once
    @Override protected void onCreate(Bundle b){
        super.onCreate(b);
        overridePendingTransition(0,0);   // opens at once: no fade or slide over the screen before
        Ui.init(this);
        getWindow().getDecorView().setSystemUiVisibility(5894);
        watchPackages(this);
        view=new AppsView(this);
        if(cache!=null){apps.addAll(cache);loaded=true;}   // the list is already known: no "loading" and no grey squares
        FrameLayout root=new FrameLayout(this);
        root.addView(view,new FrameLayout.LayoutParams(-1,-1));
        setContentView(root);
        view.requestFocus();
    }
    @Override protected void onResume(){super.onResume();onPackages=()->{if(!isFinishing())load();};if(cache==null)load();}
    @Override protected void onPause(){onPackages=null;super.onPause();}
    @Override protected void onDestroy(){io.shutdownNow();super.onDestroy();}

    /** The list, read off the UI thread: apps with a launcher, sorted by name. */
    private static List<String[]> readList(Context ctx){
        List<String[]> list=new ArrayList<>();
        try{
            for(String[] a:HomeApps.launchable(ctx)){
                if(a[0].equals(ctx.getPackageName())||HomeApps.isHidden(a[0]))continue;
                list.add(new String[]{a[0],a[1]==null?"":a[1].trim()});
            }
        }catch(Throwable t){AppLog.w("all apps list failed: "+Log.getStackTraceString(t));}
        if(list.isEmpty()){   // the usual list found nothing (a TV with only system apps): every app with a launcher, except factory menus
            try{
                PackageManager pm=ctx.getPackageManager();java.util.Set<String> seen=new java.util.HashSet<>();
                for(String cat:new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER,Intent.CATEGORY_LAUNCHER})
                    for(android.content.pm.ResolveInfo ri:pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat),0)){
                        String pkg=ri.activityInfo.packageName;
                        if(pkg.equals(ctx.getPackageName())||HomeApps.isHidden(pkg)||HomeMode.factoryMenu(pkg)||!seen.add(pkg))continue;
                        list.add(new String[]{pkg,String.valueOf(ri.loadLabel(pm)).trim()});
                    }
            }catch(Throwable t){AppLog.w("all apps fallback failed: "+Log.getStackTraceString(t));}
        }
        final java.text.Collator coll=java.text.Collator.getInstance(java.util.Locale.getDefault());
        java.util.Collections.sort(list,(x,y)->coll.compare(x[1],y[1]));
        return list;
    }

    /** The list is read off the UI thread; it is read again each time the screen comes back (an app may have been installed). */
    private void load(){
        try{io.execute(()->{
            final List<String[]> list=readList(getApplicationContext());
            main.post(()->{cache=list;apps.clear();apps.addAll(list);loaded=true;view.dataChanged();});
        });}catch(java.util.concurrent.RejectedExecutionException gone){}
    }

    private static final ExecutorService PRELOAD=Executors.newSingleThreadExecutor();
    private static boolean preloading;
    /** Called when the home screen opens: the list and the first cards are made in the background, so the first visit shows no loading text and no grey squares. */
    static void preload(Context c){
        final Context app=c.getApplicationContext();
        final Handler ui=new Handler(Looper.getMainLooper());
        ui.post(()->{
            if(preloading||(cache!=null&&TILES.size()>=Math.min(cache.size(),PRELOAD_TILES)))return;
            preloading=true;
            watchPackages(app);
            try{PRELOAD.execute(()->{
                try{
                    final List<String[]> list=readList(app);
                    ui.post(()->{if(cache==null)cache=list;});
                    int n=0;
                    for(final String[] a:list){
                        if(n++>=PRELOAD_TILES)break;
                        final String pkg=a[0];
                        try{
                            final Bitmap t=makeTile(app,pkg,a[1]);
                            ui.post(()->{if(!TILES.containsKey(pkg)){TILES.put(pkg,t);ASKED.add(pkg);}});
                        }catch(Throwable t){AppLog.w("all-apps preload tile failed: "+pkg+" "+t);}
                    }
                }finally{ui.post(()->preloading=false);}
            });}catch(java.util.concurrent.RejectedExecutionException gone){ui.post(()->preloading=false);}
        });
    }
    private static final int PRELOAD_TILES=25;   // the first screen is 15 cards; a few rows more

    // A card is 16:9 like the home screen's app tiles: the app's TV banner fills it; an app without one shows its whole icon, 70% of the card's height, on the flat #1A1F3A card.
    private static final int CARD=0xFF1A1F3A,TILE_W=330,TILE_H=186;   // reference pixels (1 dp = 1.5 px)

    /** The app's card, made off the UI thread: banner, else icon on a dark card (never a blank white square). */
    /** More than this share of a card that is light (white, grey, yellow…) and it becomes the dark card with the icon. */
    private static final float LIGHT_LIMIT=0.35f;
    static Bitmap makeTile(Context c,String pkg,String name){return makeTile(c,pkg,name,true);}

    /** `light` is kept for callers; every card now gets the same check. */
    static Bitmap makeTile(Context c,String pkg,String name,boolean light){
        PackageManager pm=c.getPackageManager();
        Bitmap raw=Bitmap.createBitmap(TILE_W,TILE_H,Bitmap.Config.ARGB_8888);
        Canvas rc=new Canvas(raw);
        AppBanners.Found found=null;
        try{found=AppBanners.find(c,pkg);}catch(Throwable t){found=null;}
        boolean ok=false;
        String how="banner";
        if(found!=null){
            try{
                rc.drawColor(CARD);   // art with transparency sits on a dark card
                AppBanners.cover(rc,found.drawable,TILE_W,TILE_H);
                ok=!blank(raw);   // the banner as the app drew it (the TV launcher shows it whatever its colours)
                how=found.how;
            }catch(Throwable t){ok=false;}
        }
        if(!ok){
            how=drawIcon(c,pkg,name,raw,rc);
        }
        AppLog.w("all-apps tile "+pkg+": "+how);
        // rounded corners, anti-aliased
        Bitmap out=Bitmap.createBitmap(TILE_W,TILE_H,Bitmap.Config.ARGB_8888);
        Paint pp=new Paint(Paint.ANTI_ALIAS_FLAG);pp.setShader(new BitmapShader(raw,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP));
        new Canvas(out).drawRoundRect(new RectF(0,0,TILE_W,TILE_H),22,22,pp);
        raw.recycle();
        return out;
    }

    /** No banner: the whole icon in its own colours, 70% of the card's height, in the middle of the flat #1A1F3A card. */
    private static String drawIcon(Context c,String pkg,String name,Bitmap raw,Canvas rc){
        int size=Math.round(TILE_H*0.70f);
        String[] how={""};
        Bitmap glyph=null;
        try{glyph=AppBanners.fullIcon(c,pkg,size,how);}catch(Throwable t){glyph=null;}
        if(glyph!=null){
            rc.drawColor(CARD);   // always #1A1F3A, never the icon's colour
            rc.drawBitmap(glyph,(TILE_W-size)/2f,(TILE_H-size)/2f,null);
            glyph.recycle();
            return how[0];
        }
        // nothing drew: the app's first letter on the dark card
        rc.drawColor(CARD);
        Paint lp=Design.text(Design.TEXT,64,Design.bold());
        String n=name==null?"":name.trim();if(n.length()>3&&n.startsWith("ال"))n=n.substring(2);
        Design.center(rc,n.isEmpty()?"?":n.substring(0,n.offsetByCodePoints(0,1)),TILE_W/2f,TILE_H/2f+22,lp);
        return "letter";
    }

    /** Share of a 40x24 grid of samples that are opaque and near white: 0.5 or more is a white card. */
    /**
     * A card that glares on an OLED: a fifth of it bright (luminance 130 and up), or a pale grey or pastel (bright and
     * nearly colourless) fourth of it, or a mean luminance of 105 and up, or mostly near white. Such a card becomes the dark one with the icon.
     */
    static boolean tooLight(Bitmap b){return tooLight(b,false);}
    /** `strict` is for a banner (a picture the app drew, not our dark card): it is dropped for the icon on the dark card earlier, so a card that is only a little pale is never shown. */
    static boolean tooLight(Bitmap b,boolean strict){
        int bright=0,pale=0,vivid=0,n=0,seen=0;double sum=0;
        for(int i=0;i<40;i++)for(int j=0;j<24;j++){
            int px=b.getPixel(Math.min(b.getWidth()-1,(int)((i+0.5f)*b.getWidth()/40)),Math.min(b.getHeight()-1,(int)((j+0.5f)*b.getHeight()/24)));
            n++;
            if((px>>>24)<128)continue;
            int r=px>>16&255,g=px>>8&255,bl=px&255;
            double luma=0.299*r+0.587*g+0.114*bl;
            seen++;sum+=luma;
            if(luma>=130)bright++;
            if(luma>=110&&Math.max(r,Math.max(g,bl))-Math.min(r,Math.min(g,bl))<40)pale++;
            if(luma>=110&&Math.max(r,Math.max(g,bl))>=200)vivid++;   // a bright yellow, light blue or pink card
        }
        float lightShare=bright/(float)n,paleShare=pale/(float)n,mean=seen==0?0:(float)(sum/seen);
        if(strict&&(lightShare>=0.12f||paleShare>=0.15f||mean>=80f||vivid/(float)n>=0.25f))return true;
        return lightShare>=0.20f||paleShare>=0.25f||mean>=105f||whiteShare(b)>=0.30f;
    }
    /** The share of samples that are bright (luminance 150 and up: white, light grey, yellow). */
    static float lightShare(Bitmap b){
        int light=0,n=0;
        for(int i=0;i<40;i++)for(int j=0;j<24;j++){
            int px=b.getPixel(Math.min(b.getWidth()-1,(int)((i+0.5f)*b.getWidth()/40)),Math.min(b.getHeight()-1,(int)((j+0.5f)*b.getHeight()/24)));
            n++;
            double luma=0.299*(px>>16&255)+0.587*(px>>8&255)+0.114*(px&255);
            if((px>>>24)>=200&&luma>=150)light++;
        }
        return light/(float)n;
    }
    private static float whiteShare(Bitmap b){
        int white=0,n=0;
        for(int i=0;i<40;i++)for(int j=0;j<24;j++){
            int px=b.getPixel(Math.min(b.getWidth()-1,(int)((i+0.5f)*b.getWidth()/40)),Math.min(b.getHeight()-1,(int)((j+0.5f)*b.getHeight()/24)));
            n++;
            if((px>>>24)>=200&&(px>>16&255)>=232&&(px>>8&255)>=232&&(px&255)>=232)white++;
        }
        return white/(float)n;
    }
    /** True when the picture is one flat colour (a blank white square) or has nothing drawn in it. Samples inside the edge, where a rounded icon is transparent. */
    private static boolean blank(Bitmap b){return blank(b,0,0,Math.min(b.getWidth(),b.getHeight()),false);}
    private static boolean blank(Bitmap b,int x0,int y0,int size,boolean glyphOnly){
        int min=255,max=0,seen=0;
        for(int i=0;i<12;i++)for(int j=0;j<12;j++){
            int x=Math.min(b.getWidth()-1,x0+Math.round(size*(0.12f+0.76f*i/11))),y=Math.min(b.getHeight()-1,y0+Math.round(size*(0.12f+0.76f*j/11)));
            int px=b.getPixel(x,y);
            if((px>>>24)<8)continue;
            int lum=((px>>16&255)*3+(px>>8&255)*6+(px&255))/10;
            seen++;min=Math.min(min,lum);max=Math.max(max,lum);
        }
        if(glyphOnly)return seen<2;
        return seen<6||max-min<10;
    }

    private final class AppsView extends View {
        private static final int COLS=5,ROWS=3;
        private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG),bmp=new Paint(Paint.FILTER_BITMAP_FLAG|Paint.ANTI_ALIAS_FLAG);
        private final Paint title=Design.text(Design.TEXT,40,Design.bold()),sub=Design.text(Design.MUTED,20,Design.regular()),
                label=Design.text(Design.MUTED,22,Design.regular()),labelOn=Design.text(Design.TEXT,22,Design.bold());
        private final RectF r=new RectF(),g=new RectF();
        private final A11y a11y=new A11y(this);
        private final java.util.Set<String> asked=ASKED;
        private final Map<String,Bitmap> tiles=TILES;
        private final ExecutorService tileLoader=Executors.newSingleThreadExecutor();
        private Bitmap backdrop;
        private float k;
        private int sel,scroll;   // sel: index in the list; scroll: first row drawn
        AppsView(Context c){super(c);setFocusable(true);setFocusableInTouchMode(true);}
        @Override public android.view.accessibility.AccessibilityNodeProvider getAccessibilityNodeProvider(){return a11y.provider(null,null);}
        @Override protected void onDetachedFromWindow(){tileLoader.shutdownNow();ASKED.retainAll(TILES.keySet());super.onDetachedFromWindow();}   // a card still being made is asked for again next time
        void dataChanged(){if(sel>=apps.size())sel=Math.max(0,apps.size()-1);invalidate();}

        @Override protected void onSizeChanged(int w,int h,int ow,int oh){
            k=w/1920f;
            backdrop=Wallpaper.inner(getContext(),w,h);   // the inner wallpaper, already dark: nothing over it
        }

        private Bitmap tile(final String pkg,final String name){
            Bitmap b=tiles.get(pkg);
            if(b!=null||!asked.add(pkg))return b;
            try{tileLoader.execute(()->{
                Bitmap made=null;
                try{made=makeTile(getContext(),pkg,name);}catch(Throwable t){AppLog.w("all-apps tile failed: "+pkg+" "+t);}
                final Bitmap done=made;
                main.post(()->{if(done!=null){tiles.put(pkg,done);invalidate();}});
            });}catch(java.util.concurrent.RejectedExecutionException gone){}
            return null;
        }

        @Override protected void onDraw(Canvas c){
            try{drawScreen(c);}
            catch(RuntimeException e){AppLog.w("all-apps draw failed: "+Log.getStackTraceString(e));}
        }
        private void drawScreen(Canvas c){
            int w=getWidth(),h=getHeight();
            if(backdrop!=null)c.drawBitmap(backdrop,null,new RectF(0,0,w,h),null);else c.drawColor(Design.BG);
            a11y.begin(k);
            c.save();c.scale(k,k);
            Design.right(c,"كل التطبيقات",1848,76,title);
            Design.right(c,loaded?apps.size()+" تطبيق":"جارٍ التحميل…",1848,113,sub);
            a11y.add(1100,40,1848,126,"كل التطبيقات · "+(loaded?apps.size()+" تطبيق":"جارٍ التحميل"),false,false);
            int pitchX=359,pitchY=250;
            int row=sel/COLS;
            if(row<scroll)scroll=row;
            if(row>=scroll+ROWS)scroll=row-ROWS+1;
            if(apps.isEmpty()){
                Design.center(c,loaded?"لا توجد تطبيقات":"جارٍ تحميل التطبيقات…",960,540,Design.text(Design.MUTED,28,Design.regular()));
            }
            for(int i=scroll*COLS;i<Math.min(apps.size(),(scroll+ROWS)*COLS);i++){
                int rr=i/COLS-scroll,cc=i%COLS;
                float right=1848-cc*pitchX,left=right-TILE_W,top=170+rr*pitchY;
                r.set(left,top,right,top+TILE_H);
                boolean focused=i==sel;
                RectF box=r;if(focused){Design.grown(r,g);box=g;}
                String[] app=apps.get(i);
                Bitmap t=tile(app[0],app[1]);
                if(t!=null)c.drawBitmap(t,null,box,bmp);
                else{Design.card(c,box,22,1,p,0xFF1B1E2B);}
                Design.center(c,Design.fitShrink(app[1],focused?labelOn:label,TILE_W-8),(left+right)/2,top+TILE_H+34,focused?labelOn:label);
                a11y.add(box,app[1],focused,true);
                if(focused)Design.focusRing(c,box,22,1,p);
            }
            Design.right(c,"OK للفتح · ▲▼◀▶ للتنقل · BACK للرئيسية",1848,1054,sub);
            Design.left(c,"كل التطبيقات",72,1054,sub);
            c.restore();
        }

        @Override public boolean onKeyDown(int key,KeyEvent e){
            int n=apps.size();
            switch(key){
                case KeyEvent.KEYCODE_DPAD_RIGHT:if(sel>0&&sel%COLS!=0)sel--;invalidate();return true;   // RTL: the first column is on the right
                case KeyEvent.KEYCODE_DPAD_LEFT:if(sel+1<n&&sel%COLS!=COLS-1)sel++;invalidate();return true;
                case KeyEvent.KEYCODE_DPAD_UP:if(sel>=COLS)sel-=COLS;invalidate();return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:if(sel+COLS<n)sel+=COLS;else if(n>0&&sel/COLS<(n-1)/COLS)sel=n-1;invalidate();return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:case KeyEvent.KEYCODE_ENTER:
                    if(e.getRepeatCount()==0&&n>0)open(apps.get(Math.min(sel,n-1))[0]);
                    return true;
            }
            return super.onKeyDown(key,e);
        }
        private void open(String pkg){
            RecentsStore.reopened(getContext(),pkg);
            Intent i=HomeApps.launchIntent(getContext().getPackageManager(),pkg);
            try{if(i!=null)getContext().startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));}
            catch(RuntimeException e){AppLog.w("all apps: could not open "+pkg);}
        }
    }
}
