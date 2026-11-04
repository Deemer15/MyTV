package com.gathin.tvhome;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.util.JsonReader;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Home's weather widget (config weather_widget): current temperature, a condition and today's high and low, in
 * Fahrenheit, from Open-Meteo (HTTPS, no account or key). The place is the TV's own last known location (the network
 * provider Android keeps from Wi-Fi), rounded to two decimals (about a kilometre) before it is sent; with none, the
 * last place seen, then config.xml's latitude and longitude. No place at all means no widget. Blocking: call it off
 * the main thread.
 */
final class WeatherClient {
    /** The condition's icon family; WeatherIconView draws each one. */
    static final int CLEAR = 0, PARTLY = 1, CLOUDY = 2, FOG = 3, RAIN = 4, SNOW = 5, STORM = 6;

    static final class Weather {
        final int temp, high, low, kind;
        final boolean night;
        final long fetchedAt;

        Weather(int temp, int high, int low, int kind, boolean night, long fetchedAt) {
            this.temp = temp;
            this.high = high;
            this.low = low;
            this.kind = kind;
            this.night = night;
            this.fetchedAt = fetchedAt;
        }
    }

    private static final int TIMEOUT_MS = 8000;
    private static final String PREFS = "weather_place";

    private WeatherClient() {
    }

    /** {latitude, longitude} rounded to 0.01, or null when the TV knows no place. */
    static double[] place(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Location best = null;
        boolean allowed = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        LocationManager lm = ctx.getSystemService(LocationManager.class);
        if (allowed && lm != null) {
            try {
                for (String provider : lm.getProviders(true)) {
                    Location l = lm.getLastKnownLocation(provider);
                    if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
                }
            } catch (SecurityException | IllegalArgumentException ignored) {
                // no usable provider: the saved or configured place
            }
        }
        if (best != null) {
            double lat = round(best.getLatitude()), lon = round(best.getLongitude());
            prefs.edit().putString("lat", String.valueOf(lat)).putString("lon", String.valueOf(lon)).apply();
            return new double[]{lat, lon};
        }
        double[] saved = parse(prefs.getString("lat", ""), prefs.getString("lon", ""));
        if (saved != null) return saved;
        return parse(ctx.getString(R.string.latitude), ctx.getString(R.string.longitude));
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double[] parse(String lat, String lon) {
        try {
            if (lat.trim().isEmpty() || lon.trim().isEmpty()) return null;
            return new double[]{round(Double.parseDouble(lat.trim())), round(Double.parseDouble(lon.trim()))};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Null when there is no place; IOException when the request or its answer fails. */
    static Weather fetch(Context ctx) throws IOException {
        double[] place = place(ctx);
        if (place == null) return null;
        String url = String.format(Locale.ROOT,
                "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f"
                        + "&current=temperature_2m,weather_code,is_day"
                        + "&daily=temperature_2m_max,temperature_2m_min"
                        + "&temperature_unit=fahrenheit&timezone=auto&forecast_days=1",
                place[0], place[1]);
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        try {
            if (c.getResponseCode() != 200) throw new IOException("weather HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream();
                 JsonReader json = new JsonReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                return read(json);
            }
        } finally {
            c.disconnect();
        }
    }

    private static Weather read(JsonReader json) throws IOException {
        double temp = Double.NaN, high = Double.NaN, low = Double.NaN;
        int code = 0, isDay = 1;
        json.beginObject();
        while (json.hasNext()) {
            String name = json.nextName();
            if (name.equals("current")) {
                json.beginObject();
                while (json.hasNext()) {
                    String k = json.nextName();
                    if (k.equals("temperature_2m")) temp = json.nextDouble();
                    else if (k.equals("weather_code")) code = json.nextInt();
                    else if (k.equals("is_day")) isDay = json.nextInt();
                    else json.skipValue();
                }
                json.endObject();
            } else if (name.equals("daily")) {
                json.beginObject();
                while (json.hasNext()) {
                    String k = json.nextName();
                    if (k.equals("temperature_2m_max")) high = firstOf(json);
                    else if (k.equals("temperature_2m_min")) low = firstOf(json);
                    else json.skipValue();
                }
                json.endObject();
            } else {
                json.skipValue();
            }
        }
        json.endObject();
        if (Double.isNaN(temp)) throw new IOException("weather answer has no temperature");
        if (Double.isNaN(high)) high = temp;
        if (Double.isNaN(low)) low = temp;
        return new Weather((int) Math.round(temp), (int) Math.round(high), (int) Math.round(low),
                kind(code), isDay == 0, System.currentTimeMillis());
    }

    /** The first number of a JSON array; skips the rest (forecast_days=1 sends one). */
    private static double firstOf(JsonReader json) throws IOException {
        double v = Double.NaN;
        json.beginArray();
        boolean first = true;
        while (json.hasNext()) {
            if (first && json.peek() != android.util.JsonToken.NULL) v = json.nextDouble();
            else json.skipValue();
            first = false;
        }
        json.endArray();
        return v;
    }

    /** WMO weather code to an icon family. */
    static int kind(int code) {
        if (code == 0) return CLEAR;
        if (code <= 2) return PARTLY;
        if (code == 3) return CLOUDY;
        if (code == 45 || code == 48) return FOG;
        if (code >= 51 && code <= 67) return RAIN;
        if (code >= 71 && code <= 77) return SNOW;
        if (code >= 80 && code <= 82) return RAIN;
        if (code == 85 || code == 86) return SNOW;
        if (code >= 95) return STORM;
        return CLOUDY;
    }
}
