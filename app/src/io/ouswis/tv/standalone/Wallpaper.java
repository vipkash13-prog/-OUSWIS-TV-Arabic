package io.ouswis.tv.standalone;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import java.io.InputStream;

/** The supplied home wallpaper (ouswis_bg_1080p.webp), centre-cropped, for screens that put a dark layer over it. */
final class Wallpaper {
    private Wallpaper(){}

    /** Draws the wallpaper over (0,0,w,h) and then a black layer of the given opacity (0..1). Call from a size change, not a draw pass. */
    static void draw(Context c,Canvas canvas,int w,int h,float dim){
        Bitmap bmp=null;
        try(InputStream in=c.getAssets().open("backgrounds/ouswis_bg_1080p.webp")){bmp=BitmapFactory.decodeStream(in);}catch(Exception ignored){}
        if(bmp==null){canvas.drawColor(Design.BG);return;}
        float s=Math.max(w/(float)bmp.getWidth(),h/(float)bmp.getHeight());
        float sw=w/s,sh=h/s;
        Rect src=new Rect((int)((bmp.getWidth()-sw)/2),(int)((bmp.getHeight()-sh)/2),(int)((bmp.getWidth()+sw)/2),(int)((bmp.getHeight()+sh)/2));
        canvas.drawBitmap(bmp,src,new RectF(0,0,w,h),new Paint(Paint.FILTER_BITMAP_FLAG));
        canvas.drawColor((int)(dim*255)<<24);
        bmp.recycle();
    }
}
