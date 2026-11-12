package io.ouswis.tv.standalone;

import android.app.Application;

/** Gives Prefs a context in every process. */
public class App extends Application {
    @Override public void onCreate() {
        super.onCreate();
        Prefs.init(this);
        Place.init(this);
        Titles.missingSink=n->AppLog.w("missing translation: "+n);
    }
}
