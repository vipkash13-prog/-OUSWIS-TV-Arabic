package io.ouswis.tv.standalone;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.Intent;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.net.Uri;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;


/** Remote-friendly, live-only Xtream Codes player. Credentials never enter the source repository. */
public final class XtreamActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayList<XtreamCatalog.Entry> channels = new ArrayList<>();
    private volatile int catalogGeneration;
    private Thread catalogThread;
    private String catalogWarning="",catalogProgress="",activeAccount="";
    private boolean catalogLoading;
    private Runnable catalogFilter;
    private TextView catalogMessage;
    private XtreamCatalog.Result pendingCatalog;
    private boolean pendingComplete;
    private LinearLayout root;
    private VideoView video;
    private TextView status;
    private String server, user, pass;
    private int current = -1;
    private String channelSearch="";
    private TextView channelLabel;
    private FrameLayout player;
    private int picturePercent;
    private int playbackGeneration;
    private long renderedAt;
    private Runnable recovery, playbackTimeout;
    private boolean playbackFailed, foreground, autoStarted;
    private final Runnable hideLabel = () -> { if (channelLabel != null) channelLabel.setVisibility(View.GONE); };

    /** Explicit fit sizing also covers streams whose dimensions arrive after preparation. */
    private static final class FitVideoView extends VideoView {
        private int frameWidth, frameHeight;
        FitVideoView(android.content.Context context) { super(context); }
        void frameSize(int width, int height) {
            frameWidth = width;
            frameHeight = height;
            requestLayout();
        }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int[] size = VideoFit.size(frameWidth, frameHeight,
                    View.MeasureSpec.getSize(widthSpec), View.MeasureSpec.getSize(heightSpec));
            setMeasuredDimension(size[0], size[1]);
        }
    }
    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().getDecorView().setSystemUiVisibility(5894);
        server=getPreferences(0).getString("server","");
        user=getPreferences(0).getString("user","");
        pass=getPreferences(0).getString("pass","");
        picturePercent=Math.max(80,Math.min(100,getPreferences(0).getInt("picturePercent",100)));
        if (server.isEmpty() || user.isEmpty() || pass.isEmpty()) login(); else load();
    }
    private TextView title(String text, int size) {
        TextView t=new TextView(this); t.setText(text); t.setTextColor(Ui.pageInk(this));
        t.setTextSize(size); t.setPadding(24,16,24,16); return t;
    }
    private void shell() {
        root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(CardBackdrop.async(this,"themes/card-live.jpg",Ui.pageDark(this),0)); setContentView(root);
    }
    private void login() {
        // Editing an account must detach the old catalogue job from the login screen.
        catalogGeneration++;if(catalogThread!=null)catalogThread.interrupt();
        channels.clear();activeAccount="";pendingCatalog=null;
        catalogFilter=null;catalogMessage=null;catalogLoading=false;autoStarted=false;
        shell(); root.addView(title(Ui.tr("Live TV")+"  |  Xtream Codes",26));
        LinearLayout form=new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(40,15,40,15); root.addView(form);
        EditText host=field(form,"Server URL (http://...:port)",server);
        EditText username=field(form,"Username",user);
        EditText password=field(form,"Password",pass);
        password.setInputType(129);
        Button connect=new Button(this); connect.setText("Connect / اتصال"); form.addView(connect);
        // The remote's arrows must walk host, username, password, Connect in order, and OK/Next on the keyboard too.
        host.setId(View.generateViewId());username.setId(View.generateViewId());
        password.setId(View.generateViewId());connect.setId(View.generateViewId());
        host.setNextFocusDownId(username.getId());username.setNextFocusUpId(host.getId());
        username.setNextFocusDownId(password.getId());password.setNextFocusUpId(username.getId());
        password.setNextFocusDownId(connect.getId());connect.setNextFocusUpId(password.getId());
        host.setImeOptions(EditorInfo.IME_ACTION_NEXT|EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        username.setImeOptions(EditorInfo.IME_ACTION_NEXT|EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        password.setImeOptions(EditorInfo.IME_ACTION_DONE|EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        host.setOnEditorActionListener((v,a,e)->{username.requestFocus();return true;});
        username.setOnEditorActionListener((v,a,e)->{password.requestFocus();return true;});
        password.setOnEditorActionListener((v,a,e)->{
            InputMethodManager ime=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
            if(ime!=null)ime.hideSoftInputFromWindow(password.getWindowToken(),0);
            connect.requestFocus();return true;
        });
        connect.setOnClickListener(v->{
            String h=host.getText().toString().trim().replaceAll("/+$","");
            if (!h.startsWith("http://")&&!h.startsWith("https://")) { Toast.makeText(this,"Enter http:// or https://",Toast.LENGTH_LONG).show(); return; }
            server=h; user=username.getText().toString().trim(); pass=password.getText().toString();
            if (user.isEmpty()||pass.isEmpty()) return;
            getPreferences(0).edit().putString("server",server).putString("user",user).putString("pass",pass).apply();
            load();
        });
        host.requestFocus();
    }
    private EditText field(LinearLayout form,String hint,String value) {
        EditText e=new EditText(this); e.setSingleLine(true); e.setHint(hint); e.setText(value);
        e.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setTextColor(Ui.pageInk(this)); e.setHintTextColor(Ui.pageMuted(this)); form.addView(e); return e;
    }
    private String enc(String s) throws Exception {return URLEncoder.encode(s,"UTF-8");}
    private String request(String address) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(address).openConnection();
        c.setConnectTimeout(12000); c.setReadTimeout(15000); c.setInstanceFollowRedirects(true);
        try {
            if(c.getResponseCode()!=200) throw new Exception("HTTP "+c.getResponseCode());
            BufferedReader r=new BufferedReader(new InputStreamReader(c.getInputStream(),"UTF-8"));
            StringBuilder s=new StringBuilder(); String line;
            while((line=r.readLine())!=null) s.append(line);
            r.close(); return s.toString();
        } finally {c.disconnect();}
    }
    private void catalogMessage() {
        String text=catalogLoading?"تحديث القنوات في الخلفية / Updating channels… "+catalogProgress:catalogWarning;
        if(catalogMessage!=null){
            catalogMessage.setText(text);
            catalogMessage.setVisibility(text.isEmpty()?View.GONE:View.VISIBLE);
        }
        if(channels.isEmpty()&&status!=null)status.setText(text.isEmpty()?"تحميل القنوات / Loading channels…":text);
    }
    private void applyCatalog(XtreamCatalog.Result result,boolean complete) {
        boolean authoritative=complete&&result.failedGroups==0&&!result.groupsUnavailable;
        if(authoritative){channels.clear();channels.addAll(result.channels);}
        else{
            XtreamCatalog.Result merged=XtreamCatalog.accumulate(channels,result);
            channels.clear();channels.addAll(merged.channels);
        }
    }
    private void acceptCatalog(XtreamCatalog.Result result,boolean complete) {
        catalogLoading=!complete;
        catalogWarning=result.failedGroups>0?"تعذّر تحميل "+result.failedGroups+" مجموعات / Some groups failed to load"
                :result.groupsUnavailable?"تعذّر تحميل أسماء المجموعات / Group names unavailable":"";
        if(current>=0){
            // Keep the playback list and its numbering unchanged until the viewer returns.
            pendingCatalog=result;pendingComplete=complete;return;
        }
        applyCatalog(result,complete);
        if(catalogFilter==null)showChannels();else{catalogFilter.run();catalogMessage();}
        autoPlay();
    }
    /** Entering Live TV plays the last watched channel (or the first) at once; Back shows the list. */
    private void autoPlay() {
        if(autoStarted||channels.isEmpty()||current>=0||!getPreferences(0).getBoolean("autoplay",true))return;
        autoStarted=true;
        String last=getPreferences(0).getString("lastChannel","");
        int index=0;
        for(int i=0;i<channels.size();i++)if(channels.get(i).id.equals(last)){index=i;break;}
        play(index);
    }
    private void load(){load(false);}
    private void load(boolean force) {
        if(catalogThread!=null)catalogThread.interrupt();
        final int generation=++catalogGeneration;
        final String base=server,username=user,password=pass;
        final String key;
        try{key=XtreamCatalog.accountKey(base,username,password);}catch(Exception e){return;}
        if(!key.equals(activeAccount)){
            activeAccount=key;channels.clear();pendingCatalog=null;catalogFilter=null;catalogMessage=null;
        }
        catalogLoading=true;catalogWarning="";catalogProgress="";
        if(channels.isEmpty()){
            catalogFilter=null;catalogMessage=null;
            shell();status=title("تحميل القنوات / Loading channels…",22);root.addView(status);
        }else catalogMessage();
        File cache=new File(getFilesDir(),"xtream-catalog-cache.json");
        catalogThread=new Thread(()->{
            try {
                XtreamCatalog.Cached saved=XtreamCatalog.readCache(cache,key);
                boolean fresh=saved!=null&&saved.fresh(System.currentTimeMillis())&&!force;
                if(saved!=null)main.post(()->{
                    if(generation==catalogGeneration)acceptCatalog(saved.result,fresh);
                });
                if(fresh)return;
                String endpoint=base+"/player_api.php?username="+enc(username)+"&password="+enc(password);
                JSONObject account=new JSONObject(request(endpoint));
                JSONObject info=account.optJSONObject("user_info");
                if(info==null||!"Active".equalsIgnoreCase(info.optString("status")))throw new Exception("Account is not active");
                final long[] checkpointAt={0};
                XtreamCatalog.Result result=XtreamCatalog.load((action,category)->{
                    if(generation!=catalogGeneration)throw new InterruptedException();
                    return new JSONArray(request(endpoint+"&action="+action+(category==null?"":"&category_id="+enc(category))));
                },(done,total)->main.post(()->{
                    if(generation==catalogGeneration){catalogProgress=done+"/"+total;catalogMessage();}
                }),(snapshot,complete)->{
                    if(generation!=catalogGeneration)return;
                    main.post(()->{if(generation==catalogGeneration)acceptCatalog(snapshot,complete);});
                    long now=System.currentTimeMillis();
                    if((saved==null||!saved.complete)&&!snapshot.channels.isEmpty()
                            &&(checkpointAt[0]==0||now-checkpointAt[0]>=10_000L)){
                        checkpointAt[0]=now;
                        XtreamCatalog.Result checkpoint=saved==null?snapshot:XtreamCatalog.accumulate(saved.result.channels,snapshot);
                        try{XtreamCatalog.writeCache(cache,key,checkpoint,false,now);}catch(Exception ignored){}
                    }
                });
                if(generation==catalogGeneration&&result.failedGroups==0&&!result.groupsUnavailable)
                    try{XtreamCatalog.writeCache(cache,key,result,true,System.currentTimeMillis());}catch(Exception ignored){}
            }catch(InterruptedException ignored){Thread.currentThread().interrupt();}
            catch(Exception e){main.post(()->{
                if(generation!=catalogGeneration)return;
                catalogLoading=false;
                catalogWarning="تعذّر تحديث القنوات • ↻ لإعادة المحاولة / Couldn't refresh channels";
                if(!channels.isEmpty()||current>=0){catalogMessage();return;}
                status.setText(catalogWarning);
                Button retry=new Button(this);retry.setText("إعادة المحاولة / Retry");root.addView(retry);retry.setOnClickListener(v->load(true));
                Button account=new Button(this);account.setText("تعديل الحساب / Edit account");root.addView(account);account.setOnClickListener(v->login());
            });}
        },"Xtream-catalog");
        catalogThread.start();
    }
    private void showChannels() {
        String selected=current>=0&&current<channels.size()?channels.get(current).id:null;
        stopPlayer();current=-1;
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if(pendingCatalog!=null){applyCatalog(pendingCatalog,pendingComplete);pendingCatalog=null;}
        shell();
        LinearLayout header=new LinearLayout(this); root.addView(header);
        TextView heading=title("البث المباشر  |  Live TV ("+channels.size()+")",22);
        header.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
        Button reload=new Button(this);reload.setText("↻");
        reload.setContentDescription("تحديث القنوات / Reload channels");header.addView(reload);
        reload.setOnClickListener(v->load(true));
        Button account=new Button(this);account.setText("Account");header.addView(account);
        catalogMessage=title("",14);root.addView(catalogMessage);catalogMessage();
        account.setOnClickListener(v->{stopPlayer();login();});
        LinearLayout searchRow=new LinearLayout(this);
        searchRow.setPadding(24,0,24,8);
        root.addView(searchRow);
        EditText search=new EditText(this);
        search.setId(View.generateViewId());
        search.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setSingleLine(true);
        search.setHint(Ui.tr("Search"));
        search.setTextColor(Ui.pageInk(this));search.setHintTextColor(Ui.pageMuted(this));
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH|EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        search.setText(channelSearch);
        searchRow.addView(search,new LinearLayout.LayoutParams(0,-2,1));
        Button clear=new Button(this);clear.setText("×");
        clear.setContentDescription("مسح البحث / Clear search");
        searchRow.addView(clear,new LinearLayout.LayoutParams(-2,-2));
        ListView list=new ListView(this);list.setId(View.generateViewId());
        root.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        TextView empty=title("لا توجد قنوات مطابقة / No matching channels",20);
        empty.setGravity(Gravity.CENTER);
        empty.setVisibility(View.GONE);
        root.addView(empty,new LinearLayout.LayoutParams(-1,0,1));
        list.setEmptyView(empty);
        ArrayList<String> names=new ArrayList<>();
        // Display positions change while filtering; playback always uses the original channel index.
        ArrayList<String> visible=new ArrayList<>();
        ArrayAdapter<String> adapter=new ArrayAdapter<String>(this,android.R.layout.simple_list_item_1,names) {
            @Override public View getView(int position,View recycled,android.view.ViewGroup parent) {
                TextView row=(TextView)super.getView(position,recycled,parent);
                row.setTextColor(Ui.pageInk(XtreamActivity.this));
                return row;
            }
        };
        list.setAdapter(adapter);
        Runnable filter=()->{
            int row=list.getSelectedItemPosition();
            String keep=row>=0&&row<visible.size()?visible.get(row):selected;
            String query=XtreamCatalog.normalize(channelSearch);
            names.clear();visible.clear();
            for(int i=0;i<channels.size();i++){
                XtreamCatalog.Entry channel=channels.get(i);
                if(channel.matches(query)){names.add(channel.name);visible.add(channel.id);}
            }
            adapter.notifyDataSetChanged();
            heading.setText("البث المباشر  |  Live TV ("+visible.size()+"/"+channels.size()+")");
            list.setSelection(Math.max(0,visible.indexOf(keep)));
        };
        search.addTextChangedListener(new TextWatcher(){
            @Override public void beforeTextChanged(CharSequence text,int start,int count,int after){}
            @Override public void onTextChanged(CharSequence text,int start,int before,int count){
                channelSearch=text.toString();filter.run();
            }
            @Override public void afterTextChanged(Editable text){}
        });
        Runnable results=()->{
            InputMethodManager ime=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
            if(ime!=null)ime.hideSoftInputFromWindow(search.getWindowToken(),0);
            if(!visible.isEmpty())list.requestFocus();
        };
        search.setOnEditorActionListener((view,action,event)->{
            if(action==EditorInfo.IME_ACTION_SEARCH||action==EditorInfo.IME_ACTION_DONE
                    ||(event!=null&&event.getKeyCode()==KeyEvent.KEYCODE_ENTER)){
                results.run();return true;
            }
            return false;
        });
        search.setOnKeyListener((view,key,event)->{
            if(key==KeyEvent.KEYCODE_DPAD_DOWN&&event.getAction()==KeyEvent.ACTION_DOWN){
                results.run();return true;
            }
            return false;
        });
        clear.setOnClickListener(view->{search.setText("");search.requestFocus();});
        search.setNextFocusDownId(list.getId());list.setNextFocusUpId(search.getId());
        reload.setId(View.generateViewId());account.setId(View.generateViewId());clear.setId(View.generateViewId());
        reload.setNextFocusRightId(account.getId());account.setNextFocusLeftId(reload.getId());
        reload.setNextFocusDownId(search.getId());account.setNextFocusDownId(search.getId());
        search.setNextFocusUpId(reload.getId());search.setNextFocusRightId(clear.getId());
        clear.setNextFocusLeftId(search.getId());clear.setNextFocusDownId(list.getId());
        clear.setNextFocusUpId(account.getId());
        list.setOnItemClickListener((parent,view,pos,id)->{
            if(pos>=0&&pos<visible.size()){
                String channelId=visible.get(pos);
                for(int i=0;i<channels.size();i++)if(channels.get(i).id.equals(channelId)){results.run();play(i);break;}
            }
        });
        catalogFilter=filter;filter.run();
        if(visible.isEmpty())search.requestFocus();else list.requestFocus();
    }

    private String streamEvent;
    private void stopPlayer() {
        if(streamEvent!=null){IslandEvents.clear(this,streamEvent);streamEvent=null;}
        playbackGeneration++;
        cancelPlaybackTimeout();
        if(recovery!=null){main.removeCallbacks(recovery);recovery=null;}
        VideoView previous=video;
        video=null;
        if(previous!=null){
            previous.setOnPreparedListener(null);
            previous.setOnCompletionListener(null);
            previous.setOnErrorListener((mp,what,extra)->true);
            // Release directly: stop() is invalid for some decoders already in the error state.
            previous.suspend();
        }
    }
    private void play(int index) { play(index,0); }
    private void play(int index,int attempt) {
        if(index<0||index>=channels.size())return;
        stopPlayer();
        playbackFailed=false;
        renderedAt=0;
        final int generation=playbackGeneration;
        main.removeCallbacks(hideLabel);
        current=index; XtreamCatalog.Entry c=channels.get(index);
        getPreferences(0).edit().putString("lastChannel",c.id).apply();
        player=new FrameLayout(this);
        player.setBackgroundColor(Color.BLACK);
        setContentView(player);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        FitVideoView fitted=new FitVideoView(this);
        video=fitted;
        player.addView(video,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));
        channelLabel=title(c.name,20);
        channelLabel.setTextColor(Color.WHITE);
        channelLabel.setBackgroundColor(0xB0000000);
        FrameLayout.LayoutParams labelPosition=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL);
        labelPosition.topMargin=Math.round(getResources().getDisplayMetrics().heightPixels*0.05f);
        player.addView(channelLabel,labelPosition);
        applyPictureSize();
        String preferred="m3u8".equalsIgnoreCase(c.extension)?"m3u8":"ts";
        final String ext=attempt%2==0?preferred:("ts".equals(preferred)?"m3u8":"ts");
        try {
            String url=server+"/live/"+enc(user)+"/"+enc(pass)+"/"+enc(c.id)+"."+ext;
            // Register before opening: preparation or failure can arrive immediately.
            fitted.setOnInfoListener((mp,what,extra)->{
                if(video!=fitted||generation!=playbackGeneration)return false;
                if(what==MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START){
                    renderedAt=android.os.SystemClock.elapsedRealtime();
                    cancelPlaybackTimeout();
                }else if(what==MediaPlayer.MEDIA_INFO_BUFFERING_START){
                    // Mid-playback buffering is normal on live streams: wait longer before reconnecting from scratch.
                    watchPlayback(fitted,generation,index,attempt,ext,renderedAt>0?45000:12000);
                }else if(what==MediaPlayer.MEDIA_INFO_BUFFERING_END&&renderedAt>0){
                    cancelPlaybackTimeout();
                }
                return false;
            });
            fitted.setOnPreparedListener(mp->{
                if(video!=fitted||generation!=playbackGeneration)return;
                mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT);
                fitted.frameSize(mp.getVideoWidth(),mp.getVideoHeight());
                mp.setOnVideoSizeChangedListener((media,width,height)->{
                    if(video==fitted)fitted.frameSize(width,height);
                });
                fitted.start();
                streamEvent="stream:"+channels.get(index).id;
                IslandEvents.post(this,streamEvent,channels.get(index).name);
            });
            fitted.setOnErrorListener((mp,what,extra)->{
                if(video==fitted&&generation==playbackGeneration)
                    recover(index,attempt,ext,what,extra);
                return true;
            });
            fitted.setOnCompletionListener(mp->{
                // Live streams should reconnect when a server closes the connection.
                if(video==fitted&&generation==playbackGeneration)recover(index,attempt,ext,0,0);
            });
            fitted.setVideoURI(Uri.parse(url));
            watchPlayback(fitted,generation,index,attempt,ext,12000);
            fitted.requestFocus();
        } catch(Exception e){recover(index,attempt,ext,MediaPlayer.MEDIA_ERROR_UNKNOWN,0);}
    }
    private void cancelPlaybackTimeout() {
        if(playbackTimeout!=null){main.removeCallbacks(playbackTimeout);playbackTimeout=null;}
    }
    private void watchPlayback(FitVideoView fitted,int generation,int index,int attempt,String format,long waitMs) {
        if(video!=fitted||generation!=playbackGeneration)return;
        cancelPlaybackTimeout();
        // Some Android decoders retry a dead HTTP source silently for over thirty seconds.
        playbackTimeout=()->{
            if(video==fitted&&generation==playbackGeneration)
                recover(index,attempt,format,MediaPlayer.MEDIA_ERROR_UNKNOWN,MediaPlayer.MEDIA_ERROR_TIMED_OUT);
        };
        main.postDelayed(playbackTimeout,waitMs);
    }
    private void recover(int index,int attempt,String format,int what,int extra) {
        // Numeric diagnostics only: provider URLs contain the viewer's credentials.
        AppLog.w("Xtream playback: what="+what+", extra="+extra+", format="+format+", attempt="+attempt);
        if(renderedAt>0&&android.os.SystemClock.elapsedRealtime()-renderedAt>=15000)attempt=0;
        stopPlayer();
        main.removeCallbacks(hideLabel);
        if(!foreground||isFinishing()){playbackFailed=true;return;}
        channelLabel.setVisibility(View.VISIBLE);
        if(attempt<3){
            channelLabel.setText("إعادة الاتصال / Reconnecting… ("+(attempt+1)+"/3)");
            final int generation=playbackGeneration, nextAttempt=attempt+1;
            recovery=()->{if(foreground&&generation==playbackGeneration&&current==index)play(index,nextAttempt);};
            main.postDelayed(recovery,attempt==0?500:1500);
        }else{
            playbackFailed=true;
            channelLabel.setText("تعذّر تشغيل القناة / Playback error ("+what+", "+extra+")  •  OK: إعادة المحاولة / Retry");
        }
    }

    private void applyPictureSize() {
        if(player==null||current<0)return;
        player.post(()->{
            if(player==null||current<0)return;
            int x=Math.round(player.getWidth()*(100-picturePercent)/200f);
            int y=Math.round(player.getHeight()*(100-picturePercent)/200f);
            player.setPadding(x,y,x,y);
        });
        channelLabel.setText((current+1)+"/"+channels.size()+"  "+channels.get(current).name+"  •  "+picturePercent+"%  •  ←→ الحجم / Size  •  ↑↓ القنوات");
        channelLabel.setVisibility(View.VISIBLE);
        main.removeCallbacks(hideLabel);
        main.postDelayed(hideLabel,3000);
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int key=event.getKeyCode();
        if(current>=0){
            boolean previous=key==KeyEvent.KEYCODE_DPAD_DOWN||key==KeyEvent.KEYCODE_CHANNEL_DOWN;
            boolean next=key==KeyEvent.KEYCODE_DPAD_UP||key==KeyEvent.KEYCODE_CHANNEL_UP;
            boolean pause=key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER;
            boolean resize=key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT;
            if(previous||next||pause||resize){
                if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0){
                    if(previous||next){
                        int index=Math.max(0,Math.min(channels.size()-1,current+(next?1:-1)));
                        if(index!=current)play(index); else applyPictureSize();
                    }
                    else if(resize){
                        picturePercent=Math.max(80,Math.min(100,picturePercent+(key==KeyEvent.KEYCODE_DPAD_LEFT?-5:5)));
                        getPreferences(0).edit().putInt("picturePercent",picturePercent).apply();
                        applyPictureSize();
                    }
                    else if(playbackFailed){play(current);}
                    else if(video!=null){if(video.isPlaying())video.pause();else video.start();}
                }
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }
    @Override public void onBackPressed(){if(current>=0)showChannels();else super.onBackPressed();}
    @Override protected void onResume(){super.onResume();foreground=true;}
    @Override protected void onStop(){
        foreground=false;
        cancelPlaybackTimeout();
        if(recovery!=null){main.removeCallbacks(recovery);recovery=null;playbackFailed=true;}
        if(video!=null&&video.isPlaying())video.pause();
        super.onStop();
    }
    @Override protected void onDestroy(){catalogGeneration++;if(catalogThread!=null)catalogThread.interrupt();main.removeCallbacks(hideLabel);stopPlayer();super.onDestroy();}
}
