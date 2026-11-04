package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Shared poster downloader for the rails and the browse view (IptvRail, MediaRail, BrowseActivity). One copy of the
 * download-and-decode so the three callers do not each carry it in the dex. Downloads a poster over HTTP(S) and
 * decodes it no larger than {@code imageW} needs, since some providers link full-size artwork; returns null on any
 * failure so the caller keeps its placeholder.
 */
final class RailImages {
    private static final int MAX_IMAGE_BYTES = 6 * 1024 * 1024;

    private RailImages() {}

    static Bitmap fetch(String url, int imageW) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(IptvClient.CONNECT_TIMEOUT_MS);
            c.setReadTimeout(IptvClient.READ_TIMEOUT_MS);
            c.setRequestProperty("User-Agent", IptvClient.USER_AGENT);
            if (c.getResponseCode() != 200) return null;
            byte[] data;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream(64 * 1024);
                byte[] chunk = new byte[16 * 1024];
                for (int n; (n = in.read(chunk)) > 0; ) {
                    buf.write(chunk, 0, n);
                    if (buf.size() > MAX_IMAGE_BYTES) return null;
                }
                data = buf.toByteArray();
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (o.outWidth <= 0) return null;
            int sample = 1;
            while (o.outWidth / (sample * 2) >= imageW) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.ARGB_8888; // full colour: crisp posters, no 16-bit banding
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Exception e) {
            return null; // the placeholder stays
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
