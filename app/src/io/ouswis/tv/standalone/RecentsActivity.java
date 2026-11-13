package io.ouswis.tv.standalone;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.app.ActivityManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

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
 * background. The cards are real focusable views, so the platform moves the focus between them. OK opens an app; ▲ takes
 * the focused one off the list at once, stops its background processes ({@code killBackgroundProcesses}, and am force-stop
 * where {@link HomeMode#mayStop} allows) and keeps it off until it is opened again ({@link RecentsStore}); "مسح الكل" does
 * the same for every card.
 */
public class RecentsActivity extends Activity {
    private static final Pattern SIZE = Pattern.compile("sz=(\\d+)");
    /** The task's affinity ("A=10087:com.netflix.ninja"; Android 8-10 write it without the uid, 12-13 as "10087-si:") or its intent ("I="). */
    private static final Pattern PACKAGE = Pattern.compile("(?:A=(?:\\d+(?:-si)?:)?|I=)([\\w.]+)");

    private static final class Entry {
        String pkg, label;
        boolean stoppable;
        long goneAt;   // when ▲ closed it: the card rises and fades for CLOSE_MS, then it leaves the list
    }
    private static final long CLOSE_MS = 260;

    // Kept for the life of the process: the last list read (null until the first read ends) and the cards' pictures.
    private static List<Entry> cache;
    private static String lastError;   // the last read failed and nothing was ever shown: said at once next time, not "searching"
    private static final Map<String, Bitmap> TILES = new HashMap<>();

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<Entry> items = new ArrayList<>();
    private BackdropView backdrop;
    private LinearLayout row;
    private Button clearBtn;
    private float k;
    private int lastFocusedIndex;
    private boolean loaded;      // a list is on screen (the cached one, or the first read)
    private String error;        // why the last read failed, while nothing is on screen
    private volatile boolean reading;

    @Override public void finish() { super.finish(); overridePendingTransition(0, 0); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        overridePendingTransition(0, 0);
        Ui.init(this);
        getWindow().getDecorView().setSystemUiVisibility(5894);
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        k = Math.max(dm.widthPixels, dm.heightPixels) / 1920f;
        if (cache != null) { items.addAll(cache); loaded = true; }   // known already: no "searching" text
        else error = lastError;
        FrameLayout root = new FrameLayout(this);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        backdrop = new BackdropView(this);
        root.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        scroll.setFocusable(false);   // only the cards take focus: a focused scroll view paints a grey band
        scroll.setDefaultFocusHighlightEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        row.setClipChildren(false);
        row.setPadding(px(72 - PAD), 0, px(72 - PAD), 0);
        scroll.addView(row, new FrameLayout.LayoutParams(-2, -1));
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-1, px(CH + 2 * PAD + 70));
        sp.topMargin = px(TOP - PAD);
        root.addView(scroll, sp);
        clearBtn = new Button(this);
        clearBtn.setId(View.generateViewId());
        clearBtn.setText("مسح الكل");
        clearBtn.setAllCaps(false);
        clearBtn.setPadding(0, 0, 0, 0);
        clearBtn.setTextColor(Design.TEXT);
        clearBtn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 26 * k);
        clearBtn.setTypeface(Design.bold());
        clearBtn.setBackground(states(px(38)));
        clearBtn.setStateListAnimator(null);
        clearBtn.setDefaultFocusHighlightEnabled(false);
        clearBtn.setOnClickListener(v -> clearAll());
        clearBtn.setOnKeyListener((v, key, e) -> {   // back to the cards: up, left and right all land on the one last focused
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                CardView c = cardAt(Math.min(lastFocusedIndex, row.getChildCount() - 1));
                if (c != null) c.requestFocus();
                return true;
            }
            return key == KeyEvent.KEYCODE_DPAD_DOWN;
        });
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(px(300), px(76));
        bp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        bp.topMargin = px(700);
        root.addView(clearBtn, bp);
        setContentView(root);
        rebuild(null);
    }

    private static final float CW = 420, CH = 236, TOP = 300, PAD = 18;

    private int px(float v) { return Math.round(v * k); }

    private StateListDrawable states(int radius) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[] {android.R.attr.state_focused}, shape(true, radius));
        s.addState(new int[] {android.R.attr.state_pressed}, shape(true, radius));
        s.addState(new int[] {}, shape(false, radius));
        return s;
    }

    /** A dark #121A3A card; a white frame when focused (no bright system highlights on an OLED). */
    private GradientDrawable shape(boolean on, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFF121A3A);
        g.setCornerRadius(radius);
        if (on) g.setStroke(Math.max(2, px(3)), Color.WHITE);
        return g;
    }

    private CardView cardAt(int i) { return i >= 0 && i < row.getChildCount() ? (CardView) row.getChildAt(i) : null; }

    /** Makes the cards again for `items`; the focus goes back to `keepPkg`'s card, else the first one. */
    private void rebuild(String keepPkg) {
        boolean hadButton = clearBtn != null && clearBtn.hasFocus();
        row.removeAllViews();
        int want = 0;
        for (int i = 0; i < items.size(); i++) {
            Entry e = items.get(i);
            CardView c = new CardView(this, e);
            c.setId(View.generateViewId());
            row.addView(c, new LinearLayout.LayoutParams(px(CW + 2 * PAD), px(CH + 2 * PAD + 70)));
            if (e.pkg.equals(keepPkg)) want = i;
        }
        relink();
        clearBtn.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        backdrop.invalidate();
        if (items.isEmpty()) return;
        if (hadButton) { clearBtn.requestFocus(); return; }
        CardView c = cardAt(want);
        if (c != null) c.requestFocus();
    }

    /** Right and left stay on the cards (the first is on the right), down is the button, up goes nowhere. */
    private void relink() {
        int n = row.getChildCount();
        for (int i = 0; i < n; i++) {
            View c = row.getChildAt(i);
            c.setNextFocusRightId(row.getChildAt(Math.max(0, i - 1)).getId());
            c.setNextFocusLeftId(row.getChildAt(Math.min(n - 1, i + 1)).getId());
            c.setNextFocusUpId(c.getId());
            c.setNextFocusDownId(clearBtn.getId());
        }
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
                        boolean closing = false;
                        for (Entry e : items) if (e.goneAt != 0) closing = true;
                        if (!closing && !same(items, found)) {
                            View f = getCurrentFocus();
                            String keep = f instanceof CardView ? ((CardView) f).e.pkg : null;
                            items.clear();
                            items.addAll(found);
                            rebuild(keep);
                        }
                        cache = new ArrayList<>(found);
                        loaded = true;
                    }
                    backdrop.invalidate();
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
                if (RecentsStore.dismissed(this, name)) continue;   // taken off with ▲ until it is opened again
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
                Bitmap t = AllAppsActivity.makeTile(this, e.pkg, e.label, true);
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

    /**
     * Takes these cards off the list at once: the focus goes to the card next to the first closed one, each card rises
     * and fades for CLOSE_MS and leaves, the removal is saved ({@link RecentsStore}), and the app's background processes
     * are stopped (killBackgroundProcesses here, am force-stop through the TV's ADB where the app may be stopped).
     */
    private void closeApps(final List<Entry> which) {
        if (which.isEmpty()) return;
        final List<String> pkgs = new ArrayList<>();
        final List<String> stop = new ArrayList<>();
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        for (Entry e : which) {
            e.goneAt = android.os.SystemClock.uptimeMillis();
            pkgs.add(e.pkg);
            if (e.stoppable) stop.add(e.pkg);
            RecentsStore.dismiss(this, e.pkg);
            try { if (am != null) am.killBackgroundProcesses(e.pkg); }
            catch (RuntimeException x) { AppLog.w("kill background: " + e.pkg + " " + x); }
        }
        // the focus first: the next card, else the one before, else the button
        int target = -1;
        for (int i = 0; i < items.size() && target < 0; i++) if (items.get(i).goneAt == 0) {
            // prefer a card after the first closed one
            int firstGone = -1;
            for (int j = 0; j < items.size(); j++) if (items.get(j).goneAt != 0) { firstGone = j; break; }
            if (i > firstGone) target = i;
        }
        if (target < 0) for (int i = items.size() - 1; i >= 0; i--) if (items.get(i).goneAt == 0) { target = i; break; }
        CardView next = cardAt(target);
        if (next != null) { lastFocusedIndex = target; next.requestFocus(); }
        for (int i = 0; i < row.getChildCount(); i++) {
            final CardView c = cardAt(i);
            if (c.e.goneAt != 0) c.animate().translationY(-px(140)).alpha(0).setDuration(CLOSE_MS).start();
        }
        main.postDelayed(() -> {
            View f = getCurrentFocus();
            String keep = f instanceof CardView ? ((CardView) f).e.pkg : null;
            boolean button = clearBtn.hasFocus();
            items.removeAll(which);
            cache = new ArrayList<>(items);
            rebuild(button ? null : keep);
            if (button && !items.isEmpty()) clearBtn.requestFocus();
        }, CLOSE_MS);
        if (stop.isEmpty()) return;
        try {
            io.execute(() -> {
                try (Adb adb = Adb.connect(getApplicationContext(), 10_000, null)) {
                    for (String p : stop) adb.shell("am force-stop " + p);
                } catch (IOException | RuntimeException e) {
                    AppLog.w("close app: " + e);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException gone) { }
    }

    private void clearAll() {
        List<Entry> which = new ArrayList<>();
        for (Entry e : items) if (e.goneAt == 0) which.add(e);
        closeApps(which);
    }

    private void open(Entry e) {
        RecentsStore.reopened(this, e.pkg);
        Intent i = HomeApps.launchIntent(getPackageManager(), e.pkg);
        try {
            if (i != null) startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        } catch (RuntimeException x) { AppLog.w("recents: could not open " + e.pkg); }
    }

    /** One app: its card, its name under it, a white frame when focused; ▲ closes it. */
    private final class CardView extends View {
        final Entry e;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), bmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private final Paint label = Design.text(Design.MUTED, 24, Design.regular()), labelOn = Design.text(Design.TEXT, 24, Design.bold());
        private final RectF r = new RectF(), g = new RectF();

        CardView(Context c, Entry e) {
            super(c);
            this.e = e;
            setFocusable(true);
            setFocusableInTouchMode(true);
            setClickable(true);
            setDefaultFocusHighlightEnabled(false);
            setContentDescription(e.label);
            setOnClickListener(v -> { if (e.goneAt == 0) open(e); });
            setOnFocusChangeListener((v, has) -> {
                if (has) { int i = row.indexOfChild(v); if (i >= 0 && e.goneAt == 0) lastFocusedIndex = i; }
                invalidate();
            });
            setOnKeyListener((v, key, ev) -> {
                if (key != KeyEvent.KEYCODE_DPAD_UP) return false;
                if (ev.getAction() == KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0 && e.goneAt == 0) {   // one app a press: held, it went on to the next
                    List<Entry> one = new ArrayList<>();
                    one.add(e);
                    closeApps(one);
                }
                return true;
            });
        }

        @Override protected void onDraw(Canvas c) {
            try {
                float s = getWidth() / (CW + 2 * PAD);
                c.save();
                c.scale(s, s);
                boolean focused = isFocused();
                r.set(PAD, PAD, PAD + CW, PAD + CH);
                RectF box = r;
                if (focused) { Design.grown(r, g); box = g; }
                Bitmap t;
                synchronized (TILES) { t = TILES.get(e.pkg); }
                if (t != null) c.drawBitmap(t, null, box, bmp); else Design.card(c, box, 22, 1, p, 0xFF1B1E2B);
                Paint lp = focused ? labelOn : label;
                Design.center(c, Design.fitShrink(e.label, lp, CW - 8), PAD + CW / 2, PAD + CH + 44, lp);
                if (focused) Design.focusRing(c, box, 22, 1, p);
                c.restore();
            } catch (RuntimeException x) { AppLog.w("recents card draw failed: " + Log.getStackTraceString(x)); }
        }
    }

    /** The wallpaper, the title and the texts around the cards; it takes no focus. */
    private final class BackdropView extends View {
        private final Paint title = Design.text(Design.TEXT, 40, Design.bold()), sub = Design.text(Design.MUTED, 20, Design.regular());
        private Bitmap wall;

        BackdropView(Context c) { super(c); setFocusable(false); setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES); }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            try {
                wall = Bitmap.createBitmap(Math.max(1, w / 2), Math.max(1, h / 2), Bitmap.Config.RGB_565);
                Canvas c = new Canvas(wall);
                c.scale(0.5f, 0.5f);
                Wallpaper.draw(getContext(), c, w, h, 0.6f, 0x0B0D1A);   // the inner wallpaper under #0B0D1A at 60 %, like All Apps
            } catch (Throwable noRoom) { wall = null; AppLog.w("backdrop skipped: " + noRoom); }
        }

        @Override protected void onDraw(Canvas c) {
            try {
                int w = getWidth(), h = getHeight();
                if (wall != null) c.drawBitmap(wall, null, new RectF(0, 0, w, h), null); else c.drawColor(Design.BG);
                c.save();
                c.scale(w / 1920f, w / 1920f);
                int n = items.size();
                Design.right(c, "التطبيقات الأخيرة", 1848, 76, title);
                String count = !loaded ? (error != null ? "" : "جارٍ البحث…") : n + (n == 1 ? " تطبيق مفتوح" : " تطبيقات مفتوحة");
                setContentDescription("التطبيقات الأخيرة · " + count);   // read by TalkBack and the tests
                Design.right(c, count, 1848, 113, sub);
                if (n == 0) {
                    String msg = !loaded ? (error != null ? error : "جارٍ البحث عن التطبيقات المفتوحة…") : "لا توجد تطبيقات مفتوحة";
                    Design.center(c, msg, 960, 520, Design.text(Design.MUTED, 28, Design.regular()));
                }
                Design.right(c, "OK للفتح · ▲ لإغلاق التطبيق · ▼ لمسح الكل · BACK للرئيسية", 1848, 1054, sub);
                Design.left(c, "التطبيقات الأخيرة", 72, 1054, sub);
                c.restore();
            } catch (RuntimeException x) { AppLog.w("recents backdrop failed: " + Log.getStackTraceString(x)); }
        }
    }

    @Override public boolean onKeyDown(int key, KeyEvent e) {
        if (key == KeyEvent.KEYCODE_APP_SWITCH) { finish(); return true; }
        return super.onKeyDown(key, e);
    }
}
