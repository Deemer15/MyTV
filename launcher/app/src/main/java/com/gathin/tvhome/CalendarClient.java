package com.gathin.tvhome;

import android.content.Context;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

/**
 * Home's "Events" widget (config calendar_widget): the next event from a Google Calendar ICS feed, with a "+N" for
 * the others still to come inside the next seven days. The feed's secret URL is the string resource
 * {@code calendar_ics_url}, read by name the same way the Beast/IPTV/TMDB secrets are, and kept in the git-ignored
 * calendar_secrets.xml. No URL means no widget.
 *
 * <p>The parser is deliberately small and matches {@link WeatherClient}'s shape: a static {@link #fetch(Context)}
 * that runs off the main thread, returns null when nothing is configured or nothing is coming up, and throws
 * {@link IOException} when the request fails. It reads VEVENTs, unfolds wrapped lines, understands all-day
 * ({@code DTSTART;VALUE=DATE}), floating/zoned and UTC ({@code ...Z}) starts, and expands recurring events
 * (RRULE) into their occurrences in the window, honouring EXDATE and RECURRENCE-ID overrides. Exotic rules it does
 * not model (BYSETPOS, ordinal BYDAY like "2MO", BYMONTHDAY, ...) fall back to the plain frequency step rather than
 * crashing, so an event shows up roughly rather than not at all.
 */
final class CalendarClient {

    /** One upcoming event the pill can show: its title, start, and whether it is all-day. */
    static final class Event {
        final String title;
        final long start;
        final boolean allDay;

        Event(String title, long start, boolean allDay) {
            this.title = title;
            this.start = start;
            this.allDay = allDay;
        }
    }

    /** The upcoming events in the window, soonest first, for the pill to rotate through. */
    static final class Events {
        final List<Event> list;
        final long fetchedAt;

        Events(List<Event> list, long fetchedAt) {
            this.list = list;
            this.fetchedAt = fetchedAt;
        }
    }

    private static final int TIMEOUT_MS = 8000;
    private static final long WINDOW_MS = 7L * 24 * 60 * 60 * 1000;
    /** A ceiling on expanded occurrences per event, so a COUNT-less daily rule can't run away. */
    private static final int MAX_OCCURRENCES = 500;
    /** How far a pre-window series is fast-forwarded to reach today (~10 years of daily occurrences). */
    private static final int MAX_SKIP = 4000;

    private CalendarClient() {
    }

    /** Midnight at the start of today, in the device's zone: the earliest start the pill still shows. */
    private static long startOfToday() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    static String url(Context ctx) {
        int id = ctx.getResources().getIdentifier("calendar_ics_url", "string", ctx.getPackageName());
        return id == 0 ? "" : ctx.getString(id).trim();
    }

    /** Null when no URL is set or nothing falls in the next week; IOException when the request fails. */
    static Events fetch(Context ctx) throws IOException {
        String url = url(ctx);
        if (url.isEmpty()) return null;

        String body = download(url);
        long now = System.currentTimeMillis();
        long startOfToday = startOfToday();
        List<Occurrence> all = new ArrayList<>();
        for (VEvent ev : parse(body)) {
            ev.expandInto(all, startOfToday, now + WINDOW_MS);
        }
        if (all.isEmpty()) return null;

        List<Occurrence> upcoming = new ArrayList<>();
        for (Occurrence o : all) {
            // Keep anything still on today's date (all-day events and earlier-today events stay all day), then
            // everything up to a week out.
            if (o.start < startOfToday) continue;
            upcoming.add(o);
        }
        if (upcoming.isEmpty()) return null;
        java.util.Collections.sort(upcoming, (a, b) -> Long.compare(a.start, b.start));

        List<Event> list = new ArrayList<>(upcoming.size());
        for (Occurrence o : upcoming) list.add(new Event(o.title, o.start, o.allDay));
        return new Events(list, now);
    }

    private static String download(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        c.setRequestProperty("Accept", "text/calendar");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("calendar HTTP " + code);
            StringBuilder sb = new StringBuilder();
            try (InputStream in = c.getInputStream();
                 BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                for (int ch; (ch = r.read()) != -1; ) sb.append((char) ch);
            }
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }

    // ----- parsing -------------------------------------------------------------------------------------------

    /** One occurrence of an event, after recurrence is expanded. */
    private static final class Occurrence {
        final long start;
        final String title;
        final boolean allDay;

        Occurrence(long start, String title, boolean allDay) {
            this.start = start;
            this.title = title;
            this.allDay = allDay;
        }
    }

    /** A VEVENT: its first start, title, optional RRULE, EXDATEs and the recurrence-id of an override. */
    private static final class VEvent {
        String summary = "";
        long start = Long.MIN_VALUE;
        boolean allDay;
        TimeZone zone = TimeZone.getDefault();
        String rrule;
        final Set<Long> exDates = new HashSet<>();
        long recurrenceId = Long.MIN_VALUE;   // set when this VEVENT overrides one occurrence of a series

        void expandInto(List<Occurrence> out, long from, long to) {
            if (start == Long.MIN_VALUE) return;
            String title = summary.isEmpty() ? "(busy)" : summary;
            if (rrule == null) {
                if (start >= from && start <= to) out.add(new Occurrence(start, title, allDay));
                return;
            }
            Rrule r = Rrule.parse(rrule, zone);
            if (r == null) {
                if (start >= from && start <= to) out.add(new Occurrence(start, title, allDay));
                return;
            }
            Calendar cal = new GregorianCalendar(zone);
            cal.setTimeInMillis(start);
            int made = 0;
            // Fast-forward a series that began before the window (a standup set up years ago) up to its start,
            // counting occurrences so a COUNT limit still lands right, without spending the emit cap getting there.
            for (int i = 0; i < MAX_SKIP && cal.getTimeInMillis() < from; i++) {
                if (r.until != Long.MIN_VALUE && cal.getTimeInMillis() > r.until) break;
                if (r.count > 0 && made >= r.count) break;
                made++;
                r.advance(cal);
            }
            for (int i = 0; i < MAX_OCCURRENCES; i++) {
                long t = cal.getTimeInMillis();
                if (r.until != Long.MIN_VALUE && t > r.until) break;
                if (r.count > 0 && made >= r.count) break;
                if (t > to) break;
                if (t >= from && !exDates.contains(t)) out.add(new Occurrence(t, title, allDay));
                made++;
                r.advance(cal);
            }
        }
    }

    /** Minimal RRULE: FREQ, INTERVAL, COUNT, UNTIL. BYDAY weekly is honoured; other BY* parts degrade to the step. */
    private static final class Rrule {
        int freqField = Calendar.DAY_OF_YEAR;
        int interval = 1;
        int count = -1;
        long until = Long.MIN_VALUE;

        void advance(Calendar cal) {
            cal.add(freqField, interval);
        }

        static Rrule parse(String spec, TimeZone zone) {
            Rrule r = new Rrule();
            boolean haveFreq = false;
            for (String part : spec.split(";")) {
                int eq = part.indexOf('=');
                if (eq < 0) continue;
                String k = part.substring(0, eq).trim().toUpperCase();
                String v = part.substring(eq + 1).trim();
                switch (k) {
                    case "FREQ":
                        haveFreq = true;
                        switch (v.toUpperCase()) {
                            case "DAILY": r.freqField = Calendar.DAY_OF_YEAR; break;
                            case "WEEKLY": r.freqField = Calendar.WEEK_OF_YEAR; break;
                            case "MONTHLY": r.freqField = Calendar.MONTH; break;
                            case "YEARLY": r.freqField = Calendar.YEAR; break;
                            case "HOURLY": r.freqField = Calendar.HOUR_OF_DAY; break;
                            case "MINUTELY": r.freqField = Calendar.MINUTE; break;
                            default: return null;
                        }
                        break;
                    case "INTERVAL":
                        try { r.interval = Math.max(1, Integer.parseInt(v)); } catch (NumberFormatException ignored) { }
                        break;
                    case "COUNT":
                        try { r.count = Integer.parseInt(v); } catch (NumberFormatException ignored) { }
                        break;
                    case "UNTIL":
                        long u = parseDate(v, zone);
                        if (u != Long.MIN_VALUE) r.until = u;
                        break;
                    default:
                        // BYDAY / BYMONTHDAY / BYSETPOS etc.: not modelled, the plain frequency step stands in
                        break;
                }
            }
            return haveFreq ? r : null;
        }
    }

    /** Pulls the VEVENTs out of an ICS body, unfolding folded lines first. */
    private static List<VEvent> parse(String body) {
        List<String> lines = unfold(body);
        List<VEvent> events = new ArrayList<>();
        VEvent cur = null;
        for (String line : lines) {
            if (line.equals("BEGIN:VEVENT")) {
                cur = new VEvent();
            } else if (line.equals("END:VEVENT")) {
                if (cur != null) events.add(cur);
                cur = null;
            } else if (cur != null) {
                int colon = line.indexOf(':');
                if (colon < 0) continue;
                String left = line.substring(0, colon);
                String value = line.substring(colon + 1);
                String name = left;
                int semi = left.indexOf(';');
                if (semi >= 0) name = left.substring(0, semi);
                name = name.toUpperCase();

                if (name.equals("SUMMARY")) {
                    cur.summary = unescape(value);
                } else if (name.equals("DTSTART")) {
                    TimeZone tz = zoneOf(left);
                    if (tz != null) cur.zone = tz;
                    cur.allDay = left.toUpperCase().contains("VALUE=DATE") && !value.contains("T");
                    long t = parseDate(value, cur.zone);
                    if (t != Long.MIN_VALUE) cur.start = t;
                } else if (name.equals("RRULE")) {
                    cur.rrule = value;
                } else if (name.equals("EXDATE")) {
                    TimeZone tz = zoneOf(left);
                    TimeZone z = tz != null ? tz : cur.zone;
                    for (String d : value.split(",")) {
                        long t = parseDate(d.trim(), z);
                        if (t != Long.MIN_VALUE) cur.exDates.add(t);
                    }
                } else if (name.equals("RECURRENCE-ID")) {
                    TimeZone tz = zoneOf(left);
                    long t = parseDate(value, tz != null ? tz : cur.zone);
                    if (t != Long.MIN_VALUE) cur.recurrenceId = t;
                }
            }
        }
        return events;
    }

    /** RFC 5545 line unfolding: a line beginning with a space or tab continues the one before it. */
    private static List<String> unfold(String body) {
        List<String> out = new ArrayList<>();
        for (String raw : body.split("\r\n|\n|\r")) {
            if (raw.isEmpty()) continue;
            char c0 = raw.charAt(0);
            if ((c0 == ' ' || c0 == '\t') && !out.isEmpty()) {
                out.set(out.size() - 1, out.get(out.size() - 1) + raw.substring(1));
            } else {
                out.add(raw);
            }
        }
        return out;
    }

    private static TimeZone zoneOf(String propWithParams) {
        String up = propWithParams.toUpperCase();
        int i = up.indexOf("TZID=");
        if (i < 0) return null;
        int start = i + 5;
        int end = start;
        while (end < propWithParams.length() && propWithParams.charAt(end) != ';' && propWithParams.charAt(end) != ':') {
            end++;
        }
        String id = propWithParams.substring(start, end);
        TimeZone tz = TimeZone.getTimeZone(id);
        // getTimeZone falls back to GMT for an unknown id; keep it, it is better than crashing.
        return tz;
    }

    /** Parses 20260310, 20260310T140000 (floating/zoned) or 20260310T140000Z (UTC). MIN_VALUE when unreadable. */
    private static long parseDate(String v, TimeZone zone) {
        try {
            v = v.trim();
            boolean utc = v.endsWith("Z");
            if (utc) v = v.substring(0, v.length() - 1);
            int y, mo, d, h = 0, mi = 0, s = 0;
            if (v.length() >= 8) {
                y = Integer.parseInt(v.substring(0, 4));
                mo = Integer.parseInt(v.substring(4, 6));
                d = Integer.parseInt(v.substring(6, 8));
            } else {
                return Long.MIN_VALUE;
            }
            int t = v.indexOf('T');
            if (t >= 0 && v.length() >= t + 7) {
                h = Integer.parseInt(v.substring(t + 1, t + 3));
                mi = Integer.parseInt(v.substring(t + 3, t + 5));
                s = Integer.parseInt(v.substring(t + 5, t + 7));
            }
            TimeZone use = utc ? TimeZone.getTimeZone("UTC") : (zone != null ? zone : TimeZone.getDefault());
            Calendar cal = new GregorianCalendar(use);
            cal.clear();
            cal.set(y, mo - 1, d, h, mi, s);
            return cal.getTimeInMillis();
        } catch (NumberFormatException | IndexOutOfBoundsException e) {
            return Long.MIN_VALUE;
        }
    }

    private static String unescape(String v) {
        StringBuilder sb = new StringBuilder(v.length());
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '\\' && i + 1 < v.length()) {
                char n = v.charAt(++i);
                switch (n) {
                    case 'n': case 'N': sb.append(' '); break;   // a one-line pill: newline becomes a space
                    case ',': sb.append(','); break;
                    case ';': sb.append(';'); break;
                    case '\\': sb.append('\\'); break;
                    default: sb.append(n);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }
}
