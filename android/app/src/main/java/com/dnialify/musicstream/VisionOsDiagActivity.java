package com.dnialify.musicstream;

import android.app.Activity;
import android.media.MediaMetadataRetriever;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Phase 8 - native VisionOS diagnostic screen (diagnostic tool, NOT a feature).
 * Runs the 16 staged checks with timestamped PASS/FAIL lines to logcat TAG
 * "DnialifyVisionOS" and to the on-screen log. Auto-runs on launch unless the
 * launching intent carries autorun=false. Bounded waits only, no retry loops.
 */
public class VisionOsDiagActivity extends Activity {
    static final String TAG = "DnialifyVisionOS";
    static final String TEST_VIDEO_ID = "M7lc1UVf-VE";

    private TextView logView;
    private TextView resultView;
    private Button runButton;
    private Button stopButton;
    private WebView web;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean running;
    private volatile boolean stopReq;
    private int passCount;
    private int failCount;

    // audio observation state (filled via bridge)
    private volatile double lastCur = -1;
    private volatile double lastDur;
    private volatile boolean lastPlaying;
    private volatile boolean lastError;
    private volatile int lastErrorCode;
    private volatile long lastEventMs;

    public class DiagBridge {
        @JavascriptInterface
        public void log(final String m) {
            dlog("audio " + m);
        }

        @JavascriptInterface
        public void state(final double cur, final double dur, final boolean playing,
                final boolean err, final int errCode) {
            lastCur = cur;
            lastDur = dur;
            lastPlaying = playing;
            lastEventMs = System.currentTimeMillis();
            if (err && !lastError) {
                lastError = true;
                lastErrorCode = errCode;
            }
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        // ADB harness path (primary automated interface, no taps):
        //   am start -n .../.VisionOsDiagActivity --es diag_action <action> ...
        if (VisionOsHarness.isAction(getIntent())) {
            TextView tv = new TextView(this);
            try {
                tv.setText("Harness: " + getIntent().getStringExtra("diag_action")
                        + "\nsee logcat -s DnialifyVisionOS");
            } catch (Exception ignored) {}
            setContentView(tv);
            VisionOsHarness.run(this, getIntent());
            return;
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(12 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("VisionOS Native Diagnostic (Phase 8)");
        title.setTextSize(18);
        root.addView(title);

        resultView = new TextView(this);
        resultView.setTextSize(14);
        resultView.setText("idle");
        root.addView(resultView);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        runButton = new Button(this);
        runButton.setText("Run");
        stopButton = new Button(this);
        stopButton.setText("Stop");
        btns.addView(runButton);
        btns.addView(stopButton);
        root.addView(btns);

        logView = new TextView(this);
        logView.setTextSize(11);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(logView);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setMediaPlaybackRequiresUserGesture(false);
        web.getSettings().setDomStorageEnabled(true);
        web.addJavascriptInterface(new DiagBridge(), "Diag");
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());
        ((ViewGroup) root).addView(web, new LinearLayout.LayoutParams(1, 1));

        runButton.setOnClickListener(v -> startRun());
        stopButton.setOnClickListener(v -> {
            stopReq = true;
            dlog("STOP requested");
        });

        boolean autorun = true;
        try {
            if (getIntent() != null && getIntent().hasExtra("autorun")) {
                autorun = getIntent().getBooleanExtra("autorun", true);
            }
        } catch (Exception ignored) {}
        if (autorun) {
            main.postDelayed(this::startRun, 1500);
        }
    }

    private String ts() {
        return new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
    }

    private void dlog(final String m) {
        Log.d(TAG, m);
        runOnUiThread(() -> {
            if (logView != null) logView.append("[" + ts() + "] " + m + "\n");
        });
    }

    private void step(boolean ok, String name, String detail) {
        if (ok) {
            passCount++;
            dlog("STEP " + name + " PASS" + (detail != null ? " " + detail : ""));
        } else {
            failCount++;
            dlog("STEP " + name + " FAIL" + (detail != null ? " " + detail : ""));
        }
        final String r = "PASS=" + passCount + " FAIL=" + failCount;
        runOnUiThread(() -> {
            if (resultView != null) resultView.setText(r);
        });
    }

    private void startRun() {
        if (running) return;
        running = true;
        stopReq = false;
        passCount = 0;
        failCount = 0;
        new Thread(this::runAll).start();
    }

    private boolean cancelled() {
        return stopReq;
    }

    private void runAll() {
        dlog("DIAG START id=" + TEST_VIDEO_ID);
        try {
            // 1 visitorData
            VisionOsResolver.Result res = null;
            try {
                boolean had = VisionOsResolver.hasFreshVisitor();
                String vd = VisionOsResolver.getVisitorData(false);
                step(vd != null && !vd.isEmpty(), "1-visitorData",
                        had ? "(session cached)" : "(fresh)");
                if (vd == null || vd.isEmpty()) return;
            } catch (VisionOsResolver.ResolverException e) {
                step(false, "1-visitorData",
                        "reason=" + e.reason + " stage=" + e.stage + " " + e.getMessage());
                return;
            }
            if (cancelled()) return;
            // 2+3 resolve + itag
            try {
                res = VisionOsResolver.resolve(TEST_VIDEO_ID);
                step(true, "2-resolve", "itag=" + res.itag + " retriedVisitor=" + res.retriedVisitor);
                step(res.itag == 251, "3-itag", "want=251 got=" + res.itag + " mime=" + res.mimeType);
            } catch (VisionOsResolver.ResolverException e) {
                step(false, "2-resolve", "reason=" + e.reason + " stage=" + e.stage + " " + e.getMessage()
                        + " action=fallback-iFrame");
                return;
            }
            if (cancelled()) return;
            final String url = res.url;
            String host = res.host;
            dlog("META videoId=" + TEST_VIDEO_ID + " title=" + res.title + " artist=" + res.author);
            dlog("META thumb=" + res.thumbnail + " durationMs=" + res.durationMs + " method=VisionOS Audio");
            dlog("META lyrics=existing-web-flow-untouched (no new scraping)");
            // 4 chunk probe 0-524287
            long total = clenOf(url);
            ChunkProbe p0 = probeChunk(url, 0, 524287, total);
            step(p0.ok, "4-chunk-probe", p0.detail);
            if (!p0.ok || cancelled()) return;
            // 5+6+13 sequential full download (each chunk verified; also the cache write)
            VisionOsCache.CacheResult cr;
            try {
                VisionOsNet.resetCounters();
                cr = VisionOsCache.download(this, TEST_VIDEO_ID, url, total);
                step(true, "5-chunks", "chunks=" + cr.chunks + " all-206");
                step(cr.bytes == total, "6-full-download", "bytes=" + cr.bytes + "/" + total);
                step(true, "13-cache-write", "path=" + cr.file.getAbsolutePath());
            } catch (Exception e) {
                step(false, "5-chunks", "reason=" + VisionOsResolver.reasonFromMessage(
                        String.valueOf(e.getMessage())) + " " + String.valueOf(e.getMessage()));
                return;
            }
            if (cancelled()) return;
            // 7 validation + 16 duration
            long durMs = fileDurationMs(cr.file);
            step(cr.file.length() == total, "7-validation",
                    "size=" + cr.file.length() + " durMs=" + durMs);
            dlog("META duration=" + (durMs / 1000.0) + "s source=MediaMetadataRetriever(local file)");
            if (cancelled()) return;
            // 8+9 direct stream + HTML5 playback
            if (!playAndWatch(url, "8-direct-stream/9-playback", 15)) return;
            if (cancelled()) return;
            // 10 seek
            if (!seekAndWatch(600)) return;
            if (cancelled()) return;
            // 11 background: wait for Home (bounded), then verify 15s progress
            dlog("STEP 11-background: press HOME now (90s window)");
            if (!waitForBackground(90)) {
                step(false, "11-background", "SKIPPED-no-home (not a failure of pipeline)");
            } else if (!watchProgress(15, "11-background")) {
                return;
            } else {
                step(true, "11-background", "advanced while backgrounded");
            }
            if (cancelled()) return;
            // 12 screen-off: bounded wait for screen off, then verify 15s
            dlog("STEP 12-screen-off: turn SCREEN OFF now (90s window)");
            if (!waitForScreenOff(90)) {
                step(false, "12-screen-off", "SKIPPED-no-screen-off (not a failure of pipeline)");
            } else if (!watchProgress(15, "12-screen-off")) {
                return;
            } else {
                step(true, "12-screen-off", "advanced while screen off");
            }
            if (cancelled()) return;
            // 14 cache playback + 15 no-YouTube proof
            try {
                VisionOsNet.setBlockNetwork(true);
                VisionOsNet.resetCounters();
                String uri = VisionOsCache.contentUri(this, TEST_VIDEO_ID).toString();
                dlog("STEP 14-cache-playback uri=" + uri + " network=BLOCKED");
                if (!playAndWatch(uri, "14-cache-playback", 10)) return;
                long g = VisionOsNet.googleRequests();
                step(g == 0, "15-cache-hit-no-youtube", "googlevideoRequests=" + g);
            } finally {
                VisionOsNet.setBlockNetwork(false);
            }
            dlog("DIAG DONE PASS=" + passCount + " FAIL=" + failCount);
        } catch (Exception e) {
            dlog("DIAG unexpected " + e);
        } finally {
            running = false;
        }
    }

    private long clenOf(String url) {
        try {
            String q = new URL(url).getQuery();
            if (q != null) {
                for (String kv : q.split("&")) {
                    int eq = kv.indexOf('=');
                    if (eq > 0 && kv.substring(0, eq).equals("clen")) {
                        return Long.parseLong(java.net.URLDecoder.decode(
                                kv.substring(eq + 1), "UTF-8"));
                    }
                }
            }
        } catch (Exception ignored) {}
        return -1;
    }

    private static final class ChunkProbe {
        boolean ok;
        String detail;
    }

    private ChunkProbe probeChunk(String url, long start, long end, long total) {
        ChunkProbe p = new ChunkProbe();
        HttpURLConnection h = null;
        int status = -1;
        try {
            VisionOsNet.noteRequest(new URL(url).getHost());
            h = (HttpURLConnection) new URL(url).openConnection();
            h.setRequestMethod("GET");
            h.setConnectTimeout(20000);
            h.setReadTimeout(20000);
            h.setRequestProperty("Range", "bytes=" + start + "-" + end);
            status = h.getResponseCode();
            String cr = h.getHeaderField("Content-Range");
            java.io.InputStream in = status >= 400 ? h.getErrorStream() : h.getInputStream();
            long got = 0;
            if (in != null) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) != -1) got += n;
                in.close();
            }
            boolean rangeOk = ("bytes " + start + "-" + end + "/" + total).equals(cr);
            p.ok = status == 206 && rangeOk && got == (end - start + 1);
            String reason = VisionOsResolver.classifyFetch(status, null);
            p.detail = "status=" + status + " reason=" + reason + " range=" + cr + " bytes=" + got;
        } catch (Exception e) {
            p.ok = false;
            p.detail = "reason=" + VisionOsResolver.classifyFetch(-1, e) + " threw " + e;
        } finally {
            if (h != null) h.disconnect();
        }
        return p;
    }

    private long fileDurationMs(File f) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(f.getAbsolutePath());
            String d = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return d != null ? Long.parseLong(d) : 0;
        } catch (Exception e) {
            return 0;
        } finally {
            try {
                mmr.release();
            } catch (Exception ignored) {}
        }
    }

    /** Load src into diag-WebView audio element and watch until playing+advanced or timeout. */
    private boolean playAndWatch(String src, String tag, int needSec) throws InterruptedException {
        lastCur = -1;
        lastPlaying = false;
        lastError = false;
        final String safe = src.replace("'", "%27");
        eval("try{(function(){var el=document.getElementById('diagAudio');"
                + "if(!el){el=document.createElement('audio');el.id='diagAudio';"
                + "el.preload='metadata';"
                + "el.addEventListener('loadedmetadata',function(){Diag.log('loadedmetadata dur='+el.duration);});"
                + "el.addEventListener('canplay',function(){Diag.log('canplay');});"
                + "el.addEventListener('playing',function(){Diag.state(el.currentTime,el.duration,true,false,0);Diag.log('playing');});"
                + "el.addEventListener('pause',function(){Diag.log('pause cur='+el.currentTime);});"
                + "el.addEventListener('waiting',function(){Diag.log('waiting cur='+el.currentTime+' rs='+el.readyState);});"
                + "el.addEventListener('stalled',function(){Diag.log('stalled cur='+el.currentTime+' ns='+el.networkState);});"
                + "el.addEventListener('error',function(){Diag.state(el.currentTime,el.duration||0,false,true,el.error?el.error.code:-1);Diag.log('error code='+(el.error?el.error.code:'?'));});"
                + "var lt=0;el.addEventListener('timeupdate',function(){Diag.state(el.currentTime,el.duration||0,!el.paused,false,0);var n=Date.now();if(n-lt>4000){lt=n;Diag.log('timeupdate cur='+el.currentTime);}});"
                + "document.body.appendChild(el);}"
                + "el.src='" + safe + "';el.play().then(function(){},function(e){Diag.log('play rejected '+e);});"
                + "return 'SRC_SET';})();}catch(e){Diag.log('handoff threw '+e);}");
        long t0 = System.currentTimeMillis();
        double firstCur = -1;
        while (System.currentTimeMillis() - t0 < 45000) {
            if (cancelled()) return false;
            Thread.sleep(1000);
            if (lastError) {
                step(false, tag, "reason=" + VisionOsResolver.R_MEDIA_ERROR
                        + " " + VisionOsResolver.mediaErrorName(lastErrorCode));
                return false;
            }
            if (lastPlaying && firstCur < 0 && lastCur > 0) firstCur = lastCur;
            if (lastPlaying && firstCur >= 0 && lastCur - firstCur >= needSec) {
                step(true, tag, "playing, advanced " + needSec + "s+");
                return true;
            }
        }
        step(false, tag, "reason=" + VisionOsResolver.R_TIMEOUT
                + " timeout playing=" + lastPlaying + " cur=" + lastCur);
        return false;
    }

    private boolean seekAndWatch(int sec) throws InterruptedException {
        dlog("STEP 10-seek to " + sec + "s");
        lastCur = -1;
        eval("try{var el=document.getElementById('diagAudio');if(el){el.currentTime=" + sec
                + ";Diag.log('seek set');}}catch(e){Diag.log('seek threw '+e);}");
        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < 20000) {
            if (cancelled()) return false;
            Thread.sleep(1000);
            if (lastError) {
                step(false, "10-seek", "reason=" + VisionOsResolver.R_MEDIA_ERROR
                        + " " + VisionOsResolver.mediaErrorName(lastErrorCode));
                return false;
            }
            if (lastCur >= sec) {
                step(true, "10-seek", "cur=" + lastCur);
                return true;
            }
        }
        step(false, "10-seek", "reason=" + VisionOsResolver.R_TIMEOUT + " timeout cur=" + lastCur);
        return false;
    }

    private boolean waitForBackground(int secs) throws InterruptedException {
        for (int i = 0; i < secs; i++) {
            if (cancelled()) return false;
            if (isBackgrounded()) return true;
            Thread.sleep(1000);
        }
        return false;
    }

    private boolean isBackgrounded() {
        try {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am == null) return false;
            java.util.List<android.app.ActivityManager.RunningAppProcessInfo> ps =
                    am.getRunningAppProcesses();
            if (ps == null) return false;
            for (android.app.ActivityManager.RunningAppProcessInfo p : ps) {
                if (p.processName != null && p.processName.equals(getPackageName())) {
                    return p.importance
                            != android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private boolean waitForScreenOff(int secs) throws InterruptedException {
        try {
            android.os.PowerManager pm =
                    (android.os.PowerManager) getSystemService(POWER_SERVICE);
            for (int i = 0; i < secs; i++) {
                if (cancelled()) return false;
                if (pm != null && !pm.isInteractive()) return true;
                Thread.sleep(1000);
            }
        } catch (Exception ignored) {}
        return false;
    }

    /** Verify currentTime advances `needSec` from now (bounded 60s). */
    private boolean watchProgress(int needSec, String tag) throws InterruptedException {
        double base = lastCur;
        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < 60000) {
            if (cancelled()) return false;
            Thread.sleep(2000);
            pollState();
            if (lastError) {
                step(false, tag, "reason=" + VisionOsResolver.R_MEDIA_ERROR
                        + " " + VisionOsResolver.mediaErrorName(lastErrorCode));
                return false;
            }
            if (base >= 0 && lastCur - base >= needSec) {
                step(true, tag, "+" + needSec + "s while " + tag);
                return true;
            }
        }
        step(false, tag, "reason=" + VisionOsResolver.R_TIMEOUT + " timeout cur=" + lastCur);
        return false;
    }

    private void pollState() {
        eval("try{var el=document.getElementById('diagAudio');"
                + "if(el){Diag.state(el.currentTime,el.duration||0,!el.paused,!!el.error,el.error?el.error.code:0);}"
                + "}catch(e){}");
    }

    private void eval(final String js) {
        try {
            runOnUiThread(() -> {
                try {
                    web.evaluateJavascript(js, null);
                } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }

    @Override
    protected void onPause() {
        super.onPause();
        dlog("lifecycle onPause (app background)");
    }

    @Override
    protected void onResume() {
        super.onResume();
        dlog("lifecycle onResume");
    }

    @Override
    protected void onDestroy() {
        stopReq = true;
        try {
            if (web != null) web.destroy();
        } catch (Exception ignored) {}
        super.onDestroy();
        Log.d(TAG, "lifecycle onDestroy");
    }
}
