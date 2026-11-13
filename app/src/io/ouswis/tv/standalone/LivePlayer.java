package io.ouswis.tv.standalone;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

/**
 * One place that builds the app's Media3 ExoPlayer for live channels, for the muted preview and for full screen. Small
 * buffers (a live picture, a TV with 1-2 GB of RAM), redirects between http and https allowed (providers do that), a
 * plain user agent. Pure Java: nothing here is CPU-specific, so the same code runs on armeabi-v7a.
 */
final class LivePlayer {
    private LivePlayer(){}

    private static ExoPlayer current;   // the one player that exists: a second one is never made while another lives
    private static long releasedAt;     // when the last one was let go (its connection closes a moment after)

    /** The small muted preview: short buffers. */
    static ExoPlayer create(Context c){return create(c,false);}

    /**
     * `full`: the full-screen channel, with a longer buffer (15 to 50 s, never more than about 24 or 48 MB) and a short start (1 s, 2.5 s after a stall).
     * Whatever player still exists is released first: the provider's account may allow only one connection.
     */
    static synchronized ExoPlayer create(Context c,boolean full){
        releaseAll(c,"a new player is being made");
        DefaultHttpDataSource.Factory http=new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(10_000).setReadTimeoutMs(15_000).setUserAgent("OUSWIS-TV/1.0");
        DefaultLoadControl.Builder lb=new DefaultLoadControl.Builder();
        if(full){
            ActivityManager am=(ActivityManager)c.getSystemService(Context.ACTIVITY_SERVICE);
            int mb=am!=null&&am.isLowRamDevice()?24:48;
            lb.setBufferDurationsMs(15_000,50_000,1_000,2_500).setTargetBufferBytes(mb*1024*1024).setPrioritizeTimeOverSizeThresholds(false);
        }else lb.setBufferDurationsMs(4_000,16_000,1_500,3_000);
        current=new ExoPlayer.Builder(c.getApplicationContext())
                .setMediaSourceFactory(new DefaultMediaSourceFactory(new DefaultDataSource.Factory(c.getApplicationContext(),http)))
                .setLoadControl(lb.build()).build();
        return current;
    }

    /** Lets go of the existing player, if any (its owner may still hold it: its calls after release are caught there). */
    static synchronized void releaseAll(Context c,String why){
        if(current==null)return;
        try{current.release();}catch(Exception ignored){}
        current=null;releasedAt=android.os.SystemClock.elapsedRealtime();
        if(c!=null)PlayLog.add(c,-1,"سُلِّم مشغّل سابق: "+why);
    }
    /** Called by an owner that released its own player. */
    static synchronized void released(ExoPlayer p){
        if(p==current){current=null;releasedAt=android.os.SystemClock.elapsedRealtime();}
    }
    /** Milliseconds to wait before opening a new connection: the one just closed is given a moment to end at the server. */
    static synchronized long settleMs(){
        long since=android.os.SystemClock.elapsedRealtime()-releasedAt;
        return releasedAt==0||since>=600?0:600-since;
    }

    // MARK: stream type (Settings > سجل التشغيل): "auto" = the server's choice, then the other on a failure; or only m3u8 / only ts
    static final String AUTO="auto",HLS="m3u8",TS="ts";
    private static SharedPreferences prefs(Context c){return c.getApplicationContext().getSharedPreferences("playback",Context.MODE_PRIVATE);}
    static String streamType(Context c){String v=prefs(c).getString("stream",AUTO);return HLS.equals(v)||TS.equals(v)?v:AUTO;}
    static void setStreamType(Context c,String v){prefs(c).edit().putString("stream",v).commit();}
    static String nextStreamType(Context c){String v=streamType(c);return AUTO.equals(v)?HLS:HLS.equals(v)?TS:AUTO;}

    /** m3u8 first unless the server says the channel is .ts, per SPEC; attempt 1 flips it (the fallback). */
    static String extension(Context c,LiveData.Channel ch,int attempt){
        String forced=streamType(c);
        if(!AUTO.equals(forced))return forced;   // a chosen type is never flipped: the test is meant to be clean
        java.util.Set<String> ok=allowed(c);
        boolean hls=ok==null||ok.contains(HLS),ts=ok==null||ok.contains(TS);
        if(hls&&!ts)return HLS;
        if(ts&&(!hls||noHls(c,ch.id)))return TS;   // the account has no m3u8, or this channel refused it before: ts straight away
        return extension(ch,attempt);
    }

    // MARK: what the account allows (user_info.allowed_output_formats) and the channels that refused m3u8
    static java.util.Set<String> allowed(Context c){
        String v=prefs(c).getString("allowed",null);
        if(v==null||v.isEmpty())return null;
        java.util.Set<String> out=new java.util.HashSet<>();
        for(String f:v.split(","))if(!f.trim().isEmpty())out.add(f.trim().toLowerCase(java.util.Locale.US));
        return out.isEmpty()?null:out;
    }
    static void setAllowed(Context c,java.util.Collection<String> formats){
        StringBuilder b=new StringBuilder();
        for(String f:formats){if(b.length()>0)b.append(',');b.append(f.trim().toLowerCase(java.util.Locale.US));}
        prefs(c).edit().putString("allowed",b.toString()).commit();
    }
    private static String noHlsIds(Context c){return prefs(c).getString("nohls","");}
    static boolean noHls(Context c,String id){return (","+noHlsIds(c)+",").contains(","+id+",");}
    static void markNoHls(Context c,String id){
        if(noHls(c,id))return;
        String all=noHlsIds(c);
        if(all.length()>2000)all=all.substring(all.indexOf(',',all.length()/2)+1);   // a long list drops its oldest half
        prefs(c).edit().putString("nohls",(all.isEmpty()?"":all+",")+id).commit();
    }
    static String extension(LiveData.Channel ch,int attempt){
        String preferred=ch.ext!=null&&ch.ext.equalsIgnoreCase("ts")?"ts":"m3u8";
        String other=preferred.equals("ts")?"m3u8":"ts";
        return attempt%2==0?preferred:other;
    }

    static MediaItem item(XtreamAccount a,LiveData.Channel ch,String ext){return item(a,ch,ext,false,false);}

    /** `full`: an HLS channel stays about 5 to 8 seconds behind the live edge (target 6.5 s) instead of the server's default. */
    static MediaItem item(XtreamAccount a,LiveData.Channel ch,String ext,boolean full,boolean alt){
        MediaItem.Builder b=new MediaItem.Builder().setUri(alt?a.liveUrlAlt(ch.id,ext):a.liveUrl(ch.id,ext));
        if(ext.equals("m3u8")){
            b.setMimeType(MimeTypes.APPLICATION_M3U8);
            if(full)b.setLiveConfiguration(new MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(6_500).setMinOffsetMs(5_000).setMaxOffsetMs(8_000).build());
        }
        return b.build();
    }
}
