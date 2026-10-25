package com.zhuojun1024.dsh;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.Manifest;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Keeps one DSH operator event stream open while the app is in the background.
 *
 * <p>The stream is a separate API operator minted with {@code /connect?interactions=answer}; the
 * browser cookie belongs to the WebView peer and cannot own a second mux socket. The operator is
 * answering because DSH refuses passive {@code $events} subscriptions, but this service only reads
 * status frames: approval waterfalls stay with the browser UI. A session becoming idle after this
 * service observed it running posts one notification, and only when no Activity window has focus.
 */
public final class SessionMonitorService extends Service {
    private static final String TAG = "DshMonitor";
    static final String ACTION_STOP = "com.zhuojun1024.dsh.action.STOP_MONITOR";

    private static final String MONITOR_CHANNEL = "dsh_monitor";
    private static final String COMPLETE_CHANNEL = "dsh_complete";
    private static final String LOGIN_CHANNEL = "dsh_login";
    private static final int MONITOR_NOTIFICATION_ID = 0x1001;
    private static final int LOGIN_NOTIFICATION_ID = 0x1002;
    private static final long RECONNECT_MIN_MS = 2_000;
    private static final long RECONNECT_MAX_MS = 60_000;
    private static final String OPEN_EVENTS =
            "{\"type\":\"open\",\"streamId\":\"events\",\"endpoint\":\"$events\",\"payload\":{\"args\":{}}}";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<String> runningSessions = Collections.synchronizedSet(new HashSet<>());
    private final AtomicInteger generation = new AtomicInteger();
    private final AtomicBoolean connecting = new AtomicBoolean();
    private final Runnable reconnect = this::connect;

    private OkHttpClient http;
    private volatile WebSocket socket;
    private volatile String apiCookie;
    private volatile boolean stopped;
    private long reconnectDelay = RECONNECT_MIN_MS;

    /** Starts the monitor from the foreground login flow. */
    static void start(Context context) {
        try {
            context.startForegroundService(new Intent(context, SessionMonitorService.class));
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to start the session monitor", e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels();
        http = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            startForegroundCompat(buildMonitorNotification());
        } catch (RuntimeException e) {
            Log.e(TAG, "Unable to promote the monitor to a foreground service", e);
            stopSelf();
            return START_NOT_STICKY;
        }
        connect();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopped = true;
        generation.incrementAndGet();
        main.removeCallbacks(reconnect);
        apiCookie = null;
        WebSocket current = socket;
        socket = null;
        if (current != null) current.close(1000, "service stopped");
        if (http != null) {
            http.dispatcher().executorService().shutdown();
            http.connectionPool().evictAll();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void connect() {
        if (stopped || socket != null || !connecting.compareAndSet(false, true)) return;
        final int gen = generation.incrementAndGet();
        main.removeCallbacks(reconnect);
        new Thread(() -> {
            try {
                TokenStore store = new TokenStore(this);
                String host = store.getHost();
                String token = store.getToken();
                if (host == null || host.isEmpty() || token == null || token.isEmpty()) {
                    Log.w(TAG, "No saved DSH credentials");
                    stopSelf();
                    return;
                }
                String cookie = apiCookie;
                if (cookie == null) {
                    cookie = mintApiCookie(host, token);
                    apiCookie = cookie;
                }
                Request request = new Request.Builder()
                        .url(webSocketUrl(host))
                        .header("Cookie", cookie)
                        .header("Origin", originOf(host))
                        .build();
                Listener listener = new Listener(gen, cookie);
                WebSocket ws = http.newWebSocket(request, listener);
                synchronized (this) {
                    if (stopped || gen != generation.get()) {
                        ws.close(1000, "superseded");
                        return;
                    }
                    socket = ws;
                }
                // A failed upgrade may report before this thread stored the socket. Without this
                // clear, connect() would see a dead socket and never schedule another attempt.
                if (listener.isDone()) {
                    synchronized (this) {
                        if (socket == ws) socket = null;
                    }
                }
            } catch (AuthException e) {
                Log.w(TAG, "DSH rejected the saved token; prompting for a new one");
                notifyLoginExpired();
                stopSelf();
            } catch (IOException | RuntimeException e) {
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "Unable to connect the session monitor", e);
                } else {
                    Log.w(TAG, "Unable to connect the session monitor: " + e.getClass().getSimpleName());
                }
                scheduleReconnect(gen);
            } finally {
                connecting.set(false);
            }
        }, "dsh-monitor-connect").start();
    }

    private void scheduleReconnect(int gen) {
        if (stopped || gen != generation.get()) return;
        main.removeCallbacks(reconnect);
        long delay = reconnectDelay;
        reconnectDelay = Math.min(reconnectDelay * 2, RECONNECT_MAX_MS);
        main.postDelayed(reconnect, delay);
    }

    /**
     * Reads one HTTP RPC response with the operator cookie.
     *
     * @param host normalized HTTPS base URL
     * @param cookie API operator cookie
     * @param endpoint DSH RPC endpoint, such as {@code session/list}
     * @param body JSON request body
     * @return parsed JSON response
     * @throws IOException when the request fails or is rejected
     */
    private static JSONObject postRpc(String host, String cookie, String endpoint, String body)
            throws IOException {
        URL url = new URL(host + "api/" + endpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Cookie", cookie);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            if (code == 401) throw new AuthException("operator cookie was rejected");
            if (code != 200) throw new IOException("HTTP " + code + " from " + endpoint);
            try {
                return new JSONObject(readAll(conn.getInputStream()));
            } catch (JSONException e) {
                throw new IOException("Invalid JSON from " + endpoint, e);
            }
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Seeds the running set before the event stream starts, so a session already running when the
     * service launches still notifies when it becomes idle.
     */
    private void seedRunningSessions(String cookie) {
        try {
            String host = new TokenStore(this).getHost();
            if (host == null || host.isEmpty()) return;
            String body = "{\"type\":\"client-request\",\"rpcId\":\"" + UUID.randomUUID()
                    + "\",\"method\":\"session/list\",\"payload\":{\"args\":{\"_request\":{}}}}";
            JSONObject response = postRpc(host, cookie, "session/list", body);
            JSONObject result = response.optJSONObject("result");
            if (result == null || !result.optBoolean("ok")) return;
            JSONObject value = result.optJSONObject("value");
            JSONArray items = value == null ? null : value.optJSONArray("items");
            if (items == null) return;
            runningSessions.clear();
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null || !item.optBoolean("running")) continue;
                String id = item.optString("sessionId", "");
                if (!id.isEmpty()) runningSessions.add(id);
            }
        } catch (AuthException e) {
            apiCookie = null;
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Unable to seed running sessions", e);
        }
    }

    private void handleFrame(String text) {
        if (stopped) return;
        try {
            JSONObject frame = new JSONObject(text);
            if (!"item".equals(frame.optString("type"))) return;
            JSONObject item = frame.optJSONObject("value");
            if (item == null || !"emit".equals(item.optString("type"))) return;
            if (!"api-session/status".equals(item.optString("event"))) return;
            JSONArray args = item.optJSONArray("args");
            if (args == null || args.length() < 2) return;
            String sessionId = args.optString(0, "");
            if (sessionId.isEmpty()) return;
            if (args.optBoolean(1, false)) {
                runningSessions.add(sessionId);
                return;
            }
            if (runningSessions.remove(sessionId) && !AppVisibility.isForeground()) {
                notifySessionComplete(sessionId);
            }
        } catch (Exception e) {
            Log.w(TAG, "Ignoring a malformed event frame", e);
        }
    }

    @SuppressLint("InlinedApi")
    private boolean canNotify() {
        return Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("InlinedApi")
    private void notifySessionComplete(String sessionId) {
        if (!canNotify()) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        String shortId = sessionId.length() <= 8 ? sessionId : sessionId.substring(sessionId.length() - 8);
        PendingIntent content = openAppIntent();
        Notification notification = new Notification.Builder(this, COMPLETE_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_dsh)
                .setContentTitle(getString(R.string.notification_complete_title))
                .setContentText(getString(R.string.notification_complete_text, shortId))
                .setContentIntent(content)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setWhen(System.currentTimeMillis())
                .build();
        int id = 0x2000 + (sessionId.hashCode() & 0x0FFF);
        try {
            manager.notify(id, notification);
        } catch (SecurityException e) {
            Log.w(TAG, "Notification permission is not granted", e);
        }
    }

    private void notifyLoginExpired() {
        if (!canNotify()) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        Intent open = new Intent(this, MainActivity.class)
                .putExtra(MainActivity.EXTRA_FORCE_LOGIN, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(
                this, 2, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, LOGIN_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_dsh)
                .setContentTitle(getString(R.string.notification_login_title))
                .setContentText(getString(R.string.notification_login_text))
                .setContentIntent(content)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_ERROR)
                .build();
        try {
            manager.notify(LOGIN_NOTIFICATION_ID, notification);
        } catch (SecurityException e) {
            Log.w(TAG, "Notification permission is not granted", e);
        }
    }

    private void createChannels() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel monitor = new NotificationChannel(
                MONITOR_CHANNEL,
                getString(R.string.notification_channel_monitor),
                NotificationManager.IMPORTANCE_LOW);
        monitor.setDescription(getString(R.string.notification_channel_monitor_desc));
        monitor.setShowBadge(false);
        NotificationChannel complete = new NotificationChannel(
                COMPLETE_CHANNEL,
                getString(R.string.notification_channel_complete),
                NotificationManager.IMPORTANCE_HIGH);
        complete.setDescription(getString(R.string.notification_channel_complete_desc));
        complete.setShowBadge(true);
        NotificationChannel login = new NotificationChannel(
                LOGIN_CHANNEL,
                getString(R.string.notification_channel_login),
                NotificationManager.IMPORTANCE_HIGH);
        login.setDescription(getString(R.string.notification_channel_login_desc));
        login.setShowBadge(true);
        manager.createNotificationChannel(monitor);
        manager.createNotificationChannel(complete);
        manager.createNotificationChannel(login);
    }

    private Notification buildMonitorNotification() {
        Intent stop = new Intent(this, SessionMonitorService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(
                this, 1, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Action stopAction = new Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_stat_dsh),
                getString(R.string.notification_monitor_stop),
                stopPending).build();
        Notification.Builder builder = new Notification.Builder(this, MONITOR_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_dsh)
                .setContentTitle(getString(R.string.notification_monitor_title))
                .setContentText(getString(R.string.notification_monitor_text))
                .setContentIntent(openAppIntent())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setShowWhen(false)
                .addAction(stopAction);
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return builder.build();
    }

    private PendingIntent openAppIntent() {
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void startForegroundCompat(Notification notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                    MONITOR_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(MONITOR_NOTIFICATION_ID, notification);
        }
    }

    private static String mintApiCookie(String host, String token) throws IOException {
        URL url = new URL(UrlTools.buildConnectUrl(host, token));
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept-Encoding", "identity");
            int code = conn.getResponseCode();
            if (code == 401 || code == 403) throw new AuthException("token was rejected");
            if (code != 302 && code != 303) throw new IOException("connect returned HTTP " + code);
            String cookies = null;
            for (Map.Entry<String, List<String>> entry : conn.getHeaderFields().entrySet()) {
                if (entry.getKey() == null || !entry.getKey().equalsIgnoreCase("Set-Cookie")) continue;
                for (String value : entry.getValue()) {
                    if (value == null) continue;
                    String pair = value.split(";", 2)[0].trim();
                    if (!pair.contains("=")) continue;
                    cookies = cookies == null ? pair : cookies + "; " + pair;
                }
            }
            if (cookies == null || cookies.isEmpty()) {
                throw new IOException("connect did not set an operator cookie");
            }
            return cookies;
        } finally {
            conn.disconnect();
        }
    }

    private static String webSocketUrl(String host) {
        String base = host.endsWith("/") ? host : host + "/";
        if (!base.startsWith("https://")) throw new IllegalArgumentException("HTTPS is required");
        return "wss://" + base.substring("https://".length()) + "api/remote.mux";
    }

    private static String originOf(String host) {
        try {
            URL url = new URL(host);
            return url.getProtocol() + "://" + url.getAuthority();
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid DSH URL", e);
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private final class Listener extends WebSocketListener {
        private final int gen;
        private final String cookie;
        private volatile boolean done;
        private boolean opened;

        Listener(int gen, String cookie) {
            this.gen = gen;
            this.cookie = cookie;
        }

        boolean isDone() {
            return done;
        }

        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            if (stopped || gen != generation.get()) {
                done = true;
                webSocket.close(1000, "superseded");
                return;
            }
            opened = true;
            reconnectDelay = RECONNECT_MIN_MS;
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.cancel(LOGIN_NOTIFICATION_ID);
            Log.i(TAG, "Session event stream connected");
            new Thread(() -> {
                seedRunningSessions(cookie);
                if (!stopped && gen == generation.get()) webSocket.send(OPEN_EVENTS);
            }, "dsh-monitor-seed").start();
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            handleFrame(text);
        }

        @Override
        public void onMessage(WebSocket webSocket, ByteString bytes) {
            handleFrame(bytes.utf8());
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable failure, Response response) {
            done = true;
            int code = response == null ? 0 : response.code();
            Log.w(TAG, "Session event stream failed" + (code == 0 ? "" : " (HTTP " + code + ")"), failure);
            synchronized (SessionMonitorService.this) {
                if (socket == webSocket) socket = null;
            }
            if (stopped || gen != generation.get()) return;
            if (opened || (code >= 400 && code < 500)) apiCookie = null;
            scheduleReconnect(gen);
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            done = true;
            synchronized (SessionMonitorService.this) {
                if (socket == webSocket) socket = null;
            }
            if (stopped || gen != generation.get()) return;
            apiCookie = null;
            scheduleReconnect(gen);
        }
    }

    /** Marks a rejected operator exchange separately from transport failures. */
    private static final class AuthException extends IOException {
        AuthException(String message) {
            super(message);
        }
    }
}
