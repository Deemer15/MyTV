package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The full-screen "Explore" page, opened from the Explore tab on Home. A huge backdrop fills the screen with the
 * focused title's logo, rating, year and overview on the left and a Play button. A tab row (Movies / Shows) sits at
 * the top; one poster rail at the bottom shows the selected tab. Focusing a show and pressing OK (or Down) turns
 * the rail into that show's episodes, each a wide still with its own TMDB name and overview; OK on an episode plays
 * it. After a few seconds idle the rail auto-advances with a soft fade; any keypress resets the dwell. Back leaves
 * episode mode, then Explore.
 *
 * <p>Movies = TMDB trending-this-week kept only when the IPTV provider or the download library has it, topped up
 * with the newest Local/IPTV movies. Shows = every downloaded series. Built in code, light for 1 GB TVs.
 */
public class ExploreActivity extends Activity {
    private static final String TAG = "ExploreActivity";
    private static final int TRENDING_SCAN = 40;
    private static final int MOVIES_TARGET = 30;
    private static final int CARD_W = 120, CARD_H = 180, GAP = 16;
    private static final int EP_W = 300, EP_H = 170;   // wide episode still card
    private static final float FOCUS_SCALE = 1.08f;
    private static final int IMAGE_W = CARD_W * 2;
    private static final long ROTATE_MS = 10_000, BEAT_MS = 1_000, FADE_MS = 420;
    private static final int BANNER_H = 400;
    private static final int ROW_BAND = CARD_H + 36;
    private static final int EP_BAND = EP_H + 64;      // episode still + its two text lines
    private static final Pattern SXXEYY = Pattern.compile("[Ss](\\d{1,2})[ ._-]?[Ee](\\d{1,3})");

    private float density;
    private BeastClient beast;
    private IptvClient iptv;
    private TmdbClient tmdb;
    private String downloadPlayer, streamPlayer, badgeDownload, badgeStream;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService imagePool = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> images = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };

    // Banner
    private ImageView backdrop;
    private ImageView logo;
    private TextView bannerTitle, bannerMeta, bannerOverview;
    private MediaItem featured;
    private String backdropKey = "";

    // Tabs + rail
    private enum Tab { MOVIES, SHOWS }
    private LinearLayout tabRow;
    private TextView tabMovies, tabShows;
    private Tab tab = Tab.MOVIES;
    private FrameLayout railStack;
    private Rail titleRail;         // the Movies/Shows poster rail
    private Rail episodeRail;       // a focused show's episodes; null-content when not in episode mode
    private boolean episodeMode;
    private MediaItem episodeShow;  // the series whose episodes are shown

    private List<MediaItem> movieItems = new ArrayList<>();
    private List<MediaItem> showItems = new ArrayList<>();

    // TMDB banner-detail cache by "mv|key" / "tv|key"; episode-text cache by show id -> (season,episode)->info.
    private final Map<String, TmdbClient.Info> infoCache = new HashMap<>();
    private final Map<String, Map<String, TmdbClient.EpisodeInfo>> seasonCache = new HashMap<>();

    private boolean resumed;
    private long idleMs;
    private final Runnable beat = this::rotateBeat;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Resources res = getResources();
        density = res.getDisplayMetrics().density;
        downloadPlayer = res.getString(R.string.media_rail_download_player).trim();
        streamPlayer = res.getString(R.string.media_rail_stream_player).trim();
        badgeDownload = res.getString(R.string.media_rail_badge_download);
        badgeStream = res.getString(R.string.media_rail_badge_stream);

        String bBase = secret("beast_api_base"), bKey = secret("beast_api_key");
        String bRoot = secret("beast_library_root"), bMedia = secret("beast_media_base");
        if (!bBase.isEmpty() && !bKey.isEmpty() && !bRoot.isEmpty() && !bMedia.isEmpty()) {
            beast = new BeastClient(bBase, bKey, bRoot, bMedia);
        }
        String iServer = secret("iptv_server"), iUser = secret("iptv_username"), iPass = secret("iptv_password");
        if (!iServer.isEmpty() && !iUser.isEmpty() && !iPass.isEmpty()) {
            while (iServer.endsWith("/")) iServer = iServer.substring(0, iServer.length() - 1);
            iptv = new IptvClient(iServer, iUser, iPass);
        }
        String tKey = secret("tmdb_api_key");
        if (!tKey.isEmpty()) tmdb = new TmdbClient(tKey);

        setContentView(buildUi());
        tabRow.post(() -> tabRow.requestFocus());   // default focus: the Movies tab (the selected tab)
        loadMovies();
        loadShows();
    }

    private String secret(String name) {
        int id = getResources().getIdentifier(name, "string", getPackageName());
        return id == 0 ? "" : getString(id).trim();
    }

    // ---- UI ----------------------------------------------------------------------------------------

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF07090D);

        backdrop = new ImageView(this);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        backdrop.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(backdrop, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        View left = new View(this);
        left.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {0xF207090D, 0x9907090D, 0x3307090D, 0x00000000}));
        left.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(left, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View bottom = new View(this);
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[] {0xF507090D, 0xCC07090D, 0x6607090D, 0x00000000}));
        bottom.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(bottom, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(buildBanner(), topLp(dp(BANNER_H)));
        root.addView(buildTabRow(), topLp(ViewGroup.LayoutParams.WRAP_CONTENT));

        railStack = new FrameLayout(this);
        railStack.setClipChildren(false);
        railStack.setClipToPadding(false);
        railStack.setPadding(dp(48), 0, 0, dp(30));
        titleRail = new Rail();
        episodeRail = new Rail();
        episodeRail.view.setVisibility(View.GONE);
        railStack.addView(titleRail.view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_BAND)));
        railStack.addView(episodeRail.view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(EP_BAND)));
        FrameLayout.LayoutParams stackLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(EP_BAND));
        stackLp.gravity = Gravity.BOTTOM;
        root.addView(railStack, stackLp);

        return root;
    }

    private FrameLayout.LayoutParams topLp(int height) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, height);
        lp.gravity = Gravity.TOP;
        return lp;
    }

    private View buildTabRow() {
        tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setPadding(dp(56), dp(28), dp(56), 0);
        tabRow.setFocusable(true);
        tabRow.setFocusableInTouchMode(false);
        tabRow.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        tabRow.setOnFocusChangeListener((v, f) -> { paintTabs(); rescheduleRotation(); });
        tabRow.setOnKeyListener((v, keyCode, event) -> onTabKey(keyCode, event));
        tabMovies = tabLabel(getString(R.string.explore_row_movies));
        tabShows = tabLabel(getString(R.string.explore_row_shows));
        tabRow.addView(tabMovies);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.leftMargin = dp(28);
        tabRow.addView(tabShows, sp);
        paintTabs();
        return tabRow;
    }

    private TextView tabLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        t.setLetterSpacing(0.04f);
        t.setTypeface(UiFont.get(this, R.string.font_medium));
        t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return t;
    }

    private void paintTabs() {
        boolean rowFocused = tabRow.isFocused();
        tabMovies.setTextColor(tab == Tab.MOVIES ? Color.WHITE : 0x80FFFFFF);
        tabShows.setTextColor(tab == Tab.SHOWS ? Color.WHITE : 0x80FFFFFF);
        tabMovies.setAlpha(tab == Tab.MOVIES ? (rowFocused ? 1f : 0.95f) : 0.7f);
        tabShows.setAlpha(tab == Tab.SHOWS ? (rowFocused ? 1f : 0.95f) : 0.7f);
    }

    private boolean onTabKey(int keyCode, KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return keyCode == KeyEvent.KEYCODE_DPAD_DOWN;
        }
        boolean rtl = tabRow.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return moveTab(rtl ? +1 : -1);
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return moveTab(rtl ? -1 : +1);
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                focusTitleRail();
                return true;
            default:
                return false;
        }
    }

    private boolean moveTab(int delta) {
        Tab next = delta < 0 ? Tab.MOVIES : Tab.SHOWS;
        if (next == tab) return true;
        tab = next;
        paintTabs();
        leaveEpisodeMode(false);
        titleRail.setItems(tab == Tab.MOVIES ? movieItems : showItems,
                tab == Tab.MOVIES ? getString(R.string.explore_movies_empty)
                        : getString(R.string.explore_shows_empty));
        if (titleRail.hasItems()) showFeatured(titleRail.itemAt(0));
        rescheduleRotation();
        return true;
    }

    private View buildBanner() {
        LinearLayout banner = new LinearLayout(this);
        banner.setOrientation(LinearLayout.VERTICAL);
        banner.setPadding(dp(56), dp(84), dp(56), 0);
        banner.setClipChildren(false);

        logo = new ImageView(this);
        logo.setScaleType(ImageView.ScaleType.FIT_START);
        logo.setAdjustViewBounds(true);
        logo.setVisibility(View.GONE);
        logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(dp(360), dp(92));
        banner.addView(logo, logoLp);

        bannerTitle = new TextView(this);
        bannerTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 38);
        bannerTitle.setTextColor(Color.WHITE);
        bannerTitle.setMaxLines(2);
        bannerTitle.setEllipsize(TextUtils.TruncateAt.END);
        bannerTitle.setTypeface(UiFont.get(this, R.string.font_light));
        banner.addView(bannerTitle, new LinearLayout.LayoutParams(
                dp(640), ViewGroup.LayoutParams.WRAP_CONTENT));

        bannerMeta = new TextView(this);
        bannerMeta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        bannerMeta.setTextColor(0xCCFFFFFF);
        bannerMeta.setSingleLine(true);
        bannerMeta.setTypeface(UiFont.get(this, R.string.font_regular));
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        metaLp.topMargin = dp(12);
        banner.addView(bannerMeta, metaLp);

        bannerOverview = new TextView(this);
        bannerOverview.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        bannerOverview.setTextColor(0xB8FFFFFF);
        bannerOverview.setMaxLines(3);
        bannerOverview.setEllipsize(TextUtils.TruncateAt.END);
        bannerOverview.setLineSpacing(dp(3), 1f);
        bannerOverview.setTypeface(UiFont.get(this, R.string.font_regular));
        LinearLayout.LayoutParams ovLp = new LinearLayout.LayoutParams(dp(560),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        ovLp.topMargin = dp(12);
        banner.addView(bannerOverview, ovLp);
        return banner;
    }

    // ---- data --------------------------------------------------------------------------------------

    private void loadMovies() {
        if (beast == null && iptv == null) return;
        worker.execute(() -> {
            List<MediaItem> result = buildMovies();
            main.post(() -> {
                if (isFinishing()) return;
                movieItems = result;
                if (tab == Tab.MOVIES) {
                    titleRail.setItems(result, getString(R.string.explore_movies_empty));
                    if (titleRail.hasItems() && featured == null) {
                        showFeatured(titleRail.itemAt(0));
                        main.post(() -> { paintTabs(); tabRow.requestFocus(); });
                    }
                }
            });
        });
    }

    private List<MediaItem> buildMovies() {
        List<MediaItem> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        List<MediaItem> localMovies = new ArrayList<>();
        List<MediaItem> iptvNewest = new ArrayList<>();
        try {
            if (beast != null) localMovies.addAll(beast.allMovies(downloadPlayer));
        } catch (Exception e) {
            Log.w(TAG, "beast movies failed: " + e.getClass().getSimpleName());
        }
        try {
            if (iptv != null) iptvNewest = iptv.newestMedia(MOVIES_TARGET * 2, java.util.Collections.emptyList(), streamPlayer);
        } catch (Exception e) {
            Log.w(TAG, "iptv newest failed: " + e.getClass().getSimpleName());
        }
        Map<String, MediaItem> byKey = new HashMap<>();
        for (MediaItem m : iptvNewest) byKey.put(m.matchKey, m);
        for (MediaItem m : localMovies) byKey.put(m.matchKey, m);
        try {
            if (tmdb != null) {
                for (TmdbMovie t : tmdb.trendingWeek(TRENDING_SCAN)) {
                    MediaItem have = byKey.get(MediaItem.key(t.title, t.year));
                    if (have == null) have = byKey.get(MediaItem.key(t.title, 0));
                    if (have == null || !seen.add(have.matchKey)) continue;
                    if (have.backdrop.isEmpty()) {
                        String bd = TmdbClient.backdropUrl(t.backdropPath);
                        if (bd != null) have.backdrop = bd;
                    }
                    out.add(have);
                    if (out.size() >= MOVIES_TARGET) break;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "trending failed: " + e.getClass().getSimpleName());
        }
        for (MediaItem m : localMovies) {
            if (out.size() >= MOVIES_TARGET) break;
            if (seen.add(m.matchKey)) out.add(m);
        }
        for (MediaItem m : iptvNewest) {
            if (out.size() >= MOVIES_TARGET) break;
            if (seen.add(m.matchKey)) out.add(m);
        }
        return out;
    }

    private void loadShows() {
        if (beast == null) return;
        worker.execute(() -> {
            List<MediaItem> items = new ArrayList<>();
            try {
                items.addAll(beast.allSeries(downloadPlayer));
            } catch (Exception e) {
                Log.w(TAG, "beast series failed: " + e.getClass().getSimpleName());
            }
            main.post(() -> {
                if (isFinishing()) return;
                showItems = items;
                if (tab == Tab.SHOWS) {
                    titleRail.setItems(items, getString(R.string.explore_shows_empty));
                    if (titleRail.hasItems() && featured == null) showFeatured(titleRail.itemAt(0));
                }
            });
        });
    }

    // ---- banner ------------------------------------------------------------------------------------

    private void showFeatured(MediaItem it) {
        featured = it;
        if (it == null) return;
        bannerTitle.setText(it.title);
        bannerTitle.setVisibility(View.VISIBLE);
        logo.setVisibility(View.GONE);
        String year = it.year > 0 ? String.valueOf(it.year) : "";
        String badge = it.source == MediaItem.Source.DOWNLOAD ? badgeDownload : badgeStream;
        bannerMeta.setText(join(it.isSeries ? getString(R.string.explore_type_show)
                : getString(R.string.explore_type_movie), year, badge));
        bannerOverview.setText("");
        setBackdrop(it.backdrop.isEmpty() ? it.poster : it.backdrop);
        fetchInfo(it);
    }

    private void fetchInfo(MediaItem it) {
        if (tmdb == null) return;
        String key = (it.isSeries ? "tv|" : "mv|") + it.matchKey;
        TmdbClient.Info cached = infoCache.get(key);
        if (cached != null) {
            applyInfo(it, cached);
            return;
        }
        worker.execute(() -> {
            TmdbClient.Info info = null;
            try {
                info = tmdb.lookup(it.title, it.year, it.isSeries, 0);
            } catch (Exception e) {
                Log.w(TAG, "info lookup failed: " + e.getClass().getSimpleName());
            }
            final TmdbClient.Info result = info;
            main.post(() -> {
                if (result != null) infoCache.put(key, result);
                if (result != null && featured == it && !episodeMode) applyInfo(it, result);
            });
        });
    }

    private void applyInfo(MediaItem it, TmdbClient.Info info) {
        if (featured != it || episodeMode) return;
        if (!info.overview.isEmpty()) bannerOverview.setText(info.overview);
        int y = it.year > 0 ? it.year : info.year;
        String year = y > 0 ? String.valueOf(y) : "";
        String rating = info.rating > 0 ? String.format(java.util.Locale.US, "\u2605 %.1f", info.rating) : "";
        String badge = it.source == MediaItem.Source.DOWNLOAD ? badgeDownload : badgeStream;
        bannerMeta.setText(join(it.isSeries ? getString(R.string.explore_type_show)
                : getString(R.string.explore_type_movie), year, rating, badge));
        if (!info.logo.isEmpty()) {
            loadInto(logo, info.logo, () -> {
                if (featured == it && !episodeMode) {
                    logo.setVisibility(View.VISIBLE);
                    bannerTitle.setVisibility(View.GONE);
                }
            });
        }
        if (it.backdrop.isEmpty() && !info.backdrop.isEmpty()) {
            it.backdrop = info.backdrop;
            setBackdrop(info.backdrop);
        }
    }

    /** The banner while an episode is focused: the show's logo/title stays; name, meta and overview become the
     *  episode's. */
    private void showEpisode(MediaItem show, BeastClient.Episode ep, TmdbClient.EpisodeInfo info) {
        int[] se = seasonEpisode(ep.label);
        String epName = info != null && !info.name.isEmpty() ? info.name : episodeTitleFallback(ep, se);
        bannerTitle.setText(epName);
        bannerTitle.setVisibility(logo.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        String seLabel = se != null ? String.format(java.util.Locale.US, "S%02dE%02d", se[0], se[1]) : "";
        bannerMeta.setText(join(show.title, seLabel));
        bannerOverview.setText(info != null && !info.overview.isEmpty()
                ? info.overview : getString(R.string.explore_no_episode_overview));
        if (info != null && !info.still.isEmpty()) setBackdrop(info.still);
    }

    private String episodeTitleFallback(BeastClient.Episode ep, int[] se) {
        if (ep.label != null) {
            int dot = ep.label.indexOf(" \u00B7 ");
            if (dot >= 0) return ep.label.substring(dot + 3);
        }
        return se != null ? String.format(java.util.Locale.US, "Episode %d", se[1]) : "Episode";
    }

    private void setBackdrop(String url) {
        if (url == null || url.isEmpty() || url.equals(backdropKey)) return;
        backdropKey = url;
        imagePool.execute(() -> {
            Bitmap art = RailImages.fetch(url, 1280);
            if (art == null) return;
            main.post(() -> {
                if (!url.equals(backdropKey)) return;
                backdrop.animate().alpha(0f).setDuration(150).withEndAction(() -> {
                    backdrop.setImageBitmap(art);
                    backdrop.animate().alpha(1f).setDuration(240).start();
                }).start();
            });
        });
    }

    private static String join(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isEmpty()) continue;
            if (sb.length() > 0) sb.append("   \u00B7   ");
            sb.append(p);
        }
        return sb.toString();
    }

    // ---- episode mode ------------------------------------------------------------------------------

    /** Enters episode mode for a focused show: the title rail fades out, an episode rail of wide stills fades in,
     *  and each episode's TMDB name/overview/still is fetched for the season. */
    private void enterEpisodeMode(MediaItem show) {
        if (show == null || !show.isSeries || show.episodes == null || show.episodes.isEmpty()) return;
        episodeMode = true;
        episodeShow = orderedEpisodes(show);
        episodeRail.setEpisodes(episodeShow);
        titleRail.view.animate().alpha(0f).setDuration(140).withEndAction(() ->
                titleRail.view.setVisibility(View.GONE)).start();
        episodeRail.view.setVisibility(View.VISIBLE);
        episodeRail.view.setAlpha(0f);
        episodeRail.view.setTranslationY(dp(24));
        episodeRail.view.animate().alpha(1f).translationY(0f).setDuration(200).start();
        episodeRail.row.post(() -> {
            RecyclerView.ViewHolder vh = episodeRail.row.findViewHolderForAdapterPosition(0);
            if (vh != null) vh.itemView.requestFocus();
            else episodeRail.row.requestFocus();
        });
        fetchSeasons(episodeShow);
        rescheduleRotation();
    }

    /** A copy of the show whose episodes are sorted season-then-episode ascending (S01E01 first); the source list
     *  is newest-first for the Home rail, which the Explore episode rail should not inherit. Unparseable labels
     *  keep their original order at the end. */
    private MediaItem orderedEpisodes(MediaItem show) {
        List<BeastClient.Episode> sorted = new ArrayList<>(show.episodes);
        final List<BeastClient.Episode> original = show.episodes;
        sorted.sort((a, b) -> {
            int[] sa = seasonEpisode(a.label), sb = seasonEpisode(b.label);
            if (sa == null && sb == null) return Integer.compare(original.indexOf(a), original.indexOf(b));
            if (sa == null) return 1;
            if (sb == null) return -1;
            if (sa[0] != sb[0]) return Integer.compare(sa[0], sb[0]);
            return Integer.compare(sa[1], sb[1]);
        });
        return new MediaItem(show.source, show.id, show.title, show.year, show.caption, show.poster, show.added,
                show.url, show.mime, show.player, true, show.newEpisodeCount, sorted);
    }

    private void leaveEpisodeMode(boolean refocusTitle) {
        if (!episodeMode) return;
        episodeMode = false;
        episodeShow = null;
        episodeRail.view.animate().alpha(0f).setDuration(140).withEndAction(() -> {
            episodeRail.view.setVisibility(View.GONE);
            episodeRail.view.setAlpha(1f);
        }).start();
        titleRail.view.setVisibility(View.VISIBLE);
        titleRail.view.animate().alpha(1f).setDuration(160).start();
        if (featured != null) showFeatured(featured);
        if (refocusTitle) focusTitleRail();
    }

    /** Fetches every season the show's episodes reference, caching EpisodeInfo by "S×E" so the episode rail and
     *  banner can fill in names, overviews and stills as they arrive. */
    private void fetchSeasons(MediaItem show) {
        if (tmdb == null) return;
        String cacheKey = "tv|" + show.matchKey;
        if (seasonCache.containsKey(cacheKey)) {
            applySeasonInfo(show);
            return;
        }
        java.util.Set<Integer> seasons = new java.util.TreeSet<>();
        for (BeastClient.Episode ep : show.episodes) {
            int[] se = seasonEpisode(ep.label);
            if (se != null) seasons.add(se[0]);
        }
        if (seasons.isEmpty()) return;
        worker.execute(() -> {
            Map<String, TmdbClient.EpisodeInfo> map = new HashMap<>();
            try {
                int id = tmdb.findId(true, show.title, show.year);
                if (id > 0) {
                    for (int s : seasons) {
                        for (TmdbClient.EpisodeInfo info : tmdb.season(id, s)) {
                            map.put(info.season + "x" + info.episode, info);
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "season fetch failed: " + e.getClass().getSimpleName());
            }
            main.post(() -> {
                seasonCache.put(cacheKey, map);
                if (episodeMode && episodeShow == show) applySeasonInfo(show);
            });
        });
    }

    private void applySeasonInfo(MediaItem show) {
        episodeRail.adapter.notifyDataSetChanged();   // stills/names arrived; redraw cards
        View focused = episodeRail.row.findFocus();
        if (focused != null) {
            RecyclerView.ViewHolder vh = episodeRail.row.findContainingViewHolder(focused);
            if (vh != null) {
                int pos = vh.getBindingAdapterPosition();
                if (pos >= 0 && pos < show.episodes.size()) {
                    showEpisode(show, show.episodes.get(pos), episodeInfoFor(show, show.episodes.get(pos)));
                }
            }
        }
    }

    private TmdbClient.EpisodeInfo episodeInfoFor(MediaItem show, BeastClient.Episode ep) {
        Map<String, TmdbClient.EpisodeInfo> map = seasonCache.get("tv|" + show.matchKey);
        if (map == null) return null;
        int[] se = seasonEpisode(ep.label);
        return se == null ? null : map.get(se[0] + "x" + se[1]);
    }

    private static int[] seasonEpisode(String label) {
        if (label == null) return null;
        Matcher m = SXXEYY.matcher(label);
        if (!m.find()) return null;
        try {
            return new int[] {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---- rail --------------------------------------------------------------------------------------

    private final class Rail {
        final LinearLayout view;
        final TextView placeholder;
        final RecyclerView row;
        final RailAdapter adapter = new RailAdapter();

        Rail() {
            view = new LinearLayout(ExploreActivity.this);
            view.setOrientation(LinearLayout.VERTICAL);
            view.setClipChildren(false);
            view.setClipToPadding(false);

            placeholder = new TextView(ExploreActivity.this);
            placeholder.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            placeholder.setTextColor(0x99FFFFFF);
            placeholder.setTypeface(UiFont.get(ExploreActivity.this, R.string.font_regular));
            placeholder.setGravity(Gravity.CENTER_VERTICAL);
            placeholder.setVisibility(View.GONE);
            view.addView(placeholder, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(CARD_H)));

            row = new RecyclerView(ExploreActivity.this);
            row.setLayoutManager(new LinearLayoutManager(
                    ExploreActivity.this, RecyclerView.HORIZONTAL, false));
            row.setClipChildren(false);
            row.setClipToPadding(false);
            row.setPaddingRelative(0, dp(6), dp(60), dp(6));
            row.setOverScrollMode(View.OVER_SCROLL_NEVER);
            row.setHorizontalScrollBarEnabled(false);
            row.setItemAnimator(null);
            row.setAdapter(adapter);
            view.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        void setItems(List<MediaItem> items, String emptyMessage) {
            adapter.episodeOf = null;
            if (items == null || items.isEmpty()) {
                placeholder.setText(emptyMessage);
                placeholder.setVisibility(View.VISIBLE);
                row.setVisibility(View.GONE);
                adapter.setItems(new ArrayList<>());
                return;
            }
            placeholder.setVisibility(View.GONE);
            row.setVisibility(View.VISIBLE);
            adapter.setItems(items);
        }

        void setEpisodes(MediaItem show) {
            adapter.episodeOf = show;
            placeholder.setVisibility(View.GONE);
            row.setVisibility(View.VISIBLE);
            adapter.setEpisodeCount(show.episodes.size());
        }

        boolean hasItems() {
            return adapter.getItemCount() > 0;
        }

        MediaItem itemAt(int i) {
            return adapter.items.get(i);
        }
    }

    private final class RailAdapter extends RecyclerView.Adapter<CardHolder> {
        final List<MediaItem> items = new ArrayList<>();
        MediaItem episodeOf;   // non-null => this adapter shows that show's episodes, not posters
        int episodeCount;

        RailAdapter() {
            setHasStableIds(true);
        }

        void setItems(List<MediaItem> next) {
            episodeOf = null;
            items.clear();
            items.addAll(next);
            notifyDataSetChanged();
        }

        void setEpisodeCount(int count) {
            items.clear();
            episodeCount = count;
            notifyDataSetChanged();
        }

        @Override public long getItemId(int position) {
            return episodeOf != null ? (episodeOf.id + "#" + position).hashCode()
                    : items.get(position).id.hashCode();
        }

        @Override public int getItemCount() {
            return episodeOf != null ? episodeCount : items.size();
        }

        @NonNull @Override public CardHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return episodeOf != null ? new CardHolder(buildEpisodeCard()) : new CardHolder(buildCard());
        }

        @Override public void onBindViewHolder(@NonNull CardHolder h, int position) {
            if (episodeOf != null) {
                bindEpisode(h, episodeOf, position);
                return;
            }
            MediaItem it = items.get(position);
            boolean series = it.isSeries;
            h.itemView.setContentDescription(series
                    ? it.title + "  \u00B7  " + newEpisodesText(it.newEpisodeCount) : it.title);
            h.name.setText(it.title);
            h.name.setVisibility(series || it.poster == null || it.poster.isEmpty()
                    ? View.VISIBLE : View.INVISIBLE);
            h.badge.setText(it.source == MediaItem.Source.DOWNLOAD ? badgeDownload : badgeStream);
            h.sub.setVisibility(series ? View.VISIBLE : View.GONE);
            h.accent.setVisibility(series ? View.VISIBLE : View.GONE);
            if (series) h.sub.setText(newEpisodesText(it.newEpisodeCount));
            loadImage(h.image, it.poster);
            h.itemView.setOnClickListener(v -> open(it));
        }

        private void bindEpisode(CardHolder h, MediaItem show, int position) {
            BeastClient.Episode ep = show.episodes.get(position);
            TmdbClient.EpisodeInfo info = episodeInfoFor(show, ep);
            int[] se = seasonEpisode(ep.label);
            String seText = se != null ? String.format(java.util.Locale.US, "S%02dE%02d", se[0], se[1])
                    : getString(R.string.explore_episode_n, position + 1);
            String name = info != null && !info.name.isEmpty() ? info.name : episodeTitleFallback(ep, se);
            h.name.setText(seText + "  \u00B7  " + name);
            h.name.setVisibility(View.VISIBLE);
            h.itemView.setContentDescription(seText + " " + name);
            loadImage(h.image, info != null ? info.still : "");
            h.itemView.setOnClickListener(v -> play(ep.url, ep.mime, show.title, show.player));
        }
    }

    private String newEpisodesText(int count) {
        Resources res = getResources();
        return count == 1 ? res.getString(R.string.media_rail_new_episode_one)
                : res.getString(R.string.media_rail_new_episodes, count);
    }

    private final class CardHolder extends RecyclerView.ViewHolder {
        final ImageView image;
        TextView name, badge, sub;
        View accent;

        CardHolder(View card) {
            super(card);
            image = card.findViewWithTag("image");
            name = card.findViewWithTag("name");
            badge = card.findViewWithTag("badge");
            sub = card.findViewWithTag("sub");
            accent = card.findViewWithTag("accent");
            card.setOnFocusChangeListener((v, focused) -> {
                float s = focused ? FOCUS_SCALE : 1f;
                v.animate().scaleX(s).scaleY(s).setDuration(140).start();
                v.setElevation(focused ? 8f * density : 0f);
                if (focused) onCardFocused(this);
            });
            card.setOnKeyListener((v, keyCode, event) -> onCardKey(this, keyCode, event));
        }
    }

    private void onCardFocused(CardHolder h) {
        int pos = h.getBindingAdapterPosition();
        if (pos < 0) return;
        if (episodeMode && episodeShow != null) {
            if (pos < episodeShow.episodes.size()) {
                showEpisode(episodeShow, episodeShow.episodes.get(pos), episodeInfoFor(episodeShow,
                        episodeShow.episodes.get(pos)));
            }
        } else if (pos < titleRail.adapter.items.size()) {
            showFeatured(titleRail.itemAt(pos));
        }
        rescheduleRotation();
    }

    private boolean onCardKey(CardHolder h, int keyCode, KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        rescheduleRotation();
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (episodeMode) {
                    leaveEpisodeMode(true);
                } else {
                    tabRow.requestFocus();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                // In the Shows tab, Down on a show enters its episodes (as does OK).
                if (!episodeMode && tab == Tab.SHOWS) {
                    int pos = h.getBindingAdapterPosition();
                    if (pos >= 0 && pos < titleRail.adapter.items.size()) {
                        enterEpisodeMode(titleRail.itemAt(pos));
                        return true;
                    }
                }
                return true;   // swallow so focus never falls off the bottom
            default:
                return false;
        }
    }

    private void focusTitleRail() {
        if (episodeMode) leaveEpisodeMode(false);
        if (!titleRail.hasItems()) return;
        titleRail.row.post(() -> {
            RecyclerView.ViewHolder vh = titleRail.row.findViewHolderForAdapterPosition(0);
            if (vh != null) vh.itemView.requestFocus();
            else titleRail.row.requestFocus();
        });
    }

    // ---- play / open -------------------------------------------------------------------------------

    private void open(MediaItem item) {
        if (item.isSeries) {
            enterEpisodeMode(item);
            return;
        }
        play(item.url, item.mime, item.title, item.player);
    }

    private void play(String url, String mime, String titleText, String player) {
        if (url == null || url.isEmpty()) {
            Toast.makeText(this, R.string.media_rail_no_player, Toast.LENGTH_LONG).show();
            return;
        }
        String type = mime == null || mime.isEmpty() ? "video/*" : mime;
        Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), type)
                .putExtra("title", titleText)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        if (player != null && !player.isEmpty()) {
            try {
                startActivity(new Intent(i).setPackage(player));
                return;
            } catch (ActivityNotFoundException | SecurityException e) {
                Log.w(TAG, "player " + player + " missing; asking any video player");
            }
        }
        try {
            startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.media_rail_no_player, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onBackPressed() {
        if (episodeMode) {
            leaveEpisodeMode(true);
            return;
        }
        super.onBackPressed();
    }

    // ---- auto-rotate -------------------------------------------------------------------------------

    private void rotateBeat() {
        if (!resumed) return;
        if (canRotate()) {
            idleMs += BEAT_MS;
            if (idleMs >= ROTATE_MS) {
                idleMs = 0;
                advance();
            }
        } else {
            idleMs = 0;
        }
        main.postDelayed(beat, BEAT_MS);
    }

    private boolean canRotate() {
        Rail rail = episodeMode ? episodeRail : titleRail;
        return resumed && rail.hasItems() && rail.adapter.getItemCount() >= 2
                && !tabRow.isFocused();
    }

    private void advance() {
        Rail rail = episodeMode ? episodeRail : titleRail;
        int n = rail.adapter.getItemCount();
        if (n < 2) return;
        View focused = rail.row.findFocus();
        int pos = 0;
        if (focused != null) {
            RecyclerView.ViewHolder vh = rail.row.findContainingViewHolder(focused);
            if (vh != null) pos = vh.getBindingAdapterPosition();
        }
        int next = (pos + 1) % n;
        railStack.animate().alpha(0f).setDuration(FADE_MS).withEndAction(() -> {
            rail.row.scrollToPosition(next);
            rail.row.post(() -> {
                RecyclerView.ViewHolder vh = rail.row.findViewHolderForAdapterPosition(next);
                if (vh != null) vh.itemView.requestFocus();
            });
            railStack.animate().alpha(1f).setDuration(FADE_MS).start();
        }).start();
    }

    private void rescheduleRotation() {
        idleMs = 0;
        railStack.setAlpha(1f);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        idleMs = 0;
        main.removeCallbacks(beat);
        main.postDelayed(beat, BEAT_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        main.removeCallbacks(beat);
        railStack.setAlpha(1f);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacks(beat);
        worker.shutdownNow();
        imagePool.shutdownNow();
        images.evictAll();
    }

    // ---- card views + images -----------------------------------------------------------------------

    private View buildCard() {
        FrameLayout card = new FrameLayout(this);
        card.setFocusable(true);
        card.setClipChildren(false);
        RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(dp(CARD_W), dp(CARD_H));
        lp.setMarginEnd(dp(GAP));
        card.setLayoutParams(lp);
        card.setForeground(ring());

        GradientDrawable placeholder = new GradientDrawable();
        placeholder.setCornerRadius(dp(8));
        placeholder.setColor(0xFF1E1E1E);

        TextView name = new TextView(this);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        name.setTextColor(0xB3FFFFFF);
        name.setGravity(Gravity.CENTER);
        name.setMaxLines(4);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(dp(6), dp(6), dp(6), dp(6));
        name.setTypeface(UiFont.get(this, R.string.font_regular));
        name.setBackground(placeholder);
        name.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        name.setTag("name");
        card.addView(name, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        ImageView image = new ImageView(this);
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

        View accent = new View(this);
        GradientDrawable accentBg = new GradientDrawable();
        accentBg.setCornerRadii(new float[] {0, 0, 0, 0, dp(8), dp(8), dp(8), dp(8)});
        accentBg.setColor(0xE6176B5A);
        accent.setBackground(accentBg);
        accent.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        accent.setVisibility(View.GONE);
        accent.setTag("accent");
        FrameLayout.LayoutParams alp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(24));
        alp.gravity = Gravity.BOTTOM;
        card.addView(accent, alp);

        TextView sub = new TextView(this);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        sub.setTextColor(Color.WHITE);
        sub.setTypeface(UiFont.get(this, R.string.font_medium));
        sub.setIncludeFontPadding(false);
        sub.setGravity(Gravity.CENTER);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        sub.setPadding(dp(4), dp(2), dp(4), dp(3));
        sub.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        sub.setVisibility(View.GONE);
        sub.setTag("sub");
        FrameLayout.LayoutParams subLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(24));
        subLp.gravity = Gravity.BOTTOM;
        card.addView(sub, subLp);

        TextView badge = new TextView(this);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8);
        badge.setTextColor(Color.WHITE);
        badge.setTypeface(UiFont.get(this, R.string.font_medium));
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

    /** A wide episode card: a 16:9 still with an "SxxEyy · name" caption below it. */
    private View buildEpisodeCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setFocusable(true);
        card.setClipChildren(false);
        RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(dp(EP_W), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(GAP));
        card.setLayoutParams(lp);

        FrameLayout stillWrap = new FrameLayout(this);
        stillWrap.setForeground(ring());
        GradientDrawable ph = new GradientDrawable();
        ph.setCornerRadius(dp(8));
        ph.setColor(0xFF1E1E1E);
        stillWrap.setBackground(ph);
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        GradientDrawable clip = new GradientDrawable();
        clip.setCornerRadius(dp(8));
        clip.setColor(Color.TRANSPARENT);
        image.setBackground(clip);
        image.setClipToOutline(true);
        image.setTag("image");
        stillWrap.addView(image, new FrameLayout.LayoutParams(dp(EP_W), dp(EP_H)));
        card.addView(stillWrap, new LinearLayout.LayoutParams(dp(EP_W), dp(EP_H)));

        TextView name = new TextView(this);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        name.setTextColor(0xD8FFFFFF);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setTypeface(UiFont.get(this, R.string.font_regular));
        name.setTag("name");
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                dp(EP_W), ViewGroup.LayoutParams.WRAP_CONTENT);
        nameLp.topMargin = dp(8);
        card.addView(name, nameLp);
        return card;
    }

    private StateListDrawable ring() {
        GradientDrawable on = new GradientDrawable();
        on.setCornerRadius(dp(8));
        on.setColor(Color.TRANSPARENT);
        on.setStroke(dp(3), ColorStateList.valueOf(Color.WHITE));
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[] {android.R.attr.state_focused}, on);
        s.addState(new int[] {}, new GradientDrawable());
        return s;
    }

    private void loadImage(ImageView view, String url) {
        view.setImageDrawable(null);
        if (url == null || url.isEmpty()) {
            view.setTag("");
            return;
        }
        view.setTag(url);
        Bitmap hit = images.get(url);
        if (hit != null) {
            view.setImageBitmap(hit);
            return;
        }
        imagePool.execute(() -> {
            Bitmap bmp = RailImages.fetch(url, IMAGE_W);
            if (bmp == null) return;
            images.put(url, bmp);
            main.post(() -> {
                if (url.equals(view.getTag())) view.setImageBitmap(bmp);
            });
        });
    }

    private void loadInto(ImageView view, String url, Runnable onReady) {
        view.setTag(url);
        Bitmap hit = images.get(url);
        if (hit != null) {
            view.setImageBitmap(hit);
            onReady.run();
            return;
        }
        imagePool.execute(() -> {
            Bitmap bmp = RailImages.fetch(url, 500);
            if (bmp == null) return;
            images.put(url, bmp);
            main.post(() -> {
                if (url.equals(view.getTag())) {
                    view.setImageBitmap(bmp);
                    onReady.run();
                }
            });
        });
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}
