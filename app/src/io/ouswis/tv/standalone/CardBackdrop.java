package io.ouswis.tv.standalone;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import java.io.InputStream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Three local, cached photographs: no networking, draw-time decoding, blur layers or input handling. */
final class CardBackdrop extends Drawable {
    private static final ConcurrentHashMap<String,Bitmap> CACHE=new ConcurrentHashMap<>();
    private static final ExecutorService LOADER=Executors.newSingleThreadExecutor();
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private final Paint photo=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG|Paint.DITHER_FLAG);
    private final Paint veil=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF area=new RectF();
    private final Matrix matrix=new Matrix();
    private Bitmap bitmap;
    private float radius;

    private CardBackdrop(boolean dark,float radius){
        this.radius=radius;
        veil.setShader(new LinearGradient(0,0,1,0,dark
                ?new int[]{0xC9080B13,0x9C080B13,0xAD080B13}
                :new int[]{0xE6F2F2F5,0xCDF2F2F5,0xDBF2F2F5},new float[]{0,0.55f,1},Shader.TileMode.CLAMP));
    }
    /** Used by GoldenHomeSkin's existing worker, so the first card frame has its artwork ready. */
    static CardBackdrop load(Context c,String path,boolean dark,float radius){
        CardBackdrop result=new CardBackdrop(dark,radius);result.setBitmap(read(c,path));return result;
    }
    /** Page backgrounds load outside the main thread and invalidate only after becoming ready. */
    static CardBackdrop async(Context c,String path,boolean dark,float radius){
        CardBackdrop result=new CardBackdrop(dark,radius);
        Bitmap cached=CACHE.get(path);
        if(cached!=null)result.setBitmap(cached);
        else{
            Context app=c.getApplicationContext();
            LOADER.execute(()->{Bitmap b=read(app,path);MAIN.post(()->{result.setBitmap(b);result.invalidateSelf();});});
        }
        return result;
    }
    private static Bitmap read(Context c,String path){
        Bitmap cached=CACHE.get(path);if(cached!=null)return cached;
        try(InputStream stream=c.getAssets().open(path)){
            BitmapFactory.Options options=new BitmapFactory.Options();options.inPreferredConfig=Bitmap.Config.RGB_565;
            Bitmap decoded=BitmapFactory.decodeStream(stream,null,options);
            if(decoded!=null){Bitmap prior=CACHE.putIfAbsent(path,decoded);return prior==null?decoded:prior;}
        }catch(Exception e){AppLog.w("card artwork unavailable: "+path);}
        return null;
    }
    private void setBitmap(Bitmap b){
        bitmap=b;if(b!=null)photo.setShader(new BitmapShader(b,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP));
        updateMatrix();
    }
    private void updateMatrix(){
        if(area.isEmpty())return;
        if(bitmap!=null){
            float scale=Math.max(area.width()/bitmap.getWidth(),area.height()/bitmap.getHeight());
            matrix.setScale(scale,scale);matrix.postTranslate(area.centerX()-bitmap.getWidth()*scale/2,area.centerY()-bitmap.getHeight()*scale/2);
            photo.getShader().setLocalMatrix(matrix);
        }
        matrix.setScale(area.width(),area.height());matrix.postTranslate(area.left,area.top);
        veil.getShader().setLocalMatrix(matrix);
    }
    @Override protected void onBoundsChange(Rect bounds){area.set(bounds);updateMatrix();}
    void drawIn(Canvas c,RectF bounds,float rounding){
        radius=rounding;
        if(!area.equals(bounds)){area.set(bounds);updateMatrix();}
        draw(c);
    }
    @Override public void draw(Canvas c){
        if(bitmap!=null)c.drawRoundRect(area,radius,radius,photo);
        c.drawRoundRect(area,radius,radius,veil);
    }
    @Override public void setAlpha(int alpha){photo.setAlpha(alpha);invalidateSelf();}
    @Override public void setColorFilter(ColorFilter filter){photo.setColorFilter(filter);invalidateSelf();}
    @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
