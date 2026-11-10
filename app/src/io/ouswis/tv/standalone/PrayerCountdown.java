package io.ouswis.tv.standalone;

/** A validated next prayer across midnight; no guessed tomorrow or synthetic countdown. */
final class PrayerCountdown {
    final int index;final long at;final PrayerSchedule schedule;
    private PrayerCountdown(int index,PrayerSchedule schedule){this.index=index;this.schedule=schedule;this.at=schedule.time(index);}
    static PrayerCountdown next(PrayerSchedule today,PrayerSchedule tomorrow,long now){
        if(today==null||!today.current(now))return null;
        int index=today.next(now);
        if(index>=0)return new PrayerCountdown(index,today);
        if(tomorrow==null||!tomorrow.date.equals(today.date.plusDays(1))||tomorrow.time(0)<=now)return null;
        return new PrayerCountdown(0,tomorrow);
    }
    boolean followingDay(PrayerSchedule today){return today!=null&&schedule.date.isAfter(today.date);}
    String compact(long now,boolean arabic){
        long total=minutes(now);
        return total<60?total+(arabic?" دقيقة":" min")
                :total/60+(arabic?" س ":" h ")+total%60+(arabic?" د":" min");
    }
    boolean approaching(long now){return at>now&&at-now<=5*60_000L;}
    long minutes(long now){return Math.max(0,(at-now+59_999)/60_000);}
}
