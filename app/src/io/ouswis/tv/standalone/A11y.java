package io.ouswis.tv.standalone;

import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import java.util.ArrayList;
import java.util.List;

/**
 * The three redesigned screens draw everything on one Canvas, so TalkBack (and uiautomator, which the install tests use)
 * would see an empty view. Each draw pass records what it drew here (a rectangle in the 1920x1080 reference space and a
 * label); a node provider then hands those out as virtual children. Arrays are reused: a pass allocates nothing but the
 * label strings it already built. Recording is cheap, and the provider only reads the last finished pass.
 */
final class A11y {
    private static final int MAX = 120;
    private final RectF[] rect = new RectF[MAX];
    private final String[] label = new String[MAX];
    private final boolean[] focused = new boolean[MAX], clickable = new boolean[MAX];
    private int n;
    private float k = 1, dy;

    /** Vertical shift of what is drawn next (a block moved with Canvas.translate). */
    void shift(float by) { dy = by; }
    private final View host;

    A11y(View host) {
        this.host = host;
        for (int i = 0; i < MAX; i++) rect[i] = new RectF();
    }

    /** Start of a draw pass; k is the reference-to-pixel scale. */
    void begin(float scale) {
        n = 0;
        dy = 0;
        k = scale;
    }

    /** One drawn element. A blank label records nothing. */
    void add(float l, float t, float r, float b, String text, boolean hasFocus, boolean canClick) {
        if (n >= MAX || text == null || text.isEmpty()) return;
        rect[n].set(l, t + dy, r, b + dy);
        label[n] = text;
        focused[n] = hasFocus;
        clickable[n] = canClick;
        n++;
    }

    void add(RectF r, String text, boolean hasFocus, boolean canClick) {
        add(r.left, r.top, r.right, r.bottom, text, hasFocus, canClick);
    }

    /** A line of plain text with no box of its own (a title, the clock): its extent is a thin band at (x, y). */
    void text(float right, float baseline, float width, float height, String text) {
        add(right - width, baseline - height, right, baseline + height * 0.25f, text, false, false);
    }

    int count() {
        return n;
    }

    String label(int i) {
        return label[i];
    }

    boolean hasLabel(String part) {
        for (int i = 0; i < n; i++) if (label[i].contains(part)) return true;
        return false;
    }

    /** Labels of the nodes in the order they were drawn (tests and logs). */
    List<String> labels() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(label[i]);
        return out;
    }

    /** What the view returns from getAccessibilityNodeProvider. */
    AccessibilityNodeProvider provider(final Runnable onClick, final IntClick click) {
        return new AccessibilityNodeProvider() {
            @Override public AccessibilityNodeInfo createAccessibilityNodeInfo(int id) {
                if (id == View.NO_ID) {
                    AccessibilityNodeInfo root = AccessibilityNodeInfo.obtain(host);
                    host.onInitializeAccessibilityNodeInfo(root);
                    for (int i = 0; i < n; i++) root.addChild(host, i + 1);
                    return root;
                }
                int i = id - 1;
                if (i < 0 || i >= n) return null;
                AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain(host, id);
                info.setParent(host);
                info.setPackageName(host.getContext().getPackageName());
                info.setClassName(clickable[i] ? "android.widget.Button" : "android.widget.TextView");
                info.setText(label[i]);
                info.setContentDescription(label[i]);
                info.setEnabled(true);
                info.setVisibleToUser(true);
                info.setFocusable(clickable[i]);
                info.setFocused(focused[i]);
                info.setClickable(clickable[i]);
                if (clickable[i]) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
                Rect parent = new Rect((int) (rect[i].left * k), (int) (rect[i].top * k), (int) (rect[i].right * k), (int) (rect[i].bottom * k));
                info.setBoundsInParent(parent);
                int[] at = new int[2];
                host.getLocationOnScreen(at);
                Rect screen = new Rect(parent);
                screen.offset(at[0], at[1]);
                info.setBoundsInScreen(screen);
                return info;
            }

            @Override public boolean performAction(int id, int action, Bundle args) {
                if (id == View.NO_ID) return host.performAccessibilityAction(action, args);
                if (action == AccessibilityNodeInfo.ACTION_CLICK && click != null && id >= 1 && id <= n) {
                    click.click(id - 1);
                    return true;
                }
                return false;
            }
        };
    }

    interface IntClick {
        void click(int index);
    }
}
