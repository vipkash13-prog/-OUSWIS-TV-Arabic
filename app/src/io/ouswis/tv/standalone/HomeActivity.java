package io.ouswis.tv.standalone;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.TextView;

/**
 * The Free My TV home screen (Android's "Home" role in Free My TV mode). It runs in the :home process: if it crashes,
 * the safety net in the main process keeps working. On the 3rd crash within 5 min, the Watchdog gives the home screen
 * back to the TV's original one.
 */
public class HomeActivity extends Activity implements RedesignHomeView.Host {
    /** Opens All Apps (a remote's All Apps button, RemoteKeys). */
    static final String EXTRA_ALL_APPS = "io.ouswis.tv.standalone.ALL_APPS";
    /** The classic HomeView is never created any more: "All Apps" and "Recent" are screens of their own. */
    private RedesignHomeView redesign;
    private int prefsVersion = Prefs.version;   // settings changed while we were away: start over

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AllAppsActivity.preload(this);   // the apps list and its first cards, ready before the person opens it
        Watchdog.install(this);
        SleepTimer.listen(this);
        SleepTimer.check(this);
        if (Watchdog.crashedTooOften(this)) {
            // Don't try to draw again: only show a notice while the main process restores the original home screen.
            HomeMode.requestRevertHome(this);
            TextView t = new TextView(this);
            t.setText(Ui.tr("Free My TV's home screen ran into a problem.\nGoing back to the TV's original home screen…"));
            t.setTextColor(Color.WHITE);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            t.setGravity(Gravity.CENTER);
            t.setBackgroundColor(Color.rgb(7, 11, 30));
            setContentView(t);
            return;
        }
        Ui.init(this);
        redesign = new RedesignHomeView(this, this);
        setContentView(redesign);
        redesign.requestFocus();
        getWindow().setBackgroundDrawable(null);
        if (getIntent().getBooleanExtra(EXTRA_ALL_APPS, false)) openClassic(false);   // starts AllAppsActivity
        // At power-on "Live TV" (#346): the first time the home screen comes up after a full start, in Free My TV mode.
        if (HomeMode.noteHomeShown(this) && SystemClock.elapsedRealtime() < 240_000 && "live".equals(Prefs.powerOn())) {
            org.json.JSONObject s = HomeMode.state(this);
            if (HomeMode.FREE.equals(s.optString("mode")) && !s.optBoolean("homeReverted")) redesign.postDelayed(powerOnLive, 10_000);
        }
    }

    private boolean resumed, keyPressed;

    /** Live TV, unless a key came first or something else is in front by then. */
    private final Runnable powerOnLive = () -> {
        if (!resumed || keyPressed) return;
        AppLog.w("at power-on: Live TV");
        HomeApps.open(this, HomeApps.action(HomeApps.XTREAM_LIVE, HomeApps.TOP_LIVE, Ui.tr("Live TV"), "live_tv"), null);
    };

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        // TCL's Android 11 records the home activity as translucent (occludesParent=false) whatever its theme says:
        // Android then keeps the app just left visible, and home tasks are layered under all others, so that app stays
        // on screen over ours. Opaque again on every resume (setTranslucent is public from Android 11).
        if (RemoteKeys.TCL && Build.VERSION.SDK_INT >= 30) setTranslucent(false);
        if (prefsVersion != Prefs.version) {
            prefsVersion = Prefs.version;
            HomeData.get(this).settingsChanged();
            recreate();   // a new language or city: draw everything again
            return;
        }
        if (redesign != null) redesign.resume();
        Updates.tellOnce(this);   // an update the week's check got ready: said once
    }

    @Override protected void onPause() {
        resumed = false;
        if (redesign != null) redesign.pause();
        super.onPause();
    }

    /** Home button pressed on the home screen: back to the entry state (focus on the first app, sheet closed). */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (redesign == null) return;
        if (intent.getBooleanExtra(EXTRA_ALL_APPS, false)) {
            openClassic(false);
            return;
        }
        long now = SystemClock.uptimeMillis();
        boolean twice = Prefs.recents().equals("home") && lastHome != 0 && now - lastHome < TWICE_MS;
        lastHome = twice ? 0 : now;
        if (twice) openClassic(true);
        else redesign.reset();
    }

    /** When Home was last pressed, and how soon the second press counts (Settings > Home Settings). */
    private long lastHome;
    private static final long TWICE_MS = 700;

    @Override protected void onDestroy() {
        if (redesign != null) {
            redesign.removeCallbacks(powerOnLive);
            redesign.destroy();
        }
        super.onDestroy();
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_COMPLETE) Recs.get(this).trim();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        keyPressed = true;
        if (RemoteKeys.onKey(this, event)) return true;
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0
                && event.getKeyCode() == KeyEvent.KEYCODE_APP_SWITCH && !Prefs.recents().equals("off")) {
            openClassic(true);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    /** "All Apps" and "Recent Apps" open as screens of their own on top of the new home; Back returns straight to it. */
    @Override public void openClassic(boolean recents) {
        try {
            startActivity(new Intent(this, recents ? RecentsActivity.class : AllAppsActivity.class));
            overridePendingTransition(0, 0);
        } catch (RuntimeException e) { AppLog.w("could not open " + (recents ? "recents" : "all apps") + ": " + e); }
    }
}
