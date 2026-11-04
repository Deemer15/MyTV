package com.gathin.tvhome;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * One title on the merged "Media" rail, from either the Beast download library or the IPTV provider, already
 * resolved to a playable URL and a player. The rail merges both sources and, when the same title is in both,
 * keeps the download (4K-first) and hides the stream. {@link #matchKey} is how two sources are judged the same.
 */
final class MediaItem {
    enum Source { DOWNLOAD, STREAM }

    final Source source;
    final String id;        // stable card id, e.g. "dl:movie:20" or "st:1234"
    final String title;     // display title, e.g. "The Matrix"
    final int year;         // release year, 0 if unknown
    final String caption;   // the line shown when focused, e.g. "The Matrix" or "The Amazing Race · S39E06"
    final String poster;    // 2:3 poster URL; may be empty
    String backdrop = "";   // wide 16:9 backdrop URL for the home backdrop; may be empty (falls back to poster)
    final long added;       // seconds since the epoch, for ordering newest-first
    final String url;       // the URL the player opens
    final String mime;      // e.g. "video/x-matroska"
    final String player;    // package to play with (mpv for downloads, Just Player for streams); empty = ask
    final String matchKey;  // normalized title+year for dedup across sources

    final boolean isSeries;                 // true when this card is a show (episode picker), not a single movie
    final int newEpisodeCount;              // for a series, the "N new episodes" count; 0 for movies
    final List<BeastClient.Episode> episodes; // for a series, all playable episodes newest-first; empty for movies

    MediaItem(Source source, String id, String title, int year, String caption, String poster, long added,
              String url, String mime, String player) {
        this(source, id, title, year, caption, poster, added, url, mime, player, false, 0, null);
    }

    MediaItem(Source source, String id, String title, int year, String caption, String poster, long added,
              String url, String mime, String player,
              boolean isSeries, int newEpisodeCount, List<BeastClient.Episode> episodes) {
        this.source = source;
        this.id = id;
        this.title = title;
        this.year = year;
        this.caption = caption;
        this.poster = poster;
        this.added = added;
        this.url = url;
        this.mime = mime;
        this.player = player;
        this.matchKey = key(title, year);
        this.isSeries = isSeries;
        this.newEpisodeCount = newEpisodeCount;
        this.episodes = episodes == null ? Collections.emptyList() : episodes;
    }

    /**
     * A loose key for "the same movie from a different source": lower-cased title with punctuation and common
     * release-name noise (resolution, source, codec tags) stripped, plus the year when known. Good enough to
     * fold a 4K download and its IPTV stream into one card without over-merging different titles.
     */
    static String key(String title, int year) {
        String t = title == null ? "" : title.toLowerCase(Locale.ROOT);
        // Drop a trailing "(year)" the provider often appends; the year is tracked separately.
        t = t.replaceAll("\\(\\s*(19|20)\\d{2}\\s*\\)", " ");
        // Strip common release tags so "Matrix 4K UHD" == "The Matrix".
        t = t.replaceAll(
                "\\b(4k|uhd|2160p|1080p|720p|hdr10\\+?|hdr|dolby\\s*vision|dv|bluray|blu-ray|web-?dl|webrip|"
                        + "hevc|x265|x264|h ?26[45]|remux|imax|extended|unrated|directors? cut|multi)\\b",
                " ");
        t = t.replaceAll("\\b(the|a|an)\\b", " ");       // leading articles differ across providers
        t = t.replaceAll("[^a-z0-9]+", " ").trim();       // punctuation to spaces
        t = t.replaceAll("\\s+", " ");
        return year > 0 ? t + "|" + year : t;
    }
}
