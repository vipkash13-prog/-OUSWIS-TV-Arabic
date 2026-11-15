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
    private static MatchCarouselView.Match match(String league,int n,long kickoff){
        MatchCarouselView.Match m=new MatchCarouselView.Match(league,"H"+n,"A"+n,"",kickoff);
        m.key=league+":"+n;m.state="pre";return m;
    }
    /** 35 competitions of `per` matches each around `now`, as a refresh would bring them. */
    private static UpcomingMatches.Full answer(int firstLeague,int leagues,int per,long now){
        UpcomingMatches.Full f=new UpcomingMatches.Full();
        for(int l=firstLeague;l<firstLeague+leagues;l++){
            String lg="lg"+l;f.ok.add(lg);
            for(int i=0;i<per;i++)f.matches.add(match(lg,i,now+(i%3-1)*86_400_000L/2));   // yesterday, today and tomorrow
        }
        return f;
    }
    private static int saved(String league){int n=0;for(MatchCarouselView.Match m:MatchStore.snapshot())if(MatchStore.leagueOf(m).equals(league))n++;return n;}
    /** The 14:19 bug: a partial answer marked as success emptied the matches screen. */
    private static void refreshRules(){
        long now=System.currentTimeMillis();
        MatchStore.resetForTest();
        UpcomingMatches.Full full=answer(0,35,12,now);   // 420 matches, 35 competitions
        MatchStore.apply(null,full,false,now);
        check(MatchStore.snapshot().size()==420&&!MatchStore.failed(),"A complete refresh is saved and counts as success");
        // 48 leagues' worth of nothing much: 12 of 35 competitions answered with 79 matches
        UpcomingMatches.Full small=answer(0,12,6,now);
        for(int i=0;i<7;i++)small.matches.add(match("lg0",100+i,now));
        for(int l=12;l<35;l++)small.failed.put("lg"+l,"HTTP 503");
        MatchStore.apply(null,small,false,now+1000);
        check(MatchStore.snapshot().size()>=420,"A partial refresh must not remove saved matches, got "+MatchStore.snapshot().size());
        check(MatchStore.failed(),"A partial refresh is not a success");
        check(saved("lg20")==12,"A competition that failed keeps its saved matches");
        // answered but far too small (nobody failed): still partial, nothing is lost
        MatchStore.resetForTest();MatchStore.apply(null,answer(0,35,12,now),false,now);
        MatchStore.apply(null,answer(0,10,12,now),false,now+1000);
        check(MatchStore.snapshot().size()==420&&MatchStore.failed(),"Fewer than 70% of the competitions is partial even with no failure");
        // an empty answer over saved data
        UpcomingMatches.Full empty=new UpcomingMatches.Full();for(int l=0;l<35;l++)empty.ok.add("lg"+l);
        MatchStore.apply(null,empty,false,now+2000);
        check(MatchStore.snapshot().size()==420&&MatchStore.failed(),"An empty answer never empties the list");
        // the retry brings back one of the failed competitions: only it is replaced
        MatchStore.resetForTest();MatchStore.apply(null,answer(0,35,12,now),false,now);
        UpcomingMatches.Full part=answer(0,34,12,now);part.failed.put("lg34","انتهت المهلة");
        MatchStore.apply(null,part,false,now+1000);
        check(MatchStore.failed()&&saved("lg34")==12,"One failed competition: the rest is fresh, it keeps its saved matches");
        UpcomingMatches.Full again=answer(34,1,5,now);
        MatchStore.apply(null,again,true,now+120_000);
        check(!MatchStore.failed()&&saved("lg34")==5,"The retry replaces only the recovered competition and completes the update");
        check(saved("lg3")==12,"The retry leaves the others alone");
        check(MatchStore.failures(part.failed).equals("lg34 (انتهت المهلة)"),"Failed competitions are named with the reason");
        System.out.println("PASS: partial refreshes never empty the matches; failed competitions keep their saved matches; retry merges only the recovered ones.");
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
        refreshRules();
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
