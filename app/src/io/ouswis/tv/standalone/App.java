package io.ouswis.tv.standalone;

import android.app.Activity;
import android.app.Application;
import android.os.Build;
import android.os.Bundle;

/** Gives Prefs a context in every process. */
public class App extends Application {
    @Override public void onCreate() {
        super.onCreate();
        Prefs.init(this);
        Place.init(this);
        Titles.missingSink=n->AppLog.w("missing translation: "+n);
        registerActivityLifecycleCallbacks(new NoAnimation());
    }

    /**
     * No screen of ours animates in or out, so two screens never show over each other for a moment on Back. Each screen
     * already did it on its own way in; this covers the way out too (onPause while finishing) and Android 14's own call.
     * MatchesActivity and XtreamActivity are left as they are.
     */
    private static final class NoAnimation implements ActivityLifecycleCallbacks {
        private static boolean skip(Activity a) { return a instanceof MatchesActivity || a instanceof XtreamActivity; }
        @Override public void onActivityCreated(Activity a, Bundle b) {
            if (skip(a)) return;
            a.overridePendingTransition(0, 0);
            try { a.getWindow().setWindowAnimations(0); } catch (RuntimeException ignored) { }
            if (Build.VERSION.SDK_INT >= 34) {
                a.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0);
                a.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0);
            }
        }
        @Override public void onActivityPaused(Activity a) { if (!skip(a) && a.isFinishing()) a.overridePendingTransition(0, 0); }
        @Override public void onActivityStarted(Activity a) { }
        @Override public void onActivityResumed(Activity a) { }
        @Override public void onActivityStopped(Activity a) { }
        @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
        @Override public void onActivityDestroyed(Activity a) { }
    }
}
