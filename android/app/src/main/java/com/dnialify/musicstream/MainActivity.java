package com.dnialify.musicstream;

import android.webkit.JavascriptInterface;
import android.Manifest;
import android.content.pm.PackageManager;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        try { getBridge().getWebView().getSettings().setMediaPlaybackRequiresUserGesture(false); } catch (Exception ignored) {}
        try { getBridge().getWebView().getSettings().setDomStorageEnabled(true); } catch (Exception ignored) {}
        // Don't override WebViewClient (breaks Capacitor Bridge) — spoof is done in public/app.js via JS (Brave-style)
        getBridge().getWebView().addJavascriptInterface(new PlaybackBridge(), "NativePlayback");
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
        }
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
