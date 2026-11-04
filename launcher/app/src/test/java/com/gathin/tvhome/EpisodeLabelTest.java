package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class EpisodeLabelTest {
    @Test public void sxxEyyWithTitleBecomesTheRicherLabel() {
        assertEquals("S39E06 \u00b7 A Fine Line Between Confidence and Stupidity",
                EpisodeLabels.format(
                        "The Amazing Race - S39E06 - A Fine Line Between Confidence and Stupidity.mkv"));
    }

    @Test public void dottedSeparatorBetweenTagAndTitleIsStripped() {
        assertEquals("S01E02 \u00b7 Pilot", EpisodeLabels.format("Show.Name.S01E02.Pilot.mkv"));
    }

    @Test public void noEpisodeTitleIsJustTheTag() {
        assertEquals("S01E02", EpisodeLabels.format("Show S01E02.mkv"));
    }

    @Test public void aPathKeepsOnlyTheFileName() {
        assertEquals("S03E10 \u00b7 Finale",
                EpisodeLabels.format("shows/Show/Season 03/Show - S03E10 - Finale.mkv"));
    }

    @Test public void aNameWithNoSxxEyyIsEmpty() {
        assertEquals("", EpisodeLabels.format("Some Movie (2021).mkv"));
        assertEquals("", EpisodeLabels.format(null));
    }
}
