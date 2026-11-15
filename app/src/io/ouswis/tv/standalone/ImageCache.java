package io.ouswis.tv.standalone;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Small logos and crests: downscaled to at most 128 px on decode, held in a small memory cache, loaded off the UI thread. */
final class ImageCache {
    private ImageCache(){}
    private static final LruCache<String,Bitmap> CACHE=new LruCache<>(400);
    private static final Set<String> BUSY=new HashSet<>();
    private static final java.util.Map<String,Long> FAILED=new java.util.HashMap<>();   // url -> when it failed: asked again after RETRY_MS
    private static final long RETRY_MS=15*60_000L;
    private static final ExecutorService POOL=Executors.newFixedThreadPool(3);
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static volatile java.io.File dir;
    private static boolean pruned;

    /** True while this address is known not to load (it is asked for again after a few minutes). */
    static boolean failed(String url){
        if(url==null)return false;
        synchronized(BUSY){Long at=FAILED.get(url);return at!=null&&System.currentTimeMillis()-at<RETRY_MS;}
    }
    /** The bitmap if it is ready; otherwise starts loading (from the disk copy, else the network) and calls `done` on the UI thread when it arrives. */
    static Bitmap get(String url,Runnable done){
        if(url==null||!url.startsWith("http"))return null;
        Bitmap b;
        synchronized(CACHE){b=CACHE.get(url);}
        if(b!=null)return b;
        synchronized(BUSY){
            Long at=FAILED.get(url);
            if(at!=null){if(System.currentTimeMillis()-at<RETRY_MS)return null;FAILED.remove(url);}
            if(!BUSY.add(url))return null;
        }
        POOL.execute(()->{
            Bitmap loaded=null;
            try{loaded=readDisk(url);}catch(Throwable ignored){}
            if(loaded==null){
                try{loaded=download(url);}catch(Throwable ignored){}
                if(loaded!=null)writeDisk(url,loaded);
            }
            synchronized(BUSY){BUSY.remove(url);if(loaded==null)FAILED.put(url,System.currentTimeMillis());}
            if(loaded!=null){synchronized(CACHE){CACHE.put(url,loaded);}if(done!=null)MAIN.post(done);}
        });
        return null;
    }

    // The small pictures (at most 128 px) are also kept on the device: a crest asked for once is not downloaded again.
    private static java.io.File file(String url){
        try{
            java.io.File d=dir;
            if(d==null){
                android.content.Context c=Prefs.context();if(c==null)return null;
                d=new java.io.File(c.getCacheDir(),"crests");d.mkdirs();dir=d;
            }
            byte[] h=java.security.MessageDigest.getInstance("SHA-1").digest(url.getBytes("UTF-8"));
            StringBuilder n=new StringBuilder();for(byte x:h)n.append(String.format("%02x",x));
            return new java.io.File(d,n+".png");
        }catch(Throwable t){return null;}
    }
    private static Bitmap readDisk(String url){
        java.io.File f=file(url);
        return f!=null&&f.isFile()&&f.length()>0?BitmapFactory.decodeFile(f.getPath()):null;
    }
    private static void writeDisk(String url,Bitmap b){
        java.io.File f=file(url);if(f==null)return;
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(f)){b.compress(Bitmap.CompressFormat.PNG,100,out);}
        catch(Throwable t){f.delete();}
        synchronized(BUSY){if(pruned)return;pruned=true;}
        try{   // once per run: no more than about 600 small files
            java.io.File[] all=f.getParentFile().listFiles();
            if(all!=null&&all.length>600){
                java.util.Arrays.sort(all,(x,y)->Long.compare(x.lastModified(),y.lastModified()));
                for(int i=0;i<all.length-400;i++)all[i].delete();
            }
        }catch(Throwable ignored){}
    }
    private static Bitmap download(String address) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(address).openConnection();
        c.setConnectTimeout(8000);c.setReadTimeout(10000);
        try{
            if(c.getResponseCode()!=200)return null;
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            try(InputStream in=c.getInputStream()){
                byte[] buf=new byte[8192];int n;
                while((n=in.read(buf))!=-1){out.write(buf,0,n);if(out.size()>3*1024*1024)return null;}
            }
            byte[] data=out.toByteArray();
            BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;
            BitmapFactory.decodeByteArray(data,0,data.length,o);
            int sample=1;while(o.outWidth/(sample*2)>=128&&o.outHeight/(sample*2)>=128)sample*=2;
            o=new BitmapFactory.Options();o.inSampleSize=sample;
            return BitmapFactory.decodeByteArray(data,0,data.length,o);
        }finally{c.disconnect();}
    }
}
