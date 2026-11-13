package io.ouswis.tv.standalone;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;

/**
 * Settings > "سجل التشغيل": the last 20 playback events (what stopped the picture and why, the provider's connection
 * count) so they can be photographed from the TV, and the stream type to try (automatic, m3u8 only, ts only).
 * It shows no address, user name or password.
 */
public final class PlayLogActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout lines;
    private Button typeButton;
    private TextView hint;
    private float k;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        getWindow().getDecorView().setSystemUiVisibility(5894);
        k = Math.max(getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels) / 1920f;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Design.BG);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setPadding(px(96), px(40), px(96), px(24));
        TextView title = text("سجل التشغيل", 34, Design.TEXT, Design.bold());
        title.setGravity(Gravity.RIGHT);
        root.addView(title);
        hint = text("", 18, Design.MUTED, Design.regular());
        hint.setGravity(Gravity.RIGHT);
        hint.setPadding(0, px(4), 0, px(14));
        root.addView(hint);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        typeButton = button("");
        typeButton.setOnClickListener(v -> { LivePlayer.setStreamType(this, LivePlayer.nextStreamType(this)); refresh(); });
        Button clear = button("مسح السجل");
        clear.setOnClickListener(v -> { PlayLog.clear(this); refresh(); });
        row.addView(typeButton, new LinearLayout.LayoutParams(px(420), px(64)));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(px(260), px(64));
        cp.rightMargin = px(16);
        row.addView(clear, cp);
        root.addView(row);
        lines = new LinearLayout(this);
        lines.setOrientation(LinearLayout.VERTICAL);
        lines.setPadding(0, px(18), 0, 0);
        root.addView(lines, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        typeButton.requestFocus();
        refresh();
    }

    @Override protected void onResume() { super.onResume(); main.post(tick); }
    @Override protected void onPause() { main.removeCallbacks(tick); super.onPause(); }
    private final Runnable tick = new Runnable() { @Override public void run() { refresh(); main.postDelayed(this, 2000); } };

    private int px(float v) { return Math.round(v * k); }

    private TextView text(String s, float size, int color, Typeface face) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size * k);
        t.setTextColor(color);
        t.setTypeface(face);
        return t;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setPadding(0, 0, 0, 0);
        b.setTextColor(Color.WHITE);
        b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 24 * k);
        b.setTypeface(Design.bold());
        GradientDrawable rest = new GradientDrawable();
        rest.setColor(0xFF121A3A);
        rest.setCornerRadius(px(18));
        GradientDrawable on = new GradientDrawable();
        on.setColor(0xFF121A3A);
        on.setCornerRadius(px(18));
        on.setStroke(Math.max(2, px(3)), Color.WHITE);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[] {android.R.attr.state_focused}, on);
        s.addState(new int[] {android.R.attr.state_pressed}, on);
        s.addState(new int[] {}, rest);
        b.setBackground(s);
        b.setStateListAnimator(null);
        return b;
    }

    private void refresh() {
        String type = LivePlayer.streamType(this);
        typeButton.setText("نوع البث: " + (LivePlayer.AUTO.equals(type) ? "تلقائي" : type + " فقط"));
        hint.setText("آخر " + PlayLog.SHOWN + " حدثاً · يتجدد وحده · OK على نوع البث يبدّل بين تلقائي و m3u8 و ts");
        List<String> all = PlayLog.last(this, PlayLog.SHOWN);
        lines.removeAllViews();
        if (all.isEmpty()) {
            TextView none = text("لا أحداث بعد. شغّل قناة بملء الشاشة ثم ارجع هنا.", 24, Design.MUTED, Design.regular());
            none.setGravity(Gravity.RIGHT);
            lines.addView(none);
            return;
        }
        for (String line : all) {
            TextView t = text(line, 19, Design.TEXT, Design.regular());
            t.setTextDirection(View.TEXT_DIRECTION_LTR);
            t.setGravity(Gravity.LEFT);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            lines.addView(t);
        }
    }
}
