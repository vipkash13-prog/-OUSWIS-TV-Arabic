package io.ouswis.tv.standalone;

import org.json.JSONObject;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** Optional observations: absent, invalid, stale or future values are never substituted with sample data. */
final class EnvironmentReading {
    static final long MAX_AGE=3*3600_000L;
    final double aqi;
    final long observedAt;
    final String place;
    EnvironmentReading(double aqi,long observedAt,String place){this.aqi=aqi;this.observedAt=observedAt;this.place=place;}
    boolean available(long now,String location){return place.equals(location)&&Double.isFinite(aqi)&&aqi>=0&&aqi<=1000&&now-observedAt<=MAX_AGE&&observedAt-now<=15*60_000L;}
    static EnvironmentReading parse(String raw,String place,long now)throws Exception{
        JSONObject root=new JSONObject(raw),current=root.getJSONObject("current");
        double value=current.optDouble("us_aqi",Double.NaN);
        String time=current.getString("time");
        long at=LocalDateTime.parse(time).toInstant(ZoneOffset.ofTotalSeconds(root.optInt("utc_offset_seconds",0))).toEpochMilli();
        EnvironmentReading result=new EnvironmentReading(value,at,place);
        if(!result.available(now,place))throw new IllegalArgumentException("Unavailable or stale AQI");
        return result;
    }
    static double probability(double value){return Double.isFinite(value)&&value>=0&&value<=100?value:Double.NaN;}
}
