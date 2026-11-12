package io.ouswis.tv.standalone;

import android.content.Context;
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

    static ExoPlayer create(Context c){
        DefaultHttpDataSource.Factory http=new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(10_000).setReadTimeoutMs(15_000).setUserAgent("OUSWIS-TV/1.0");
        DefaultLoadControl load=new DefaultLoadControl.Builder()
                .setBufferDurationsMs(4_000,16_000,1_500,3_000).build();
        return new ExoPlayer.Builder(c.getApplicationContext())
                .setMediaSourceFactory(new DefaultMediaSourceFactory(new DefaultDataSource.Factory(c.getApplicationContext(),http)))
                .setLoadControl(load).build();
    }

    /** m3u8 first unless the server says the channel is .ts, per SPEC; attempt 1 flips it (the fallback). */
    static String extension(LiveData.Channel ch,int attempt){
        String preferred=ch.ext!=null&&ch.ext.equalsIgnoreCase("ts")?"ts":"m3u8";
        String other=preferred.equals("ts")?"m3u8":"ts";
        return attempt%2==0?preferred:other;
    }

    static MediaItem item(XtreamAccount a,LiveData.Channel ch,String ext){
        MediaItem.Builder b=new MediaItem.Builder().setUri(a.liveUrl(ch.id,ext));
        if(ext.equals("m3u8"))b.setMimeType(MimeTypes.APPLICATION_M3U8);
        return b.build();
    }
}
