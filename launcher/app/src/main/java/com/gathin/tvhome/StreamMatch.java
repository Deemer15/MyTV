package com.gathin.tvhome;

import java.util.List;

/**
 * Matches a TMDB (title, year) to an IPTV stream by normalized title, with no per-stream lookups. Pure and
 * Android-free so the JVM unit tests can cover it. Matching uses the same {@link MediaItem#key(String, int)}
 * normalization the merged rail already uses for dedup.
 *
 * <p>First an exact title+year key match; failing that, a title-only fallback when exactly one IPTV entry
 * normalizes equal to the TMDB title ignoring year. A miss returns null, so the Streaming section degrades
 * gracefully: it shows only the TMDB titles that resolve to a stream.
 */
final class StreamMatch {
    private StreamMatch() {}

    /** One IPTV candidate for matching: the stream's title, its year (0 if unknown) and the underlying item. */
    static final class Entry {
        final String title;
        final int year;
        final IptvItem item;

        Entry(String title, int year, IptvItem item) {
            this.title = title;
            this.year = year;
            this.item = item;
        }
    }

    /** The IPTV stream matching the given TMDB (title, year), or null when none matches. */
    static IptvItem find(List<Entry> entries, String tmdbTitle, int tmdbYear) {
        if (entries == null || tmdbTitle == null) return null;
        String wantKey = MediaItem.key(tmdbTitle, tmdbYear);
        String wantTitleOnly = MediaItem.key(tmdbTitle, 0);

        // Exact title+year.
        for (Entry e : entries) {
            if (MediaItem.key(e.title, e.year).equals(wantKey)) return e.item;
        }
        // Title-only fallback, but only when exactly one entry normalizes equal ignoring year.
        IptvItem only = null;
        int hits = 0;
        for (Entry e : entries) {
            if (MediaItem.key(e.title, 0).equals(wantTitleOnly)) {
                hits++;
                only = e.item;
            }
        }
        return hits == 1 ? only : null;
    }
}
