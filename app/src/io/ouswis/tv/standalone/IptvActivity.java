package io.ouswis.tv.standalone;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.net.URI;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONObject;

/**
 * Settings > "اشتراك IPTV": the Xtream subscription in use (server, user, expiry, status; the password is only ••••),
 * changing it (with a connection test before saving) and deleting it. The login stays in AccountVault, sealed.
 * Saving or deleting also wipes everything tied to the old subscription: channels, categories, guide, recents,
 * favourites and the last channel.
 */
public class IptvActivity extends Activity {
    private static final int BG = 0xFF0B0D1A, CARD = 0xFF121A3A, INK = 0xFFFFFFFF, MUTED = 0xB3FFFFFF, GOOD = 0xFF7BE0A0, BAD = 0xFFFF8A80;
    private static final int INFO = 0, EDIT = 1, CONFIRM = 2;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private LinearLayout page;
    private float scale;
    private int mode = INFO;
    private String status = "…", expiry = "";   // read from the server for the INFO page
    private boolean statusDone;
    private LiveData loader;

    // The EDIT page's fields and the result of the last test (a save needs a passed test of the same values).
    private EditText fServer, fPort, fUser, fPass;
    private TextView result;
    private String testedFor = "";
    private boolean testing;
    private int generation;

    @Override public void finish() { super.finish(); overridePendingTransition(0, 0); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        overridePendingTransition(0, 0);
        Ui.init(this);
        getWindow().getDecorView().setSystemUiVisibility(5894);
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
        scale = getResources().getDisplayMetrics().heightPixels / 1080f;
        show(INFO);
    }

    @Override protected void onDestroy() { generation++; io.shutdownNow(); if (loader != null) loader.shutdown(); super.onDestroy(); }

    @Override public boolean onKeyDown(int key, KeyEvent e) {
        if (key == KeyEvent.KEYCODE_BACK && mode != INFO) { show(INFO); return true; }
        return super.onKeyDown(key, e);
    }

    // MARK: pages

    private void show(int to) {
        mode = to;
        generation++;
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        page.setBackgroundColor(BG);
        page.setPadding(px(480), px(70), px(480), px(40));
        page.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        scroll.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        scroll.addView(page, new ScrollView.LayoutParams(-1, -1));
        setContentView(scroll);
        Button first = null;
        if (to == INFO) first = buildInfo();
        else if (to == EDIT) buildEdit();
        else first = buildConfirm();
        if (first != null) first.requestFocus();
    }

    private Button buildInfo() {
        text("اشتراك IPTV", 40, INK, true, 8);
        String[] v = AccountVault.load(this);
        if (v == null) {
            text("لا يوجد اشتراك محفوظ", 26, MUTED, false, 30);
            Button add = button("إضافة اشتراك");
            add.setOnClickListener(x -> show(EDIT));
            return add;
        }
        String host = v[0];
        try { URI u = URI.create(v[0]); host = u.getHost() == null ? v[0] : u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : ""); } catch (RuntimeException ignored) { }
        row("السيرفر", host, INK);
        row("المستخدم", v[1], INK);
        row("كلمة المرور", "••••", INK);
        final TextView exp = row("تاريخ الانتهاء", statusDone ? (expiry.isEmpty() ? "غير محدد" : expiry) : "جارٍ التحقق…", INK);
        final TextView st = row("حالة الحساب", statusDone ? status : "جارٍ التحقق…", statusDone ? colorFor(status) : MUTED);
        if (!statusDone) readStatus(v, exp, st);
        Button change = button("تغيير الاشتراك");
        change.setOnClickListener(x -> show(EDIT));
        Button delete = button("حذف الاشتراك");
        delete.setOnClickListener(x -> show(CONFIRM));
        return change;
    }

    private static int colorFor(String s) { return "نشط".equals(s) ? GOOD : BAD; }

    private void readStatus(final String[] v, final TextView exp, final TextView st) {
        final int gen = generation;
        try {
            io.execute(() -> {
                String s = "تعذّر التحقق", e = "";
                try {
                    JSONObject info = new JSONObject(XtreamAccount.get(v[0] + "/player_api.php?username=" + XtreamAccount.enc(v[1])
                            + "&password=" + XtreamAccount.enc(v[2]))).optJSONObject("user_info");
                    if (info != null) {
                        s = statusText(info);
                        long t = info.optLong("exp_date", 0);
                        if (t > 0) e = new java.text.SimpleDateFormat("d MMMM yyyy", Design.AR).format(new java.util.Date(t * 1000));
                    }
                } catch (Exception ignored) { AppLog.w("iptv status failed"); }
                final String fs = s, fe = e;
                main.post(() -> {
                    status = fs; expiry = fe; statusDone = true;
                    if (gen != generation || isFinishing()) return;
                    exp.setText(fe.isEmpty() ? "غير محدد" : fe);
                    st.setText(fs);
                    st.setTextColor(colorFor(fs));
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException gone) { }
    }

    private static String statusText(JSONObject info) {
        String s = info.optString("status");
        if ("Active".equalsIgnoreCase(s)) return "نشط";
        if ("Expired".equalsIgnoreCase(s)) return "منتهٍ";
        if ("Banned".equalsIgnoreCase(s)) return "محظور";
        if ("Disabled".equalsIgnoreCase(s)) return "معطّل";
        return s.isEmpty() ? "غير معروف" : s;
    }

    private void buildEdit() {
        text("تغيير الاشتراك", 40, INK, true, 8);
        String[] v = AccountVault.load(this);
        String server = "", port = "";
        if (v != null) {
            server = v[0];
            try {
                URI u = URI.create(v[0]);
                if (u.getPort() > 0 && u.getHost() != null) { port = String.valueOf(u.getPort()); server = u.getScheme() + "://" + u.getHost(); }
            } catch (RuntimeException ignored) { }
        }
        fServer = field("السيرفر (http://example.com)", server, InputType.TYPE_TEXT_VARIATION_URI);
        fPort = field("البورت (مثال 8080)", port, InputType.TYPE_CLASS_NUMBER);
        fUser = field("اسم المستخدم", v == null ? "" : v[1], InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        fPass = field("كلمة المرور", v == null ? "" : v[2], InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        result = text("", 24, MUTED, false, 12);
        result.setGravity(Gravity.CENTER);
        testedFor = "";
        Button test = button("اختبار الاتصال");
        Button save = button("حفظ");
        Button back = button("إلغاء");
        test.setOnClickListener(x -> runTest(false));
        save.setOnClickListener(x -> {
            if (testedFor.equals(current())) save(); else runTest(true);   // untested values are tested first
        });
        back.setOnClickListener(x -> show(INFO));
        fServer.setNextFocusDownId(fPort.getId()); fPort.setNextFocusUpId(fServer.getId());
        fPort.setNextFocusDownId(fUser.getId()); fUser.setNextFocusUpId(fPort.getId());
        fUser.setNextFocusDownId(fPass.getId()); fPass.setNextFocusUpId(fUser.getId());
        fPass.setNextFocusDownId(test.getId()); test.setNextFocusUpId(fPass.getId());
        EditText[] all = {fServer, fPort, fUser, fPass};
        for (int i = 0; i < all.length; i++) {
            final View next = i + 1 < all.length ? all[i + 1] : test;
            all[i].setImeOptions((i + 1 < all.length ? EditorInfo.IME_ACTION_NEXT : EditorInfo.IME_ACTION_DONE) | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
            all[i].setOnEditorActionListener((tv, a, e) -> {
                if (next == test) {
                    InputMethodManager ime = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                    if (ime != null) ime.hideSoftInputFromWindow(tv.getWindowToken(), 0);
                }
                next.requestFocus();
                return true;
            });
        }
        fServer.requestFocus();
    }

    private Button buildConfirm() {
        text("حذف الاشتراك", 40, INK, true, 8);
        TextView t = text("هل تريد حذف الاشتراك؟ سيتم مسح القنوات والأقسام والدليل والأخيرة والمفضلة وآخر قناة.", 26, MUTED, false, 30);
        t.setGravity(Gravity.CENTER);
        Button no = button("إلغاء");
        Button yes = button("تأكيد الحذف");
        no.setOnClickListener(x -> show(INFO));
        yes.setOnClickListener(x -> {
            AccountVault.clear(this);
            wipeOldData(this);
            statusDone = false;
            show(INFO);
        });
        return no;   // the safe choice has the focus
    }

    // MARK: test and save

    private String current() {
        return fServer.getText().toString().trim() + "|" + fPort.getText().toString().trim() + "|"
                + fUser.getText().toString().trim() + "|" + fPass.getText().toString();
    }

    /** "http://host" + "8080" → "http://host:8080"; null when the server can't be used. */
    private String address() {
        String h = fServer.getText().toString().trim().replaceAll("/+$", "");
        String p = fPort.getText().toString().trim();
        if (h.isEmpty()) return null;
        if (!h.startsWith("http://") && !h.startsWith("https://")) h = "http://" + h;
        try {
            URI u = URI.create(h);
            if (u.getHost() == null) return null;
            if (!p.isEmpty() && u.getPort() < 0) h = h + ":" + p;
        } catch (RuntimeException e) { return null; }
        return h;
    }

    private void say(String s, int color) { if (result != null) { result.setText(s); result.setTextColor(color); } }

    private void runTest(final boolean thenSave) {
        if (testing) return;
        final String base = address(), user = fUser.getText().toString().trim(), pass = fPass.getText().toString();
        if (base == null || user.isEmpty() || pass.isEmpty()) { say("أكمل السيرفر واسم المستخدم وكلمة المرور", BAD); return; }
        testing = true;
        final String snapshot = current();
        final int gen = generation;
        say("جارٍ اختبار الاتصال…", MUTED);
        try {
            io.execute(() -> {
                final int outcome = test(base, user, pass);   // 0 ok, 1 wrong login, 2 server silent
                main.post(() -> {
                    testing = false;
                    if (gen != generation || isFinishing()) return;
                    if (outcome == 0) {
                        testedFor = snapshot;
                        say("نجح الاتصال ✓", GOOD);
                        if (thenSave) save();
                    } else if (outcome == 1) say("بيانات الدخول غير صحيحة", BAD);
                    else say("السيرفر لا يرد", BAD);
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException gone) { testing = false; }
    }

    /** 0: the server answered and the login is good; 1: it answered and refused it; 2: no (usable) answer. */
    private static int test(String base, String user, String pass) {
        try {
            String body = XtreamAccount.get(base + "/player_api.php?username=" + XtreamAccount.enc(user) + "&password=" + XtreamAccount.enc(pass));
            JSONObject info = new JSONObject(body).optJSONObject("user_info");
            if (info == null) return 1;
            if (info.has("auth") && info.optInt("auth", 0) != 1) return 1;
            String s = info.optString("status");
            if (!s.isEmpty() && !"Active".equalsIgnoreCase(s) && !info.has("auth")) return 1;
            return 0;
        } catch (org.json.JSONException notXtream) {
            return 2;
        } catch (Exception e) {
            String m = String.valueOf(e.getMessage());
            return m.equals("HTTP 401") || m.equals("HTTP 403") ? 1 : 2;
        }
    }

    private void save() {
        String base = address();
        if (base == null) return;
        String user = fUser.getText().toString().trim(), pass = fPass.getText().toString();
        wipeOldData(this);                       // the old subscription's data goes first
        AccountVault.save(this, base, user, pass);   // sealed, as before
        XtreamAccount a = XtreamAccount.read(this);
        if (a == null) { say("تعذّر حفظ الاشتراك", BAD); return; }
        statusDone = false;
        final XtreamAccount account = a;
        // Loads the new subscription's categories in the background: Live TV finds them cached.
        if (loader != null) loader.shutdown();
        loader = new LiveData(this, account);
        show(INFO);
        loadNote = "تم حفظ الاشتراك · جارٍ تحميل القنوات الجديدة…";
        final TextView note = text(loadNote, 24, GOOD, false, 10);
        note.setGravity(Gravity.CENTER);
        final LiveData data = loader;
        data.loadCategories(() -> {
            if (isFinishing()) return;
            note.setText(data.failed ? "تم حفظ الاشتراك · تعذّر تحميل القنوات الآن" : "تم حفظ الاشتراك · " + data.categories.size() + " قسم جاهز");
        });
    }
    private String loadNote;

    /** Everything tied to the old subscription: channels, categories, guide, recents, favourites and the last channel. */
    static void wipeOldData(Context c) {
        File[] files = c.getFilesDir().listFiles();
        if (files != null) for (File f : files) {
            String n = f.getName();
            if (n.startsWith("live-") || n.startsWith("xtream-catalog-cache")) f.delete();
        }
        c.getApplicationContext().getSharedPreferences("ouswis-live", Context.MODE_PRIVATE).edit()
                .remove("recent").remove("favorites").remove("last").commit();
        XtreamAccount.prefs(c).edit().remove("lastChannel").remove("lastChannelName").commit();
        MatchChannels.invalidate();
    }

    // MARK: views

    private int px(float v) { return Math.round(v * scale); }

    private TextView text(String s, float size, int color, boolean bold, float bottom) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size * scale);
        t.setTextColor(color);
        t.setTypeface(bold ? Design.bold() : Design.regular());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = px(bottom);
        t.setLayoutParams(lp);
        page.addView(t);
        return t;
    }

    /** A card with a label on the right and its value on the left. */
    private TextView row(String label, String value, int valueColor) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(px(28), 0, px(28), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(px(18));
        r.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, px(70));
        lp.bottomMargin = px(10);
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 24 * scale);
        l.setTextColor(MUTED);
        l.setTypeface(Design.regular());
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 26 * scale);
        v.setTextColor(valueColor);
        v.setTypeface(Design.bold());
        v.setSingleLine(true);
        v.setEllipsize(android.text.TextUtils.TruncateAt.END);
        v.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        v.setTextDirection(View.TEXT_DIRECTION_LTR);
        r.addView(l, new LinearLayout.LayoutParams(px(250), -2));
        r.addView(v, new LinearLayout.LayoutParams(0, -2, 1));
        page.addView(r, lp);
        return v;
    }

    private EditText field(String hint, String value, int variation) {
        EditText e = new EditText(this);
        e.setId(View.generateViewId());
        e.setSingleLine(true);
        e.setHint(hint);
        e.setText(value);
        e.setInputType((variation == InputType.TYPE_CLASS_NUMBER ? InputType.TYPE_CLASS_NUMBER : InputType.TYPE_CLASS_TEXT | variation | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
        e.setTextColor(INK);
        e.setHintTextColor(0x66FFFFFF);
        e.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 26 * scale);
        e.setTypeface(Design.regular());
        e.setTextDirection(View.TEXT_DIRECTION_LTR);
        e.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        e.setPadding(px(28), 0, px(28), 0);
        e.setBackground(states(px(18)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, px(70));
        lp.bottomMargin = px(10);
        page.addView(e, lp);
        return e;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setId(View.generateViewId());
        b.setText(label);
        b.setAllCaps(false);
        b.setPadding(0, 0, 0, 0);
        b.setTextColor(INK);
        b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 26 * scale);
        b.setTypeface(Design.bold());
        b.setBackground(states(px(18)));
        b.setStateListAnimator(null);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, px(70));
        lp.topMargin = px(6);
        lp.bottomMargin = px(6);
        page.addView(b, lp);
        return b;
    }

    /** A dark #121A3A card; a white frame when focused (no bright system highlights on an OLED). */
    private GradientDrawable shape(boolean on, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(radius);
        if (on) g.setStroke(Math.max(2, px(3)), Color.WHITE);
        return g;
    }

    private StateListDrawable states(int radius) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[] {android.R.attr.state_focused}, shape(true, radius));
        s.addState(new int[] {android.R.attr.state_pressed}, shape(true, radius));
        s.addState(new int[] {}, shape(false, radius));
        return s;
    }
}
