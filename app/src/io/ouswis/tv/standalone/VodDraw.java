package io.ouswis.tv.standalone;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * One film poster as the films screens draw it (movies.png, movie.png): the picture filling a rounded card, a dark
 * fade at the bottom with the name, and the rating in a small dark chip at the top left. Drawn in the poster's own
 * width×height and scaled to the box, so the paints and the fade are made once.
 */
final class VodDraw {
    final float w,h;
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG),fade=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint name,rate,star,empty;
    private final Path clip=new Path();
    private final RectF r=new RectF(),src=new RectF(),dst=new RectF();
    private final android.graphics.Rect srcI=new android.graphics.Rect();

    VodDraw(float w,float h,float nameSize){
        this.w=w;this.h=h;
        name=Design.text(Design.TEXT,nameSize,Design.bold());
        rate=Design.text(0xFFFFFFFF,nameSize*0.82f,Design.regular());
        star=Design.text(Design.GOLD,nameSize*0.86f,Design.bold());
        empty=Design.text(Design.MUTED,nameSize,Design.bold());
        fade.setShader(new LinearGradient(0,h*0.55f,0,h,0x00000000,0xE6000000,Shader.TileMode.CLAMP));
        clip.addRoundRect(new RectF(0,0,w,h),14,14,Path.Direction.CW);
    }

    /** `withName`: the name over the fade (the grid); the details page's poster carries it too. */
    void draw(Canvas c,RectF box,Bitmap poster,String title,String rating,boolean withName){
        c.save();
        c.translate(box.left,box.top);c.scale(box.width()/w,box.height()/h);
        c.save();c.clipPath(clip);
        p.setShader(null);p.setStyle(Paint.Style.FILL);p.setColor(0xFF1A1F3A);c.drawRect(0,0,w,h,p);
        if(poster!=null){   // centre-crop to the card
            float s=Math.max(w/poster.getWidth(),h/poster.getHeight());
            float sw=w/s,sh=h/s;
            srcI.set(Math.round((poster.getWidth()-sw)/2),Math.round((poster.getHeight()-sh)/2),Math.round((poster.getWidth()+sw)/2),Math.round((poster.getHeight()+sh)/2));
            dst.set(0,0,w,h);p.setColor(0xFFFFFFFF);c.drawBitmap(poster,srcI,dst,p);
        }else if(!withName){
            Design.center(c,Design.fit(title,empty,w-24),w/2,h/2,empty);
        }
        if(withName){
            c.drawRect(0,h*0.55f,w,h,fade);
            Design.center(c,Design.fitShrink(title,name,w-20),w/2,h-22,name);
        }
        c.restore();
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.5f);p.setColor(Design.STROKE);r.set(0,0,w,h);c.drawRoundRect(r,14,14,p);
        if(rating!=null&&!rating.isEmpty()){
            float tw=rate.measureText(rating),sw=star.measureText("★");
            float bw=tw+sw+26,bh=rate.getTextSize()*1.55f;
            r.set(10,10,10+bw,10+bh);
            p.setStyle(Paint.Style.FILL);p.setColor(0xCC0B0D1A);c.drawRoundRect(r,bh/2,bh/2,p);
            float base=10+bh/2+rate.getTextSize()*0.36f;
            Design.left(c,rating,10+9,base,rate);
            Design.left(c,"★",10+9+tw+6,base,star);
        }
        c.restore();
    }
}
