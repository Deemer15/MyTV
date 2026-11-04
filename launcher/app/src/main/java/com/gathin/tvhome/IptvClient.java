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
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Reads an IPTV provider's Xtream Codes API (the login TiviMate uses) for the IPTV rail. Every call blocks; run it
 * off the main thread. The login is only ever put in request URLs, never logged or cached.
 */
final class IptvClient {
    static final int CONNECT_TIMEOUT_MS = 6000;
    static final int READ_TIMEOUT_MS = 20000;
    /** Some providers turn away requests with no User-Agent; Android's default one is accepted. */
    static final String USER_AGENT = "CleanYourTV-Launcher/1.0";

    private final String base, username, password;

    IptvClient(String base, String username, String password) {
        this.base = base;
        this.username = username;
        this.password = password;
    }

    /** The URL VLC plays for one movie. */
    String streamUrl(IptvItem it) {
        String ext = it.extension.isEmpty() ? "mp4" : it.extension;
        return base + "/movie/" + Uri.encode(username) + "/" + Uri.encode(password) + "/" + it.streamId + "." + ext;
    }

    /**
     * The newest {@code limit} movies, newest first, leaving out the categories named in {@code skipCategories}.
     * The provider sends its whole movie list in one reply; it is read as a stream and only the newest are kept.
     */
    List<IptvItem> newest(int limit, Collection<String> skipCategories) throws IOException {
        Set<String> skipNames = new HashSet<>();
        for (String s : skipCategories) skipNames.add(s.trim().toLowerCase(Locale.ROOT));
        Set<String> skipIds = new HashSet<>();
        if (!skipNames.isEmpty()) {
            try (Reply reply = open("get_vod_categories")) {
                JsonReader r = reply.json;
                r.beginArray();
                while (r.hasNext()) {
                    String id = "", name = "";
                    r.beginObject();
                    while (r.hasNext()) {
                        String k = r.nextName();
                        if ("category_id".equals(k)) id = string(r);
                        else if ("category_name".equals(k)) name = string(r);
                        else r.skipValue();
                    }
                    r.endObject();
                    if (skipNames.contains(name.trim().toLowerCase(Locale.ROOT))) skipIds.add(id);
                }
                r.endArray();
            }
        }

        // Oldest of the kept ones on top, so each newer movie replaces it.
        PriorityQueue<IptvItem> keep = new PriorityQueue<>(limit + 1, (a, b) -> Long.compare(a.added, b.added));
        try (Reply reply = open("get_vod_streams")) {
            JsonReader r = reply.json;
            r.beginArray();
            while (r.hasNext()) {
                long id = -1, added = 0;
                String name = "", icon = "", ext = "", category = "";
                r.beginObject();
                while (r.hasNext()) {
                    switch (r.nextName()) {
                        case "stream_id": id = parseLong(string(r)); break;
                        case "added": added = parseLong(string(r)); break;
                        case "name": name = string(r); break;
                        case "stream_icon": icon = string(r); break;
                        case "container_extension": ext = string(r); break;
                        case "category_id": category = string(r); break;
                        default: r.skipValue();
                    }
                }
                r.endObject();
                if (id < 0 || name.isEmpty() || skipIds.contains(category)) continue;
                keep.add(new IptvItem(id, added, name.trim(), icon.trim(), safeExtension(ext)));
                if (keep.size() > limit) keep.poll();
            }
            r.endArray();
        }
        List<IptvItem> out = new ArrayList<>(keep);
        out.sort((a, b) -> Long.compare(b.added, a.added));
        return out;
    }

    /**
     * The newest movies as {@link MediaItem}s, source STREAM, each resolved to a stream URL and the given
     * {@code player}. The title and year are pulled from the provider's name so the merged rail can fold a
     * stream into its matching 4K download.
     */
    List<MediaItem> newestMedia(int limit, Collection<String> skipCategories, String player) throws IOException {
        List<MediaItem> out = new ArrayList<>();
        for (IptvItem it : newest(limit, skipCategories)) {
            int year = yearIn(it.name);
            String title = titleOf(it.name, year);
            String mime = "mkv".equals(it.extension) ? "video/x-matroska" : "video/*";
            out.add(new MediaItem(MediaItem.Source.STREAM, "st:" + it.streamId, title, year,
                    it.name, posterUrl(it.poster), it.added, streamUrl(it), mime, player));
        }
        return out;
    }

    /**
     * The whole VOD list as lightweight {@link StreamMatch.Entry} candidates (title, year, item) for matching TMDB
     * trending titles to streams. One get_vod_streams call, no per-stream get_vod_info. Streams whose name has no
     * usable title are skipped.
     */
    List<StreamMatch.Entry> vodEntries() throws IOException {
        List<StreamMatch.Entry> out = new ArrayList<>();
        try (Reply reply = open("get_vod_streams")) {
            JsonReader r = reply.json;
            r.beginArray();
            while (r.hasNext()) {
                long id = -1, added = 0;
                String name = "", icon = "", ext = "";
                r.beginObject();
                while (r.hasNext()) {
                    switch (r.nextName()) {
                        case "stream_id": id = parseLong(string(r)); break;
                        case "added": added = parseLong(string(r)); break;
                        case "name": name = string(r); break;
                        case "stream_icon": icon = string(r); break;
                        case "container_extension": ext = string(r); break;
                        default: r.skipValue();
                    }
                }
                r.endObject();
                if (id < 0 || name.trim().isEmpty()) continue;
                int year = yearIn(name);
                String title = titleOf(name, year);
                IptvItem item = new IptvItem(id, added, name.trim(), icon.trim(), safeExtension(ext));
                out.add(new StreamMatch.Entry(title, year, item));
            }
            r.endArray();
        }
        return out;
    }

    /** The 4-digit year inside a provider name like "The Matrix (1999)" or "Matrix 2160p 1999", else 0. */
    private static int yearIn(String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?<![0-9])(19|20)\\d{2}(?![0-9])").matcher(name);
        int y = 0;
        while (m.find()) y = Integer.parseInt(m.group()); // last match: year usually trails the title
        return (y >= 1900 && y <= 2100) ? y : 0;
    }

    /** The title part of a provider name, with a trailing "(year)" removed when present. */
    private static String titleOf(String name, int year) {
        String t = name.trim();
        if (year > 0) t = t.replaceFirst("\\s*\\(?\\s*" + year + "\\s*\\)?\\s*$", "").trim();
        return t.isEmpty() ? name.trim() : t;
    }

    /** A smaller copy of a TMDB poster (the provider links the large ones); other hosts as they are. */
    static String posterUrl(String url) {
        if (url == null || url.isEmpty()) return null;
        if (url.startsWith("http://image.tmdb.org/")) url = "https://" + url.substring("http://".length());
        if (url.startsWith("https://image.tmdb.org/t/p/")) {
            return url.replaceFirst("^https://image\\.tmdb\\.org/t/p/[^/]+/", "https://image.tmdb.org/t/p/w500/");
        }
        return url;
    }

    private Reply open(String action) throws IOException {
        String url = base + "/player_api.php?username=" + Uri.encode(username) + "&password=" + Uri.encode(password)
                + "&action=" + action;
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        c.setRequestProperty("User-Agent", USER_AGENT);
        c.setRequestProperty("Accept", "application/json");
        int code = c.getResponseCode();
        if (code != 200) {
            c.disconnect();
            throw new IOException("HTTP " + code + " for " + action); // the URL holds the login: not in the message
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

    /** A value as text, whether the provider sent it as a string, a number or null. */
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

    /** Only a plain container name goes into the stream URL. */
    private static String safeExtension(String ext) {
        String e = ext.trim().toLowerCase(Locale.ROOT);
        return e.matches("[a-z0-9]{1,5}") ? e : "";
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
