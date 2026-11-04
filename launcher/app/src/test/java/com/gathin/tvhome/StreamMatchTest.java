package com.gathin.tvhome;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class StreamMatchTest {
    private static StreamMatch.Entry entry(String title, int year) {
        IptvItem it = new IptvItem(1, 0, title, "", "mp4");
        return new StreamMatch.Entry(title, year, it);
    }

    @Test public void exactTitleAndYearMatches() {
        StreamMatch.Entry e = entry("The Matrix", 1999);
        List<StreamMatch.Entry> list = new ArrayList<>();
        list.add(entry("Other Film", 2001));
        list.add(e);
        assertSame(e.item, StreamMatch.find(list, "The Matrix", 1999));
    }

    @Test public void differentYearWithNoUniqueTitleDoesNotMatch() {
        // Two entries share the normalized title, so a year mismatch cannot fall back.
        List<StreamMatch.Entry> list = new ArrayList<>();
        list.add(entry("The Matrix", 1999));
        list.add(entry("Matrix", 2003));
        assertNull(StreamMatch.find(list, "The Matrix", 2010));
    }

    @Test public void differentYearFallsBackWhenTitleIsUnique() {
        StreamMatch.Entry e = entry("The Matrix", 1999);
        List<StreamMatch.Entry> list = new ArrayList<>();
        list.add(e);
        list.add(entry("Some Other Movie", 2001));
        // No exact year match, but exactly one entry normalizes to the same title.
        assertSame(e.item, StreamMatch.find(list, "The Matrix", 2010));
    }

    @Test public void noMatchReturnsNull() {
        List<StreamMatch.Entry> list = new ArrayList<>();
        list.add(entry("Completely Different", 2015));
        assertNull(StreamMatch.find(list, "The Matrix", 1999));
    }
}
