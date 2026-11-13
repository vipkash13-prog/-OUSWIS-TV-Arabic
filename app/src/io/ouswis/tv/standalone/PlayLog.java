package io.ouswis.tv.standalone;

import android.content.Context;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The playback log: what the player did and why it stopped, newest last, kept in a small file so the Settings page
 * "سجل التشغيل" can show the last 20 events. It never holds an address, a user name or a password: the callers pass
 * only codes and numbers, and {@link #clean} blanks the login if one slips into a message.
 */
final class PlayLog {
    private PlayLog() {}
    static final int SHOWN = 20, KEPT = 60;
    private static final Object LOCK = new Object();

    private static File file(Context c) { return new File(c.getApplicationContext().getFilesDir(), "playlog.txt"); }

    /** `sinceMs`: time from the start of this channel's playback, or negative when it doesn't apply. */
    static void add(Context c, long sinceMs, String what) {
        String when = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        String since = sinceMs < 0 ? "  --  " : String.format(Locale.US, "+%d:%02d", sinceMs / 60000, sinceMs / 1000 % 60);
        String line = when + " | " + since + " | " + what.replace('\n', ' ');
        AppLog.w("play: " + line);
        synchronized (LOCK) {
            try {
                File f = file(c);
                List<String> all = readAll(f);
                all.add(line);
                if (all.size() > KEPT) all = new ArrayList<>(all.subList(all.size() - SHOWN * 2, all.size()));
                try (FileWriter w = new FileWriter(f, false)) { for (String s : all) w.write(s + "\n"); }
            } catch (Exception e) { AppLog.w("play log write failed: " + e); }
        }
    }

    /** The last `n` lines, oldest first. */
    static List<String> last(Context c, int n) {
        synchronized (LOCK) {
            List<String> all = readAll(file(c));
            return all.size() <= n ? all : new ArrayList<>(all.subList(all.size() - n, all.size()));
        }
    }

    static void clear(Context c) { synchronized (LOCK) { file(c).delete(); } }

    private static List<String> readAll(File f) {
        List<String> out = new ArrayList<>();
        if (!f.exists()) return out;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String s;
            while ((s = r.readLine()) != null) if (!s.isEmpty()) out.add(s);
        } catch (Exception ignored) { }
        return out;
    }

    /** Blanks the account's server, user and password in a text (an exception message can carry the address). */
    static String clean(String s, XtreamAccount a) {
        if (s == null) return "";
        if (a != null) {
            for (String secret : new String[] {XtreamAccount.enc(a.pass), a.pass, XtreamAccount.enc(a.user), a.user, a.server})
                if (secret != null && secret.length() > 2) s = s.replace(secret, "***");
        }
        return s.length() > 160 ? s.substring(0, 160) : s;
    }
}
