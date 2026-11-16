package io.ouswis.tv.standalone;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.DisplayMetrics;
import android.graphics.drawable.Drawable;

/** What an app card is made of: the app's banner if it has one, else its whole icon on the flat dark card. */
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
     * The art of the card, the way Android TV's own launcher finds it: the banner `ActivityInfo.loadBanner()` gives for
     * the app's LEANBACK_LAUNCHER activity (it falls back to the application's banner), then the package manager's
     * activity banner, then the application's. A logo is no banner: an app without one gets its icon on the flat card.
     */
    static Found find(Context c, String pkg) {
        PackageManager pm = c.getPackageManager();
        Intent launch = HomeApps.launchIntent(pm, pkg);
        Drawable d = null;
        try {
            if (launch != null && launch.getComponent() != null) d = pm.getActivityInfo(launch.getComponent(), 0).loadBanner(pm);
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
        }
        if (d != null) return new Found(d, "loadBanner");
        try {
            if (launch != null) d = pm.getActivityBanner(launch);
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
        }
        if (d != null) return new Found(d, "activity banner");
        try {
            d = pm.getApplicationBanner(pkg);
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
        }
        return d != null ? new Found(d, "app banner") : null;
    }

    /**
     * A banner on the card. About the card's own proportions (16:9, within 12%): it fills the card. Any other shape is shown
     * whole and centred on the card, never cropped: a square banner must not lose its edges.
     */
    static void cover(Canvas canvas, Drawable d, int w, int h) {
        int iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
        if (iw <= 0 || ih <= 0) {
            d.setBounds(0, 0, w, h);
            d.draw(canvas);
            return;
        }
        float ratio = (iw / (float) ih) / (w / (float) h);
        if (ratio > 0.88f && ratio < 1.12f) {
            d.setBounds(0, 0, w, h);
            d.draw(canvas);
            return;
        }
        float s = Math.min(w / (float) iw, h / (float) ih);
        int dw = Math.round(iw * s), dh = Math.round(ih * s);
        d.setBounds((w - dw) / 2, (h - dh) / 2, (w - dw) / 2 + dw, (h - dh) / 2 + dh);
        d.draw(canvas);
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
                        int iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
                        float sc = iw > 0 && ih > 0 ? Math.min(size / (float) iw, size / (float) ih) : 0;
                        int dw = sc > 0 ? Math.round(iw * sc) : size, dh = sc > 0 ? Math.round(ih * sc) : size;
                        d.setBounds((size - dw) / 2, (size - dh) / 2, (size - dw) / 2 + dw, (size - dh) / 2 + dh);   // whole and centred
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
}
