package com.gathin.tvhome;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.util.AtomicFile;
import android.util.Log;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.FocusFinder;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Movies &amp; Shows": a spotlight between the top bar and the dock, merging two sources — the Beast
 * download library (played by mpv) and the IPTV provider's newest movies (played by Just Player). The spotlight is
 * one large featured tile bound to the focused item plus a strip of smaller "peek" tiles the D-pad scrolls through;
 * moving across the peeks promotes the focused item into the featured tile. When the same title is in both sources,
 * the 4K download wins and the stream is dropped (dedup by {@link MediaItem#matchKey}). The row is newest-first.
 *
 * Movies play directly on OK. A show is a distinct "N new episodes" card: OK opens a Back-dismissible episode picker
 * (a single-episode show plays straight away). The final tile is "Show more", which opens the full browse view
 * (Tier 2) via {@link #openBrowse()}.
 *
 * Sources are optional: with only one configured, the rail is just that source. Everything the rail needs lives
 * here, so MainActivity only creates it, places it and routes keys to it.
 */
final class MediaRail {
    interface Host {
        void onRailUp();
        void onRailDown();
        void onRailFocused();
        /** The focused item changed (for the home backdrop); null when focus leaves the rail or is the "Show more" tile. */
        default void onItemFocused(MediaItem item) {}
        default void onRailMenu() {}
        default void onDashboardPlayFocusTimedOut() {}
    }

    private void armMovieFocusTimeout() {
        main.removeCallbacks(clearMovieFocus);
        if (section == Section.MOVIES && row.findFocus() != null) {
            main.postDelayed(clearMovieFocus, MOVIE_FOCUS_IDLE_MS);
        }
    }

    private void clearMovieFocusAfterIdle() {
        if (released || !resumed || section != Section.MOVIES) return;
        View focused = row.findFocus();
        if (focused == null) return;
        focused.clearFocus();
        row.clearFocus();
        enterMoviesIdle();
    }

    private void armDashboardPlayFocusTimeout() {
        main.removeCallbacks(clearDashboardPlayFocus);
        if (section == Section.DASHBOARD && playButton.hasFocus()) {
            main.postDelayed(clearDashboardPlayFocus, MOVIE_FOCUS_IDLE_MS);
        }
    }

    private void clearDashboardPlayFocusAfterIdle() {
        if (released || !resumed || section != Section.DASHBOARD || !playButton.hasFocus()) return;
        playButton.clearFocus();
        host.onDashboardPlayFocusTimedOut();
    }

    private static final String TAG = "MediaRail";
    private static final long MIN_REFRESH_MS = 10 * 60_000;
    /** How long each showcased item sits before the rail auto-advances to the next one while idle. */
    private static final long ROTATE_MS = 10_000;
    /** Peek tile size in dp: skinnier/taller than the wide featured tile, like the reference's "up next" tiles. */
    // Proportions taken from the cleanyourtv-ui fork's Explore spotlight (its L_CARD_H=442 cards: the featured one
    // ~940 wide, the "tall" peeks 320 wide — same height, bottom-aligned). Scaled to our band height: the hero is
    // ~2.13× its height wide; the peeks are ~0.72× its height wide and the SAME height as the hero.
    private static final int CARD_W = 150, CARD_H = 195;
    /** Full-width cinematic spotlight; poster rows below it. */
    private static final int SHOWCASE_H = 190;
    /** Decode width for tile art: a bit above the featured tile's pixel size (biggest tile) so posters stay crisp
     *  without wasting memory — decoding far larger than needed both wastes RAM and thrashes the image cache. */
    private static final int ART_DECODE_W = 520;
    private static final int GAP = 10, SECTION = 22, SECTION_GAP = 4;
    private static final float FOCUS_SCALE = 1.08f;
    /** The cache schema; readCache rejects any other header, so old "media-rail 1" files are simply ignored. */
    private static final String CACHE_HEADER = "media-rail 2";
    /** Sub-delimiter packing the episode list into one tab column (clean() strips tab/newline but not these). */
    private static final char EP_SEP = '\u001f';     // unit separator: between episodes
    private static final char EP_FIELD = '\u001e';   // record separator: between an episode's fields

    private final Context context;
    private final Host host;
    private final float density;
    private final BeastClient beast;         // may be null
    private final IptvClient iptv;           // may be null
    private final TmdbClient tmdb;           // may be null; used only to fill a missing wide backdrop lazily
    /** Backdrop URLs found for "title|year" keys, so a showcased item is looked up at most once per session.
     *  "" is cached for a title with no TMDB backdrop, so we don't re-query it every time it is shown. */
    private final Map<String, String> backdropCache = new HashMap<>();
    /** TMDB metadata found for "sort|title|year" keys. Null means not looked up yet. */
    private final Map<String, TmdbClient.Info> metadataCache = new HashMap<>();
    private final Set<String> metadataLoading = new HashSet<>();
    private final String downloadPlayer;
    private final String streamPlayer;
    private final int limit;
    private final int sourceCount;
    private final List<String> skipCategories;
    private final String badgeDownload;
    private final String badgeStream;
    private final AtomicFile cacheFile;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService metadataPool = Executors.newFixedThreadPool(2);
    private final ExecutorService imagePool = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    // Big enough to hold the featured tile plus the whole visible peek strip at ARGB_8888, so a sharp bitmap is
    // never evicted and reloaded (which showed as "sharp, then it goes soft"). ~24 MB on a 1 GB TV is acceptable
    // for a handful of poster-sized bitmaps.
    private final LruCache<String, Bitmap> images = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };

    /** The rail's content filter, chosen by the tab row above the showcase. */
    private enum Filter { ALL, MOVIES, SHOWS }
    /** Dashboard uses the priority-app row, while Movies and Shows use their own media rails. */
    private enum Section { DASHBOARD, MOVIES, SHOWS }

    private final LinearLayout container;
    private final LinearLayout tabRow;       // Explore / Movies / Shows, above the showcase (replaces the old header)
    private final TextView[] tabs;
    /** config explore_page: when true the tabs are Home / Explore (Explore opens ExploreActivity), and the rail
     *  itself never filters — it stays the full merged list. When false the old Explore / Movies / Shows tabs
     *  filter the rail in place. */
    private final boolean explorePage;
    private Filter filter = Filter.ALL;
    /** The full merged list from the sources; the adapter holds only the current filter's subset. */
    private List<MediaItem> allItems = new ArrayList<>();
    /** Hero rotation candidates. Shows remain in their own rail and never auto-enter the spotlight. */
    private List<MediaItem> spotlightItems = new ArrayList<>();
    private MediaItem heroItem;
    private final LinearLayout body;
    private final FrameLayout showcase;      // the big left box: the ONE focusable/clickable element in the rail
    private final ImageView showcaseImage;   // the big left showcase box's art
    private final TextView showcaseRating;
    private final TextView featuredTitle;    // the showcase title
    private final TextView featuredSub;       // detail line: "year · source · added" or "N new episodes"
    private final TextView featuredOverview;
    private final TextView playButton;
    private final RecyclerView row;
    private final RecyclerView showsRow;
    private final TextView recentLabel;
    private final TextView showsLabel;
    private Section section = Section.DASHBOARD;
    private final Adapter adapter = new Adapter(false);
    private final Adapter showsAdapter = new Adapter(true);

    private long lastRefresh;
    private boolean released;
    private boolean gridAtTop = true;
    private boolean placed;
    /** The selected position: 0..items.size()-1 picks that item into the showcase; items.size() is the "Show more"
     *  state (showcase shows a Show-more card, OK opens browse). The strip shows everything AFTER the selection, so
     *  the selected item is never duplicated as a peek tile. Left/Right move this; the strip never takes focus. */
    private int selected;
    private boolean edgeLeftArmed;
    private EpisodePicker picker;
    /** True while the launcher is in front (between onResume/onPause). Rotation only runs while resumed, so a
     *  backgrounded Home does not keep posting advances. */
    private boolean resumed = true;
    /** A steady 1 s heartbeat while the launcher is in front. Each beat re-checks {@link #canRotate()} from
     *  scratch and advances the showcase once {@link #ROTATE_MS} of uninterrupted idle time has accumulated. A
     *  polling heartbeat (rather than arming a one-shot on every focus/visibility/data event) is deliberately
     *  self-healing: the rail becomes rotatable only when resumed + placed + gridAtTop + visible all hold at once,
     *  a moment no single event reliably catches, so the beat simply keeps checking until they do. */
    private final Runnable rotateTick = this::rotateBeat;
    private static final long MOVIE_FOCUS_IDLE_MS = 10_000;
    private final Runnable clearMovieFocus = this::clearMovieFocusAfterIdle;
    private final Runnable clearDashboardPlayFocus = this::clearDashboardPlayFocusAfterIdle;
    private static final long ROTATE_BEAT_MS = 1_000;
    /** Idle time accumulated toward the next advance; reset to 0 whenever the user acts or rotation can't run. */
    private long idleMs;
    /** Set when the rail launched a player or the browse view, so Home puts focus back on the showcase when it
     *  resumes — the user left from the rail, so they return to it. Cleared once that focus is restored. */
    private boolean wantsFocusOnResume;

    /** The rail, added to {@code root} just below {@code above}; null when media_rail is off or no source is set. */
    static MediaRail create(Context context, ViewGroup root, View above, Host host) {
        Resources res = context.getResources();
        if (!res.getBoolean(R.bool.media_rail)) return null;

        BeastClient beast = null;
        String bBase = secret(context, "beast_api_base");
        String bKey = secret(context, "beast_api_key");
        String bRoot = secret(context, "beast_library_root");
        String bMedia = secret(context, "beast_media_base");
        if (!bBase.isEmpty() && !bKey.isEmpty() && !bRoot.isEmpty() && !bMedia.isEmpty()) {
            beast = new BeastClient(bBase, bKey, bRoot, bMedia);
        }

        IptvClient iptv = null;
        String iServer = secret(context, "iptv_server");
        String iUser = secret(context, "iptv_username");
        String iPass = secret(context, "iptv_password");
        if (!iServer.isEmpty() && !iUser.isEmpty() && !iPass.isEmpty()) {
            while (iServer.endsWith("/")) iServer = iServer.substring(0, iServer.length() - 1);
            iptv = new IptvClient(iServer, iUser, iPass);
        }

        if (beast == null && iptv == null) return null;
        String tKey = secret(context, "tmdb_api_key");
        TmdbClient tmdb = tKey.isEmpty() ? null : new TmdbClient(tKey);
        return new MediaRail(context, root, above, host, beast, iptv, tmdb);
    }

    private static String secret(Context context, String name) {
        int id = context.getResources().getIdentifier(name, "string", context.getPackageName());
        return id == 0 ? "" : context.getString(id).trim();
    }

    private MediaRail(Context context, ViewGroup root, View above, Host host,
                      BeastClient beast, IptvClient iptv, TmdbClient tmdb) {
        this.context = context;
        this.host = host;
        this.beast = beast;
        this.iptv = iptv;
        this.tmdb = tmdb;
        Resources res = context.getResources();
        density = res.getDisplayMetrics().density;
        downloadPlayer = res.getString(R.string.media_rail_download_player).trim();
        streamPlayer = res.getString(R.string.media_rail_stream_player).trim();
        limit = Math.max(1, Math.min(60, res.getInteger(R.integer.media_rail_count)));
        sourceCount = Math.max(limit, Math.min(60, res.getInteger(R.integer.media_rail_source_count)));
        skipCategories = Arrays.asList(res.getStringArray(R.array.media_rail_skip_categories));
        explorePage = res.getBoolean(R.bool.explore_page);
        badgeDownload = res.getString(R.string.media_rail_badge_download);
        badgeStream = res.getString(R.string.media_rail_badge_stream);
        cacheFile = new AtomicFile(new File(context.getFilesDir(), "media_rail.tsv"));

        container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setClipChildren(false);
        container.setClipToPadding(false);
        container.setVisibility(View.GONE);
        container.setAlpha(0f);
        container.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        container.getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            if (newF != null && !isInside(newF)) {
                container.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            }
        });

        // Kept internally for compatibility with filtered layouts, but the redesigned Home has no visible tabs.
        tabRow = new LinearLayout(context);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setFocusable(true);
        tabRow.setFocusableInTouchMode(false);
        tabRow.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);   // the row itself takes focus, not a child
        tabRow.setOnKeyListener((v, keyCode, event) -> onTabKey(keyCode, event));
        tabRow.setOnFocusChangeListener((v, focused) -> { paintTabs(focused); rescheduleRotation(); });
        int[] labels = explorePage
                ? new int[] {R.string.media_rail_tab_home, R.string.media_rail_tab_explore}
                : new int[] {R.string.media_rail_tab_explore, R.string.media_rail_tab_movies, R.string.media_rail_tab_shows};
        tabs = new TextView[labels.length];
        for (int i = 0; i < tabs.length; i++) {
            TextView t = new TextView(context);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            t.setLetterSpacing(0.08f);
            t.setSingleLine(true);
            t.setTypeface(UiFont.get(context, R.string.font_medium));
            t.setText(labels[i]);
            t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tp.setMarginEnd(dp(18));
            tabRow.addView(t, tp);
            tabs[i] = t;
        }
        paintTabs(false);
        tabRow.setVisibility(View.GONE);

        // The body is a vertical stack: a big hero title + meta line over the backdrop (no poster tile — the
        // full-screen HomeBackdrop IS the hero), then the single poster row the D-pad scrolls through.
        body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setClipChildren(false);
        body.setClipToPadding(false);

        // The body is horizontal: a BIG showcase box on the left (the focused item, large) + the poster strip on
        // the right. Moving focus along the strip updates the showcase. This is the Apple-TV spotlight shape.
        body.setOrientation(LinearLayout.VERTICAL);

        // --- Left: the big showcase box — the ONE focusable, clickable element in the rail ---
        showcase = new FrameLayout(context);
        showcase.setClipToOutline(false);
        showcase.setFocusable(false);
        showcase.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        showcaseImage = new ImageView(context);
        showcaseImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        showcaseImage.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        showcase.addView(showcaseImage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        showcaseImage.setVisibility(View.GONE);

        showcaseRating = ratingBadge(context);
        showcaseRating.setVisibility(View.GONE);

        LinearLayout showText = new LinearLayout(context);
        showText.setOrientation(LinearLayout.VERTICAL);
        showText.setPadding(dp(48), dp(10), dp(14), dp(12));
        showText.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        featuredTitle = new TextView(context);
        featuredTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        featuredTitle.setTextColor(Color.WHITE);
        featuredTitle.setTypeface(UiFont.get(context, R.string.font_medium));
        featuredTitle.setMaxLines(2);
        featuredTitle.setEllipsize(TextUtils.TruncateAt.END);
        showText.addView(featuredTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout detailHeader = new LinearLayout(context);
        detailHeader.setOrientation(LinearLayout.HORIZONTAL);
        detailHeader.setGravity(Gravity.CENTER_VERTICAL);
        detailHeader.setClipChildren(false);
        detailHeader.setClipToPadding(false);

        featuredSub = new TextView(context);
        featuredSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        featuredSub.setTextColor(0xDDFFFFFF);
        featuredSub.setTypeface(UiFont.get(context, R.string.font_regular));
        featuredSub.setSingleLine(true);
        featuredSub.setEllipsize(TextUtils.TruncateAt.END);
        featuredSub.setMaxWidth(dp(470));
        detailHeader.addView(featuredSub, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        playButton = new TextView(context);
        playButton.setText(R.string.media_rail_play);
        playButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        playButton.setTypeface(UiFont.get(context, R.string.font_medium));
        playButton.setTextColor(new ColorStateList(
                new int[][] {{android.R.attr.state_focused}, {}},
                new int[] {Color.BLACK, Color.WHITE}));
        playButton.setSingleLine(true);
        playButton.setGravity(Gravity.CENTER);
        playButton.setPadding(dp(16), dp(7), dp(16), dp(7));
        playButton.setBackground(playButtonBackground());
        playButton.setFocusable(true);
        playButton.setFocusableInTouchMode(false);
        playButton.setVisibility(View.GONE);
        playButton.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        playButton.setOnClickListener(v -> activateFeatured());
        playButton.setOnKeyListener((v, keyCode, event) -> onPlayKey(keyCode, event));
        playButton.setOnFocusChangeListener((v, focused) -> {
            v.animate().cancel();
            v.animate().scaleX(focused ? 1.06f : 1f).scaleY(focused ? 1.06f : 1f)
                    .setDuration(120).start();
            main.removeCallbacks(clearDashboardPlayFocus);
            if (focused && section == Section.DASHBOARD) armDashboardPlayFocusTimeout();
            if (focused) rescheduleRotation();
        });
        LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        playLp.setMarginStart(dp(14));
        detailHeader.addView(playButton, playLp);

        LinearLayout.LayoutParams headerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headerLp.topMargin = dp(3);
        showText.addView(detailHeader, headerLp);

        featuredOverview = new TextView(context);
        featuredOverview.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        featuredOverview.setTextColor(0xD9FFFFFF);
        featuredOverview.setTypeface(UiFont.get(context, R.string.font_regular));
        featuredOverview.setMaxLines(3);
        featuredOverview.setEllipsize(TextUtils.TruncateAt.END);
        featuredOverview.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams overviewLp = new LinearLayout.LayoutParams(
                dp(470), ViewGroup.LayoutParams.WRAP_CONTENT);
        overviewLp.topMargin = dp(8);
        showText.addView(featuredOverview, overviewLp);

        FrameLayout.LayoutParams stLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stLp.gravity = Gravity.BOTTOM;
        showcase.addView(showText, stLp);

        LinearLayout.LayoutParams showLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(SHOWCASE_H));
        showLp.setMarginEnd(dp(16));
        // Keep the showcase drawn above the strip as a backstop; the strip's left-edge fade (FadeEdgeFrame) is the
        // primary treatment, so a tile is already faded out before it could reach the showcase.
        showcase.setTranslationZ(dp(10));
        body.addView(showcase, showLp);

        recentLabel = sectionLabel(R.string.explore_row_movies, "Recently Added");
        LinearLayout.LayoutParams recentLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(SECTION));
        recentLp.topMargin = dp(10);
        recentLp.leftMargin = dp(48);
        body.addView(recentLabel, recentLp);

        // Recently Added rail beneath the spotlight.
        row = new RecyclerView(context);
        row.setLayoutManager(new LinearLayoutManager(context, RecyclerView.HORIZONTAL, false));
        row.setClipChildren(false);
        row.setClipToPadding(false);
        row.setPaddingRelative(dp(48), dp(10), dp(16), 0);
        row.setOverScrollMode(View.OVER_SCROLL_NEVER);
        row.setHorizontalScrollBarEnabled(false);
        row.setItemAnimator(null);
        row.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        row.setAdapter(adapter);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(CARD_H));
        body.addView(row, rp);

        showsLabel = sectionLabel(R.string.explore_row_shows, "Shows");
        LinearLayout.LayoutParams showsLabelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(SECTION));
        showsLabelLp.topMargin = dp(SECTION_GAP + 4);
        showsLabelLp.leftMargin = dp(48);
        body.addView(showsLabel, showsLabelLp);

        showsRow = new RecyclerView(context);
        showsRow.setLayoutManager(new LinearLayoutManager(context, RecyclerView.HORIZONTAL, false));
        showsRow.setClipChildren(false);
        showsRow.setClipToPadding(false);
        showsRow.setPaddingRelative(dp(48), dp(10), dp(16), 0);
        showsRow.setOverScrollMode(View.OVER_SCROLL_NEVER);
        showsRow.setHorizontalScrollBarEnabled(false);
        showsRow.setItemAnimator(null);
        showsRow.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        showsRow.setAdapter(showsAdapter);
        body.addView(showsRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(CARD_H)));
        recentLabel.setVisibility(View.GONE);
        row.setVisibility(View.GONE);
        showsLabel.setVisibility(View.GONE);
        showsRow.setVisibility(View.GONE);

        container.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height());
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.setMarginStart(0);
        int at = root.indexOfChild(above);
        root.addView(container, at < 0 ? root.getChildCount() : at, lp);

        List<MediaItem> cached = readCache();
        if (cached != null) apply(cached);
    }

    // ---- placement and visibility ----------------------------------------------------------------

    int height() {
        return dp(SHOWCASE_H + 14
                + (section == Section.DASHBOARD ? 0 : SECTION + CARD_H));
    }

    /** The rail band's pixel height — Home uses it to leave room above the dock so the band never overlaps it. */
    int bandHeight() {
        return height();
    }

    void placeAbove(float anchorTop, float minTop) {
        float y = Math.max(minTop + dp(8), anchorTop - height() - dp(4));
        container.setY(y);
        placed = true;
        updateVisibility();
    }

    void placeBelowHeader(float headerBottom) {
        container.setY(headerBottom + dp(8));
        placed = true;
        updateVisibility();
    }

    void setGridAtTop(boolean atTop) {
        updateVisibility();
    }

    /** Re-assert the rail's visibility from its current state. Called when Home resumes (e.g. back from a player),
     *  so a container left GONE during the transition is restored rather than staying hidden. */
    void revalidate() {
        updateVisibility();
    }

    /**
     * Called by Home on resume. If the user left the rail to play something or open browse, bring the rail back on
     * screen and put focus on the showcase, so returning lands exactly where they were. This also sidesteps the
     * grid's scroll-offset flag entirely: focusing the showcase means the rail is shown regardless of any transient
     * scroll state left by the trip. Returns true when it took focus, so Home does not also refocus a grid tile.
     */
    boolean restoreFocusIfRequested() {
        if (!wantsFocusOnResume || released) return false;
        wantsFocusOnResume = false;
        if (explorePage) paintExploreTab(false);   // returning from Explore: Home is the selected tab again
        if (itemCount() == 0) return false;
        // Force the rail visible: the user is returning to it, so the grid is at the top for our purposes.
        gridAtTop = true;
        placed = true;
        updateVisibility();
        // Focus the showcase once it has been laid out (post handles the resume frame not being drawn yet).
        container.post(() -> {
            if (!released && wantsFocusOnResume == false && container.getVisibility() == View.VISIBLE) {
                focusCurrent();
            }
        });
        return true;
    }

    /** True when the rail launched a player/browse and so owns focus on the next resume. Home uses this to keep
     *  its tile rebuild from stealing focus (and scroll-flipping the rail away) before the rail restores focus. */
    boolean wantsFocusOnResume() {
        return wantsFocusOnResume && !released && itemCount() > 0;
    }

    /** True when the spotlight has content that can be presented and played. */
    boolean hasItems() {
        return !released && itemCount() > 0;
    }

    /** Clears the pending "focus the rail on resume" request (e.g. the user pressed Home, not Back from a player). */
    void cancelFocusOnResume() {
        wantsFocusOnResume = false;
    }

    boolean isShowing() {
        // itemCount(), not adapter.getItemCount(): the latter is the strip's peek count, which is 0 whenever the
        // selected item is the last one (empty strip) — as on the last movie, or the single item in Shows. The
        // rail is still on screen and focusable then (the showcase holds that item), so keying off the peek count
        // made Up from the grid skip the rail and land on the top bar.
        return container.getVisibility() == View.VISIBLE && itemCount() > 0;
    }

    boolean isShowsActive() {
        return section == Section.SHOWS;
    }

    boolean isDashboardActive() {
        return section == Section.DASHBOARD;
    }

    void focus() {
        if (itemCount() == 0) return;
        updateVisibility();
        gridAtTop = true;
        switch (section) {
            case DASHBOARD:
                focusPlay();
                break;
            case MOVIES:
                focusMovies();
                break;
            case SHOWS:
                focusShows();
                break;
        }
    }

    void focusDashboard() {
        section = Section.DASHBOARD;
        main.removeCallbacks(clearMovieFocus);
        main.removeCallbacks(clearDashboardPlayFocus);
        applySectionRows();
        updateVisibility();
        container.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        gridAtTop = true;
        idleMs = 0;
        rescheduleRotation();
    }

    void focusShows() {
        updateVisibility();
        if (showsAdapter.getItemCount() == 0) {
            return;
        }
        boolean wasMovies = section == Section.MOVIES;
        if (wasMovies) {
            switchRail(true, true);
        } else {
            section = Section.SHOWS;
            applySectionRows();
            focusFirst(showsRow);
        }
        main.removeCallbacks(rotateTick);
        main.removeCallbacks(clearMovieFocus);
        main.removeCallbacks(clearDashboardPlayFocus);
        idleMs = 0;
    }

    void focusMovies() {
        boolean wasShows = section == Section.SHOWS;
        updateVisibility();
        if (wasShows) {
            switchRail(false, true);
        } else {
            section = Section.MOVIES;
            applySectionRows();
            focusFirst(row);
        }
        main.removeCallbacks(clearDashboardPlayFocus);
        if (wasShows) onResumed();
    }

    void focusCurrent() {
        switch (section) {
            case DASHBOARD:
                focusPlay();
                break;
            case MOVIES:
                focusMovies();
                break;
            case SHOWS:
                focusShows();
                break;
        }
    }

    void focusPlay() {
        if (itemCount() == 0 || playButton.getVisibility() != View.VISIBLE) return;
        container.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        playButton.postOnAnimation(() -> {
            if (!released && playButton.requestFocus() && section == Section.DASHBOARD) {
                armDashboardPlayFocusTimeout();
            }
        });
    }

    void enterMoviesIdle() {
        container.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        if (getCurrentFocusedView() != null) getCurrentFocusedView().clearFocus();
        idleMs = 0;
        main.removeCallbacks(rotateTick);
        if (resumed && !released) main.postDelayed(rotateTick, ROTATE_BEAT_MS);
    }

    boolean isMoviesIdle() {
        return section == Section.MOVIES && row.findFocus() == null && !playButton.hasFocus();
    }

    void focusMoviesFromIdle(KeyEvent event) {
        focusFirst(row);
        row.post(() -> {
            RecyclerView.ViewHolder holder = row.findViewHolderForAdapterPosition(0);
            if (holder != null && holder.itemView.isFocused()) {
                holder.itemView.dispatchKeyEvent(event);
            }
        });
    }

    private View getCurrentFocusedView() {
        if (row.hasFocus()) return row.getFocusedChild();
        if (showsRow.hasFocus()) return showsRow.getFocusedChild();
        return container.findFocus();
    }

    void recoverFromApps() {
        placed = true;
        gridAtTop = true;
        updateVisibility();
    }

    /** Number of real media items (not counting the Show-more state). */
    private int itemCount() {
        return spotlightItems.size();
    }

    /** The highest selectable index: the Show-more state sits one past the last item. */
    private int maxSelected() {
        return itemCount();   // 0..itemCount()-1 are items; itemCount() is "Show more"
    }

    private boolean isShowMoreSelected() {
        return selected >= itemCount();
    }

    // ---- showcase input: Left/Right rotate the selection, OK acts on it, Up/Down leave the rail ----

    private boolean onShowcaseKey(int keyCode, KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            // Swallow the matching UP so a held key does not leak to the grid behind.
            return keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN;
        }
        // Any key while the spotlight is focused counts as activity: reset the auto-advance dwell so a parked
        // spotlight only rotates once the user has actually stopped pressing keys. (Left/Right also reset it via
        // moveSelection, but Left-at-first / Right-at-last no-op there, and Up/Down/OK wouldn't otherwise.)
        rescheduleRotation();
        boolean rtl = showcase.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                host.onRailUp();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                focusFirst(row);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return moveSelection(rtl ? +1 : -1);
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return moveSelection(rtl ? -1 : +1);
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                activateSelected();
                return true;
            default:
                return false;
        }
    }

    /** Key handling on the tab row: Left/Right switch the filter, Down returns to the showcase, Up escapes upward
     *  (to the top bar) like the showcase's Up used to. */
    private boolean onTabKey(int keyCode, KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN;
        }
        boolean rtl = tabRow.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                // In two-tab mode, OK on the row opens Explore (the row shows Home / Explore and never filters).
                if (explorePage) {
                    openExplore();
                    return true;
                }
                return false;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return moveTab(rtl ? +1 : -1);
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return moveTab(rtl ? -1 : +1);
            case KeyEvent.KEYCODE_DPAD_DOWN:
                // Down returns to the showcase when the tab has items; on an empty tab the showcase is not
                // focusable, so drop to the grid instead rather than trap focus on the tab row.
                if (itemCount() > 0) showcase.requestFocus();
                else host.onRailDown();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                host.onRailUp();           // leave the rail upward, to the top bar
                return true;
            default:
                return false;
        }
    }

    /** Moves the tab selection by delta (clamped), applies the new filter, and repaints the row. */
    private boolean moveTab(int delta) {
        if (explorePage) {
            // Two tabs: Home (0) and Explore (1). Right from Home opens the Explore page; the tab stays Home so
            // returning from Explore lands back on an unchanged Home.
            if (delta > 0) {
                paintExploreTab(true);
                openExplore();
            } else {
                paintExploreTab(false);
            }
            return true;
        }
        int next = Math.max(0, Math.min(tabs.length - 1, filter.ordinal() + delta));
        if (next == filter.ordinal()) return true;   // at an end: swallow so focus never escapes sideways
        filter = Filter.values()[next];
        paintTabs(true);
        selected = 0;                                  // a new filter: start at its first item
        adapter.setItems(filtered());
        bindSelected();
        updateVisibility();
        rescheduleRotation();                          // the new filter may have a different item count
        return true;
    }

    /** In two-tab mode, briefly highlight the Explore tab (true) or restore Home as selected (false). */
    private void paintExploreTab(boolean exploreActive) {
        if (tabs.length < 2) return;
        tabs[0].setTextColor(exploreActive ? 0x80FFFFFF : Color.WHITE);
        tabs[1].setTextColor(exploreActive ? Color.WHITE : 0x80FFFFFF);
    }

    /** Opens the full-screen Explore page; returning from it lands back on the showcase. */
    private void openExplore() {
        wantsFocusOnResume = true;
        try {
            Intent i = new Intent(context, ExploreActivity.class);
            if (!(context instanceof android.app.Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(context, R.string.media_rail_no_player, Toast.LENGTH_SHORT).show();
        }
    }

    /** Highlights the selected tab (bright, or accent-underlined while the row holds focus) and dims the rest. */
    private void paintTabs(boolean rowFocused) {
        for (int i = 0; i < tabs.length; i++) {
            boolean sel = i == filter.ordinal();
            tabs[i].setTextColor(sel ? Color.WHITE : 0x80FFFFFF);
            // While the row is focused, lift the selected label a touch more so it reads as the focused control.
            tabs[i].setAlpha(sel ? 1f : (rowFocused ? 0.9f : 0.7f));
        }
    }

    /** The current filter's items from the full merged list. */
    private List<MediaItem> filtered() {
        if (filter == Filter.ALL) return allItems;
        List<MediaItem> out = new ArrayList<>();
        for (MediaItem it : allItems) {
            if ((filter == Filter.SHOWS) == it.isSeries) out.add(it);
        }
        return out;
    }

    /** Advances the selection by {@code delta}, clamped to 0..maxSelected (the Show-more state). Always consumes the
     *  key so focus never escapes the rail sideways; a move at either end simply does nothing. */
    private boolean moveSelection(int delta) {
        int next = Math.max(0, Math.min(maxSelected(), selected + delta));
        if (next != selected) {
            selected = next;
            bindSelected();
        }
        rescheduleRotation();   // a manual move restarts the idle clock, so the user isn't yanked a moment later
        return true;
    }

    /** Moves within playable spotlight items only; the Play control never enters the separate Show-more state. */
    private void moveFeaturedSelection(int delta) {
        int next = Math.max(0, Math.min(itemCount() - 1, selected + delta));
        if (next != selected) {
            selected = next;
            bindSelected();
        }
        rescheduleRotation();
    }

    // ---- auto-rotate: advance the showcase while idle, looping past the last item (never "Show more") ----

    /** One heartbeat: when rotation is allowed, add this beat to the idle clock and advance once it reaches
     *  {@link #ROTATE_MS}; when it isn't (focused, a player in front, one-item filter, hidden…), hold the clock at
     *  0 so the full dwell restarts the moment it becomes idle again. Always re-posts itself while resumed. */
    private void rotateBeat() {
        if (released) return;
        if (canRotate()) {
            idleMs += ROTATE_BEAT_MS;
            if (idleMs >= ROTATE_MS) {
                idleMs = 0;
                fadeToNext();
            }
        } else {
            idleMs = 0;
        }
        if (resumed) main.postDelayed(rotateTick, ROTATE_BEAT_MS);
    }

    /** True only when it makes sense to auto-advance: the launcher is in front, the rail is shown, no picker is up,
     *  and the current filter has at least two items to cycle between. Rotation runs even while the showcase holds
     *  focus (a parked spotlight keeps cycling) and while the tab row holds focus (the showcase still rotates under
     *  the Home/Explore tabs) — rotation only swaps the showed item, never moving focus — but any keypress resets
     *  the dwell via rescheduleRotation(), so it never advances out from under someone actively pressing keys. */
    private boolean canRotate() {
        return resumed && !released
                && container.getVisibility() == View.VISIBLE && gridAtTop && placed
                && picker == null
                && section != Section.SHOWS
                && row.findFocus() == null && showsRow.findFocus() == null
                && itemCount() >= 2;
    }

    /** Length of each half of the auto-advance cross-dissolve (fade the card out, swap, fade it back). */
    private static final long ROTATE_FADE_MS = 450;

    /** Auto-advance with a slow cross-dissolve: dim the whole card (hero art, title/meta and the peek strip) down,
     *  swap to the next item at the trough, then bring it back up — so a rotation glides instead of cutting.
     *  Only the timer uses this; manual Left/Right and focus still bind instantly, which keeps scrubbing snappy. */
    private void fadeToNext() {
        int n = itemCount();
        if (n < 2) return;
        showcase.animate().cancel();
        showcase.animate().alpha(0f).setDuration(ROTATE_FADE_MS).withEndAction(() -> {
            if (released) return;
            selected = (selected + 1) % itemCount();   // wraps the last item back to 0; Show-more is skipped
            bindSelected();
            showcase.setAlpha(0f);
            showcase.animate().alpha(1f).setDuration(ROTATE_FADE_MS).start();
        }).start();
    }

    /** A user action (manual Left/Right, a filter switch) resets the dwell so the item they landed on gets a full
     *  interval before the rail moves on, and the heartbeat is (re)started if it isn't already running. It also
     *  settles the card's alpha to 1, in case a rotation cross-dissolve was mid-flight when the user took over. */
    private void rescheduleRotation() {
        body.animate().cancel();
        body.setAlpha(1f);
        idleMs = 0;
        if (resumed && !released) {
            main.removeCallbacks(rotateTick);
            main.postDelayed(rotateTick, ROTATE_BEAT_MS);
        }
    }

    /** Home resumed: the launcher is in front again, so start the heartbeat. */
    void onResumed() {
        resumed = true;
        idleMs = 0;
        main.removeCallbacks(rotateTick);
        main.postDelayed(rotateTick, ROTATE_BEAT_MS);
        armMovieFocusTimeout();
        armDashboardPlayFocusTimeout();
    }

    /** Home paused/stopped: stop the heartbeat so a backgrounded launcher doesn't keep stepping the showcase, and
     *  settle the card's alpha in case a cross-dissolve was mid-flight (else it could return invisible). */
    void onPaused() {
        resumed = false;
        main.removeCallbacks(rotateTick);
        main.removeCallbacks(clearMovieFocus);
        main.removeCallbacks(clearDashboardPlayFocus);
        body.animate().cancel();
        body.setAlpha(1f);
    }

    /** OK on the showcase: a movie plays, a series opens the picker, the Show-more state opens browse. */
    private void activateSelected() {
        if (isShowMoreSelected()) {
            openBrowse();
            return;
        }
        if (selected < 0 || selected >= itemCount()) return;
        open(spotlightItems.get(selected), showcase);
    }

    private void activateFeatured() {
        if (heroItem != null) {
            open(heroItem, playButton);
            return;
        }
        activateSelected();
    }

    private boolean onPlayKey(int keyCode, KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                    || keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
        }
        rescheduleRotation();
        if (section == Section.DASHBOARD) armDashboardPlayFocusTimeout();
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                host.onRailUp();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (section == Section.DASHBOARD) host.onRailDown();
                else if (section == Section.SHOWS) focusFirst(showsRow);
                else focusFirst(row);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (section != Section.SHOWS) {
                    moveFeaturedSelection(playButton.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? +1 : -1);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (section != Section.SHOWS) {
                    moveFeaturedSelection(playButton.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? -1 : +1);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                activateFeatured();
                return true;
            default:
                return false;
        }
    }

    /** Points the showcase, the strip (everything after the selection) and the home backdrop at the current
     *  selection. Called on every Left/Right move, on focus, and after new data lands. */
    private void bindSelected() {
        int n = itemCount();
        if (n == 0) {
            bindEmptyFilter();   // the current tab has nothing: a short message, not a blank box
            adapter.setStart(0);
            return;
        }
        selected = Math.max(0, Math.min(maxSelected(), selected));
        if (isShowMoreSelected()) {
            bindShowMore();
            host.onItemFocused(spotlightItems.get(0));   // keep a sensible backdrop behind the Show-more card
        } else {
            MediaItem it = spotlightItems.get(selected);
            bindFeatured(it);
            host.onItemFocused(it);
        }
        // The strip previews everything AFTER the selection, then the Show-more tile; so the selected item is
        // never duplicated as a peek. At the Show-more state the strip is empty.
        adapter.setStart(0);
    }

    /** Dresses the showcase as the "Show more" card: the newest item's art kept dimmed behind the label, so the
     *  box reads as a real "see everything" card rather than an empty box. */
    private void bindShowMore() {
        heroItem = null;
        playButton.setVisibility(View.GONE);
        showcaseRating.setVisibility(View.GONE);
        if (!spotlightItems.isEmpty()) {
            loadImage(showcaseImage, wideArt(spotlightItems.get(0)));
            showcaseImage.setAlpha(0.35f);         // dim the art so the "Show more" label clearly leads
        } else {
            showcaseImage.setImageDrawable(null);
            showcaseImage.setTag(null);
        }
        featuredTitle.setText(context.getString(R.string.media_rail_show_more));
        featuredSub.setText(context.getString(R.string.media_rail_show_more_sub));
    }

    private boolean isInside(View v) {
        for (View p = v; p != null; p = p.getParent() instanceof View ? (View) p.getParent() : null) {
            if (p == container) return true;
        }
        return false;
    }

    private void updateVisibility() {
        // Show whenever the rail has any data at all (across filters), not just the current filter. An empty tab
        // (e.g. Shows with no series) must keep the rail and its tabs on screen with the empty message, so the
        // user can switch back; it must not tear the whole rail down.
        boolean show = placed && !allItems.isEmpty();
        boolean visible = container.getVisibility() == View.VISIBLE && container.getAlpha() > 0f;
        if (show == visible) {
            // Already in the right state, but a prior animation may have left alpha mid-way or a pending
            // withEndAction could still flip it; settle it deterministically without re-animating.
            container.animate().cancel();
            if (show) {
                container.setVisibility(View.VISIBLE);
                container.setAlpha(1f);
            } else {
                container.setVisibility(View.GONE);
                container.setAlpha(0f);
            }
            rescheduleRotation();   // rotation is gated on visibility, so re-arm/cancel whenever it settles
            return;
        }
        container.animate().cancel();
        if (show) {
            container.setVisibility(View.VISIBLE);
            container.animate().alpha(1f).setDuration(200).start();
        } else {
            container.animate().alpha(0f).setDuration(140)
                    // Guard: only hide if hiding is still the intent when the fade ends (state can change mid-fade).
                    // Mirror the show condition exactly (allItems, not the strip's peek count) so an empty strip on
                    // the last item or in a single-item filter does not wrongly let the rail hide.
                    .withEndAction(() -> {
                        if (!(placed && gridAtTop && !allItems.isEmpty())) {
                            container.setVisibility(View.GONE);
                        }
                    }).start();
        }
        rescheduleRotation();   // rotation is gated on visibility, so re-arm/cancel whenever it settles
    }

    // ---- data ------------------------------------------------------------------------------------

    void refresh() {
        if (released) return;
        long now = SystemClock.elapsedRealtime();
        if (lastRefresh != 0 && now - lastRefresh < MIN_REFRESH_MS) return;
        lastRefresh = now;
        worker.execute(() -> {
            // Each source is fetched independently: one being down must not blank the whole row.
            List<MediaItem> downloads = new ArrayList<>();
            List<MediaItem> streams = new ArrayList<>();
            boolean anyOk = false;
            if (beast != null) {
                try {
                    downloads = beast.recent(sourceCount, downloadPlayer);
                    anyOk = true;
                } catch (Exception e) {
                    Log.w(TAG, "beast fetch failed: " + e.getClass().getSimpleName());
                }
            }
            if (iptv != null) {
                try {
                    streams = iptv.newestMedia(sourceCount, skipCategories, streamPlayer);
                    anyOk = true;
                } catch (Exception e) {
                    Log.w(TAG, "iptv fetch failed: " + e.getClass().getSimpleName());
                }
            }
            if (!anyOk) {
                lastRefresh = 0; // both sources failed: try again next time Home comes up
                return;
            }
            List<MediaItem> merged = merge(downloads, streams);
            if (merged.isEmpty()) return; // keep what is cached
            writeCache(merged);
            main.post(() -> {
                if (!released) apply(merged);
            });
        });
    }

    /**
     * Merges downloads and streams into one newest-first list, deduped by {@link MediaItem#matchKey}: when a title
     * is in both, the download is kept and the stream dropped (4K-first). Capped at {@link #limit}.
     */
    private List<MediaItem> merge(List<MediaItem> downloads, List<MediaItem> streams) {
        Map<String, MediaItem> byKey = new LinkedHashMap<>();
        // Downloads first so they win the key; a blank key (rare) falls back to the id so it is never merged away.
        for (MediaItem d : downloads) {
            byKey.put(keyOf(d), d);
        }
        for (MediaItem s : streams) {
            byKey.putIfAbsent(keyOf(s), s);
        }
        List<MediaItem> all = new ArrayList<>(byKey.values());
        all.sort((a, b) -> Long.compare(b.added, a.added));
        return all.size() > limit ? new ArrayList<>(all.subList(0, limit)) : all;
    }

    private static String keyOf(MediaItem it) {
        return it.matchKey == null || it.matchKey.isEmpty() ? it.id : it.matchKey;
    }

    void release() {
        released = true;
        main.removeCallbacks(rotateTick);
        main.removeCallbacks(clearMovieFocus);
        main.removeCallbacks(clearDashboardPlayFocus);
        dismissPicker();
        worker.shutdownNow();
        metadataPool.shutdownNow();
        imagePool.shutdownNow();
        images.evictAll();
    }

    private void apply(List<MediaItem> items) {
        boolean firstData = allItems.isEmpty();
        allItems = items;
        List<MediaItem> recent = new ArrayList<>();
        List<MediaItem> shows = new ArrayList<>();
        for (MediaItem item : items) {
            if (item.isSeries) shows.add(item);
            else recent.add(item);
        }
        spotlightItems = new ArrayList<>(recent);
        adapter.setItems(recent);
        showsAdapter.setItems(shows);
        selected = Math.max(0, Math.min(maxSelected(), selected));
        bindSelected();
        updateVisibility();
        rescheduleRotation();   // first data (or a refresh) may make rotation possible for the first time
        if (firstData && section != Section.DASHBOARD) container.post(this::focus);
    }

    private List<MediaItem> readCache() {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(cacheFile.openRead(), StandardCharsets.UTF_8))) {
            if (!CACHE_HEADER.equals(in.readLine())) return null;
            List<MediaItem> items = new ArrayList<>();
            for (String line; (line = in.readLine()) != null; ) {
                String[] f = line.split("\t", -1);
                if (f.length != 12) continue;
                try {
                    MediaItem.Source src = "DOWNLOAD".equals(f[0]) ? MediaItem.Source.DOWNLOAD : MediaItem.Source.STREAM;
                    boolean isSeries = "1".equals(f[9]);
                    int newEpisodeCount = Integer.parseInt(f[10]);
                    List<BeastClient.Episode> eps = decodeEpisodes(f[11], playerFor(src));
                    items.add(new MediaItem(src, f[1], f[2], Integer.parseInt(f[3]), f[4], f[5],
                            Long.parseLong(f[6]), f[7], f[8], playerFor(src), isSeries, newEpisodeCount, eps));
                } catch (NumberFormatException ignored) {
                    // a damaged line is skipped, the rest still shows
                }
            }
            return items;
        } catch (IOException e) {
            return null;
        }
    }

    private String playerFor(MediaItem.Source src) {
        return src == MediaItem.Source.DOWNLOAD ? downloadPlayer : streamPlayer;
    }

    private void writeCache(List<MediaItem> items) {
        StringBuilder b = new StringBuilder(CACHE_HEADER).append('\n');
        for (MediaItem it : items) {
            b.append(it.source).append('\t').append(clean(it.id)).append('\t').append(clean(it.title)).append('\t')
                    .append(it.year).append('\t').append(clean(it.caption)).append('\t').append(clean(it.poster))
                    .append('\t').append(it.added).append('\t').append(clean(it.url)).append('\t')
                    .append(clean(it.mime)).append('\t')
                    .append(it.isSeries ? '1' : '0').append('\t').append(it.newEpisodeCount).append('\t')
                    .append(encodeEpisodes(it.episodes)).append('\n');
        }
        FileOutputStream out = null;
        try {
            out = cacheFile.startWrite();
            out.write(b.toString().getBytes(StandardCharsets.UTF_8));
            cacheFile.finishWrite(out);
        } catch (IOException e) {
            if (out != null) cacheFile.failWrite(out);
        }
    }

    /** Packs an episode list into one tab-safe column: episodes joined by EP_SEP, fields by EP_FIELD. */
    private static String encodeEpisodes(List<BeastClient.Episode> eps) {
        if (eps == null || eps.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (BeastClient.Episode e : eps) {
            if (e == null || e.url == null || e.url.isEmpty()) continue;   // unplayable episodes are not persisted
            if (sb.length() > 0) sb.append(EP_SEP);
            sb.append(epField(e.label)).append(EP_FIELD)
                    .append(e.episodeId).append(EP_FIELD)
                    .append(epField(e.url)).append(EP_FIELD)
                    .append(epField(e.mime)).append(EP_FIELD)
                    .append(epField(e.serverPath));
        }
        return sb.toString();
    }

    /** Rebuilds the episode list persisted by {@link #encodeEpisodes}; a damaged entry is skipped. */
    private static List<BeastClient.Episode> decodeEpisodes(String packed, String player) {
        List<BeastClient.Episode> out = new ArrayList<>();
        if (packed == null || packed.isEmpty()) return out;
        for (String part : packed.split(String.valueOf(EP_SEP), -1)) {
            if (part.isEmpty()) continue;
            String[] f = part.split(String.valueOf(EP_FIELD), -1);
            if (f.length != 5) continue;
            BeastClient.Episode e = new BeastClient.Episode();
            e.label = f[0];
            try {
                e.episodeId = Long.parseLong(f[1]);
            } catch (NumberFormatException ex) {
                continue;
            }
            e.url = f[2];
            e.mime = f[3];
            e.serverPath = f[4];
            if (e.url.isEmpty()) continue;
            out.add(e);
        }
        return out;
    }

    private static String epField(String s) {
        if (s == null) return "";
        // Strip the sub-delimiters so a stray value cannot corrupt the packing.
        return s.replace(EP_SEP, ' ').replace(EP_FIELD, ' ');
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    // ---- playing ---------------------------------------------------------------------------------

    private void play(MediaItem item) {
        play(item.url, item.mime, item.title, item.player);
    }

    private void play(String url, String mime, String titleText, String player) {
        if (url == null || url.isEmpty()) {
            Toast.makeText(context, R.string.media_rail_no_player, Toast.LENGTH_LONG).show();
            return;
        }
        // The user is leaving Home from the rail to watch something; put focus back on the showcase on return.
        wantsFocusOnResume = true;
        Uri uri = Uri.parse(url);
        String type = mime == null || mime.isEmpty() ? "video/*" : mime;
        Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
                .putExtra("title", titleText)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        if (player != null && !player.isEmpty()) {
            try {
                context.startActivity(new Intent(i).setPackage(player));
                return;
            } catch (ActivityNotFoundException | SecurityException e) {
                Log.w(TAG, "player " + player + " missing; asking any video player");
            }
        }
        try {
            context.startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(context, R.string.media_rail_no_player, Toast.LENGTH_LONG).show();
        }
    }

    /** OK on a card: a movie plays, a series opens the picker (or plays straight away when it has one episode). */
    private void open(MediaItem item, View origin) {
        if (!item.isSeries) {
            play(item);
            return;
        }
        List<BeastClient.Episode> eps = item.episodes;
        if (eps == null || eps.isEmpty()) {
            Toast.makeText(context, R.string.media_rail_picker_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        wantsFocusOnResume = true;
        Intent show = new Intent(context, ExploreActivity.class)
                .putExtra(ExploreActivity.EXTRA_SHOW_ID, item.id);
        try {
            context.startActivity(show);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(context, R.string.media_rail_no_player, Toast.LENGTH_SHORT).show();
        }
    }

    /** Opens the full browse view (Tier 2): the full download library plus TMDB trending matched to streams. */
    private void openBrowse() {
        wantsFocusOnResume = true;   // returning from browse lands back on the showcase
        try {
            Intent i = new Intent(context, BrowseActivity.class);
            if (!(context instanceof android.app.Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(context, R.string.media_rail_no_player, Toast.LENGTH_SHORT).show();
        }
    }

    // ---- episode picker --------------------------------------------------------------------------

    private void showPicker(MediaItem series, View origin) {
        dismissPicker();
        View parent = container.getParent() instanceof ViewGroup ? (View) container.getParent() : null;
        if (!(parent instanceof ViewGroup)) return;
        picker = new EpisodePicker(series, origin);
        ((ViewGroup) parent).addView(picker.overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        picker.focusFirst();
        rescheduleRotation();   // picker is up: canRotate() is now false, so this cancels the pending tick
    }

    private void dismissPicker() {
        if (picker == null) return;
        EpisodePicker p = picker;
        picker = null;
        p.remove();
        rescheduleRotation();   // picker gone: idle rotation may resume
    }

    /** A compact, centered, Back-dismissible list of a series' episodes, newest-first, drawn over the whole root. */
    private final class EpisodePicker {
        final FrameLayout overlay;
        final LinearLayout list;
        final View origin;

        EpisodePicker(MediaItem series, View origin) {
            this.origin = origin;
            // dispatchKeyEvent (not an OnKeyListener) so Back is caught no matter which child holds focus: the
            // focus sits on an episode row inside the ScrollView, and a ViewGroup's OnKeyListener only fires when
            // that group itself is focused, so the old listener never saw Back — the picker could only be left
            // with Home. dispatchKeyEvent runs on every key routed into this overlay's subtree.
            overlay = new FrameLayout(context) {
                @Override public boolean dispatchKeyEvent(KeyEvent event) {
                    if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                        if (event.getAction() == KeyEvent.ACTION_UP) dismissPicker();
                        return true;   // swallow both down and up so Back never reaches the launcher behind us
                    }
                    // Trap focus inside the picker: a D-pad move that would leave the overlay (e.g. Down past the
                    // last episode) is swallowed, so focus never escapes to the grid behind while the popup stays
                    // open. Moves that stay inside — between episodes — pass through untouched. Only Back or
                    // picking an episode leaves the picker.
                    if (event.getAction() == KeyEvent.ACTION_DOWN && isDirectionKey(event.getKeyCode())) {
                        int dir = focusDirection(event.getKeyCode());
                        View focused = findFocus();
                        if (dir != 0 && focused != null) {
                            View next = FocusFinder.getInstance().findNextFocus(this, focused, dir);
                            if (next == null || !isInOverlay(next)) {
                                return true;   // would escape: consume, keep focus where it is
                            }
                        }
                    }
                    return super.dispatchKeyEvent(event);
                }

                private boolean isDirectionKey(int keyCode) {
                    return keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                            || keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
                }

                private int focusDirection(int keyCode) {
                    switch (keyCode) {
                        case KeyEvent.KEYCODE_DPAD_UP: return View.FOCUS_UP;
                        case KeyEvent.KEYCODE_DPAD_DOWN: return View.FOCUS_DOWN;
                        case KeyEvent.KEYCODE_DPAD_LEFT: return View.FOCUS_LEFT;
                        case KeyEvent.KEYCODE_DPAD_RIGHT: return View.FOCUS_RIGHT;
                        default: return 0;
                    }
                }

                private boolean isInOverlay(View v) {
                    for (View p = v; p != null; p = p.getParent() instanceof View ? (View) p.getParent() : null) {
                        if (p == this) return true;
                    }
                    return false;
                }
            };
            overlay.setBackgroundColor(0xCC000000);
            overlay.setClickable(true);
            overlay.setFocusable(true);
            overlay.setFocusableInTouchMode(true);

            LinearLayout panel = new LinearLayout(context);
            panel.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable panelBg = new GradientDrawable();
            panelBg.setCornerRadius(dp(12));
            panelBg.setColor(0xFF161616);
            panel.setBackground(panelBg);
            panel.setPadding(dp(18), dp(16), dp(18), dp(16));

            TextView t = new TextView(context);
            t.setText(series.title);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            t.setTextColor(Color.WHITE);
            t.setTypeface(UiFont.get(context, R.string.font_medium));
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            panel.addView(t);

            TextView sub = new TextView(context);
            sub.setText(context.getString(R.string.media_rail_picker_title));
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            sub.setTextColor(0x99FFFFFF);
            sub.setTypeface(UiFont.get(context, R.string.font_regular));
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            subLp.bottomMargin = dp(10);
            panel.addView(sub, subLp);

            ScrollView scroller = new ScrollView(context);
            scroller.setVerticalScrollBarEnabled(false);
            list = new LinearLayout(context);
            list.setOrientation(LinearLayout.VERTICAL);
            int index = 0;
            for (BeastClient.Episode ep : series.episodes) {
                list.addView(episodeRow(series, ep, index++));
            }
            scroller.addView(list, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            panel.addView(scroller, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(250)));

            FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(dp(360),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            plp.gravity = Gravity.CENTER;
            overlay.addView(panel, plp);
        }

        private View episodeRow(MediaItem series, BeastClient.Episode ep, int index) {
            TextView r = new TextView(context);
            String label = ep.label == null || ep.label.isEmpty() ? series.title : ep.label;
            r.setText(label);
            r.setContentDescription(label);
            r.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            r.setTextColor(Color.WHITE);
            r.setTypeface(UiFont.get(context, R.string.font_regular));
            r.setSingleLine(true);
            r.setEllipsize(TextUtils.TruncateAt.END);
            r.setPadding(dp(12), dp(10), dp(12), dp(10));
            r.setFocusable(true);
            r.setBackground(rowRing());
            r.setOnClickListener(v -> {
                dismissPicker();
                play(ep.url, ep.mime, series.title, series.player);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = index == 0 ? 0 : dp(2);
            r.setLayoutParams(lp);
            return r;
        }

        void focusFirst() {
            overlay.post(() -> {
                if (list.getChildCount() > 0) list.getChildAt(0).requestFocus();
                else overlay.requestFocus();
            });
        }

        void remove() {
            if (overlay.getParent() instanceof ViewGroup) {
                ((ViewGroup) overlay.getParent()).removeView(overlay);
            }
            if (origin != null) origin.requestFocus();
        }
    }

    private StateListDrawable rowRing() {
        GradientDrawable on = new GradientDrawable();
        on.setCornerRadius(dp(8));
        on.setColor(0x33FFFFFF);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[] {android.R.attr.state_focused}, on);
        s.addState(new int[] {}, new GradientDrawable());
        return s;
    }

    // ---- headers / featured ----------------------------------------------------------------------

    /** The wide (16:9) art for a tile: the TMDB backdrop when we have one, else the poster as a fallback. */
    private static String wideArt(MediaItem it) {
        return it.backdrop != null && !it.backdrop.isEmpty() ? it.backdrop : it.poster;
    }

    /**
     * The showcase box is wide (16:9); a provider that gave only a 2:3 poster leaves it showing a cropped,
     * zoomed poster. When a TMDB key is configured, look the title up once (off the main thread) to fill a real
     * backdrop, cache the result per title+year, and re-bind the showcase if that same item is still selected.
     * Does nothing when there is no key, the item already has a backdrop, or no confident match is found — the
     * poster fallback (current behaviour) stays in those cases, so a wrong guess never replaces correct art.
     */
    private void maybeFetchBackdrop(MediaItem it) {
        if (tmdb == null || it == null) return;
        if (it.backdrop != null && !it.backdrop.isEmpty()) return;   // already have one
        String key = it.title + "|" + it.year;
        String cached = backdropCache.get(key);
        if (cached != null) {                 // "" means "looked up, none found" — don't re-query
            if (!cached.isEmpty()) {
                it.backdrop = cached;
                if (isSelected(it)) {
                    loadImage(showcaseImage, cached);
                    host.onItemFocused(it);   // refresh the full-screen home backdrop too, not just the showcase
                }
            }
            return;
        }
        final String title = it.title;
        final int year = it.year;
        // Use worker (not imagePool): a slow TMDB call must not occupy an image-decode thread. worker is otherwise
        // idle except during refresh, and these lookups are infrequent (once per item the user lands on, cached).
        worker.execute(() -> {
            String bd;
            try {
                bd = tmdb.searchBackdrop(title, year);
            } catch (Exception e) {
                return;   // leave it uncached: a transient failure can be retried next time the item is shown
            }
            final String result = bd == null ? "" : bd;
            main.post(() -> {
                if (released) return;
                backdropCache.put(key, result);
                if (result.isEmpty()) return;
                it.backdrop = result;
                if (isSelected(it)) {                 // only refresh if still showing this item
                    loadImage(showcaseImage, result);
                    host.onItemFocused(it);           // and update the full-screen home backdrop to match
                }
            });
        });
    }

    /** True when {@code it} is the item currently featured in the showcase (guards a late backdrop from
     *  overwriting a newer selection the user has since moved to). */
    private boolean isSelected(MediaItem it) {
        return itemCount() > 0 && heroItem == it;
    }

    /** The showcase when the chosen tab has no items (e.g. no Shows yet): a short message, no art, no backdrop. */
    private void bindEmptyFilter() {
        heroItem = null;
        playButton.setVisibility(View.GONE);
        showcaseRating.setVisibility(View.GONE);
        showcaseImage.setAlpha(1f);
        showcaseImage.setImageDrawable(null);
        showcaseImage.setTag(null);
        featuredTitle.setText(filter == Filter.SHOWS ? R.string.media_rail_empty_shows
                : filter == Filter.MOVIES ? R.string.media_rail_empty_movies
                : R.string.media_rail_empty);
        featuredSub.setText("");
        featuredOverview.setText("");
        host.onItemFocused(null);   // clear the full-screen backdrop; nothing to show behind an empty tab
    }

    private void bindFeatured(MediaItem it) {
        heroItem = it;
        showcaseImage.setAlpha(1f);   // restore full art (the Show-more state dims it)
        if (it == null) {
            featuredTitle.setText("");
            featuredSub.setText("");
            showcaseImage.setImageDrawable(null);
            showcaseImage.setTag(null);
            showcaseRating.setVisibility(View.GONE);
            playButton.setVisibility(View.GONE);
            return;
        }
        loadImage(showcaseImage, wideArt(it));
        maybeFetchBackdrop(it);   // the provider gave only a poster: fill a wide 16:9 backdrop from TMDB if we can
        featuredTitle.setText(it.isSeries ? it.title : it.spotlightTitle());
        playButton.setVisibility(View.VISIBLE);
        playButton.setContentDescription(context.getString(R.string.media_rail_play) + " " + it.spotlightTitle());
        TmdbClient.Info info = metadataCache.get(metadataKey(it));
        showcaseRating.setVisibility(View.GONE);
        featuredSub.setText(detailLine(it, info));
        featuredOverview.setText(info == null ? "" : info.overview);
        maybeFetchMetadata(it);
    }

    /** Detail line under the title: "year" or "N new episodes", then the age rating when known, the source and when
     *  it was added. */
    private CharSequence detailLine(MediaItem it, TmdbClient.Info info) {
        List<String> parts = new ArrayList<>();
        if (it.isSeries) {
            parts.add(it.title);
            parts.add(newEpisodesText(it.newEpisodeCount));
        }
        int year = it.year > 0 ? it.year : info == null ? 0 : info.year;
        if (year > 0) parts.add(String.valueOf(year));
        if (info != null) {
            String runtime = TmdbClient.runtimeText(info.runtime);
            if (!runtime.isEmpty()) parts.add(runtime);
        }
        parts.add(it.source == MediaItem.Source.DOWNLOAD ? badgeDownload : badgeStream);
        String added = addedText(it.added);
        if (!added.isEmpty()) parts.add(added);
        SpannableStringBuilder line = new SpannableStringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) line.append("  \u00B7  ");
            line.append(parts.get(i));
        }
        if (info != null) {
            String certification = TmdbClient.ratingChip(info.certification);
            if (!certification.isEmpty()) {
                if (line.length() > 0) line.append("  ");
                int start = line.length();
                line.append(certification);
                line.setSpan(new RoundedBadgeSpan(context, false), start, line.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (info.rating > 0) {
                if (line.length() > 0) line.append("  ");
                int start = line.length();
                line.append("TMDb");
                line.setSpan(new RoundedBadgeSpan(context, true), start, line.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                line.append(" ").append(String.format(java.util.Locale.US, "%.1f", info.rating));
            }
        }
        return line;
    }

    private static String metadataKey(MediaItem it) {
        return (it.isSeries ? "tv|" : "mv|") + it.title + "|" + it.year;
    }

    /**
     * Runtime, age rating and score for the showcased title, from TMDB and cached per title and year.
     */
    private void maybeFetchMetadata(MediaItem it) {
        if (tmdb == null || it == null) return;
        String key = metadataKey(it);
        if (metadataCache.containsKey(key) || !metadataLoading.add(key)) return;
        final String title = it.title;
        final int year = it.year;
        final boolean series = it.isSeries;
        metadataPool.execute(() -> {
            TmdbClient.Info result;
            try {
                result = tmdb.lookup(title, year, series, 0);
            } catch (Exception e) {
                main.post(() -> metadataLoading.remove(key));
                return;
            }
            if (result == null) {
                main.post(() -> metadataLoading.remove(key));
                return;
            }
            main.post(() -> {
                metadataLoading.remove(key);
                if (released) return;
                metadataCache.put(key, result);
                for (MediaItem item : allItems) {
                    if (metadataKey(item).equals(key)) item.certification = result.certification;
                }
                if (isSelected(it)) {
                    showcaseRating.setVisibility(View.GONE);
                    featuredSub.setText(detailLine(it, result));
                    featuredOverview.setText(result.overview);
                }
                adapter.notifyDataSetChanged();
            });
        });
    }

    private String newEpisodesText(int count) {
        Resources res = context.getResources();
        return count == 1 ? res.getString(R.string.media_rail_new_episode_one)
                : res.getString(R.string.media_rail_new_episodes, count);
    }


    private String addedText(long added) {
        if (added <= 0) return "";
        long days = (System.currentTimeMillis() / 1000 - added) / (24 * 60 * 60);
        Resources res = context.getResources();
        if (days <= 0) return res.getString(R.string.media_rail_added_today);
        if (days == 1) return res.getString(R.string.media_rail_added_yesterday);
        return res.getString(R.string.media_rail_added_days, (int) days);
    }

    // ---- images ----------------------------------------------------------------------------------

    private void loadImage(ImageView view, String url) {
        view.setTag(url);
        if (url == null || url.isEmpty()) {
            view.setImageDrawable(null);
            return;
        }
        Bitmap hit = images.get(url);
        if (hit != null) {
            view.setImageBitmap(hit);
            return;
        }
        view.setImageDrawable(null);
        imagePool.execute(() -> {
            Bitmap bmp = RailImages.fetch(url, ART_DECODE_W);
            if (bmp == null) return;
            images.put(url, bmp);
            main.post(() -> {
                if (url.equals(view.getTag())) view.setImageBitmap(bmp);
            });
        });
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    private static TextView ratingBadge(Context context) {
        TextView rating = new TextView(context);
        rating.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        rating.setTextColor(Color.WHITE);
        rating.setTypeface(UiFont.get(context, R.string.font_medium));
        rating.setGravity(Gravity.CENTER);
        rating.setIncludeFontPadding(false);
        rating.setSingleLine(true);
        rating.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dpValue(context, 4));
        bg.setColor(0xCC000000);
        bg.setStroke(dpValue(context, 1), 0xCCFFFFFF);
        rating.setBackground(bg);
        rating.setVisibility(View.GONE);
        return rating;
    }

    private static int dpValue(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static void bindRating(TextView view, MediaItem item) {
        bindRating(view, item, null);
    }

    private static void bindRating(TextView view, MediaItem item, TmdbClient.Info info) {
        String rating = info == null ? item.certification : info.certification;
        rating = TmdbClient.ratingChip(rating);
        view.setText(rating);
        view.setVisibility(rating.isEmpty() ? View.GONE : View.VISIBLE);
    }

    // ---- cards -----------------------------------------------------------------------------------

    private static final int TYPE_MEDIA = 0;

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        private final List<MediaItem> items = new ArrayList<>();
        private final boolean showsOnly;
        /** First item index the strip shows: it previews everything from here on (the selection + 1), so the
         *  selected item is never duplicated as a peek. When start == items.size() the strip is empty — the
         *  "Show more" state lives only in the showcase, never as a strip tile. */
        private int start;

        Adapter(boolean showsOnly) {
            this.showsOnly = showsOnly;
            setHasStableIds(true);
        }

        void setItems(List<MediaItem> next) {
            boolean same = next.size() == items.size();
            for (int i = 0; same && i < next.size(); i++) {
                same = next.get(i).id.equals(items.get(i).id);
            }
            if (same) return;
            items.clear();
            items.addAll(next);
            notifyDataSetChanged();
        }

        /** Moves the strip's first previewed item; the showcase holds everything before it. */
        void setStart(int newStart) {
            int clamped = Math.max(0, Math.min(items.size(), newStart));
            if (clamped == start) return;
            start = clamped;
            notifyDataSetChanged();
            (showsOnly ? showsRow : row).scrollToPosition(0);
        }

        /** Preview items shown: those from {@code start} to the end. The strip is media posters only — there is
         *  no "Show more" tile; that state lives in the showcase. */
        private int previewCount() {
            return Math.max(0, items.size() - start);
        }

        /** Strip position -> real item index. */
        private int itemIndexFor(int position) {
            return start + position;
        }

        @Override public long getItemId(int position) {
            return items.get(itemIndexFor(position)).id.hashCode();
        }

        @Override public int getItemViewType(int position) {
            return TYPE_MEDIA;
        }

        @Override public int getItemCount() {
            return previewCount();
        }

        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(buildCard(), viewType);
        }

        @Override public void onBindViewHolder(@NonNull Holder h, int position) {
            // Preview-only cards: never focusable, never clickable — the showcase is the one interactive element.
            MediaItem it = items.get(itemIndexFor(position));
            h.itemView.setContentDescription(null);
            boolean series = it.isSeries;
            h.name.setText(it.spotlightTitle());
            h.name.setVisibility(series || it.poster == null || it.poster.isEmpty() ? View.VISIBLE : View.INVISIBLE);
            h.badge.setText(it.source == MediaItem.Source.DOWNLOAD ? badgeDownload : badgeStream);
            maybeFetchMetadata(it);
            if (h.sub != null) {
                h.sub.setVisibility(series ? View.VISIBLE : View.GONE);
                if (series) h.sub.setText(newEpisodesText(it.newEpisodeCount));
            }
            if (h.accent != null) h.accent.setVisibility(series ? View.VISIBLE : View.GONE);
            loadImage(h.image, it.poster != null && !it.poster.isEmpty() ? it.poster : wideArt(it));
            h.itemView.setOnClickListener(v -> open(it, h.itemView));
            h.itemView.setOnKeyListener((v, keyCode, event) -> onCardKey(keyCode, event, showsOnly, h, it));
            h.itemView.setOnFocusChangeListener((v, focused) -> {
                float scale = focused ? 1.07f : 1f;
                v.animate().scaleX(scale).scaleY(scale).setDuration(120).start();
                v.setElevation(focused ? dp(5) : 0f);
                if (focused) {
                    if (!showsOnly) armMovieFocusTimeout();
                    host.onRailFocused();
                    gridAtTop = true;
                    edgeLeftArmed = false;
                        if (!it.isSeries) {
                            int index = spotlightItems.indexOf(it);
                            if (index >= 0) selected = index;
                        }
                    bindFeatured(it);
                    host.onItemFocused(it);
                    idleMs = 0;
                    main.removeCallbacks(rotateTick);
                    main.postDelayed(rotateTick, ROTATE_BEAT_MS);
                }
            });
        }
    }

    /** A preview card holder: plain art, no focus or key handling (the showcase owns all input). */
    private final class Holder extends RecyclerView.ViewHolder {
        final ImageView image;
        final TextView name;
        final TextView badge;
        final TextView sub;
        final TextView rating;
        final View accent;
        final int type;

        Holder(View card, int type) {
            super(card);
            this.type = type;
            image = card.findViewWithTag("image");
            name = card.findViewWithTag("name");
            badge = card.findViewWithTag("badge");
            sub = card.findViewWithTag("sub");
            rating = card.findViewWithTag("rating");
            accent = card.findViewWithTag("accent");
            if (image != null) image.setTag(null);
            if (name != null) name.setTag(null);
            if (badge != null) badge.setTag(null);
            if (sub != null) sub.setTag(null);
            if (rating != null) rating.setTag(null);
            if (accent != null) accent.setTag(null);
        }
    }

    /** One peek card: a rounded 2:3 poster, a source badge in the top-left corner. Preview only — not focusable. */
    private View buildCard() {
        FrameLayout card = new FrameLayout(context);
        card.setFocusable(true);
        card.setFocusableInTouchMode(false);
        card.setClipToPadding(false);
        card.setClipChildren(false);
        card.setForeground(showcaseRing());
        RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(dp(CARD_W), dp(CARD_H));
        lp.setMarginEnd(dp(GAP));
        card.setLayoutParams(lp);

        GradientDrawable placeholder = new GradientDrawable();
        placeholder.setCornerRadius(dp(8));
        placeholder.setColor(0xFF1E1E1E);

        TextView name = new TextView(context);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        name.setTextColor(0xB3FFFFFF);
        name.setGravity(Gravity.CENTER);
        name.setMaxLines(4);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(dp(6), dp(6), dp(6), dp(6));
        name.setTypeface(UiFont.get(context, R.string.font_regular));
        name.setBackground(placeholder);
        name.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        name.setTag("name");
        card.addView(name, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        ImageView image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        GradientDrawable clip = new GradientDrawable();
        clip.setCornerRadius(dp(8));
        clip.setColor(Color.TRANSPARENT);
        image.setBackground(clip);
        image.setClipToOutline(true);
        image.setTag("image");
        card.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // Series accent: a thin bar along the bottom edge, drawn under the "N new episodes" line.
        View accent = new View(context);
        GradientDrawable accentBg = new GradientDrawable();
        accentBg.setCornerRadii(new float[] {0, 0, 0, 0, dp(8), dp(8), dp(8), dp(8)});
        accentBg.setColor(0xE6176B5A);
        accent.setBackground(accentBg);
        accent.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        accent.setVisibility(View.GONE);
        accent.setTag("accent");
        FrameLayout.LayoutParams alp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(22));
        alp.gravity = Gravity.BOTTOM;
        card.addView(accent, alp);

        TextView sub = new TextView(context);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8);
        sub.setTextColor(Color.WHITE);
        sub.setTypeface(UiFont.get(context, R.string.font_medium));
        sub.setIncludeFontPadding(false);
        sub.setGravity(Gravity.CENTER);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        sub.setPadding(dp(4), dp(2), dp(4), dp(3));
        sub.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        sub.setVisibility(View.GONE);
        sub.setTag("sub");
        FrameLayout.LayoutParams subLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(22));
        subLp.gravity = Gravity.BOTTOM;
        card.addView(sub, subLp);

        TextView badge = new TextView(context);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8);
        badge.setTextColor(Color.WHITE);
        badge.setTypeface(UiFont.get(context, R.string.font_medium));
        badge.setPadding(dp(5), dp(1), dp(5), dp(2));
        badge.setIncludeFontPadding(false);
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setCornerRadius(dp(4));
        badgeBg.setColor(0xCC000000);
        badge.setBackground(badgeBg);
        badge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        badge.setTag("badge");
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.gravity = Gravity.TOP | Gravity.START;
        blp.setMargins(dp(4), dp(4), 0, 0);
        card.addView(badge, blp);
        return card;
    }

    private TextView sectionLabel(int fallbackRes, String label) {
        TextView text = new TextView(context);
        text.setText(label);
        if (fallbackRes != 0 && context.getString(fallbackRes).isEmpty()) {
            text.setText(fallbackRes);
        }
        text.setTextColor(0xE6FFFFFF);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        text.setTypeface(UiFont.get(context, R.string.font_medium));
        text.setLetterSpacing(0.04f);
        return text;
    }

    private boolean onCardKey(int keyCode, KeyEvent event, boolean fromShows, Holder holder, MediaItem item) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN;
        }
        if (!fromShows) armMovieFocusTimeout();
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            focusPlay();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            open(item, null);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            RecyclerView owner = fromShows ? showsRow : row;
            int position = holder.getBindingAdapterPosition();
            if (position == RecyclerView.NO_POSITION) return true;
            int delta = keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1;
            int next = position + delta;
            int count = owner.getAdapter() == null ? 0 : owner.getAdapter().getItemCount();
            if (next >= 0 && next < count) {
                edgeLeftArmed = false;
                owner.scrollToPosition(next);
                owner.post(() -> {
                    RecyclerView.ViewHolder nextHolder = owner.findViewHolderForAdapterPosition(next);
                    if (nextHolder != null) nextHolder.itemView.requestFocus();
                });
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (edgeLeftArmed) {
                    edgeLeftArmed = false;
                    host.onRailMenu();
                } else {
                    edgeLeftArmed = true;
                }
            }
            return true;
        }
        return false;
    }

    private void focusFirst(RecyclerView target) {
        container.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        gridAtTop = true;
        target.postOnAnimation(new Runnable() {
            int tries;
            @Override public void run() {
                Section wanted = target == row ? Section.MOVIES : Section.SHOWS;
                if (released || section != wanted) return;
                RecyclerView.ViewHolder holder = target.findViewHolderForAdapterPosition(0);
                if (holder != null && holder.itemView.requestFocus()) {
                    if (target == row) armMovieFocusTimeout();
                    return;
                }
                // A slow first layout (bigger rail, slower TV) took longer than a few frames and left nothing focused.
                if (tries++ < 60 && target.getAdapter() != null
                        && target.getAdapter().getItemCount() > 0) {
                    target.requestLayout();
                    target.postOnAnimation(this);
                }
            }
        });
    }

    private void applySectionRows() {
        View[] views = {recentLabel, row, showsLabel, showsRow};
        for (View view : views) {
            view.animate().cancel();
            view.setTranslationY(0f);
            view.setAlpha(1f);
        }
        recentLabel.setVisibility(section == Section.MOVIES ? View.VISIBLE : View.GONE);
        row.setVisibility(section == Section.MOVIES ? View.VISIBLE : View.GONE);
        showsLabel.setVisibility(section == Section.SHOWS ? View.VISIBLE : View.GONE);
        showsRow.setVisibility(section == Section.SHOWS ? View.VISIBLE : View.GONE);
        resizeContainer();
    }

    private void resizeContainer() {
        ViewGroup.LayoutParams lp = container.getLayoutParams();
        if (lp != null && lp.height != height()) {
            lp.height = height();
            container.setLayoutParams(lp);
        }
    }

    private void switchRail(boolean shows, boolean focus) {
        Section target = shows ? Section.SHOWS : Section.MOVIES;
        if (section == target) {
            if (focus) focusFirst(shows ? showsRow : row);
            return;
        }
        if (section == Section.DASHBOARD) {
            section = target;
            applySectionRows();
            if (focus) focusFirst(shows ? showsRow : row);
            return;
        }
        section = target;
        resizeContainer();
        if (shows) {
            main.removeCallbacks(clearMovieFocus);
            main.removeCallbacks(clearDashboardPlayFocus);
        }
        View outgoingLabel = shows ? recentLabel : showsLabel;
        View outgoingRow = shows ? row : showsRow;
        View incomingLabel = shows ? showsLabel : recentLabel;
        View incomingRow = shows ? showsRow : row;
        float direction = shows ? -1f : 1f;
        incomingLabel.setVisibility(View.VISIBLE);
        incomingRow.setVisibility(View.VISIBLE);
        incomingLabel.setTranslationX(0f);
        incomingRow.setTranslationX(0f);
        incomingLabel.setAlpha(0f);
        incomingRow.setAlpha(0f);
        incomingLabel.setTranslationY(-direction * dp(12));
        incomingRow.setTranslationY(-direction * dp(12));
        outgoingLabel.animate().alpha(0f).translationY(direction * dp(12)).setDuration(160).start();
        outgoingRow.animate().alpha(0f).translationY(direction * dp(12)).setDuration(160)
                .withEndAction(() -> {
                    outgoingLabel.setVisibility(View.GONE);
                    outgoingRow.setVisibility(View.GONE);
                    outgoingLabel.setTranslationY(0f);
                    outgoingRow.setTranslationY(0f);
                    outgoingLabel.setAlpha(1f);
                    outgoingRow.setAlpha(1f);
                    incomingLabel.animate().alpha(1f).translationY(0f).setDuration(180).start();
                    incomingRow.animate().alpha(1f).translationY(0f).setDuration(180)
                            .withEndAction(() -> {
                                if (focus && section == target) {
                                    focusFirst(shows ? showsRow : row);
                                }
                            }).start();
                }).start();
    }

    /** The white focus ring drawn over the showcase while it holds focus (its rounded corners match the box). */
    private StateListDrawable showcaseRing() {
        GradientDrawable on = new GradientDrawable();
        on.setCornerRadius(dp(12));
        on.setColor(Color.TRANSPARENT);
        on.setStroke(dp(3), ColorStateList.valueOf(Color.WHITE));
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[] {android.R.attr.state_focused}, on);
        s.addState(new int[] {}, new GradientDrawable());
        return s;
    }

    private StateListDrawable playButtonBackground() {
        GradientDrawable focused = new GradientDrawable();
        focused.setCornerRadius(dp(100));
        focused.setColor(Color.WHITE);
        GradientDrawable rest = new GradientDrawable();
        rest.setCornerRadius(dp(100));
        rest.setColor(0x33FFFFFF);
        rest.setStroke(dp(1), 0x99FFFFFF);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] {android.R.attr.state_focused}, focused);
        states.addState(new int[] {}, rest);
        return states;
    }

    /**
     * A {@link FrameLayout} that fades its contents out along the left edge. The strip scrolls inside it, so a
     * poster sliding toward the showcase dissolves to transparent over {@code fadeWidth} px instead of hard-clipping
     * or tucking behind the showcase. Done by drawing the children into an offscreen layer, then multiplying a
     * left-to-right opacity ramp onto it with {@link PorterDuff.Mode#DST_IN} — purely local, no network, no bitmaps.
     */
    private static final class FadeEdgeFrame extends FrameLayout {
        private final Paint fadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int fadeWidth;
        private LinearGradient shader;
        private int shaderWidth = -1;

        FadeEdgeFrame(Context context, int fadeWidth) {
            super(context);
            this.fadeWidth = fadeWidth;
            // DST_IN keeps the destination (the strip) only where the source (the ramp) is opaque; the ramp is
            // transparent at the very left and opaque past fadeWidth, so the left edge fades out.
            fadePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
            // An offscreen layer is required for a cross-child mask; without hardware layers the DST_IN would only
            // affect each child's own draw. A software layer here is cheap for one short strip.
            setLayerType(LAYER_TYPE_HARDWARE, null);
        }

        @Override protected void dispatchDraw(Canvas canvas) {
            if (fadeWidth <= 0 || getWidth() <= 0) {
                super.dispatchDraw(canvas);
                return;
            }
            int saved = canvas.saveLayer(0, 0, getWidth(), getHeight(), null);
            super.dispatchDraw(canvas);
            if (shader == null || shaderWidth != getWidth()) {
                // Opaque (keep) on the right, transparent (drop) at x=0, ramping across fadeWidth.
                shader = new LinearGradient(0, 0, fadeWidth, 0,
                        0x00000000, 0xFF000000, Shader.TileMode.CLAMP);
                shaderWidth = getWidth();
                fadePaint.setShader(shader);
            }
            canvas.drawRect(0, 0, fadeWidth, getHeight(), fadePaint);
            canvas.restoreToCount(saved);
        }
    }
}
