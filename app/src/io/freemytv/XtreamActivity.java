package io.freemytv;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
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
import java.io.InputStreamReader;
import java.util.ArrayList;

/** Remote-friendly, live-only Xtream Codes player. Credentials never enter the source repository. */
public final class XtreamActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayList<Channel> channels = new ArrayList<>();
    private LinearLayout root;
    private VideoView video;
    private TextView status;
    private String server, user, pass;
    private int current = -1;
    private static final class Channel {
        String name, id, extension;
        Channel(String n, String i, String e) { name=n; id=i; extension=e; }
    }
    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().getDecorView().setSystemUiVisibility(5894);
        server=getPreferences(0).getString("server","");
        user=getPreferences(0).getString("user","");
        pass=getPreferences(0).getString("pass","");
        if (server.isEmpty()) login(); else load();
    }
    private TextView title(String text, int size) {
        TextView t=new TextView(this); t.setText(text); t.setTextColor(Color.WHITE);
        t.setTextSize(size); t.setPadding(24,16,24,16); return t;
    }
    private void shell() {
        root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(12,14,28)); setContentView(root);
    }
    private void login() {
        shell(); root.addView(title("OUSWIS TV  |  Xtream Codes",26));
        LinearLayout form=new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(40,15,40,15); root.addView(form);
        EditText host=field(form,"Server URL (http://...:port)",server);
        EditText username=field(form,"Username",user);
        EditText password=field(form,"Password",pass);
        password.setInputType(129);
        Button connect=new Button(this); connect.setText("Connect / اتصال"); form.addView(connect);
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
        e.setTextColor(Color.WHITE); e.setHintTextColor(Color.LTGRAY); form.addView(e); return e;
    }
    private String enc(String s) throws Exception {return URLEncoder.encode(s,"UTF-8");}
    private String api(String action) throws Exception {
        return server+"/player_api.php?username="+enc(user)+"&password="+enc(pass)+"&action="+action;
    }
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
    private void load() {
        shell(); status=title("Loading live channels / تحميل القنوات...",22); root.addView(status);
        new Thread(()->{
            try {
                JSONObject account=new JSONObject(request(api(""))); 
                JSONObject info=account.optJSONObject("user_info");
                if(info==null || !"Active".equalsIgnoreCase(info.optString("status"))) throw new Exception("Account is not active");
                JSONArray data=new JSONArray(request(api("get_live_streams")));
                ArrayList<Channel> found=new ArrayList<>();
                for(int i=0;i<data.length();i++){
                    JSONObject x=data.optJSONObject(i); if(x==null)continue;
                    String id=x.optString("stream_id"); if(id.isEmpty())continue;
                    found.add(new Channel(x.optString("name","Channel"),id,x.optString("container_extension","ts")));
                }
                main.post(()->{channels.clear();channels.addAll(found);showChannels();});
            } catch(Exception e){main.post(()->{status.setText("Connection failed / فشل الاتصال: "+e.getMessage());
                Button retry=new Button(this);retry.setText("Edit account / تعديل الحساب");root.addView(retry);retry.setOnClickListener(v->login());});}
        }).start();
    }
    private void showChannels() {
        if(video!=null){video.stopPlayback();video=null;} current=-1; shell();
        LinearLayout header=new LinearLayout(this); root.addView(header);
        TextView heading=title("البث المباشر  |  Live TV ("+channels.size()+")",22);
        header.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
        Button account=new Button(this);account.setText("Account");header.addView(account);
        account.setOnClickListener(v->login());
        ListView list=new ListView(this);root.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        ArrayList<String> names=new ArrayList<>();for(Channel c:channels)names.add(c.name);
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_list_item_1,names);
        list.setAdapter(adapter);list.setOnItemClickListener((parent,view,pos,id)->play(pos));
        list.requestFocus();
    }
    private void play(int index) {
        if(index<0||index>=channels.size())return;
        current=index; Channel c=channels.get(index);shell();
        TextView label=title(c.name+"    (↑↓ Channels • Back List)",20);root.addView(label);
        video=new VideoView(this);root.addView(video,new LinearLayout.LayoutParams(-1,0,1));
        String ext=c.extension; if(!"m3u8".equalsIgnoreCase(ext))ext="ts";
        try {
            String url=server+"/live/"+enc(user)+"/"+enc(pass)+"/"+enc(c.id)+"."+ext;
            video.setVideoURI(Uri.parse(url));
            video.setOnPreparedListener(mp->video.start());
            video.setOnErrorListener((mp,what,extra)->{Toast.makeText(this,"Playback error / تعذر تشغيل القناة",Toast.LENGTH_LONG).show();return true;});
            video.requestFocus();
        } catch(Exception e){Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show();}
    }
    @Override public boolean onKeyDown(int key,KeyEvent event) {
        if(current>=0){
            if(key==KeyEvent.KEYCODE_DPAD_UP){play((current+channels.size()-1)%channels.size());return true;}
            if(key==KeyEvent.KEYCODE_DPAD_DOWN){play((current+1)%channels.size());return true;}
            if(key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER){if(video!=null){if(video.isPlaying())video.pause();else video.start();}return true;}
        }
        return super.onKeyDown(key,event);
    }
    @Override public void onBackPressed(){if(current>=0)showChannels();else super.onBackPressed();}
    @Override protected void onStop(){super.onStop();if(video!=null)video.pause();}
    @Override protected void onDestroy(){if(video!=null)video.stopPlayback();super.onDestroy();}
}
