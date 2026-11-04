package com.gathin.tvhome;

/** One TMDB "trending this week" movie: enough to match it to an IPTV stream and draw a card. */
final class TmdbMovie {
    final int id;              // TMDB id
    final String title;        // display/match title
    final int year;            // release year, 0 if unknown
    final String posterPath;   // TMDB poster_path (e.g. "/abc.jpg"); may be empty
    final String backdropPath; // TMDB backdrop_path (wide 16:9 art); may be empty

    TmdbMovie(int id, String title, int year, String posterPath, String backdropPath) {
        this.id = id;
        this.title = title;
        this.year = year;
        this.posterPath = posterPath == null ? "" : posterPath;
        this.backdropPath = backdropPath == null ? "" : backdropPath;
    }
}
