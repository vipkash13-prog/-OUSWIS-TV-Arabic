package io.ouswis.tv.standalone;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** Foreground update controls; Android always asks the viewer before installing. */
public final class UpdatesActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private Button action;
    private volatile boolean working;
    private boolean visible;
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!visible) return;
            refresh();
            handler.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(5894);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(24, 16, 24, 16);
        root.setBackgroundColor(Ui.pageBackground(this));
        TextView heading = new TextView(this);
        heading.setText("OUSWIS TV 1.0  •  " + Ui.tr("Updates"));
        heading.setTextColor(Ui.pageInk(this));
        heading.setTextSize(22);
        root.addView(heading);
        status = new TextView(this);
        status.setTextColor(Ui.pageInk(this));
        status.setTextSize(18);
        status.setPadding(0, 20, 0, 20);
        root.addView(status);
        action = new Button(this);
        action.setAllCaps(false);
        action.setOnClickListener(v -> act());
        root.addView(action);
        setContentView(root);
        action.requestFocus();
        check();
    }

    private void refresh() {
        org.json.JSONObject state = Updates.state(this);
        status.setText(Updates.summary(state, Updates.installedBuild(this)));
        boolean ready = Updates.ready(state, Updates.installedBuild(this));
        action.setText(ready ? Ui.trf("Install {version}", "version", "1.0") : Ui.tr("Check now"));
        action.setEnabled(!working && !Updates.busy(state));
    }

    private void check() {
        if (working) return;
        working = true;
        refresh();
        new Thread(() -> {
            try { Updates.check(getApplicationContext()); }
            finally { working = false; handler.post(() -> { if (visible) refresh(); }); }
        }, "ouswis-update-check").start();
    }

    private void act() {
        if (!Updates.ready(Updates.state(this), Updates.installedBuild(this))) {
            check();
            return;
        }
        if (!getPackageManager().canRequestPackageInstalls()) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName())));
            } catch (RuntimeException e) {
                Toast.makeText(this, Ui.tr("Couldn't update"), Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (working) return;
        working = true;
        refresh();
        new Thread(() -> {
            try { Updates.install(getApplicationContext()); }
            finally { working = false; handler.post(() -> { if (visible) refresh(); }); }
        }, "ouswis-update-install").start();
    }

    @Override protected void onResume() {
        super.onResume();
        visible = true;
        handler.post(poll);
    }

    @Override protected void onPause() {
        visible = false;
        handler.removeCallbacks(poll);
        super.onPause();
    }
}
