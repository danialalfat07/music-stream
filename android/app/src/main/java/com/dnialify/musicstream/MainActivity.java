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
    private static final String TAG_DIAG = "DnialifyDiag";
    // DiagnosticsBridge receives batched JSON logs from diag-bg.html during background
    private final DiagnosticsBridge diagnosticsBridge = new DiagnosticsBridge();

    @Override
    public void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        android.util.Log.d(TAG_DIAG, "Activity onCreate SDK=" + Build.VERSION.SDK_INT + " webkitDocStart=" + androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT));
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
        logLifecycle("onStart");
        // Bridge ready here — add interface + settings (fixes patah jembatan: addJavascriptInterface sebelum WebView ready)
        try {
            android.webkit.WebView wv = getBridge().getWebView();
            wv.getSettings().setMediaPlaybackRequiresUserGesture(false);
            wv.getSettings().setDomStorageEnabled(true);
            wv.removeJavascriptInterface("NativePlayback");
            wv.addJavascriptInterface(new PlaybackBridge(), "NativePlayback");
            wv.removeJavascriptInterface("Diagnostics");
            wv.addJavascriptInterface(diagnosticsBridge, "Diagnostics");
            // Stage1: Brave JS inject document-start (primary addDocumentStartJavaScript, fallback delegate)
            String bgJs = loadAssetText("brave-video-bg-play.js");
            String pageviewJs = loadAssetText("brave-disable-pageview-api.js");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && bgJs != null && pageviewJs != null) {
                // AndroidX WebKit document-start runs before page scripts. No WebViewClient replacement.
                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
                        wv, pageviewJs, Collections.singleton("*"));
                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
                        wv, bgJs, Collections.singleton("*"));
                android.util.Log.d(TAG_DIAG, "WebView addDocumentStartJavaScript injected pageview+bgJs docStartSupported=true");
            } else if (bgJs != null && pageviewJs != null) {
                // Old WebView fallback. Main app JS remains fallback for already-loaded pages.
                wv.evaluateJavascript(pageviewJs + "\n" + bgJs, null);
                android.util.Log.d(TAG_DIAG, "WebView fallback evaluateJavascript injected docStartSupported=" + androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT));
            }
            logKeepRunning();
        } catch (Exception e) {
            android.util.Log.d(TAG_DIAG, "onStart inject error " + e);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        logLifecycle("onResume");
        logKeepRunning();
    }

    @Override
    public void onPause() {
        logLifecycle("onPause");
        logKeepRunning();
        super.onPause();
    }

    @Override
    public void onStop() {
        logLifecycle("onStop");
        super.onStop();
    }

    @Override
    public void onRestart() {
        super.onRestart();
        logLifecycle("onRestart");
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        android.util.Log.d(TAG_DIAG, "Activity onWindowFocusChanged hasFocus=" + hasFocus + " " + lifecycleSnapshot());
    }

    @Override
    public void onTopResumedActivityChanged(boolean isTopResumed) {
        // API29+ — top resumed indicates true foreground
        super.onTopResumedActivityChanged(isTopResumed);
        android.util.Log.d(TAG_DIAG, "Activity onTopResumedActivityChanged isTopResumed=" + isTopResumed + " " + lifecycleSnapshot());
    }

    private void logLifecycle(String event) {
        android.util.Log.d(TAG_DIAG, "Activity " + event + " " + lifecycleSnapshot());
    }

    private String lifecycleSnapshot() {
        String keep = "unknown";
        String depth = "na";
        try {
            if (getBridge() != null) keep = String.valueOf(getBridge().shouldKeepRunning());
        } catch (Exception e) { keep = "err:" + e.getMessage(); }
        try { depth = String.valueOf(activityDepth); } catch (Exception ignored) {}
        boolean hasBridge = false;
        boolean hasWebView = false;
        try { hasBridge = getBridge() != null; } catch (Exception ignored) {}
        try { hasWebView = hasBridge && getBridge().getWebView() != null; } catch (Exception ignored) {}
        return "keepRunning=" + keep + " activityDepth=" + depth + " hasBridge=" + hasBridge + " hasWebView=" + hasWebView + " sdk=" + Build.VERSION.SDK_INT;
    }

    private void logKeepRunning() {
        try {
            if (getBridge() != null) {
                boolean kr = getBridge().shouldKeepRunning();
                android.util.Log.d(TAG_DIAG, "Capacitor keepRunning=" + kr + " bridge=" + getBridge().getClass().getSimpleName());
            }
        } catch (Exception e) {
            android.util.Log.d(TAG_DIAG, "keepRunning check err " + e);
        }
    }
    @Override
    public void onBackPressed() {
        try {
            android.webkit.WebView wv = null;
            if (getBridge() != null) {
                wv = getBridge().getWebView();
            }
            if (wv == null) {
                android.util.Log.d(TAG_DIAG, "Back diag wv null " + lifecycleSnapshot());
                moveTaskToBack(true);
                return;
            }
            boolean canBack = false;
            String url = "null";
            String orig = "null";
            int size = -1;
            int idx = -1;
            try { canBack = wv.canGoBack(); } catch (Exception e) { android.util.Log.d(TAG_DIAG, "Back canGoBack err " + e); }
            try { url = String.valueOf(wv.getUrl()); } catch (Exception e) { url = "err:" + e.getMessage(); }
            try { orig = String.valueOf(wv.getOriginalUrl()); } catch (Exception e) { orig = "err:" + e.getMessage(); }
            try {
                android.webkit.WebBackForwardList list = wv.copyBackForwardList();
                if (list != null) {
                    size = list.getSize();
                    idx = list.getCurrentIndex();
                    for (int i = 0; i < size; i++) {
                        try { String itemUrl = list.getItemAtIndex(i).getUrl(); android.util.Log.d(TAG_DIAG, "Back history[" + i + "]=" + itemUrl); }
                        catch (Exception e) { android.util.Log.d(TAG_DIAG, "Back history[" + i + "] err " + e); }
                    }
                }
            } catch (Exception e) { android.util.Log.d(TAG_DIAG, "Back BackForwardList err " + e); }
            android.util.Log.d(TAG_DIAG, "Back diag wv!=null canGoBack=" + canBack + " url=" + url + " orig=" + orig + " size=" + size + " idx=" + idx + " " + lifecycleSnapshot());

            final boolean finalCanBack = canBack;
            final android.webkit.WebView finalWv = wv;
            String jsState = "(function(){try{"
                    + "var m=document.getElementById('modal');"
                    + "var n=document.getElementById('nowplaying');"
                    + "var isModal=m&&!m.classList.contains('hidden');"
                    + "var isNow=n&&!n.classList.contains('hidden');"
                    + "var pane=document.querySelector('.np-pane.active');"
                    + "var paneId=pane?pane.id:null;"
                    + "var tabEl=document.querySelector('.np-tab.active');"
                    + "var activeTab=tabEl?tabEl.dataset.nptab:null;"
                    + "if(!activeTab&&paneId){if(paneId==='np-player')activeTab='player';else if(paneId==='np-lyrics')activeTab='lyrics';else if(paneId==='np-queue')activeTab='queue';else if(paneId==='np-related')activeTab='related';}"
                    + "return JSON.stringify({modalOpen:!!isModal,nowOpen:!!isNow,activeTab:activeTab,activePane:paneId,href:location.href,hash:location.hash,historyLen:history.length});"
                    + "}catch(e){return JSON.stringify({error:String(e)});}})()";
            try {
                finalWv.evaluateJavascript(jsState, value -> {
                    try {
                        String raw = value;
                        android.util.Log.d(TAG_DIAG, "Back JS state raw=" + raw);
                        String cleaned = raw;
                        if (cleaned != null && cleaned.length() >= 2 && cleaned.charAt(0) == '\"' && cleaned.charAt(cleaned.length() - 1) == '\"') {
                            cleaned = cleaned.substring(1, cleaned.length() - 1).replace("\\\\", "\\").replace("\\\"", "\"");
                        }
                        if (cleaned == null || cleaned.equals("null") || cleaned.trim().isEmpty()) {
                            android.util.Log.d(TAG_DIAG, "Back JS state empty, fallback native canGoBack=" + finalCanBack);
                            if (finalCanBack) { finalWv.goBack(); return; }
                            moveTaskToBack(true);
                            return;
                        }
                        org.json.JSONObject obj = new org.json.JSONObject(cleaned);
                        if (obj.has("error")) android.util.Log.d(TAG_DIAG, "Back JS error " + obj.optString("error"));
                        boolean modalOpen = obj.optBoolean("modalOpen", false);
                        boolean nowOpen = obj.optBoolean("nowOpen", false);
                        String activeTab = obj.isNull("activeTab") ? null : obj.optString("activeTab", null);
                        String activePane = obj.isNull("activePane") ? null : obj.optString("activePane", null);
                        int historyLen = obj.optInt("historyLen", 1);
                        String hash = obj.optString("hash", "");
                        String href = obj.optString("href", "");
                        android.util.Log.d(TAG_DIAG, "Back JS parsed modalOpen=" + modalOpen + " nowOpen=" + nowOpen + " activeTab=" + activeTab + " activePane=" + activePane + " hash=" + hash + " historyLen=" + historyLen + " href=" + href + " canBack=" + finalCanBack);
                        if (modalOpen) {
                            android.util.Log.d(TAG_DIAG, "Back: closeModal modalOpen=true");
                            finalWv.evaluateJavascript("try{closeModal()}catch(e){}", null);
                            return;
                        }
                        if (nowOpen) {
                            if (activeTab != null && !"player".equals(activeTab)) {
                                android.util.Log.d(TAG_DIAG, "Back: switchNPTab(player) from " + activeTab);
                                finalWv.evaluateJavascript("try{switchNPTab('player')}catch(e){}", null);
                                return;
                            } else {
                                android.util.Log.d(TAG_DIAG, "Back: closeNowPlaying nowOpen player");
                                finalWv.evaluateJavascript("try{closeNowPlaying()}catch(e){}", null);
                                return;
                            }
                        }
                        if (finalCanBack) {
                            android.util.Log.d(TAG_DIAG, "Back: goBack canGoBack=true historyLen=" + historyLen + " hash=" + hash);
                            finalWv.goBack();
                            return;
                        }
                        if (historyLen > 1) {
                            android.util.Log.d(TAG_DIAG, "Back: JS history.back() fallback historyLen=" + historyLen + " hash=" + hash);
                            finalWv.evaluateJavascript("try{history.back()}catch(e){}", null);
                            return;
                        }
                        android.util.Log.d(TAG_DIAG, "Back: moveTaskToBack no overlay/history hash=" + hash + " canBack=" + finalCanBack + " historyLen=" + historyLen);
                        moveTaskToBack(true);
                    } catch (Exception e) {
                        android.util.Log.d(TAG_DIAG, "Back JS callback err " + e + " raw=" + value);
                        try { if (finalCanBack) { finalWv.goBack(); return; } } catch (Exception ex) {}
                        moveTaskToBack(true);
                    }
                });
                return;
            } catch (Exception e) {
                android.util.Log.d(TAG_DIAG, "Back JS eval err " + e);
            }
            if (canBack) {
                android.util.Log.d(TAG_DIAG, "Back: goBack fallback sync canGoBack=true");
                wv.goBack();
                return;
            }
            android.util.Log.d(TAG_DIAG, "Back: moveTaskToBack fallback sync");
            moveTaskToBack(true);
        } catch (Exception e) {
            android.util.Log.d(TAG_DIAG, "Back handler err " + e);
            try { moveTaskToBack(true); } catch (Exception ex) {}
        }
    }

    @Override
    public void onDestroy() {
        android.util.Log.d(TAG_DIAG, "Activity onDestroy " + lifecycleSnapshot());
        if (current == this) current = null;
        super.onDestroy();
    }

    private final class DiagnosticsBridge {
        @JavascriptInterface
        public void log(String json) {
            // Batched from diag-bg.html: already timestamped in JS, just forward to logcat
            try {
                // Split if > 4000 chars (logcat limit)
                int max = 3500;
                if (json.length() <= max) {
                    android.util.Log.d(TAG_DIAG, "JS " + json);
                } else {
                    for (int i = 0; i < json.length(); i += max) {
                        int end = Math.min(json.length(), i + max);
                        android.util.Log.d(TAG_DIAG, "JS chunk " + (i / max) + " " + json.substring(i, end));
                    }
                }
            } catch (Exception e) {
                android.util.Log.d(TAG_DIAG, "Diagnostics log err " + e);
            }
        }
        @JavascriptInterface
        public void logLine(String line) {
            android.util.Log.d(TAG_DIAG, "JS " + line);
        }
    }

    private final class PlaybackBridge {
        @JavascriptInterface
        public void arm() { PlaybackService.arm(MainActivity.this); }

        @JavascriptInterface
        public void updateNotification(String title, String artist) { PlaybackService.updateNotificationStatic(MainActivity.this, title, artist); }

        @JavascriptInterface
        public void updateWebViewState(String title, String artist, String artwork, boolean isPlaying, double positionMs, double durationMs) {
            android.util.Log.d("DnialifyDiag", "Bridge updateWebViewState title=" + title + " artist=" + artist + " playing=" + isPlaying + " pos=" + positionMs + " dur=" + durationMs);
            PlaybackService.updateWebViewState(MainActivity.this, title, artist, artwork, isPlaying, (long) positionMs, (long) durationMs);
        }

        @JavascriptInterface
        public void updateLyrics(String prev, String current, String next) {
            android.util.Log.d("DnialifyDiag", "Bridge updateLyrics prev=" + prev + " cur=" + current + " next=" + next);
            PlaybackService.updateLyricsStatic(MainActivity.this, prev, current, next);
        }

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
