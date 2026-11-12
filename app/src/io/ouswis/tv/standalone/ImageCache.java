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
    private static final LruCache<String,Bitmap> CACHE=new LruCache<>(160);
    private static final Set<String> BUSY=new HashSet<>(),FAILED=new HashSet<>();
    private static final ExecutorService POOL=Executors.newFixedThreadPool(3);
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    /** The bitmap if it is ready; otherwise starts loading and calls `done` on the UI thread when it arrives. */
    static Bitmap get(String url,Runnable done){
        if(url==null||!url.startsWith("http"))return null;
        Bitmap b;
        synchronized(CACHE){b=CACHE.get(url);}
        if(b!=null)return b;
        synchronized(BUSY){if(FAILED.contains(url)||!BUSY.add(url))return null;}
        POOL.execute(()->{
            Bitmap loaded=null;
            try{loaded=download(url);}catch(Throwable ignored){}
            synchronized(BUSY){BUSY.remove(url);if(loaded==null)FAILED.add(url);}
            if(loaded!=null){synchronized(CACHE){CACHE.put(url,loaded);}MAIN.post(done);}
        });
        return null;
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
