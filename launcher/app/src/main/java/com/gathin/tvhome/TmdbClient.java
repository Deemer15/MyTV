package com.gathin.tvhome;

import android.net.Uri;
import android.util.JsonReader;
import android.util.JsonToken;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads TMDB's "trending this week" movie list for the Streaming section of the Media rail. Every call blocks; run
 * it off the main thread. TMDB is HTTPS, so no network_security_config change is needed. The v3 api_key goes in the
 * query string (never a header) and is never logged.
 */
final class TmdbClient {
    static final int CONNECT_TIMEOUT_MS = 6000;
    static final int READ_TIMEOUT_MS = 20000;
    static final String USER_AGENT = "CleanYourTV-Launcher/1.0";

    private final String apiKey;

    TmdbClient(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    /** Full poster URL for a TMDB poster_path, at a TV-friendly width; null when there is no path. */
    static String posterUrl(String posterPath) {
        if (posterPath == null || posterPath.isEmpty()) return null;
        return "https://image.tmdb.org/t/p/w500" + posterPath;
    }

    /** Full backdrop URL for a TMDB backdrop_path, wide 16:9 art for the home backdrop; null when absent. */
    static String backdropUrl(String backdropPath) {
        if (backdropPath == null || backdropPath.isEmpty()) return null;
        return "https://image.tmdb.org/t/p/w1280" + backdropPath;
    }

    /**
     * The trending-this-week movies, at most {@code limit}, in TMDB's order. Returns an empty list when no api key is
     * configured, so the Streaming section simply shows nothing rather than failing.
     */
    List<TmdbMovie> trendingWeek(int limit) throws IOException {
        List<TmdbMovie> out = new ArrayList<>();
        if (apiKey.isEmpty()) return out;
        String url = "https://api.themoviedb.org/3/trending/movie/week?api_key=" + Uri.encode(apiKey);
        try (Reply reply = open(url)) {
            JsonReader r = reply.json;
            r.beginObject();
            while (r.hasNext()) {
                if ("results".equals(r.nextName()) && r.peek() == JsonToken.BEGIN_ARRAY) {
                    r.beginArray();
                    while (r.hasNext()) {
                        int id = 0;
                        String title = "", posterPath = "", backdropPath = "", release = "";
                        r.beginObject();
                        while (r.hasNext()) {
                            switch (r.nextName()) {
                                case "id": id = (int) parseLong(string(r)); break;
                                case "title": title = string(r).trim(); break;
                                case "poster_path": posterPath = string(r).trim(); break;
                                case "backdrop_path": backdropPath = string(r).trim(); break;
                                case "release_date": release = string(r).trim(); break;
                                default: r.skipValue();
                            }
                        }
                        r.endObject();
                        if (id > 0 && !title.isEmpty() && out.size() < limit) {
                            out.add(new TmdbMovie(id, title, yearOf(release), posterPath, backdropPath));
                        }
                    }
                    r.endArray();
                } else {
                    r.skipValue();
                }
            }
            r.endObject();
        }
        return out;
    }

    /**
     * Looks up one movie by title (optionally constrained to a release year) and returns its wide backdrop URL, or
     * null when there is no api key, no match, or the match has no backdrop. Used to enrich a Media rail item whose
     * provider gave only a poster, so the wide showcase shows a 16:9 still instead of a cropped poster. Blocks; run
     * off the main thread. The year, when > 0, is passed to TMDB to disambiguate remakes and same-named series.
     */
    String searchBackdrop(String title, int year) throws IOException {
        if (apiKey.isEmpty() || title == null || title.trim().isEmpty()) return null;
        String url = "https://api.themoviedb.org/3/search/movie?api_key=" + Uri.encode(apiKey)
                + "&query=" + Uri.encode(title.trim()) + "&include_adult=false";
        if (year > 0) url += "&year=" + year;
        try (Reply reply = open(url)) {
            JsonReader r = reply.json;
            r.beginObject();
            while (r.hasNext()) {
                if ("results".equals(r.nextName()) && r.peek() == JsonToken.BEGIN_ARRAY) {
                    r.beginArray();
                    // Take the first result: TMDB orders search hits by relevance/popularity, and the year filter
                    // above already narrows a same-named remake to the right one when a year is known.
                    while (r.hasNext()) {
                        String backdropPath = "";
                        r.beginObject();
                        while (r.hasNext()) {
                            if ("backdrop_path".equals(r.nextName())) backdropPath = string(r);
                            else r.skipValue();
                        }
                        r.endObject();
                        String bd = backdropUrl(backdropPath.trim());
                        if (bd != null) return bd;   // first hit that actually has a backdrop
                    }
                    r.endArray();
                } else {
                    r.skipValue();
                }
            }
            r.endObject();
        }
        return null;
    }

    /** The year from a "YYYY-MM-DD" release date, or 0 when it is absent or unparseable. */
    private static int yearOf(String release) {
        if (release == null || release.length() < 4) return 0;
        try {
            return Integer.parseInt(release.substring(0, 4));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private Reply open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        c.setRequestProperty("User-Agent", USER_AGENT);
        c.setRequestProperty("Accept", "application/json");
        int code = c.getResponseCode();
        if (code != 200) {
            c.disconnect();
            throw new IOException("HTTP " + code + " from TMDB"); // the URL holds the key: not in the message
        }
        InputStream in = new BufferedInputStream(c.getInputStream(), 32 * 1024);
        return new Reply(c, new JsonReader(new InputStreamReader(in, StandardCharsets.UTF_8)));
    }

    /** A JSON reply; closing it also closes the connection. */
    private static final class Reply implements AutoCloseable {
        final JsonReader json;
        private final HttpURLConnection connection;

        Reply(HttpURLConnection connection, JsonReader json) {
            this.connection = connection;
            this.json = json;
        }

        @Override
        public void close() throws IOException {
            try {
                json.close();
            } finally {
                connection.disconnect();
            }
        }
    }

    private static String string(JsonReader r) throws IOException {
        JsonToken t = r.peek();
        if (t == JsonToken.NULL) {
            r.nextNull();
            return "";
        }
        if (t == JsonToken.STRING || t == JsonToken.NUMBER) return r.nextString();
        if (t == JsonToken.BOOLEAN) return String.valueOf(r.nextBoolean());
        r.skipValue();
        return "";
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
