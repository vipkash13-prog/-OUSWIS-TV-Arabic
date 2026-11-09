package io.ouswis.tv.standalone;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.chrono.HijrahDate;
import java.time.chrono.IsoChronology;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoField;
import java.util.Locale;

/** Both civil dates roll over at midnight in Jeddah, regardless of the device's time zone. */
final class SaudiDates {
    static final ZoneId ZONE = ZoneId.of("Asia/Riyadh");
    private static final Locale ARABIC = Locale.forLanguageTag("ar-SA");
    private static final String[] HIJRI_MONTHS = {
        "محرم", "صفر", "ربيع الأول", "ربيع الآخر", "جمادى الأولى", "جمادى الآخرة",
        "رجب", "شعبان", "رمضان", "شوال", "ذو القعدة", "ذو الحجة"
    };
    private LocalDate day;
    private Locale locale;
    String gregorian = "", hijri = "";

    boolean update(long now, Locale language) {
        LocalDate today = Instant.ofEpochMilli(now).atZone(ZONE).toLocalDate();
        if (today.equals(day) && language.equals(locale)) return false;
        day = today;
        locale = language;
        // Force ISO/Gregorian even if the selected locale normally uses an Islamic or Buddhist calendar.
        gregorian = DateTimeFormatter.ofPattern("EEEE d MMMM uuuu", language)
                .withChronology(IsoChronology.INSTANCE).format(today);
        // HijrahDate's built-in chronology is explicitly Hijrah-umalqura, not the tabular Islamic calendar.
        HijrahDate islamic = HijrahDate.from(today);
        hijri = String.format(ARABIC, "%d %s %d هـ", islamic.get(ChronoField.DAY_OF_MONTH),
                HIJRI_MONTHS[islamic.get(ChronoField.MONTH_OF_YEAR) - 1], islamic.get(ChronoField.YEAR));
        return true;
    }
}
