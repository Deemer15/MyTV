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
 * Reads the Mediarium ("Beast") server for the Beast rail: the dashboard's "recently added" list, and each title's
 * file path, turned into a plain-HTTP media URL the player can open. Every call blocks; run it off the main thread.
 *
 * Playback does not go through Beast at all. Beast's /api/files/stream needs an X-API-Key header the player cannot
 * send, so instead the actual files are served over plain HTTP by a static file server (beast_media_base) rooted at
 * the same library folder Beast reports as beast_library_root. A title's absolute server path is rewritten from
 * that root onto the media base, which needs no auth. The API key is only ever used (as a header) to read metadata,
 * never put in a URL.
 */
final class BeastClient {
    static final int CONNECT_TIMEOUT_MS = 6000;
    static final int READ_TIMEOUT_MS = 20000;
    static final String USER_AGENT = "CleanYourTV-Launcher/1.0";

    private final String base;          // e.g. http://192.168.1.15:8264  (no trailing slash)
    private final String apiKey;        // admin/read key, sent as X-API-Key header only
    private final String libraryRoot;   // the server path prefix, e.g. /mnt/e/Media/Torrents  (no trailing slash)
    private final String mediaBase;     // the HTTP file server, e.g. http://192.168.1.10:8200  (no trailing slash)

    BeastClient(String base, String apiKey, String libraryRoot, String mediaBase) {
        this.base = strip(base);
        this.apiKey = apiKey;
        this.libraryRoot = strip(libraryRoot);
        this.mediaBase = strip(mediaBase);
    }

    private static String strip(String s) {
        String t = s == null ? "" : s.trim();
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        return t;
    }

    /**
     * The newest {@code limit} library items (movies and episodes) as {@link MediaItem}s, source DOWNLOAD, newest
     * first, each resolved to a playable HTTP URL and the given {@code player}. Items not on disk, or whose file
     * path cannot be resolved or mapped onto the media base, are left out.
     */
    List<MediaItem> recent(int limit, String player) throws IOException {
        List<Dash> dash = dashboard();
        List<MediaItem> out = new ArrayList<>();
        for (Dash d : dash) {
            if (out.size() >= limit) break;
            String serverPath = null;
            String caption = d.title;
            int year = (int) d.year;
            if ("movie".equals(d.kind)) {
                if (!"downloaded".equals(d.state)) continue;
                serverPath = movieFilePath(d.id);
            } else if ("series".equals(d.kind)) {
                if (!"downloaded".equals(d.state) && !"partial".equals(d.state)) continue;
                long sid = d.seriesId > 0 ? d.seriesId : d.id;
                List<Episode> eps = episodes(sid, player);
                if (eps.isEmpty()) continue;
                Episode newest = eps.get(0);
                caption = d.title + (newest.label.isEmpty() ? "" : " · " + sxxEyy(newest.label));
                out.add(new MediaItem(MediaItem.Source.DOWNLOAD, "dl:" + d.kind + ":" + d.id, d.title, year,
                        caption, d.posterUrl, d.at, newest.url, newest.mime, player,
                        true, eps.size(), eps));
                continue;
            } else {
                continue; // albums/books are not played by this rail
            }
            if (serverPath == null) continue;
            String url = mediaUrl(serverPath);
            if (url == null) continue;
            out.add(new MediaItem(MediaItem.Source.DOWNLOAD, "dl:" + d.kind + ":" + d.id, d.title, year,
                    caption, d.posterUrl, d.at, url, mimeFor(serverPath), player));
        }
        return out;
    }

    /** The full downloaded-movie library as DOWNLOAD {@link MediaItem}s. GET /api/movies, status=='downloaded'. */
    List<MediaItem> allMovies(String player) throws IOException {
        List<MediaItem> out = new ArrayList<>();
        try (Reply reply = open("/api/movies")) {
            JsonReader r = reply.json;
            r.beginArray();
            while (r.hasNext()) {
                long id = -1, year = 0;
                String title = "", poster = "", status = "", filePath = "";
                r.beginObject();
                while (r.hasNext()) {
                    switch (r.nextName()) {
                        case "id": id = parseLong(string(r)); break;
                        case "title": title = string(r).trim(); break;
                        case "year": year = parseLong(string(r)); break;
                        case "posterUrl": poster = string(r).trim(); break;
                        case "status": status = string(r); break;
                        case "filePath": filePath = string(r).trim(); break;
                        default: r.skipValue();
                    }
                }
                r.endObject();
                if (id < 0 || title.isEmpty() || !"downloaded".equals(status) || filePath.isEmpty()) continue;
                String url = mediaUrl(filePath);
                if (url == null) continue;
                out.add(new MediaItem(MediaItem.Source.DOWNLOAD, "dl:movie:" + id, title, (int) year,
                        title, poster, 0, url, mimeFor(filePath), player));
            }
            r.endArray();
        }
        return out;
    }

    /** The full series library as DOWNLOAD {@link MediaItem}s, one per show, episodes resolved eagerly. GET /api/series. */
    List<MediaItem> allSeries(String player) throws IOException {
        List<long[]> ids = new ArrayList<>();      // [id]
        List<String[]> meta = new ArrayList<>();   // [title, poster]
        List<int[]> counts = new ArrayList<>();    // [year, downloadedCount]
        try (Reply reply = open("/api/series")) {
            JsonReader r = reply.json;
            r.beginArray();
            while (r.hasNext()) {
                long id = -1;
                int year = 0, downloaded = 0;
                String title = "", poster = "";
                r.beginObject();
                while (r.hasNext()) {
                    switch (r.nextName()) {
                        case "id": id = parseLong(string(r)); break;
                        case "title": title = string(r).trim(); break;
                        case "year": year = (int) parseLong(string(r)); break;
                        case "posterUrl": poster = string(r).trim(); break;
                        case "downloadedCount": downloaded = (int) parseLong(string(r)); break;
                        default: r.skipValue();
                    }
                }
                r.endObject();
                if (id < 0 || title.isEmpty()) continue;
                ids.add(new long[]{id});
                meta.add(new String[]{title, poster});
                counts.add(new int[]{year, downloaded});
            }
            r.endArray();
        }
        List<MediaItem> out = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            long id = ids.get(i)[0];
            String title = meta.get(i)[0], poster = meta.get(i)[1];
            int year = counts.get(i)[0], downloaded = counts.get(i)[1];
            List<Episode> eps = episodes(id, player);     // eager: Tier 2 renders counts + picker with no extra round trip
            if (eps.isEmpty()) continue;
            Episode newest = eps.get(0);
            int newCount = downloaded > 0 ? downloaded : eps.size();
            out.add(new MediaItem(MediaItem.Source.DOWNLOAD, "dl:series:" + id, title, year,
                    title, poster, 0, newest.url, newest.mime, player, true, newCount, eps));
        }
        return out;
    }

    /** Rewrites an absolute server file path (under libraryRoot) onto the HTTP media base, URL-encoding segments. */
    String mediaUrl(String serverPath) {
        if (serverPath == null || libraryRoot.isEmpty() || mediaBase.isEmpty()) return null;
        String p = serverPath.replace('\\', '/');
        String root = libraryRoot.replace('\\', '/');
        if (!p.startsWith(root)) return null;
        String rel = p.substring(root.length());
        while (rel.startsWith("/")) rel = rel.substring(1);
        StringBuilder sb = new StringBuilder(mediaBase);
        for (String seg : rel.split("/")) {
            if (seg.isEmpty()) continue;
            sb.append('/').append(Uri.encode(seg));
        }
        return sb.toString();
    }

    private static String mimeFor(String path) {
        String p = path.toLowerCase(java.util.Locale.ROOT);
        if (p.endsWith(".mkv")) return "video/x-matroska";
        if (p.endsWith(".mp4") || p.endsWith(".m4v")) return "video/mp4";
        if (p.endsWith(".webm")) return "video/webm";
        if (p.endsWith(".ts") || p.endsWith(".m2ts")) return "video/mp2t";
        if (p.endsWith(".avi")) return "video/x-msvideo";
        if (p.endsWith(".mov")) return "video/quicktime";
        if (p.endsWith(".wmv")) return "video/x-ms-wmv";
        return "video/*";
    }

    // ---- dashboard ("recently added") ------------------------------------------------------------

    private static final class Dash {
        String kind = "", title = "", state = "", posterUrl = "";
        long id = -1, seriesId = -1, at, year;
    }

    private List<Dash> dashboard() throws IOException {
        List<Dash> list = new ArrayList<>();
        try (Reply reply = open("/api/dashboard")) {
            JsonReader r = reply.json;
            r.beginObject();
            while (r.hasNext()) {
                if ("recentlyAdded".equals(r.nextName()) && r.peek() == JsonToken.BEGIN_ARRAY) {
                    r.beginArray();
                    while (r.hasNext()) {
                        Dash d = new Dash();
                        r.beginObject();
                        while (r.hasNext()) {
                            switch (r.nextName()) {
                                case "kind": d.kind = string(r); break;
                                case "id": d.id = parseLong(string(r)); break;
                                case "seriesId": d.seriesId = parseLong(string(r)); break;
                                case "title": d.title = string(r).trim(); break;
                                case "state": d.state = string(r); break;
                                case "posterUrl": d.posterUrl = string(r).trim(); break;
                                case "year": d.year = parseLong(string(r)); break;
                                case "at": d.at = epochSeconds(string(r)); break;
                                default: r.skipValue();
                            }
                        }
                        r.endObject();
                        if (d.id >= 0 && !d.title.isEmpty()) list.add(d);
                    }
                    r.endArray();
                } else {
                    r.skipValue();
                }
            }
            r.endObject();
        }
        return list;
    }

    // ---- per-title file paths --------------------------------------------------------------------

    /** The absolute server filePath of a movie, or null if it has none. GET /api/movies/{id}. */
    private String movieFilePath(long id) throws IOException {
        try (Reply reply = open("/api/movies/" + id)) {
            JsonReader r = reply.json;
            String filePath = null, status = "";
            r.beginObject();
            while (r.hasNext()) {
                switch (r.nextName()) {
                    case "filePath": filePath = string(r); break;
                    case "status": status = string(r); break;
                    default: r.skipValue();
                }
            }
            r.endObject();
            if (filePath == null || filePath.trim().isEmpty()) return null;
            if (!status.isEmpty() && !"downloaded".equals(status)) return null;
            return filePath.trim();
        }
    }

    /** One playable episode of a series: its absolute server path, a picker label, and the resolved media URL. */
    static final class Episode {
        String serverPath;   // absolute path under libraryRoot
        String label;        // picker label, e.g. "S39E06 · A Fine Line..." (or "SxxEyy", or "")
        long episodeId;      // the server's episodeId; larger is newer
        String url;          // media-base URL the player opens; null until mapped
        String mime;         // e.g. "video/x-matroska"
    }

    /**
     * All playable episodes of a series, newest first (greatest episodeId first). GET /api/series/{id}/files returns a
     * {@code folder} (relative to the library root, e.g. "shows") and each video's {@code path} under it. Only
     * {@code main==true && kind=='video'} files are kept; each is resolved to a media-base URL and episodes that
     * cannot be mapped are dropped. {@code player} is carried on each episode's MediaItem later.
     */
    List<Episode> episodes(long seriesId, String player) throws IOException {
        List<Episode> eps = new ArrayList<>();
        try (Reply reply = open("/api/series/" + seriesId + "/files")) {
            JsonReader r = reply.json;
            String folder = "";
            r.beginObject();
            while (r.hasNext()) {
                String key = r.nextName();
                if ("folder".equals(key)) {
                    folder = string(r);
                } else if ("files".equals(key) && r.peek() == JsonToken.BEGIN_ARRAY) {
                    r.beginArray();
                    while (r.hasNext()) {
                        String path = "", kind = "";
                        boolean main = false;
                        long ep = Long.MIN_VALUE;
                        r.beginObject();
                        while (r.hasNext()) {
                            switch (r.nextName()) {
                                case "path": path = string(r); break;
                                case "kind": kind = string(r); break;
                                case "main": main = bool(r); break;
                                case "episodeId": ep = parseLong(string(r)); break;
                                default: r.skipValue();
                            }
                        }
                        r.endObject();
                        if (main && "video".equals(kind) && !path.isEmpty()) {
                            Episode e = new Episode();
                            String rel = (folder == null || folder.isEmpty() ? "" : folder + "/") + path;
                            e.serverPath = libraryRoot + "/" + rel;
                            e.label = EpisodeLabels.format(path);
                            e.episodeId = ep;
                            e.mime = mimeFor(path);
                            eps.add(e);
                        }
                    }
                    r.endArray();
                } else {
                    r.skipValue();
                }
            }
            r.endObject();
        }
        // Newest first by episodeId.
        eps.sort((a, b) -> Long.compare(b.episodeId, a.episodeId));
        List<Episode> out = new ArrayList<>(eps.size());
        for (Episode e : eps) {
            e.url = mediaUrl(e.serverPath);
            if (e.url != null) out.add(e);
        }
        return out;
    }

    /** The SxxEyy prefix of a picker label (everything before the " · "), for the rail header caption. */
    private static String sxxEyy(String label) {
        if (label == null) return "";
        int dot = label.indexOf(" · ");
        return dot >= 0 ? label.substring(0, dot) : label;
    }

    // ---- http ------------------------------------------------------------------------------------

    private Reply open(String path) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        c.setRequestProperty("User-Agent", USER_AGENT);
        c.setRequestProperty("Accept", "application/json");
        if (!apiKey.isEmpty()) c.setRequestProperty("X-API-Key", apiKey);
        int code = c.getResponseCode();
        if (code != 200) {
            c.disconnect();
            throw new IOException("HTTP " + code + " for " + path);
        }
        InputStream in = new BufferedInputStream(c.getInputStream(), 32 * 1024);
        return new Reply(c, new JsonReader(new InputStreamReader(in, StandardCharsets.UTF_8)));
    }

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

    private static boolean bool(JsonReader r) throws IOException {
        JsonToken t = r.peek();
        if (t == JsonToken.BOOLEAN) return r.nextBoolean();
        if (t == JsonToken.STRING) return "true".equalsIgnoreCase(r.nextString());
        r.skipValue();
        return false;
    }

    /** Accepts either an RFC3339 timestamp or a plain epoch-seconds number; returns epoch seconds (0 if unknown). */
    private static long epochSeconds(String s) {
        String v = s.trim();
        if (v.isEmpty()) return 0;
        if (v.matches("\\d+")) {
            long n = parseLong(v);
            return n > 100_000_000_000L ? n / 1000 : n; // tolerate milliseconds
        }
        // RFC3339 like 2026-10-23T21:03:39Z (optionally with fraction/offset).
        try {
            String iso = v.replaceFirst("\\.\\d+", "");
            if (iso.endsWith("Z")) iso = iso.substring(0, iso.length() - 1) + "+0000";
            else iso = iso.replaceFirst("([+-]\\d{2}):(\\d{2})$", "$1$2");
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ssZ", java.util.Locale.ROOT);
            return f.parse(iso).getTime() / 1000;
        } catch (Exception e) {
            return 0;
        }
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
