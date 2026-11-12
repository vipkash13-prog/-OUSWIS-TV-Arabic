package io.ouswis.tv.standalone;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * The viewer's Xtream login, read from where XtreamActivity already keeps it (its private preferences). Nothing is
 * stored in the repository and nothing here writes credentials: the login screen stays XtreamActivity's.
 */
final class XtreamAccount {
    final String server, user, pass;
    private XtreamAccount(String s,String u,String p){server=s;user=u;pass=p;}
    /** XtreamActivity uses getPreferences(0): a file named after the class. Null when nobody has signed in yet. */
    static XtreamAccount read(Context c){
        SharedPreferences p=c.getSharedPreferences("XtreamActivity",Context.MODE_PRIVATE);
        String s=p.getString("server",""),u=p.getString("user",""),w=p.getString("pass","");
        if(s.isEmpty()||u.isEmpty()||w.isEmpty())return null;
        return new XtreamAccount(s,u,w);
    }
    static SharedPreferences prefs(Context c){return c.getSharedPreferences("XtreamActivity",Context.MODE_PRIVATE);}
    static String enc(String s){try{return URLEncoder.encode(s,"UTF-8");}catch(Exception e){return s;}}
    String api(String action,String extra){
        return server+"/player_api.php?username="+enc(user)+"&password="+enc(pass)
                +(action==null?"":"&action="+action)+(extra==null?"":"&"+extra);
    }
    /** Numeric/diagnostic logging must never include this: it contains the password. */
    static String get(String address) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(address).openConnection();
        c.setConnectTimeout(10000);c.setReadTimeout(15000);c.setInstanceFollowRedirects(true);
        try{
            if(c.getResponseCode()!=200)throw new Exception("HTTP "+c.getResponseCode());
            try(BufferedReader r=new BufferedReader(new InputStreamReader(c.getInputStream(),"UTF-8"))){
                StringBuilder s=new StringBuilder();String line;
                while((line=r.readLine())!=null){s.append(line);if(s.length()>8*1024*1024)throw new Exception("too large");}
                return s.toString();
            }
        }finally{c.disconnect();}
    }
}
