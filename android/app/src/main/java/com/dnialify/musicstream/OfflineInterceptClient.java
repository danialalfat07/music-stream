package com.dnialify.musicstream;

import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * Skeleton for Phase 1 offline intercept (Android-only).
 * Additive, no rewrite: only intercept /api/stream, pass-through everything else.
 * Disabled via FeatureFlags.CACHE_INTERCEPT = false.
 *
 * YT iframe, /api/thumb, /api/lyrics, static assets → return null (let WebView handle).
 */
public class OfflineInterceptClient extends WebViewClient {

    private final WebViewClient delegate;

    public OfflineInterceptClient(WebViewClient delegate) {
        this.delegate = delegate;
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        if (!FeatureFlags.CACHE_INTERCEPT) {
            return delegate != null ? delegate.shouldInterceptRequest(view, request) : null;
        }
        try {
            String url = request.getUrl() != null ? request.getUrl().toString() : "";
            // Only intercept audio stream, additive
            if (!url.contains("/api/stream")) {
                return delegate != null ? delegate.shouldInterceptRequest(view, request) : null;
            }
            // TODO Phase 1: check native filesystem files/offline-beta/{songId}/seg_*.bin
            // if cached → return WebResourceResponse("audio/webm", null, 206, "OK", headers, inputStream)
            // else → return null (let network fetch, then save via bridge)
            return delegate != null ? delegate.shouldInterceptRequest(view, request) : null;
        } catch (Exception e) {
            android.util.Log.d("DnialifyDiag", "OfflineIntercept shouldInterceptRequest err " + e);
            return delegate != null ? delegate.shouldInterceptRequest(view, request) : null;
        }
    }

    // Legacy overload for older WebView
    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
        if (!FeatureFlags.CACHE_INTERCEPT) {
            return delegate != null ? delegate.shouldInterceptRequest(view, url) : null;
        }
        if (url == null || !url.contains("/api/stream")) {
            return delegate != null ? delegate.shouldInterceptRequest(view, url) : null;
        }
        return delegate != null ? delegate.shouldInterceptRequest(view, url) : null;
    }
}
