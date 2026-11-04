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
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The full browse view (Tier 2), opened from the Media rail's "Show more" tile. Two visibly separate, labeled,
 * UN-deduped sections stacked vertically, each a horizontal row of poster cards reusing the rail's card look:
 *
 * <ul>
 *   <li><b>Downloaded Media</b> — the whole Beast library ({@link BeastClient#allMovies}/{@link BeastClient#allSeries}):
 *       movies play straight away, each show is listed once as an "N new episodes" card that opens the same
 *       Back-dismissible episode picker the rail uses (one-episode shows play directly).</li>
 *   <li><b>Streaming</b> — TMDB trending-this-week, top 20, filtered to the IPTV titles the provider actually carries,
 *       matched by normalized title+year ({@link StreamMatch}); played via Just Player. Only the resolved matches
 *       show; if none resolve, or a source is unreachable, the section shows a graceful empty state.</li>
 * </ul>
 *
 * Everything is built in code (no new layout XML), kept lightweight for 1 GB-RAM TVs: no new Gradle deps, no blur or
 * video, a small bounded image cache. D-pad: Left/Right within a row, Up/Down between the two rows, Back finishes.
 * Secrets are read from the git-ignored res/values/*_secrets.xml by name and never logged.
 */
public class BrowseActivity extends Activity {
    private static final String TAG = "BrowseActivity";
    /** TMDB trending list size the owner asked for. */
    private static final int STREAMING_LIMIT = 20;
    private static final int CARD_W = 108, CARD_H = 162;
    private static final int GAP = 14, SECTION_GAP = 20, HEADER = 20;
    private static final float FOCUS_SCALE = 1.08f;
    private static final int IMAGE_W = CARD_W * 2;

    private float density;
    private BeastClient beast;         // may be null when unconfigured
    private IptvClient iptv;           // may be null
    private TmdbClient tmdb;           // may be null
    private String downloadPlayer;
    private String streamPlayer;
    private String badgeDownload;
    private String badgeStream;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService imagePool = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> images = new LruCache<String, Bitmap>(4 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };

    private Section downloaded;
    private Section streaming;
    private EpisodePicker picker;

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

        ScrollView root = new ScrollView(this);
        root.setBackgroundColor(0xFF0A0A0A);
        root.setVerticalScrollBarEnabled(false);
        root.setFillViewport(true);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(48);
        column.setPadding(pad, dp(28), pad, dp(28));
        column.setClipChildren(false);
        column.setClipToPadding(false);

        downloaded = new Section(getString(R.string.browse_section_downloaded));
        streaming = new Section(getString(R.string.browse_section_streaming));
        column.addView(downloaded.view);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(SECTION_GAP);
        column.addView(streaming.view, sp);

        root.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);

        loadDownloaded();
        loadStreaming();
    }

    private String secret(String name) {
        int id = getResources().getIdentifier(name, "string", getPackageName());
        return id == 0 ? "" : getString(id).trim();
    }

    // ---- data --------------------------------------------------------------------------------------

    private void loadDownloaded() {
        if (beast == null) {
            downloaded.showEmpty(R.string.browse_downloaded_empty);
            return;
        }
        worker.execute(() -> {
            List<MediaItem> items = new ArrayList<>();
            try {
                items.addAll(beast.allMovies(downloadPlayer));
            } catch (Exception e) {
                Log.w(TAG, "beast movies failed: " + e.getClass().getSimpleName());
            }
            try {
                items.addAll(beast.allSeries(downloadPlayer));
            } catch (Exception e) {
                Log.w(TAG, "beast series failed: " + e.getClass().getSimpleName());
            }
            main.post(() -> {
                if (isFinishing()) return;
                if (items.isEmpty()) downloaded.showEmpty(R.string.browse_downloaded_empty);
                else downloaded.setItems(items);
            });
        });
    }

    private void loadStreaming() {
        if (tmdb == null || iptv == null) {
            streaming.showEmpty(R.string.browse_streaming_empty);
            return;
        }
        worker.execute(() -> {
            List<MediaItem> items = new ArrayList<>();
            try {
                List<TmdbMovie> trending = tmdb.trendingWeek(STREAMING_LIMIT);
                List<StreamMatch.Entry> entries = iptv.vodEntries();
                for (TmdbMovie m : trending) {
                    IptvItem hit = StreamMatch.find(entries, m.title, m.year);
                    if (hit == null) continue;   // degrade gracefully: show only the titles the provider carries
                    String mime = "mkv".equals(hit.extension) ? "video/x-matroska" : "video/*";
                    String poster = TmdbClient.posterUrl(m.posterPath);
                    MediaItem mi = new MediaItem(MediaItem.Source.STREAM, "st:" + hit.streamId, m.title, m.year,
                            m.title, poster, hit.added, iptv.streamUrl(hit), mime, streamPlayer);
                    String bd = TmdbClient.backdropUrl(m.backdropPath);
                    mi.backdrop = bd == null ? "" : bd;
                    items.add(mi);
                }
            } catch (Exception e) {
                Log.w(TAG, "streaming build failed: " + e.getClass().getSimpleName());
            }
            main.post(() -> {
                if (isFinishing()) return;
                if (items.isEmpty()) streaming.showEmpty(R.string.browse_streaming_empty);
                else streaming.setItems(items);
            });
        });
    }

    // ---- playing / opening -------------------------------------------------------------------------

    /** OK on a card: a movie plays, a series opens the picker (or plays straight away when it has one episode). */
    private void open(MediaItem item, View origin) {
        if (!item.isSeries) {
            play(item);
            return;
        }
        List<BeastClient.Episode> eps = item.episodes;
        if (eps == null || eps.isEmpty()) {
            Toast.makeText(this, R.string.media_rail_picker_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        if (eps.size() == 1) {
            BeastClient.Episode only = eps.get(0);
            play(only.url, only.mime, item.title, item.player);
            return;
        }
        showPicker(item, origin);
    }

    private void play(MediaItem item) {
        play(item.url, item.mime, item.title, item.player);
    }

    private void play(String url, String mime, String titleText, String player) {
        if (url == null || url.isEmpty()) {
            Toast.makeText(this, R.string.media_rail_no_player, Toast.LENGTH_LONG).show();
            return;
        }
        Uri uri = Uri.parse(url);
        String type = mime == null || mime.isEmpty() ? "video/*" : mime;
        Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
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
        if (picker != null) {
            dismissPicker();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.shutdownNow();
        imagePool.shutdownNow();
        images.evictAll();
    }

    // ---- episode picker ----------------------------------------------------------------------------

    private void showPicker(MediaItem series, View origin) {
        dismissPicker();
        View root = getWindow().getDecorView();
        if (!(root instanceof ViewGroup)) return;
        picker = new EpisodePicker(series, origin);
        ((ViewGroup) root).addView(picker.overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        picker.focusFirst();
    }

    private void dismissPicker() {
        if (picker == null) return;
        EpisodePicker p = picker;
        picker = null;
        p.remove();
    }

    /** A compact, centered, Back-dismissible list of a series' episodes, newest-first, drawn over the whole window. */
    private final class EpisodePicker {
        final FrameLayout overlay;
        final LinearLayout list;
        final View origin;

        EpisodePicker(MediaItem series, View origin) {
            this.origin = origin;
            overlay = new FrameLayout(BrowseActivity.this);
            overlay.setBackgroundColor(0xCC000000);
            overlay.setClickable(true);
            overlay.setFocusable(true);
            overlay.setFocusableInTouchMode(true);

            LinearLayout panel = new LinearLayout(BrowseActivity.this);
            panel.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable panelBg = new GradientDrawable();
            panelBg.setCornerRadius(dp(12));
            panelBg.setColor(0xFF161616);
            panel.setBackground(panelBg);
            panel.setPadding(dp(18), dp(16), dp(18), dp(16));

            TextView t = new TextView(BrowseActivity.this);
            t.setText(series.title);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            t.setTextColor(Color.WHITE);
            t.setTypeface(UiFont.get(BrowseActivity.this, R.string.font_medium));
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            panel.addView(t);

            TextView sub = new TextView(BrowseActivity.this);
            sub.setText(getString(R.string.media_rail_picker_title));
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            sub.setTextColor(0x99FFFFFF);
            sub.setTypeface(UiFont.get(BrowseActivity.this, R.string.font_regular));
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            subLp.bottomMargin = dp(10);
            panel.addView(sub, subLp);

            ScrollView scroller = new ScrollView(BrowseActivity.this);
            scroller.setVerticalScrollBarEnabled(false);
            list = new LinearLayout(BrowseActivity.this);
            list.setOrientation(LinearLayout.VERTICAL);
            int index = 0;
            for (BeastClient.Episode ep : series.episodes) {
                list.addView(episodeRow(series, ep, index++));
            }
            scroller.addView(list, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            panel.addView(scroller, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(300)));

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
            TextView r = new TextView(BrowseActivity.this);
            String label = ep.label == null || ep.label.isEmpty() ? series.title : ep.label;
            r.setText(label);
            r.setContentDescription(label);
            r.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            r.setTextColor(Color.WHITE);
            r.setTypeface(UiFont.get(BrowseActivity.this, R.string.font_regular));
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

    // ---- a labeled section (header + horizontal row) -----------------------------------------------

    /** One labeled, horizontally-scrolling section. Empty sections stay focusable via a placeholder message. */
    private final class Section {
        final LinearLayout view;
        final RecyclerView row;
        final TextView placeholder;
        final SectionAdapter adapter = new SectionAdapter();

        Section(String header) {
            view = new LinearLayout(BrowseActivity.this);
            view.setOrientation(LinearLayout.VERTICAL);
            view.setClipChildren(false);
            view.setClipToPadding(false);

            TextView title = new TextView(BrowseActivity.this);
            title.setText(header);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            title.setLetterSpacing(0.06f);
            title.setTextColor(0xE6FFFFFF);
            title.setTypeface(UiFont.get(BrowseActivity.this, R.string.font_medium));
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(HEADER));
            hp.bottomMargin = dp(8);
            view.addView(title, hp);

            placeholder = new TextView(BrowseActivity.this);
            placeholder.setText(getString(R.string.browse_loading));
            placeholder.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            placeholder.setTextColor(0x99FFFFFF);
            placeholder.setTypeface(UiFont.get(BrowseActivity.this, R.string.font_regular));
            placeholder.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(CARD_H));
            view.addView(placeholder, pp);

            row = new RecyclerView(BrowseActivity.this);
            row.setLayoutManager(new LinearLayoutManager(
                    BrowseActivity.this, RecyclerView.HORIZONTAL, false));
            row.setClipChildren(false);
            row.setClipToPadding(false);
            row.setPaddingRelative(0, dp(6), dp(6), dp(6));
            row.setOverScrollMode(View.OVER_SCROLL_NEVER);
            row.setHorizontalScrollBarEnabled(false);
            row.setItemAnimator(null);
            row.setAdapter(adapter);
            row.setVisibility(View.GONE);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(CARD_H + 12));
            view.addView(row, rp);
        }

        void setItems(List<MediaItem> items) {
            if (items.isEmpty()) {
                showEmpty(R.string.browse_streaming_empty);
                return;
            }
            placeholder.setVisibility(View.GONE);
            row.setVisibility(View.VISIBLE);
            adapter.setItems(items);
        }

        void showEmpty(int messageRes) {
            placeholder.setText(getString(messageRes));
            placeholder.setVisibility(View.VISIBLE);
            row.setVisibility(View.GONE);
            adapter.setItems(new ArrayList<>());
        }
    }

    private final class SectionAdapter extends RecyclerView.Adapter<CardHolder> {
        private final List<MediaItem> items = new ArrayList<>();

        SectionAdapter() {
            setHasStableIds(true);
        }

        void setItems(List<MediaItem> next) {
            items.clear();
            items.addAll(next);
            notifyDataSetChanged();
        }

        @Override public long getItemId(int position) {
            return items.get(position).id.hashCode();
        }

        @Override public int getItemCount() {
            return items.size();
        }

        @NonNull @Override public CardHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new CardHolder(buildCard());
        }

        @Override public void onBindViewHolder(@NonNull CardHolder h, int position) {
            MediaItem it = items.get(position);
            boolean series = it.isSeries;
            h.itemView.setContentDescription(descriptionFor(it));
            h.name.setText(it.title);
            h.name.setVisibility(series || it.poster == null || it.poster.isEmpty()
                    ? View.VISIBLE : View.INVISIBLE);
            h.badge.setText(it.source == MediaItem.Source.DOWNLOAD ? badgeDownload : badgeStream);
            h.sub.setVisibility(series ? View.VISIBLE : View.GONE);
            h.accent.setVisibility(series ? View.VISIBLE : View.GONE);
            if (series) h.sub.setText(newEpisodesText(it.newEpisodeCount));
            loadImage(h.image, it.poster);
            h.itemView.setOnClickListener(v -> open(it, h.itemView));
        }
    }

    private String descriptionFor(MediaItem it) {
        if (it.isSeries) return it.title + "  ·  " + newEpisodesText(it.newEpisodeCount);
        return it.title;
    }

    private String newEpisodesText(int count) {
        Resources res = getResources();
        return count == 1 ? res.getString(R.string.media_rail_new_episode_one)
                : res.getString(R.string.media_rail_new_episodes, count);
    }

    private final class CardHolder extends RecyclerView.ViewHolder {
        final ImageView image;
        final TextView name;
        final TextView badge;
        final TextView sub;
        final View accent;

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
            });
        }
    }

    /** One poster card: a rounded 2:3 poster, a source badge, and (for a series) a "N new episodes" strip. */
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

    // ---- images ------------------------------------------------------------------------------------

    private void loadImage(ImageView view, String url) {
        view.setTag("image");   // keep the holder tag; track the url separately
        view.setImageDrawable(null);
        if (url == null || url.isEmpty()) {
            view.setContentDescription(null);
            view.setTag(url == null ? "" : url);
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

    private int dp(float v) {
        return Math.round(v * density);
    }
}
