package com.dnialify.musicstream;

import android.webkit.JavascriptInterface;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import java.io.InputStream;
import java.util.Collections;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    public static MainActivity current;
    @Override
    public void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
        }
    }
    private String loadBraveBgJs() {
        try {
            InputStream is = getAssets().open("brave-bg.js");
            byte[] buf = new byte[is.available()];
            is.read(buf);
            is.close();
            return new String(buf, "UTF-8");
        } catch (Exception e) { return null; }
    }

    @Override
    public void onStart() {
        super.onStart();
        current = this;
        // Bridge ready here — add interface + settings (fixes patah jembatan: addJavascriptInterface sebelum WebView ready)
        try {
            android.webkit.WebView wv = getBridge().getWebView();
            wv.getSettings().setMediaPlaybackRequiresUserGesture(false);
            wv.getSettings().setDomStorageEnabled(true);
            wv.removeJavascriptInterface("NativePlayback");
            wv.addJavascriptInterface(new PlaybackBridge(), "NativePlayback");
            // Stage1: Brave JS inject document-start (primary addDocumentStartJavaScript, fallback delegate)
            String bgJs = loadBraveBgJs();
            if (bgJs != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    try {
                        // API31+ document-start, runs before any page JS — best for YT visibility spoof
                        wv.addDocumentStartJavaScript(bgJs, Collections.singleton("*"));
                    } catch (Exception e) {
                        try { wv.evaluateJavascript(bgJs, null); } catch (Exception ignored) {}
                    }
                } else {
                    // Fallback: delegate WebViewClient onPageStarted (do not break BridgeWebViewClient)
                    try {
                        android.webkit.WebViewClient orig = null;
                        try {
                            // try to get existing client via reflection (Capacitor Bridge)
                            java.lang.reflect.Method m = android.webkit.WebView.class.getMethod("getWebViewClient");
                            orig = (android.webkit.WebViewClient) m.invoke(wv);
                        } catch (Exception ignored) {}
                        final android.webkit.WebViewClient origClient = orig;
                        final String fBgJs = bgJs;
                        wv.setWebViewClient(new android.webkit.WebViewClient() {
                            @Override
                            public void onPageStarted(android.webkit.WebView view, String url, android.graphics.Bitmap favicon) {
                                if (origClient != null) try { origClient.onPageStarted(view, url, favicon); } catch (Exception ignored) {}
                                try { view.evaluateJavascript(fBgJs, null); } catch (Exception ignored) {}
                                super.onPageStarted(view, url, favicon);
                            }
                            @Override
                            public void onPageFinished(android.webkit.WebView view, String url) {
                                if (origClient != null) try { origClient.onPageFinished(view, url); } catch (Exception ignored) {}
                                try { view.evaluateJavascript(fBgJs, null); } catch (Exception ignored) {}
                                super.onPageFinished(view, url);
                            }
                            @Override
                            public boolean shouldOverrideUrlLoading(android.webkit.WebView view, String url) {
                                if (origClient != null) try { return origClient.shouldOverrideUrlLoading(view, url); } catch (Exception ignored) {}
                                return super.shouldOverrideUrlLoading(view, url);
                            }
                            @Override
                            public boolean shouldOverrideUrlLoading(android.webkit.WebView view, android.webkit.WebResourceRequest request) {
                                if (origClient != null) try { return origClient.shouldOverrideUrlLoading(view, request); } catch (Exception ignored) {}
                                return super.shouldOverrideUrlLoading(view, request);
                            }
                        });
                    } catch (Exception ignored) {
                        try { wv.evaluateJavascript(bgJs, null); } catch (Exception ex) {}
                    }
                }
            }
        } catch (Exception ignored) {}
    }
    @Override
    public void onDestroy() {
        if (current == this) current = null;
        super.onDestroy();
    }

    private final class PlaybackBridge {
        @JavascriptInterface
        public void arm() { PlaybackService.arm(MainActivity.this); }

        @JavascriptInterface
        public void updateNotification(String title, String artist) { PlaybackService.updateNotificationStatic(MainActivity.this, title, artist); }

        @JavascriptInterface
        public void play(String url, String title, String artist, String artwork) {
            PlaybackService.play(MainActivity.this, url, title, artist, artwork);
        }

        @JavascriptInterface
        public void pause() { PlaybackService.pause(MainActivity.this); }

        @JavascriptInterface
        public void seek(double seconds) { PlaybackService.seek(MainActivity.this, seconds); }

        @JavascriptInterface
        public boolean isPlaying() { return PlaybackService.isPlaying(); }

        @JavascriptInterface
        public boolean isEnded() { return PlaybackService.isEnded(); }

        @JavascriptInterface
        public double currentTime() { return PlaybackService.currentTime(); }

        @JavascriptInterface
        public double duration() { return PlaybackService.duration(); }

        @JavascriptInterface
        public void speed(double value) { PlaybackService.speed(MainActivity.this, value); }

        @JavascriptInterface
        public void volume(double value) { PlaybackService.volume(MainActivity.this, value); }
    }
}
