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
        eq("نشرة الأخبار",Titles.arabic("نشرة الأخبار"));
        eq("",Titles.arabic(null));
        String t="Juventus vs OL Lyonnes - UEFA Women's Champions League";
        is(true,Titles.mentions(t,"Juventus","يوفنتوس"),"Juventus in title");
        is(true,Titles.mentions(t,"Lyon","ليون"),"Lyon in OL Lyonnes");
        is(false,Titles.mentions(t,"Manchester City","مانشستر سيتي"),"City not in title");
        is(true,Titles.mentions("الهلال × النصر · دوري روشن","Al Nassr","النصر"),"Arabic title");
        is(false,Titles.mentions("Arsenal vs Chelsea","Roma","روما"),"Roma not in title");
        if(bad>0){System.out.println(bad+" failed");System.exit(1);}
        System.out.println("TitlesCheck: all passed");
    }
}
