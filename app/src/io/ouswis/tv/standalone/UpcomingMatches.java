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
import java.util.concurrent.ConcurrentHashMap;
import java.time.OffsetDateTime;

/** Free, keyless football fixtures from ESPN's unofficial scoreboard feed (no fabricated data).
 * Competitions load in parallel; one that fails never blocks the others. The endpoint is undocumented.
 */
public final class UpcomingMatches {
    private UpcomingMatches(){}
    public static final String[] LEAGUES={
        "ksa.1", "ksa.kings.cup", "afc.champions", "afc.cup", "afc.asian.cup", "fifa.worldq.afc", "fifa.world",
        "uefa.champions", "uefa.wchampions", "caf.champions", "caf.confed", "caf.nations_qual", "fifa.worldq.caf", "fifa.worldq.uefa",
        "fifa.worldq.conmebol", "fifa.worldq.concacaf", "fifa.friendly", "uefa.europa", "uefa.europa.conf", "uefa.nations",
        "eng.1", "eng.fa", "eng.league_cup", "esp.1", "esp.copa_del_rey", "ita.1", "ita.coppa_italia",
        "ger.1", "ger.dfb_pokal", "fra.1", "fra.coupe_de_france", "por.1", "ned.1", "tur.1", "caf.nations"
    };
    private static final String[][] COMPETITIONS={
        {"Saudi Pro League","دوري روشن السعودي"},{"Saudi Kings Cup","كأس الملك"},{"King's Cup","كأس الملك"},
        {"AFC Champions League Elite","دوري أبطال آسيا للنخبة"},{"AFC Champions League","دوري أبطال آسيا"},
        {"AFC Champions League Two","دوري أبطال آسيا 2"},{"AFC Asian Cup","كأس آسيا"},
        {"FIFA World Cup","كأس العالم"},{"UEFA Champions League","دوري أبطال أوروبا"},{"UEFA Women's Champions League","دوري أبطال أوروبا للسيدات"},
        {"UEFA Europa League","الدوري الأوروبي"},{"UEFA Conference League","دوري المؤتمر الأوروبي"},
        {"UEFA Europa Conference League","دوري المؤتمر الأوروبي"},{"UEFA Nations League","دوري الأمم الأوروبية"},
        {"English Premier League","الدوري الإنجليزي الممتاز"},{"English FA Cup","كأس الاتحاد الإنجليزي"},
        {"English League Cup","كأس الرابطة الإنجليزية"},{"Spanish LALIGA","الدوري الإسباني"},
        {"Spanish Copa del Rey","كأس ملك إسبانيا"},{"Italian Serie A","الدوري الإيطالي"},
        {"Italian Coppa Italia","كأس إيطاليا"},{"German Bundesliga","الدوري الألماني"},
        {"German DFB-Pokal","كأس ألمانيا"},{"French Ligue 1","الدوري الفرنسي"},
        {"French Coupe de France","كأس فرنسا"},{"Portuguese Primeira Liga","الدوري البرتغالي"},
        {"Dutch Eredivisie","الدوري الهولندي"},{"Turkish Super Lig","الدوري التركي"},
        {"Africa Cup of Nations","كأس الأمم الأفريقية"},{"Africa Cup of Nations Qualifying","تصفيات كأس الأمم الأفريقية"},
        {"CAF Champions League","دوري أبطال أفريقيا"},{"CAF Confederation Cup","كأس الكونفدرالية الأفريقية"},
        {"International Friendly","المباراة الودية الدولية"}};
    static String arabicCompetition(String name){
        for(String[] c:COMPETITIONS)if(c[0].equalsIgnoreCase(name))return c[1];
        if(name.startsWith("FIFA World Cup")){
            String region=name.contains("AFC")?" - آسيا":name.contains("CAF")?" - أفريقيا":name.contains("UEFA")?" - أوروبا":name.contains("CONMEBOL")?" - أمريكا الجنوبية"
                    :name.toLowerCase(java.util.Locale.ROOT).contains("concacaf")?" - الكونكاكاف":"";
            return "تصفيات كأس العالم"+region;
        }
        return name;
    }
    /** What a load found: the matches, and how many competitions answered or didn't. */
    public static final class Result {
        public final List<MatchCarouselView.Match> matches=new ArrayList<>();
        public int answered, failed;
        /** A failed feed must not be presented as a confirmed empty schedule. */
        public boolean unavailable(){return matches.isEmpty()&&failed>0;}
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
    static String arabic(String name){String v=AR.get(name);return v!=null?v:TeamNames.arabic(name);}

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
        ExecutorService pool=Executors.newFixedThreadPool(6);
        List<Future<List<MatchCarouselView.Match>>> jobs=new ArrayList<>();
        final String sec=section;
        for(final String league:leagues){
            if(league==null||!league.matches("[a-z0-9.]+"))continue;
            jobs.add(pool.submit(()->fetchLeague(league,sec,from,to,now,todayStart,yesterdayStart,tomorrowStart)));
        }
        Result result=new Result();
        HashSet<String> seen=new HashSet<>();
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);
        for(Future<List<MatchCarouselView.Match>> job:jobs){
            try{
                List<MatchCarouselView.Match> rows=job.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
                result.answered++;
                for(MatchCarouselView.Match m:rows)if(seen.add(m.key))result.matches.add(m);
            }catch(InterruptedException e){Thread.currentThread().interrupt();break;}
            catch(Exception e){result.failed++;job.cancel(true);System.err.println("Matches feed: "+e);}
        }
        for(Future<?> job:jobs)if(!job.isDone())job.cancel(true);
        pool.shutdownNow();
        // Chronological across leagues; live matches by kickoff too, so the longest-running come first.
        Collections.sort(result.matches,(a,b)->Long.compare(a.kickoffMillis,b.kickoffMillis));
        if("today".equals(section)||"yesterday".equals(section))Collections.reverse(result.matches);
        return result;
    }

    private static final class CachedFeed {
        final String json; final long at;
        CachedFeed(String json){this.json=json;at=System.currentTimeMillis();}
    }
    private static final Map<String,CachedFeed> FEEDS=new ConcurrentHashMap<>();

    /** Soccer's feed accepts one day or month, but rejects a date range with HTTP 400. */
    static List<String> queryMonths(String from,String to){
        ArrayList<String> months=new ArrayList<>();
        java.util.Calendar date=java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        date.clear();date.set(Integer.parseInt(from.substring(0,4)),Integer.parseInt(from.substring(4,6))-1,1);
        SimpleDateFormat format=new SimpleDateFormat("yyyyMM",Locale.US);format.setTimeZone(date.getTimeZone());
        String last=to.substring(0,6);
        for(int i=0;i<3;i++){
            String month=format.format(date.getTime());months.add(month);
            if(month.equals(last))break;
            date.add(java.util.Calendar.MONTH,1);
        }
        return months;
    }

    private static JSONObject readFeed(String league,String month) throws Exception {
        String key=league+":"+month;
        CachedFeed cached=FEEDS.get(key);
        if(cached!=null&&System.currentTimeMillis()-cached.at<60_000)return new JSONObject(cached.json);
        URL url=new URL("https://site.api.espn.com/apis/site/v2/sports/soccer/"+league+"/scoreboard?dates="+month+"&limit=1000");
        HttpURLConnection c=(HttpURLConnection)url.openConnection();
        c.setConnectTimeout(8000);c.setReadTimeout(12000);
        c.setRequestProperty("Accept","application/json");
        c.setRequestProperty("User-Agent","OUSWIS-TV/1.0 (Android)");
        try{
            int code=c.getResponseCode();
            if(code!=200)throw new java.io.IOException(league+": HTTP "+code);
            StringBuilder raw=new StringBuilder();
            try(BufferedReader reader=new BufferedReader(new InputStreamReader(c.getInputStream(),"UTF-8"))){
                String line;while((line=reader.readLine())!=null)raw.append(line);
            }
            JSONObject root=new JSONObject(raw.toString());
            if(root.optJSONArray("events")==null)throw new java.io.IOException(league+": missing events");
            if(FEEDS.size()>48)FEEDS.clear();
            FEEDS.put(key,new CachedFeed(raw.toString()));
            return root;
        }finally{c.disconnect();}
    }

    private static List<MatchCarouselView.Match> fetchLeague(String league,String section,String from,String to,long now,
            long todayStart,long yesterdayStart,long tomorrowStart) throws Exception {
        ArrayList<MatchCarouselView.Match> out=new ArrayList<>();
        for(String month:queryMonths(from,to)){
            if(Thread.currentThread().isInterrupted())throw new InterruptedException();
            out.addAll(parseFeed(readFeed(league,month),league,section,now,todayStart,yesterdayStart,tomorrowStart));
        }
        return out;
    }

    static List<MatchCarouselView.Match> parseFeed(JSONObject root,String league,String section,long now,
            long todayStart,long yesterdayStart,long tomorrowStart){
        ArrayList<MatchCarouselView.Match> out=new ArrayList<>();
        SimpleDateFormat fmt=new SimpleDateFormat("dd/MM  h:mm a",new Locale("ar","SA"));
        fmt.setTimeZone(TimeZone.getTimeZone("Asia/Riyadh"));
            String leagueLogo="";
            JSONArray leagueList=root.optJSONArray("leagues");
            JSONObject first=leagueList==null?null:leagueList.optJSONObject(0);
            JSONArray logos=first==null?null:first.optJSONArray("logos");
            if(logos!=null&&logos.optJSONObject(0)!=null)leagueLogo=logos.optJSONObject(0).optString("href");
            String competition=arabicCompetition(first==null?league:first.optString("name",league));
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
                long kickoff;
                try{kickoff=OffsetDateTime.parse(utc).toInstant().toEpochMilli();}
                catch(java.time.format.DateTimeParseException badDate){continue;}
                Date date=new Date(kickoff);
                boolean include;
                if("live".equals(section)) include="in".equals(state);
                else if("today".equals(section)) include="post".equals(state)&&kickoff>=todayStart&&kickoff<tomorrowStart;
                else if("yesterday".equals(section)) include="post".equals(state)&&kickoff>=yesterdayStart&&kickoff<todayStart;
                else include="pre".equals(state)&&kickoff>=now&&kickoff<now+14L*24*60*60*1000;
                if(!include)continue;
                MatchCarouselView.Match m=new MatchCarouselView.Match(competition,arabic(home),arabic(away),fmt.format(date),kickoff);
                m.homeEn=home;m.awayEn=away;
                JSONArray casts=comp.optJSONArray("broadcasts");
                for(int k=0;casts!=null&&k<casts.length();k++){
                    JSONObject b=casts.optJSONObject(k);JSONArray names=b==null?null:b.optJSONArray("names");
                    for(int q=0;names!=null&&q<names.length();q++)if(!names.optString(q).isEmpty())m.broadcasts.add(names.optString(q));
                }
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
