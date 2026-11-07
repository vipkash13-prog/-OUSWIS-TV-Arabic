package io.freemytv;

import org.json.JSONArray;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Free, keyless upcoming football fixtures from ESPN's unofficial scoreboard feed.
 * Network failures return an empty list; no fabricated fixtures are displayed.
 * This endpoint is undocumented and may change without notice.
 */
public final class UpcomingMatches {
    private UpcomingMatches(){}
    public static final String[] LEAGUES={
        "ksa.1", "afc.champions.league", "uefa.champions",
        "eng.1", "esp.1", "ita.1", "fra.1"
    };
    public static List<MatchCarouselView.Match> fetch(String[] selected) { return fetch(selected, "upcoming"); }
    public static List<MatchCarouselView.Match> fetch(String[] selected, String section) {
        ArrayList<MatchCarouselView.Match> out=new ArrayList<>();
        HashSet<String> seen=new HashSet<>();
        String[] leagues=selected==null?LEAGUES:selected;
        SimpleDateFormat fmt=new SimpleDateFormat("dd/MM  HH:mm",new Locale("ar","SA"));
        fmt.setTimeZone(TimeZone.getTimeZone("Asia/Riyadh"));
        long now=System.currentTimeMillis();
        SimpleDateFormat day=new SimpleDateFormat("yyyyMMdd",Locale.US);
        day.setTimeZone(TimeZone.getTimeZone("UTC"));
        TimeZone riyadh=TimeZone.getTimeZone("Asia/Riyadh");
        java.util.Calendar calendar=java.util.Calendar.getInstance(riyadh);
        calendar.setTimeInMillis(now);
        calendar.set(java.util.Calendar.HOUR_OF_DAY,0);
        calendar.set(java.util.Calendar.MINUTE,0);
        calendar.set(java.util.Calendar.SECOND,0);
        calendar.set(java.util.Calendar.MILLISECOND,0);
        long todayStart=calendar.getTimeInMillis();
        long yesterdayStart=todayStart-24L*60*60*1000;
        long tomorrowStart=todayStart+24L*60*60*1000;
        String from=day.format(new Date(now-2L*24*60*60*1000));
        String to=day.format(new Date(now+14L*24*60*60*1000));
        for(String league:leagues){
            if(league==null||!league.matches("[a-z0-9.]+"))continue;
            HttpURLConnection c=null;
            try {
                URL url=new URL("https://site.api.espn.com/apis/site/v2/sports/soccer/"+league+"/scoreboard?dates="+from+"-"+to+"&limit=100");
                c=(HttpURLConnection)url.openConnection();
                c.setConnectTimeout(6500);c.setReadTimeout(6500);
                if(c.getResponseCode()!=200)continue;
                StringBuilder raw=new StringBuilder();
                try(BufferedReader reader=new BufferedReader(new InputStreamReader(c.getInputStream(),"UTF-8"))){
                    String line;while((line=reader.readLine())!=null)raw.append(line);
                }
                JSONObject root=new JSONObject(raw.toString());
                JSONArray events=root.optJSONArray("events");
                if(events==null)continue;
                for(int i=0;i<events.length();i++){
                    JSONObject e=events.optJSONObject(i);if(e==null)continue;
                    String id=e.optString("id");
                    if(!seen.add(league+":"+id))continue;
                    JSONArray comps=e.optJSONArray("competitions");
                    if(comps==null||comps.length()==0)continue;
                    JSONObject comp=comps.optJSONObject(0);if(comp==null)continue;
                    JSONObject status=e.optJSONObject("status");
                    JSONObject type=status==null?null:status.optJSONObject("type");
                    if(type==null)continue;
                    String state=type.optString("state");
                    JSONArray teams=comp.optJSONArray("competitors");
                    if(teams==null||teams.length()<2)continue;
                    String home="",away="";
                    for(int k=0;k<teams.length();k++){
                        JSONObject t=teams.optJSONObject(k);if(t==null)continue;
                        JSONObject team=t.optJSONObject("team");
                        String name=team==null?"":team.optString("displayName");
                        if("home".equals(t.optString("homeAway")))home=name;
                        else if("away".equals(t.optString("homeAway")))away=name;
                    }
                    if(home.isEmpty()||away.isEmpty())continue;
                    String utc=e.optString("date");if(utc.isEmpty())continue;
                    SimpleDateFormat iso=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'",Locale.US);
                    iso.setTimeZone(TimeZone.getTimeZone("UTC"));
                    Date date=iso.parse(utc);
                    if(date==null)continue;
                    long kickoff=date.getTime();
                    boolean include;
                    if("live".equals(section)) include="in".equals(state);
                    else if("today".equals(section)) include="post".equals(state)&&kickoff>=todayStart&&kickoff<tomorrowStart;
                    else if("yesterday".equals(section)) include="post".equals(state)&&kickoff>=yesterdayStart&&kickoff<todayStart;
                    else include="pre".equals(state)&&kickoff>=now;
                    if(!include)continue;
                    JSONObject leagueObj=e.optJSONObject("league");
                    String competition=leagueObj==null?league:leagueObj.optString("name",league);
                    String display=fmt.format(date);
                    if("live".equals(section)||"today".equals(section)||"yesterday".equals(section)) {
                        String hs="",as="";
                        for(int k=0;k<teams.length();k++){
                            JSONObject t=teams.optJSONObject(k);if(t==null)continue;
                            if("home".equals(t.optString("homeAway")))hs=t.optString("score");
                            if("away".equals(t.optString("homeAway")))as=t.optString("score");
                        }
                        if(!hs.isEmpty()&&!as.isEmpty())display=hs+" - "+as+"  •  "+display;
                    }
                    out.add(new MatchCarouselView.Match(competition,home,away,display,kickoff));
                }
            }catch(Exception ignored){
                // Unavailable competition must not prevent other competitions from loading.
            }finally{if(c!=null)c.disconnect();}
        }
        // Chronological fixtures across all leagues, not grouped by provider response order.
        java.util.Collections.sort(out,(a,b)->Long.compare(a.kickoffMillis,b.kickoffMillis));
        return out;
    }
}
