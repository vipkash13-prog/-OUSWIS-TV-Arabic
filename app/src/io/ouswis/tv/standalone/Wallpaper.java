package io.ouswis.tv.standalone;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import java.io.InputStream;

/** The inner screens' background (ouswis_bg_inner_1080p.webp: the city without the crescent), centre-cropped, under a dark layer. The home screen keeps ouswis_bg_1080p.webp. */
final class Wallpaper {
    private Wallpaper(){}

    /** Draws the wallpaper over (0,0,w,h) and then a black layer of the given opacity (0..1). Call from a size change, not a draw pass. */
    static void draw(Context c,Canvas canvas,int w,int h,float dim){draw(c,canvas,w,h,dim,0x000000);}
    /** The same with the dark layer in the given colour (RGB), e.g. #0B0D1A. */
    static void draw(Context c,Canvas canvas,int w,int h,float dim,int rgb){
        Bitmap bmp=null;
        try(InputStream in=c.getAssets().open("backgrounds/ouswis_bg_inner_1080p.webp")){BitmapFactory.Options o=new BitmapFactory.Options();o.inPreferredConfig=Bitmap.Config.RGB_565;bmp=BitmapFactory.decodeStream(in,null,o);}catch(Exception ignored){}
        if(bmp==null){canvas.drawColor(Design.BG);return;}
        float s=Math.max(w/(float)bmp.getWidth(),h/(float)bmp.getHeight());
        float sw=w/s,sh=h/s;
        Rect src=new Rect((int)((bmp.getWidth()-sw)/2),(int)((bmp.getHeight()-sh)/2),(int)((bmp.getWidth()+sw)/2),(int)((bmp.getHeight()+sh)/2));
        canvas.drawBitmap(bmp,src,new RectF(0,0,w,h),new Paint(Paint.FILTER_BITMAP_FLAG));
        canvas.drawColor(((int)(dim*255)<<24)|(rgb&0xFFFFFF));
        bmp.recycle();
    }
}
