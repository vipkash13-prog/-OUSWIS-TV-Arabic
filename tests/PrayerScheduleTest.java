package io.ouswis.tv.standalone;

import org.json.JSONObject;
import java.time.LocalDate;
import java.time.Instant;
import java.time.ZoneId;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public final class PrayerScheduleTest {
    private static void check(boolean condition,String why){if(!condition)throw new AssertionError(why);}
    private static JSONObject sample(LocalDate day) throws Exception {
        JSONObject times=new JSONObject().put("Fajr","05:11").put("Dhuhr","12:07").put("Asr","15:21")
                .put("Maghrib","17:44").put("Isha","19:14");
        JSONObject meta=new JSONObject().put("latitude",PrayerSchedule.LATITUDE).put("longitude",PrayerSchedule.LONGITUDE)
                .put("timezone","Asia/Riyadh").put("method",new JSONObject().put("id",4)).put("school","STANDARD");
        return new JSONObject().put("code",200).put("data",new JSONObject().put("timings",times).put("meta",meta)
                .put("date",new JSONObject().put("gregorian",new JSONObject().put("date",PrayerSchedule.DATE.format(day)))));
    }
    private static void reject(JSONObject root,LocalDate day,String why) throws Exception {
        boolean rejected=false;try{PrayerSchedule.parse(root.toString(),day);}catch(Exception expected){rejected=true;}
        check(rejected,why);
    }
    private static String fetch(LocalDate day) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(PrayerSchedule.url(day)).openConnection();
        c.setConnectTimeout(8000);c.setReadTimeout(12000);
        c.setRequestProperty("User-Agent","OUSWIS-TV/1.0 (Android)");c.setRequestProperty("Accept","application/json");
        try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);
            return new String(out.toByteArray(),StandardCharsets.UTF_8);
        }finally{c.disconnect();}
    }
    public static void main(String[] args) throws Exception {
        LocalDate day=LocalDate.of(2026,11,7);PrayerSchedule s=PrayerSchedule.parse(sample(day).toString(),day);
        check(s.current(Instant.parse("2026-11-06T21:00:00Z").toEpochMilli()),"Riyadh midnight starts the correct day");
        check(!s.current(Instant.parse("2026-11-06T20:59:59Z").toEpochMilli()),"Do not show tomorrow as today");
        check(!s.current(Instant.parse("2026-11-07T21:00:00Z").toEpochMilli()),"Do not show yesterday as today");
        check(Instant.ofEpochMilli(s.time(0)).atZone(ZoneId.of("Asia/Riyadh")).getHour()==5,"Use Riyadh regardless of device timezone");
        check(s.next(s.time(4)+1)==-1,"Never guess tomorrow's Fajr from today's time");
        reject(sample(day),day.plusDays(1),"Reject wrong source date");
        JSONObject bad=sample(day);bad.getJSONObject("data").getJSONObject("meta").put("timezone","UTC");reject(bad,day,"Reject wrong timezone");
        bad=sample(day);bad.getJSONObject("data").getJSONObject("meta").put("latitude",24.7);reject(bad,day,"Reject wrong city");
        bad=sample(day);bad.getJSONObject("data").getJSONObject("meta").getJSONObject("method").put("id",2);reject(bad,day,"Reject wrong method");
        bad=sample(day);bad.getJSONObject("data").getJSONObject("timings").put("Asr","10:00");reject(bad,day,"Reject invalid ordering");
        bad=sample(day);bad.put("code",500);reject(bad,day,"Reject API failure");
        System.out.println("PASS: Jeddah location/method/timezone/date, midnight rollover, ordering and stale-cache guards.");
        if(args.length>0&&args[0].equals("--live")){
            LocalDate today=LocalDate.now(PrayerSchedule.ZONE);
            PrayerSchedule live=PrayerSchedule.parse(fetch(today),today);
            for(int i=0;i<5;i++)System.out.println("LIVE: Jeddah "+today+" "+PrayerSchedule.KEYS[i]+" "+Instant.ofEpochMilli(live.time(i)).atZone(PrayerSchedule.ZONE).toLocalTime());
            PrayerSchedule ramadan=PrayerSchedule.parse(fetch(LocalDate.of(2026,2,20)),LocalDate.of(2026,2,20));
            check(ramadan.time(4)-ramadan.time(3)==120*60_000L,"Keep Umm al-Qura's Ramadan Isha interval from the provider");
            System.out.println("PASS: real Umm al-Qura data for Jeddah and the provider's Ramadan Isha interval.");
        }
    }
}
