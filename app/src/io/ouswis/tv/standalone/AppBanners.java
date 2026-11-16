package io.ouswis.tv.standalone;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;

/** What an app card is made of: the app's banner (or logo) if it has one, else its whole icon on a card of the icon's own colour. */
final class AppBanners {
    private AppBanners() {}

    static final int CARD = 0xFF1A1F3A;   // the flat card of an icon with no colour of its own

    /** A banner or logo found for an app, and where it came from (for the log). */
    static final class Found {
        final Drawable drawable;
        final String how;
        Found(Drawable d, String how) {
            this.drawable = d;
            this.how = how;
        }
    }

    /**
     * The art for the whole card, in this order: the banner of the app's LEANBACK_LAUNCHER activity, the app's banner,
     * the banner the activity info loads, the activity's logo, the app's logo.
     */
    static Found find(Context c, String pkg) {
        PackageManager pm = c.getPackageManager();
        Intent launch = HomeApps.launchIntent(pm, pkg);
        Drawable d = null;
        try {
            if (launch != null && launch.getComponent() != null) {
                ActivityInfo ai = pm.getActivityInfo(launch.getComponent(), 0);
                int res = ai.getBannerResource();
                if (res != 0) {   // at the highest density the app has
                    try {
                        d = pm.getResourcesForApplication(ai.applicationInfo).getDrawableForDensity(res, DisplayMetrics.DENSITY_XXXHIGH, null);
                    } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
                    }
                }
            }
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
        }
        if (d != null) return new Found(d, "activity banner");
        try {
            if (launch != null) d = pm.getActivityBanner(launch);
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
        }
        if (d != null) return new Found(d, "activity banner");
        try {
            d = pm.getApplicationBanner(pkg);
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
        }
        if (d != null) return new Found(d, "app banner");
        try {
            if (launch != null && launch.getComponent() != null) d = pm.getActivityInfo(launch.getComponent(), 0).loadBanner(pm);
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
        }
        if (d != null) return new Found(d, "loaded banner");
        try {
            if (launch != null) d = pm.getActivityLogo(launch);
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
        }
        if (d != null) return new Found(d, "activity logo");
        try {
            d = pm.getApplicationLogo(pkg);
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
        }
        return d != null ? new Found(d, "app logo") : null;
    }

    /** Fills w × h with d, keeping its proportions (the extra is cropped, evenly); a 16:9 banner just fills it. */
    static void cover(Canvas canvas, Drawable d, int w, int h) {
        int iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
        if (iw <= 0 || ih <= 0) {
            d.setBounds(0, 0, w, h);
            d.draw(canvas);
            return;
        }
        float s = Math.max(w / (float) iw, h / (float) ih);
        int dw = Math.round(iw * s), dh = Math.round(ih * s);
        int save = canvas.save();
        canvas.clipRect(0, 0, w, h);
        d.setBounds((w - dw) / 2, (h - dh) / 2, (w - dw) / 2 + dw, (h - dh) / 2 + dh);
        d.draw(canvas);
        canvas.restoreToCount(save);
    }

    /**
     * The app's whole icon, size × size: an adaptive icon with its background and its foreground in the launcher's
     * squircle (never the monochrome layer), else the plain icon. Null when nothing drew. `how[0]` says which one.
     */
    static Bitmap fullIcon(Context c, String pkg, int size, String[] how) {
        PackageManager pm = c.getPackageManager();
        Drawable[] tries = new Drawable[3];
        String[] names = {"icon", "app icon", "icon resource"};
        try {
            Intent launch = HomeApps.launchIntent(pm, pkg);
            if (launch != null && launch.getComponent() != null) tries[0] = pm.getActivityInfo(launch.getComponent(), 0).loadIcon(pm);
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
        }
        try {
            tries[1] = pm.getApplicationInfo(pkg, 0).loadIcon(pm);
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
        }
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            if (ai.icon != 0) tries[2] = pm.getResourcesForApplication(ai).getDrawableForDensity(ai.icon, DisplayMetrics.DENSITY_XXXHIGH, null);
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
        }
        Bitmap spare = null;   // an icon that is one flat colour: shown only if nothing better turns up
        String spareHow = null;
        for (int pass = 0; pass < 2; pass++) {   // pass 1: the foreground alone, only for an icon whose full drawing is one flat colour
            for (int i = 0; i < tries.length; i++) {
                Drawable d = tries[i];
                if (d == null) continue;
                boolean adaptive = d instanceof AdaptiveIconDrawable;
                if (pass == 1 && !adaptive) continue;
                Bitmap glyph = null;
                try {
                    glyph = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                    Canvas gc = new Canvas(glyph);
                    if (adaptive) {
                        AdaptiveIconDrawable a = (AdaptiveIconDrawable) d;
                        Bitmap layers = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                        Canvas lc = new Canvas(layers);
                        int pad = size / 4;   // layers are 108 dp, 72 dp of it show
                        Drawable[] ls = pass == 0 ? new Drawable[] {a.getBackground(), a.getForeground()} : new Drawable[] {a.getForeground()};
                        for (Drawable layer : ls) {
                            if (layer == null) continue;
                            layer.setBounds(-pad, -pad, size + pad, size + pad);
                            layer.draw(lc);
                        }
                        Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG);
                        sp.setShader(new BitmapShader(layers, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
                        gc.drawRoundRect(0, 0, size, size, size * 0.2237f, size * 0.2237f, sp);
                        layers.recycle();
                    } else {
                        d.setBounds(0, 0, size, size);
                        d.draw(gc);
                    }
                    if (!visible(glyph)) {
                        glyph.recycle();
                        glyph = null;
                        continue;
                    }
                    if (adaptive && pass == 0 && flat(glyph)) {
                        if (spare == null) {
                            spare = glyph;
                            spareHow = names[i] + " (flat)";
                            glyph = null;
                        }
                        continue;
                    }
                    if (spare != null) spare.recycle();
                    how[0] = names[i] + (pass == 1 ? " (foreground only)" : "");
                    return glyph;
                } catch (RuntimeException ignored) {
                }
                if (glyph != null) glyph.recycle();
            }
        }
        if (spare != null) how[0] = spareHow;
        return spare;
    }

    /** Something drawn: a few percent of the picture is opaque. */
    private static boolean visible(Bitmap b) {
        int seen = 0, n = 0;
        for (int i = 0; i < 16; i++)
            for (int j = 0; j < 16; j++) {
                n++;
                if ((b.getPixel(Math.min(b.getWidth() - 1, (i * b.getWidth() + b.getWidth() / 2) / 16), Math.min(b.getHeight() - 1, (j * b.getHeight() + b.getHeight() / 2) / 16)) >>> 24) >= 64) seen++;
            }
        return seen >= n / 12;
    }

    /** One colour all over (a white square): the opaque samples inside the edge hardly differ. */
    private static boolean flat(Bitmap b) {
        int min = 255, max = 0, seen = 0;
        for (int i = 0; i < 12; i++)
            for (int j = 0; j < 12; j++) {
                int x = Math.min(b.getWidth() - 1, Math.round(b.getWidth() * (0.12f + 0.76f * i / 11)));
                int y = Math.min(b.getHeight() - 1, Math.round(b.getHeight() * (0.12f + 0.76f * j / 11)));
                int px = b.getPixel(x, y);
                if ((px >>> 24) < 8) continue;
                int lum = ((px >> 16 & 255) * 3 + (px >> 8 & 255) * 6 + (px & 255)) / 10;
                seen++;
                min = Math.min(min, lum);
                max = Math.max(max, lum);
            }
        return seen < 6 || max - min < 10;
    }

    /**
     * The icon's dominant colour, darkened so white text reads on it (Palette's idea: the hue that most colourful pixels
     * share, averaged). An icon with no colour of its own (white, grey, black) gets the flat dark card.
     */
    static int dominant(Bitmap icon) {
        final int buckets = 24;
        float[] weight = new float[buckets];
        float[] r = new float[buckets], g = new float[buckets], bl = new float[buckets];
        float[] hsv = new float[3];
        int w = icon.getWidth(), h = icon.getHeight();
        for (int i = 0; i < 32; i++)
            for (int j = 0; j < 32; j++) {
                int px = icon.getPixel(Math.min(w - 1, (int) ((i + 0.5f) * w / 32)), Math.min(h - 1, (int) ((j + 0.5f) * h / 32)));
                if ((px >>> 24) < 200) continue;
                Color.colorToHSV(px, hsv);
                if (hsv[1] < 0.25f || hsv[2] < 0.2f) continue;   // white, grey, near black: no colour
                float wt = hsv[1] * hsv[2];
                int k = Math.min(buckets - 1, (int) (hsv[0] / 360f * buckets));
                weight[k] += wt;
                r[k] += wt * (px >> 16 & 255);
                g[k] += wt * (px >> 8 & 255);
                bl[k] += wt * (px & 255);
            }
        int best = -1;
        for (int k = 0; k < buckets; k++) if (best < 0 || weight[k] > weight[best]) best = k;
        if (best < 0 || weight[best] < 8f) return CARD;   // under about 40 colourful samples of 1024
        int col = Color.rgb(Math.round(r[best] / weight[best]), Math.round(g[best] / weight[best]), Math.round(bl[best] / weight[best]));
        Color.colorToHSV(col, hsv);
        hsv[2] = Math.min(hsv[2], 0.42f);   // luminance well under half: white text shows
        hsv[1] = Math.min(1f, hsv[1] * 0.95f);
        int out = Color.HSVToColor(hsv);
        // a yellow is bright even at 0.42: pull it down until white text has contrast
        for (int n = 0; n < 6 && luma(out) > 75; n++) {
            hsv[2] *= 0.85f;
            out = Color.HSVToColor(hsv);
        }
        return out;
    }

    private static double luma(int c) {
        return 0.299 * (c >> 16 & 255) + 0.587 * (c >> 8 & 255) + 0.114 * (c & 255);
    }
}
