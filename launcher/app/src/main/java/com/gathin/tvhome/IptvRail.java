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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Latest movies": a row of posters between the top bar and the dock, filled from an IPTV provider's Xtream Codes
 * API (res/values/iptv_rail.xml; the login in res/values/iptv_secrets.xml, which stays out of git). Up from the dock
 * lands here, Up again on the top bar, Down goes back to the dock. OK plays the movie in VLC. The provider is asked
 * when Home comes up (at most every {@link #MIN_REFRESH_MS}), never in the background; the last list is cached so
 * the row fills at once.
 *
 * Everything the rail needs lives here, so MainActivity only creates it, places it and routes keys to it.
 */
final class IptvRail {
    interface Host {
        /** Up from the rail: focus the top bar. */
        void onRailUp();

        /** Down from the rail: focus the dock. */
        void onRailDown();

        /** A rail card took focus (hide the dock's name above the dock). */
        void onRailFocused();
    }

    private static final String TAG = "IptvRail";
    private static final long MIN_REFRESH_MS = 10 * 60_000;
    /** Poster size in dp: 2:3. Kept small so two rails (this and the Beast rail) stack above the dock. */
    private static final int CARD_W = 64, CARD_H = 96;
    /** Decode posters to about 2x the dp width, which a 1080p TV draws at 2x. */
    private static final int IMAGE_W = CARD_W * 2;
    private static final int GAP = 12, HEADER = 18, HEADER_GAP = 6;
    private static final float FOCUS_SCALE = 1.08f;

    private final Context context;
    private final Host host;
    private final float density;
    private final IptvClient client;
    private final String player;
    private final int limit;
    private final List<String> skipCategories;
    private final AtomicFile cacheFile;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService imagePool = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> images = new LruCache<String, Bitmap>(4 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private final LinearLayout container;
    private final TextView header;
    private final RecyclerView row;
    private final Adapter adapter = new Adapter();
    private final String title;

    private long lastRefresh;
    private boolean released;
    private boolean gridAtTop = true;
    private boolean placed;
    private int lastIndex;

    /**
     * The rail, added to {@code root} just below {@code above}; null when iptv_rail is off or the login file is
     * missing or empty.
     */
    static IptvRail create(Context context, ViewGroup root, View above, Host host) {
        Resources res = context.getResources();
        if (!res.getBoolean(R.bool.iptv_rail)) return null;
        // Looked up by name: iptv_secrets.xml is optional, so the launcher still builds without it.
        String server = secret(context, "iptv_server");
        String user = secret(context, "iptv_username");
        String pass = secret(context, "iptv_password");
        if (server.isEmpty() || user.isEmpty() || pass.isEmpty()) return null;
        while (server.endsWith("/")) server = server.substring(0, server.length() - 1);
        return new IptvRail(context, root, above, host, new IptvClient(server, user, pass));
    }

    private static String secret(Context context, String name) {
        int id = context.getResources().getIdentifier(name, "string", context.getPackageName());
        return id == 0 ? "" : context.getString(id).trim();
    }

    private IptvRail(Context context, ViewGroup root, View above, Host host, IptvClient client) {
        this.context = context;
        this.host = host;
        this.client = client;
        Resources res = context.getResources();
        density = res.getDisplayMetrics().density;
        player = res.getString(R.string.iptv_rail_player).trim();
        limit = Math.max(1, Math.min(50, res.getInteger(R.integer.iptv_rail_count)));
        skipCategories = Arrays.asList(res.getStringArray(R.array.iptv_rail_skip_categories));
        title = res.getString(R.string.iptv_rail_title);
        cacheFile = new AtomicFile(new File(context.getFilesDir(), "iptv_rail.tsv"));

        container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setClipChildren(false);
        container.setClipToPadding(false);
        container.setVisibility(View.GONE);
        container.setAlpha(0f);
        // Closed to focus except while the user is on it: the rail comes before the grid in the view tree, so
        // any default focus search (Home coming up, a restored focus) would otherwise land here, not on the dock.
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

        row = new RecyclerView(context);
        row.setLayoutManager(new LinearLayoutManager(context, RecyclerView.HORIZONTAL, false));
        row.setClipChildren(false);
        row.setClipToPadding(false);
        row.setPaddingRelative(0, dp(6), dp(66), dp(6));
        row.setOverScrollMode(View.OVER_SCROLL_NEVER);
        row.setHorizontalScrollBarEnabled(false);
        row.setItemAnimator(null);
        row.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        row.setAdapter(adapter);
        container.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(CARD_H + 12)));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height());
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.setMarginStart(dp(66)); // the dock's first tile: root padding 56dp + grid padding 10dp
        int at = root.indexOfChild(above);
        root.addView(container, at < 0 ? root.getChildCount() : at, lp);

        List<IptvItem> cached = readCache();
        if (cached != null) apply(cached);
    }

    // ---- placement and visibility ----------------------------------------------------------------

    /** Total height of the rail in pixels. */
    int height() {
        return dp(HEADER + HEADER_GAP + CARD_H + 12);
    }

    /**
     * Parks the rail just above the dock app's name, whose top edge is {@code heroTop} in root coordinates, but never
     * higher than {@code minTop} (the bottom of the clock and the pills).
     */
    void placeAbove(float heroTop, float minTop) {
        float y = Math.max(minTop + dp(8), heroTop - height() - dp(4));
        container.setY(y);
        placed = true;
        updateVisibility();
    }

    /** The current top edge of this rail in root coordinates, for another rail to anchor above it. */
    float top() {
        return container.getY();
    }

    /** True once {@link #placeAbove} has run, so another rail can anchor above this one. */
    boolean isPlaced() {
        return placed;
    }

    /** The grid scrolled away from the dock (false) or back to it (true): the rail only shows with the dock. */
    void setGridAtTop(boolean atTop) {
        if (gridAtTop == atTop) return;
        gridAtTop = atTop;
        updateVisibility();
    }

    /** True while the rail is on screen and has cards to focus. */
    boolean isShowing() {
        return container.getVisibility() == View.VISIBLE && adapter.getItemCount() > 0 && gridAtTop;
    }

    /** Focuses the card that was focused last (or the first). */
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

    /** Asks the provider again unless that happened in the last {@link #MIN_REFRESH_MS}. Call when Home comes up. */
    void refresh() {
        if (released) return;
        long now = SystemClock.elapsedRealtime();
        if (lastRefresh != 0 && now - lastRefresh < MIN_REFRESH_MS) return;
        lastRefresh = now;
        worker.execute(() -> {
            try {
                List<IptvItem> items = client.newest(limit, skipCategories);
                if (items.isEmpty()) return; // keep what is cached rather than blanking the row
                writeCache(items);
                main.post(() -> {
                    if (!released) apply(items);
                });
            } catch (Exception e) {
                // Provider down or unreachable: keep the cached cards. The message never holds the login.
                Log.w(TAG, "refresh failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                lastRefresh = 0; // try again next time Home comes up
            }
        });
    }

    void release() {
        released = true;
        worker.shutdownNow();
        imagePool.shutdownNow();
        images.evictAll();
    }

    private void apply(List<IptvItem> items) {
        adapter.setItems(items);
        updateVisibility();
    }

    private List<IptvItem> readCache() {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(cacheFile.openRead(), StandardCharsets.UTF_8))) {
            if (!"iptv-rail 1".equals(in.readLine())) return null;
            List<IptvItem> items = new ArrayList<>();
            for (String line; (line = in.readLine()) != null; ) {
                String[] f = line.split("\t", -1);
                if (f.length != 5) continue;
                try {
                    items.add(new IptvItem(Long.parseLong(f[0]), Long.parseLong(f[1]), f[2], f[3], f[4]));
                } catch (NumberFormatException ignored) {
                    // a damaged line is skipped, the rest still shows
                }
            }
            return items;
        } catch (IOException e) {
            return null; // no cache yet
        }
    }

    private void writeCache(List<IptvItem> items) {
        StringBuilder b = new StringBuilder("iptv-rail 1\n");
        for (IptvItem it : items) {
            b.append(it.streamId).append('\t').append(it.added).append('\t').append(clean(it.name)).append('\t')
                    .append(clean(it.poster)).append('\t').append(clean(it.extension)).append('\n');
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

    private static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    // ---- playing ---------------------------------------------------------------------------------

    /**
     * Opens the movie in the player (iptv_rail_player). VLC was dropped: handed a second movie while the first was
     * still open, even in the background, it lost its video surface (black screen) or closed.
     */
    private void play(IptvItem item) {
        Uri uri = Uri.parse(client.streamUrl(item));
        String type = "mkv".equals(item.extension) ? "video/x-matroska" : "video/*";
        Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
                .putExtra("title", item.name)
                // A fresh player screen each time, not the one left behind by the last movie.
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        if (!player.isEmpty()) {
            try {
                context.startActivity(new Intent(i).setPackage(player));
                return;
            } catch (ActivityNotFoundException | SecurityException e) {
                Log.w(TAG, "player " + player + " is missing; asking any video player");
            }
        }
        try {
            context.startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(context, R.string.iptv_rail_no_player, Toast.LENGTH_LONG).show();
        }
    }

    private String headerFor(IptvItem it) {
        String added = addedText(it.added);
        return title + "  ·  " + it.name + (added.isEmpty() ? "" : " · " + added);
    }

    private String addedText(long added) {
        if (added <= 0) return "";
        long days = (System.currentTimeMillis() / 1000 - added) / (24 * 60 * 60);
        Resources res = context.getResources();
        if (days <= 0) return res.getString(R.string.iptv_rail_added_today);
        if (days == 1) return res.getString(R.string.iptv_rail_added_yesterday);
        return res.getString(R.string.iptv_rail_added_days, (int) days);
    }

    // ---- images ----------------------------------------------------------------------------------

    private void loadImage(ImageView view, String url) {
        view.setTag(url);
        if (url == null) {
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

    // ---- cards -----------------------------------------------------------------------------------

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        private final List<IptvItem> items = new ArrayList<>();

        Adapter() {
            setHasStableIds(true);
        }

        void setItems(List<IptvItem> next) {
            boolean same = next.size() == items.size();
            for (int i = 0; same && i < next.size(); i++) {
                same = next.get(i).streamId == items.get(i).streamId;
            }
            if (same) return;
            items.clear();
            items.addAll(next);
            notifyDataSetChanged();
        }

        @Override
        public long getItemId(int position) {
            return items.get(position).streamId;
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(buildCard());
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            IptvItem it = items.get(position);
            h.itemView.setContentDescription(headerFor(it));
            h.name.setText(it.name);
            loadImage(h.image, IptvClient.posterUrl(it.poster));
            h.itemView.setOnClickListener(v -> play(it));
        }
    }

    private final class Holder extends RecyclerView.ViewHolder {
        final ImageView image;
        /** The movie's name on the placeholder, hidden by the poster once it loads. */
        final TextView name;

        Holder(View card) {
            super(card);
            image = card.findViewWithTag("image");
            name = card.findViewWithTag("name");
            image.setTag(null);
            name.setTag(null);
            card.setOnFocusChangeListener((v, focused) -> {
                float s = focused ? FOCUS_SCALE : 1f;
                v.animate().scaleX(s).scaleY(s).setDuration(140).start();
                v.setElevation(focused ? 8f * density : 0f);
                if (focused) {
                    int pos = getBindingAdapterPosition();
                    if (pos != RecyclerView.NO_POSITION) {
                        lastIndex = pos;
                        header.setText(headerFor(adapter.items.get(pos)));
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
                        return pos == (rtl ? adapter.getItemCount() - 1 : 0); // the row's ends stay put
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        return pos == (rtl ? 0 : adapter.getItemCount() - 1);
                    default:
                        return false;
                }
            });
        }
    }

    /** One card: a rounded 2:3 poster with a white ring while focused; the name shows until the poster loads. */
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
