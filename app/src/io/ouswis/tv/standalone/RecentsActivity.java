package io.ouswis.tv.standalone;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recent apps: the apps that are open, as cards on the dark inner wallpaper, like the other screens. It is its own
 * screen (the old classic home view is never created for it), so Back and Home go straight to the new home.
 * The list is kept for the life of the process and shown at once; a read through the TV's own ADB refreshes it in the
 * background. OK opens an app, ▲ closes the focused one (am force-stop, only where {@link HomeMode#mayStop} allows),
 * and "مسح الكل" closes all of them that may be closed.
 */
public class RecentsActivity extends Activity {
    private static final Pattern SIZE = Pattern.compile("sz=(\\d+)");
    /** The task's affinity ("A=10087:com.netflix.ninja"; Android 8-10 write it without the uid, 12-13 as "10087-si:") or its intent ("I="). */
    private static final Pattern PACKAGE = Pattern.compile("(?:A=(?:\\d+(?:-si)?:)?|I=)([\\w.]+)");

    private static final class Entry {
        String pkg, label;
        boolean stoppable;
    }

    // Kept for the life of the process: the last list read (null until the first read ends) and the cards' pictures.
    private static List<Entry> cache;
    private static String lastError;   // the last read failed and nothing was ever shown: said at once next time, not "searching"
    private static final Map<String, Bitmap> TILES = new HashMap<>();

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<Entry> items = new ArrayList<>();
    private RecentsView view;
    private boolean loaded;      // a list is on screen (the cached one, or the first read)
    private String error;        // why the last read failed, while nothing is on screen
    private String notice;       // "closed 3 apps, 1 is protected"
    private volatile boolean reading;

    @Override public void finish() { super.finish(); overridePendingTransition(0, 0); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        overridePendingTransition(0, 0);
        Ui.init(this);
        getWindow().getDecorView().setSystemUiVisibility(5894);
        if (cache != null) { items.addAll(cache); loaded = true; }   // known already: no "searching" text
        else error = lastError;
        view = new RecentsView(this);
        FrameLayout root = new FrameLayout(this);
        root.addView(view, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        view.requestFocus();
    }

    @Override protected void onResume() { super.onResume(); refresh(); }

    @Override protected void onDestroy() { io.shutdownNow(); super.onDestroy(); }

    /** Reads the open apps in the background; the screen changes only if the list is not the one shown. */
    private void refresh() {
        if (reading) return;
        reading = true;
        try {
            io.execute(() -> {
                List<Entry> found = new ArrayList<>();
                String err = null;
                try { read(found); }
                catch (IOException | RuntimeException e) {   // a crash here would count for the watchdog
                    AppLog.w("recent apps: " + e);
                    err = "تعذّر قراءة التطبيقات الأخيرة. فعّل تصحيح USB لـ OUSWIS TV.";
                }
                final String shownError = err;
                main.post(() -> {
                    reading = false;
                    if (isFinishing() || isDestroyed()) return;
                    if (shownError != null) {
                        if (!loaded) { error = shownError; lastError = shownError; }   // a list already on screen stays
                    } else {
                        error = null; lastError = null;
                        if (!same(items, found)) {
                            String keep = view.focusedPackage();
                            items.clear();
                            items.addAll(found);
                            view.restoreFocus(keep);
                        }
                        cache = new ArrayList<>(found);
                        loaded = true;
                    }
                    view.invalidate();
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException gone) { reading = false; }
    }

    private static boolean same(List<Entry> a, List<Entry> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++)
            if (!a.get(i).pkg.equals(b.get(i).pkg) || a.get(i).stoppable != b.get(i).stoppable) return false;
        return true;
    }

    private void read(List<Entry> found) throws IOException {
        try (Adb adb = Adb.connect(getApplicationContext(), 10_000, null)) {
            String recents = adb.shell("dumpsys activity recents");
            String ps = adb.shell("ps -A -o NAME");
            // Android turns a stopped app's accessibility service off for good, and the keyboard is the only way to type.
            Set<String> keep = new HashSet<>();
            for (String s : adb.shell("settings get secure enabled_accessibility_services").split(":")) keep.add(owner(s));
            keep.add(owner(adb.shell("settings get secure default_input_method")));
            Set<String> alive = new HashSet<>();
            for (String line : ps.split("\n")) alive.add(line.trim());
            Map<String, String> launchable = new HashMap<>();
            for (String[] app : HomeApps.launchable(this)) launchable.put(app[0], app[1]);
            Set<String> seen = new HashSet<>();
            for (String line : recents.split("\n")) {
                if (!line.contains("Recent #")) continue;
                Matcher pkg = PACKAGE.matcher(line);
                if (!pkg.find()) continue;
                String name = pkg.group(1);
                // The affinity can be longer than the package (YouTube's task is "com.google.android.youtube.tv.main").
                while (!launchable.containsKey(name) && name.indexOf('.') > 0) name = name.substring(0, name.lastIndexOf('.'));
                Matcher sz = SIZE.matcher(line);
                boolean hasActivity = sz.find() && Integer.parseInt(sz.group(1)) > 0;
                boolean running = alive.contains(name);
                if (!(hasActivity || running) || name.equals(getPackageName()) || !launchable.containsKey(name)
                        || HomeApps.isHidden(name) || !seen.add(name)) continue;
                Entry e = new Entry();
                e.pkg = name;
                String l = launchable.get(name);
                e.label = l == null ? name : l.trim();
                e.stoppable = HomeMode.mayStop(name) && !keep.contains(name);
                found.add(e);
            }
            // As before anything goes off: what the TV's inputs or the remote's Input key need stays.
            Set<String> closable = new HashSet<>();
            for (Entry e : found) if (e.stoppable) closable.add(e.pkg);
            Set<String> needed;
            try { needed = closable.isEmpty() ? closable : HomeMode.neededByTv(adb, closable); }
            catch (IOException e) { needed = null; }
            for (Entry e : found) if (needed == null || needed.contains(e.pkg)) e.stoppable = false;
        }
        for (Entry e : found) {   // the cards are made here, off the UI thread
            synchronized (TILES) { if (TILES.containsKey(e.pkg)) continue; }
            try {
                Bitmap t = AllAppsActivity.makeTile(this, e.pkg, e.label);
                synchronized (TILES) { TILES.put(e.pkg, t); }
            } catch (Throwable t) { AppLog.w("recent tile failed: " + e.pkg + " " + t); }
        }
    }

    /** "com.app/.Service" or "com.app/com.app.Ime" → "com.app"; empty for "null" or nothing. */
    private static String owner(String component) {
        String s = component.trim();
        int slash = s.indexOf('/');
        return slash > 0 ? s.substring(0, slash) : "";
    }

    /** Stops these apps through the TV's ADB (one connection), then drops them from the list. */
    private void closeApps(final List<Entry> which) {
        if (which.isEmpty()) return;
        items.removeAll(which);   // gone from the screen at once; the read after refreshes the truth
        cache = new ArrayList<>(items);
        view.afterRemove();
        final List<String> pkgs = new ArrayList<>();
        for (Entry e : which) pkgs.add(e.pkg);
        try {
            io.execute(() -> {
                try (Adb adb = Adb.connect(getApplicationContext(), 10_000, null)) {
                    for (String p : pkgs) adb.shell("am force-stop " + p);
                } catch (IOException | RuntimeException e) {
                    AppLog.w("close app: " + e);
                }
                try { Thread.sleep(600); } catch (InterruptedException ignored) { }
                main.post(() -> { if (!isFinishing()) refresh(); });
            });
        } catch (java.util.concurrent.RejectedExecutionException gone) { }
    }

    private void clearAll() {
        List<Entry> which = new ArrayList<>();
        int kept = 0;
        for (Entry e : items) { if (e.stoppable) which.add(e); else kept++; }
        notice = which.isEmpty() ? (kept > 0 ? "التطبيقات المفتوحة محمية ولا يمكن إغلاقها" : null)
                : "تم إغلاق " + which.size() + (which.size() == 1 ? " تطبيق" : " تطبيقات") + (kept > 0 ? " · " + kept + " محمي" : "");
        closeApps(which);
    }

    private void open(Entry e) {
        Intent i = HomeApps.launchIntent(getPackageManager(), e.pkg);
        try {
            if (i != null) startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        } catch (RuntimeException x) { AppLog.w("recents: could not open " + e.pkg); }
    }

    private final class RecentsView extends View {
        private static final int COLS = 4;
        private static final float CW = 420, CH = 236, PITCH = 460, TOP = 300;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), bmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private final Paint title = Design.text(Design.TEXT, 40, Design.bold()), sub = Design.text(Design.MUTED, 20, Design.regular()),
                label = Design.text(Design.MUTED, 24, Design.regular()), labelOn = Design.text(Design.TEXT, 24, Design.bold()),
                btn = Design.text(Design.TEXT, 26, Design.bold());
        private final RectF r = new RectF(), g = new RectF(), tmp = new RectF();
        private final A11y a11y = new A11y(this);
        private Bitmap backdrop;
        private float k;
        private int sel, first;       // sel: the focused card; first: the first card shown (a window of COLS)
        private boolean onButton;     // focus is on "مسح الكل"

        RecentsView(Context c) { super(c); setFocusable(true); setFocusableInTouchMode(true); setDefaultFocusHighlightEnabled(false); }

        @Override public android.view.accessibility.AccessibilityNodeProvider getAccessibilityNodeProvider() {
            return a11y.provider(null, idx -> { activate(idx); });
        }

        String focusedPackage() { return !onButton && sel < items.size() ? items.get(sel).pkg : null; }

        void restoreFocus(String pkg) {
            if (pkg != null) for (int i = 0; i < items.size(); i++) if (items.get(i).pkg.equals(pkg)) { sel = i; return; }
            sel = Math.min(sel, Math.max(0, items.size() - 1));
        }

        void afterRemove() {
            if (sel >= items.size()) sel = Math.max(0, items.size() - 1);
            if (items.isEmpty()) onButton = false;
            invalidate();
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            k = w / 1920f;
            try {
                backdrop = Bitmap.createBitmap(Math.max(1, w / 2), Math.max(1, h / 2), Bitmap.Config.RGB_565);
                Canvas c = new Canvas(backdrop);
                c.scale(0.5f, 0.5f);
                Wallpaper.draw(getContext(), c, w, h, 0.6f, 0x0B0D1A);   // the inner wallpaper under #0B0D1A at 60 %, like All Apps
            } catch (Throwable noRoom) { backdrop = null; AppLog.w("backdrop skipped: " + noRoom); }
        }

        @Override protected void onDraw(Canvas c) {
            try { drawScreen(c); }
            catch (RuntimeException e) { AppLog.w("recents draw failed: " + Log.getStackTraceString(e)); }
        }

        private void drawScreen(Canvas c) {
            int w = getWidth(), h = getHeight();
            if (backdrop != null) c.drawBitmap(backdrop, null, new RectF(0, 0, w, h), null); else c.drawColor(Design.BG);
            a11y.begin(k);
            c.save();
            c.scale(k, k);
            int n = items.size();
            Design.right(c, "التطبيقات الأخيرة", 1848, 76, title);
            String count = !loaded ? (error != null ? "" : "جارٍ البحث…") : n + (n == 1 ? " تطبيق مفتوح" : " تطبيقات مفتوحة");
            Design.right(c, count, 1848, 113, sub);
            a11y.add(1100, 40, 1848, 126, "التطبيقات الأخيرة · " + count, false, false);
            if (n == 0) {
                String msg = !loaded ? (error != null ? error : "جارٍ البحث عن التطبيقات المفتوحة…") : "لا توجد تطبيقات مفتوحة";
                Design.center(c, msg, 960, 520, Design.text(Design.MUTED, 28, Design.regular()));
                a11y.add(360, 480, 1560, 540, msg, false, false);
            }
            if (sel < first) first = sel;
            if (sel >= first + COLS) first = sel - COLS + 1;
            for (int i = first; i < Math.min(n, first + COLS); i++) {
                Entry e = items.get(i);
                float right = 1848 - (i - first) * PITCH, left = right - CW;
                r.set(left, TOP, right, TOP + CH);
                boolean focused = !onButton && i == sel;
                RectF box = r;
                if (focused) { Design.grown(r, g); box = g; }
                Bitmap t;
                synchronized (TILES) { t = TILES.get(e.pkg); }
                if (t != null) c.drawBitmap(t, null, box, bmp); else Design.card(c, box, 22, 1, p, 0xFF1B1E2B);
                Design.center(c, Design.fitShrink(e.label, focused ? labelOn : label, CW - 8), (left + right) / 2, TOP + CH + 44, focused ? labelOn : label);
                a11y.add(box, e.label + (e.stoppable ? "" : " (محمي)"), focused, true);
                if (focused) Design.focusRing(c, box, 22, 1, p);
            }
            if (n > COLS) {
                Design.center(c, (first + 1) + "–" + Math.min(n, first + COLS) + " من " + n, 960, 640, sub);
            }
            if (n > 0) {
                r.set(810, 700, 1110, 776);
                Design.card(c, r, 38, 1, p, 0xFF121A3A);
                Design.center(c, "مسح الكل", 960, 749, btn);
                a11y.add(r, "مسح الكل", onButton, true);
                if (onButton) Design.focusRing(c, r, 38, 1, p);
            }
            if (notice != null) Design.center(c, notice, 960, 840, Design.text(Design.MUTED, 24, Design.regular()));
            String hint = "OK للفتح · ▲ لإغلاق التطبيق · ▼ لمسح الكل · BACK للرئيسية";
            Design.right(c, hint, 1848, 1054, sub);
            Design.left(c, "التطبيقات الأخيرة", 72, 1054, sub);
            c.restore();
        }

        private void activate(int a11yIndex) {
            // nodes: header, [empty message], cards..., button; a click on a card opens it, on the button clears all
            int n = items.size();
            int base = a11yIndex - (n == 0 ? 2 : 1);
            if (base >= 0 && base < Math.min(n - first, COLS)) { open(items.get(first + base)); return; }
            if (n > 0 && a11yIndex == a11y.count() - 1) clearAll();
        }

        @Override public boolean onKeyDown(int key, KeyEvent e) {
            int n = items.size();
            switch (key) {
                case KeyEvent.KEYCODE_DPAD_RIGHT: if (!onButton && sel > 0) sel--; notice = null; invalidate(); return true;   // RTL: the newest is on the right
                case KeyEvent.KEYCODE_DPAD_LEFT: if (!onButton && sel + 1 < n) sel++; notice = null; invalidate(); return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (onButton) onButton = false;
                    else if (n > 0 && e.getRepeatCount() == 0) {   // one app a press: held, it went on to the next
                        Entry x = items.get(Math.min(sel, n - 1));
                        if (x.stoppable) { notice = null; List<Entry> one = new ArrayList<>(); one.add(x); closeApps(one); }
                        else { notice = "هذا التطبيق محمي ولا يمكن إغلاقه"; }
                    }
                    invalidate();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN: if (n > 0) onButton = true; invalidate(); return true;
                case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (e.getRepeatCount() == 0 && n > 0) {
                        if (onButton) clearAll(); else open(items.get(Math.min(sel, n - 1)));
                    }
                    return true;
                case KeyEvent.KEYCODE_APP_SWITCH: finish(); return true;
            }
            return super.onKeyDown(key, e);
        }
    }
}
