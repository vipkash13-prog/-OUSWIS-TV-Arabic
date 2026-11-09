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
import java.util.Locale;

/** Static artwork and reusable paints for the approved sunset/black/gold home. No allocations per panel draw. */
final class GoldenHomeSkin {
    static final int GOLD=0xFFFFD384,WHITE=0xFFF4F1E9;
    private final Paint image=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final Paint glass=new Paint(Paint.ANTI_ALIAS_FLAG),line=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint header=new Paint(Paint.ANTI_ALIAS_FLAG),clock=new Paint(Paint.ANTI_ALIAS_FLAG),small=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box=new RectF(),art=new RectF();
    private Bitmap wallpaper,prayerArt;
    private long minute=-1;
    private int generation=-1;
    private String date="",hijri="";
    private final android.icu.util.IslamicCalendar calendar=new android.icu.util.IslamicCalendar();
    private final android.icu.text.SimpleDateFormat hijriFormat=new android.icu.text.SimpleDateFormat("d MMMM yyyy",new Locale("ar"));
    GoldenHomeSkin(){
        line.setStyle(Paint.Style.STROKE);
        calendar.setCalculationType(android.icu.util.IslamicCalendar.CalculationType.ISLAMIC_UMALQURA);
        hijriFormat.setCalendar(calendar);
    }
    void load(Context context){
        wallpaper=decode(context,"themes/gold-sunset.png");
        prayerArt=decode(context,"themes/gold-prayers.png");
    }
    private static Bitmap decode(Context context,String path){
        try(InputStream in=context.getAssets().open(path)){return BitmapFactory.decodeStream(in);}
        catch(Exception e){AppLog.w("gold artwork unavailable");return null;}
    }
    void resize(float k,float width,float height){
        glass.setShader(new LinearGradient(0,0,width,height,new int[]{0xED29313E,0xF2090D16,0xF5080B11},new float[]{0,0.45f,1},Shader.TileMode.CLAMP));
        header.setTypeface(Ui.semibold);header.setTextSize(25*k);header.setColor(WHITE);
        clock.setTypeface(Ui.displaySemibold);clock.setTextSize(70*k);clock.setColor(WHITE);
        small.setTypeface(Ui.medium);small.setTextSize(22*k);small.setColor(0xE0F0E6D4);
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
        if(now/60000!=minute||Ui.generation!=generation){
            minute=now/60000;generation=Ui.generation;
            date=new java.text.SimpleDateFormat("EEEE d MMMM yyyy",Ui.locale()).format(new java.util.Date(now));
            hijri=hijriFormat.format(new java.util.Date(now));
        }
        fitText(c,date,95*k,91*k,510*k,header);
        fitText(c,hijri,95*k,137*k,510*k,small);
        box.set(745*k,33*k,1165*k,157*k);panel(c,box,60*k,k,0);
        fitText(c,time,box.centerX()-clock.measureText(time)/2,119*k,390*k,clock);

    }
    void text(Canvas c,String value,float left,float baseline,float width,float k,boolean large){
        fitText(c,value,left,baseline,width,large?clock:small);
    }
    private static void fitText(Canvas c,String value,float left,float baseline,float width,Paint p){
        float measured=p.measureText(value);p.setTextScaleX(measured>width?width/measured:1);
        c.drawText(value,left,baseline,p);p.setTextScaleX(1);
    }
    void prayerArt(Canvas c,RectF r,float k){
        if(prayerArt==null)return;
        // Transparent frontal artwork merges with the glass. Fit rather than stretch the Kaaba and minarets.
        float right=r.right-10*k,bottom=r.bottom-28*k;
        float scale=Math.min(r.width()*0.44f/prayerArt.getWidth(),(r.height()-96*k)/prayerArt.getHeight());
        art.set(right-prayerArt.getWidth()*scale,bottom-prayerArt.getHeight()*scale,right,bottom);
        c.drawBitmap(prayerArt,null,art,image);
    }
}
