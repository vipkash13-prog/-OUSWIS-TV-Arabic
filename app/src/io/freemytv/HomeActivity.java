package io.freemytv;

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
import android.widget.FrameLayout;
import java.util.List;

/**
 * The Free My TV home screen (Android's "Home" role in Free My TV mode). It runs in the :home process: if it crashes,
 * the safety net in the main process keeps working. On the 3rd crash within 5 min, the Watchdog gives the home screen
 * back to the TV's original one.
 */
public class HomeActivity extends Activity {
    /** Opens All Apps (a remote's All Apps button, RemoteKeys). */
    static final String EXTRA_ALL_APPS = "io.freemytv.ALL_APPS";
    private HomeView home;
    private MatchCarouselView matchPreview;
    private final android.os.Handler matchHandler=new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable loadMatches=new Runnable() {
        @Override public void run() {
            new Thread(()->{
                List<MatchCarouselView.Match> fixtures=UpcomingMatches.fetch(null,"upcoming");
                runOnUiThread(()->{
                    if(matchPreview!=null){
                        matchPreview.setMatches(fixtures);
                        matchPreview.setVisibility(fixtures.isEmpty()?android.view.View.GONE:android.view.View.VISIBLE);
                        matchHandler.postDelayed(loadMatches,15*60*1000);
                    }
                });
            },"ouswis-fixtures").start();
        }
    };
    private int prefsVersion = Prefs.version;   // settings changed while we were away: start over

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
        home = new HomeView(this);
        FrameLayout stage=new FrameLayout(this);
        stage.addView(home,new FrameLayout.LayoutParams(-1,-1));
        matchPreview=new MatchCarouselView(this);
        FrameLayout.LayoutParams previewParams=new FrameLayout.LayoutParams(
                (int)(getResources().getDisplayMetrics().widthPixels*.37f),
                (int)(getResources().getDisplayMetrics().heightPixels*.29f),
                Gravity.RIGHT|Gravity.CENTER_VERTICAL);
        previewParams.rightMargin=(int)(getResources().getDisplayMetrics().density*22);
        matchPreview.setFocusable(true);
        matchPreview.setVisibility(android.view.View.GONE);
        matchPreview.setClickable(true);
        matchPreview.setOnClickListener(v->startActivity(new Intent(this,MatchesActivity.class)));
        stage.addView(matchPreview,previewParams);
        setContentView(stage);
        getWindow().setBackgroundDrawable(null);
        if (getIntent().getBooleanExtra(EXTRA_ALL_APPS, false)) home.showAllApps();
        // At power-on "Live TV" (#346): the first time the home screen comes up after a full start, in Free My TV mode.
        if (HomeMode.noteHomeShown(this) && SystemClock.elapsedRealtime() < 240_000 && "live".equals(Prefs.powerOn())) {
            org.json.JSONObject s = HomeMode.state(this);
            if (HomeMode.FREE.equals(s.optString("mode")) && !s.optBoolean("homeReverted")) home.postDelayed(powerOnLive, 10_000);
        }
    }

    private boolean resumed, keyPressed;

    /** Live TV, unless a key came first or something else is in front by then. */
    private final Runnable powerOnLive = () -> {
        if (!resumed || keyPressed) return;
        AppLog.w("at power-on: Live TV");
        HomeApps.open(this, HomeApps.action(HomeApps.LIVE_TV, HomeApps.TOP_LIVE, Ui.tr("Live TV"), "live_tv"), null);
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
        if (home != null) home.resume();
        if(matchPreview!=null){matchHandler.removeCallbacks(loadMatches);matchHandler.post(loadMatches);}
        Updates.tellOnce(this);   // an update the week's check got ready: said once
    }

    @Override protected void onPause() {
        resumed = false;
        matchHandler.removeCallbacks(loadMatches);
        if (home != null) home.pause();
        super.onPause();
    }

    /** Home button pressed on the home screen: back to the entry state (focus on the first app, sheet closed). */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (home == null) return;
        if (intent.getBooleanExtra(EXTRA_ALL_APPS, false)) {
            home.showAllApps();
            return;
        }
        long now = SystemClock.uptimeMillis();
        boolean twice = Prefs.recents().equals("home") && lastHome != 0 && now - lastHome < TWICE_MS;
        lastHome = twice ? 0 : now;
        if (twice) home.showRecents();
        else home.reset(true);
    }

    /** When Home was last pressed, and how soon the second press counts (Settings > Home Settings). */
    private long lastHome;
    private static final long TWICE_MS = 700;

    @Override protected void onDestroy() {
        matchHandler.removeCallbacks(loadMatches);
        matchPreview=null;
        if (home != null) {
            home.removeCallbacks(powerOnLive);
            home.destroy();
        }
        super.onDestroy();
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_COMPLETE) Recs.get(this).trim();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        keyPressed = true;
        // The carousel owns left/right only while focused; keep Home and system keys intact.
        if (matchPreview != null && matchPreview.hasFocus()
                && event.getAction()==KeyEvent.ACTION_DOWN
                && (event.getKeyCode()==KeyEvent.KEYCODE_DPAD_LEFT
                    || event.getKeyCode()==KeyEvent.KEYCODE_DPAD_RIGHT)
                && matchPreview.onKeyDown(event.getKeyCode(),event)) return true;
        if (RemoteKeys.onKey(this, event)) return true;
        if (home != null && home.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

}
