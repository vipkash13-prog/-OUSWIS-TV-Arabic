package io.ouswis.tv.standalone;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import java.io.InputStream;

/**
 * The two backgrounds, each already darkened inside the picture (nothing is drawn over them): the home screen's
 * (ouswis_bg_stars_1080p.webp) and the inner screens' (ouswis_bg_stars_inner_1080p.webp: Matches, Live TV, All Apps,
 * Recent and Settings). Each is loaded once per process as RGB_565, centre-cropped and scaled to the screen's size, and
 * every screen draws that same bitmap. A TV with no room for it gets the flat colour.
 */
final class Wallpaper {
    private Wallpaper(){}
    private static Bitmap home,inner;

    static synchronized Bitmap home(Context c,int w,int h){
        if(home==null||home.getWidth()!=w||home.getHeight()!=h)home=load(c,"backgrounds/ouswis_bg_stars_1080p.webp",w,h);
        return home;
    }
    static synchronized Bitmap inner(Context c,int w,int h){
        if(inner==null||inner.getWidth()!=w||inner.getHeight()!=h)inner=load(c,"backgrounds/ouswis_bg_stars_inner_1080p.webp",w,h);
        return inner;
    }

    /** RGB_565, centre-cropped to the screen's shape and scaled to w×h; null when there is no room. */
    private static Bitmap load(Context c,String path,int w,int h){
        if(w<1||h<1)return null;
        Bitmap full=null;
        try(InputStream in=c.getAssets().open(path)){
            BitmapFactory.Options o=new BitmapFactory.Options();o.inPreferredConfig=Bitmap.Config.RGB_565;
            full=BitmapFactory.decodeStream(in,null,o);
            if(full==null)return null;
            if(full.getWidth()==w&&full.getHeight()==h)return full;
            float s=Math.max(w/(float)full.getWidth(),h/(float)full.getHeight());   // centerCrop
            Bitmap out=Bitmap.createBitmap(w,h,Bitmap.Config.RGB_565);
            Matrix m=new Matrix();
            m.postScale(s,s);
            m.postTranslate((w-full.getWidth()*s)/2f,(h-full.getHeight()*s)/2f);
            new Canvas(out).drawBitmap(full,m,new Paint(Paint.FILTER_BITMAP_FLAG));
            full.recycle();
            return out;
        }catch(Throwable noRoom){
            if(full!=null)full.recycle();
            AppLog.w("wallpaper skipped: "+noRoom);
            return null;
        }
    }

    /** The inner wallpaper under a plain screen's content (Updates, Play log, IPTV, Section order, Channel map). The content must have no background of its own. */
    static android.view.View behind(final android.app.Activity a,android.view.View content){
        android.widget.FrameLayout f=new android.widget.FrameLayout(a);
        f.addView(new android.view.View(a){
            private Bitmap wall;
            @Override protected void onSizeChanged(int w,int h,int ow,int oh){wall=inner(getContext(),w,h);}
            @Override protected void onDraw(Canvas c){if(wall!=null)c.drawBitmap(wall,0,0,null);else c.drawColor(0xFF0B0D1A);}
        },new android.widget.FrameLayout.LayoutParams(-1,-1));
        f.addView(content,new android.widget.FrameLayout.LayoutParams(-1,-1));
        return f;
    }

    /** No slide or fade when this screen opens or closes, on any Android (14 ignores overridePendingTransition for a new task). */
    static void noTransition(android.app.Activity a){
        a.overridePendingTransition(0,0);
        if(android.os.Build.VERSION.SDK_INT>=34){
            a.overrideActivityTransition(android.app.Activity.OVERRIDE_TRANSITION_OPEN,0,0);
            a.overrideActivityTransition(android.app.Activity.OVERRIDE_TRANSITION_CLOSE,0,0);
        }
    }
}
