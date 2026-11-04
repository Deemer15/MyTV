package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * The cinematic shelf's backdrop: an app's artwork reduced to a small bitmap (48×27 by default), saturated and
 * smoothed, then stretched over the screen by its ImageView. Bilinear filtering of an image that small is a
 * blur that costs one tiny texture; a real blur would mean RenderScript or a shader pass per frame, which a 1 GB
 * TV cannot spare. Callers wanting a lighter blur from a higher-quality source pass a larger w×h and fewer passes
 * to {@link #fromBitmap(Bitmap, int, int, int)} — still one small texture, so still no per-frame cost.
 */
final class HeroArt {
    static final int W = 48, H = 27;
    private static final float SATURATION = 1.7f;

    private HeroArt() {
    }

    /** The softened artwork, about 5 KB; null when there is nothing to draw. */
    static Bitmap from(Drawable live) {
        if (live == null) return null;
        // Draw a private copy: changing the bounds of the drawable a tile shows would blank the tile.
        Drawable d = live.getConstantState() != null ? live.getConstantState().newDrawable().mutate() : live;
        Rect saved = new Rect(d.getBounds());
        Bitmap big = Bitmap.createBitmap(W * 4, H * 4, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(big);
        c.drawColor(Color.BLACK);
        d.setBounds(0, 0, big.getWidth(), big.getHeight());
        d.draw(c);
        d.setBounds(saved);
        return soften(big, W, H, 3);
    }

    /**
     * Same softening for a fetched photo (a carousel backdrop/poster) rather than an app icon: reduce to {@link #W}×
     * {@link #H}, saturate and box-blur, so the ImageView's stretch looks like a blur at no per-frame cost. The
     * source is not recycled (the caller may still cache or display it).
     */
    static Bitmap fromBitmap(Bitmap src) {
        return fromBitmap(src, W, H, 3);
    }

    /**
     * As {@link #fromBitmap(Bitmap)} but with a caller-chosen softened resolution and blur-pass count, so a
     * higher-quality source can keep more detail (a larger w×h and fewer passes = a lighter blur). Still just a
     * small bitmap the ImageView stretches, so there is no per-frame blur cost regardless of w×h. The source is
     * not recycled.
     */
    static Bitmap fromBitmap(Bitmap src, int w, int h, int passes) {
        if (src == null || src.getWidth() <= 0 || src.getHeight() <= 0) return null;
        // Scale into a w*4×h*4 canvas filling the frame (the backdrop is drawn fitXY), so the softened art keeps
        // the whole image's colour rather than letterbox bars.
        Bitmap big = Bitmap.createBitmap(w * 4, h * 4, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(big);
        c.drawColor(Color.BLACK);
        Rect dst = new Rect(0, 0, big.getWidth(), big.getHeight());
        c.drawBitmap(src, null, dst, new Paint(Paint.FILTER_BITMAP_FLAG));
        return soften(big, w, h, passes);
    }

    /** Shared tail of {@link #from} and {@link #fromBitmap}: halve twice, saturate, N box passes. Recycles big. */
    private static Bitmap soften(Bitmap big, int w, int h, int passes) {
        // Halve twice: each filtered halving averages 2×2 pixels, where one 4× step would skip most of them.
        Bitmap half = Bitmap.createScaledBitmap(big, w * 2, h * 2, true);
        big.recycle();
        Bitmap small = Bitmap.createScaledBitmap(half, w, h, true);
        half.recycle();

        int[] px = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        saturate(px);
        // Box passes approximate a gaussian, so the stretched image shows no seams between pixels.
        for (int pass = 0; pass < passes; pass++) {
            px = boxBlur(px, w, h, 2, true);
            px = boxBlur(px, w, h, 2, false);
        }
        Bitmap out = small.isMutable() ? small : small.copy(Bitmap.Config.ARGB_8888, true);
        if (out != small) small.recycle();
        out.setPixels(px, 0, w, 0, 0, w, h);
        return out;
    }

    private static void saturate(int[] px) {
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int r = Color.red(p), g = Color.green(p), b = Color.blue(p);
            float lum = 0.2126f * r + 0.7152f * g + 0.0722f * b;
            px[i] = Color.rgb(clamp(lum + (r - lum) * SATURATION), clamp(lum + (g - lum) * SATURATION),
                    clamp(lum + (b - lum) * SATURATION));
        }
    }

    private static int clamp(float v) {
        return v < 0 ? 0 : v > 255 ? 255 : Math.round(v);
    }

    /** One box blur pass along rows (horizontal) or columns, edges clamped. */
    private static int[] boxBlur(int[] src, int w, int h, int radius, boolean horizontal) {
        int[] dst = new int[src.length];
        int lines = horizontal ? h : w, length = horizontal ? w : h, span = 2 * radius + 1;
        for (int line = 0; line < lines; line++) {
            for (int i = 0; i < length; i++) {
                int r = 0, g = 0, b = 0;
                for (int k = -radius; k <= radius; k++) {
                    int j = Math.max(0, Math.min(length - 1, i + k));
                    int p = horizontal ? src[line * w + j] : src[j * w + line];
                    r += Color.red(p);
                    g += Color.green(p);
                    b += Color.blue(p);
                }
                dst[horizontal ? line * w + i : i * w + line] = Color.rgb(r / span, g / span, b / span);
            }
        }
        return dst;
    }
}
