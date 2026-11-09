package io.ouswis.tv.standalone;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import java.io.InputStream;
import android.graphics.Matrix;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextDirectionHeuristics;
import android.text.TextPaint;

/** Static artwork and reusable paints for the approved sunset/black/gold home. No allocations per panel draw. */
final class GoldenHomeSkin {
    static final int GOLD=0xFFFFD384,WHITE=0xFFF4F1E9;
    private final Paint image=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final Paint glass=new Paint(Paint.ANTI_ALIAS_FLAG),line=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint temperature=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint header=new Paint(Paint.ANTI_ALIAS_FLAG),clock=new Paint(Paint.ANTI_ALIAS_FLAG),small=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box=new RectF(),art=new RectF();
    private final Paint cardGlass=new Paint(Paint.ANTI_ALIAS_FLAG),cardRim=new Paint(Paint.ANTI_ALIAS_FLAG),cardReflection=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix cardMatrix=new Matrix();
    private final TextPaint datePaint=new TextPaint(Paint.ANTI_ALIAS_FLAG),hijriPaint=new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final SaudiDates dates=new SaudiDates();
    private StaticLayout gregorianLayout,hijriLayout;
    private CardBackdrop prayerBackdrop;
    private Bitmap wallpaper,prayerArt,weatherPhoto,calendarIcon,mosqueIcon;
    private long minute=-1;
    private int generation=-1;
    GoldenHomeSkin(){line.setStyle(Paint.Style.STROKE);cardRim.setStyle(Paint.Style.STROKE);}
    void load(Context context){
        wallpaper=decode(context,"themes/gold-sunset.png");
        prayerArt=decode(context,"themes/gold-prayers.png");
        prayerBackdrop=CardBackdrop.load(context,"themes/card-prayer.jpg",true,0);
        weatherPhoto=decode(context,"themes/gold-weather.png");
        calendarIcon=decode(context,"themes/gold-calendar.png");
        mosqueIcon=decode(context,"themes/gold-mosque.png");
    }
    private static Bitmap decode(Context context,String path){
        try(InputStream in=context.getAssets().open(path)){return BitmapFactory.decodeStream(in);}
        catch(Exception e){AppLog.w("gold artwork unavailable");return null;}
    }
    void resize(float k,float width,float height){
        glass.setShader(new LinearGradient(0,0,width,height,new int[]{0xED29313E,0xF2090D16,0xF5080B11},new float[]{0,0.45f,1},Shader.TileMode.CLAMP));
        header.setTypeface(Ui.semibold);header.setTextSize(25*k);header.setColor(WHITE);
        clock.setTypeface(Ui.light);clock.setTextSize(86*k);clock.setColor(WHITE);
        clock.setTextAlign(Paint.Align.CENTER);clock.setFontFeatureSettings("'tnum'");
        clock.setShadowLayer(3*k,0,2*k,0x88000000);
        temperature.setTypeface(Ui.displaySemibold);temperature.setTextSize(70*k);temperature.setColor(WHITE);
        gregorianLayout=null;hijriLayout=null;
        cardGlass.setShader(new LinearGradient(0,0,0,1,new int[]{0xF3121418,0xF905070A,0xF5030508},new float[]{0,0.5f,1},Shader.TileMode.CLAMP));
        cardRim.setShader(new LinearGradient(0,0,1,1,new int[]{0xFFFFE6AF,0xFFA67B34,0xFFFFCF70,0xFF91702F,0xFFFFE6B3},new float[]{0,0.28f,0.55f,0.8f,1},Shader.TileMode.CLAMP));
        cardReflection.setShader(new LinearGradient(0,0,0,1,new int[]{0x26FFFFFF,0x06FFFFFF,0x00FFFFFF},new float[]{0,0.18f,0.5f},Shader.TileMode.CLAMP));
        small.setTypeface(Ui.medium);small.setTextSize(22*k);small.setColor(0xE0F0E6D4);
        datePaint.set(header);hijriPaint.set(small);
    }
    void background(Canvas c,RectF screen){
        c.drawColor(0xFF060A13);
        if(wallpaper!=null)c.drawBitmap(wallpaper,null,screen,image);
    }
    void panel(Canvas c,RectF r,float radius,float k,float focus){
        c.drawRoundRect(r,radius,radius,glass);
        line.setColor(0x80D2B47D);line.setStrokeWidth(k);
        c.drawRoundRect(r,radius,radius,line);
        if(focus>0.01f)focus(c,r,radius,k,focus);
    }
    void focus(Canvas c,RectF r,float radius,float k,float focus){
        for(int i=3;i>=1;i--){
            box.set(r);box.inset(-i*2*k,-i*2*k);
            line.setStrokeWidth((3+i)*k);line.setColor(Ui.alpha(GOLD,focus*(0.09f-0.02f*i)));
            c.drawRoundRect(box,radius+i*2*k,radius+i*2*k,line);
        }
        line.setColor(Ui.alpha(0xFFFFE9B4,focus));line.setStrokeWidth(3*k);
        c.drawRoundRect(r,radius,radius,line);
    }
    void highlight(Canvas c,RectF r,float k){
        Paint.Style style=line.getStyle();line.setStyle(Paint.Style.FILL);line.setColor(0xFFFFD77C);
        c.drawRoundRect(r,15*k,15*k,line);line.setStyle(style);
    }
    void download(Canvas c,float cx,float cy,float size,int color){
        float u=size/2;line.setColor(color);line.setStrokeWidth(size/14);
        box.set(cx-u,cy-u,cx+u,cy+u);c.drawRoundRect(box,u/4,u/4,line);
        c.drawLine(cx,cy-u*0.7f,cx,cy+u*0.35f,line);
        c.drawLine(cx-u*0.45f,cy-u*0.1f,cx,cy+u*0.35f,line);
        c.drawLine(cx+u*0.45f,cy-u*0.1f,cx,cy+u*0.35f,line);
        c.drawLine(cx-u*0.5f,cy+u*0.65f,cx+u*0.5f,cy+u*0.65f,line);
    }
    void header(Canvas c,float k,String time,HomeData.Weather weather,String city){
        long now=System.currentTimeMillis();
        if(now/60000!=minute||Ui.generation!=generation||gregorianLayout==null){
            minute=now/60000;generation=Ui.generation;
            boolean changed=dates.update(now,Ui.locale());
            if(changed||gregorianLayout==null){
                boolean rtl="ar".equals(Ui.locale().getLanguage());
                gregorianLayout=dateLayout(dates.gregorian,datePaint,410*k,rtl);
                hijriLayout=dateLayout(dates.hijri,hijriPaint,410*k,true);
            }
        }
        int save=c.save();c.translate(95*k,62*k);gregorianLayout.draw(c);c.restoreToCount(save);
        save=c.save();c.translate(95*k,112*k);hijriLayout.draw(c);c.restoreToCount(save);
        if(calendarIcon!=null){art.set(522*k,76*k,522*k+48*k*calendarIcon.getWidth()/calendarIcon.getHeight(),124*k);c.drawBitmap(calendarIcon,null,art,image);}
        // Large, light numerals directly on the wallpaper: no opaque clock capsule.
        fitText(c,time,955*k,121*k,390*k,clock);
    }
    private static StaticLayout dateLayout(String value,TextPaint paint,float width,boolean rtl){
        return StaticLayout.Builder.obtain(value,0,value.length(),paint,Math.max(1,Math.round(width)))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false)
                .setTextDirection(rtl?TextDirectionHeuristics.RTL:TextDirectionHeuristics.LTR).build();
    }
    /** Shared local-coordinate black glass for only the weather and prayer cards. */
    void informationCard(Canvas c,RectF r,float k){informationCard(c,r,k,false);}
    void prayerCard(Canvas c,RectF r,float k){informationCard(c,r,k,true);}
    private void informationCard(Canvas c,RectF r,float k,boolean prayer){
        float radius=30*k;
        // Layered strokes make a soft shadow/glow without off-screen blur layers on older TVs.
        line.setShader(null);
        for(int i=4;i>=1;i--){
            box.set(r);box.offset(0,3*k);box.inset(-i*k,-i*k);
            line.setStrokeWidth((i*2+2)*k);line.setColor(0x18000000);c.drawRoundRect(box,radius,radius,line);
        }
        cardMatrix.setScale(r.width(),r.height());cardMatrix.postTranslate(r.left,r.top);
        cardGlass.getShader().setLocalMatrix(cardMatrix);cardRim.getShader().setLocalMatrix(cardMatrix);
        cardReflection.getShader().setLocalMatrix(cardMatrix);
        c.drawRoundRect(r,radius,radius,cardGlass);
        if(prayer&&prayerBackdrop!=null)prayerBackdrop.drawIn(c,r,radius);
        c.drawRoundRect(r,radius,radius,cardReflection);
        for(int i=4;i>=1;i--){
            box.set(r);box.inset(-i*k,-i*k);
            line.setStrokeWidth((2+i)*k);line.setColor(Ui.alpha(0xFFFFBE4C,0.07f-0.01f*i));
            c.drawRoundRect(box,radius+i*k,radius+i*k,line);
        }
        cardRim.setAlpha(255);cardRim.setStrokeWidth(1.6f*k);c.drawRoundRect(r,radius,radius,cardRim);
        box.set(r);box.inset(2.4f*k,2.4f*k);cardRim.setAlpha(65);cardRim.setStrokeWidth(0.7f*k);
        c.drawRoundRect(box,radius-2*k,radius-2*k,cardRim);cardRim.setAlpha(255);
    }
    void text(Canvas c,String value,float left,float baseline,float width,float k,boolean large){
        fitText(c,value,left,baseline,width,large?temperature:small);
    }
    private static void fitText(Canvas c,String value,float left,float baseline,float width,Paint p){
        float measured=p.measureText(value);p.setTextScaleX(measured>width?width/measured:1);
        c.drawText(value,left,baseline,p);p.setTextScaleX(1);
    }
    /** Jeddah's bridge, fading into the weather card's lower left as in the approved design. */
    void weatherArt(Canvas c,RectF r,float k){
        if(weatherPhoto==null)return;
        float w=r.width()*0.46f,h=w*weatherPhoto.getHeight()/weatherPhoto.getWidth();
        art.set(r.left+2*k,r.bottom-2*k-h,r.left+2*k+w,r.bottom-2*k);
        c.drawBitmap(weatherPhoto,null,art,image);
    }
    void mosque(Canvas c,float left,float cy,float size){
        if(mosqueIcon==null)return;
        float w=size*mosqueIcon.getWidth()/mosqueIcon.getHeight();
        art.set(left,cy-size/2,left+w,cy+size/2);c.drawBitmap(mosqueIcon,null,art,image);
    }
    void prayerArt(Canvas c,RectF r,float k){
        if(prayerArt==null)return;
        // Transparent frontal artwork merges with the glass. Fit rather than stretch the Kaaba and minarets.
        float right=r.right-10*k,bottom=r.bottom-28*k;
        float scale=Math.min(r.width()*0.50f/prayerArt.getWidth(),(r.height()-70*k)/prayerArt.getHeight());
        art.set(right-prayerArt.getWidth()*scale,bottom-prayerArt.getHeight()*scale,right,bottom);
        c.drawBitmap(prayerArt,null,art,image);
    }
}
