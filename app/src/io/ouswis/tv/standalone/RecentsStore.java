package io.ouswis.tv.standalone;

import android.content.Context;
import android.content.SharedPreferences;

/** Apps taken off Recent Apps with ▲ stay off it until they are opened again. */
final class RecentsStore {
    private RecentsStore() { }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("ouswis-recents", Context.MODE_PRIVATE);
    }

    static boolean dismissed(Context c, String pkg) { return prefs(c).getBoolean(pkg, false); }

    static void dismiss(Context c, String pkg) { prefs(c).edit().putBoolean(pkg, true).commit(); }

    /** The app is being opened: it may be listed again. */
    static void reopened(Context c, String pkg) {
        if (pkg == null) return;
        SharedPreferences p = prefs(c);
        if (p.contains(pkg)) p.edit().remove(pkg).commit();
    }
}
