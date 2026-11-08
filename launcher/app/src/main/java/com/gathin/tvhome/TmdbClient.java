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

    /** Full backdrop URL for a TMDB backdrop_path: original source (1080p-class art) for a crisp full-screen banner;
     *  null when absent. */
    static String backdropUrl(String backdropPath) {
        if (backdropPath == null || backdropPath.isEmpty()) return null;
        return "https://image.tmdb.org/t/p/original" + backdropPath;
    }

    /** Full still URL for an episode still_path, at original source for the wide banner/card; null when absent. */
    static String stillUrl(String stillPath) {
        if (stillPath == null || stillPath.isEmpty()) return null;
        return "https://image.tmdb.org/t/p/original" + stillPath;
    }

    /**
     * The trending-this-week movies, at most {@code limit}, in TMDB's order. Returns an empty list when no api key is
     * configured, so the Streaming section simply shows nothing rather than failing.
     */
    List<TmdbMovie> trendingWeek(int limit) throws IOException {
        List<TmdbMovie> out = new ArrayList<>();
        // TMDB answers 20 titles a page; ask for more pages only when the caller wants more than one page of them.
        for (int page = 1; page <= 3 && out.size() < limit; page++) {
            List<TmdbMovie> got = trendingPage(page, limit - out.size());
            if (got.isEmpty()) break;
            out.addAll(got);
        }
        return out;
    }

    private List<TmdbMovie> trendingPage(int page, int limit) throws IOException {
        List<TmdbMovie> out = new ArrayList<>();
        if (apiKey.isEmpty()) return out;
        String url = "https://api.themoviedb.org/3/trending/movie/week?api_key=" + Uri.encode(apiKey)
                + "&page=" + page;
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

    /** What the Explore banner shows about one title; every field may be empty/0. */
    static final class Info {
        final String overview;
        final double rating;    // TMDB's vote_average out of 10; 0 when unrated
        final int year;
        final String backdrop;  // full wide backdrop URL or ""
        final String logo;      // full title-logo URL or ""
        final String certification;   // US age rating, e.g. "PG-13", "R", "TV-MA"; "" when none

        Info(String overview, double rating, int year, String backdrop, String logo, String certification) {
            this.overview = overview;
            this.rating = rating;
            this.year = year;
            this.backdrop = backdrop;
            this.logo = logo;
            this.certification = certification;
        }
    }

    /**
     * Overview, rating, year, backdrop and title logo for one movie or show, in one details call (logos come with
     * append_to_response=images). {@code knownId} is TMDB's own id when the title came from TMDB trending; with 0 the
     * title is searched first. Null with no key or no match. Blocks; run off the main thread.
     */
    Info lookup(String title, int year, boolean series, int knownId) throws IOException {
        if (apiKey.isEmpty()) return null;
        String kind = series ? "tv" : "movie";
        int id = knownId > 0 ? knownId : searchId(kind, title, year);
        if (id <= 0) return null;
        // A movie's age rating lives in release_dates, a show's in content_ratings; ask for the one that applies.
        String ratings = series ? "content_ratings" : "release_dates";
        String url = "https://api.themoviedb.org/3/" + kind + "/" + id + "?api_key=" + Uri.encode(apiKey)
                + "&append_to_response=images," + ratings + "&include_image_language=en,null";
        String overview = "", date = "", backdrop = "", logo = "", certification = "";
        double rating = 0;
        try (Reply reply = open(url)) {
            JsonReader r = reply.json;
            r.beginObject();
            while (r.hasNext()) {
                switch (r.nextName()) {
                    case "overview": overview = string(r).trim(); break;
                    case "vote_average": rating = parseDouble(string(r)); break;
                    case "release_date":
                    case "first_air_date": date = string(r).trim(); break;
                    case "backdrop_path": backdrop = string(r).trim(); break;
                    case "images":
                        if (r.peek() == JsonToken.BEGIN_OBJECT) logo = firstLogo(r); else r.skipValue();
                        break;
                    case "release_dates":
                        if (r.peek() == JsonToken.BEGIN_OBJECT) certification = usMovieCertification(r); else r.skipValue();
                        break;
                    case "content_ratings":
                        if (r.peek() == JsonToken.BEGIN_OBJECT) certification = usShowRating(r); else r.skipValue();
                        break;
                    default: r.skipValue();
                }
            }
            r.endObject();
        }
        String bd = backdropUrl(backdrop);
        String lg = logo.isEmpty() ? "" : "https://image.tmdb.org/t/p/w500" + logo;
        return new Info(overview, rating, yearOf(date), bd == null ? "" : bd, lg, certification);
    }

    /**
     * The US age rating of a movie from its release_dates object: the theatrical release's certification (type 3),
     * else the first certification TMDB lists for the US. "" when there is none.
     */
    private static String usMovieCertification(JsonReader r) throws IOException {
        String cert = "";
        r.beginObject();
        while (r.hasNext()) {
            if ("results".equals(r.nextName()) && r.peek() == JsonToken.BEGIN_ARRAY) {
                r.beginArray();
                while (r.hasNext()) {
                    String country = "", theatrical = "", any = "";
                    r.beginObject();
                    while (r.hasNext()) {
                        switch (r.nextName()) {
                            case "iso_3166_1": country = string(r).trim(); break;
                            case "release_dates":
                                if (r.peek() == JsonToken.BEGIN_ARRAY) {
                                    r.beginArray();
                                    while (r.hasNext()) {
                                        String c = "";
                                        int type = 0;
                                        r.beginObject();
                                        while (r.hasNext()) {
                                            switch (r.nextName()) {
                                                case "certification": c = string(r).trim(); break;
                                                case "type": type = (int) parseLong(string(r)); break;
                                                default: r.skipValue();
                                            }
                                        }
                                        r.endObject();
                                        if (c.isEmpty()) continue;
                                        if (any.isEmpty()) any = c;
                                        if (type == 3 && theatrical.isEmpty()) theatrical = c;
                                    }
                                    r.endArray();
                                } else {
                                    r.skipValue();
                                }
                                break;
                            default: r.skipValue();
                        }
                    }
                    r.endObject();
                    if ("US".equals(country)) cert = theatrical.isEmpty() ? any : theatrical;
                }
                r.endArray();
            } else {
                r.skipValue();
            }
        }
        r.endObject();
        return cert;
    }

    /** The US rating of a show from its content_ratings object (for example "TV-MA"); "" when there is none. */
    private static String usShowRating(JsonReader r) throws IOException {
        String rating = "";
        r.beginObject();
        while (r.hasNext()) {
            if ("results".equals(r.nextName()) && r.peek() == JsonToken.BEGIN_ARRAY) {
                r.beginArray();
                while (r.hasNext()) {
                    String country = "", value = "";
                    r.beginObject();
                    while (r.hasNext()) {
                        switch (r.nextName()) {
                            case "iso_3166_1": country = string(r).trim(); break;
                            case "rating": value = string(r).trim(); break;
                            default: r.skipValue();
                        }
                    }
                    r.endObject();
                    if ("US".equals(country)) rating = value;
                }
                r.endArray();
            } else {
                r.skipValue();
            }
        }
        r.endObject();
        return rating;
    }

    /** The "images" object's best logo path: the first English one, else the first of any language. */
    private static String firstLogo(JsonReader r) throws IOException {
        String english = "", any = "";
        r.beginObject();
        while (r.hasNext()) {
            if ("logos".equals(r.nextName()) && r.peek() == JsonToken.BEGIN_ARRAY) {
                r.beginArray();
                while (r.hasNext()) {
                    String path = "", lang = "";
                    r.beginObject();
                    while (r.hasNext()) {
                        switch (r.nextName()) {
                            case "file_path": path = string(r).trim(); break;
                            case "iso_639_1": lang = string(r).trim(); break;
                            default: r.skipValue();
                        }
                    }
                    r.endObject();
                    if (path.isEmpty()) continue;
                    if (any.isEmpty()) any = path;
                    if (english.isEmpty() && "en".equals(lang)) english = path;
                }
                r.endArray();
            } else {
                r.skipValue();
            }
        }
        r.endObject();
        return english.isEmpty() ? any : english;
    }

    /** One episode's TMDB text, keyed by season+episode number so a caller can match it to a library file. */
    static final class EpisodeInfo {
        final int season, episode;
        final String name, overview, still;   // still = full image URL or ""

        EpisodeInfo(int season, int episode, String name, String overview, String still) {
            this.season = season;
            this.episode = episode;
            this.name = name;
            this.overview = overview;
            this.still = still;
        }
    }

    /**
     * Every episode of one season of a show, as TMDB lists them: name, overview and still image. {@code showId} is
     * TMDB's tv id; find it with {@link #searchId}("tv", title, year). Empty list on no key or no match. Blocks; run
     * off the main thread.
     */
    List<EpisodeInfo> season(int showId, int season) throws IOException {
        List<EpisodeInfo> out = new ArrayList<>();
        if (apiKey.isEmpty() || showId <= 0) return out;
        String url = "https://api.themoviedb.org/3/tv/" + showId + "/season/" + season
                + "?api_key=" + Uri.encode(apiKey);
        try (Reply reply = open(url)) {
            JsonReader r = reply.json;
            r.beginObject();
            while (r.hasNext()) {
                if ("episodes".equals(r.nextName()) && r.peek() == JsonToken.BEGIN_ARRAY) {
                    r.beginArray();
                    while (r.hasNext()) {
                        int epNum = 0;
                        String name = "", overview = "", still = "";
                        r.beginObject();
                        while (r.hasNext()) {
                            switch (r.nextName()) {
                                case "episode_number": epNum = (int) parseLong(string(r)); break;
                                case "name": name = string(r).trim(); break;
                                case "overview": overview = string(r).trim(); break;
                                case "still_path": still = string(r).trim(); break;
                                default: r.skipValue();
                            }
                        }
                        r.endObject();
                        String stillUrl = stillUrl(still);
                        out.add(new EpisodeInfo(season, epNum, name, overview, stillUrl == null ? "" : stillUrl));
                    }
                    r.endArray();
                } else {
                    r.skipValue();
                }
            }
            r.endObject();
        }
        // A just-aired episode can have an uploaded still that TMDB has not yet promoted to the season payload's
        // still_path (iso_639_1 unset, vote_count 0): it is absent here but present on the per-episode images
        // endpoint. Fill those in with one extra call each, so a new episode shows art without waiting for TMDB to
        // promote it. Only the still-less episodes cost the extra call.
        for (int i = 0; i < out.size(); i++) {
            EpisodeInfo e = out.get(i);
            if (!e.still.isEmpty()) continue;
            String path = episodeStill(showId, e.season, e.episode);
            if (path != null) {
                out.set(i, new EpisodeInfo(e.season, e.episode, e.name, e.overview,
                        "https://image.tmdb.org/t/p/original" + path));
            }
        }
        return out;
    }

    /**
     * The first still of one episode from its own images endpoint, used only to fill a gap the season payload left:
     * {@code /tv/{id}/season/{s}/episode/{e}/images}. Returns the file_path (leading slash) or null on no key, no
     * still, or any failure. Blocks; run off the main thread.
     */
    private String episodeStill(int showId, int season, int episode) throws IOException {
        if (apiKey.isEmpty() || showId <= 0) return null;
        String url = "https://api.themoviedb.org/3/tv/" + showId + "/season/" + season + "/episode/" + episode
                + "/images?api_key=" + Uri.encode(apiKey);
        try (Reply reply = open(url)) {
            JsonReader r = reply.json;
            r.beginObject();
            while (r.hasNext()) {
                if ("stills".equals(r.nextName()) && r.peek() == JsonToken.BEGIN_ARRAY) {
                    r.beginArray();
                    String first = null;
                    while (r.hasNext()) {
                        String path = "";
                        r.beginObject();
                        while (r.hasNext()) {
                            if ("file_path".equals(r.nextName())) path = string(r).trim();
                            else r.skipValue();
                        }
                        r.endObject();
                        if (first == null && !path.isEmpty()) first = path;   // keep reading to drain the stream
                    }
                    r.endArray();
                    return first;
                } else {
                    r.skipValue();
                }
            }
            r.endObject();
        } catch (IOException e) {
            return null;   // a 404 or transient error just means no fallback still
        }
        return null;
    }

    /** Public search: TMDB's id for the first "tv"/"movie" hit of title (optionally year); 0 for none. */
    int findId(boolean series, String title, int year) throws IOException {
        if (apiKey.isEmpty()) return 0;
        return searchId(series ? "tv" : "movie", title, year);
    }

    /** TMDB's id for the first search hit, retrying without the year when the year finds nothing; 0 for none. */
    private int searchId(String kind, String title, int year) throws IOException {
        if (title == null || title.trim().isEmpty()) return 0;
        int id = year > 0 ? searchOnce(kind, title, year) : 0;
        return id > 0 ? id : searchOnce(kind, title, 0);
    }

    private int searchOnce(String kind, String title, int year) throws IOException {
        String url = "https://api.themoviedb.org/3/search/" + kind + "?api_key=" + Uri.encode(apiKey)
                + "&query=" + Uri.encode(title.trim()) + "&include_adult=false";
        if (year > 0) url += (kind.equals("tv") ? "&first_air_date_year=" : "&year=") + year;
        try (Reply reply = open(url)) {
            JsonReader r = reply.json;
            int found = 0;
            r.beginObject();
            while (r.hasNext()) {
                if ("results".equals(r.nextName()) && r.peek() == JsonToken.BEGIN_ARRAY) {
                    r.beginArray();
                    while (r.hasNext()) {
                        int id = 0;
                        r.beginObject();
                        while (r.hasNext()) {
                            if ("id".equals(r.nextName())) id = (int) parseLong(string(r)); else r.skipValue();
                        }
                        r.endObject();
                        if (found == 0 && id > 0) found = id;   // keep reading: the stream must be consumed
                    }
                    r.endArray();
                } else {
                    r.skipValue();
                }
            }
            r.endObject();
            return found;
        }
    }

    private static double parseDouble(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
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
