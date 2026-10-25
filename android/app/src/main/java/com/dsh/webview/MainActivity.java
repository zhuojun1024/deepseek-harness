package com.dsh.webview;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.Manifest;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.ConsoleMessage;
import android.webkit.DownloadListener;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedDispatcher;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLException;

/**
 * Single-Activity WebView shell for DSH Web.
 *
 * <p>The page is always served by the configured dsh web instance; this app never bundles the
 * frontend. Login exchanges the launch token for the server's session cookie, then persists the
 * encrypted token for the next launch.
 */
public final class MainActivity extends Activity {
    private static final String TAG = "DshMain";
    private static final int REQ_FILE_CHOOSER = 1001;
    private static final int REQ_NOTIFICATIONS = 1002;
    private static final int AUTH_TIMEOUT_MS = 30_000;
    static final String EXTRA_FORCE_LOGIN = "force_login";
    static final String EXTRA_SESSION_ID = "session_id";

    private FrameLayout root;
    private ScrollView loginScroll;
    private View webContainer;
    private WebView webView;
    private ProgressBar webProgress;
    private View errorPanel;
    private TextView errorDetail;
    private View loadingOverlay;
    private TextView loadingText;
    private EditText urlInput;
    private EditText tokenInput;
    private CheckBox showToken;
    private TextView loginError;
    private Button connectButton;

    private TokenStore store;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AtomicInteger authGeneration = new AtomicInteger();

    private String baseUrl;
    private String pendingHost;
    private String pendingToken;
    private String pendingSessionId;
    private boolean authLoading;
    private String authError;

    private ValueCallback<Uri[]> filePathCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new TokenStore(this);
        configureWindow();
        setContentView(R.layout.activity_main);
        bindViews();
        applyInsets();
        configureBars();
        configureWebView();
        configureLogin();
        maybeRequestNotificationPermission();
        if (savedInstanceState == null) {
            pendingSessionId = takeSessionId(getIntent());
        } else {
            getIntent().removeExtra(EXTRA_SESSION_ID);
        }
        if (Build.VERSION.SDK_INT >= 33) {
            BackCompat33.register(this, this::handleBack);
        }
        if (getIntent().getBooleanExtra(EXTRA_FORCE_LOGIN, false)) {
            forceLogin();
        } else {
            restoreSession();
        }
    }

    private void configureWindow() {
        Window window = getWindow();
        window.setDecorFitsSystemWindows(false);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        window.setStatusBarContrastEnforced(false);
        window.setNavigationBarContrastEnforced(false);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
    }

    private void bindViews() {
        root = findViewById(R.id.root);
        loginScroll = findViewById(R.id.loginScroll);
        urlInput = findViewById(R.id.urlInput);
        tokenInput = findViewById(R.id.tokenInput);
        showToken = findViewById(R.id.showToken);
        loginError = findViewById(R.id.loginError);
        connectButton = findViewById(R.id.connectButton);
        webContainer = findViewById(R.id.webContainer);
        webView = findViewById(R.id.webView);
        webProgress = findViewById(R.id.webProgress);
        errorPanel = findViewById(R.id.errorPanel);
        errorDetail = findViewById(R.id.errorDetail);
        loadingOverlay = findViewById(R.id.loadingOverlay);
        loadingText = findViewById(R.id.loadingText);
        Button retryButton = findViewById(R.id.retryButton);
        Button editServerButton = findViewById(R.id.editServerButton);

        retryButton.setOnClickListener(view -> {
            errorPanel.setVisibility(View.GONE);
            webView.reload();
        });
        editServerButton.setOnClickListener(view -> showLogin(String.valueOf(errorDetail.getText())));
    }

    private void applyInsets() {
        root.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            Insets ime = windowInsets.getInsets(WindowInsets.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            if (ime.bottom > 0 && loginScroll.getVisibility() == View.VISIBLE) {
                View focused = getCurrentFocus();
                if (focused != null) {
                    focused.post(() -> {
                        Rect rect = new Rect(0, 0, focused.getWidth(), focused.getHeight());
                        focused.requestRectangleOnScreen(rect, true);
                    });
                }
            }
            return windowInsets;
        });
        root.requestApplyInsets();
    }

    private void configureBars() {
        boolean night = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        WindowInsetsController controller = getWindow().getInsetsController();
        if (controller == null) return;
        int lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        controller.setSystemBarsAppearance(night ? 0 : lightBars, lightBars);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(false);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setTextZoom(Math.round(getResources().getConfiguration().fontScale * 100f));
        settings.setUserAgentString(settings.getUserAgentString() + " DSHAndroid/0.1");

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, false);

        webView.setBackgroundColor(Color.TRANSPARENT);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);
        webView.setWebViewClient(new DshWebViewClient());
        webView.setWebChromeClient(new DshChromeClient());
        webView.setDownloadListener(new DshDownloadListener());
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
    }

    private void configureLogin() {
        urlInput.setText(UrlTools.DEFAULT_HOST);
        showToken.setOnCheckedChangeListener((buttonView, isChecked) -> {
            int selection = tokenInput.getSelectionStart();
            tokenInput.setInputType(isChecked
                    ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            tokenInput.setTypeface(Typeface.MONOSPACE);
            if (selection >= 0) {
                tokenInput.setSelection(Math.min(selection, tokenInput.getText().length()));
            }
        });
        connectButton.setOnClickListener(view -> submitLogin());
        tokenInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO) {
                submitLogin();
                return true;
            }
            return false;
        });
    }

    private void restoreSession() {
        String host = store.getHost();
        String token = store.getToken();

        if (host != null && !host.isEmpty()) {
            urlInput.setText(host);
            pendingHost = host;
        }
        if (token != null && !token.isEmpty()) {
            tokenInput.setText(token);
            pendingToken = token;
        }

        if (host == null || host.isEmpty()) {
            showLogin(null);
            return;
        }

        String cookie = null;
        try {
            cookie = CookieManager.getInstance().getCookie(host);
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to read the WebView cookie", e);
        }
        if (cookie != null && !cookie.isEmpty()) {
            enterHome(host);
            return;
        }
        if (token != null && !token.isEmpty()) {
            startAuth(host, token, true);
            return;
        }
        showLogin("保存的 token 已失效，请重新输入。");
    }

    private void submitLogin() {
        hideKeyboard();
        String rawUrl = urlInput.getText().toString().trim();
        String rawToken = tokenInput.getText().toString().trim();
        if (rawUrl.isEmpty()) {
            showLoginError(getString(R.string.error_server_required));
            return;
        }

        // Only a URL with token= carries an embedded token; a bare host is not a token.
        String embeddedToken = UrlTools.extractEmbeddedToken(rawUrl);
        if (!embeddedToken.isEmpty()) {
            rawToken = embeddedToken;
        }
        String token = UrlTools.extractToken(rawToken);
        if (token.isEmpty()) {
            showLoginError(getString(R.string.error_token_required));
            return;
        }
        if (token.contains(" ") || token.contains("\n")) {
            showLoginError(getString(R.string.error_token_space));
            return;
        }

        final String host;
        try {
            host = UrlTools.normalizeBaseUrl(rawUrl);
        } catch (IllegalArgumentException e) {
            showLoginError(e.getMessage());
            return;
        }

        urlInput.setText(host);
        tokenInput.setText(token);
        startAuth(host, token, false);
    }

    private void startAuth(String host, String token, boolean automatic) {
        if (host == null || host.isEmpty() || token == null || token.isEmpty()) {
            showLogin("缺少服务器地址或 token。");
            return;
        }
        if (isFinishing() || isDestroyed()) return;

        baseUrl = host;
        pendingHost = host;
        pendingToken = token;
        authLoading = false;
        authError = null;
        final int generation = authGeneration.incrementAndGet();

        loginScroll.setVisibility(View.GONE);
        webContainer.setVisibility(View.GONE);
        errorPanel.setVisibility(View.GONE);
        loadingOverlay.setVisibility(View.VISIBLE);
        loadingText.setText(automatic ? R.string.loading_connecting : R.string.loading_verifying);
        connectButton.setEnabled(false);

        try {
            io.execute(() -> {
                PreflightResult result = preflight(host, token);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || generation != authGeneration.get()) return;
                    connectButton.setEnabled(true);
                    if (!result.ok) {
                        showLogin(result.message);
                        return;
                    }
                    loadAuthUrl(host, token);
                });
            });
        } catch (RejectedExecutionException e) {
            showLogin("应用正在退出，请重新打开。");
        }
    }

    private void loadAuthUrl(String host, String token) {
        baseUrl = host;
        pendingHost = host;
        pendingToken = token;
        authLoading = true;
        authError = null;
        errorPanel.setVisibility(View.GONE);
        loginScroll.setVisibility(View.GONE);
        webContainer.setVisibility(View.VISIBLE);
        loadingOverlay.setVisibility(View.VISIBLE);
        loadingText.setText(R.string.loading_logging_in);
        webView.loadUrl(UrlTools.buildAuthUrl(host, token));

        final int generation = authGeneration.get();
        root.postDelayed(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (generation != authGeneration.get() || !authLoading) return;
            onAuthFailed(authError != null ? authError : "登录超时，请检查网络后重试。");
        }, AUTH_TIMEOUT_MS);
    }

    private void enterHome(String host) {
        baseUrl = host;
        pendingHost = host;
        authLoading = false;
        authError = null;
        loginScroll.setVisibility(View.GONE);
        loadingOverlay.setVisibility(View.GONE);
        errorPanel.setVisibility(View.GONE);
        webContainer.setVisibility(View.VISIBLE);
        webView.loadUrl(host);
        startSessionMonitor();
    }

    private void checkAuthResult(int generation) {
        if (generation != authGeneration.get() || !authLoading) return;
        if (authError != null) {
            onAuthFailed(authError);
            return;
        }
        String current = webView.getUrl();
        if (current == null || current.startsWith("about:")) {
            onAuthFailed("无法确认登录结果，请重试。");
            return;
        }
        if (UrlTools.containsToken(current)) {
            webView.postDelayed(() -> {
                if (generation != authGeneration.get() || !authLoading) return;
                if (authError != null) {
                    onAuthFailed(authError);
                    return;
                }
                String again = webView.getUrl();
                if (again == null || again.startsWith("about:")) {
                    onAuthFailed("无法确认登录结果，请重试。");
                    return;
                }
                if (UrlTools.containsToken(again)) {
                    onAuthFailed("登录后地址仍带着 token，服务器没有完成跳转。");
                    return;
                }
                if (!UrlTools.sameOrigin(baseUrl, again)) {
                    onAuthFailed("登录后被重定向到其他域名：" + again);
                    return;
                }
                onAuthSuccess();
            }, 1200);
            return;
        }
        if (!UrlTools.sameOrigin(baseUrl, current)) {
            onAuthFailed("登录后被重定向到其他域名：" + current);
            return;
        }
        onAuthSuccess();
    }

    private void onAuthSuccess() {
        if (!authLoading) return;
        authLoading = false;
        authError = null;
        getIntent().removeExtra(EXTRA_FORCE_LOGIN);
        if (pendingHost != null && pendingToken != null) {
            try {
                store.save(pendingHost, pendingToken);
            } catch (RuntimeException e) {
                Toast.makeText(
                        this,
                        getString(R.string.save_failed, String.valueOf(e.getMessage())),
                        Toast.LENGTH_LONG).show();
            }
        }
        CookieManager.getInstance().flush();
        webView.clearHistory();
        webProgress.setVisibility(View.GONE);
        loadingOverlay.setVisibility(View.GONE);
        loginScroll.setVisibility(View.GONE);
        errorPanel.setVisibility(View.GONE);
        webContainer.setVisibility(View.VISIBLE);
        startSessionMonitor();
        webView.postDelayed(this::maybeOpenPendingSession, 300);
    }

    private void startSessionMonitor() {
        if (store != null && store.getToken() != null) {
            SessionMonitorService.start(this);
        }
    }

    private void forceLogin() {
        String host = store.getHost();
        if (host != null && !host.isEmpty()) {
            urlInput.setText(host);
            pendingHost = host;
        }
        tokenInput.setText("");
        pendingToken = null;
        showLogin(getString(R.string.login_expired));
    }

    @SuppressLint("InlinedApi")
    private void maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) return;
        requestPermissions(
                new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
    }

    private void onAuthFailed(String message) {
        if (!authLoading) return;
        authLoading = false;
        authError = null;
        showLogin(message);
    }

    private void showLogin(String message) {
        authLoading = false;
        authError = null;
        authGeneration.incrementAndGet();

        if (webContainer.getVisibility() == View.VISIBLE) {
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.clearHistory();
        }

        loadingOverlay.setVisibility(View.GONE);
        webContainer.setVisibility(View.GONE);
        errorPanel.setVisibility(View.GONE);
        loginScroll.setVisibility(View.VISIBLE);
        webProgress.setVisibility(View.GONE);
        connectButton.setEnabled(true);

        if (message != null && !message.isEmpty()) {
            loginError.setText(message);
            loginError.setVisibility(View.VISIBLE);
        } else {
            loginError.setVisibility(View.GONE);
        }
    }

    private void showLoginError(String message) {
        loginError.setText(message);
        loginError.setVisibility(View.VISIBLE);
    }

    private void showWebError(String message) {
        loadingOverlay.setVisibility(View.GONE);
        loginScroll.setVisibility(View.GONE);
        webContainer.setVisibility(View.VISIBLE);
        errorPanel.setVisibility(View.VISIBLE);
        webProgress.setVisibility(View.GONE);
        errorDetail.setText(message);
    }

    private void hideKeyboard() {
        InputMethodManager imm =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(tokenInput.getWindowToken(), 0);
        }
        urlInput.clearFocus();
        tokenInput.clearFocus();
    }

    /**
     * Restores the Session named by a completion notification. DSH Web persists its selected
     * Session in {@code dsh.sessions.current}; writing that key and reloading lets the existing
     * startup restoration open the exact conversation without adding a server-side deep link.
     */
    private void maybeOpenPendingSession() {
        String sessionId = pendingSessionId;
        if (sessionId == null || authLoading || webView == null
                || webContainer.getVisibility() != View.VISIBLE
                || errorPanel.getVisibility() == View.VISIBLE) return;
        String url = webView.getUrl();
        if (url == null || url.startsWith("about:") || UrlTools.containsToken(url)
                || !UrlTools.sameOrigin(baseUrl, url)) return;
        if (webView.getProgress() < 100) return;
        pendingSessionId = null;
        String target = JSONObject.quote(sessionId);
        String script = "(function(){try{"
                + "var target=" + target + ";"
                + "var current=null;"
                + "try{current=JSON.parse(localStorage.getItem('dsh.sessions.current')||'null');}catch(e){}"
                + "if(!current||current.sessionId!==target){"
                + "localStorage.setItem('dsh.sessions.current',JSON.stringify({sessionId:target}));"
                + "location.reload();"
                + "}"
                + "}catch(e){}})();";
        webView.evaluateJavascript(script, null);
    }

    private static String takeSessionId(Intent intent) {
        if (intent == null) return null;
        String sessionId = intent.getStringExtra(EXTRA_SESSION_ID);
        intent.removeExtra(EXTRA_SESSION_ID);
        return sessionId == null || sessionId.isEmpty() ? null : sessionId;
    }

    private void handleBack() {
        if (loadingOverlay.getVisibility() == View.VISIBLE) {
            finish();
            return;
        }
        if (webContainer.getVisibility() == View.VISIBLE) {
            if (errorPanel.getVisibility() == View.VISIBLE) {
                showLogin(String.valueOf(errorDetail.getText()));
                return;
            }
            if (webView.canGoBack()) {
                webView.goBack();
                return;
            }
        }
        finish();
    }

    // API 33+ uses the platform OnBackInvokedCallback registered in onCreate; this is the <33 fallback.
    @SuppressLint("GestureBackNavigation")
    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (Build.VERSION.SDK_INT >= 33) {
            super.onBackPressed();
            return;
        }
        handleBack();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        configureBars();
        if (webView != null) {
            webView.getSettings().setTextZoom(Math.round(newConfig.fontScale * 100f));
        }
        root.requestApplyInsets();
    }

    @Override
    protected void onPause() {
        super.onPause();
        AppVisibility.setForeground(false);
        CookieManager.getInstance().flush();
        if (webView != null) {
            webView.onPause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        AppVisibility.setForeground(true);
        if (webView != null) {
            webView.onResume();
            webView.post(this::maybeOpenPendingSession);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent == null) return;
        if (intent.getBooleanExtra(EXTRA_FORCE_LOGIN, false)) {
            forceLogin();
            return;
        }
        String sessionId = takeSessionId(intent);
        if (sessionId != null) {
            pendingSessionId = sessionId;
            maybeOpenPendingSession();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        AppVisibility.setForeground(hasFocus);
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        if (webView != null) {
            ViewGroup parent = (ViewGroup) webView.getParent();
            if (parent != null) {
                parent.removeView(webView);
            }
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE_CHOOSER) {
            if (filePathCallback == null) return;
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null) {
                ClipData clipData = data.getClipData();
                if (clipData != null) {
                    result = new Uri[clipData.getItemCount()];
                    for (int i = 0; i < clipData.getItemCount(); i++) {
                        result[i] = clipData.getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    result = new Uri[]{data.getData()};
                }
            }
            filePathCallback.onReceiveValue(result);
            filePathCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_NOTIFICATIONS) return;
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (!granted) {
            Toast.makeText(this, R.string.notification_permission_denied, Toast.LENGTH_LONG).show();
        }
    }

    private void openExternal(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "没有可打开此链接的应用", Toast.LENGTH_SHORT).show();
        }
    }

    private PreflightResult preflight(String host, String token) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(UrlTools.buildAuthUrl(host, token));
            connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(15_000);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", "DSH-Android/0.1");
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml");
            connection.setRequestProperty("Accept-Encoding", "identity");

            int code = connection.getResponseCode();
            if (code == 303) {
                String location = connection.getHeaderField("Location");
                if (isExpectedRedirect(host, url, location)) return PreflightResult.ok();
                return PreflightResult.error(
                        "登录被重定向到意外地址：" + location + "（可能是代理登录页）");
            }
            if (code == 301 || code == 302 || code == 307 || code == 308) {
                String location = connection.getHeaderField("Location");
                if (isExpectedRedirect(host, url, location)) return PreflightResult.ok();
                return PreflightResult.error("服务器返回了意外的重定向：" + location);
            }
            if (code == 401) {
                return PreflightResult.error(
                        "token 无效或已过期。请从 dsh web 启动输出复制最新的 token。");
            }
            if (code == 403) {
                return PreflightResult.error(
                        "服务器拒绝了该域名。请检查 dsh web 的 --trusted-host 是否包含 " + host);
            }
            if (code == 404) {
                return PreflightResult.error(
                        "地址不对：该路径没有 DSH 页面。请检查是否漏了路径前缀（例如 /ui/）。");
            }
            if (code >= 500) {
                return PreflightResult.error("服务器错误（HTTP " + code + "）");
            }
            if (code == 200) {
                String body = readLimited(connection.getInputStream(), 64 * 1024);
                if (body.contains("__DSH_BOOT__")) {
                    return PreflightResult.error(
                            "服务器没有要求 token 登录（直接返回了页面）。当前客户端按 token 登录设计，请确认 dsh web 的认证配置。");
                }
                return PreflightResult.error(
                        "服务器返回的不是 DSH 页面（可能是 NAS/代理登录页）。请先在浏览器完成代理登录。");
            }
            return PreflightResult.error("服务器返回 HTTP " + code);
        } catch (UnknownHostException e) {
            return PreflightResult.error("域名解析失败：" + host + "。请检查网络或 DNS。");
        } catch (SocketTimeoutException e) {
            return PreflightResult.error("连接超时。请检查 NAS/Tailscale 是否在线。");
        } catch (SSLException e) {
            return PreflightResult.error("TLS 证书校验失败：" + e.getMessage());
        } catch (IOException e) {
            return PreflightResult.error("无法连接服务器：" + e.getMessage());
        } catch (RuntimeException e) {
            return PreflightResult.error("验证失败：" + e.getMessage());
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static boolean isExpectedRedirect(String baseHost, URL authUrl, String location) {
        if (location == null || location.isEmpty()) return false;
        try {
            URI resolved = authUrl.toURI().resolve(location);
            if (!UrlTools.sameOrigin(baseHost, resolved)) return false;
            String basePath = new URI(baseHost).getPath();
            if (basePath == null || basePath.isEmpty()) basePath = "/";
            String resolvedPath = resolved.getPath();
            if (resolvedPath == null || resolvedPath.isEmpty()) resolvedPath = "/";
            String normalizedBase = basePath.endsWith("/")
                    ? basePath.substring(0, basePath.length() - 1)
                    : basePath;
            String normalizedResolved = resolvedPath.endsWith("/")
                    ? resolvedPath.substring(0, resolvedPath.length() - 1)
                    : resolvedPath;
            if (normalizedResolved.equals(normalizedBase)) return true;
            return (normalizedBase + "/index.html").equals(normalizedResolved);
        } catch (RuntimeException | java.net.URISyntaxException e) {
            return false;
        }
    }

    private static String readLimited(InputStream input, int maxBytes) throws IOException {
        if (input == null) return "";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while (total < maxBytes && (read = input.read(buffer)) != -1) {
            int take = Math.min(read, maxBytes - total);
            output.write(buffer, 0, take);
            total += take;
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String mapWebError(WebResourceError error) {
        switch (error.getErrorCode()) {
            case WebViewClient.ERROR_HOST_LOOKUP:
                return "域名解析失败。请检查网络或 DNS。";
            case WebViewClient.ERROR_CONNECT:
                return "无法连接服务器。请检查 NAS/Tailscale 是否在线。";
            case WebViewClient.ERROR_TIMEOUT:
                return "连接超时。请检查网络后重试。";
            case WebViewClient.ERROR_FAILED_SSL_HANDSHAKE:
                return "TLS 握手失败。请确认服务器证书有效。";
            case WebViewClient.ERROR_UNSUPPORTED_SCHEME:
                return "不支持的地址协议。";
            case WebViewClient.ERROR_BAD_URL:
                return "地址格式错误。";
            default:
                return "加载失败：" + error.getDescription() + "（" + error.getErrorCode() + "）";
        }
    }

    private static final class PreflightResult {
        final boolean ok;
        final String message;

        private PreflightResult(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }

        static PreflightResult ok() {
            return new PreflightResult(true, null);
        }

        static PreflightResult error(String message) {
            return new PreflightResult(false, message);
        }
    }

    @TargetApi(33)
    @SuppressLint("UseRequiresApi")
    private static final class BackCompat33 {
        private BackCompat33() {}

        static void register(Activity activity, Runnable callback) {
            activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback::run);
        }
    }

    private final class DshWebViewClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            String scheme = uri.getScheme();
            if (scheme == null) return false;
            if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                if (UrlTools.sameOrigin(baseUrl, uri.toString())) return false;
                openExternal(uri);
                return true;
            }
            if ("mailto".equalsIgnoreCase(scheme)
                    || "tel".equalsIgnoreCase(scheme)
                    || "sms".equalsIgnoreCase(scheme)) {
                openExternal(uri);
                return true;
            }
            return true;
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            webProgress.setProgress(0);
            webProgress.setVisibility(View.VISIBLE);
            errorPanel.setVisibility(View.GONE);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            webProgress.setVisibility(View.GONE);
            if (!authLoading) {
                view.post(MainActivity.this::maybeOpenPendingSession);
                return;
            }
            // This callback also fires for subframes; checkAuthResult reads the main-frame URL
            // itself, so any callback is a useful wake-up after the redirect.
            final int generation = authGeneration.get();
            view.postDelayed(() -> checkAuthResult(generation), 700);
        }

        @Override
        public void onReceivedHttpError(
                WebView view,
                WebResourceRequest request,
                WebResourceResponse errorResponse) {
            if (!request.isForMainFrame()) return;
            int status = errorResponse.getStatusCode();
            String message;
            if (status == 401) {
                message = "登录已失效（HTTP 401）。请从 dsh web 启动输出复制最新的 token。";
            } else if (status == 403) {
                message = "服务器拒绝了该域名（HTTP 403）。请检查 --trusted-host。";
            } else if (status == 404) {
                message = "服务器没有在该路径提供 DSH（HTTP 404）。请检查路径前缀。";
            } else if (status >= 500) {
                message = "服务器错误（HTTP " + status + "）";
            } else {
                return;
            }

            if (authLoading) {
                authError = message;
                return;
            }
            if (status == 401 && pendingHost != null && pendingToken != null && !pendingToken.isEmpty()) {
                startAuth(pendingHost, pendingToken, true);
                return;
            }
            if (status == 401 || status == 403) {
                showLogin(message);
                return;
            }
            showWebError(message);
        }

        @Override
        public void onReceivedError(
                WebView view,
                WebResourceRequest request,
                WebResourceError error) {
            if (!request.isForMainFrame()) return;
            String description = String.valueOf(error.getDescription());
            if (error.getErrorCode() == WebViewClient.ERROR_UNKNOWN
                    && description.contains("ERR_ABORTED")) {
                return;
            }
            String message = mapWebError(error);
            if (authLoading) {
                authError = message;
                return;
            }
            showWebError(message);
        }

        @Override
        public void onReceivedSslError(
                WebView view,
                SslErrorHandler handler,
                SslError error) {
            handler.cancel();
            String message = "TLS 证书校验失败。请确认域名证书有效；自签名证书不会被接受。";
            if (authLoading) {
                authError = message;
                return;
            }
            showWebError(message);
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            if (!isFinishing() && !isDestroyed()) {
                Toast.makeText(
                        MainActivity.this, R.string.renderer_crashed, Toast.LENGTH_LONG).show();
                recreate();
            }
            return true;
        }
    }

    private final class DshChromeClient extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            webProgress.setProgress(newProgress);
            webProgress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
        }

        @Override
        public boolean onShowFileChooser(
                WebView webView,
                ValueCallback<Uri[]> callback,
                FileChooserParams params) {
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(null);
                filePathCallback = null;
            }
            filePathCallback = callback;

            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setType("*/*");

            if (params != null) {
                String[] acceptTypes = params.getAcceptTypes();
                if (acceptTypes != null) {
                    List<String> types = new ArrayList<>();
                    for (String type : acceptTypes) {
                        if (type != null && !type.trim().isEmpty()) {
                            types.add(type.trim());
                        }
                    }
                    if (!types.isEmpty()) {
                        if (types.size() == 1) {
                            intent.setType(types.get(0));
                        } else {
                            intent.putExtra(Intent.EXTRA_MIME_TYPES, types.toArray(new String[0]));
                        }
                    }
                }
                if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }
            }

            try {
                startActivityForResult(intent, REQ_FILE_CHOOSER);
            } catch (ActivityNotFoundException e) {
                filePathCallback = null;
                callback.onReceiveValue(null);
                Toast.makeText(
                        MainActivity.this, "没有可用的文件选择器", Toast.LENGTH_SHORT).show();
                return false;
            }
            return true;
        }

        @Override
        public void onPermissionRequest(PermissionRequest request) {
            request.deny();
        }

        @Override
        public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, consoleMessage.message() + " @" + consoleMessage.lineNumber());
            }
            return true;
        }
    }

    private final class DshDownloadListener implements DownloadListener {
        @Override
        public void onDownloadStart(
                String url,
                String userAgent,
                String contentDisposition,
                String mimetype,
                long contentLength) {
            String scheme = Uri.parse(url).getScheme();
            if ("blob".equalsIgnoreCase(scheme) || "data".equalsIgnoreCase(scheme)) {
                Toast.makeText(
                        MainActivity.this, R.string.download_blob, Toast.LENGTH_LONG).show();
                return;
            }
            try {
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                String cookie = CookieManager.getInstance().getCookie(url);
                if (cookie != null && !cookie.isEmpty()) {
                    request.addRequestHeader("Cookie", cookie);
                }
                if (mimetype != null && !mimetype.isEmpty()) {
                    request.setMimeType(mimetype);
                }
                request.setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                String fileName = URLUtil.guessFileName(url, contentDisposition, mimetype);
                try {
                    request.setDestinationInExternalPublicDir(
                            Environment.DIRECTORY_DOWNLOADS, fileName);
                } catch (RuntimeException e) {
                    request.setDestinationInExternalFilesDir(
                            MainActivity.this, Environment.DIRECTORY_DOWNLOADS, fileName);
                }
                DownloadManager manager =
                        (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                if (manager == null) {
                    Toast.makeText(
                            MainActivity.this, "系统下载服务不可用", Toast.LENGTH_SHORT).show();
                    return;
                }
                manager.enqueue(request);
                Toast.makeText(
                        MainActivity.this, R.string.download_started, Toast.LENGTH_SHORT).show();
            } catch (RuntimeException e) {
                Toast.makeText(
                        MainActivity.this, "下载失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }
}
