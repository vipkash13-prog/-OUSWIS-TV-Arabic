package io.ouswis.tv.standalone;

/** A validated next prayer across midnight; no guessed tomorrow or synthetic countdown. */
final class PrayerCountdown {
    final int index;final long at;
    private PrayerCountdown(int index,long at){this.index=index;this.at=at;}
    static PrayerCountdown next(PrayerSchedule today,PrayerSchedule tomorrow,long now){
        if(today==null||!today.current(now))return null;
        int index=today.next(now);
        if(index>=0)return new PrayerCountdown(index,today.time(index));
        if(tomorrow==null||!tomorrow.date.equals(today.date.plusDays(1))||tomorrow.time(0)<=now)return null;
        return new PrayerCountdown(0,tomorrow.time(0));
    }
    long minutes(long now){return Math.max(0,(at-now+59_999)/60_000);}
}
