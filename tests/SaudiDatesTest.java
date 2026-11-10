package io.ouswis.tv.standalone;

import java.time.Instant;
import java.time.LocalDate;
import java.time.chrono.HijrahDate;
import java.util.Locale;
import java.util.TimeZone;

public final class SaudiDatesTest {
    private static final Locale AR = Locale.forLanguageTag("ar-SA");
    private static int checks;
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    private static long at(String text){return Instant.parse(text).toEpochMilli();}
    public static void main(String[] args){
        Locale priorLocale=Locale.getDefault();TimeZone priorZone=TimeZone.getDefault();
        try{
            // A TV set to another country must still roll over at midnight in Jeddah (21:00 UTC).
            for(String zone:new String[]{"UTC","America/Los_Angeles","Pacific/Kiritimati"}){
                TimeZone.setDefault(TimeZone.getTimeZone(zone));Locale.setDefault(Locale.US);
                check(SaudiDates.clock(at("2026-11-10T16:41:00Z"),AR,false).equals("7:41 م"),"Jeddah clock agrees with prayer epoch regardless of device zone");
                check(SaudiDates.clock(at("2026-11-10T21:00:00Z"),AR,false).equals("12:00 ص"),"Saudi midnight and period");
                SaudiDates dates=new SaudiDates();
                check(dates.update(at("2024-03-10T20:59:59Z"),AR),"first date");
                check(dates.hijri.contains("شعبان"),"29 Shaaban before Saudi midnight: "+dates.hijri);
                check(!dates.update(at("2024-03-10T20:59:59.999Z"),AR),"same civil day cached");
                check(dates.update(at("2024-03-10T21:00:00Z"),AR),"Saudi midnight invalidates cache");
                check(dates.hijri.contains("رمضان")&&dates.hijri.startsWith("1 "),"11 March 2024 = 1 Ramadan 1445: "+dates.hijri);
                check(dates.gregorian.contains("مارس")&&dates.gregorian.contains("2024"),"Gregorian March 2024: "+dates.gregorian);
                dates.update(at("2024-04-09T21:00:00Z"),AR);
                check(dates.hijri.startsWith("1 شوال 1445"),"10 April 2024 = 1 Shawwal 1445: "+dates.hijri);
                dates.update(at("2023-12-31T21:00:00Z"),AR);
                check(dates.gregorian.contains("يناير")&&dates.gregorian.contains("2024"),"Gregorian year boundary");
                dates.update(at("2024-02-28T21:00:00Z"),AR);
                check(dates.gregorian.contains("29")&&dates.gregorian.contains("فبراير"),"Gregorian leap day");
            }
            String[] months={"محرم","صفر","ربيع الأول","ربيع الآخر","جمادى الأولى","جمادى الآخرة","رجب","شعبان","رمضان","شوال","ذو القعدة","ذو الحجة"};
            SaudiDates dates=new SaudiDates();
            for(int month=1;month<=12;month++){
                LocalDate iso=LocalDate.from(HijrahDate.of(1448,month,15));
                dates.update(iso.atStartOfDay(SaudiDates.ZONE).toInstant().toEpochMilli(),AR);
                check(dates.hijri.equals("15 "+months[month-1]+" 1448 هـ"),"Islamic month "+month+": "+dates.hijri);
            }
            dates.update(at("2026-11-09T12:00:00Z"),AR);
            check(dates.hijri.contains("جمادى الأولى")&&!dates.hijri.contains("مايو"),"regression: Gregorian symbols must never name a Hijri month");
            check(dates.update(at("2026-11-09T12:00:00Z"),Locale.US),"language change invalidates cache");
            check(dates.gregorian.contains("November"),"language refresh");
            System.out.println("PASS: "+checks+" checks: Umm al-Qura anchors, all 12 Arabic Islamic months, Jeddah midnight, month/year/leap boundaries, host zone/locale independence and date caching.");
        }finally{Locale.setDefault(priorLocale);TimeZone.setDefault(priorZone);}
    }
}
