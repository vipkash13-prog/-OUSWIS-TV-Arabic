package io.ouswis.tv.standalone;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
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

    @Override protected void onCreate(Bundle b){
        super.onCreate(b);
        Ui.init(this);
        getWindow().getDecorView().setSystemUiVisibility(5894);
        view=new AppsView(this);
        FrameLayout root=new FrameLayout(this);
        root.addView(view,new FrameLayout.LayoutParams(-1,-1));
        setContentView(root);
        view.requestFocus();
    }
    @Override protected void onResume(){super.onResume();load();}
    @Override protected void onDestroy(){io.shutdownNow();super.onDestroy();}

    /** The list is read off the UI thread; it is read again each time the screen comes back (an app may have been installed). */
    private void load(){
        try{io.execute(()->{
            List<String[]> list=new ArrayList<>();
            try{
                for(String[] a:HomeApps.launchable(this)){
                    if(a[0].equals(getPackageName())||HomeApps.isHidden(a[0]))continue;
                    list.add(new String[]{a[0],a[1]==null?"":a[1].trim()});
                }
            }catch(Throwable t){AppLog.w("all apps list failed: "+Log.getStackTraceString(t));}
            if(list.isEmpty()){   // the usual list found nothing (a TV with only system apps): every app with a launcher, except factory menus
                try{
                    PackageManager pm=getPackageManager();java.util.Set<String> seen=new java.util.HashSet<>();
                    for(String cat:new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER,Intent.CATEGORY_LAUNCHER})
                        for(android.content.pm.ResolveInfo ri:pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat),0)){
                            String pkg=ri.activityInfo.packageName;
                            if(pkg.equals(getPackageName())||HomeApps.isHidden(pkg)||HomeMode.factoryMenu(pkg)||!seen.add(pkg))continue;
                            list.add(new String[]{pkg,String.valueOf(ri.loadLabel(pm)).trim()});
                        }
                }catch(Throwable t){AppLog.w("all apps fallback failed: "+Log.getStackTraceString(t));}
            }
            final java.text.Collator coll=java.text.Collator.getInstance(java.util.Locale.getDefault());
            java.util.Collections.sort(list,(x,y)->coll.compare(x[1],y[1]));
            main.post(()->{apps.clear();apps.addAll(list);loaded=true;view.dataChanged();});
        });}catch(java.util.concurrent.RejectedExecutionException gone){}
    }

    private final class AppsView extends View {
        private static final int COLS=6,ROWS=4;
        private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG),bmp=new Paint(Paint.FILTER_BITMAP_FLAG|Paint.ANTI_ALIAS_FLAG);
        private final Paint title=Design.text(Design.TEXT,40,Design.bold()),sub=Design.text(Design.MUTED,20,Design.regular()),
                label=Design.text(Design.TEXT,21,Design.regular()),initial=Design.text(Design.TEXT,40,Design.bold());
        private final RectF r=new RectF(),g=new RectF(),ir=new RectF();
        private final A11y a11y=new A11y(this);
        private final Map<String,Bitmap> icons=new HashMap<>();
        private final java.util.Set<String> asked=new java.util.HashSet<>();
        private final ExecutorService iconLoader=Executors.newSingleThreadExecutor();
        private Bitmap backdrop;
        private float k;
        private int sel,scroll;   // sel: index in the list; scroll: first row drawn
        AppsView(Context c){super(c);setFocusable(true);setFocusableInTouchMode(true);}
        @Override public android.view.accessibility.AccessibilityNodeProvider getAccessibilityNodeProvider(){return a11y.provider(null,null);}
        @Override protected void onDetachedFromWindow(){iconLoader.shutdownNow();super.onDetachedFromWindow();}
        void dataChanged(){if(sel>=apps.size())sel=Math.max(0,apps.size()-1);invalidate();}

        @Override protected void onSizeChanged(int w,int h,int ow,int oh){
            k=w/1920f;
            try{
                backdrop=Bitmap.createBitmap(Math.max(1,w/2),Math.max(1,h/2),Bitmap.Config.RGB_565);
                Canvas c=new Canvas(backdrop);c.scale(0.5f,0.5f);Wallpaper.draw(getContext(),c,w,h,0.6f,0x0B0D1A);   // the inner wallpaper under #0B0D1A at 60 %
            }catch(Throwable noRoom){backdrop=null;AppLog.w("backdrop skipped: "+noRoom);}
        }

        /** The icon of an app, drawn once at 96 px on a worker; until it is there the app's first letter shows. */
        private Bitmap icon(final String pkg){
            Bitmap b=icons.get(pkg);
            if(b!=null||!asked.add(pkg))return b;
            try{iconLoader.execute(()->{
                Bitmap made=null;
                try{
                    Drawable d=getContext().getPackageManager().getApplicationIcon(pkg);
                    made=Bitmap.createBitmap(96,96,Bitmap.Config.ARGB_8888);
                    Canvas c=new Canvas(made);d.setBounds(0,0,96,96);d.draw(c);
                }catch(Throwable t){made=null;}
                final Bitmap done=made;
                main.post(()->{if(done!=null){icons.put(pkg,done);invalidate();}});
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
            int tileW=270,tileH=190,gapX=20,gapY=18;
            int row=sel/COLS;
            if(row<scroll)scroll=row;
            if(row>=scroll+ROWS)scroll=row-ROWS+1;
            if(apps.isEmpty()){
                Design.center(c,loaded?"لا توجد تطبيقات":"جارٍ تحميل التطبيقات…",960,540,Design.text(Design.MUTED,28,Design.regular()));
            }
            for(int i=scroll*COLS;i<Math.min(apps.size(),(scroll+ROWS)*COLS);i++){
                int rr=i/COLS-scroll,cc=i%COLS;
                float right=1848-cc*(tileW+gapX),left=right-tileW,top=170+rr*(tileH+gapY);
                r.set(left,top,right,top+tileH);
                boolean focused=i==sel;
                RectF box=r;if(focused){Design.grown(r,g);box=g;}
                Design.card(c,box,24,1,p,0xFF151A2E);
                String[] app=apps.get(i);
                float cx=(left+right)/2;
                Bitmap ic=icon(app[0]);
                if(ic!=null){ir.set(cx-44,top+22,cx+44,top+110);c.drawBitmap(ic,null,ir,bmp);}
                else{
                    p.setStyle(Paint.Style.FILL);p.setShader(null);p.setColor(0x24FFFFFF);c.drawCircle(cx,top+66,40,p);
                    Design.center(c,app[1].isEmpty()?"?":app[1].substring(0,app[1].offsetByCodePoints(0,1)),cx,top+80,initial);
                }
                Design.center(c,Design.fitShrink(app[1],label,tileW-24),cx,top+152,label);
                a11y.add(box,app[1],focused,true);
                if(focused)Design.focusRing(c,box,24,1,p);
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
            Intent i=HomeApps.launchIntent(getContext().getPackageManager(),pkg);
            try{if(i!=null)getContext().startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));}
            catch(RuntimeException e){AppLog.w("all apps: could not open "+pkg);}
        }
    }
}
