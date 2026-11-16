package io.ouswis.tv.standalone;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Which channel broadcasts a match, as two Arabic football sites write it (Yallakora, then Filgoal). A backup of ESPN,
 * which stays the source of the matches and scores: this only names the channel. Every request is made on a background
 * thread, each address at most once every 30 minutes (answers are cached on disk and in memory), and whatever goes wrong
 * (no answer, a changed page, an exception) only means there is no hint: the guide still decides then.
 */
final class ChannelHints {
    private ChannelHints() {}

    static final String YALLA = "يلا كورة", FILGOAL = "فيل جول";
    private static final long TTL = 30 * 60_000L;       // one address, once in 30 minutes at most (failures too)
    private static final int DAYS = 3, MAX_BYTES = 2_000_000;
    private static final String FILE = "channel-hints.json";

    /** One match of a site's page: the two teams as the site spells them and the channel text. */
    static final class Row {
        final String site, day, home, away, channel;
        Row(String site, String day, String home, String away, String channel) {
            this.site = site; this.day = day; this.home = home; this.away = away; this.channel = channel;
        }
    }

    private static final Object LOCK = new Object();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ouswis-channel-hints"); t.setDaemon(true); return t;
    });
    private static final AtomicBoolean running = new AtomicBoolean();
    private static volatile List<Row> rows = new ArrayList<>();
    private static final java.util.Map<String, Long> fetched = new java.util.concurrent.ConcurrentHashMap<>();   // "site|day" → when
    private static volatile boolean loaded;
    private static volatile long dueAt;   // the earliest time any address may be asked again

    // MARK: reading for a match

    /** The channel texts the sites give for this match (Yallakora's first), "beIN SPORTS 4"; empty when none says. Safe on any thread. */
    static List<String> channelsFor(MatchCarouselView.Match m) {
        List<String> out = new ArrayList<>();
        if (m == null) return out;
        List<Row> snapshot = rows;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String site : new String[] {YALLA, FILGOAL}) {
            for (Row r : snapshot) {
                if (!r.site.equals(site) || r.channel.isEmpty() || !sameMatch(r, m)) continue;
                for (String part : r.channel.split("\\s*(?:/|،|,|\\+|&| و )\\s*")) {
                    String clean = clean(part);
                    if (!clean.isEmpty() && seen.add(Titles.channelKey(clean))) out.add(clean);
                }
            }
        }
        return out;
    }


    /**
     * A channel name for comparing, whatever the provider decorated it with: no prefix ("8K:", "AR|"), no quality tag, no
     * symbols, and a symbol sitting inside a word is a letter O (providers write "beIN SP⚽RTS 2" and "beIN SP●RTS 2").
     */
    static String hintKey(String name) {
        String n = Titles.channelName(java.text.Normalizer.normalize(name == null ? "" : name, java.text.Normalizer.Form.NFKD));
        StringBuilder b = new StringBuilder();
        int[] cps = n.codePoints().toArray();
        for (int i = 0; i < cps.length; i++) {
            int cp = cps[i];
            if (Character.isLetterOrDigit(cp)) { b.appendCodePoint(Character.toLowerCase(cp)); continue; }
            boolean symbol = Character.getType(cp) == Character.OTHER_SYMBOL || Character.getType(cp) == Character.MATH_SYMBOL;
            if (symbol && i > 0 && i < cps.length - 1 && Character.isLetter(cps[i - 1]) && Character.isLetter(cps[i + 1])) b.append('o');
        }
        return b.toString();
    }
    /** The same without any "o": a last try when a provider's decoration is not a clean O. */
    static String looseKey(String name) { return hintKey(name).replace("o", ""); }

    /** Which site named a channel for this match ("يلا كورة"), for the log; empty when none. */
    static String siteFor(MatchCarouselView.Match m) {
        if (m == null) return "";
        for (String site : new String[] {YALLA, FILGOAL})
            for (Row r : rows) if (r.site.equals(site) && !r.channel.isEmpty() && sameMatch(r, m)) return site;
        return "";
    }

    /** "beIN Sports MENA 4" and "beIN SPORTS 4 HD" are both "beIN SPORTS 4": the subscription's copy is chosen by the caller. */
    static String clean(String raw) {
        String s = raw == null ? "" : raw.replace('\u00a0', ' ').trim();
        s = s.replaceAll("(?i)\\bMENA\\b", " ").replaceAll("(?i)\\b(?:HD|FHD|UHD|4K|RAW|LQ)\\b", " ").replaceAll("\\s{2,}", " ").trim();
        return s;
    }

    private static boolean sameMatch(Row r, MatchCarouselView.Match m) {
        if (m.home == null || m.away == null || m.home.isEmpty() || m.away.isEmpty()) return false;
        if (same(r.home, m.home) && same(r.away, m.away)) return true;
        if (same(r.home, m.away) && same(r.away, m.home)) return true;
        try { return Titles.sameMatch(r.home + " × " + r.away, m.homeEn, m.home, m.awayEn, m.away); } catch (RuntimeException e) { return false; }
    }

    // MARK: team names that two sites spell a little differently (فاروه / فارو, لوكسمبرج / لوكسمبورغ, مالطة / مالطا)

    private static String norm(String s) {
        if (s == null) return "";
        String t = s.replaceAll("[\\u064b-\\u065f\\u0640]", "").toLowerCase(Locale.ROOT)
                .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ة', 'ه').replace('ى', 'ي').replace('ئ', 'ي').replace('ؤ', 'و');
        StringBuilder b = new StringBuilder();
        for (String w : t.split("[^\\p{L}\\p{Nd}]+")) {
            if (w.isEmpty()) continue;
            if (w.startsWith("ال") && w.length() > 3) w = w.substring(2);
            b.append(w);
        }
        String joined = b.toString();
        return joined.equals("مقدونياشماليه") ? "مقدونيا" : joined;   // the one name a site shortens by a word
    }
    static boolean same(String a, String b) {
        String x = norm(a), y = norm(b);
        if (x.isEmpty() || y.isEmpty()) return false;
        if (x.equals(y)) return true;
        int limit = Math.max(x.length(), y.length()) < 9 ? 1 : 2;   // سلوفينيا and سلوفاكيا are two teams
        return distance(x, y, limit) <= limit;
    }
    private static int distance(String a, String b, int cap) {
        if (Math.abs(a.length() - b.length()) > cap) return cap + 1;
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++)
                cur[j] = Math.min(Math.min(cur[j - 1], prev[j]) + 1, prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }

    /** Only for the offline test: the rows as ("site","day","home","away","channel"). */
    static void setRowsForTest(String[][] data) {
        List<Row> list = new ArrayList<>();
        for (String[] r : data) list.add(new Row(r[0], r[1], r[2], r[3], r[4]));
        rows = list;
    }

    // MARK: refreshing (background only)

    /** Asks the sites again when an address is older than 30 minutes. Returns at once; the work happens on its own thread. */
    static void refreshIfStale(Context c) {
        try {
            long now = System.currentTimeMillis();
            if (loaded && now < dueAt) return;
            if (Busy.on()) return;   // not while a channel list or a channel is on screen
            if (!running.compareAndSet(false, true)) return;
            final Context app = c.getApplicationContext();
            WORKER.execute(() -> {
                try { cycle(app); } catch (Throwable t) { log(app, "قنوات المباريات: خطأ غير متوقع " + t.getClass().getSimpleName()); }
                finally { running.set(false); }
            });
        } catch (Throwable ignored) { }
    }

    private static List<String> days() {
        List<String> out = new ArrayList<>();
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Riyadh"));
        for (int i = 0; i < DAYS; i++) {
            out.add(String.format(Locale.US, "%04d-%02d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH)));
            cal.add(Calendar.DAY_OF_MONTH, 1);
        }
        return out;
    }

    private static void cycle(Context app) {
        loadFile(app);
        long now = System.currentTimeMillis();
        List<String> days = days();
        List<String> errors = new ArrayList<>();
        boolean changed = false, asked = false;
        List<Row> all = new ArrayList<>();
        for (Row r : rows) if (days.contains(r.day)) all.add(r);   // yesterday's rows are dropped
        boolean paused = false;
        outer:
        for (String site : new String[] {YALLA, FILGOAL}) {
            for (String day : days) {
                String key = site + "|" + day;
                Long at = fetched.get(key);
                if (at != null && now - at < TTL) continue;
                if (Busy.on()) { paused = true; break outer; }   // the rest waits for the viewer to leave the live screen
                if (asked) { try { Thread.sleep(1000); } catch (InterruptedException e) { return; } }   // one request a second at most
                asked = true;
                fetched.put(key, System.currentTimeMillis());   // a failure waits 30 minutes too
                try {
                    String html = get(url(site, day));
                    List<Row> parsed = site.equals(YALLA) ? parseYalla(html, day) : parseFilgoal(html, day);
                    if (parsed.isEmpty() && html.contains(site.equals(YALLA) ? "liveScoreMatchId=" : "cin_cntnr")) { errors.add(site + " " + day + ": ما قدرت أقرأ الصفحة (تغيّر شكلها؟)"); continue; }
                    all.removeIf(r -> r.site.equals(site) && r.day.equals(day));
                    all.addAll(parsed);
                    changed = true;
                } catch (Throwable t) {
                    errors.add(site + " " + day + ": " + describe(t));
                }
            }
        }
        dueAt = System.currentTimeMillis() + (paused ? 60_000L : TTL);
        if (!asked) { loaded = true; return; }   // everything was fresh: nothing was asked, nothing to log
        rows = all;
        loaded = true;
        saveFile(app);
        logSummary(app, errors);
        if (changed) MatchChannels.hintsChanged();
    }

    private static String describe(Throwable t) {
        String m = t.getMessage() == null ? "" : t.getMessage();
        if (m.startsWith("HTTP ")) return m;
        return t.getClass().getSimpleName();
    }

    /** One line in the playback log: how many matches each site named a channel for, out of the matches the app has in these days. */
    private static void logSummary(Context app, List<String> errors) {
        int total = 0, byYalla = 0, byFilgoal = 0, any = 0;
        long now = System.currentTimeMillis(), to = now + DAYS * 24L * 3600_000L;
        for (MatchCarouselView.Match m : MatchStore.snapshot()) {
            if (m.kickoffMillis < now - 3L * 3600_000L || m.kickoffMillis > to || (!m.live() && !m.upcoming())) continue;
            total++;
            boolean y = false, f = false;
            for (Row r : rows) {
                if (r.channel.isEmpty() || !sameMatch(r, m)) continue;
                if (r.site.equals(YALLA)) y = true; else f = true;
            }
            if (y) byYalla++;
            if (f) byFilgoal++;
            if (y || f) any++;
        }
        StringBuilder b = new StringBuilder("قنوات المباريات: " + YALLA + " لقي قناة لـ " + byYalla + " من " + total + " مباراة · "
                + FILGOAL + " لـ " + byFilgoal + " من " + total + " · معروفة من أحدهما " + any + " من " + total);
        if (!errors.isEmpty()) {
            b.append(" · أخطاء: ");
            for (int i = 0; i < Math.min(4, errors.size()); i++) b.append(i > 0 ? "، " : "").append(errors.get(i));
            if (errors.size() > 4) b.append(" …(").append(errors.size()).append(")");
        }
        log(app, b.toString());
    }

    private static void log(Context app, String what) {
        try { PlayLog.add(app, -1, what); } catch (Throwable ignored) { }
    }

    // MARK: the two sites

    private static String url(String site, String day) {
        if (site.equals(YALLA)) return "https://www.yallakora.com/match-center?date=" + day.substring(5, 7) + "/" + day.substring(8, 10) + "/" + day.substring(0, 4);
        return "https://www.filgoal.com/matches/?date=" + day;
    }

    private static String get(String address) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(12000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36");
        c.setRequestProperty("Accept-Language", "ar,en;q=0.8");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new java.io.IOException("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > MAX_BYTES) throw new java.io.IOException("too large");
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }

    private static final Pattern YALLA_CHANNEL = Pattern.compile("<div class=\"channel[^\"]*\">([^<]*)</div>");
    private static final Pattern P = Pattern.compile("<p>([^<]+)</p>");
    static List<Row> parseYalla(String html, String day) {
        List<Row> out = new ArrayList<>();
        String[] blocks = html.split("liveScoreMatchId=");
        for (int i = 1; i < blocks.length; i++) {
            Matcher ch = YALLA_CHANNEL.matcher(blocks[i]);
            List<String> names = new ArrayList<>();
            Matcher p = P.matcher(blocks[i]);
            while (p.find() && names.size() < 2) names.add(unescape(p.group(1)));
            if (names.size() < 2) continue;
            out.add(new Row(YALLA, day, names.get(0), names.get(1), ch.find() ? unescape(ch.group(1)) : ""));
        }
        return out;
    }

    private static final Pattern STRONG = Pattern.compile("<strong>([^<]+)</strong>");
    private static final Pattern FIL_CHANNEL = Pattern.compile("fb_screen\"></use></svg>([^<]*)</span>");
    static List<Row> parseFilgoal(String html, String day) {
        List<Row> out = new ArrayList<>();
        String[] blocks = html.split("<div class=\"cin_cntnr\">");
        for (int i = 1; i < blocks.length; i++) {
            List<String> names = new ArrayList<>();
            Matcher s = STRONG.matcher(blocks[i]);
            while (s.find() && names.size() < 2) names.add(unescape(s.group(1)));
            if (names.size() < 2) continue;
            Matcher ch = FIL_CHANNEL.matcher(blocks[i]);
            out.add(new Row(FILGOAL, day, names.get(0), names.get(1), ch.find() ? unescape(ch.group(1)) : ""));
        }
        return out;
    }

    private static final Pattern ENTITY = Pattern.compile("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);");
    /** The few HTML entities a team name carries (&amp; &quot; &#39; &nbsp; numeric ones); anything else stays as written. */
    static String unescape(String s) {
        Matcher m = ENTITY.matcher(s);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String e = m.group(1), r;
            try {
                if (e.startsWith("#x") || e.startsWith("#X")) r = new String(Character.toChars(Integer.parseInt(e.substring(2), 16)));
                else if (e.startsWith("#")) r = new String(Character.toChars(Integer.parseInt(e.substring(1))));
                else if (e.equals("amp")) r = "&"; else if (e.equals("quot")) r = "\""; else if (e.equals("apos")) r = "'";
                else if (e.equals("lt")) r = "<"; else if (e.equals("gt")) r = ">"; else if (e.equals("nbsp")) r = " ";
                else r = m.group();
            } catch (RuntimeException bad) { r = m.group(); }
            m.appendReplacement(out, Matcher.quoteReplacement(r));
        }
        m.appendTail(out);
        return out.toString().trim();
    }

    // MARK: the cache on disk (so a restart does not ask again)

    private static void loadFile(Context app) {
        if (loaded) return;
        try {
            File f = new File(app.getFilesDir(), FILE);
            if (!f.isFile()) return;
            JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            JSONObject at = o.optJSONObject("fetched");
            if (at != null) for (java.util.Iterator<String> it = at.keys(); it.hasNext(); ) { String k = it.next(); fetched.put(k, at.optLong(k)); }
            List<Row> list = new ArrayList<>();
            JSONArray a = o.optJSONArray("rows");
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject r = a.getJSONObject(i);
                list.add(new Row(r.optString("s"), r.optString("d"), r.optString("h"), r.optString("a"), r.optString("c")));
            }
            rows = list;
        } catch (Throwable ignored) { }
    }

    private static void saveFile(Context app) {
        try {
            JSONObject o = new JSONObject();
            JSONObject at = new JSONObject();
            for (java.util.Map.Entry<String, Long> e : fetched.entrySet()) at.put(e.getKey(), e.getValue());
            JSONArray a = new JSONArray();
            for (Row r : rows) a.put(new JSONObject().put("s", r.site).put("d", r.day).put("h", r.home).put("a", r.away).put("c", r.channel));
            o.put("fetched", at).put("rows", a);
            File f = new File(app.getFilesDir(), FILE), tmp = new File(app.getFilesDir(), FILE + ".tmp");
            Files.write(tmp.toPath(), o.toString().getBytes(StandardCharsets.UTF_8));
            if (!tmp.renameTo(f)) tmp.delete();
        } catch (Throwable ignored) { }
    }
}
