package io.ouswis.tv.standalone;

import org.json.JSONArray;
import org.json.JSONObject;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;

public final class MatchesFeedTest {
    private static long at(String s){return OffsetDateTime.parse(s).toInstant().toEpochMilli();}
    private static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
    private static JSONObject event(String id,String date,String state) throws Exception {
        JSONObject home=new JSONObject().put("homeAway","home").put("score","2")
                .put("team",new JSONObject().put("id","h").put("displayName","Liverpool").put("logo","https://example.com/home.png"));
        JSONObject away=new JSONObject().put("homeAway","away").put("score","1")
                .put("team",new JSONObject().put("id","a").put("displayName","Arsenal").put("logo","https://example.com/away.png"));
        JSONObject goal=new JSONObject().put("scoringPlay",true).put("team",new JSONObject().put("id","h"))
                .put("athletesInvolved",new JSONArray().put(new JSONObject().put("displayName","Player")))
                .put("clock",new JSONObject().put("displayValue","77′"));
        return new JSONObject().put("id",id).put("date",date)
                .put("status",new JSONObject().put("type",new JSONObject().put("state",state).put("name","STATUS_FULL_TIME")))
                .put("competitions",new JSONArray().put(new JSONObject().put("competitors",new JSONArray().put(home).put(away))
                        .put("details",new JSONArray().put(goal))));
    }
    public static void main(String[] args) throws Exception {
        check(UpcomingMatches.queryMonths("20261105","20261121").equals(Arrays.asList("202611")),"Use a single supported month");
        check(UpcomingMatches.queryMonths("20261230","20270114").equals(Arrays.asList("202612","202701")),"Cross-year fixtures");
        UpcomingMatches.Result partial=new UpcomingMatches.Result();partial.answered=1;partial.failed=1;
        check(partial.unavailable(),"An incomplete empty response must not claim no fixtures exist");
        partial.failed=0;check(!partial.unavailable(),"A valid empty response is not a connection error");
        long now=at("2026-11-07T11:00Z"),start=at("2026-11-06T21:00Z"),end=start+86_400_000L;
        JSONArray events=new JSONArray()
                .put(event("minute","2026-11-07T12:30Z","pre"))
                .put(event("seconds","2026-11-08T15:00:00Z","pre"))
                .put(event("fraction","2026-11-09T18:00:00.000Z","pre"))
                .put(event("today","2026-11-06T22:30:00Z","post"))
                .put(event("yesterday","2026-11-06T20:59Z","post"))
                .put(event("live","2026-11-07T10:30Z","in"))
                .put(event("past","2026-11-07T09:00Z","pre"))
                .put(event("too-far","2026-12-01T18:00Z","pre"))
                .put(event("bad","invalid-date","pre"));
        JSONObject root=new JSONObject().put("events",events).put("leagues",new JSONArray().put(new JSONObject().put("name","Premier League")));
        List<MatchCarouselView.Match> upcoming=UpcomingMatches.parseFeed(root,"eng.1","upcoming",now,start,start-86_400_000L,end);
        check(upcoming.size()==3,"All supported timestamp formats and 14-day window; malformed event must not erase others");
        check(upcoming.get(0).home.equals("ليفربول"),"Keep Arabic team names");
        check(upcoming.get(0).homeLogo.endsWith("home.png"),"Keep crests");
        check(UpcomingMatches.parseFeed(root,"eng.1","today",now,start,start-86_400_000L,end).size()==1,"Today uses Riyadh midnight, not UTC midnight");
        check(UpcomingMatches.parseFeed(root,"eng.1","yesterday",now,start,start-86_400_000L,end).size()==1,"Yesterday boundary");
        List<MatchCarouselView.Match> live=UpcomingMatches.parseFeed(root,"eng.1","live",now,start,start-86_400_000L,end);
        check(live.size()==1&&live.get(0).homeScore.equals("2")&&live.get(0).goalsHome.size()==1,"Keep live scores/goals");
        System.out.println("PASS: supported date queries, timestamps, Riyadh boundaries, Arabic names, crests, scores and goals.");
        if(args.length>0&&args[0].equals("--live")){
            String[] leagues={"eng.1","esp.1","ita.1","ger.1"};
            UpcomingMatches.Result actual=UpcomingMatches.load(leagues,"upcoming");
            check(actual.answered==4&&actual.failed==0,"All four real league feeds must answer successfully");
            for(String league:leagues){
                int count=0;for(MatchCarouselView.Match m:actual.matches)if(m.key.startsWith(league+":"))count++;
                System.out.println("LIVE: "+league+" upcoming fixtures="+count);
            }
            System.out.println("PASS: production Java loader fetched real league data with supported month requests.");
        }
    }
}
