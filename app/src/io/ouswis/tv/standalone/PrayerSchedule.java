package io.ouswis.tv.standalone;

import org.json.JSONObject;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Locale;

/** One validated Jeddah calendar day, in Riyadh time and Umm al-Qura method 4. */
final class PrayerSchedule {
    static final ZoneId ZONE=ZoneId.of("Asia/Riyadh");
    static final double LATITUDE=21.4858, LONGITUDE=39.1925;
    static final DateTimeFormatter DATE=DateTimeFormatter.ofPattern("dd-MM-uuuu",Locale.US).withResolverStyle(ResolverStyle.STRICT);
    static final String[] KEYS={"Fajr","Dhuhr","Asr","Maghrib","Isha"};
    final LocalDate date;
    private final long[] times;

    private PrayerSchedule(LocalDate date,long[] times){this.date=date;this.times=times;}
    static String url(LocalDate day){
        return "https://api.aladhan.com/v1/timings/"+DATE.format(day)
                +"?latitude="+LATITUDE+"&longitude="+LONGITUDE+"&method=4&school=0&timezonestring=Asia%2FRiyadh";
    }
    static PrayerSchedule parse(String json,LocalDate expected) throws Exception {
        JSONObject root=new JSONObject(json);
        if(root.optInt("code")!=200)throw new IllegalArgumentException("Prayer source did not return success");
        JSONObject data=root.getJSONObject("data"),meta=data.getJSONObject("meta");
        LocalDate date=LocalDate.parse(data.getJSONObject("date").getJSONObject("gregorian").getString("date"),DATE);
        if(!date.equals(expected))throw new IllegalArgumentException("Wrong prayer date");
        if(!"Asia/Riyadh".equals(meta.getString("timezone"))||meta.getJSONObject("method").getInt("id")!=4
                ||!"STANDARD".equals(meta.optString("school","STANDARD"))
                ||Math.abs(meta.getDouble("latitude")-LATITUDE)>0.02||Math.abs(meta.getDouble("longitude")-LONGITUDE)>0.02)
            throw new IllegalArgumentException("Wrong prayer location, timezone or method");
        long[] times=new long[5];JSONObject values=data.getJSONObject("timings");
        for(int i=0;i<5;i++){
            String value=values.getString(KEYS[i]);
            if(!value.matches("[0-2][0-9]:[0-5][0-9](?: \\([^)]*\\))?"))throw new IllegalArgumentException("Invalid prayer time");
            LocalTime time=LocalTime.parse(value.substring(0,5));
            times[i]=date.atTime(time).atZone(ZONE).toInstant().toEpochMilli();
            if(i>0&&times[i]<=times[i-1])throw new IllegalArgumentException("Prayer times not chronological");
        }
        return new PrayerSchedule(date,times);
    }
    boolean current(long now){return date.equals(java.time.Instant.ofEpochMilli(now).atZone(ZONE).toLocalDate());}
    long time(int index){return times[index];}
    int next(long now){for(int i=0;i<times.length;i++)if(times[i]>now)return i;return -1;}
}
