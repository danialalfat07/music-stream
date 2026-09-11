package com.dnialify.musicstream;

import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.app.PictureInPictureParams;
import android.util.Rational;
import android.content.res.Configuration;
import java.io.InputStream;
import java.util.Collections;

import com.getcapacitor.BridgeActivity;
import android.widget.TextView;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.util.TypedValue;

public class MainActivity extends BridgeActivity {
    public static MainActivity current;
    private static final String TAG_DIAG = "DnialifyDiag";
    // DiagnosticsBridge receives batched JSON logs from diag-bg.html during background
    private final DiagnosticsBridge diagnosticsBridge = new DiagnosticsBridge();
    // Phase 11 — native PiP renderer (mirrors WebView state, no second playback engine)
    // Phase 12 — Full-Bleed Synced Lyrics (Karaoke) — blurred art + dark overlay + 10-line centered + dynamic scaling
    private ViewGroup pipNativeView;
    private ImageView pipArtView;
    private TextView pipTitleView;
    private TextView pipArtistView;
    private java.util.List<TextView> pipLyricLineViews = new java.util.ArrayList<>();
    private java.util.List<String> pipLyricLines = new java.util.ArrayList<>();
    private int pipLyricActiveIdx = -1;
    private int lastPipRenderedActiveIdx = -999;
    private android.widget.ScrollView pipLyricsScroll;
    private LinearLayout pipLyricsContainer;
    private String pipTitle = "Dnialify Music Stream";
    private String pipArtist = "MusicStream";
    private String pipArtworkUrl = "";
    private String pipCurrentLyric = "";
    private long pipPositionMs = 0;
    private long pipDurationMs = 0;
    private boolean pipIsPlaying = false;
    private Bitmap pipArtworkBitmap;
    private String pipLoadedArtworkUrl = "";

    @Override
    public void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        try {
            WebView.setWebContentsDebuggingEnabled(true);
            android.util.Log.d(TAG_DIAG, "WebView remote debugging enabled");
        } catch (Exception e) {
            android.util.Log.d(TAG_DIAG, "WebView debugging enable failed " + e);
        }
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
        // Phase 11 — native PiP view (GONE until PiP, mirrors WebView state)
        ensurePipNativeView();
        logPipNative("onStart");
    }

    @Override
    public void onResume() {
        super.onResume();
        logLifecycle("onResume");
        logKeepRunning();
        logPipNative("onResume");
    }

    @Override
    public void onPause() {
        logLifecycle("onPause");
        logKeepRunning();
        logPipNative("onPause");
        super.onPause();
        logPipNative("onPause:afterSuper");
    }

    @Override
    public void onStop() {
        logLifecycle("onStop");
        logPipNative("onStop:beforeSuper");
        super.onStop();
        logPipNative("onStop:afterSuper");
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
        logPipNative("onWindowFocusChanged:" + hasFocus);
    }

    @Override
    public void onTopResumedActivityChanged(boolean isTopResumed) {
        // API29+ — top resumed indicates true foreground
        super.onTopResumedActivityChanged(isTopResumed);
        android.util.Log.d(TAG_DIAG, "Activity onTopResumedActivityChanged isTopResumed=" + isTopResumed + " " + lifecycleSnapshot());
        logPipNative("onTopResumed:" + isTopResumed);
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
                    + "var s=document.getElementById('settings-modal');"
                    + "var h=document.getElementById('help-modal');"
                    + "var isModal=m&&!m.classList.contains('hidden');"
                    + "var isNow=n&&!n.classList.contains('hidden');"
                    + "var isSettings=s&&!s.classList.contains('hidden');"
                    + "var isHelp=h&&!h.classList.contains('hidden');"
                    + "var pane=document.querySelector('.np-pane.active');"
                    + "var paneId=pane?pane.id:null;"
                    + "var tabEl=document.querySelector('.np-tab.active');"
                    + "var activeTab=tabEl?tabEl.dataset.nptab:null;"
                    + "if(!activeTab&&paneId){if(paneId==='np-player')activeTab='player';else if(paneId==='np-lyrics')activeTab='lyrics';else if(paneId==='np-queue')activeTab='queue';else if(paneId==='np-related')activeTab='related';}"
                    + "var hash=location.hash||'';"
                    + "var isHome=hash==='#/home'||hash==='#/'||hash===''||hash==='#';"
                    + "return JSON.stringify({modalOpen:!!isModal,nowOpen:!!isNow,settingsOpen:!!isSettings,helpOpen:!!isHelp,activeTab:activeTab,activePane:paneId,href:location.href,hash:hash,historyLen:history.length,isHome:isHome});"
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
                        boolean settingsOpen = obj.optBoolean("settingsOpen", false);
                        boolean helpOpen = obj.optBoolean("helpOpen", false);
                        String activeTab = obj.isNull("activeTab") ? null : obj.optString("activeTab", null);
                        String activePane = obj.isNull("activePane") ? null : obj.optString("activePane", null);
                        int historyLen = obj.optInt("historyLen", 1);
                        String hash = obj.optString("hash", "");
                        String href = obj.optString("href", "");
                        boolean isHome = obj.optBoolean("isHome", false);
                        android.util.Log.d(TAG_DIAG, "Back JS parsed modalOpen=" + modalOpen + " nowOpen=" + nowOpen + " settingsOpen=" + settingsOpen + " helpOpen=" + helpOpen + " activeTab=" + activeTab + " activePane=" + activePane + " hash=" + hash + " isHome=" + isHome + " historyLen=" + historyLen + " href=" + href + " canBack=" + finalCanBack);
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
                        if (settingsOpen) {
                            android.util.Log.d(TAG_DIAG, "Back: closeSettingsModal settingsOpen=true");
                            finalWv.evaluateJavascript("try{closeSettingsModal()}catch(e){}", null);
                            return;
                        }
                        if (helpOpen) {
                            android.util.Log.d(TAG_DIAG, "Back: closeHelpModal helpOpen=true");
                            finalWv.evaluateJavascript("try{closeHelpModal()}catch(e){}", null);
                            return;
                        }
                        if (isHome) {
                            android.util.Log.d(TAG_DIAG, "Back: moveTaskToBack isHome=true hash=" + hash + " canBack=" + finalCanBack + " historyLen=" + historyLen);
                            moveTaskToBack(true);
                            return;
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

    // Phase 12 — Full-Bleed Karaoke: blurred art + 70% dark overlay + header gradient + 10-line scroll centered + dynamic scaling
    private void ensurePipNativeView() {
        try {
            if (pipNativeView != null) return;
            ViewGroup content = findViewById(android.R.id.content);
            if (content == null) {
                View decor = getWindow() != null ? getWindow().getDecorView() : null;
                if (decor instanceof ViewGroup) content = (ViewGroup) decor;
            }
            if (content == null) {
                android.util.Log.d(TAG_DIAG, "[PipNative] attach failed: content null");
                return;
            }
            FrameLayout root = new FrameLayout(this);
            root.setVisibility(View.GONE);
            root.setBackgroundColor(Color.BLACK);
            // Background: album art blurred ~25px + dark overlay 60-75%
            ImageView art = new ImageView(this);
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            art.setVisibility(View.VISIBLE);
            FrameLayout.LayoutParams artLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            root.addView(art, artLp);
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                try { art.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(25f, 25f, android.graphics.Shader.TileMode.CLAMP)); } catch (Exception ignored) {}
            }
            View scrim = new View(this);
            scrim.setBackgroundColor(Color.parseColor("#B3000000")); // 70% black overlay
            FrameLayout.LayoutParams scrimLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            root.addView(scrim, scrimLp);
            // Outer flex: header pinned top + lyrics scroll flex 1
            LinearLayout outer = new LinearLayout(this);
            outer.setOrientation(LinearLayout.VERTICAL);
            outer.setBackgroundColor(Color.TRANSPARENT);
            FrameLayout.LayoutParams outerLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            root.addView(outer, outerLp);
            // Header with gradient 80% -> transparent
            LinearLayout header = new LinearLayout(this);
            header.setOrientation(LinearLayout.VERTICAL);
            header.setGravity(Gravity.CENTER);
            int hdrPad = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8, getResources().getDisplayMetrics());
            header.setPadding(hdrPad, hdrPad, hdrPad, hdrPad/2);
            android.graphics.drawable.GradientDrawable hdrBg = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{ Color.parseColor("#CC000000"), Color.TRANSPARENT });
            header.setBackground(hdrBg);
            outer.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView titleTv = new TextView(this);
            titleTv.setText(pipTitle);
            titleTv.setTextColor(Color.WHITE);
            titleTv.setTypeface(null, android.graphics.Typeface.BOLD);
            titleTv.setGravity(Gravity.CENTER);
            titleTv.setMaxLines(1);
            titleTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            titleTv.setAlpha(0.95f);
            header.addView(titleTv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView artistTv = new TextView(this);
            artistTv.setText(pipArtist);
            artistTv.setTextColor(Color.parseColor("#B3B3B3"));
            artistTv.setGravity(Gravity.CENTER);
            artistTv.setMaxLines(1);
            artistTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams aLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            aLp.topMargin = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2, getResources().getDisplayMetrics());
            header.addView(artistTv, aLp);
            // Scrollable lyrics container flex 1 — active centered
            android.widget.ScrollView scroll = new android.widget.ScrollView(this);
            scroll.setVerticalScrollBarEnabled(false);
            scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
            LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            outer.addView(scroll, scrollLp);
            LinearLayout lyricsContainer = new LinearLayout(this);
            lyricsContainer.setOrientation(LinearLayout.VERTICAL);
            lyricsContainer.setGravity(Gravity.CENTER);
            int padH = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6, getResources().getDisplayMetrics());
            int padV = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10, getResources().getDisplayMetrics());
            lyricsContainer.setPadding(padH, padV, padH, padV);
            scroll.addView(lyricsContainer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            pipLyricLineViews.clear();
            for (int i = 0; i < 10; i++) {
                TextView tv = new TextView(this);
                tv.setGravity(Gravity.CENTER);
                tv.setMaxLines(2);
                tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
                tv.setBackgroundColor(Color.TRANSPARENT);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2, getResources().getDisplayMetrics());
                lp.bottomMargin = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2, getResources().getDisplayMetrics());
                lyricsContainer.addView(tv, lp);
                pipLyricLineViews.add(tv);
            }
            // Dynamic scaling on resize
            root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> recalcPipDynamicScaling());
            FrameLayout.LayoutParams rootLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            content.addView(root, rootLp);
            pipNativeView = root;
            pipArtView = art;
            pipTitleView = titleTv;
            pipArtistView = artistTv;
            pipLyricsScroll = scroll;
            pipLyricsContainer = lyricsContainer;
            android.util.Log.d(TAG_DIAG, "[PipNative] attached GONE karaoke parent=" + content.getClass().getSimpleName() + " childCount=" + content.getChildCount());
            logPipNative("attached:GONE karaoke");
            updatePipNativeView();
            recalcPipDynamicScaling();
        } catch (Exception e) {
            android.util.Log.d(TAG_DIAG, "[PipNative] attach err " + e);
        }
    }

    private void recalcPipDynamicScaling() {
        try {
            if (pipNativeView == null) return;
            int h = pipNativeView.getHeight();
            if (h <= 0) h = getResources().getDisplayMetrics().heightPixels / 3;
            float density = getResources().getDisplayMetrics().scaledDensity;
            // FIX Bug1: gunakan SP dengan pembagi density, multiplier 1/4 dari sebelumnya
            float titleSp = (h * 0.015f) / density;
            float artistSp = (h * 0.011f) / density;
            float lyricSp = (h * 0.009f) / density;
            float activeSp = lyricSp * 1.18f;
            // clamp baru: Title max 14sp, Artist 11sp, Inactive 10sp, Active 12sp
            titleSp = Math.max(10f, Math.min(titleSp, 14f));
            artistSp = Math.max(8f, Math.min(artistSp, 11f));
            lyricSp = Math.max(8f, Math.min(lyricSp, 10f));
            activeSp = Math.max(10f, Math.min(activeSp, 12f));
            if (pipTitleView != null) pipTitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, titleSp);
            if (pipArtistView != null) pipArtistView.setTextSize(TypedValue.COMPLEX_UNIT_SP, artistSp);
            // store SP values (nama variabel tetap Px untuk kompatibilitas)
            pipDynamicTitlePx = titleSp; pipDynamicArtistPx = artistSp; pipDynamicLyricPx = lyricSp; pipDynamicActivePx = activeSp;
            android.util.Log.d(TAG_DIAG, "[PipNative] scaling FIX h=" + h + " density=" + density + " titleSp=" + titleSp + " artistSp=" + artistSp + " lyricSp=" + lyricSp + " activeSp=" + activeSp + " PX_vs_SP=SP_benar");
        } catch (Exception e) { android.util.Log.d(TAG_DIAG, "[PipNative] scaling err " + e); }
    }
    private float pipDynamicTitlePx = -1, pipDynamicArtistPx = -1, pipDynamicLyricPx = -1, pipDynamicActivePx = -1;

    private void updatePipNativeView() {
        try {
            if (pipNativeView == null || pipTitleView == null || pipLyricLineViews.isEmpty()) return;
            runOnUiThread(() -> {
                try {
                    pipTitleView.setText(pipTitle == null || pipTitle.isEmpty() ? "Dnialify Music Stream" : pipTitle);
                    pipArtistView.setText(pipArtist == null || pipArtist.isEmpty() ? "MusicStream" : pipArtist);
                    // artwork as background — keep visible, fallback black only if missing (spec: jangan hitam polos)
                    try {
                        String url = pipArtworkUrl;
                        boolean hasArt = url != null && !url.isEmpty();
                        if (hasArt) {
                            pipArtView.setVisibility(View.VISIBLE);
                            if (!url.equals(pipLoadedArtworkUrl)) loadArtworkNative(url);
                        } else {
                            pipArtView.setImageDrawable(null);
                            pipArtView.setVisibility(View.GONE);
                            pipNativeView.setBackgroundColor(Color.BLACK);
                        }
                    } catch (Exception ignored) {}
                    // 10-line window — active centered
                    java.util.List<String> lines = pipLyricLines;
                    int active = pipLyricActiveIdx;
                    int size = lines == null ? 0 : lines.size();
                    // edge: no lyrics → header + centered art, no scroll (spec fallback)
                    if (size == 0) {
                        String cur = pipCurrentLyric == null ? "" : pipCurrentLyric.trim();
                        if (cur.isEmpty()) {
                            // no lyrics at all → hide scroll, show unblurred art centered
                            if (pipLyricsScroll != null) pipLyricsScroll.setVisibility(View.GONE);
                            if (pipArtView != null) {
                                pipArtView.setVisibility(View.VISIBLE);
                                if (android.os.Build.VERSION.SDK_INT >= 31) try { pipArtView.setRenderEffect(null); } catch (Exception ignored) {}
                                pipArtView.setAlpha(1f);
                            }
                            for (TextView tv : pipLyricLineViews) tv.setVisibility(View.INVISIBLE);
                            android.util.Log.d(TAG_DIAG, "[PipNative] update no-lyrics fallback header only");
                            return;
                        }
                        if (pipLyricsScroll != null) pipLyricsScroll.setVisibility(View.VISIBLE);
                        // single lyric fallback centered
                        for (int i = 0; i < 10; i++) {
                            TextView tv = pipLyricLineViews.get(i);
                            boolean isActive = i == 5;
                            tv.setText(isActive ? cur : "");
                            applyLyricLineStyle(tv, isActive, isActive ? 0 : Math.abs(i - 5));
                            tv.setVisibility(isActive ? View.VISIBLE : View.INVISIBLE);
                        }
                        android.util.Log.d(TAG_DIAG, "[PipNative] update 10line fallback lyric=" + cur + " size=0 activeIdx=" + active);
                        return;
                    }
                    if (pipLyricsScroll != null) pipLyricsScroll.setVisibility(View.VISIBLE);
                    // restore blur if was removed for no-lyrics case
                    if (android.os.Build.VERSION.SDK_INT >= 31 && pipArtView != null) {
                        try { pipArtView.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(25f, 25f, android.graphics.Shader.TileMode.CLAMP)); pipArtView.setAlpha(1f); } catch (Exception ignored) {}
                    }
                    if (active < 0 || active >= size) active = 0;
                    // compute window 10 lines centered around active: 5 above, active, 4 below
                    int start = active - 5;
                    int end = active + 4; // inclusive 10
                    if (start < 0) { end += -start; start = 0; }
                    if (end >= size) { start -= (end - size + 1); end = size - 1; }
                    if (start < 0) start = 0;
                    // ensure window size at most 10, fill
                    int winSize = Math.min(10, size);
                    // if size <10, center active as best possible — keep active near middle index 5 but clamp
                    // adjust start to keep size winSize
                    if (size >= 10) {
                        // already 10
                    } else {
                        start = 0; end = size - 1;
                    }
                    // COMPREHENSIVE DEBUG
                    boolean _isPip = false; try { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) _isPip = isInPictureInPictureMode(); } catch (Exception ignored) {}
                    android.util.Log.d(TAG_DIAG, "[PipNative] update DEBUG size=" + size + " activeIdx=" + active + " start=" + start + " end=" + end + " window=10 isInPip=" + _isPip);
                    for (int dbg=0; dbg<10; dbg++) {
                        int src = start + dbg;
                        String txt = (src>=0 && src<size ? pipLyricLines.get(src) : "OUT_OF_BOUNDS");
                        android.util.Log.d(TAG_DIAG, "[PipNative] line["+dbg+"] srcIdx="+src+" text=\""+txt+"\" isActive="+(src==active)+" visibility="+(src<0||src>end||src>=size?"INVISIBLE":"VISIBLE"));
                    }
                    for (int i = 0; i < 10; i++) {
                        TextView tv = pipLyricLineViews.get(i);
                        int srcIdx = start + i;
                        if (srcIdx < 0 || srcIdx > end || srcIdx >= size) {
                            tv.setText("");
                            tv.setVisibility(View.INVISIBLE);
                            continue;
                        }
                        String txt = lines.get(srcIdx);
                        if (txt == null) txt = "";
                        txt = txt.trim();
                        if (txt.isEmpty()) txt = "♪";
                        boolean isActive = srcIdx == active;
                        tv.setText(txt);
                        tv.setVisibility(View.VISIBLE); // FIX Bug2: prev/next VISIBLE dengan alpha 0.4, bukan GONE/INVISIBLE
                        int dist = Math.abs(srcIdx - active);
                        applyLyricLineStyle(tv, isActive, dist);
                    }
                    // FIX Bug2: postDelayed 150ms agar layout selesai measure sebelum getTop()
                    final int activeFinal = active;
                    final int startFinal = start;
                    boolean shouldScroll = activeFinal != lastPipRenderedActiveIdx;
                    if (pipLyricsScroll != null && shouldScroll) {
                        pipLyricsScroll.postDelayed(() -> {
                            try {
                                int idxInWin = activeFinal - startFinal;
                                if (idxInWin <0 || idxInWin >= pipLyricLineViews.size()) return;
                                View av = pipLyricLineViews.get(idxInWin);
                                int target = av.getTop() - (pipLyricsScroll.getHeight() - av.getHeight())/2;
                                pipLyricsScroll.smoothScrollTo(0, Math.max(0, target));
                                android.util.Log.d(TAG_DIAG, "[PipNative] smoothScrollTo target=" + target + " activeTop=" + av.getTop());
                            } catch (Exception e) { android.util.Log.d(TAG_DIAG, "[PipNative] scroll err " + e); }
                        }, 150);
                        try {
                            for (int i=0;i<pipLyricLineViews.size();i++) {
                                TextView tv = pipLyricLineViews.get(i);
                                boolean isA = (startFinal + i) == activeFinal;
                                tv.animate().scaleX(isA?1.07f:1f).scaleY(isA?1.07f:1f).setDuration(200).start();
                            }
                        } catch (Exception ignored) {}
                        lastPipRenderedActiveIdx = activeFinal;
                    } else {
                        android.util.Log.d(TAG_DIAG, "[PipNative] skip scroll same activeIdx=" + activeFinal);
                    }
                    android.util.Log.d(TAG_DIAG, "[PipNative] update 10line title=" + pipTitle + " artist=" + pipArtist + " activeIdx=" + active + " window=" + start + "-" + end + " size=" + size + " lyric=" + pipCurrentLyric + " shouldScroll=" + shouldScroll);
                } catch (Exception e) { android.util.Log.d(TAG_DIAG, "[PipNative] update err " + e); }
            });
        } catch (Exception e) { android.util.Log.d(TAG_DIAG, "[PipNative] update outer err " + e); }
    }

    private void applyLyricLineStyle(TextView tv, boolean isActive, int dist) {
        try {
            float lyricSp = pipDynamicLyricPx > 0 ? pipDynamicLyricPx : 10f;
            float activeSp = pipDynamicActivePx > 0 ? pipDynamicActivePx : 12f;
            if (isActive) {
                tv.setTextColor(Color.parseColor("#1DB954")); // bright green
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, activeSp);
                tv.setTypeface(null, android.graphics.Typeface.BOLD);
                tv.setAlpha(1f);
                tv.setBackgroundColor(Color.TRANSPARENT);
                tv.setLineSpacing(0, 1.15f);
                // FIX Bug1 wrapping: active boleh wrap 5 baris, HAPUS ellipsize
                tv.setMaxLines(5);
                tv.setEllipsize(null);
                tv.setSingleLine(false);
                tv.setScaleX(1.07f); tv.setScaleY(1.07f);
            } else {
                tv.setTextColor(Color.parseColor("#FFFFFF")); tv.setAlpha(0.42f); // inactive white 0.4 per spec, solid via alpha
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, lyricSp);
                tv.setTypeface(null, android.graphics.Typeface.NORMAL);
                tv.setBackgroundColor(Color.TRANSPARENT);
                tv.setLineSpacing(0, 1.2f);
                tv.setMaxLines(2);
                tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
                tv.setSingleLine(false);
                tv.setScaleX(1f); tv.setScaleY(1f);
            }
            tv.setShadowLayer(0,0,0,0);
            tv.setPadding(0,0,0,0);
        } catch (Exception ignored) {}
    }

    private void loadArtworkNative(String url) {
        try {
            if (url == null || url.isEmpty()) return;
            String target = url;
            new Thread(() -> {
                try {
                    Bitmap bmp = BitmapFactory.decodeStream(new java.net.URL(target).openStream());
                    if (bmp != null) {
                        runOnUiThread(() -> {
                            try {
                                if (pipArtView != null && target.equals(pipArtworkUrl)) {
                                    pipArtView.setImageBitmap(bmp);
                                    pipArtworkBitmap = bmp;
                                    pipLoadedArtworkUrl = target;
                                    android.util.Log.d(TAG_DIAG, "[PipNative] artwork loaded " + bmp.getWidth() + "x" + bmp.getHeight() + " url=" + target);
                                }
                            } catch (Exception e) { android.util.Log.d(TAG_DIAG, "[PipNative] set bmp err " + e); }
                        });
                    } else {
                        android.util.Log.d(TAG_DIAG, "[PipNative] artwork decode null url=" + target);
                    }
                } catch (Exception e) { android.util.Log.d(TAG_DIAG, "[PipNative] artwork load err " + e + " url=" + target); }
            }).start();
        } catch (Exception e) { android.util.Log.d(TAG_DIAG, "[PipNative] loadArt outer err " + e); }
    }

    private void logPipNative(String phase) {
        try {
            boolean isPip = false;
            try { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) isPip = isInPictureInPictureMode(); } catch (Exception ignored) {}
            String vis = pipNativeView == null ? "null" : (pipNativeView.getVisibility()==View.VISIBLE?"VISIBLE":pipNativeView.getVisibility()==View.GONE?"GONE":"INVISIBLE");
            boolean shown = pipNativeView != null && pipNativeView.isShown();
            int w = pipNativeView != null ? pipNativeView.getWidth() : -1;
            int h = pipNativeView != null ? pipNativeView.getHeight() : -1;
            String parentInfo = "null";
            try {
                if (pipNativeView != null && pipNativeView.getParent() instanceof ViewGroup) {
                    ViewGroup p = (ViewGroup) pipNativeView.getParent();
                    parentInfo = p.getClass().getSimpleName() + " vis=" + (p.getVisibility()==View.VISIBLE?"VISIBLE":"GONE") + " size=" + p.getWidth() + "x" + p.getHeight() + " idx=" + p.indexOfChild(pipNativeView) + "/" + p.getChildCount();
                }
            } catch (Exception e) { parentInfo = "err:"+e; }
            android.util.Log.d(TAG_DIAG, "[PipNative] " + phase + " isInPip=" + isPip + " visibility=" + vis + " shown=" + shown + " size=" + w + "x" + h + " parent=" + parentInfo + " title=" + pipTitle + " artist=" + pipArtist + " lyric=" + pipCurrentLyric + " hasArt=" + (pipArtworkUrl!=null&&!pipArtworkUrl.isEmpty()));
        } catch (Exception e) { android.util.Log.d(TAG_DIAG, "[PipNative] log err " + e); }
    }

    @Override
    public void onDestroy() {
        android.util.Log.d(TAG_DIAG, "Activity onDestroy " + lifecycleSnapshot());
        if (current == this) current = null;
        super.onDestroy();
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        android.util.Log.d(TAG_DIAG, "[Native] onPictureInPictureModeChanged pip=" + isInPictureInPictureMode);
        android.util.Log.d(TAG_DIAG, "PiP mode changed isInPip=" + isInPictureInPictureMode + " " + lifecycleSnapshot());
        // Phase 11 — native PiP view visible only in PiP
        try {
            ensurePipNativeView();
            if (pipNativeView != null) {
                if (isInPictureInPictureMode) {
                    updatePipNativeView();
                    pipNativeView.setVisibility(View.VISIBLE);
                    pipNativeView.bringToFront();
                    pipNativeView.requestLayout();
                    pipNativeView.invalidate();
                    ViewGroup parent = (ViewGroup) pipNativeView.getParent();
                    if (parent != null) { parent.requestLayout(); parent.invalidate(); }
                } else {
                    pipNativeView.setVisibility(View.GONE);
                }
            }
        } catch (Exception ignored) {}
        logPipNative("onPictureInPictureModeChanged:" + isInPictureInPictureMode);
        try { android.os.Handler hh = new android.os.Handler(android.os.Looper.getMainLooper()); hh.postDelayed(() -> logPipNative("pipDelayed400"), 400); hh.postDelayed(() -> logPipNative("pipDelayed800"), 800); hh.postDelayed(() -> { try { if(pipNativeView!=null){pipNativeView.bringToFront(); pipNativeView.invalidate(); logPipNative("pipBringFront600");}}catch(Exception ignored){} }, 600); } catch(Exception ignored){}
        try {
            if (getBridge() != null && getBridge().getWebView() != null) {
                final boolean pip = isInPictureInPictureMode;
                getBridge().getWebView().post(() -> {
                    try {
                        String js = "try{"
                                + "document.body.classList.toggle('pip-system', " + pip + ");"
                                + "if(" + pip + "){"
                                + "document.body.classList.add('float-mode');"
                                + "var w=document.getElementById('float-widget'); if(w){w.classList.remove('hidden'); try{if(window.enableDrag) enableDrag(w)}catch(e){} try{if(window.bindFloatWidget) bindFloatWidget(document)}catch(e){}}"
                                + "}else{"
                                + "document.body.classList.remove('pip-system');"
                                + "}"
                                + "}catch(e){}";
                        getBridge().getWebView().evaluateJavascript(js, null);
                        if (pip) {
                            getBridge().eval("try{Player.floatOn=true; if(window.syncFloatWidget) syncFloatWidget(); if(window.syncFloatLyric && window.currentLyricText) syncFloatLyric(currentLyricText());}catch(e){}", null);
                        } else {
                            getBridge().eval("try{if(window.syncFloatWidget) syncFloatWidget();}catch(e){}", null);
                        }
                        // Diagnostics for black PiP - Step 1,2,3,4,5
                        getBridge().getWebView().postDelayed(() -> {
                            try {
                                // Native WebView state - Step 8
                                android.webkit.WebView wv = getBridge().getWebView();
                                String visStr = "UNKNOWN";
                                try {
                                    int vis = wv.getVisibility();
                                    if (vis == android.view.View.VISIBLE) visStr = "VISIBLE";
                                    else if (vis == android.view.View.INVISIBLE) visStr = "INVISIBLE";
                                    else if (vis == android.view.View.GONE) visStr = "GONE";
                                    else visStr = String.valueOf(vis);
                                } catch (Exception e) { visStr = "err:" + e; }
                                String nativeDiag = "[PiP-NATIVE] vis=" + visStr
                                        + " shown=" + wv.isShown()
                                        + " alpha=" + wv.getAlpha()
                                        + " size=" + wv.getWidth() + "x" + wv.getHeight()
                                        + " pip=" + pip;
                                android.util.Log.d(TAG_DIAG, nativeDiag);
                                // Force invalidate - Step 9 diagnostic
                                try { wv.invalidate(); wv.requestLayout(); } catch (Exception ignored) {}
                                wv.post(() -> { try { wv.invalidate(); wv.requestLayout(); } catch (Exception ignored) {} });

                                String diag = "(function(){try{"
                                        + "var w=document.getElementById('float-widget');"
                                        + "var r=w? w.getBoundingClientRect(): {width:0,height:0,top:0,left:0};"
                                        + "var cs=w? getComputedStyle(w):{display:'',visibility:'',opacity:'',zIndex:''};"
                                        + "var bodyCs = getComputedStyle(document.body);"
                                        + "var app=document.getElementById('app'); var appCs=app?getComputedStyle(app):{display:'',visibility:''}; var appR=app?app.getBoundingClientRect():{width:0,height:0};"
                                        + "var parentChain=[]; var p=w? w.parentElement:null; for(var i=0;i<4 && p;i++){ parentChain.push({tag:p.tagName + (p.id?'#'+p.id:''), display:getComputedStyle(p).display, vis:getComputedStyle(p).visibility, w:Math.round(p.getBoundingClientRect().width), h:Math.round(p.getBoundingClientRect().height)}); p=p.parentElement; }"
                                        + "return JSON.stringify({"
                                        + "bodyClass: document.body.className,"
                                        + "pipSystem: document.body.classList.contains('pip-system'),"
                                        + "floatMode: document.body.classList.contains('float-mode'),"
                                        + "floatOn: (window.Player&&window.Player.floatOn),"
                                        + "widgetExists: !!w,"
                                        + "widgetHiddenAttr: w? w.hidden : null,"
                                        + "widgetHiddenClass: w? w.classList.contains('hidden'): null,"
                                        + "hasHiddenAttr: w? w.hasAttribute('hidden'): null,"
                                        + "display: cs.display,"
                                        + "visibility: cs.visibility,"
                                        + "opacity: cs.opacity,"
                                        + "zIndex: cs.zIndex,"
                                        + "width: Math.round(r.width),"
                                        + "height: Math.round(r.height),"
                                        + "top: Math.round(r.top),"
                                        + "left: Math.round(r.left),"
                                        + "hasArt: !!document.getElementById('fw-art'),"
                                        + "lyric: document.getElementById('fw-lyric')?document.getElementById('fw-lyric').textContent: null,"
                                        + "viewport: window.innerWidth+'x'+window.innerHeight,"
                                        + "docClient: document.documentElement.clientWidth+'x'+document.documentElement.clientHeight,"
                                        + "bodyBg: bodyCs.backgroundColor,"
                                        + "appDisplay: appCs.display,"
                                        + "appVis: appCs.visibility,"
                                        + "appSize: Math.round(appR.width)+'x'+Math.round(appR.height),"
                                        + "parentChain: parentChain"
                                        + "});"
                                        + "}catch(e){return 'diag err '+e;}})()";
                                getBridge().getWebView().evaluateJavascript(diag, value -> {
                                    android.util.Log.d(TAG_DIAG, "[PiP-DIAG] pip=" + pip + " " + value);
                                });
                                // Also log via DiagnosticsBridge if available
                                getBridge().eval("try{var d=(function(){var w=document.getElementById('float-widget');var r=w?w.getBoundingClientRect():{width:0,height:0};var cs=w?getComputedStyle(w):{display:''};return 'pip='+document.body.classList.contains('pip-system')+' hidden='+ (w&&w.classList.contains('hidden'))+' display='+cs.display+' '+r.width+'x'+r.height;})(); if(window.Diagnostics) window.Diagnostics.logLine('[PiP] '+d);}catch(e){}", null);
                                // Step 6 simple render test - red background diagnostic (temporary)
                                getBridge().getWebView().evaluateJavascript("try{document.body.style.background='red'; document.getElementById('app').style.background='red'; setTimeout(()=>{document.body.style.background=''; document.getElementById('app').style.background='';}, 1200);}catch(e){}", null);
                            } catch (Exception ignored) {}
                        }, 400);
                    } catch (Exception ignored) {}
                });
            }
        } catch (Exception e) {
            android.util.Log.d(TAG_DIAG, "PiP changed notify err " + e);
        }
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
            // Phase 11 — mirror to native PiP (WebView is source of truth, no second playback system)
            try {
                if (title != null) pipTitle = title;
                if (artist != null) pipArtist = artist;
                if (artwork != null) pipArtworkUrl = artwork;
                pipIsPlaying = isPlaying;
                pipPositionMs = (long) positionMs;
                pipDurationMs = (long) durationMs;
                android.util.Log.d(TAG_DIAG, "[PipNative] stateUpdate title=" + pipTitle + " artist=" + pipArtist + " hasArt=" + (pipArtworkUrl!=null&&!pipArtworkUrl.isEmpty()) + " lyric=" + pipCurrentLyric + " pos=" + pipPositionMs + " dur=" + pipDurationMs);
                updatePipNativeView();
            } catch (Exception e) { android.util.Log.d(TAG_DIAG, "[PipNative] updateWebViewState mirror err " + e); }
            PlaybackService.updateWebViewState(MainActivity.this, title, artist, artwork, isPlaying, (long) positionMs, (long) durationMs);
        }

        @JavascriptInterface
        public void updateLyrics(String prev, String current, String next) {
            android.util.Log.d("DnialifyDiag", "Bridge updateLyrics prev=" + prev + " cur=" + current + " next=" + next);
            // Phase 11 — lyric mirror (single fallback, 10-line window via updateLyricWindow)
            try {
                pipCurrentLyric = current == null ? "" : current;
                android.util.Log.d(TAG_DIAG, "[PipNative] lyricUpdate current=" + pipCurrentLyric + " hasArt=" + (pipArtworkUrl!=null&&!pipArtworkUrl.isEmpty()) + " title=" + pipTitle);
                // if window not yet set, update single view
                if (pipLyricLines == null || pipLyricLines.isEmpty()) updatePipNativeView();
            } catch (Exception e) { android.util.Log.d(TAG_DIAG, "[PipNative] lyric mirror err " + e); }
            PlaybackService.updateLyricsStatic(MainActivity.this, prev, current, next);
        }

        @JavascriptInterface
        public void updateLyricWindow(String jsonLines, int activeIdx) {
            try {
                android.util.Log.d(TAG_DIAG, "[PipNative] lyricWindow activeIdx=" + activeIdx + " jsonLen=" + (jsonLines==null?0:jsonLines.length()));
                java.util.List<String> lines = new java.util.ArrayList<>();
                if (jsonLines != null && !jsonLines.isEmpty()) {
                    org.json.JSONArray arr = new org.json.JSONArray(jsonLines);
                    for (int i = 0; i < arr.length(); i++) lines.add(arr.optString(i, ""));
                }
                pipLyricLines = lines;
                pipLyricActiveIdx = activeIdx;
                if (activeIdx >= 0 && activeIdx < lines.size()) pipCurrentLyric = lines.get(activeIdx);
                android.util.Log.d(TAG_DIAG, "[PipNative] lyricWindow size=" + lines.size() + " active=" + activeIdx + " cur=" + pipCurrentLyric);
                updatePipNativeView();
            } catch (Exception e) { android.util.Log.d(TAG_DIAG, "[PipNative] lyricWindow err " + e); }
        }

        @JavascriptInterface
        public void play(String url, String title, String artist, String artwork) {
            PlaybackService.play(MainActivity.this, url, title, artist, artwork);
        }

        @JavascriptInterface
        public void pause() { PlaybackService.pause(MainActivity.this); }

        @JavascriptInterface
        public void stop() { PlaybackService.stop(MainActivity.this); }

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

        @JavascriptInterface
        public void enterPip() {
            android.util.Log.d(TAG_DIAG, "[Native] enterPip() CALLED sdk=" + Build.VERSION.SDK_INT + " " + lifecycleSnapshot());
            runOnUiThread(() -> {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        if (isInPictureInPictureMode()) {
                            android.util.Log.d(TAG_DIAG, "[Native] enterPip already in PiP, skip");
                            return;
                        }
                        // Phase 11 — show native PiP (artwork+title+artist+lyric) immediately before PiP entry
                        try {
                            ensurePipNativeView();
                            updatePipNativeView();
                            if (pipNativeView != null) {
                                pipNativeView.setVisibility(View.VISIBLE);
                                pipNativeView.bringToFront();
                                pipNativeView.requestLayout();
                                pipNativeView.invalidate();
                                ViewGroup parent = (ViewGroup) pipNativeView.getParent();
                                if (parent != null) { parent.requestLayout(); parent.invalidate(); }
                            }
                            logPipNative("enterPip:VISIBLE before enter");
                        } catch (Exception e) { android.util.Log.d(TAG_DIAG, "[PipNative] enterPip show err " + e); }
                        Rational ratio = new Rational(9, 16);
                        PictureInPictureParams params = new PictureInPictureParams.Builder()
                                .setAspectRatio(ratio)
                                .build();
                        boolean result = enterPictureInPictureMode(params);
                        android.util.Log.d(TAG_DIAG, "[Native] enterPictureInPictureMode result=" + result + " " + lifecycleSnapshot());
                        logPipNative("enterPip:afterEnter result=" + result);
                    } else {
                        android.util.Log.d(TAG_DIAG, "[Native] enterPip skipped SDK<26");
                    }
                } catch (Exception e) {
                    android.util.Log.d(TAG_DIAG, "[Native] enterPip failed exception=" + e);
                    android.util.Log.d(TAG_DIAG, "[Native] enterPictureInPictureMode exception " + e);
                }
            });
        }

        @JavascriptInterface
        public void exitPip() {
            // System gesture exits PiP; no direct API needed pre-Android 12
        }

        @JavascriptInterface
        public boolean isInPip() {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) return isInPictureInPictureMode();
            } catch (Exception ignored) {}
            return false;
        }
    }
}
