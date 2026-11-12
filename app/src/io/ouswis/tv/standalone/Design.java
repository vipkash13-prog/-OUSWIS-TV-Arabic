package io.ouswis.tv.standalone;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

/**
 * The redesign's tokens (SPEC section 1) and the few drawing helpers the three new screens share. Everything is drawn
 * in a 1920x1080 reference space (the reference images are 1920x1080) and scaled by {@link #scale}. No blur: only
 * translucent colours, so it stays light on a 32-bit Amlogic box.
 */
final class Design {
    private Design(){}
    static final int BG=0xFF0B0D1A, GLOW_A=0xFF3A2350, GLOW_B=0xFF1A1430;
    static final int SURFACE=0x0FFFFFFF, STROKE=0x1AFFFFFF;
    static final int TEXT=0xFFF4EFE4, MUTED=0x9EF4EFE4;           // muted = 62 %
    static final int GOLD=0xFFE9C46A, GOLD2=0xFFB8862B, LIVE=0xFFEF4444, OK=0xFF4ADE80;
    static final float W=1920f, H=1080f;

    static float scale(int width){return width/W;}

    /** The shared night background: flat bg with a purple glow that fades out from the top centre. */
    static void background(Canvas c,int width,int height,Paint p,float k){
        c.drawColor(BG);
        p.setStyle(Paint.Style.FILL);
        p.setShader(new RadialGradient(width/2f,0,Math.max(width,height)*0.62f,new int[]{GLOW_A,GLOW_B,BG},new float[]{0,0.5f,1},Shader.TileMode.CLAMP));
        p.setAlpha(255);
        c.drawRect(0,0,width,height,p);
        p.setShader(null);
    }

    static Typeface bold(){return Ui.semibold!=null?Ui.semibold:Typeface.DEFAULT_BOLD;}
    static Typeface regular(){return Ui.regular!=null?Ui.regular:Typeface.DEFAULT;}
    static Typeface light(){return Ui.light!=null?Ui.light:Typeface.DEFAULT;}

    static Paint text(int color,float size,Typeface face){
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);p.setTextSize(size);p.setTypeface(face);
        return p;
    }

    /** Text with its right edge at x (Arabic reads from the right). */
    static void right(Canvas c,String s,float x,float y,Paint p){
        p.setTextAlign(Paint.Align.RIGHT);c.drawText(s,x,y,p);
    }
    static void center(Canvas c,String s,float x,float y,Paint p){
        p.setTextAlign(Paint.Align.CENTER);c.drawText(s,x,y,p);
    }
    static void left(Canvas c,String s,float x,float y,Paint p){
        p.setTextAlign(Paint.Align.LEFT);c.drawText(s,x,y,p);
    }
    /** A name that is too wide is drawn smaller (down to 70 %) rather than cut; only then is it cut with an ellipsis. The paint is left as it was. */
    static String fitShrink(String s,Paint p,float width){
        if(s==null)return "";
        float size=p.getTextSize(),w=p.measureText(s);
        if(w<=width)return s;
        float scaled=Math.max(size*0.7f,size*width/w);
        p.setTextSize(scaled);
        String out=p.measureText(s)<=width?s:fit(s,p,width);
        p.setTextSize(size);
        return out;
    }
    /** Shrinks the text a little rather than cutting it, then cuts with an ellipsis if still too wide. */
    static String fit(String s,Paint p,float width){
        if(s==null)return "";
        if(p.measureText(s)<=width)return s;
        int n=p.breakText(s,true,width-p.measureText("…"),null);
        return n<=0?"":s.substring(0,Math.min(n,s.length()))+"…";
    }

    static void card(Canvas c,RectF r,float radius,float k,Paint p,int fill){
        p.setStyle(Paint.Style.FILL);p.setShader(null);p.setColor(fill);
        c.drawRoundRect(r,radius,radius,p);
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(Math.max(1,k));p.setColor(STROKE);
        c.drawRoundRect(r,radius,radius,p);
    }
    static void fill(Canvas c,RectF r,float radius,Paint p,int color){
        p.setStyle(Paint.Style.FILL);p.setShader(null);p.setColor(color);c.drawRoundRect(r,radius,radius,p);
    }
    /** Gold gradient, for the logo, the main buttons and the progress bars. */
    static void gold(Canvas c,RectF r,float radius,Paint p){
        p.setStyle(Paint.Style.FILL);p.setColor(0xFFFFFFFF);   // a shader is multiplied by the paint's alpha
        p.setShader(new LinearGradient(r.left,r.top,r.right,r.bottom,GOLD,GOLD2,Shader.TileMode.CLAMP));
        c.drawRoundRect(r,radius,radius,p);p.setShader(null);
    }
    /**
     * The TV focus state (SPEC "Focus"): a 3 dp white ring, a gold glow around it and a slightly larger card. The caller
     * draws the card at {@link #grown} size first.
     */
    static void focusRing(Canvas c,RectF r,float radius,float k,Paint p){
        p.setStyle(Paint.Style.STROKE);p.setShader(null);
        p.setStrokeWidth(10*k);p.setColor(0x1FE9C46A);c.drawRoundRect(r,radius+4*k,radius+4*k,p);
        p.setStrokeWidth(5*k);p.setColor(0x33E9C46A);c.drawRoundRect(r,radius+2*k,radius+2*k,p);
        p.setStrokeWidth(Math.max(3,4.5f*k));p.setColor(0xFFFFFFFF);c.drawRoundRect(r,radius,radius,p);
    }
    /** scale 1.05 about the centre, written into out. */
    static RectF grown(RectF r,RectF out){
        float dx=r.width()*0.018f,dy=r.height()*0.018f;
        out.set(r.left-dx,r.top-dy,r.right+dx,r.bottom+dy);return out;
    }
    static void liveBadge(Canvas c,float cx,float cy,String label,float size,Paint fillPaint,Paint textPaint){
        textPaint.setTextSize(size);
        float w=textPaint.measureText(label)+size*1.9f,h=size*1.55f;
        RectF b=new RectF(cx-w/2,cy-h/2,cx+w/2,cy+h/2);
        fill(c,b,h/2,fillPaint,LIVE);
        fillPaint.setStyle(Paint.Style.FILL);fillPaint.setColor(0xFFFFFFFF);
        c.drawCircle(b.right-size*0.75f,cy,size*0.2f,fillPaint);
        center(c,label,cx-size*0.25f,cy+size*0.34f,textPaint);
    }
    /** A coloured disc with a letter, for a crest or logo that is missing or still loading. */
    static void initial(Canvas c,String name,float cx,float cy,float radius,Paint p,Paint t){
        if(name!=null)name=name.replace("\u2068","").replace("\u2069","");
        p.setStyle(Paint.Style.FILL);p.setShader(null);p.setColor(0xFF1A1F3A);   // a dark disc and the first letter, never a loud colour
        c.drawCircle(cx,cy,radius,p);
        String ch=name==null||name.isEmpty()?"?":name.substring(0,name.offsetByCodePoints(0,1));
        t.setTextSize(radius*0.95f);t.setColor(TEXT);
        center(c,ch,cx,cy+radius*0.33f,t);
    }
    static final java.util.Locale AR=java.util.Locale.forLanguageTag("ar-u-nu-latn");
    private static final String[] PRAYERS={"الفجر","الظهر","العصر","المغرب","العشاء"};
    static String prayer(int i){return PRAYERS[Math.max(0,Math.min(4,i))];}
    /** Open-Meteo / WMO weather code in Arabic. */
    static String weather(int code,boolean day){
        if(code<0)return "";
        if(code==0)return day?"صافٍ":"صافية";
        if(code==1)return "غالباً صافٍ";
        if(code==2)return "غائم جزئياً";
        if(code==3)return "غائم";
        if(code==45||code==48)return "ضباب";
        if(code>=51&&code<=57)return "رذاذ";
        if(code>=71&&code<=77||code==85||code==86)return "ثلج";
        if(code>=95)return "عواصف رعدية";
        return "مطر";
    }
    private static final ThreadLocal<java.util.Map<String,java.text.SimpleDateFormat>> FORMATS=new ThreadLocal<java.util.Map<String,java.text.SimpleDateFormat>>(){
        @Override protected java.util.Map<String,java.text.SimpleDateFormat> initialValue(){return new java.util.HashMap<>();}
    };
    static String clock(long at,String pattern){
        java.util.Map<String,java.text.SimpleDateFormat> m=FORMATS.get();   // one formatter per pattern and thread, not one per frame
        java.text.SimpleDateFormat f=m.get(pattern);
        if(f==null){f=new java.text.SimpleDateFormat(pattern,AR);f.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Riyadh"));m.put(pattern,f);}
        return f.format(new java.util.Date(at));
    }
}
