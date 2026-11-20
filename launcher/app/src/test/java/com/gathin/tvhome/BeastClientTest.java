package com.gathin.tvhome;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;

public class BeastClientTest {
    @Test public void seriesUsesNewestEpisodeFileModification() {
        BeastClient.Episode older = episode(100);
        BeastClient.Episode newest = episode(300);

        assertEquals(300, BeastClient.latestSeriesAdded(200, Arrays.asList(older, newest)));
    }

    @Test public void seriesKeepsDashboardDateWhenFilesHaveNoModification() {
        BeastClient.Episode episode = episode(0);

        assertEquals(200, BeastClient.latestSeriesAdded(200, Collections.singletonList(episode)));
    }

    @Test public void seriesKeepsDashboardDateWhenNoEpisodesAreAvailable() {
        assertEquals(200, BeastClient.latestSeriesAdded(200, Collections.emptyList()));
    }

    @Test public void seriesSpotlightUsesNewestEpisodeLabel() {
        BeastClient.Episode episode = episode(300);
        episode.label = "S39E09 \u00b7 A Double-Edged Sword With an Alliance";
        MediaItem item = new MediaItem(MediaItem.Source.DOWNLOAD, "dl:series:1", "The Amazing Race",
                2001, "", "", 300, episode.url, "", "", true, 9, Collections.singletonList(episode));

        assertEquals(episode.label, item.spotlightTitle());
    }

    @Test public void movieSpotlightKeepsMovieTitle() {
        MediaItem item = new MediaItem(MediaItem.Source.DOWNLOAD, "dl:movie:1", "The Matrix",
                1999, "", "", 300, "", "", "");

        assertEquals("The Matrix", item.spotlightTitle());
    }

    @Test public void runtimeAndRatingAreBannerFormatted() {
        assertEquals("2h 33m", TmdbClient.runtimeText(153));
        assertEquals("45m", TmdbClient.runtimeText(45));
        assertEquals("2h", TmdbClient.runtimeText(120));
        assertEquals("", TmdbClient.runtimeText(0));
        assertEquals("TV-PG", TmdbClient.ratingChip("TV-PG"));
        assertEquals("", TmdbClient.ratingChip(""));
    }

    private static BeastClient.Episode episode(long modified) {
        BeastClient.Episode episode = new BeastClient.Episode();
        episode.modified = modified;
        return episode;
    }
}
