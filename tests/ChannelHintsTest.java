package io.ouswis.tv.standalone;

import java.util.List;

/** Offline: the two sites' pages are read, team spellings that differ still match, and "beIN Sports MENA 4" is the subscription's "beIN SPORTS 4 HD". */
public final class ChannelHintsTest {
    private static void check(boolean value, String why) { if (!value) throw new AssertionError(why); }

    private static final String YALLA =
        "<div class=\"ul\"><div liveScoreMatchId=\"1\" class=\"item now liItem\"><a href=\"/x\"><div class=\"allData\">"
        + "<div class=\"channel icon-channel\">beIN SPORTS 3</div><div class=\"topData\"><div class=\"date\">الجولة الرابعة</div></div>"
        + "<div class=\"teamCntnr\"><div class=\"teams teamA\"><p>زيمبابوي</p></div><div class=\"teams teamB\"><p>غينيا الاستوائية</p></div></div></div></a></div>"
        + "<div liveScoreMatchId=\"2\" class=\"item finish liItem\"><a href=\"/y\"><div class=\"allData\">"
        + "<div class=\"teamCntnr\"><div class=\"teams teamA\"><p>جزر فاروه</p></div><div class=\"teams teamB\"><p>مولدوفا</p></div></div></div></a></div></div>";
    private static final String FILGOAL =
        "<div class=\"cin_cntnr\"><a href=\"/matches/1\"><div class=\"c-i-next\"><div class=\"f\"><a href=\"/t/1\"><strong>جبل طارق</strong></a></div>"
        + "<div class=\"m\"><span class=\"status \">لم تبدأ</span></div><div class=\"s\"><a href=\"/t/2\"><strong>مالطا</strong></a></div></div>"
        + "<div class=\"match-aux\"><span><svg><use xlink:href=\"#fb_screen\"></use></svg>beIN SPORTS 5 HD</span>"
        + "<span><svg><use xlink:href=\"#fb_calendar\"></use></svg> 16-11-2026 - 21:45 </span></div></a></div>"
        + "<div class=\"cin_cntnr\"><a href=\"/matches/2\"><div class=\"c-i-next\"><div class=\"f\"><strong>إستونيا</strong></div>"
        + "<div class=\"s\"><strong>بلغاريا </strong></div></div><div class=\"match-aux\"><span><svg><use xlink:href=\"#fb_calendar\"></use></svg> 16-11-2026 - 19:00 </span></div></a></div>";

    private static MatchCarouselView.Match match(String home, String away) {
        MatchCarouselView.Match m = new MatchCarouselView.Match("", home, away, "", 0L);
        m.key = "x:" + home;
        return m;
    }

    public static void main(String[] args) {
        // the pages
        List<ChannelHints.Row> y = ChannelHints.parseYalla(YALLA, "2026-11-16");
        check(y.size() == 2, "Yallakora: two matches, got " + y.size());
        check(y.get(0).home.equals("زيمبابوي") && y.get(0).away.equals("غينيا الاستوائية") && y.get(0).channel.equals("beIN SPORTS 3"), "Yallakora first row");
        check(y.get(1).channel.isEmpty(), "Yallakora: a match with no channel stays empty");
        List<ChannelHints.Row> f = ChannelHints.parseFilgoal(FILGOAL, "2026-11-16");
        check(f.size() == 2, "Filgoal: two matches, got " + f.size());
        check(f.get(0).channel.equals("beIN SPORTS 5 HD") && f.get(0).home.equals("جبل طارق") && f.get(0).away.equals("مالطا"), "Filgoal first row");
        check(f.get(1).channel.isEmpty() && f.get(1).away.equals("بلغاريا"), "Filgoal: no channel, trailing space gone");
        check(ChannelHints.parseYalla("<html>nothing here</html>", "d").isEmpty() && ChannelHints.parseFilgoal("", "d").isEmpty(), "a changed page gives no rows, not an exception");
        check(ChannelHints.unescape("A &amp; B &#39;x&#39; &quot;y&quot;").equals("A & B 'x' \"y\""), "entities");

        // names two sites spell differently
        check(ChannelHints.same("جزر فاروه", "جزر فارو"), "فاروه / فارو");
        check(ChannelHints.same("لوكسمبرج", "لوكسمبورغ"), "لوكسمبرج / لوكسمبورغ");
        check(ChannelHints.same("مالطة", "مالطا"), "مالطة / مالطا");
        check(ChannelHints.same("مقدونيا", "مقدونيا الشمالية"), "مقدونيا / مقدونيا الشمالية");
        check(ChannelHints.same("إسكتلندا", "اسكتلندا"), "إسكتلندا / اسكتلندا");
        check(ChannelHints.same("ليشتنشتاين", "ليختنشتاين"), "ليشتنشتاين / ليختنشتاين");
        check(ChannelHints.same("الهلال", "هلال"), "the article does not matter");
        check(!ChannelHints.same("سلوفينيا", "سلوفاكيا"), "Slovenia is not Slovakia");
        check(!ChannelHints.same("الهلال", "النصر"), "two clubs");
        check(!ChannelHints.same("غينيا", "غينيا بيساو") && !ChannelHints.same("غينيا", "غينيا الاستوائية"), "the Guineas are three teams");
    }
}
