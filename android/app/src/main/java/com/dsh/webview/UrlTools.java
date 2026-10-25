package com.dsh.webview;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;

/** URL and token normalization for the DSH login flow. */
final class UrlTools {
    /** Starts blank; the login form asks the user for their own HTTPS server. */
    static final String DEFAULT_HOST = "";

    private UrlTools() {}

    /**
     * Normalizes user input to an HTTPS base URL ending in '/'.
     * Tokens embedded in the URL are intentionally dropped here; callers extract them first.
     */
    static String normalizeBaseUrl(String raw) {
        if (raw == null) throw new IllegalArgumentException("请输入服务器地址");
        String s = raw.trim();
        if (s.startsWith("dsh web:")) s = s.substring("dsh web:".length()).trim();
        if (s.isEmpty()) throw new IllegalArgumentException("请输入服务器地址");
        int query = s.indexOf('?');
        if (query >= 0) s = s.substring(0, query);
        int fragment = s.indexOf('#');
        if (fragment >= 0) s = s.substring(0, fragment);
        if (!s.contains("://")) s = "https://" + s;

        final URI uri;
        try {
            uri = new URI(s);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("地址格式不对");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("只支持 https:// 地址");
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            throw new IllegalArgumentException("地址里没有有效域名");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("地址不能包含用户名或密码");
        }
        String path = uri.getPath();
        if (path == null || path.isEmpty()) path = "/";
        if (!path.endsWith("/")) path += "/";
        int port = uri.getPort();
        String portPart = (port == -1 || port == 443) ? "" : ":" + port;
        return "https://" + host + portPart + path;
    }

    /**
     * Extracts a token from a bare token, a dsh web URL, or a URL with ?token=.
     * Returns an empty string when the input is a URL without a token.
     */
    static String extractToken(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.startsWith("dsh web:")) s = s.substring("dsh web:".length()).trim();
        int marker = s.indexOf("token=");
        if (marker >= 0) {
            String token = s.substring(marker + "token=".length());
            int amp = token.indexOf('&');
            if (amp >= 0) token = token.substring(0, amp);
            try {
                return URLDecoder.decode(token, "UTF-8").trim();
            } catch (UnsupportedEncodingException | IllegalArgumentException e) {
                return token.trim();
            }
        }
        if (s.contains("://") || s.startsWith("/") || s.contains("?")) return "";
        return s;
    }

    /**
     * Extracts a token from a URL that actually carries token=.
     * A bare host (the login form's address field) yields "" instead of being treated as a token.
     */
    static String extractEmbeddedToken(String raw) {
        return containsToken(raw) ? extractToken(raw) : "";
    }

    static String buildAuthUrl(String baseUrl, String token) {
        try {
            String separator = baseUrl.contains("?") ? "&" : "?";
            return baseUrl + separator + "token=" + URLEncoder.encode(token, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 is unavailable", e);
        }
    }

    /**
     * Builds the API operator exchange for the background monitor.
     * The operator must be answering because DSH forbids passive {$events} subscriptions.
     */
    static String buildConnectUrl(String baseUrl, String token) {
        try {
            return baseUrl + "connect?token=" + URLEncoder.encode(token, "UTF-8")
                    + "&interactions=answer";
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 is unavailable", e);
        }
    }

    static boolean sameOrigin(String baseUrl, String otherUrl) {
        if (otherUrl == null) return false;
        try {
            return sameOrigin(baseUrl, new URI(otherUrl));
        } catch (URISyntaxException e) {
            return false;
        }
    }

    static boolean sameOrigin(String baseUrl, URI other) {
        if (baseUrl == null || other == null) return false;
        try {
            URI base = new URI(baseUrl);
            if (!"https".equalsIgnoreCase(base.getScheme())) return false;
            if (!"https".equalsIgnoreCase(other.getScheme())) return false;
            String baseHost = base.getHost();
            String otherHost = other.getHost();
            if (baseHost == null || otherHost == null) return false;
            int basePort = base.getPort() == -1 ? 443 : base.getPort();
            int otherPort = other.getPort() == -1 ? 443 : other.getPort();
            return baseHost.equalsIgnoreCase(otherHost) && basePort == otherPort;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    static boolean containsToken(String url) {
        return url != null && url.contains("token=");
    }
}
