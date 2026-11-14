package io.ouswis.tv.standalone;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.source.LoadEventInfo;
import androidx.media3.exoplayer.source.MediaLoadData;
import java.util.ArrayList;
import java.util.List;

/**
 * Full-screen live playback with Media3 ExoPlayer. It holds only the channels of the one section the viewer came from
 * (never the whole catalogue): CH+/CH- or up/down move through that section, Back returns to the list. The channel
 * becomes "last watched" after 10 s of playing. A failed stream is tried as the other format (m3u8 / ts), then again.
 */
public final class PlayerActivity extends Activity {
    static final String EXTRA_ID="id",EXTRA_NAME="name",EXTRA_ICON="icon",EXTRA_NUM="num",EXTRA_EXT="ext",EXTRA_SECTION="section";
    private final Handler main=new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private SurfaceView surface;
    private android.widget.ImageView hold;   // a copy of the last picture, shown over the surface while a reconnect has none
    private TextView label,status;
    private ExoPlayer player;
    private XtreamAccount account;
    private LiveData data;
    private final List<LiveData.Channel> list=new ArrayList<>();
    private int index;
    private int attempt,failures,generation;
    private boolean started;
    // Diagnostics and the freeze watch (see PlayLog): all times are SystemClock.elapsedRealtime
    private static final long FREEZE_MS=8_000;          // no progress for this long: reconnect
    private static final long FIRST_PICTURE_MS=25_000;  // ... but a channel that has not shown a picture yet may still be filling its start buffer
    private static final long HEALTHY_MS=3_000;        // this long of playing resets the waiting between reconnects
    private static final long BUFFERING_STUCK_MS=4_000;
    private static final long HEARTBEAT_MS=60_000;
    static final String EXTRA_MATCH_KEY="matchKey";
    private static final long KEEP_MATCH_MS=60_000;
    private String matchKey="";private String savedChannelId="";
    private long channelStartAt,lastProgressAt,lastPos=-1,lastHeartbeatAt,lastLoadLogAt;
    private final List<Long> freezes=new ArrayList<>();
    private boolean gaveUp;
    // Reconnecting: a live stream that the server closes is joined again at once, with the last picture left on screen
    private static final long STALL_MS=6_000;          // the buffer isn't being filled for this long: reconnect
    private static final long STATUS_DELAY_MS=3_000;   // "loading" shows only if a reconnect or a rebuffer lasts this long
    private static final long CLOSE_GAP_MS=250;        // the old connection is closed this long before the new one opens
    private long reconnectT0,lastBufferedPos=-1,bufferMovedAt,posMovedAt,healthySince;
    private int backoffStep;                           // consecutive reconnects without a healthy run: 0, 1, 2, 4, 8, 10, 10 … seconds of waiting
    private final List<Long> instants=new ArrayList<>();
    private boolean pictured;                          // this channel has shown a picture
    private String currentExt="";
    private int hlsForm;                               // 0: the usual m3u8 address, 1: the older one without /live
    private String pendingStatus;
    private final Runnable showPending=()->{if(pendingStatus!=null){status.setText(pendingStatus);status.setVisibility(View.VISIBLE);}};
    private void statusLater(String text,long ms){pendingStatus=text;main.removeCallbacks(showPending);main.postDelayed(showPending,ms);}
    private void statusNow(String text){pendingStatus=text;main.removeCallbacks(showPending);status.setText(text);status.setVisibility(View.VISIBLE);}
    private void statusClear(){pendingStatus=null;main.removeCallbacks(showPending);status.setVisibility(View.GONE);}
    private final Runnable hideLabel=()->label.setVisibility(View.GONE);
    private long playingSince;   // elapsedRealtime when this channel's picture first came up; 0 until then
    /** "Last channel" and "Recent": only a channel that has played full screen for 10 seconds (the small preview never gets here). */
    private final Runnable noteLater=()->{
        if(playingSince==0)return;
        long left=10_000-(android.os.SystemClock.elapsedRealtime()-playingSince);
        if(left>500){main.postDelayed(this.noteLaterRef(),left);return;}
        noteWatchedNow();
    };
    private Runnable noteLaterRef(){return noteLater;}
    private void noteWatchedNow(){if(index>=0&&index<list.size()&&list.get(index).id.length()>0)LiveData.noteWatched(this,list.get(index));}
    /** The picture is up: start counting (once per channel), from whichever signal the decoder gives first. */
    private void pictureUp(){
        if(playingSince!=0)return;
        playingSince=android.os.SystemClock.elapsedRealtime();
        main.removeCallbacks(noteLater);main.postDelayed(noteLater,10_000);
    }
    /** Called as the channel is left (zap, back, stop): it counts if it had been playing for the 10 seconds. */
    private void leaveChannel(){
        if(playingSince!=0&&android.os.SystemClock.elapsedRealtime()-playingSince>=10_000)noteWatchedNow();
        playingSince=0;
    }

    static android.content.Intent intent(android.content.Context c,LiveData.Channel ch,String section){
        return new android.content.Intent(c,PlayerActivity.class).putExtra(EXTRA_ID,ch.id).putExtra(EXTRA_NAME,ch.name)
                .putExtra(EXTRA_ICON,ch.icon).putExtra(EXTRA_NUM,ch.num).putExtra(EXTRA_EXT,ch.ext).putExtra(EXTRA_SECTION,section==null?"":section);
    }

    @Override public void finish(){super.finish();overridePendingTransition(0,0);}   // goes back at once: the home screen is never seen faded over black
    @Override protected void onCreate(Bundle b){
        super.onCreate(b);
        overridePendingTransition(0,0);
        Ui.init(this);
        getWindow().getDecorView().setSystemUiVisibility(5894);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        account=XtreamAccount.read(this);
        if(account==null){startActivity(new android.content.Intent(this,XtreamActivity.class));finish();return;}
        root=new FrameLayout(this);root.setBackgroundColor(Color.BLACK);
        surface=new SurfaceView(this);
        root.addView(surface,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));
        hold=new android.widget.ImageView(this);hold.setScaleType(android.widget.ImageView.ScaleType.FIT_XY);hold.setVisibility(android.view.View.GONE);
        root.addView(hold,new FrameLayout.LayoutParams(-1,-1));
        label=new TextView(this);label.setTextColor(Color.WHITE);label.setTextSize(30);label.setTypeface(Design.bold());
        label.setBackgroundColor(0xB0000000);label.setPadding(40,22,40,22);label.setGravity(Gravity.CENTER_VERTICAL|Gravity.RIGHT);
        root.addView(label,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
        status=new TextView(this);status.setTextColor(Color.WHITE);status.setTextSize(16);status.setTypeface(Design.regular());
        status.setBackgroundColor(0x99000000);status.setPadding(20,8,20,8);
        FrameLayout.LayoutParams sp=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.LEFT);sp.setMargins(32,28,0,0);root.addView(status,sp);   // small, in the corner: it never covers the picture or stops the retries
        setContentView(root);
        String id=getIntent().getStringExtra(EXTRA_ID);
        LiveData.Channel first=new LiveData.Channel(id==null?"":id,str(EXTRA_NAME),str(EXTRA_ICON),str(EXTRA_NUM),str(EXTRA_EXT).isEmpty()?"m3u8":str(EXTRA_EXT));
        list.add(first);index=0;
        data=new LiveData(this,account);
        matchKey=str(EXTRA_MATCH_KEY);
        final String given=str(EXTRA_SECTION);
        if(given.isEmpty()){
            // Opened from a match: find the section the channel belongs to, so CH+/CH- have a list to move through.
            new Thread(()->{
                final String found=MatchChannels.sectionOf(this,account,first.id);
                // not in any downloaded section (e.g. a channel from "Recent"): the recents list is its section
                main.post(()->{if(!isFinishing())loadSection(found.isEmpty()?LiveData.RECENT:found,first);});
            }).start();
        }else loadSection(given,first);
    }
    private void loadSection(final String sid,final LiveData.Channel first){
        {
            data.loadChannels(sid,()->{
                List<LiveData.Channel> all=data.cached(sid);
                if(all==null)return;
                for(int i=0;i<all.size();i++)if(all.get(i).id.equals(first.id)){
                    LiveData.Channel at=list.get(index);
                    list.clear();list.addAll(all);index=i;
                    if(!at.id.equals(list.get(index).id))index=0;
                    return;
                }
            });
        }
    }
    private String str(String key){String s=getIntent().getStringExtra(key);return s==null?"":s;}

    @Override protected void onStart(){
        super.onStart();
        if(account==null)return;
        player=LivePlayer.create(this,true);   // the only player: the preview's is released first
        player.setVideoSurfaceView(surface);
        channelStartAt=android.os.SystemClock.elapsedRealtime();freezes.clear();instants.clear();gaveUp=false;backoffStep=0;healthySince=0;posMovedAt=android.os.SystemClock.elapsedRealtime();pictured=false;hlsForm=0;
        log("فتح ملء الشاشة · نوع البث: "+LivePlayer.streamType(this));
        logAccountInfo();
        player.addListener(new Player.Listener(){
            @Override public void onPlaybackStateChanged(int state){
                log("الحالة: "+stateName(state)+" · مخزّن "+(player==null?0:player.getTotalBufferedDuration()/1000)+" ث");
                if(state==Player.STATE_ENDED){main.post(()->reconnect("انتهى الملف (ENDED): السيرفر أغلق الاتصال"));return;}   // a live channel never ends
                if(state==Player.STATE_BUFFERING&&pictured)statusLater("جارٍ التحميل…",STATUS_DELAY_MS);   // the last picture stays; the text comes only if it lasts
                if(state==Player.STATE_READY){statusClear();failures=0;}
            }
            @Override public void onRenderedFirstFrame(){releaseHold();statusClear();pictureUp();pictured=true;log("ظهرت أول لقطة");}
            @Override public void onPlaybackSuppressionReasonChanged(int reason){if(reason!=Player.PLAYBACK_SUPPRESSION_REASON_NONE)log("إيقاف مؤقت من النظام (السبب "+reason+")");}
            @Override public void onIsPlayingChanged(boolean playing){
                if(!playing)return;
                pictureUp();
                if(reconnectT0!=0){log("عاد البث بعد "+(android.os.SystemClock.elapsedRealtime()-reconnectT0)+" مللي ثانية من إعادة الاتصال");reconnectT0=0;}
            }
            @Override public void onVideoSizeChanged(VideoSize size){fit(size);}
            @Override public void onPlayerError(PlaybackException e){
                log("توقف: "+describe(e)+" · "+playerState());
                if(e.errorCode==PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW){   // fell behind the live edge: jump back to it, not a failure
                    try{player.seekToDefaultPosition();player.prepare();player.setPlayWhenReady(true);lastProgressAt=android.os.SystemClock.elapsedRealtime();log("عودة إلى البث المباشر: seekToDefaultPosition + prepare");return;}
                    catch(RuntimeException x){log("تعذّر الرجوع للبث المباشر: "+x.getClass().getSimpleName());}
                }
                if(pictured&&dropped(e)){final String code=e.getErrorCodeName();main.post(()->reconnect("انقطع الاتصال أثناء المشاهدة ("+code+")"));return;}   // same address again, not a different format
                fail(e);
            }
        });
        player.addAnalyticsListener(new AnalyticsListener(){
            @Override public void onLoadError(AnalyticsListener.EventTime t,LoadEventInfo info,MediaLoadData data,java.io.IOException error,boolean canceled){
                long now=android.os.SystemClock.elapsedRealtime();
                if(now-lastLoadLogAt<3_000)return;   // a bad stretch would fill the log
                lastLoadLogAt=now;
                int http=httpCode(error);
                log("تعذّر تحميل جزء"+(canceled?" (ملغى)":"")+": "+causes(error)+(http>0?" HTTP "+http:"")+" · "+playerState());
            }
            @Override public void onVideoCodecError(AnalyticsListener.EventTime t,Exception e){log("خطأ decoder للفيديو: "+e.getClass().getSimpleName());}
            @Override public void onAudioCodecError(AnalyticsListener.EventTime t,Exception e){log("خطأ decoder للصوت: "+e.getClass().getSimpleName());}
        });
        playCurrent();
    }
    @Override protected void onStop(){
        leaveChannel();
        main.removeCallbacksAndMessages(null);
        if(player!=null){log("إغلاق المشغّل ("+playerState()+")");try{player.release();}catch(Exception ignored){}LivePlayer.released(player);player=null;}
        started=false;
        if(data!=null)data.shutdown();
        super.onStop();
    }

    private void fit(VideoSize size){
        int[] s=VideoFit.size(size.width,size.height,root.getWidth(),root.getHeight());
        surface.setLayoutParams(new FrameLayout.LayoutParams(s[0],s[1],Gravity.CENTER));
    }

    // MARK: diagnostics

    private long since(){return channelStartAt==0?-1:android.os.SystemClock.elapsedRealtime()-channelStartAt;}
    private void log(String what){PlayLog.add(this,since(),PlayLog.clean(what,account));}
    private static String stateName(int st){
        switch(st){case Player.STATE_IDLE:return "IDLE";case Player.STATE_BUFFERING:return "BUFFERING";case Player.STATE_READY:return "READY";case Player.STATE_ENDED:return "ENDED";}
        return String.valueOf(st);
    }
    private String playerState(){
        if(player==null)return "بلا مشغّل";
        return stateName(player.getPlaybackState())+(player.getPlayWhenReady()?"":" متوقف")+" · موضع "+player.getCurrentPosition()/1000+" ث · مخزّن "+player.getTotalBufferedDuration()/1000+" ث";
    }
    /** The http status inside an error's causes, or 0. */
    private static int httpCode(Throwable t){
        for(int i=0;t!=null&&i<8;i++,t=t.getCause())
            if(t instanceof HttpDataSource.InvalidResponseCodeException)return ((HttpDataSource.InvalidResponseCodeException)t).responseCode;
        return 0;
    }
    private static String causes(Throwable t){
        StringBuilder b=new StringBuilder();
        for(int i=0;t!=null&&i<4;i++,t=t.getCause()){if(b.length()>0)b.append(" ← ");b.append(t.getClass().getSimpleName());}
        return b.toString();
    }
    /** What stopped it, in words: network, buffer, decoder, live window, or the server (with its http status). */
    private static String describe(PlaybackException e){
        int http=httpCode(e);
        String kind;
        switch(e.errorCode){
            case PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW:kind="تأخر عن البث المباشر (BehindLiveWindow)";break;
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED:kind="خطأ شبكة: فشل الاتصال";break;
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT:kind="خطأ شبكة: انتهت المهلة";break;
            case PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS:kind="انقطاع من السيرفر: رمز HTTP "+http;break;
            case PlaybackException.ERROR_CODE_IO_UNSPECIFIED:kind="خطأ إدخال/إخراج";break;
            case PlaybackException.ERROR_CODE_DECODER_INIT_FAILED:case PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED:
            case PlaybackException.ERROR_CODE_DECODING_FAILED:case PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES:
            case PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED:kind="خطأ decoder";break;
            case PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED:case PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED:kind="بث تالف (تحليل)";break;
            default:kind="خطأ";
        }
        return kind+" ["+e.getErrorCodeName()+"] "+causes(e)+(http>0&&e.errorCode!=PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS?" HTTP "+http:"");
    }
    /** The provider's own count of connections, read from user_info; never logs the login. */
    private void logAccountInfo(){
        final XtreamAccount a=account;
        new Thread(()->{
            String text;
            try{
                org.json.JSONObject u=new org.json.JSONObject(XtreamAccount.get(a.api(null,null))).getJSONObject("user_info");
                java.util.List<String> formats=new ArrayList<>();
                Object f=u.opt("allowed_output_formats");
                if(f instanceof org.json.JSONArray)for(int i=0;i<((org.json.JSONArray)f).length();i++)formats.add(String.valueOf(((org.json.JSONArray)f).opt(i)));
                else if(f instanceof String)for(String x:((String)f).split("[,\\s]+"))if(!x.isEmpty())formats.add(x);
                if(!formats.isEmpty())LivePlayer.setAllowed(PlayerActivity.this,formats);
                text="الحساب: max_connections="+u.opt("max_connections")+" active_cons="+u.opt("active_cons")+" status="+u.opt("status")
                        +" · الصيغ المسموحة: "+(formats.isEmpty()?"غير معلنة":android.text.TextUtils.join(",",formats));
            }catch(Exception e){
                text="تعذّر قراءة user_info: "+e.getClass().getSimpleName()+(String.valueOf(e.getMessage()).startsWith("HTTP ")?" "+e.getMessage():"");
            }
            final String line=text;
            main.post(()->{if(!isFinishing())log(line);});
        }).start();
    }
    private final Runnable watch=new Runnable(){@Override public void run(){
        if(player==null||isFinishing())return;
        long now=android.os.SystemClock.elapsedRealtime();
        long pos=player.getCurrentPosition();
        int st=player.getPlaybackState();
        boolean moved=pos!=lastPos;
        if(st==Player.STATE_READY&&player.getPlayWhenReady()&&moved){
            lastProgressAt=now;
            if(healthySince==0)healthySince=now;
            if(now-healthySince>=HEALTHY_MS)backoffStep=0;   // it has played for a while: the next failure starts again at once
        }else if(st!=Player.STATE_READY)healthySince=0;
        if(moved)posMovedAt=now;
        lastPos=pos;
        long bp=player.getBufferedPosition();
        if(bp!=lastBufferedPos){lastBufferedPos=bp;bufferMovedAt=now;}
        // A live server sends at the speed of the picture, so a buffer that stays at 4-6 s while the position moves is a healthy
        // stream. Only two cases are a stopped feed: (1) the end of the buffer has not moved for 6 s and less than a second is
        // left to play, (2) the position has not moved for 4 s while buffering and nothing arrived in those 4 s either.
        // (ENDED is handled by the player's listener.)
        if(pictured&&started&&(st==Player.STATE_READY||st==Player.STATE_BUFFERING)){
            long left=bp-pos;
            if(now-bufferMovedAt>STALL_MS&&left<1000)
                reconnect("توقف الإرسال: نهاية المخزون ثابتة منذ "+(now-bufferMovedAt)/1000+" ث والمتبقي "+Math.max(0,left)+" مللي ثانية");
            else if(st==Player.STATE_BUFFERING&&now-posMovedAt>BUFFERING_STUCK_MS&&now-bufferMovedAt>BUFFERING_STUCK_MS)
                reconnect("توقف الإرسال: تحميل مؤقت بلا تقدّم منذ "+(now-posMovedAt)/1000+" ث");
        }
        if(now-lastHeartbeatAt>=HEARTBEAT_MS){lastHeartbeatAt=now;log("نبض: "+playerState());}
        // opened from a match and still watching this channel after a minute: it is the channel that carries the match
        if(!matchKey.isEmpty()&&pictured&&index>=0&&index<list.size()&&channelStartAt!=0&&now-channelStartAt>KEEP_MATCH_MS){
            LiveData.Channel cur=list.get(index);
            if(!cur.id.equals(savedChannelId)){
                savedChannelId=cur.id;MatchChannels.pickMatch(this,matchKey,cur);
                log("حُفظت "+cur.name+" كقناة المباراة بعد أكثر من دقيقة مشاهدة");
            }
        }
        long freezeLimit=pictured?FREEZE_MS:FIRST_PICTURE_MS;   // the first picture may wait for the larger start buffer
        if(started&&now-lastProgressAt>freezeLimit)frozen(now);
        main.postDelayed(this,1000);
    }};
    /** No progress at all: same as a closed stream, join the channel again (with the growing wait), never give up. */
    private void frozen(long now){
        logAccountInfo();
        reconnect("تجمّد بلا تقدّم ("+playerState()+")");
    }

    /**
     * The server closed the stream, stopped sending or the picture froze: join the same channel again, the first time at
     * once and then after 1, 2, 4, 8 and at most 10 seconds, for as long as the screen is open. Never counted as a failure
     * that ends the tries; a small message in the corner says so. The last picture stays on screen.
     */
    private void reconnect(String why){
        if(player==null||isFinishing())return;
        long now=android.os.SystemClock.elapsedRealtime();
        long wait=backoffStep==0?0:Math.min(10_000L,1000L<<(backoffStep-1));
        backoffStep++;
        reconnectT0=now;
        log((wait>0?"إعادة اتصال بعد انتظار "+wait/1000+" ث: ":"إعادة اتصال فورية: ")+why+" · "+playerState()+" · المحاولة "+backoffStep);
        healthySince=0;
        if(wait>0)statusNow("إعادة الاتصال… المحاولة "+backoffStep);
        restart(wait);
    }

    /** Closes the old connection, waits `gap` ms, then opens the same channel again; no loading text unless it lasts. */
    /** Copies what the surface shows into {@link #hold}: the codec release of a reconnect can blank some TVs' surfaces. */
    private void holdPicture(){
        if(!pictured||hold.getVisibility()==android.view.View.VISIBLE||surface==null||!surface.getHolder().getSurface().isValid()||surface.getWidth()<2)return;
        try{
            final android.graphics.Bitmap bm=android.graphics.Bitmap.createBitmap(Math.max(2,surface.getWidth()/2),Math.max(2,surface.getHeight()/2),android.graphics.Bitmap.Config.ARGB_8888);
            android.view.PixelCopy.request(surface,bm,result->{
                if(result==android.view.PixelCopy.SUCCESS&&!isFinishing()&&!pictured0()){hold.setImageBitmap(bm);hold.setVisibility(android.view.View.VISIBLE);}
            },main);
        }catch(Throwable t){log("تعذّر حفظ آخر لقطة: "+t.getClass().getSimpleName());}
    }
    private boolean pictured0(){return holdDone;}
    private boolean holdDone;
    private void releaseHold(){holdDone=true;if(hold!=null){hold.setVisibility(android.view.View.GONE);hold.setImageDrawable(null);}}
    private void restart(long gap){
        if(player==null||index<0||index>=list.size())return;
        holdDone=false;holdPicture();
        final int mine=++generation;
        final LiveData.Channel ch=list.get(index);
        long now=android.os.SystemClock.elapsedRealtime();
        lastProgressAt=now+gap;lastPos=-1;lastBufferedPos=-1;bufferMovedAt=now+gap;posMovedAt=now+gap;   // the detectors wait while a reconnect waits
        statusLater("جارٍ إعادة الاتصال…",STATUS_DELAY_MS);
        // no stop()/clearMediaItems(): they release the video codec first and the surface can go black; setMediaItem() in openStream
        // replaces the source on the same player (the old connection is closed by it) and the last frame stays until the new one is drawn
        final long t0=reconnectT0==0?now:reconnectT0;
        main.postDelayed(()->{
            if(mine!=generation||player==null)return;
            openStream(ch,currentExt.isEmpty()?LivePlayer.extension(this,ch,attempt):currentExt);
            log("أُعيد فتح الاتصال بعد "+(android.os.SystemClock.elapsedRealtime()-t0)+" مللي ثانية");
        },gap);
    }

    private void openStream(LiveData.Channel ch,String ext){
        currentExt=ext;
        log("تشغيل "+ch.name+" ("+ch.id+") · "+ext+(hlsForm==1?" (عنوان بديل)":"")+(failures>0?" · محاولة "+(failures+1):""));
        player.setMediaItem(LivePlayer.item(account,ch,ext,true,hlsForm==1));
        player.setVolume(1f);
        player.prepare();player.setPlayWhenReady(true);
        lastProgressAt=android.os.SystemClock.elapsedRealtime();bufferMovedAt=lastProgressAt;
    }

    private void playCurrent(){
        if(player==null||index<0||index>=list.size())return;
        final LiveData.Channel ch=list.get(index);
        final int mine=++generation;
        main.removeCallbacks(noteLater);playingSince=0;
        final String ext=LivePlayer.extension(this,ch,attempt);
        statusLater("جارٍ الاتصال…",1_500);   // a new channel: the old picture stays a moment, then the text if it is slow
        showLabel(ch);
        started=true;
        lastProgressAt=android.os.SystemClock.elapsedRealtime();lastPos=-1;bufferMovedAt=lastProgressAt;lastBufferedPos=-1;
        long wait=LivePlayer.settleMs();   // the preview's connection was just closed: it ends at the server first
        Runnable go=()->{if(mine==generation&&player!=null)openStream(ch,ext);};
        if(wait>0)main.postDelayed(go,wait);else go.run();
        main.removeCallbacks(watch);main.postDelayed(watch,1000);
    }
    private void showLabel(LiveData.Channel ch){
        label.setText((ch.num.isEmpty()?String.valueOf(index+1):ch.num)+" · "+ch.name+(list.size()>1?"   ("+(index+1)+"/"+list.size()+")":""));
        label.setVisibility(View.VISIBLE);
        main.removeCallbacks(hideLabel);main.postDelayed(hideLabel,3500);
    }
    /** A network drop or a server close in the middle of watching: not a reason to change format. */
    private static boolean dropped(PlaybackException e){
        switch(e.errorCode){
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED:case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT:
            case PlaybackException.ERROR_CODE_IO_UNSPECIFIED:case PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS:
            case PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED:return true;
        }
        return false;
    }
    private void fail(PlaybackException e){
        LiveData.Channel ch=index>=0&&index<list.size()?list.get(index):null;
        boolean auto=LivePlayer.AUTO.equals(LivePlayer.streamType(this));
        boolean hlsRefused=ch!=null&&"m3u8".equals(currentExt)&&e.errorCode==PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS;
        if(auto&&hlsRefused&&hlsForm==0){   // try the older form of the HLS address before giving up on m3u8
            hlsForm=1;
            log("m3u8 رُفض (HTTP "+httpCode(e)+"): تجربة العنوان البديل");
            final int mine=++generation;
            main.postDelayed(()->{if(mine==generation&&player!=null)openStream(ch,"m3u8");},CLOSE_GAP_MS);
            return;
        }
        if(auto&&hlsRefused){LivePlayer.markNoHls(this,ch.id);log("m3u8 غير متاح لهذه القناة: ts مباشرة من الآن");}
        hlsForm=0;
        failures++;attempt++;
        if(failures>6){statusNow("تعذّر تشغيل هذه القناة");return;}
        statusLater("إعادة الاتصال…",STATUS_DELAY_MS);
        final int mine=generation;
        main.postDelayed(()->{if(mine==generation)playCurrent();},failures<=1?300:2000);
    }
    private void zap(int by){
        if(list.size()<2)return;
        leaveChannel();
        index=(index+by+list.size())%list.size();
        attempt=0;failures=0;
        channelStartAt=android.os.SystemClock.elapsedRealtime();freezes.clear();instants.clear();gaveUp=false;backoffStep=0;healthySince=0;posMovedAt=android.os.SystemClock.elapsedRealtime();pictured=false;hlsForm=0;currentExt="";reconnectT0=0;releaseHold();
        log("تغيير القناة");
        playCurrent();
    }

    @Override public boolean onKeyDown(int key,KeyEvent e){
        switch(key){
            case KeyEvent.KEYCODE_CHANNEL_UP:case KeyEvent.KEYCODE_DPAD_UP:zap(1);return true;
            case KeyEvent.KEYCODE_CHANNEL_DOWN:case KeyEvent.KEYCODE_DPAD_DOWN:zap(-1);return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:case KeyEvent.KEYCODE_ENTER:
                if(index>=0&&index<list.size())showLabel(list.get(index));return true;
        }
        return super.onKeyDown(key,e);
    }
    @Override public void onBackPressed(){finish();}
}
