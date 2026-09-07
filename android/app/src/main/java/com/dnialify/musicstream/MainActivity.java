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
    private String loadAssetText(String name) {
        try {
            InputStream is = getAssets().open(name);
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
            String bgJs = loadAssetText("brave-video-bg-play.js");
            String pageviewJs = loadAssetText("brave-disable-pageview-api.js");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && bgJs != null && pageviewJs != null) {
                // AndroidX WebKit document-start runs before page scripts. No WebViewClient replacement.
                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
                        wv, pageviewJs, Collections.singleton("*"));
                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
                        wv, bgJs, Collections.singleton("*"));
            } else if (bgJs != null && pageviewJs != null) {
                // Old WebView fallback. Main app JS remains fallback for already-loaded pages.
                wv.evaluateJavascript(pageviewJs + "\n" + bgJs, null);
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
