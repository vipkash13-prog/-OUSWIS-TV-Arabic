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
import androidx.media3.exoplayer.ExoPlayer;
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

    @Override protected void onCreate(Bundle b){
        super.onCreate(b);
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
        player=LivePlayer.create(this);
        player.setVideoSurfaceView(surface);
        player.addListener(new Player.Listener(){
            @Override public void onPlaybackStateChanged(int state){
                if(state==Player.STATE_BUFFERING)status.setText("جارٍ التحميل…");
                if(state==Player.STATE_READY){status.setVisibility(View.GONE);failures=0;}
                if(state==Player.STATE_BUFFERING)status.setVisibility(View.VISIBLE);
            }
            @Override public void onRenderedFirstFrame(){status.setVisibility(View.GONE);pictureUp();}
            @Override public void onIsPlayingChanged(boolean playing){if(playing)pictureUp();}
            @Override public void onVideoSizeChanged(VideoSize size){fit(size);}
            @Override public void onPlayerError(PlaybackException e){fail();}
        });
        playCurrent();
    }
    @Override protected void onStop(){
        leaveChannel();
        main.removeCallbacksAndMessages(null);
        if(player!=null){try{player.release();}catch(Exception ignored){}player=null;}
        started=false;
        if(data!=null)data.shutdown();
        super.onStop();
    }

    private void fit(VideoSize size){
        int[] s=VideoFit.size(size.width,size.height,root.getWidth(),root.getHeight());
        surface.setLayoutParams(new FrameLayout.LayoutParams(s[0],s[1],Gravity.CENTER));
    }

    private void playCurrent(){
        if(player==null||index<0||index>=list.size())return;
        LiveData.Channel ch=list.get(index);
        final int mine=++generation;
        main.removeCallbacks(noteLater);playingSince=0;
        String ext=LivePlayer.extension(ch,attempt);
        player.setMediaItem(LivePlayer.item(account,ch,ext));
        player.setVolume(1f);
        player.prepare();player.setPlayWhenReady(true);
        started=true;
        status.setText("جارٍ الاتصال…");status.setVisibility(View.VISIBLE);
        showLabel(ch);
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
