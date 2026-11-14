package com.gathin.tvhome;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * "Beast": the Mediarium web panel (config beast_url) full screen, opened from the Beast pill in the top bar.
 * You sign in once with the remote; the session cookie is kept, so later visits open signed in. Only the panel's own
 * address loads here: a link to anywhere else is refused, so the page can't wander off to the internet.
 * Back goes back inside the panel, and leaves when there is nothing to go back to.
 */
public class BeastActivity extends Activity {
    /** Intent extra: the page to open. Without it the page is beast_url. */
    static final String EXTRA_URL = "com.gathin.tvhome.URL";
    /** CSS width a page opened from EXTRA_URL (the Home Assistant dashboard) is laid out at. A 1080p TV at density 320
     *  reports only about 960 CSS px, which gives a dashboard its tablet layout; 1920 gives it the desktop one. */
    private static final int DESKTOP_WIDTH = 1920;
    private boolean desktop;
    private WebView web;
    private String home;
    private String origin;
    private Cursor cursor;
    private float cx = -1, cy = -1; // pointer position in the page, in pixels
    private long pressTime;

    @SuppressLint("SetJavaScriptEnabled") // the panel is a JavaScript app; nothing else is ever loaded
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        // The page to open: the house pill passes the Home Assistant dashboard; Beast opens its own panel.
        String given = getIntent().getStringExtra(EXTRA_URL);
        desktop = given != null && !given.trim().isEmpty();
        home = desktop ? given.trim() : getString(R.string.beast_url).trim();
        android.net.Uri u = android.net.Uri.parse(home);
        origin = u.getScheme() + "://" + u.getAuthority();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportMultipleWindows(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        CookieManager.getInstance().setAcceptCookie(true);

        TextView error = new TextView(this);
        error.setTextColor(0xB3FFFFFF);
        error.setTextSize(20);
        error.setPadding(80, 80, 80, 80);
        error.setVisibility(View.GONE);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // Stay on the panel: anything off its own address is not opened.
                return !request.getUrl().toString().startsWith(origin);
            }

            @Override
            public void onReceivedError(WebView view, int code, String description, String failingUrl) {
                error.setText(getString(R.string.beast_unreachable, origin));
                error.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageCommitVisible(WebView view, String url) {
                widen(view);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                CookieManager.getInstance().flush(); // keep the sign-in across restarts
                widen(view);
            }
        });
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(error, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        cursor = new Cursor(this);
        root.addView(cursor, new FrameLayout.LayoutParams(Cursor.W, Cursor.H));
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (cx < 0) { cx = (r - l) / 2f; cy = (b - t) / 2f; }
            placeCursor();
        });
        setContentView(root);
        web.requestFocus();
        if (state != null) web.restoreState(state);
        else web.loadUrl(home);
    }

    /** Lays the Home Assistant page out DESKTOP_WIDTH CSS px wide by rewriting its viewport tag; Beast is left alone. */
    private void widen(WebView view) {
        if (!desktop) return;
        view.evaluateJavascript("(function(){var m=document.querySelector('meta[name=viewport]');"
                + "if(!m){m=document.createElement('meta');m.name='viewport';document.head.appendChild(m);}"
                + "m.setAttribute('content','width=" + DESKTOP_WIDTH + "');})();", null);
    }

    /**
     * The panel is a mouse-and-touch website, so the arrows drive a pointer instead of the page's own focus order:
     * they move it (faster while held), push against a screen edge to scroll what is under it, and OK taps where it
     * points (hold OK for a long-press). Keys go to the on-screen keyboard as usual while it is open.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        int dx = 0, dy = 0;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: dx = -1; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: dx = 1; break;
            case KeyEvent.KEYCODE_DPAD_UP: dy = -1; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: dy = 1; break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                    pressTime = SystemClock.uptimeMillis();
                    touch(MotionEvent.ACTION_DOWN);
                } else if (e.getAction() == KeyEvent.ACTION_UP && pressTime != 0) {
                    touch(MotionEvent.ACTION_UP);
                    pressTime = 0;
                }
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN) movePointer(dx, dy, e.getRepeatCount());
        return true;
    }

    private void movePointer(int dx, int dy, int repeat) {
        View root = cursor == null ? null : (View) cursor.getParent();
        if (root == null || root.getWidth() == 0) return;
        float w = root.getWidth(), h = root.getHeight();
        float step = Math.min(w / 120f + repeat * (w / 120f), w / 10f); // ~16px tap, ramps to ~192px held at 1080p
        float nx = cx + dx * step, ny = cy + dy * step;
        float edge = 2;
        // Already against the edge in that direction: scroll the page (or the panel under the pointer) instead.
        if ((dy < 0 && cy <= edge) || (dy > 0 && cy >= h - edge)) scroll(0, dy);
        else if ((dx < 0 && cx <= edge) || (dx > 0 && cx >= w - edge)) scroll(dx, 0);
        cx = Math.max(0, Math.min(w - 1, nx));
        cy = Math.max(0, Math.min(h - 1, ny));
        placeCursor();
        hover();
    }

    private void placeCursor() {
        if (cursor == null || cx < 0) return;
        cursor.setTranslationX(cx - Cursor.TIP);
        cursor.setTranslationY(cy - Cursor.TIP);
    }

    private MotionEvent.PointerProperties[] props(int tool) {
        MotionEvent.PointerProperties p = new MotionEvent.PointerProperties();
        p.id = 0;
        p.toolType = tool;
        return new MotionEvent.PointerProperties[]{p};
    }

    private MotionEvent.PointerCoords[] coords() {
        MotionEvent.PointerCoords c = new MotionEvent.PointerCoords();
        c.x = cx;
        c.y = cy;
        c.pressure = 1f;
        c.size = 1f;
        return new MotionEvent.PointerCoords[]{c};
    }

    private void touch(int action) {
        if (web == null || cx < 0) return;
        long now = SystemClock.uptimeMillis();
        MotionEvent m = MotionEvent.obtain(pressTime, now, action, 1, props(MotionEvent.TOOL_TYPE_FINGER),
                coords(), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        web.dispatchTouchEvent(m);
        m.recycle();
    }

    private void hover() {
        if (web == null) return;
        long now = SystemClock.uptimeMillis();
        MotionEvent m = MotionEvent.obtain(now, now, MotionEvent.ACTION_HOVER_MOVE, 1,
                props(MotionEvent.TOOL_TYPE_MOUSE), coords(), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0);
        web.dispatchGenericMotionEvent(m);
        m.recycle();
    }

    /** A mouse-wheel notch at the pointer, so an inner scrolling panel scrolls as well as the page. */
    private void scroll(int dx, int dy) {
        if (web == null) return;
        long now = SystemClock.uptimeMillis();
        MotionEvent.PointerCoords[] c = coords();
        c[0].setAxisValue(MotionEvent.AXIS_VSCROLL, -dy); // wheel up is positive
        c[0].setAxisValue(MotionEvent.AXIS_HSCROLL, dx);
        MotionEvent m = MotionEvent.obtain(now, now, MotionEvent.ACTION_SCROLL, 1,
                props(MotionEvent.TOOL_TYPE_MOUSE), c, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0);
        web.dispatchGenericMotionEvent(m);
        m.recycle();
    }

    /** The pointer: a white arrow with a dark outline, its tip at (TIP, TIP). */
    private static final class Cursor extends View {
        static final int W = 44, H = 56, TIP = 4;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path arrow = new Path();

        Cursor(android.content.Context c) {
            super(c);
            fill.setColor(Color.WHITE);
            line.setColor(0xE6000000);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(3f);
            line.setStrokeJoin(Paint.Join.ROUND);
            arrow.moveTo(TIP, TIP);
            arrow.lineTo(TIP, 46);
            arrow.lineTo(15, 36);
            arrow.lineTo(23, 52);
            arrow.lineTo(30, 49);
            arrow.lineTo(22, 33);
            arrow.lineTo(37, 33);
            arrow.close();
            setFocusable(false);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawPath(arrow, fill);
            canvas.drawPath(arrow, line);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && web != null && web.canGoBack()) {
            web.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (web != null) web.saveState(out);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) web.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
