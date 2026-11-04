package com.gathin.tvhome;

/** One movie on the IPTV rail, as the provider's Xtream Codes API lists it. */
final class IptvItem {
    final long streamId;
    final long added;      // seconds since the epoch, when the provider added it
    final String name;     // as the provider names it, usually "Title (Year)"
    final String poster;   // 2:3 poster URL; may be empty
    final String extension; // container, e.g. "mp4" or "mkv": part of the stream URL

    IptvItem(long streamId, long added, String name, String poster, String extension) {
        this.streamId = streamId;
        this.added = added;
        this.name = name;
        this.poster = poster;
        this.extension = extension;
    }
}
