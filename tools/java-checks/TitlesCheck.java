package io.ouswis.tv.standalone;

/** Plain-JVM check of Titles (match titles → Arabic, team matching for the guide). Run by tools/check-titles.sh. */
public final class TitlesCheck {
    static int bad;
    static void eq(String want,String got){if(!want.equals(got)){bad++;System.out.println("FAIL\n  want: "+want+"\n  got:  "+got);}}
    static void is(boolean want,boolean got,String what){if(want!=got){bad++;System.out.println("FAIL "+what);}}
    public static void main(String[] a){
        String iso="⁨",end="⁩";
        eq("يوفنتوس × ليون — دوري أبطال أوروبا للسيدات",Titles.arabic("Juventus vs OL Lyonnes - UEFA Women's Champions League"));
        eq("آرسنال × تشيلسي — الدوري الإنجليزي الممتاز",Titles.arabic("Arsenal vs Chelsea - English Premier League 2026/2027 - MD4"));
        eq("ريال مدريد × برشلونة — الدوري الإسباني",Titles.arabic("Real Madrid vs Barcelona - LaLiga - Week 10"));
        eq("الهلال × النصر — دوري روشن السعودي",Titles.arabic("Al Hilal vs Al Nassr - Saudi Pro League"));
        eq("إنتر ميلان × ميلان — الدوري الإيطالي",Titles.arabic("Inter vs AC Milan - Serie A 2026/27 - Matchday 12"));
        eq("بايرن ميونخ × دورتموند — الدوري الألماني",Titles.arabic("Bayern Munich vs Borussia Dortmund - Bundesliga"));
        eq("ليفربول × "+iso+"Zzyzx United"+end+" — دوري أبطال أوروبا",Titles.arabic("Liverpool vs Zzyzx United - UEFA Champions League - MD3"));
        eq("Game of Thrones",Titles.arabic("Game of Thrones"));
        eq("غانا × الصومال — كأس الأمم الأفريقية",Titles.arabic("غانا × الصومال — كأس الأمم الأفريقية"));
        eq("غانا","غانا".equals(Titles.parseMatch("غانا × الصومال — كأس الأمم الأفريقية")[0])?"غانا":"x");
        eq("كأس الأمم الأفريقية",Titles.parseMatch("غانا × الصومال — كأس الأمم الأفريقية")[2]);
        eq("دوري الأمم",Titles.shortLeague("دوري الأمم الأوروبية"));
        eq("تصفيات أفريقيا",Titles.shortLeague("تصفيات كأس الأمم الأفريقية"));
        eq("تصفيات كأس العالم",Titles.filterLabel("تصفيات كأس العالم - آسيا"));
        eq("دوري روشن",Titles.parseMatch("الهلال × النصر · دوري روشن")[2]);
        eq("النصر",Titles.parseMatch("الهلال × النصر · دوري روشن")[1]);
        is(true,Titles.isRerun("Ghana vs Somalia - Replay"),"replay");
        is(false,Titles.isRerun("Ghana vs Somalia"),"live");
        eq("نشرة الأخبار",Titles.arabic("نشرة الأخبار"));
        eq("",Titles.arabic(null));
        String t="Juventus vs OL Lyonnes - UEFA Women's Champions League";
        is(true,Titles.mentions(t,"Juventus","يوفنتوس"),"Juventus in title");
        is(true,Titles.mentions(t,"Lyon","ليون"),"Lyon in OL Lyonnes");
        is(false,Titles.mentions(t,"Manchester City","مانشستر سيتي"),"City not in title");
        is(true,Titles.mentions("الهلال × النصر · دوري روشن","Al Nassr","النصر"),"Arabic title");
        is(false,Titles.mentions("Arsenal vs Chelsea","Roma","روما"),"Roma not in title");
        // Round C: the match the viewer reported (beIN SPORTS 2 HD's guide says "غانا × الصومال" and the card said "اختر القناة")
        is(true,Titles.sameMatch("غانا × الصومال — كأس الأمم الأفريقية","Ghana","غانا","Somalia","الصومال"),"Ghana x Somalia, Arabic title");
        is(true,Titles.sameMatch("Ghana vs Somalia - Africa Cup of Nations Qualifying","Ghana","غانا","Somalia","الصومال"),"Ghana vs Somalia, English title");
        is(true,Titles.sameMatch("GHANA  v  SOMALIA (Live)","Ghana","غانا","Somalia","الصومال"),"case and symbols");
        is(true,Titles.sameMatch("الصومال × غانا","Ghana","غانا","Somalia","الصومال"),"swapped order");
        is(true,Titles.sameMatch("غانا - الصومال","Ghana","غانا","Somalia","الصومال"),"dash between teams");
        is(true,Titles.sameMatch("غانا الصومال","Ghana","غانا","Somalia","الصومال"),"no separator");
        is(true,Titles.sameMatch("Ghana U23 FC vs Somalia FC","Ghana","غانا","Somalia","الصومال"),"FC and U23 ignored");
        is(true,Titles.sameMatch("غانا × الصومال","Ghana","غانا","Somalia","صومال"),"ال ignored");
        is(false,Titles.sameMatch("غانا × نيجيريا","Ghana","غانا","Somalia","الصومال"),"other opponent");
        is(false,Titles.sameMatch("Arsenal vs Chelsea","Ghana","غانا","Somalia","الصومال"),"other match");
        is(true,Titles.sameTeam("Man City","Manchester City","مانشستر سيتي"),"Man City");
        if(bad>0){System.out.println(bad+" failed");System.exit(1);}
        System.out.println("TitlesCheck: all passed");
    }
}
