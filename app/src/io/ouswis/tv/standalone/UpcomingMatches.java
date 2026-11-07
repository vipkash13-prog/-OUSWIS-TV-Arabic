package io.ouswis.tv.standalone;

import org.json.JSONArray;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Free, keyless football fixtures from ESPN's unofficial scoreboard feed (no fabricated data).
 * Competitions load in parallel; one that fails never blocks the others. The endpoint is undocumented.
 */
public final class UpcomingMatches {
    private UpcomingMatches(){}
    public static final String[] LEAGUES={
        "ksa.1", "afc.champions", "uefa.champions",
        "eng.1", "esp.1", "ita.1", "fra.1"
    };
    /** What a load found: the matches, and how many competitions answered or didn't. */
    public static final class Result {
        public final List<MatchCarouselView.Match> matches=new ArrayList<>();
        public int answered, failed;
        /** True when nothing answered at all (offline, blocked): different from "no matches today". */
        public boolean unavailable(){return answered==0&&failed>0;}
    }
    private static final Map<String,String> AR=new HashMap<>();
    static{
        String[][] t={{"Al Hilal","الهلال"},{"Al Nassr","النصر"},{"Al Ittihad","الاتحاد"},{"Al Ahli","الأهلي"},{"Al-Ahli","الأهلي"},
            {"Al Shabab","الشباب"},{"Al Ettifaq","الاتفاق"},{"Al Fateh","الفتح"},{"Al Taawoun","التعاون"},{"Al Raed","الرائد"},
            {"Al Fayha","الفيحاء"},{"Al Khaleej","الخليج"},{"Damac","ضمك"},{"Al Riyadh","الرياض"},{"Al Qadsiah","القادسية"},
            {"Al Okhdood","الأخدود"},{"Al Kholood","الخلود"},{"Al Orobah","العروبة"},{"Al Wehda","الوحدة"},{"Neom","نيوم"},
            {"Real Madrid","ريال مدريد"},{"Barcelona","برشلونة"},{"Atletico Madrid","أتلتيكو مدريد"},{"Sevilla","إشبيلية"},
            {"Manchester City","مانشستر سيتي"},{"Manchester United","مانشستر يونايتد"},{"Liverpool","ليفربول"},{"Arsenal","آرسنال"},
            {"Chelsea","تشيلسي"},{"Tottenham Hotspur","توتنهام"},{"Newcastle United","نيوكاسل"},{"Bayern Munich","بايرن ميونخ"},
            {"Borussia Dortmund","دورتموند"},{"Paris Saint-Germain","باريس سان جيرمان"},{"Juventus","يوفنتوس"},{"Inter Milan","إنتر ميلان"},
            {"AC Milan","ميلان"},{"Napoli","نابولي"},{"Roma","روما"},{"Benfica","بنفيكا"},{"Porto","بورتو"},{"Al Sadd","السد"},{"Al Ain","العين"}};
        for(String[] x:t)AR.put(x[0],x[1]);
    }
    static String arabic(String name){String v=AR.get(name);return v==null?name:v;}

    public static List<MatchCarouselView.Match> fetch(String[] selected) { return fetch(selected, "upcoming"); }
    public static List<MatchCarouselView.Match> fetch(String[] selected, String section) { return load(selected, section).matches; }

    public static Result load(String[] selected, String section) {
        final String[] leagues=selected==null?LEAGUES:selected;
        final long now=System.currentTimeMillis();
        TimeZone riyadh=TimeZone.getTimeZone("Asia/Riyadh");
        java.util.Calendar calendar=java.util.Calendar.getInstance(riyadh);
        calendar.setTimeInMillis(now);
        calendar.set(java.util.Calendar.HOUR_OF_DAY,0);calendar.set(java.util.Calendar.MINUTE,0);
        calendar.set(java.util.Calendar.SECOND,0);calendar.set(java.util.Calendar.MILLISECOND,0);
        final long todayStart=calendar.getTimeInMillis();
        final long yesterdayStart=todayStart-24L*60*60*1000;
        final long tomorrowStart=todayStart+24L*60*60*1000;
        SimpleDateFormat day=new SimpleDateFormat("yyyyMMdd",Locale.US);
        day.setTimeZone(TimeZone.getTimeZone("UTC"));
        final String from=day.format(new Date(now-2L*24*60*60*1000)),to=day.format(new Date(now+14L*24*60*60*1000));
        ExecutorService pool=Executors.newFixedThreadPool(4);
        List<Future<List<MatchCarouselView.Match>>> jobs=new ArrayList<>();
        final String sec=section;
        for(final String league:leagues){
            if(league==null||!league.matches("[a-z0-9.]+"))continue;
            jobs.add(pool.submit(()->fetchLeague(league,sec,from,to,now,todayStart,yesterdayStart,tomorrowStart)));
        }
        Result result=new Result();
        HashSet<String> seen=new HashSet<>();
        for(Future<List<MatchCarouselView.Match>> job:jobs){
            try{
                List<MatchCarouselView.Match> rows=job.get(20,TimeUnit.SECONDS);
                result.answered++;
                for(MatchCarouselView.Match m:rows)if(seen.add(m.key))result.matches.add(m);
            }catch(Exception e){result.failed++;job.cancel(true);}
        }
        pool.shutdownNow();
        // Chronological across leagues; live matches by kickoff too, so the longest-running come first.
        Collections.sort(result.matches,(a,b)->Long.compare(a.kickoffMillis,b.kickoffMillis));
        if("today".equals(section)||"yesterday".equals(section))Collections.reverse(result.matches);
        return result;
    }

    private static List<MatchCarouselView.Match> fetchLeague(String league,String section,String from,String to,long now,
            long todayStart,long yesterdayStart,long tomorrowStart) throws Exception {
        ArrayList<MatchCarouselView.Match> out=new ArrayList<>();
        SimpleDateFormat fmt=new SimpleDateFormat("dd/MM  h:mm a",new Locale("ar","SA"));
        fmt.setTimeZone(TimeZone.getTimeZone("Asia/Riyadh"));
        HttpURLConnection c=null;
        try {
            URL url=new URL("https://site.api.espn.com/apis/site/v2/sports/soccer/"+league+"/scoreboard?dates="+from+"-"+to+"&limit=100");
            c=(HttpURLConnection)url.openConnection();
            c.setConnectTimeout(8000);c.setReadTimeout(12000);
            int code=c.getResponseCode();
            if(code==404||code==400)return out; // this competition has no feed: not a connection failure
            if(code!=200)throw new java.io.IOException("HTTP "+code);
            StringBuilder raw=new StringBuilder();
            try(BufferedReader reader=new BufferedReader(new InputStreamReader(c.getInputStream(),"UTF-8"))){
                String line;while((line=reader.readLine())!=null)raw.append(line);
            }
            JSONObject root=new JSONObject(raw.toString());
            String leagueLogo="";
            JSONArray leagueList=root.optJSONArray("leagues");
            JSONObject first=leagueList==null?null:leagueList.optJSONObject(0);
            JSONArray logos=first==null?null:first.optJSONArray("logos");
            if(logos!=null&&logos.optJSONObject(0)!=null)leagueLogo=logos.optJSONObject(0).optString("href");
            String competition=first==null?league:first.optString("name",league);
            JSONArray events=root.optJSONArray("events");
            if(events==null)return out;
            for(int i=0;i<events.length();i++){
                JSONObject e=events.optJSONObject(i);if(e==null)continue;
                JSONArray comps=e.optJSONArray("competitions");
                if(comps==null||comps.length()==0)continue;
                JSONObject comp=comps.optJSONObject(0);if(comp==null)continue;
                JSONObject status=e.optJSONObject("status");
                JSONObject type=status==null?null:status.optJSONObject("type");
                if(type==null)continue;
                String state=type.optString("state");
                JSONArray teams=comp.optJSONArray("competitors");
                if(teams==null||teams.length()<2)continue;
                String home="",away="",homeId="",awayId="",homeLogo="",awayLogo="",hs="",as="";
                for(int k=0;k<teams.length();k++){
                    JSONObject t=teams.optJSONObject(k);if(t==null)continue;
                    JSONObject team=t.optJSONObject("team");
                    String name=team==null?"":team.optString("displayName");
                    String id=team==null?"":team.optString("id"),logo=team==null?"":team.optString("logo");
                    if("home".equals(t.optString("homeAway"))){home=name;homeId=id;homeLogo=logo;hs=t.optString("score");}
                    else if("away".equals(t.optString("homeAway"))){away=name;awayId=id;awayLogo=logo;as=t.optString("score");}
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
                MatchCarouselView.Match m=new MatchCarouselView.Match(competition,arabic(home),arabic(away),fmt.format(date),kickoff);
                m.key=league+":"+e.optString("id");
                m.state=state;m.leagueLogo=leagueLogo;m.homeLogo=homeLogo;m.awayLogo=awayLogo;
                if(!"pre".equals(state)){m.homeScore=hs;m.awayScore=as;}
                String detail=type.optString("shortDetail",type.optString("detail"));
                m.status=statusText(state,detail,status==null?"":status.optString("displayClock"),type.optString("name"));
                JSONArray details=comp.optJSONArray("details");
                if(details!=null)for(int k=0;k<details.length();k++){
                    JSONObject d=details.optJSONObject(k);if(d==null||!d.optBoolean("scoringPlay"))continue;
                    JSONObject team=d.optJSONObject("team");String tid=team==null?"":team.optString("id");
                    JSONArray who=d.optJSONArray("athletesInvolved");JSONObject p=who==null?null:who.optJSONObject(0);
                    String player=p==null?"":p.optString("displayName",p.optString("shortName"));
                    JSONObject clock=d.optJSONObject("clock");String minute=clock==null?"":clock.optString("displayValue");
                    JSONObject dt=d.optJSONObject("type");String kind=dt==null?"":dt.optString("text").toLowerCase(Locale.ROOT);
                    String line=(player.isEmpty()?"هدف":player)+(minute.isEmpty()?"":"  "+minute)
                            +(kind.contains("own")?"  (عكسي)":kind.contains("penalty")?"  (جزاء)":"");
                    // An own goal is credited to the other side's tally by ESPN's team field already.
                    if(tid.equals(homeId))m.goalsHome.add(line);else if(tid.equals(awayId))m.goalsAway.add(line);
                }
                out.add(m);
            }
        }finally{if(c!=null)c.disconnect();}
        return out;
    }

    private static String statusText(String state,String detail,String clock,String name){
        if("in".equals(state)){
            if(name.contains("HALF")||"HT".equalsIgnoreCase(detail))return "استراحة";
            String shown=detail.isEmpty()?clock:detail;
            return "مباشر"+(shown.isEmpty()?"":"  "+shown);
        }
        if("post".equals(state)){
            if(name.contains("FULL")||"FT".equalsIgnoreCase(detail)||detail.isEmpty())return "انتهت";
            if(detail.toUpperCase(Locale.ROOT).startsWith("AET")||name.contains("EXTRA"))return "انتهت بعد التمديد";
            if(name.contains("PEN")||detail.toUpperCase(Locale.ROOT).contains("PEN"))return "انتهت بركلات الترجيح";
            return "انتهت";
        }
        return "";
    }
}
