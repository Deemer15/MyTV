package com.gathin.tvhome;

import android.content.Context;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Handler;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The motion warning on the house pill: while Home is in front it keeps one live connection to Home Assistant (its
 * server-sent events stream) and tells the listener when any watched sensor (config ha_alert_entities) is on, so
 * the pill can turn red. It starts in {@link #start} (Home resumed) and closes in {@link #stop} (Home paused), so
 * nothing runs in the background. A fresh read of the sensors on each start catches motion that happened while
 * Home was away.
 *
 * Pressing the pill calls {@link #acknowledge}: this TV clears at once and fires a {@value #ACK_EVENT} event, which
 * every other TV hears on its own stream and clears too. An acknowledged sensor stays quiet until it turns off and
 * on again (compared by Home Assistant's own last_changed, never by the TV's clock).
 *
 * The long-lived token only ever goes in an Authorization header and is never logged. Every call blocks, so none
 * runs on the main thread.
 */
final class HaAlert {
    interface Listener {
        /** On the main thread: true while a watched sensor is on and nobody has acknowledged it. */
        void onAlert(boolean active);
    }

    private static final String TAG = "HaAlert";
    static final String ACK_EVENT = "mytv_motion_ack";
    private static final int CONNECT_MS = 6000;
    private static final int READ_MS = 15000;
    private static final int STREAM_READ_MS = 90000;   // Home Assistant sends a ping about every 30 s
    private static final long RETRY_MS = 5000;

    private final Handler main;
    private final Listener listener;
    private final String base;
    private final String token;
    private final Set<String> entities;
    private final Object state = new Object();
    /** Watched sensors that are on now, with Home Assistant's last_changed for each. Guarded by state. */
    private final Map<String, String> onChanged = new HashMap<>();
    /** What onChanged held when the pill was last acknowledged, here or on another TV. Guarded by state. */
    private final Map<String, String> acked = new HashMap<>();
    private boolean alerting;                           // guarded by state
    private final ExecutorService poster = Executors.newSingleThreadExecutor();

    private volatile boolean running;
    private volatile Thread worker;
    private volatile HttpURLConnection live;

    /** Null when the feature is off, the token is missing, or there is nothing to watch. */
    static HaAlert create(Context context, Handler main, Listener listener) {
        Resources res = context.getResources();
        if (!res.getBoolean(R.bool.ha_alert) || !res.getBoolean(R.bool.ha_page)) return null;
        String url = context.getString(R.string.ha_url).trim();
        String token = secret(context, "ha_token");
        String[] list = res.getStringArray(R.array.ha_alert_entities);
        if (url.isEmpty() || token.isEmpty() || list.length == 0) return null;
        Uri u = Uri.parse(url);
        if (u.getScheme() == null || u.getAuthority() == null) return null;
        return new HaAlert(main, listener, u.getScheme() + "://" + u.getAuthority(), token, list);
    }

    private static String secret(Context context, String name) {
        int id = context.getResources().getIdentifier(name, "string", context.getPackageName());
        return id == 0 ? "" : context.getString(id).trim();
    }

    private HaAlert(Handler main, Listener listener, String base, String token, String[] entities) {
        this.main = main;
        this.listener = listener;
        this.base = base;
        this.token = token;
        this.entities = new HashSet<>();
        for (String e : entities) {
            if (e != null && !e.trim().isEmpty()) this.entities.add(e.trim());
        }
    }

    /** Home is in front: read the sensors, then hold the live connection. */
    synchronized void start() {
        if (running) return;
        running = true;
        Thread t = new Thread(this::run, "HaAlert");
        t.setDaemon(true);
        worker = t;
        t.start();
    }

    /** Home is not in front: drop the connection. The acknowledged state is kept for the next start. */
    synchronized void stop() {
        running = false;
        Thread t = worker;
        worker = null;
        if (t != null) t.interrupt();
        closeLive();
    }

    void release() {
        stop();
        poster.shutdownNow();
    }

    /**
     * The pill was pressed: clear here at once, and tell every other TV to clear. Call on the main thread; the
     * network part runs on its own thread.
     */
    void acknowledge() {
        ackAll();
        try {
            poster.execute(this::fireAck);
        } catch (RuntimeException ignored) {
            // released: nothing to tell
        }
    }

    // ---- worker -----------------------------------------------------------------------------------

    private void run() {
        Thread me = Thread.currentThread();
        while (running && worker == me) {
            try {
                readStates();
                stream(me);
            } catch (IOException e) {
                if (running) Log.w(TAG, "connection ended: " + e.getClass().getSimpleName());
            }
            try {
                Thread.sleep(RETRY_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** One read of every watched sensor, for the state at start and after a dropped connection. */
    private void readStates() throws IOException {
        Map<String, String> now = new HashMap<>();
        for (String id : entities) {
            HttpURLConnection c = open("/api/states/" + id, "GET");
            try {
                int code = c.getResponseCode();
                if (code == 401) throw new IOException("HTTP 401: the Home Assistant token was refused");
                if (code != 200) continue;
                JSONObject o = new JSONObject(read(c));
                if ("on".equals(o.optString("state"))) now.put(id, o.optString("last_changed"));
            } catch (JSONException e) {
                Log.w(TAG, "unreadable state for a sensor");
            } finally {
                c.disconnect();
            }
        }
        synchronized (state) {
            onChanged.clear();
            onChanged.putAll(now);
        }
        evaluate();
    }

    private void stream(Thread me) throws IOException {
        HttpURLConnection c = open("/api/stream?restrict=state_changed," + ACK_EVENT, "GET");
        c.setReadTimeout(STREAM_READ_MS);
        c.setRequestProperty("Accept", "text/event-stream");
        live = c;
        try {
            if (!running || worker != me) return;
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code + " from the event stream");
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(),
                    StandardCharsets.UTF_8))) {
                String line;
                while (running && worker == me && (line = r.readLine()) != null) {
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring(5).trim();
                    if (data.isEmpty() || data.equals("ping")) continue;
                    handle(data);
                }
            }
        } finally {
            c.disconnect();
        }
    }

    private void handle(String data) {
        try {
            JSONObject o = new JSONObject(data);
            String type = o.optString("event_type");
            if (ACK_EVENT.equals(type)) {
                ackAll();
                return;
            }
            if (!"state_changed".equals(type)) return;
            JSONObject d = o.optJSONObject("data");
            if (d == null) return;
            String id = d.optString("entity_id");
            if (!entities.contains(id)) return;
            JSONObject n = d.optJSONObject("new_state");
            synchronized (state) {
                if (n != null && "on".equals(n.optString("state"))) onChanged.put(id, n.optString("last_changed"));
                else onChanged.remove(id);
            }
            evaluate();
        } catch (JSONException e) {
            // a line that is not an event: ignore it
        }
    }

    // ---- state ------------------------------------------------------------------------------------

    /** Everything on right now counts as seen, here or on another TV. */
    private void ackAll() {
        synchronized (state) {
            acked.clear();
            acked.putAll(onChanged);
        }
        publish(false);
    }

    /** Red while some sensor is on that has not been acknowledged since it last turned on. */
    private void evaluate() {
        boolean fresh = false;
        synchronized (state) {
            for (Map.Entry<String, String> e : onChanged.entrySet()) {
                if (!e.getValue().equals(acked.get(e.getKey()))) fresh = true;
            }
        }
        publish(fresh);
    }

    private void publish(boolean active) {
        synchronized (state) {
            if (alerting == active) return;
            alerting = active;
        }
        main.post(() -> listener.onAlert(active));
    }

    // ---- network ----------------------------------------------------------------------------------

    private void fireAck() {
        HttpURLConnection c = null;
        try {
            c = open("/api/events/" + ACK_EVENT, "POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = c.getOutputStream()) {
                out.write("{}".getBytes(StandardCharsets.UTF_8));
            }
            c.getResponseCode();
        } catch (IOException e) {
            Log.w(TAG, "could not tell the other TVs: " + e.getClass().getSimpleName());
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private HttpURLConnection open(String path, String method) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(CONNECT_MS);
        c.setReadTimeout(READ_MS);
        c.setRequestProperty("Authorization", "Bearer " + token);   // header only: never in a URL or a log
        c.setRequestProperty("User-Agent", "CleanYourTV-Launcher/1.0");
        return c;
    }

    private static String read(HttpURLConnection c) throws IOException {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(),
                StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[2048];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
            return sb.toString();
        }
    }

    /** Closes the open stream from another thread, so a blocked read ends; off the main thread, which may not do I/O. */
    private void closeLive() {
        final HttpURLConnection c = live;
        live = null;
        if (c != null) new Thread(c::disconnect, "HaAlert-close").start();
    }
}
