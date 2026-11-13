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
    private TextView label,status;
    private ExoPlayer player;
    private XtreamAccount account;
    private LiveData data;
    private final List<LiveData.Channel> list=new ArrayList<>();
    private int index;
    private int attempt,failures,generation;
    private boolean started;
    // Diagnostics and the freeze watch (see PlayLog): all times are SystemClock.elapsedRealtime
    private static final long FREEZE_MS=8_000;          // no progress for this long: prepare the same channel again
    private static final int FREEZE_TRIES=3;            // at most this many restarts ...
    private static final long FREEZE_WINDOW_MS=120_000; // ... within this time, then a message
    private long channelStartAt,lastProgressAt,lastPos=-1,lastHeartbeatAt,lastLoadLogAt;
    private final List<Long> freezes=new ArrayList<>();
    private boolean gaveUp;
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
        label=new TextView(this);label.setTextColor(Color.WHITE);label.setTextSize(30);label.setTypeface(Design.bold());
        label.setBackgroundColor(0xB0000000);label.setPadding(40,22,40,22);label.setGravity(Gravity.CENTER_VERTICAL|Gravity.RIGHT);
        root.addView(label,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
        status=new TextView(this);status.setTextColor(Color.WHITE);status.setTextSize(26);status.setTypeface(Design.regular());
        status.setBackgroundColor(0x99000000);status.setPadding(36,18,36,18);
        FrameLayout.LayoutParams sp=new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER);root.addView(status,sp);
        setContentView(root);
        String id=getIntent().getStringExtra(EXTRA_ID);
        LiveData.Channel first=new LiveData.Channel(id==null?"":id,str(EXTRA_NAME),str(EXTRA_ICON),str(EXTRA_NUM),str(EXTRA_EXT).isEmpty()?"m3u8":str(EXTRA_EXT));
        list.add(first);index=0;
        data=new LiveData(this,account);
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
        channelStartAt=android.os.SystemClock.elapsedRealtime();freezes.clear();gaveUp=false;
        log("فتح ملء الشاشة · نوع البث: "+LivePlayer.streamType(this));
        logAccountInfo();
        player.addListener(new Player.Listener(){
            @Override public void onPlaybackStateChanged(int state){
                log("الحالة: "+stateName(state)+" · مخزّن "+(player==null?0:player.getTotalBufferedDuration()/1000)+" ث");
                if(state==Player.STATE_BUFFERING)status.setText("جارٍ التحميل…");
                if(state==Player.STATE_READY){status.setVisibility(View.GONE);failures=0;}
                if(state==Player.STATE_BUFFERING)status.setVisibility(View.VISIBLE);
            }
            @Override public void onRenderedFirstFrame(){status.setVisibility(View.GONE);pictureUp();log("ظهرت أول لقطة");}
            @Override public void onPlaybackSuppressionReasonChanged(int reason){if(reason!=Player.PLAYBACK_SUPPRESSION_REASON_NONE)log("إيقاف مؤقت من النظام (السبب "+reason+")");}
            @Override public void onIsPlayingChanged(boolean playing){if(playing)pictureUp();}
            @Override public void onVideoSizeChanged(VideoSize size){fit(size);}
            @Override public void onPlayerError(PlaybackException e){
                log("توقف: "+describe(e)+" · "+playerState());
                if(e.errorCode==PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW){   // fell behind the live edge: jump back to it, not a failure
                    try{player.seekToDefaultPosition();player.prepare();player.setPlayWhenReady(true);lastProgressAt=android.os.SystemClock.elapsedRealtime();log("عودة إلى البث المباشر: seekToDefaultPosition + prepare");return;}
                    catch(RuntimeException x){log("تعذّر الرجوع للبث المباشر: "+x.getClass().getSimpleName());}
                }
                fail();
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
                text="الحساب: max_connections="+u.opt("max_connections")+" active_cons="+u.opt("active_cons")+" status="+u.opt("status");
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
        if(player.getPlaybackState()==Player.STATE_READY&&player.getPlayWhenReady()&&pos!=lastPos)lastProgressAt=now;
        lastPos=pos;
        if(now-lastHeartbeatAt>=15_000){lastHeartbeatAt=now;log("نبض: "+playerState());}
        if(!gaveUp&&started&&now-lastProgressAt>FREEZE_MS)frozen(now);
        main.postDelayed(this,1000);
    }};
    /** No progress for FREEZE_MS: prepare the same channel again, up to FREEZE_TRIES times in FREEZE_WINDOW_MS, then say so. */
    private void frozen(long now){
        for(int i=freezes.size()-1;i>=0;i--)if(now-freezes.get(i)>FREEZE_WINDOW_MS)freezes.remove(i);
        if(freezes.size()>=FREEZE_TRIES){
            gaveUp=true;
            log("تجمّد ولم تنفع "+FREEZE_TRIES+" محاولات: "+playerState());
            status.setText("توقّف البث. جرّب قناة أخرى أو غيّر نوع البث من الإعدادات > سجل التشغيل");status.setVisibility(View.VISIBLE);
            return;
        }
        freezes.add(now);
        log("تجمّد "+FREEZE_MS/1000+" ث بلا تقدّم ("+playerState()+") — إعادة تحضير "+freezes.size()+"/"+FREEZE_TRIES);
        logAccountInfo();
        playCurrent();
    }

    private void playCurrent(){
        if(player==null||index<0||index>=list.size())return;
        LiveData.Channel ch=list.get(index);
        final int mine=++generation;
        main.removeCallbacks(noteLater);playingSince=0;
        final String ext=LivePlayer.extension(this,ch,attempt);
        status.setText("جارٍ الاتصال…");status.setVisibility(View.VISIBLE);
        showLabel(ch);
        started=true;
        lastProgressAt=android.os.SystemClock.elapsedRealtime();lastPos=-1;
        long wait=LivePlayer.settleMs();   // the preview's connection was just closed: it ends at the server first
        Runnable go=()->{
            if(mine!=generation||player==null)return;
            log("تشغيل "+ch.name+" ("+ch.id+") · "+ext+(attempt>0?" · محاولة "+(attempt+1):""));
            player.setMediaItem(LivePlayer.item(account,ch,ext,true));
            player.setVolume(1f);
            player.prepare();player.setPlayWhenReady(true);
            lastProgressAt=android.os.SystemClock.elapsedRealtime();
        };
        if(wait>0)main.postDelayed(go,wait);else go.run();
        main.removeCallbacks(watch);main.postDelayed(watch,1000);
    }
    private void showLabel(LiveData.Channel ch){
        label.setText((ch.num.isEmpty()?String.valueOf(index+1):ch.num)+" · "+ch.name+(list.size()>1?"   ("+(index+1)+"/"+list.size()+")":""));
        label.setVisibility(View.VISIBLE);
        main.removeCallbacks(hideLabel);main.postDelayed(hideLabel,3500);
    }
    private void fail(){
        failures++;attempt++;
        if(failures>6){status.setText("تعذّر تشغيل هذه القناة");status.setVisibility(View.VISIBLE);return;}
        status.setText("إعادة الاتصال…");status.setVisibility(View.VISIBLE);
        final int mine=generation;
        main.postDelayed(()->{if(mine==generation)playCurrent();},failures<=1?300:2000);
    }
    private void zap(int by){
        if(list.size()<2)return;
        leaveChannel();
        index=(index+by+list.size())%list.size();
        attempt=0;failures=0;
        channelStartAt=android.os.SystemClock.elapsedRealtime();freezes.clear();gaveUp=false;
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
