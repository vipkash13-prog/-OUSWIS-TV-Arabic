package io.ouswis.tv.standalone;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Film posters, small: decoded at the width they are drawn at (RGB_565, about 240 px for the grid), kept as a JPEG on
 * the disk (so a poster seen once is never downloaded again) and in a memory cache of a few MB. The newest request is
 * served first, so a fast scroll does not wait behind posters already scrolled past.
 */
final class PosterCache {
    private PosterCache(){}
    static final int GRID=240,LARGE=480;
    private static final LruCache<String,Bitmap> MEM=new LruCache<String,Bitmap>(memBytes()){
        @Override protected int sizeOf(String k,Bitmap b){return b.getByteCount();}
    };
    private static int memBytes(){
        long max=Runtime.getRuntime().maxMemory();
        return (int)Math.max(6L*1024*1024,Math.min(20L*1024*1024,max/12));
    }
    private static final Set<String> BUSY=new HashSet<>();
    private static final Map<String,Long> FAILED=new HashMap<>();
    private static final long RETRY_MS=10*60_000L;
    private static final ThreadPoolExecutor POOL=new ThreadPoolExecutor(3,3,30,TimeUnit.SECONDS,new LinkedBlockingDeque<Runnable>(){
        @Override public boolean offer(Runnable r){return super.offerFirst(r);}   // newest first
    });
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static volatile File dir;
    private static boolean pruned;

    /** The poster at this width if it is ready; otherwise it is loaded and `done` runs on the UI thread when it arrives. */
    static Bitmap get(String url,int width,Runnable done){
        if(url==null||!url.startsWith("http"))return null;
        final String key=width+"|"+url;
        Bitmap b=MEM.get(key);
        if(b!=null)return b;
        synchronized(BUSY){
            Long at=FAILED.get(key);
            if(at!=null){if(System.currentTimeMillis()-at<RETRY_MS)return null;FAILED.remove(key);}
            if(!BUSY.add(key))return null;
            if(POOL.getQueue().size()>60){   // the oldest asks are dropped: they are asked again if still on screen
                Runnable old=((LinkedBlockingDeque<Runnable>)POOL.getQueue()).pollLast();
                if(old instanceof Job)BUSY.remove(((Job)old).key);
            }
        }
        POOL.execute(new Job(key,url,width,done));
        return null;
    }
    static boolean failed(String url,int width){
        synchronized(BUSY){Long at=FAILED.get(width+"|"+url);return at!=null&&System.currentTimeMillis()-at<RETRY_MS;}
    }

    private static final class Job implements Runnable{
        final String key,url;final int width;final Runnable done;
        Job(String key,String url,int width,Runnable done){this.key=key;this.url=url;this.width=width;this.done=done;}
        @Override public void run(){
            Bitmap loaded=null;
            File f=file(key);
            try{if(f!=null&&f.isFile()&&f.length()>0)loaded=decode(f,width);}catch(Throwable ignored){}
            if(loaded==null){
                try{loaded=download(url,width);}catch(Throwable ignored){}
                if(loaded!=null&&f!=null)write(f,loaded);
            }
            synchronized(BUSY){BUSY.remove(key);if(loaded==null)FAILED.put(key,System.currentTimeMillis());}
            if(loaded!=null){MEM.put(key,loaded);if(done!=null)MAIN.post(done);}
        }
    }

    private static File file(String key){
        try{
            File d=dir;
            if(d==null){android.content.Context c=Prefs.context();if(c==null)return null;d=new File(c.getCacheDir(),"posters");d.mkdirs();dir=d;}
            byte[] h=java.security.MessageDigest.getInstance("SHA-1").digest(key.getBytes("UTF-8"));
            StringBuilder n=new StringBuilder();for(byte x:h)n.append(String.format("%02x",x));
            return new File(d,n+".jpg");
        }catch(Throwable t){return null;}
    }
    private static Bitmap decode(File f,int width){
        BitmapFactory.Options o=new BitmapFactory.Options();o.inPreferredConfig=Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(f.getPath(),o);
    }
    private static void write(File f,Bitmap b){
        try(FileOutputStream out=new FileOutputStream(f)){b.compress(Bitmap.CompressFormat.JPEG,85,out);}
        catch(Throwable t){f.delete();}
        synchronized(BUSY){if(pruned)return;pruned=true;}
        try{   // once per run: about 1500 posters at most (a few tens of MB)
            File[] all=f.getParentFile().listFiles();
            if(all!=null&&all.length>1500){
                java.util.Arrays.sort(all,(x,y)->Long.compare(x.lastModified(),y.lastModified()));
                for(int i=0;i<all.length-1000;i++)all[i].delete();
            }
        }catch(Throwable ignored){}
    }
    private static Bitmap download(String address,int width) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(address).openConnection();
        c.setConnectTimeout(8000);c.setReadTimeout(12000);c.setInstanceFollowRedirects(true);
        try{
            if(c.getResponseCode()!=200)return null;
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            try(InputStream in=c.getInputStream()){
                byte[] buf=new byte[8192];int n;
                while((n=in.read(buf))!=-1){out.write(buf,0,n);if(out.size()>6*1024*1024)return null;}
            }
            byte[] data=out.toByteArray();
            BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;
            BitmapFactory.decodeByteArray(data,0,data.length,o);
            if(o.outWidth<=0||o.outHeight<=0)return null;
            int sample=1;while(o.outWidth/(sample*2)>=width)sample*=2;
            o=new BitmapFactory.Options();o.inSampleSize=sample;o.inPreferredConfig=Bitmap.Config.RGB_565;
            Bitmap b=BitmapFactory.decodeByteArray(data,0,data.length,o);
            if(b==null)return null;
            if(b.getWidth()>width*1.4f){   // still well over: scaled down to the drawn width
                int h=Math.max(1,Math.round(b.getHeight()*(width/(float)b.getWidth())));
                Bitmap s=Bitmap.createScaledBitmap(b,width,h,true);
                if(s!=b)b.recycle();
                b=s;
            }
            return b;
        }finally{c.disconnect();}
    }
}
