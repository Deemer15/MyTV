package com.gathin.tvhome;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns an episode file name into a picker label. Pure and Android-free so the JVM unit tests can cover it.
 *
 * <p>With an SxxEyy token the label is "SxxEyy · &lt;title&gt;", where the title is the human part that follows the
 * token (extension and leading separators stripped); when nothing follows, it is just "SxxEyy". A name with no
 * SxxEyy token yields the empty string.
 */
final class EpisodeLabels {
    private EpisodeLabels() {}

    private static final Pattern SXXEYY = Pattern.compile("[Ss](\\d{1,2})[ ._-]?[Ee](\\d{1,3})");

    /** The picker label for an episode file name, e.g. "S39E06 · A Fine Line..."; "" when there is no SxxEyy. */
    static String format(String filename) {
        if (filename == null) return "";
        String name = filename.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        // Drop a trailing file extension.
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);

        Matcher m = SXXEYY.matcher(name);
        if (!m.find()) return "";
        String tag = String.format(Locale.ROOT, "S%02dE%02d",
                parseInt(m.group(1)), parseInt(m.group(2)));
        String title = name.substring(m.end());
        // Strip leading separators (spaces, dots, dashes, underscores) the release name puts between fields.
        title = title.replaceFirst("^[\\s._-]+", "").trim();
        return title.isEmpty() ? tag : tag + " · " + title;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
