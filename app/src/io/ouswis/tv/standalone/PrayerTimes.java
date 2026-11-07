package io.ouswis.tv.standalone;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Daily fetched prayer times; yesterday's data is never labelled as today's. No location permission needed. */
final class PrayerTimes {
    private static PrayerTimes instance;
    static synchronized PrayerTimes get(Context c){if(instance==null)instance=new PrayerTimes(c);return instance;}
    private final SharedPreferences cache;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final List<Runnable> listeners=new ArrayList<>();
    private volatile PrayerSchedule first,second;
    volatile long changedAt,fetchedAt;
    volatile boolean fetching,failed,fromCache=true;
    private volatile long failedAt;
    private PrayerTimes(Context c){
        cache=c.getApplicationContext().getSharedPreferences("prayer-times",Context.MODE_PRIVATE);
        fetchedAt=cache.getLong("fetchedAt",0);
        try{first=cached("today");}catch(Exception ignored){}
        try{second=cached("tomorrow");}catch(Exception ignored){}
    }
    private PrayerSchedule cached(String key) throws Exception {
        String raw=cache.getString(key,"");if(raw.isEmpty())return null;
        String day=new org.json.JSONObject(raw).getJSONObject("data").getJSONObject("date").getJSONObject("gregorian").getString("date");
        return PrayerSchedule.parse(raw,LocalDate.parse(day,PrayerSchedule.DATE));
    }
    void addListener(Runnable r){if(!listeners.contains(r))listeners.add(r);}
    void removeListener(Runnable r){listeners.remove(r);}
    private void changed(){changedAt=System.currentTimeMillis();main.post(()->{for(Runnable r:new ArrayList<>(listeners))r.run();});}
    PrayerSchedule today(){long now=System.currentTimeMillis();PrayerSchedule a=first,b=second;return a!=null&&a.current(now)?a:b!=null&&b.current(now)?b:null;}
    PrayerSchedule tomorrow(){
        LocalDate day=LocalDate.now(PrayerSchedule.ZONE).plusDays(1);
        PrayerSchedule a=first,b=second;
        return a!=null&&a.date.equals(day)?a:b!=null&&b.date.equals(day)?b:null;
    }
    synchronized void refreshIfStale(){
        if(!Prefs.prayers()||fetching)return;
        long now=System.currentTimeMillis();
        if(failedAt>0&&now>=failedAt&&now-failedAt<60_000)return;
        if(today()!=null&&!fromCache&&now>=fetchedAt&&now-fetchedAt<6*3600_000L)return;
        fetching=true;
        io.execute(()->{
            try{
                LocalDate day=LocalDate.now(PrayerSchedule.ZONE);
                String raw=fetch(PrayerSchedule.url(day));
                first=PrayerSchedule.parse(raw,day);
                fetchedAt=System.currentTimeMillis();fromCache=false;failed=false;failedAt=0;
                cache.edit().putString("today",raw).putLong("fetchedAt",fetchedAt).apply();changed();
                try{
                    String next=fetch(PrayerSchedule.url(day.plusDays(1)));
                    second=PrayerSchedule.parse(next,day.plusDays(1));
                    cache.edit().putString("tomorrow",next).apply();
                }catch(Exception e){AppLog.w("prayers tomorrow: "+e.getMessage());}
            }catch(Exception e){failed=true;fromCache=true;failedAt=System.currentTimeMillis();AppLog.w("prayers: "+e.getMessage());}
            finally{fetching=false;changed();}
        });
    }
    static String name(int i){switch(i){case 0:return Ui.tr("Fajr");case 1:return Ui.tr("Dhuhr");case 2:return Ui.tr("Asr");case 3:return Ui.tr("Maghrib");default:return Ui.tr("Isha");}}
    String status(){return failed?Ui.tr("No connection"):Ui.tr("Checking…");}
    static String place(){return Ui.language.equals("ar")?"جدة · أم القرى":"Jeddah · Umm al-Qura";}
    static String time(PrayerSchedule day,int index){
        boolean h24=Ui.h24();long at=day.time(index);
        return Ui.once(h24?"prayer24":"prayer12",at,()->{
            java.text.SimpleDateFormat f=new java.text.SimpleDateFormat(h24?"HH:mm":"h:mm a",Ui.locale());
            f.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Riyadh"));return f.format(new java.util.Date(at));
        });
    }
    String nextText(){
        PrayerSchedule day=today();long now=System.currentTimeMillis();
        if(day==null)return Ui.tr("Prayer times")+" · "+status();
        int index=day.next(now);
        boolean following=index<0;
        if(following){day=tomorrow();index=0;}
        if(day==null)return Ui.tr("Prayer times")+" · "+Ui.tr("Unavailable");
        return Ui.trf("{prayer} at {time}","prayer",name(index)+(following?" ("+day.date.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM"))+")":""),"time",time(day,index));
    }
    private static String fetch(String address) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(address).openConnection();
        c.setConnectTimeout(8000);c.setReadTimeout(12000);
        c.setRequestProperty("Accept","application/json");c.setRequestProperty("User-Agent","OUSWIS-TV/1.0 (Android)");
        try{
            if(c.getResponseCode()!=200)throw new java.io.IOException("Prayer source HTTP "+c.getResponseCode());
            try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){if(out.size()+n>128*1024)throw new java.io.IOException("Oversized prayer response");out.write(buffer,0,n);}
                return new String(out.toByteArray(),StandardCharsets.UTF_8);
            }
        }finally{c.disconnect();}
    }
}
