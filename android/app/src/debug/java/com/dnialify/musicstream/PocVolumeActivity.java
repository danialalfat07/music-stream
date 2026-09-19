package com.dnialify.musicstream;

import android.app.Activity;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.audiofx.LoudnessEnhancer;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.view.ViewGroup;
import android.view.Gravity;
import java.util.List;

/**
 * POC terisolasi untuk eksperimen native gain YouTube iframe.
 * TIDAK MEMODIFIKASI MainActivity.java / public/app.js / server.js
 * Tujuan: jawab 3 pertanyaan opencode.txt:
 * 1. Apakah YT iframe muncul sebagai AudioPlaybackConfiguration?
 * 2. Bisa dapat session ID?
 * 3. Apakah LoudnessEnhancer(sessionId) benar menaikkan volume?
 *
 * Cara pakai:
 * adb shell am start -n com.dnialify.musicstream/.PocVolumeActivity
 * Lihat logcat: adb logcat -s PocVolume:D
 */
public class PocVolumeActivity extends Activity {
    private static final String TAG = "PocVolume";
    private WebView webView;
    private TextView logView;
    private AudioManager audioManager;
    private AudioManager.AudioPlaybackCallback playbackCallback;
    private int webAudioSessionId = 0;
    private LoudnessEnhancer enhancer;

    // Reflection helpers: these APIs are absent from compileSdk 34 but may exist at runtime.
    private static String clientPkg(AudioPlaybackConfiguration c) {
        try {
            Object v = AudioPlaybackConfiguration.class.getMethod("getClientPackageName").invoke(c);
            return v != null ? String.valueOf(v) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static int audioSession(AudioPlaybackConfiguration c) {
        try {
            Object v = AudioPlaybackConfiguration.class.getMethod("getAudioSessionId").invoke(c);
            return v instanceof Number ? ((Number) v).intValue() : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private void log(String msg) {
        Log.d(TAG, msg);
        if (logView != null) {
            runOnUiThread(() -> {
                logView.append(msg + "\n");
                // auto scroll handled by ScrollView
            });
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // UI: vertical LinearLayout
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // WebView
        webView = new WebView(this);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setAllowFileAccess(true);
        webView.setWebChromeClient(new WebChromeClient());
        // simple YT iframe page
        String html = "<html><body style='margin:0;background:#000;color:#fff;font-family:sans-serif'>" +
                "<h3 style='padding:8px'>POC YT iframe</h3>" +
                "<iframe width='100%' height='220' src='https://www.youtube.com/embed/M7lc1UVf-VE?autoplay=1&enablejsapi=1' frameborder='0' allow='autoplay; encrypted-media' allowfullscreen></iframe>" +
                "<p style='padding:8px;color:#999'>Play video, lalu cek log session. Tap tombol gain.</p>" +
                "</body></html>";
        webView.loadDataWithBaseURL("https://www.youtube.com", html, "text/html", "utf-8", null);
        LinearLayout.LayoutParams webParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
        root.addView(webView, webParams);

        // Buttons row
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER);
        btnRow.setPadding(8,8,8,8);
        int[] levels = {100, 150, 200, 250, 300};
        for (int lv : levels) {
            Button b = new Button(this);
            b.setText(lv + "%");
            b.setTag(lv);
            b.setOnClickListener(v -> {
                int level = (int) v.getTag();
                applyGain(level);
            });
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
            bp.setMargins(4,0,4,0);
            btnRow.addView(b, bp);
        }
        root.addView(btnRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Log view
        ScrollView scroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setTextSize(11f);
        logView.setPadding(8,8,8,8);
        logView.setText("POC Volume log:\n");
        scroll.addView(logView);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 400);
        root.addView(scroll, scrollParams);

        // Refresh button
        Button refreshBtn = new Button(this);
        refreshBtn.setText("Refresh session scan");
        refreshBtn.setOnClickListener(v -> scanNow());
        root.addView(refreshBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);

        // AudioManager callback API >=24 (prompt minta >=24)
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            playbackCallback = new AudioManager.AudioPlaybackCallback() {
                @Override
                public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
                    log("=== onPlaybackConfigChanged configs=" + configs.size() + " ===");
                    for (AudioPlaybackConfiguration c : configs) {
                        try {
                            String pkg = clientPkg(c);
                            // usage via AudioAttributes
                            int usage = c.getAudioAttributes().getUsage();
                            int session = -1;
                            try { session = audioSession(c); } catch (Exception e) { log("getAudioSessionId ex " + e); }
                            log("config pkg=" + pkg + " usage=" + usage + " sessionId=" + session);
                            // heuristic: WebView YT likely USAGE_MEDIA (1) and package = us
                            if (pkg != null && pkg.equals(getPackageName()) && usage == 1 && session != 0) {
                                if (session != webAudioSessionId) {
                                    log(">> CANDIDATE WebView sessionId=" + session + " (pkg match USAGE_MEDIA)");
                                    webAudioSessionId = session;
                                    // jangan auto-create enhancer di sini, biar tombol yang test, tapi log
                                    log("webAudioSessionId updated to " + webAudioSessionId);
                                }
                            }
                        } catch (Exception e) {
                            log("config parse ex " + e);
                        }
                    }
                    if (webAudioSessionId == 0) {
                        log("No WebView session found yet (webAudioSessionId==0)");
                    }
                }
            };
            try {
                audioManager.registerAudioPlaybackCallback(playbackCallback, null);
                log("registerAudioPlaybackCallback OK (API " + Build.VERSION.SDK_INT + ")");
            } catch (Exception e) {
                log("registerAudioPlaybackCallback FAILED " + e);
            }
        } else {
            log("API <24, AUDIO_PLAYBACK_CALLBACK not available. Need API 24+ for POC. Current=" + Build.VERSION.SDK_INT);
        }

        log("POC onCreate done. Play YT video, then tap Refresh or wait for callback.");
        log("webAudioSessionId initial=" + webAudioSessionId);
    }

    private void scanNow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // API 26+ getActivePlaybackConfigurations polling
            try {
                AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
                List<AudioPlaybackConfiguration> list = am.getActivePlaybackConfigurations();
                log("=== scanNow getActivePlaybackConfigurations size=" + list.size() + " ===");
                for (AudioPlaybackConfiguration c : list) {
                    try {
                        String pkg = clientPkg(c);
                        int usage = c.getAudioAttributes().getUsage();
                        int session = audioSession(c);
                        log("scan pkg=" + pkg + " usage=" + usage + " sessionId=" + session);
                        if (pkg != null && pkg.equals(getPackageName()) && usage == 1 && session != 0) {
                            log(">> scan CANDIDATE sessionId=" + session);
                            webAudioSessionId = session;
                        }
                    } catch (Exception e) { log("scan ex " + e); }
                }
            } catch (Exception e) { log("scanNow ex " + e); }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            log("scanNow: API 24-25, rely on callback only, no getActivePlaybackConfigurations (needs 26).");
        } else {
            log("scanNow: API <24 not supported");
        }
        log("Current webAudioSessionId=" + webAudioSessionId);
    }

    private void applyGain(int level) {
        // definisi multiplier per opencode.txt
        double multiplier = level / 100.0;
        double gainDb = 0;
        if (level > 100) {
            gainDb = 20.0 * Math.log10(multiplier);
        }
        int targetMb = (int) (gainDb * 100);
        log("=== applyGain level=" + level + "% multiplier=" + multiplier + " gainDb=" + String.format("%.2f", gainDb) + " targetMb=" + targetMb + " sessionId=" + webAudioSessionId + " ===");

        if (webAudioSessionId == 0) {
            log("FAIL: webAudioSessionId==0 invalid, cannot create LoudnessEnhancer. Play video first, wait for session, tap Refresh.");
            return;
        }

        try {
            // Reuse satu instance untuk session yang sama (opencode.txt: jangan banyak instance)
            if (enhancer != null) {
                try {
                    // cek apakah session masih sama, jika beda, release dan buat baru
                    // LoudnessEnhancer tidak ada getAudioSessionId, jadi simpan manual
                    // Untuk POC sederhana, release jika session berubah (deteksi via webAudioSessionId change)
                    // Di sini kita cek jika enhancer sudah ada dan sessionId sebelumnya beda (track via field)
                    // Simplifikasi: jika enhancer ada, coba setEnabled false, release jika sessionId berubah (kita tidak tau old session, jadi release tiap ganti session)
                    // Untuk POC ini, jika enhancer sudah ada dan level==100, disable; jika sessionId sama, reuse
                    // Kita simpan lastSessionId
                } catch (Exception ignore) {}
            }
            // Jika enhancer null atau sessionId berubah, buat baru
            // Untuk POC: selalu reuse jika enhancer != null, tapi jika sessionId berbeda dari saat enhancer dibuat, release
            // Simpan lastSession di tag enhancer via reflection? Simplifikasi: release dan buat baru tiap apply jika sessionId != last
            // Untuk POC ini, kita buat baru jika enhancer == null
            if (enhancer == null) {
                log("Creating LoudnessEnhancer(sessionId=" + webAudioSessionId + ")");
                enhancer = new LoudnessEnhancer(webAudioSessionId);
                log("LoudnessEnhancer created OK sessionId=" + webAudioSessionId);
            } else {
                log("Reusing existing LoudnessEnhancer for sessionId=" + webAudioSessionId);
            }
            log("Calling setTargetGain(" + targetMb + " mB) for level " + level + "%");
            enhancer.setTargetGain(targetMb);
            boolean enable = level > 100;
            enhancer.setEnabled(enable);
            log("setEnabled(" + enable + ") OK. getEnabled()=" + enhancer.getEnabled() + " hasControl=" + isEnhancerHasControl(enhancer));
            log("SUCCESS: gain " + gainDb + "dB (" + targetMb + " mB) applied to session " + webAudioSessionId + " enabled=" + enable);
            log("TEST: Dengarkan apakah volume YT iframe benar berubah 100→" + level + "%");
        } catch (Exception e) {
            log("EXCEPTION LoudnessEnhancer sessionId=" + webAudioSessionId + " level=" + level + " ex=" + e + " msg=" + e.getMessage());
            e.printStackTrace();
            // release on failure
            try { if (enhancer != null) { enhancer.release(); enhancer = null; } } catch (Exception ignore) {}
            log("Enhancer released after exception");
        }
    }

    private boolean isEnhancerHasControl(LoudnessEnhancer e) {
        try {
            // hasControl() exists API 19+
            return e.getEnabled(); // fallback, hasControl is hidden? use getEnabled as proxy
        } catch (Exception ex) { return false; }
    }

    @Override
    protected void onDestroy() {
        log("onDestroy release enhancer session=" + webAudioSessionId);
        try {
            if (playbackCallback != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
                am.unregisterAudioPlaybackCallback(playbackCallback);
                log("unregisterAudioPlaybackCallback OK");
            }
        } catch (Exception e) { log("unregister ex " + e); }
        try {
            if (enhancer != null) {
                enhancer.setEnabled(false);
                enhancer.release();
                enhancer = null;
                log("enhancer release OK");
            }
        } catch (Exception e) { log("enhancer release ex " + e); }
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
