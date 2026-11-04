package com.gathin.tvhome;

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
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.AtomicFile;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    }

    private static final String TAG = "MediaRail";
    private static final long MIN_REFRESH_MS = 10 * 60_000;
    /** Peek tile size in dp: skinnier/taller than the wide featured tile, like the reference's "up next" tiles. */
    private static final int CARD_W = 115, CARD_H = 162;
    /** The big left-hand featured box: a large wide 16:9 tile, sized to the band above the dock. */
    private static final int SHOWCASE_W = 334, SHOWCASE_H = 188;
    /** Decode width for tile art: a bit above the featured tile's pixel size (biggest tile) so posters stay crisp
     *  without wasting memory — decoding far larger than needed both wastes RAM and thrashes the image cache. */
    private static final int ART_DECODE_W = 520;
    private static final int GAP = 12, HEADER = 18, HEADER_GAP = 6;
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
    private final String downloadPlayer;
    private final String streamPlayer;
    private final int limit;
    private final int sourceCount;
    private final List<String> skipCategories;
    private final String badgeDownload;
    private final String badgeStream;
    private final AtomicFile cacheFile;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService imagePool = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    // Big enough to hold the featured tile plus the whole visible peek strip at ARGB_8888, so a sharp bitmap is
    // never evicted and reloaded (which showed as "sharp, then it goes soft"). ~24 MB on a 1 GB TV is acceptable
    // for a handful of poster-sized bitmaps.
    private final LruCache<String, Bitmap> images = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };

    private final LinearLayout container;
    private final TextView header;
    private final LinearLayout body;
    private final ImageView showcaseImage;   // the big left showcase box's art
    private final TextView featuredTitle;    // the showcase title
    private final TextView featuredSub;       // detail line: "year · source · added" or "N new episodes"
    private final RecyclerView row;
    private final Adapter adapter = new Adapter();
    private final String title;

    private long lastRefresh;
    private boolean released;
    private boolean gridAtTop = true;
    private boolean placed;
    private int lastIndex;
    private EpisodePicker picker;

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
        return new MediaRail(context, root, above, host, beast, iptv);
    }

    private static String secret(Context context, String name) {
        int id = context.getResources().getIdentifier(name, "string", context.getPackageName());
        return id == 0 ? "" : context.getString(id).trim();
    }

    private MediaRail(Context context, ViewGroup root, View above, Host host, BeastClient beast, IptvClient iptv) {
        this.context = context;
        this.host = host;
        this.beast = beast;
        this.iptv = iptv;
        Resources res = context.getResources();
        density = res.getDisplayMetrics().density;
        downloadPlayer = res.getString(R.string.media_rail_download_player).trim();
        streamPlayer = res.getString(R.string.media_rail_stream_player).trim();
        limit = Math.max(1, Math.min(60, res.getInteger(R.integer.media_rail_count)));
        sourceCount = Math.max(limit, Math.min(60, res.getInteger(R.integer.media_rail_source_count)));
        skipCategories = Arrays.asList(res.getStringArray(R.array.media_rail_skip_categories));
        badgeDownload = res.getString(R.string.media_rail_badge_download);
        badgeStream = res.getString(R.string.media_rail_badge_stream);
        title = res.getString(R.string.media_rail_title);
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

        header = new TextView(context);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        header.setLetterSpacing(0.08f);
        header.setTextColor(0xB3FFFFFF);
        header.setSingleLine(true);
        header.setEllipsize(TextUtils.TruncateAt.END);
        header.setTypeface(UiFont.get(context, R.string.font_medium));
        header.setText(title);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(HEADER));
        hp.bottomMargin = dp(HEADER_GAP);
        hp.setMarginEnd(dp(66));
        container.addView(header, hp);

        // The body is a vertical stack: a big hero title + meta line over the backdrop (no poster tile — the
        // full-screen HomeBackdrop IS the hero), then the single poster row the D-pad scrolls through.
        body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setClipChildren(false);
        body.setClipToPadding(false);

        // The body is horizontal: a BIG showcase box on the left (the focused item, large) + the poster strip on
        // the right. Moving focus along the strip updates the showcase. This is the Apple-TV spotlight shape.
        body.setOrientation(LinearLayout.HORIZONTAL);

        // --- Left: the big showcase box ---
        FrameLayout showcase = new FrameLayout(context);
        showcase.setClipToOutline(true);
        GradientDrawable showBg = new GradientDrawable();
        showBg.setCornerRadius(dp(12));
        showBg.setColor(0xFF161A22);
        showcase.setBackground(showBg);
        showcase.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        showcaseImage = new ImageView(context);
        showcaseImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        showcaseImage.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        showcase.addView(showcaseImage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Bottom scrim so the title/meta stay readable over the art.
        View showScrim = new View(context);
        showScrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0x00000000, 0x66000000, 0xF0000000}));
        showScrim.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams scrimLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(SHOWCASE_H * 60 / 100));
        scrimLp.gravity = Gravity.BOTTOM;
        showcase.addView(showScrim, scrimLp);

        LinearLayout showText = new LinearLayout(context);
        showText.setOrientation(LinearLayout.VERTICAL);
        showText.setPadding(dp(14), dp(10), dp(14), dp(14));
        showText.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        featuredTitle = new TextView(context);
        featuredTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        featuredTitle.setTextColor(Color.WHITE);
        featuredTitle.setTypeface(UiFont.get(context, R.string.font_medium));
        featuredTitle.setMaxLines(2);
        featuredTitle.setEllipsize(TextUtils.TruncateAt.END);
        showText.addView(featuredTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        featuredSub = new TextView(context);
        featuredSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        featuredSub.setTextColor(0xDDFFFFFF);
        featuredSub.setTypeface(UiFont.get(context, R.string.font_regular));
        featuredSub.setSingleLine(true);
        featuredSub.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams fsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fsLp.topMargin = dp(3);
        showText.addView(featuredSub, fsLp);

        FrameLayout.LayoutParams stLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stLp.gravity = Gravity.BOTTOM;
        showcase.addView(showText, stLp);

        LinearLayout.LayoutParams showLp = new LinearLayout.LayoutParams(dp(SHOWCASE_W), dp(SHOWCASE_H));
        showLp.setMarginEnd(dp(28));
        body.addView(showcase, showLp);

        // --- Right: the poster strip ---
        row = new RecyclerView(context);
        row.setLayoutManager(new LinearLayoutManager(context, RecyclerView.HORIZONTAL, false));
        row.setClipChildren(false);
        row.setClipToPadding(false);
        // Left padding so the first card — even when it scales up 1.08× on focus — never touches the showcase.
        row.setPaddingRelative(dp(10), dp(6), dp(66), dp(6));
        row.setOverScrollMode(View.OVER_SCROLL_NEVER);
        row.setHorizontalScrollBarEnabled(false);
        row.setItemAnimator(null);
        row.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        row.setAdapter(adapter);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, dp(CARD_H + 12), 1f);
        rp.gravity = Gravity.CENTER_VERTICAL;
        body.addView(row, rp);

        container.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height());
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.setMarginStart(dp(66));
        int at = root.indexOfChild(above);
        root.addView(container, at < 0 ? root.getChildCount() : at, lp);

        List<MediaItem> cached = readCache();
        if (cached != null) apply(cached);
    }

    // ---- placement and visibility ----------------------------------------------------------------

    int height() {
        // header + the big featured wide tile (the tallest element) + room for its caption below.
        return dp(HEADER + HEADER_GAP + SHOWCASE_H + 40);
    }

    void placeAbove(float anchorTop, float minTop) {
        float y = Math.max(minTop + dp(8), anchorTop - height() - dp(4));
        container.setY(y);
        placed = true;
        updateVisibility();
    }

    void setGridAtTop(boolean atTop) {
        if (gridAtTop == atTop) return;
        gridAtTop = atTop;
        updateVisibility();
    }

    boolean isShowing() {
        return container.getVisibility() == View.VISIBLE && adapter.getItemCount() > 0 && gridAtTop;
    }

    void focus() {
        int n = adapter.getItemCount();
        if (n == 0) return;
        container.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        int pos = Math.max(0, Math.min(lastIndex, n - 1));
        RecyclerView.ViewHolder vh = row.findViewHolderForAdapterPosition(pos);
        if (vh != null) {
            vh.itemView.requestFocus();
            return;
        }
        row.scrollToPosition(pos);
        row.post(() -> {
            RecyclerView.ViewHolder later = row.findViewHolderForAdapterPosition(pos);
            if (later != null) later.itemView.requestFocus();
        });
    }

    private boolean isInside(View v) {
        for (View p = v; p != null; p = p.getParent() instanceof View ? (View) p.getParent() : null) {
            if (p == container) return true;
        }
        return false;
    }

    private void updateVisibility() {
        boolean show = placed && gridAtTop && adapter.getItemCount() > 0;
        if (show && container.getVisibility() != View.VISIBLE) {
            container.setVisibility(View.VISIBLE);
            container.animate().cancel();
            container.animate().alpha(1f).setDuration(200).start();
        } else if (!show && container.getVisibility() == View.VISIBLE) {
            container.animate().cancel();
            container.animate().alpha(0f).setDuration(140)
                    .withEndAction(() -> container.setVisibility(View.GONE)).start();
        }
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
        dismissPicker();
        worker.shutdownNow();
        imagePool.shutdownNow();
        images.evictAll();
    }

    private void apply(List<MediaItem> items) {
        adapter.setItems(items);
        bindFeatured(items.isEmpty() ? null : items.get(Math.max(0, Math.min(lastIndex, items.size() - 1))));
        updateVisibility();
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
        if (eps.size() == 1) {
            BeastClient.Episode only = eps.get(0);
            play(only.url, only.mime, item.title, item.player);
            return;
        }
        showPicker(item, origin);
    }

    /** Opens the full browse view (Tier 2): the full download library plus TMDB trending matched to streams. */
    private void openBrowse() {
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
    }

    private void dismissPicker() {
        if (picker == null) return;
        EpisodePicker p = picker;
        picker = null;
        p.remove();
    }

    /** A compact, centered, Back-dismissible list of a series' episodes, newest-first, drawn over the whole root. */
    private final class EpisodePicker {
        final FrameLayout overlay;
        final LinearLayout list;
        final View origin;

        EpisodePicker(MediaItem series, View origin) {
            this.origin = origin;
            overlay = new FrameLayout(context);
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

            overlay.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() == KeyEvent.ACTION_UP && keyCode == KeyEvent.KEYCODE_BACK) {
                    dismissPicker();
                    return true;
                }
                return keyCode == KeyEvent.KEYCODE_BACK;
            });
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

    private void bindFeatured(MediaItem it) {
        if (it == null) {
            featuredTitle.setText("");
            featuredSub.setText("");
            showcaseImage.setImageDrawable(null);
            showcaseImage.setTag(null);
            return;
        }
        loadImage(showcaseImage, wideArt(it));
        featuredTitle.setText(it.title);

        // Detail line under the title: "N new episodes" for a series, else "year" when known; append the source.
        String source = it.source == MediaItem.Source.DOWNLOAD ? badgeDownload : badgeStream;
        String detail;
        if (it.isSeries) {
            detail = newEpisodesText(it.newEpisodeCount) + "  ·  " + source;
        } else if (it.year > 0) {
            detail = it.year + "  ·  " + source;
        } else {
            detail = source;
        }
        String added = addedText(it.added);
        if (!added.isEmpty()) detail += "  ·  " + added;
        featuredSub.setText(detail);
    }

    private String newEpisodesText(int count) {
        Resources res = context.getResources();
        return count == 1 ? res.getString(R.string.media_rail_new_episode_one)
                : res.getString(R.string.media_rail_new_episodes, count);
    }

    private String headerFor(MediaItem it) {
        String added = addedText(it.added);
        String badge = it.source == MediaItem.Source.DOWNLOAD ? badgeDownload : badgeStream;
        String lead = it.isSeries
                ? it.title + "  ·  " + newEpisodesText(it.newEpisodeCount)
                : title + "  ·  " + it.caption;
        return lead + "  ·  " + badge + (added.isEmpty() ? "" : " · " + added);
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

    // ---- cards -----------------------------------------------------------------------------------

    private static final int TYPE_MEDIA = 0, TYPE_MORE = 1;

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        private final List<MediaItem> items = new ArrayList<>();

        Adapter() {
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

        /** Adapter position maps straight to the item list now that the backdrop (not a tile) is the hero. */
        private int itemIndexFor(int position) {
            return position;
        }

        /** The "Show more" tile is a synthetic final position; it is not a MediaItem. */
        private boolean isMore(int position) {
            return position == items.size();
        }

        @Override public long getItemId(int position) {
            return isMore(position) ? "\u0000show-more".hashCode() : items.get(position).id.hashCode();
        }

        @Override public int getItemViewType(int position) {
            return isMore(position) ? TYPE_MORE : TYPE_MEDIA;
        }

        @Override public int getItemCount() {
            return items.isEmpty() ? 0 : items.size() + 1;   // every item + the "Show more" tile
        }

        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(viewType == TYPE_MORE ? buildMoreCard() : buildCard(), viewType);
        }

        @Override public void onBindViewHolder(@NonNull Holder h, int position) {
            if (isMore(position)) {
                h.itemView.setContentDescription(context.getString(R.string.media_rail_show_more));
                h.itemView.setOnClickListener(v -> openBrowse());
                return;
            }
            MediaItem it = items.get(itemIndexFor(position));
            h.itemView.setContentDescription(headerFor(it));
            boolean series = it.isSeries;
            h.name.setText(it.title);
            // The show title stays visible on a series card (there may be no poster); a movie shows its name only
            // behind a missing poster, where the image is transparent.
            h.name.setVisibility(series || it.poster == null || it.poster.isEmpty() ? View.VISIBLE : View.INVISIBLE);
            h.badge.setText(it.source == MediaItem.Source.DOWNLOAD ? badgeDownload : badgeStream);
            if (h.sub != null) {
                h.sub.setVisibility(series ? View.VISIBLE : View.GONE);
                if (series) h.sub.setText(newEpisodesText(it.newEpisodeCount));
            }
            if (h.accent != null) h.accent.setVisibility(series ? View.VISIBLE : View.GONE);
            // Peek tiles are skinny/portrait, so use the poster (not the wide backdrop, which would stretch).
            loadImage(h.image, it.poster != null && !it.poster.isEmpty() ? it.poster : wideArt(it));
            h.itemView.setOnClickListener(v -> open(it, h.itemView));
        }
    }

    private final class Holder extends RecyclerView.ViewHolder {
        final ImageView image;
        final TextView name;
        final TextView badge;
        final TextView sub;       // "N new episodes" for a series card; null on the "Show more" tile
        final View accent;        // series accent treatment; null on the "Show more" tile
        final int type;

        Holder(View card, int type) {
            super(card);
            this.type = type;
            image = card.findViewWithTag("image");
            name = card.findViewWithTag("name");
            badge = card.findViewWithTag("badge");
            sub = card.findViewWithTag("sub");
            accent = card.findViewWithTag("accent");
            if (image != null) image.setTag(null);
            if (name != null) name.setTag(null);
            if (badge != null) badge.setTag(null);
            if (sub != null) sub.setTag(null);
            if (accent != null) accent.setTag(null);
            card.setOnFocusChangeListener((v, focused) -> {
                float s = focused ? FOCUS_SCALE : 1f;
                v.animate().scaleX(s).scaleY(s).setDuration(140).start();
                v.setElevation(focused ? 8f * density : 0f);
                if (focused) {
                    int pos = getBindingAdapterPosition();
                    if (pos != RecyclerView.NO_POSITION && !adapter.isMore(pos)) {
                        int idx = adapter.itemIndexFor(pos);
                        lastIndex = idx;
                        MediaItem it = adapter.items.get(idx);
                        header.setText(headerFor(it));
                        bindFeatured(it);
                        host.onItemFocused(it);
                    } else {
                        // On the "Show more" tile the hero falls back to item 0.
                        if (!adapter.items.isEmpty()) {
                            bindFeatured(adapter.items.get(0));
                            host.onItemFocused(adapter.items.get(0));
                        }
                        header.setText(context.getString(R.string.media_rail_show_more));
                    }
                    host.onRailFocused();
                } else {
                    header.setText(title);
                }
            });
            card.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) {
                    return keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN;
                }
                int pos = getBindingAdapterPosition();
                boolean rtl = v.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_UP:
                        host.onRailUp();
                        return true;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        host.onRailDown();
                        return true;
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        return pos == (rtl ? adapter.getItemCount() - 1 : 0);
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        return pos == (rtl ? 0 : adapter.getItemCount() - 1);
                    default:
                        return false;
                }
            });
        }
    }

    /** One peek card: a rounded 2:3 poster with a white ring while focused, a source badge in the top-left corner. */
    private View buildCard() {
        FrameLayout card = new FrameLayout(context);
        card.setFocusable(true);
        card.setClipChildren(false);
        RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(dp(CARD_W), dp(CARD_H));
        lp.setMarginEnd(dp(GAP));
        card.setLayoutParams(lp);
        card.setForeground(ring());

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

    /** The final "Show more" peek tile: a plain focusable card with a centered label. */
    private View buildMoreCard() {
        FrameLayout card = new FrameLayout(context);
        card.setFocusable(true);
        card.setClipChildren(false);
        RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(dp(CARD_W), dp(CARD_H));
        lp.setMarginEnd(dp(GAP));
        card.setLayoutParams(lp);
        card.setForeground(ring());

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(8));
        bg.setColor(0xFF242424);

        TextView label = new TextView(context);
        label.setText(context.getString(R.string.media_rail_show_more));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        label.setTextColor(0xE6FFFFFF);
        label.setGravity(Gravity.CENTER);
        label.setTypeface(UiFont.get(context, R.string.font_medium));
        label.setBackground(bg);
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.addView(label, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
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
}
