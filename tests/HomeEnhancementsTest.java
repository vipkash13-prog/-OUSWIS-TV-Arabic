package io.ouswis.tv.standalone;
import org.json.JSONObject;
import java.time.LocalDate;
import java.time.Instant;

public final class HomeEnhancementsTest {
    private static int count;
    private static void check(boolean ok,String text){count++;if(!ok)throw new AssertionError(text);}
    private static PrayerSchedule day(LocalDate date)throws Exception{
        JSONObject t=new JSONObject().put("Fajr","05:12").put("Dhuhr","12:07").put("Asr","15:20").put("Maghrib","17:43").put("Isha","19:13");
        JSONObject meta=new JSONObject().put("latitude",21.4858).put("longitude",39.1925).put("timezone","Asia/Riyadh").put("method",new JSONObject().put("id",4));
        return PrayerSchedule.parse(new JSONObject().put("code",200).put("data",new JSONObject().put("meta",meta).put("timings",t).put("date",new JSONObject().put("gregorian",new JSONObject().put("date",date.format(PrayerSchedule.DATE))))).toString(),date);
    }
    private static void invalid(String raw,long now)throws Exception{boolean rejected=false;try{EnvironmentReading.parse(raw,"Jeddah",now);}catch(Exception e){rejected=true;}check(rejected,"invalid AQI must not be shown");}
    public static void main(String[] args)throws Exception{
        Spring f=Spring.focus(0);f.target=1;f.step(.05f);check(f.value>0&&f.value<1,"focus interpolates");f.step(.05f);check(f.value<1,"focus not complete at 100ms");f.step(.05f);check(f.value==1,"focus settles at 150ms");
        f.target=0;for(int i=0;i<30;i++){f.step(.01f);check(f.value>=0&&f.value<=1,"no focus overshoot; maximum growth stays 3%");}check(f.value==0,"focus fades out");
        f.target=1;f.step(.075f);float half=f.value;f.target=0;f.step(.01f);check(f.value<half&&f.value>=0,"fast remote reversal");f.snap(1);check(!f.step(.016f),"settled focus uses no continuous animation");
        long now=Instant.parse("2026-11-09T12:00:00Z").toEpochMilli();
        String valid="{\"current\":{\"us_aqi\":42,\"time\":\"2026-11-09T12:00\"},\"utc_offset_seconds\":0}";
        EnvironmentReading air=EnvironmentReading.parse(valid,"Jeddah",now);check(air.aqi==42&&air.available(now,"Jeddah"),"actual observation");check(!air.available(now,"Riyadh"),"location-scoped AQI");check(!air.available(now+EnvironmentReading.MAX_AGE+1,"Jeddah"),"old AQI hidden");
        invalid(valid.replace("42","null"),now);invalid(valid.replace("42","-2"),now);invalid(valid,now+4*3600_000L);invalid(valid,now-3600_000L);
        check(Double.isNaN(EnvironmentReading.probability(-1))&&Double.isNaN(EnvironmentReading.probability(101))&&Double.isNaN(EnvironmentReading.probability(Double.NaN)),"missing and invalid rain hidden");check(EnvironmentReading.probability(0)==0&&EnvironmentReading.probability(100)==100,"valid rain endpoints");
        PrayerSchedule today=day(LocalDate.of(2026,11,9)),tomorrow=day(LocalDate.of(2026,11,10));
        long before=today.time(0)-90_000;PrayerCountdown next=PrayerCountdown.next(today,tomorrow,before);check(next.index==0&&next.minutes(before)==2,"countdown rounds up");check(PrayerCountdown.next(today,tomorrow,today.time(0)).index==1,"exact prayer start advances");
        next=PrayerCountdown.next(today,tomorrow,today.time(4)+1);check(next.index==0&&next.at==tomorrow.time(0),"after Isha uses real tomorrow");check(PrayerCountdown.next(today,null,today.time(4)+1)==null,"never invent tomorrow");check(PrayerCountdown.next(null,tomorrow,now)==null,"missing source stays unavailable");
        System.out.println("PASS: "+count+" checks: exact 150ms focus, bounded scaling, reversal, optional real rain/AQI, stale/future/location guards, prayer rollover and missing source.");
    }
}
