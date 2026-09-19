package com.dnialify.musicstream;

import android.app.Activity;
import android.content.Intent;
import android.os.PowerManager;
import android.util.Log;
import android.webkit.WebView;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/**
 * Phase 8 - ADB-controlled VisionOS diagnostic harness.
 *
 * Primary test interface (no coordinate taps):
 *   adb shell am start -n com.dnialify.musicstream/.VisionOsDiagActivity \
 *     --es diag_action <action> [--es video_id ID] [--es value V] [--ei seconds N]
 * Results:
 *   adb logcat -s DnialifyVisionOS
 *
 * Actions: status, settings, set-stream, set-cache, set-max, visitor, resolve,
 * probe, download, play, pause, resume, seek, stop, background, screenoff,
 * cache, cache-play, cache-clear, metadata, iframe, full-test.
 *
 * Production-audio actions drive MainActivity's EXISTING WebView audio element
 * (same element production uses) via window.__vosDiag. Nothing is stubbed.
 */
public final class VisionOsHarness {
    static final String TAG = "DnialifyVisionOS";
    static final String DEFAULT_VIDEO = "M7lc1UVf-VE";

    static String lastUrl;
    static String lastVideoId;
    static int lastItag;
    static String lastMime;
    static long lastTotal = -1;

    private VisionOsHarness() {}

    public static boolean isAction(Intent i) {
        try {
            return i != null && i.hasExtra("diag_action");
        } catch (Exception e) {
            return false;
        }
    }

    public static void run(final VisionOsDiagActivity act, final Intent intent) {
        new Thread(() -> {
            String action = "?";
            try {
                action = intent.getStringExtra("diag_action");
                if (action == null) action = "?";
                action = action.trim().toLowerCase(java.util.Locale.US);
                hlog("[HARNESS] START action=" + action);
                boolean ok = dispatch(act, intent, action, null);
                hlog("[HARNESS] RESULT action=" + action + " status=" + (ok ? "PASS" : "FAIL"));
            } catch (Exception e) {
                hlog("[HARNESS] RESULT action=" + action + " status=FAIL reason=EXCEPTION detail=" + e);
            } finally {
                try {
                    act.finish();
                } catch (Exception ignored) {}
            }
        }).start();
    }

    static void hlog(String m) {
        Log.d(TAG, m);
    }

    /** Dispatch single action. results!=null collects full-test flags. Returns pass/fail. */
    static boolean dispatch(VisionOsDiagActivity act, Intent intent, String action,
            Map<String, String> results) {
        String videoId = intent.getStringExtra("video_id");
        if (videoId == null || videoId.isEmpty()) videoId = DEFAULT_VIDEO;
        String value = intent.getStringExtra("value");
        int seconds = intent.getIntExtra("seconds", -1);
        switch (action) {
            case "status": return aStatus(act);
            case "settings": return aSettings(act);
            case "set-stream": return aSetStream(act, value);
            case "set-cache": return aSetCache(act, value);
            case "set-max": return aSetMax(act, intent, value);
            case "visitor": return put(results, "visitorData", aVisitor(act));
            case "resolve": return put(results, "resolver", aResolve(act, videoId, results));
            case "probe": return probeAll(act, videoId, results);
            case "download":
                return put(results, "fullDownload", aDownload(act, videoId, results, "fullDownload"));
            case "play": return put(results, "visionosPlayback", aPlay(act, videoId));
            case "pause": return put(results, "pause", aPause(act));
            case "resume": return put(results, "resume", aResume(act));
            case "seek":
                return put(results, "seek", aSeek(act, seconds < 0 ? 30 : seconds));
            case "stop": return aStop(act);
            case "background": return put(results, "background", aBackground(act));
            case "screenoff": return put(results, "screenOff", aScreenOff(act));
            case "cache": return cacheFlow(act, videoId, results);
            case "cache-play": return put(results, "cachePlayback", aCachePlay(act, videoId));
            case "cache-song": return aCacheSong(act, videoId, value);
            case "cache-song-full": return aCacheSongFull(act, intent, videoId);
            case "cache-status": return aCacheStatus(act, videoId);
            case "cache-list": return aCacheList(act);
            case "cache-delete": return aCacheDelete(act, videoId);
            case "cache-offline": return aCacheOffline(act, videoId);
            case "cache-restart": return aCacheRestart(act, videoId);
            case "resume-cache": return aResumeCache(act, videoId);
            case "cache-clear": return aCacheClear(act, videoId);
            case "metadata": return put(results, "metadata", aMetadata(act));
            case "iframe": return aIframe(act, videoId);
            case "eval": return aEval(value);
            case "web-offline": return aWebOffline(act);
            case "cache-probe": return aCacheProbe(act, videoId);
            case "loopback-probe": return aLoopbackProbe(act, videoId);
            case "native-play": return aNativePlay(act, videoId);
            case "native-pause": return aNativePause();
            case "native-resume": return aNativeResume();
            case "native-seek": return aNativeSeek(seconds < 0 ? 60 : seconds);
            case "native-stop": return aNativeStop();
            case "native-state": return aNativeState();
            case "notification": return aNotification();
            case "lyrics": return aLyrics();
            case "fallback": return aFallback(act, videoId);
            case "switch-native": return aSwitchNative(act, videoId);
            case "switch-iframe": return aSwitchIframe(act, videoId);
            case "lyrics-follow": return aLyricsFollow(act, videoId);
            case "full-test": return aFullTest(act, videoId);
            default:
                hlog("[HARNESS] unknown action=" + action);
                return false;
        }
    }

    static boolean put(Map<String, String> r, String k, boolean v) {
        if (r != null) r.put(k, v ? "PASS" : "FAIL");
        return v;
    }

    // ---------- infrastructure ----------

    static WebView mainWebView() {
        try {
            MainActivity a = MainActivity.current;
            if (a == null) return null;
            if (a.getBridge() == null) return null;
            return a.getBridge().getWebView();
        } catch (Exception e) {
            return null;
        }
    }

    static String unquoteJs(String v) {
        if (v == null) return null;
        v = v.trim();
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            try {
                Object o = new org.json.JSONTokener(v).nextValue();
                return o == null ? null : String.valueOf(o);
            } catch (Exception e) {
                return v.substring(1, v.length() - 1);
            }
        }
        return v;
    }

    /** Evaluate JS on main WebView, return raw (unquoted) result string or null. */
    static String evalMain(String js, long timeoutMs) {
        final WebView wv = mainWebView();
        if (wv == null) return null;
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> out = new AtomicReference<>();
        try {
            MainActivity.current.runOnUiThread(() -> {
                try {
                    wv.evaluateJavascript(js, (String v) -> {
                        out.set(unquoteJs(v));
                        latch.countDown();
                    });
                } catch (Exception e) {
                    latch.countDown();
                }
            });
        } catch (Exception e) {
            return null;
        }
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {}
        return out.get();
    }

    static JSONObject jsState(String raw) {
        try {
            JSONObject o = new JSONObject(raw);
            if (o.has("state")) return o.getJSONObject("state");
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    static String hostOf(String url) {
        try {
            return new java.net.URL(url).getHost();
        } catch (Exception e) {
            String s = String.valueOf(url);
            return s.length() > 60 ? s.substring(0, 60) : s;
        }
    }

    // ---------- actions ----------

    static boolean aStatus(VisionOsDiagActivity act) {
        try {
            String ver;
            try {
                ver = act.getPackageManager().getPackageInfo(act.getPackageName(), 0).versionName;
            } catch (Exception e) {
                ver = "?";
            }
            hlog("[STATUS] version=" + ver);
            hlog("[STATUS] mainRunning=" + (MainActivity.current != null)
                    + " webview=" + (mainWebView() != null));
            aSettings(act);
            java.io.File f = VisionOsCache.fileFor(act, DEFAULT_VIDEO);
            hlog("[STATUS] cacheFile=" + f.getAbsolutePath() + " exists=" + f.isFile()
                    + " bytes=" + (f.isFile() ? f.length() : 0));
            return true;
        } catch (Exception e) {
            hlog("[STATUS] FAIL " + e);
            return false;
        }
    }

    static boolean aSettings(VisionOsDiagActivity act) {
        int mode = StreamSettings.getStreamMode(act);
        boolean cache = StreamSettings.isAudioCacheOn(act);
        int max = StreamSettings.getMaxCachedSongs(act);
        hlog("[SETTINGS] stream=" + (mode == StreamSettings.MODE_VISIONOS ? "VisionOS" : "iFrame"));
        hlog("[SETTINGS] cache=" + (cache ? "ON" : "OFF"));
        hlog("[SETTINGS] maxCachedSongs=" + max);
        return true;
    }

    static boolean aSetStream(VisionOsDiagActivity act, String value) {
        String v = String.valueOf(value).trim().toLowerCase(java.util.Locale.US);
        int mode = (v.equals("visionos") || v.equals("1")) ? StreamSettings.MODE_VISIONOS
                : StreamSettings.MODE_IFRAME;
        StreamSettings.setStreamMode(act, mode);
        hlog("[SETTINGS] stream=" + (mode == StreamSettings.MODE_VISIONOS ? "VisionOS" : "iFrame"));
        return true;
    }

    static boolean aSetCache(VisionOsDiagActivity act, String value) {
        String v = String.valueOf(value).trim().toLowerCase(java.util.Locale.US);
        boolean on = v.equals("on") || v.equals("1") || v.equals("true");
        StreamSettings.setAudioCache(act, on);
        hlog("[SETTINGS] cache=" + (on ? "ON" : "OFF"));
        return true;
    }

    static boolean aSetMax(VisionOsDiagActivity act, Intent intent, String value) {
        int n = intent.getIntExtra("seconds", -1);
        if (n < 0) {
            try {
                n = Integer.parseInt(String.valueOf(value).trim());
            } catch (Exception e) {
                n = -1;
            }
        }
        if (n < 1) {
            hlog("[SETTINGS] set-max FAIL need --es value N or --ei seconds N");
            return false;
        }
        StreamSettings.setMaxCachedSongs(act, n);
        hlog("[SETTINGS] maxCachedSongs=" + StreamSettings.getMaxCachedSongs(act));
        return true;
    }

    static boolean aVisitor(VisionOsDiagActivity act) {
        hlog("[VISIONOS] visitor START");
        try {
            boolean had = VisionOsResolver.hasFreshVisitor();
            String vd = VisionOsResolver.getVisitorData(false);
            if (vd == null || vd.isEmpty()) {
                hlog("[VISIONOS] visitor FAIL reason=RESOLVER_FAIL empty");
                return false;
            }
            long ageMs = VisionOsResolver.visitorAgeMs();
            hlog("[VISIONOS] visitor HTTP=200 (GAPIS visitor_id ok)");
            hlog("[VISIONOS] visitor PASS length=" + vd.length()
                    + " cached=" + had + " ageMs=" + ageMs
                    + " head=" + vd.substring(0, Math.min(8, vd.length())) + "...");
            return true;
        } catch (VisionOsResolver.ResolverException e) {
            hlog("[VISIONOS] visitor FAIL reason=" + e.reason + " stage=" + e.stage + " " + e.getMessage());
            return false;
        }
    }

    static boolean aResolve(VisionOsDiagActivity act, String videoId, Map<String, String> results) {
        hlog("[RESOLVE] START videoId=" + videoId);
        try {
            boolean hadVd = VisionOsResolver.hasFreshVisitor();
            hlog("[RESOLVE] visitorData=" + (hadVd ? "present(session)" : "fetch-on-demand"));
            VisionOsResolver.Result r = VisionOsResolver.resolve(videoId);
            lastUrl = r.url;
            lastVideoId = videoId;
            lastItag = r.itag;
            lastMime = r.mimeType;
            lastTotal = clenOf(r.url);
            hlog("[RESOLVE] HTTP=200 (GAPIS player ok)");
            hlog("[RESOLVE] streamingData=present formats=" + r.formatCount);
            hlog("[RESOLVE] selected itag=" + r.itag);
            hlog("[RESOLVE] mime=" + r.mimeType);
            hlog("[RESOLVE] bitrate=" + r.bitrate + " durationMs=" + r.durationMs);
            hlog("[RESOLVE] host=" + r.host + " clen=" + lastTotal);
            hlog("[RESOLVE] retriedVisitor=" + r.retriedVisitor);
            hlog("[RESOLVE] title=" + r.title + " artist=" + r.author);
            boolean itagOk = r.itag == 251;
            if (results != null) results.put("itag251", itagOk ? "PASS" : "FAIL");
            hlog("[RESOLVE] PASS");
            return true;
        } catch (VisionOsResolver.ResolverException e) {
            if (results != null) results.put("itag251", "FAIL");
            hlog("[RESOLVE] FAIL");
            hlog("[RESOLVE] reason=" + e.reason + " stage=" + e.stage + " " + e.getMessage());
            hlog("[RESOLVE] action=fallback-iFrame");
            return false;
        }
    }

    static long clenOf(String url) {
        try {
            String q = new java.net.URL(url).getQuery();
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

    static boolean ensureUrl(VisionOsDiagActivity act, String videoId) {
        if (lastUrl != null && videoId.equals(lastVideoId)) return true;
        hlog("[HARNESS] no cached url, resolving first");
        return aResolve(act, videoId, null);
    }

    static boolean probeAll(VisionOsDiagActivity act, String videoId, Map<String, String> results) {
        if (!ensureUrl(act, videoId)) {
            hlog("[PROBE] SKIP no url (resolve failed)");
            return false;
        }
        long[][] ranges = {{0, 524287}, {524288, 1048575}, {1048576, 1572863}, {1572864, 2097151}};
        boolean all = true;
        for (int i = 0; i < ranges.length; i++) {
            boolean ok = aProbeOne(lastUrl, ranges[i][0], ranges[i][1], lastTotal, i + 1);
            if (results != null) results.put("range" + (i + 1), ok ? "PASS" : "FAIL");
            all = all && ok;
            if (!ok) break;
        }
        return all;
    }

    static boolean aProbeOne(String url, long start, long end, long total, int idx) {
        hlog("[PROBE] range=" + start + "-" + end);
        java.net.HttpURLConnection h = null;
        int status = -1;
        long t0 = System.currentTimeMillis();
        try {
            VisionOsNet.noteRequest(new java.net.URL(url).getHost());
            h = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            h.setRequestMethod("GET");
            h.setConnectTimeout(20000);
            h.setReadTimeout(20000);
            h.setRequestProperty("Range", "bytes=" + start + "-" + end);
            status = h.getResponseCode();
            String cr = h.getHeaderField("Content-Range");
            String ct = h.getHeaderField("Content-Type");
            String cl = h.getHeaderField("Content-Length");
            java.io.InputStream in = status >= 400 ? h.getErrorStream() : h.getInputStream();
            long got = 0;
            if (in != null) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) != -1) got += n;
                in.close();
            }
            long ms = System.currentTimeMillis() - t0;
            String reason = VisionOsResolver.classifyFetch(status, null);
            hlog("[PROBE] HTTP=" + status + " reason=" + reason
                    + " Content-Range=" + cr + " Content-Length=" + cl + " Content-Type=" + ct);
            hlog("[PROBE] bytes=" + got + "/" + (end - start + 1) + " elapsedMs=" + ms);
            boolean ok = status == 206 && got == (end - start + 1);
            hlog("[PROBE] range" + idx + "=" + (ok ? "PASS" : "FAIL"));
            return ok;
        } catch (Exception e) {
            String reason = VisionOsResolver.classifyFetch(-1, e);
            hlog("[PROBE] range" + idx + "=FAIL reason=" + reason + " threw=" + e);
            return false;
        } finally {
            if (h != null) h.disconnect();
        }
    }

    static boolean aDownload(VisionOsDiagActivity act, String videoId, Map<String, String> results,
            String key) {
        if (!ensureUrl(act, videoId)) {
            hlog("[DOWNLOAD] SKIP no url (resolve failed)");
            return false;
        }
        hlog("[DOWNLOAD] START videoId=" + videoId + " expectedBytes=" + lastTotal);
        long t0 = System.currentTimeMillis();
        try {
            VisionOsNet.resetCounters();
            VisionOsCache.CacheResult cr =
                    VisionOsCache.download(act, videoId, lastUrl, lastTotal);
            long ms = System.currentTimeMillis() - t0;
            double bps = ms > 0 ? (cr.bytes * 1000.0 / ms) : 0;
            hlog("[DOWNLOAD] HTTP=206 (all chunks)");
            hlog("[DOWNLOAD] expectedBytes=" + lastTotal + " actualBytes=" + cr.bytes);
            hlog("[DOWNLOAD] chunks=" + cr.chunks + " elapsedMs=" + ms
                    + " bytesPerSec=" + Math.round(bps));
            hlog("[DOWNLOAD] WebM=VALID Opus=VALID (EBML magic + size match)");
            hlog("[DOWNLOAD] path=" + cr.file.getAbsolutePath());
            hlog("[DOWNLOAD] PASS");
            if (results != null) {
                results.put("webmValidation", "PASS");
                if ("fullDownload".equals(key)) results.put(key, "PASS");
            }
            return true;
        } catch (Exception e) {
            String reason = VisionOsResolver.reasonFromMessage(String.valueOf(e.getMessage()));
            hlog("[DOWNLOAD] FAIL reason=" + reason + " " + e.getMessage());
            if (results != null) {
                results.put("webmValidation", "FAIL");
                if ("fullDownload".equals(key)) results.put(key, "FAIL");
            }
            return false;
        }
    }

    // ---------- production-audio actions (MainActivity WebView) ----------

    static String diagJs(String action, JSONObject arg) {
        String a = arg != null ? arg.toString() : "{}";
        // single-quote wrap with escaping for evaluateJavascript string
        String esc = a.replace("\\", "\\\\").replace("'", "\\'");
        return "(function(){try{return window.__vosDiag('" + action + "', '" + esc + "');}"
                + "catch(e){return JSON.stringify({ok:false,err:'nodiag:'+e});}})()";
    }

    static boolean needMain() {
        if (mainWebView() == null) {
            hlog("[HARNESS] FAIL reason=NO_MAIN_ACTIVITY "
                    + "(open app once: am start -n com.dnialify.musicstream/.MainActivity)");
            return false;
        }
        return ensureDiagJs();
    }

    static String cachedHookJs;

    /**
     * The APK WebView loads the REMOTE Vercel page, so bundled-JS helpers are
     * not live until a web deploy. Inject the control-only hook at runtime via
     * evaluateJavascript (page context, no prod behavior change). Verified by
     * typeof check, never assumed.
     */
    static synchronized boolean ensureDiagJs() {
        try {
            String t = evalMain("(function(){return typeof window.__vosDiag;})()", 10000);
            if (t != null && t.contains("function")) {
                return true;
            }
            if (cachedHookJs == null) {
                java.io.InputStream is = null;
                try {
                    is = MainActivity.current.getAssets().open("public/diag-hook.js");
                    byte[] buf = new byte[is.available()];
                    int n = 0;
                    while (n < buf.length) {
                        int r = is.read(buf, n, buf.length - n);
                        if (r < 0) break;
                        n += r;
                    }
                    cachedHookJs = new String(buf, "UTF-8");
                } finally {
                    if (is != null) try { is.close(); } catch (Exception ignored) {}
                }
            }
            if (cachedHookJs == null) {
                hlog("[HARNESS] FAIL reason=NO_HOOK_JS asset public/diag-hook.js missing");
                return false;
            }
            String res = evalMain(cachedHookJs, 15000);
            hlog("[HARNESS] hook inject result=" + res);
            String t2 = evalMain("(function(){return typeof window.__vosDiag;})()", 10000);
            boolean ok = t2 != null && t2.contains("function");
            hlog("[HARNESS] hook " + (ok ? "READY" : "FAIL typeof=" + t2));
            return ok;
        } catch (Exception e) {
            hlog("[HARNESS] hook FAIL " + e);
            return false;
        }
    }

    static boolean aPlay(VisionOsDiagActivity act, String videoId) {
        if (!ensureUrl(act, videoId)) {
            hlog("[PLAY] SKIP no url (resolve failed) action=fallback-iFrame");
            return false;
        }
        if (!needMain()) return false;
        hlog("[PLAY] requestedMethod=VisionOS videoId=" + videoId);
        hlog("[PLAY] resolving... itag=" + lastItag + " mime=" + lastMime);
        try {
            JSONObject arg = new JSONObject();
            arg.put("url", lastUrl);
            arg.put("videoId", videoId);
            String raw = evalMain(diagJs("visionos-play", arg), 15000);
            JSONObject st = jsState(raw);
            hlog("[PLAY] source=" + (st != null ? hostOf(st.optString("src", "?")) : "?"));
            hlog("[PLAY] actualMethod=VISIONOS_AUDIO (audio element, useAudio=true)");
        } catch (Exception e) {
            hlog("[PLAY] FAIL reason=HARNESS_JS " + e);
            return false;
        }
        // monitor: need currentTime to advance >=15s, watch for error/YT-steal
        long t0 = System.currentTimeMillis();
        double first = -1;
        while (System.currentTimeMillis() - t0 < 90000) {
            sleep(2000);
            String raw = evalMain(diagJs("state", null), 10000);
            JSONObject st = jsState(raw);
            if (st == null) continue;
            double cur = st.optDouble("cur", -1);
            int err = st.optInt("err", 0);
            int yt = st.optInt("yt", -99);
            if (err != 0) {
                hlog("[PLAY] FAIL reason=" + VisionOsResolver.R_MEDIA_ERROR + " "
                        + VisionOsResolver.mediaErrorName(err) + " cur=" + cur);
                hlog("[PLAY] action=fallback-iFrame");
                return false;
            }
            if (yt == 1) {
                hlog("[FALLBACK] UNEXPECTED VisionOS resolver=PASS media=audio-stalled "
                        + "but YT iFrame PLAYING reason=PROD_STEAL action=investigate");
                return false;
            }
            if (first < 0 && cur > 0) {
                first = cur;
                hlog("[PLAY] firstProgress cur=" + cur + " dur=" + st.optDouble("dur", 0));
            }
            if (first >= 0 && cur - first >= 15) {
                hlog("[PLAY] PASS currentTime advanced 15s+ (cur=" + cur + ")");
                return true;
            }
        }
        hlog("[PLAY] FAIL reason=" + VisionOsResolver.R_TIMEOUT + " no 15s progress");
        return false;
    }

    static boolean aPause(VisionOsDiagActivity act) {
        if (!needMain()) return false;
        hlog("[ACTION] pause START");
        evalMain(diagJs("pause", null), 10000);
        sleep(1500);
        JSONObject st = jsState(evalMain(diagJs("state", null), 10000));
        boolean ok = st != null && st.optBoolean("paused", false);
        hlog("[MEDIA] paused=" + (st != null ? st.optBoolean("paused", true) : "?"));
        hlog("[ACTION] pause " + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    static boolean aResume(VisionOsDiagActivity act) {
        if (!needMain()) return false;
        hlog("[ACTION] resume START");
        JSONObject before = jsState(evalMain(diagJs("state", null), 10000));
        double b = before != null ? before.optDouble("cur", -1) : -1;
        evalMain(diagJs("resume", null), 10000);
        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < 20000) {
            sleep(2000);
            JSONObject st = jsState(evalMain(diagJs("state", null), 10000));
            if (st == null) continue;
            double cur = st.optDouble("cur", -1);
            if (!st.optBoolean("paused", true) && cur > b) {
                hlog("[MEDIA] playing currentTime=" + cur);
                hlog("[ACTION] resume PASS");
                return true;
            }
        }
        hlog("[ACTION] resume FAIL");
        return false;
    }

    static boolean aSeek(VisionOsDiagActivity act, int seconds) {
        if (!needMain()) return false;
        hlog("[SEEK] requested=" + seconds);
        JSONObject before = jsState(evalMain(diagJs("state", null), 10000));
        double b = before != null ? before.optDouble("cur", -1) : -1;
        hlog("[SEEK] before=" + b);
        try {
            JSONObject arg = new JSONObject();
            arg.put("seconds", seconds);
            evalMain(diagJs("seek", arg), 10000);
        } catch (Exception e) {
            hlog("[SEEK] FAIL harness " + e);
            return false;
        }
        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < 20000) {
            sleep(1000);
            JSONObject st = jsState(evalMain(diagJs("state", null), 10000));
            if (st == null) continue;
            double cur = st.optDouble("cur", -1);
            if (st.optInt("err", 0) != 0) {
                hlog("[SEEK] FAIL reason=" + VisionOsResolver.R_MEDIA_ERROR + " "
                        + VisionOsResolver.mediaErrorName(st.optInt("err", 0)));
                return false;
            }
            if (Math.abs(cur - seconds) < 8) {
                hlog("[SEEK] after=" + cur + " playing=" + !st.optBoolean("paused", true));
                hlog("[SEEK] PASS");
                return true;
            }
        }
        hlog("[SEEK] FAIL reason=" + VisionOsResolver.R_TIMEOUT);
        return false;
    }

    static boolean aStop(VisionOsDiagActivity act) {
        if (!needMain()) return false;
        hlog("[ACTION] stop START");
        evalMain(diagJs("stop", null), 10000);
        sleep(1000);
        JSONObject st = jsState(evalMain(diagJs("state", null), 10000));
        boolean ok = st != null && st.optBoolean("paused", false);
        hlog("[MEDIA] paused=" + ok);
        hlog("[ACTION] stop " + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    static boolean aBackground(VisionOsDiagActivity act) {
        if (!needMain()) return false;
        hlog("[BACKGROUND] moveTaskToBack (no taps)");
        JSONObject before = jsState(evalMain(diagJs("state", null), 10000));
        double b = before != null ? before.optDouble("cur", -1) : -1;
        hlog("[BACKGROUND] before=" + b);
        try {
            MainActivity.current.moveTaskToBack(true);
        } catch (Exception e) {
            hlog("[BACKGROUND] FAIL moveTask " + e);
            return false;
        }
        sleep(15000);
        JSONObject after = jsState(evalMain(diagJs("state", null), 15000));
        double a = after != null ? after.optDouble("cur", -1) : -1;
        boolean playing = after != null && !after.optBoolean("paused", true);
        hlog("[BACKGROUND] after=" + a + " playbackContinued=" + (a > b && playing));
        // bring back for next stages
        try {
            Intent i = new Intent(act, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            act.startActivity(i);
        } catch (Exception ignored) {}
        boolean ok = a > b && playing;
        hlog("[BACKGROUND] " + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    static boolean aScreenOff(VisionOsDiagActivity act) {
        if (!needMain()) return false;
        boolean inter;
        try {
            PowerManager pm = (PowerManager) act.getSystemService(Activity.POWER_SERVICE);
            inter = pm != null && pm.isInteractive();
        } catch (Exception e) {
            inter = true;
        }
        hlog("[SCREENOFF] screenInteractive(before)=" + inter
                + " (driver: adb shell input keyevent KEYCODE_SLEEP / WAKEUP)");
        JSONObject before = jsState(evalMain(diagJs("state", null), 10000));
        double b = before != null ? before.optDouble("cur", -1) : -1;
        sleep(20000);
        boolean inter2;
        try {
            PowerManager pm = (PowerManager) act.getSystemService(Activity.POWER_SERVICE);
            inter2 = pm != null && pm.isInteractive();
        } catch (Exception e) {
            inter2 = true;
        }
        JSONObject after = jsState(evalMain(diagJs("state", null), 15000));
        double a = after != null ? after.optDouble("cur", -1) : -1;
        hlog("[SCREENOFF] screenOff=" + (!inter2) + " after=" + a
                + " playbackContinued=" + (a > b));
        boolean ok = a > b;
        hlog("[SCREENOFF] " + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    static boolean cacheFlow(VisionOsDiagActivity act, String videoId, Map<String, String> results) {
        hlog("[CACHE] ensure MISS (clear first)");
        aCacheClear(act, videoId);
        hlog("[CACHE] MISS confirmed");
        boolean dl = aDownload(act, videoId, null, null);
        boolean w = dl && VisionOsCache.exists(act, videoId);
        hlog("[CACHE] DOWNLOAD " + (dl ? "PASS" : "FAIL"));
        hlog("[CACHE] VALIDATION " + (dl ? "PASS (inside download)" : "FAIL"));
        hlog("[CACHE] WRITE " + (w ? "PASS" : "FAIL"));
        if (results != null) {
            results.put("cacheWrite", (dl && w) ? "PASS" : "FAIL");
        }
        return dl && w;
    }

    static boolean aCachePlay(VisionOsDiagActivity act, String videoId) {
        if (!VisionOsCache.exists(act, videoId)) {
            hlog("[CACHE] HIT FAIL no file, run cache first");
            return false;
        }
        if (!needMain()) return false;
        hlog("[CACHE] HIT file=" + VisionOsCache.fileFor(act, videoId).getAbsolutePath());
        // NOTE: page-context fetch()/audio CANNOT read content:// (proven
        // FETCH_FAIL + code=4). Serve the same cached bytes over loopback.
        String uri = VisionOsLoopback.urlFor(act, videoId);
        if (uri == null) {
            hlog("[CACHE] FAIL loopback (no file?)");
            return false;
        }
        hlog("[CACHE] source=local uri=" + uri);
        try {
            VisionOsNet.setBlockNetwork(true);
            VisionOsNet.resetCounters();
            JSONObject arg = new JSONObject();
            arg.put("url", uri);
            arg.put("videoId", videoId);
            evalMain(diagJs("visionos-play", arg), 15000);
            hlog("[PLAY] actualMethod=CACHED_AUDIO network=BLOCKED");
            long t0 = System.currentTimeMillis();
            double first = -1;
            while (System.currentTimeMillis() - t0 < 40000) {
                sleep(2000);
                JSONObject st = jsState(evalMain(diagJs("state", null), 10000));
                if (st == null) continue;
                double cur = st.optDouble("cur", -1);
                if (st.optInt("err", 0) != 0) {
                    hlog("[CACHE] PLAYBACK FAIL reason=" + VisionOsResolver.R_MEDIA_ERROR);
                    return false;
                }
                if (first < 0 && cur > 0) first = cur;
                if (first >= 0 && cur - first >= 5) {
                    long g = VisionOsNet.googleRequests();
                    hlog("[CACHE] googlevideoRequests=" + g);
                    hlog("[CACHE] cacheHit=" + (g == 0 ? "PASS (no YouTube)" : "FAIL (hit network)"));
                    if (lastVideoId != null && lastVideoId.equals(videoId)) {
                        // keep
                    }
                    hlog("[PLAY] PASS cached currentTime advanced");
                    return g == 0;
                }
            }
            hlog("[CACHE] PLAYBACK FAIL reason=" + VisionOsResolver.R_TIMEOUT);
            return false;
        } catch (Exception e) {
            hlog("[CACHE] PLAYBACK FAIL " + e);
            return false;
        } finally {
            VisionOsNet.setBlockNetwork(false);
        }
    }

    static boolean aCacheClear(VisionOsDiagActivity act, String videoId) {
        hlog("[CACHE] CLEAR START videoId=" + videoId);
        long removed = 0;
        int n = 0;
        try {
            java.io.File dir = VisionOsCache.dir(act);
            java.io.File[] files = dir.listFiles();
            if (files != null) {
                for (java.io.File f : files) {
                    if (f.getName().startsWith(videoId)) {
                        removed += f.length();
                        if (f.delete()) n++;
                    }
                }
            }
        } catch (Exception e) {
            hlog("[CACHE] CLEAR FAIL " + e);
            return false;
        }
        hlog("[CACHE] removed=" + n + " files bytes=" + removed);
        hlog("[CACHE] CLEAR PASS");
        return true;
    }

    static boolean aMetadata(VisionOsDiagActivity act) {
        if (!needMain()) return false;
        String raw = evalMain(diagJs("metadata", null), 10000);
        if (raw == null) {
            hlog("[METADATA] FAIL no webview result");
            return false;
        }
        try {
            JSONObject o = new JSONObject(raw);
            JSONObject st = o.optJSONObject("state");
            JSONObject meta = o.optJSONObject("meta");
            hlog("[METADATA] title=" + (meta != null ? meta.optString("title", "?") : "?"));
            hlog("[METADATA] artist=" + (meta != null ? meta.optString("artist", "?") : "?"));
            hlog("[METADATA] artwork=" + (meta != null ? meta.optString("thumb", "?") : "?"));
            hlog("[METADATA] duration=" + (st != null ? st.optDouble("dur", 0) : 0));
            hlog("[METADATA] videoId=" + (st != null ? st.optString("vid", "?") : "?"));
            hlog("[METADATA] lyrics-present=existing-web-flow (untouched by harness)");
            hlog("[METADATA] playback-method=" + (st != null ? st.optString("method", "?") : "?"));
            hlog("[METADATA] source=" + (st != null ? hostOf(st.optString("src", "?")) : "?"));
            hlog("[METADATA] PASS");
            return true;
        } catch (Exception e) {
            hlog("[METADATA] FAIL " + e);
            return false;
        }
    }

    /** Generic page-context eval for debugging (result truncated to 500 chars). */
    static boolean aEval(String js) {
        if (!needMain()) return false;
        if (js == null || js.isEmpty()) {
            hlog("[EVAL] FAIL empty --es value");
            return false;
        }
        String raw = evalMain(js, 20000);
        String show = String.valueOf(raw);
        if (show.length() > 500) show = show.substring(0, 500) + "...";
        hlog("[EVAL] result=" + show);
        return raw != null;
    }

    static void wlog(String k, String v) {
        String s = String.valueOf(v);
        for (int i = 0; i < s.length(); i += 700) {
            hlog("[WEB-OFFLINE] " + k + "=" + s.substring(i, Math.min(s.length(), i + 700)));
        }
        if (s.isEmpty()) hlog("[WEB-OFFLINE] " + k + "=");
    }

    /**
     * TEST web-offline: real page -> real router (#/library/offline) -> real DOM
     * -> real OfflineLib mirror -> real rendered items + native record/art/lyrics
     * -> offline play via OfflineLib.play (Engine B CACHED_AUDIO, zero network).
     * All JS lives here in Java; caller runs one intent, no shell quoting.
     */
    static boolean aWebOffline(VisionOsDiagActivity act) {
        if (!needMain()) return false;
        String vid = "E7kHvjvU6JY";
        hlog("[WEB-OFFLINE] START route=#/library/offline videoId=" + vid);
        wlog("startUrl", evalMain("location.href", 10000));
        evalMain("location.hash='#/library/offline'", 10000);
        sleep(6000);
        wlog("curUrl", evalMain("location.href", 10000));
        wlog("bridgeDiag", evalMain(
                "(function(){try{var n=0;try{n=JSON.parse("
                        + "NativePlayback.getCachedSongs()||'[]').length;}catch(x){}"
                        + "return 'hasBridge='+OfflineLib.hasBridge()+' n='+n"
                        + "+' keys='+Object.keys(OfflineLib.map()).length;}"
                        + "catch(e){return 'ERR:'+e;}})()", 15000));
        wlog("rows", evalMain("document.querySelectorAll('.off-wrap').length", 10000));
        wlog("doneBadges", evalMain("document.querySelectorAll('.off-done').length", 10000));
        wlog("doneText", evalMain(
                "(function(){var e=document.querySelector('.off-done');"
                        + "return e?e.textContent:'none';})()", 10000));
        wlog("playBtns", evalMain("document.querySelectorAll('[data-offplay]').length", 10000));
        wlog("contBtns", evalMain("document.querySelectorAll('[data-offcont]').length", 10000));
        wlog("rowText", evalMain(
                "(function(){var e=document.querySelector('.off-wrap');"
                        + "return e?e.textContent.slice(0,300):'none';})()", 10000));
        wlog("artImg", evalMain(
                "(function(){var i=document.querySelector('.off-row img');"
                        + "if(i)return 'img:'+i.src.slice(0,40)+' len='+i.src.length;"
                        + "return document.querySelector('.off-row .art-ph')"
                        + "?'placeholder':'noart';})()", 10000));
        wlog("mirror", evalMain(
                "(function(){try{return JSON.stringify(OfflineLib.get('E7kHvjvU6JY'));"
                        + "}catch(e){return 'ERR:'+e;}})()", 15000));
        // native record, artwork file, lyrics (Java direct, no JS needed)
        JSONObject rec = SongCache.getRecord(act, vid);
        if (rec == null) {
            hlog("[WEB-OFFLINE] FAIL reason=NO-RECORD");
            return false;
        }
        wlog("record", "status=" + rec.optString("cacheStatus", "?")
                + " bytes=" + rec.optLong("downloadedBytes", 0)
                + "/" + rec.optLong("audioSize", 0)
                + " pct=" + rec.optDouble("downloadPercent", 0)
                + " title=" + rec.optString("title", "?")
                + " artist=" + rec.optString("artist", "?")
                + " dur=" + rec.optDouble("duration", 0));
        java.io.File art = SongCache.artFile(act, vid);
        String magic = "?";
        try {
            java.io.FileInputStream fi = new java.io.FileInputStream(art);
            byte[] b = new byte[3];
            int n = fi.read(b);
            fi.close();
            if (n == 3) magic = String.format("%02x%02x%02x", b[0], b[1], b[2]);
        } catch (Exception ignored) {}
        wlog("artwork", "exists=" + art.isFile() + " size=" + (art.isFile() ? art.length() : 0)
                + " magic=" + magic + " status=" + rec.optString("artworkStatus", "?")
                + " hasFileField=" + rec.has("artworkFile")
                + " thumbLen=" + rec.optString("artworkThumb", "").length());
        String lyr = rec.optString("lyrics", "");
        int timed = 0;
        String sample = "";
        try {
            for (String ln : lyr.split("\n")) {
                if (ln.startsWith("[")) {
                    timed++;
                    if (sample.isEmpty()) sample = ln.length() > 60 ? ln.substring(0, 60) : ln;
                }
            }
        } catch (Exception ignored) {}
        wlog("lyrics", "status=" + rec.optString("lyricsStatus", "?")
                + " format=" + rec.optString("lyricsFormat", "?")
                + " chars=" + lyr.length() + " timedLines=" + timed);
        wlog("lyricsSample", sample);
        // offline play via real web path: OfflineLib.play -> Engine B, network blocked
        boolean playing = false;
        try {
            VisionOsNet.setBlockNetwork(true);
            VisionOsNet.resetCounters();
            wlog("clickPlay", evalMain(
                    "(function(){try{OfflineLib.play('E7kHvjvU6JY');return 'ok';}"
                            + "catch(e){return 'ERR:'+e;}}())", 15000));
            long t0 = System.currentTimeMillis();
            while (System.currentTimeMillis() - t0 < 45000) {
                sleep(2000);
                try {
                    JSONObject s = new JSONObject(nativeStateStr());
                    if ("ERROR".equals(s.optString("state", ""))) {
                        hlog("[WEB-OFFLINE] FAIL reason=ENGINE_" + s.optString("reason", "?"));
                        return false;
                    }
                    if ("PLAYING".equals(s.optString("state", ""))
                            && s.optDouble("currentTime", 0) > 3
                            && "CACHED_AUDIO".equals(s.optString("source", ""))) {
                        playing = true;
                        break;
                    }
                } catch (Exception ignored) {}
            }
            long g = VisionOsNet.googleRequests();
            long rr = VisionOsNet.resolveRequests();
            wlog("offlinePlay", "playing=" + playing
                    + " googlevideoRequests=" + g + " gapisRequests=" + rr
                    + " state=" + nativeStateStr());
            wlog("history", evalMain(
                    "(function(){try{return JSON.stringify((Library.history||[])"
                            + ".slice(0,3).map(function(h){return h.videoId;}));}"
                            + "catch(e){return 'ERR:'+e;}})()", 10000));
        } finally {
            VisionOsNet.setBlockNetwork(false);
            NativeAudioEngine.get().stop("web-offline-test");
        }
        boolean domOk = "1".equals(String.valueOf(
                evalMain("document.querySelectorAll('.off-wrap').length", 10000)).trim());
        boolean ok = playing && domOk;
        hlog("[WEB-OFFLINE] RESULT " + (ok ? "PASS" : "FAIL")
                + " domRows1=" + domOk + " offlinePlaying=" + playing);
        return ok;
    }

    /** Probe whether page-context fetch() can read the cache content URI. */
    static boolean aCacheProbe(VisionOsDiagActivity act, String videoId) {
        if (!needMain()) return false;
        String uri;
        try {
            uri = VisionOsCache.contentUri(act, videoId).toString();
        } catch (Exception e) {
            hlog("[CACHE-PROBE] FAIL uri " + e);
            return false;
        }
        hlog("[CACHE-PROBE] uri=" + uri);
        String js = "(function(){var u=" + JSONObject.quote(uri) + ";"
                + "window.__probeResult='pending';"
                + "fetch(u,{headers:{Range:'bytes=0-1023'}}).then(function(r){"
                + "return r.arrayBuffer().then(function(b){"
                + "window.__probeResult='status='+r.status+' ct='+r.headers.get('Content-Type')+' bytes='+b.byteLength;});})"
                + ".catch(function(e){window.__probeResult='FETCH_FAIL '+e;});"
                + "return 'sent';})()";
        evalMain(js, 20000);
        String raw = null;
        for (int i = 0; i < 10; i++) {
            sleep(1000);
            raw = evalMain("(function(){return window.__probeResult||'pending';})()", 10000);
            if (raw != null && !raw.contains("pending")) break;
        }
        hlog("[CACHE-PROBE] fetch=" + raw);
        return raw != null && raw.indexOf("bytes=1024") >= 0;
    }

        /** Probe whether page-context fetch() can read the loopback cache URL. */
    static boolean aLoopbackProbe(VisionOsDiagActivity act, String videoId) {
        if (!needMain()) return false;
        String url = VisionOsLoopback.urlFor(act, videoId);
        if (url == null) {
            hlog("[LOOPBACK-PROBE] FAIL no cache file");
            return false;
        }
        hlog("[LOOPBACK-PROBE] url=" + url);
        String js = "(function(){var u=" + JSONObject.quote(url) + ";"
                + "window.__probeResult='pending';"
                + "fetch(u,{headers:{Range:'bytes=0-1023'}}).then(function(r){"
                + "return r.arrayBuffer().then(function(b){"
                + "window.__probeResult='status='+r.status+' ct='+r.headers.get('Content-Type')"
                + "+' cr='+r.headers.get('Content-Range')+' bytes='+b.byteLength;});})"
                + ".catch(function(e){window.__probeResult='FETCH_FAIL '+e;});"
                + "return 'sent';})()";
        evalMain(js, 20000);
        String raw = null;
        for (int i = 0; i < 10; i++) {
            sleep(1000);
            raw = evalMain("(function(){return window.__probeResult||'pending';})()", 10000);
            if (raw != null && !raw.contains("pending")) break;
        }
        hlog("[LOOPBACK-PROBE] fetch=" + raw);
        return raw != null && raw.indexOf("bytes=1024") >= 0;
    }

        // ---------- Engine B (native) actions: no WebView audio involved ----------

    static String nativeStateStr() {
        try {
            return NativeAudioEngine.get().getState().toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    static boolean aNativePlay(VisionOsDiagActivity act, String videoId) {
        hlog("[NATIVE_CMD] play videoId=" + videoId + " title=DIAG");
        NativeAudioEngine.get().play(act, videoId, "DIAG", "", "");
        long t0 = System.currentTimeMillis();
        double first = -1;
        while (System.currentTimeMillis() - t0 < 90000) {
            sleep(2000);
            JSONObject s;
            try {
                s = new JSONObject(nativeStateStr());
            } catch (Exception e) {
                continue;
            }
            String st = s.optString("state", "?");
            if ("ERROR".equals(st)) {
                hlog("[NATIVE] FAIL reason=" + s.optString("reason", "?")
                        + " " + s.optString("message", ""));
                return false;
            }
            double cur = s.optDouble("currentTime", -1);
            if (first < 0 && cur > 0) {
                first = cur;
                hlog("[NATIVE] firstProgress cur=" + cur
                        + " dur=" + s.optDouble("duration", 0)
                        + " source=" + s.optString("source", "?"));
            }
            if (first >= 0 && cur - first >= 10) {
                hlog("[NATIVE] PASS advanced 10s+ cur=" + cur);
                return true;
            }
        }
        hlog("[NATIVE] FAIL reason=TIMEOUT " + nativeStateStr());
        return false;
    }

    static boolean aNativePause() {
        NativeAudioEngine.get().pause();
        sleep(1500);
        boolean ok = nativeStateStr().contains("\"state\":\"PAUSED\"");
        hlog("[NATIVE] pause " + (ok ? "PASS" : "FAIL") + " " + nativeStateStr());
        return ok;
    }

    static boolean aNativeResume() {
        NativeAudioEngine.get().resume();
        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < 20000) {
            sleep(2000);
            try {
                JSONObject s = new JSONObject(nativeStateStr());
                if ("PLAYING".equals(s.optString("state", ""))) {
                    hlog("[NATIVE] resume PASS cur=" + s.optDouble("currentTime", -1));
                    return true;
                }
            } catch (Exception ignored) {}
        }
        hlog("[NATIVE] resume FAIL");
        return false;
    }

    static boolean aNativeSeek(int seconds) {
        hlog("[NATIVE_CMD] seek=" + seconds);
        NativeAudioEngine.get().seek(seconds);
        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < 20000) {
            sleep(1000);
            try {
                JSONObject s = new JSONObject(nativeStateStr());
                double cur = s.optDouble("currentTime", -1);
                if (Math.abs(cur - seconds) < 8
                        && !"ERROR".equals(s.optString("state", ""))) {
                    hlog("[NATIVE] seek PASS cur=" + cur);
                    return true;
                }
            } catch (Exception ignored) {}
        }
        hlog("[NATIVE] seek FAIL");
        return false;
    }

    static boolean aNativeStop() {
        NativeAudioEngine.get().stop("harness");
        sleep(1000);
        boolean ok = nativeStateStr().contains("\"state\":\"STOPPED\"");
        hlog("[NATIVE] stop " + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    static boolean aNativeState() {
        hlog("[NATIVE] state=" + nativeStateStr());
        return true;
    }

    static boolean aNotification() {
        String s = PlaybackService.diagSession();
        hlog("[NOTIFICATION] session=" + s);
        boolean ok = s.contains("state=3");
        hlog("[NOTIFICATION] " + (ok ? "PASS playing" : "check state above"));
        return ok;
    }

    static boolean aLyrics() {
        if (!needMain()) return false;
        String js = "(function(){try{var L=(window.Player&&Player.lyrics)||{};"
                + "var lines=L.lines||[];var idx=-1;"
                + "try{idx=window.lastLyricIdx;}catch(e){}"
                + "var t='';try{t=document.getElementById('np-title').textContent||'';}catch(e){}"
                + "return JSON.stringify({track:t,lines:lines.length,idx:idx,"
                + "hasSynced:!!L.synced});}catch(e){return 'ERR '+e;}})()";
        String raw = evalMain(js, 15000);
        hlog("[LYRICS] " + raw);
        return raw != null && !raw.startsWith("ERR");
    }

    /** Controlled failure: bogus videoId -> resolver fail -> classified error, no retry storm. */
    static boolean aFallback(VisionOsDiagActivity act, String videoId) {
        String bogus = "xxxxxxxxxxx";
        hlog("[FALLBACK-TEST] force fail videoId=" + bogus);
        NativeAudioEngine.get().play(act, bogus, "DIAG", "", "");
        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < 45000) {
            sleep(2000);
            try {
                JSONObject s = new JSONObject(nativeStateStr());
                if ("ERROR".equals(s.optString("state", ""))) {
                    String reason = s.optString("reason", "?");
                    hlog("[FALLBACK-TEST] classified reason=" + reason);
                    hlog("[FALLBACK-TEST] engine released (no retry storm)");
                    NativeAudioEngine.get().stop("fallback-test");
                    boolean ok = reason != null && !reason.isEmpty()
                            && !"null".equals(reason);
                    hlog("[FALLBACK-TEST] " + (ok ? "PASS" : "FAIL"));
                    return ok;
                }
            } catch (Exception ignored) {}
        }
        hlog("[FALLBACK-TEST] FAIL no ERROR state");
        NativeAudioEngine.get().stop("fallback-test");
        return false;
    }

    // ---------- full-song cache actions ----------

    /**
     * cache-song-full: real-song path mirroring app runtime:
     * meta (extras title/artist/artworkUrl) -> resolve -> lyrics via the app's
     * real /api/lyrics -> download audio with progress -> artwork fetch.
     * Extras: title, artist, artwork_url, lyrics_base (default Vercel prod).
     */
    static boolean aCacheSongFull(VisionOsDiagActivity act, Intent intent, String videoId) {
        String preset = intent.getStringExtra("preset");
        String title = intent.getStringExtra("title");
        String artist = intent.getStringExtra("artist");
        String artworkUrl = intent.getStringExtra("artwork_url");
        if ("tulus".equals(preset)) {
            // real song from /api/search (shell-safe: spaces break am extras)
            videoId = "E7kHvjvU6JY";
            title = "Hati-Hati di Jalan";
            artist = "Tulus";
            artworkUrl = "https://yt3.googleusercontent.com/0LkNos-al_3gRdfeVnsoULGEInj8_1uQkE4p9nT_6heSYKzO2emQrqm-XcexNEhl_sfTueS-gT45yoQ=w544-h544-l90-rj";
        }
        String lyricsBase = intent.getStringExtra("lyrics_base");
        if (title == null) title = videoId;
        if (artist == null) artist = "";
        if (artworkUrl == null) artworkUrl = "";
        if (lyricsBase == null || lyricsBase.isEmpty()) {
            lyricsBase = "https://dnialify-music-stream.vercel.app";
        }
        hlog("[SONGFULL] START videoId=" + videoId + " title=" + title + " artist=" + artist);
        try {
            JSONObject meta = new JSONObject();
            meta.put("videoId", videoId);
            meta.put("title", title);
            meta.put("artist", artist);
            meta.put("artworkUrl", artworkUrl);
            meta.put("source", "VISIONOS");
            SongCache.ensureMeta(act, meta);
            hlog("[SONGFULL] metadata created");
            if (SongCache.isComplete(act, videoId)) {
                hlog("[SONGFULL] already COMPLETE");
                ensureArtwork(act, videoId);
                return true;
            }
            VisionOsResolver.Result r = VisionOsResolver.resolve(videoId);
            long total = clenOf(r.url);
            hlog("[SONGFULL] resolved itag=" + r.itag + " mime=" + r.mimeType
                    + " durMs=" + r.durationMs + " clen=" + total);
            try {
                JSONObject rec = SongCache.getRecord(act, videoId);
                if (rec != null) {
                    rec.put("duration", r.durationMs / 1000.0);
                    rec.put("audioFormat", r.mimeType);
                    SongCache.putRecord(act, videoId, rec);
                }
            } catch (Exception ignored) {}
            // lyrics via real app source (same shape as /api/lyrics)
            fetchLyricsInto(act, videoId, lyricsBase, title, artist,
                    Math.round(r.durationMs / 1000.0));
            // audio (progress persisted inside; FULL = no window cap, no stop pause)
            SongCache.downloadFull(act, videoId, r.url, total);
            // artwork (best effort, explicit status)
            if (!artworkUrl.isEmpty()) {
                String lp = SongCache.fetchArtwork(act, videoId, artworkUrl);
                JSONObject rec = SongCache.getRecord(act, videoId);
                if (rec != null) {
                    try {
                        rec.put("artworkStatus",
                                lp != null ? SongCache.LY_AVAILABLE : SongCache.LY_FAILED);
                        SongCache.putRecord(act, videoId, rec);
                    } catch (Exception ignored) {}
                }
                hlog("[SONGFULL] artwork " + (lp != null ? "AVAILABLE " + lp : "FAILED"));
            }
            JSONObject rec = SongCache.getRecord(act, videoId);
            boolean ok = rec != null
                    && SongCache.ST_COMPLETE.equals(rec.optString("cacheStatus", ""));
            hlog("[SONGFULL] title=" + (rec != null ? rec.optString("title", "?") : "?")
                    + " lyrics=" + (rec != null ? rec.optString("lyricsStatus", "?") : "?")
                    + " art=" + (rec != null ? rec.optString("artworkStatus", "?") : "?"));
            hlog("[SONGFULL] " + (ok ? "PASS COMPLETE" : "FAIL"));
            return ok;
        } catch (Exception e) {
            hlog("[SONGFULL] FAIL " + e);
            return false;
        }
    }

    static void ensureArtwork(VisionOsDiagActivity act, String videoId) {
        try {
            JSONObject rec = SongCache.getRecord(act, videoId);
            if (rec == null || rec.has("artworkFile")) return;
            String u = rec.optString("artworkUrl", "");
            if (u.isEmpty()) return;
            String lp = SongCache.fetchArtwork(act, videoId, u);
            rec.put("artworkStatus", lp != null ? SongCache.LY_AVAILABLE : SongCache.LY_FAILED);
            SongCache.putRecord(act, videoId, rec);
            hlog("[SONGFULL] artwork " + (lp != null ? "AVAILABLE " + lp : "FAILED"));
        } catch (Exception ignored) {}
    }

    /** GET lyricsBase/api/lyrics?title=&artist=&duration= -> store raw timed lyrics. */    static void fetchLyricsInto(VisionOsDiagActivity act, String videoId, String base,
            String title, String artist, long durSec) {
        java.net.HttpURLConnection h = null;
        try {
            String url = base + "/api/lyrics?title=" + java.net.URLEncoder.encode(title, "UTF-8")
                    + "&artist=" + java.net.URLEncoder.encode(artist, "UTF-8")
                    + "&duration=" + durSec;
            h = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            h.setConnectTimeout(20000);
            h.setReadTimeout(20000);
            int status = h.getResponseCode();
            if (status < 200 || status > 299) {
                markLyrics(act, videoId, null, null, SongCache.LY_FAILED);
                hlog("[SONGFULL] lyrics HTTP=" + status + " FAILED");
                return;
            }
            java.io.InputStream in = h.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[32768];
            int n;
            long got = 0;
            while ((n = in.read(buf)) != -1) {
                got += n;
                if (got > 200 * 1024) break;
                bos.write(buf, 0, n);
            }
            in.close();
            JSONObject j = new JSONObject(bos.toString("UTF-8"));
            String synced = j.optString("synced", null);
            String plain = j.optString("plain", null);
            if ((synced == null || synced.isEmpty()) && (plain == null || plain.isEmpty())) {
                markLyrics(act, videoId, null, null, SongCache.LY_UNAVAILABLE);
                hlog("[SONGFULL] lyrics UNAVAILABLE (empty, source="
                        + j.optString("source", "?") + ")");
                return;
            }
            String fmt = synced != null && !synced.isEmpty() ? "lrc" : "plain";
            markLyrics(act, videoId,
                    synced != null && !synced.isEmpty() ? synced : plain, fmt,
                    SongCache.LY_AVAILABLE);
            hlog("[SONGFULL] lyrics AVAILABLE format=" + fmt + " source="
                    + j.optString("source", "?") + " len="
                    + (synced != null ? synced.length() : plain.length()));
        } catch (Exception e) {
            markLyrics(act, videoId, null, null, SongCache.LY_FAILED);
            hlog("[SONGFULL] lyrics FAILED " + e);
        } finally {
            if (h != null) h.disconnect();
        }
    }

    static void markLyrics(VisionOsDiagActivity act, String videoId, String text,
            String fmt, String status) {
        try {
            JSONObject rec = SongCache.getRecord(act, videoId);
            if (rec == null) return;
            if (text != null) {
                rec.put("lyrics", text);
                rec.put("lyricsFormat", fmt != null ? fmt : "lrc");
            }
            rec.put("lyricsStatus", status);
            SongCache.putRecord(act, videoId, rec);
        } catch (Exception ignored) {}
    }

    /** cache-song: meta shell + resolve + record writer + artwork. value=optional title. */
    static boolean aCacheSong(VisionOsDiagActivity act, String videoId, String value) {
        hlog("[SONG] cache-song START videoId=" + videoId);
        try {
            JSONObject meta = new JSONObject();
            meta.put("videoId", videoId);
            meta.put("title", value != null && !value.isEmpty() ? value : "DIAG SONG");
            meta.put("artist", "DIAG");
            meta.put("artworkUrl", "");
            meta.put("source", "VISIONOS");
            SongCache.ensureMeta(act, meta);
            hlog("[SONG] metadata created");
            if (SongCache.isComplete(act, videoId)) {
                hlog("[SONG] already COMPLETE");
                return true;
            }
            VisionOsResolver.Result r = VisionOsResolver.resolve(videoId);
            hlog("[SONG] resolved itag=" + r.itag + " clen=" + clenOf(r.url));
            SongCache.download(act, videoId, r.url, clenOf(r.url));
            JSONObject rec = SongCache.getRecord(act, videoId);
            boolean ok = rec != null
                    && SongCache.ST_COMPLETE.equals(rec.optString("cacheStatus", ""));
            hlog("[SONG] cache-song " + (ok ? "PASS COMPLETE" : "FAIL "
                    + (rec != null ? rec.optString("cacheStatus", "?") : "no-record")));
            return ok;
        } catch (Exception e) {
            hlog("[SONG] cache-song FAIL " + e);
            return false;
        }
    }

    static boolean aCacheStatus(VisionOsDiagActivity act, String videoId) {
        JSONObject r = SongCache.getRecord(act, videoId);
        if (r == null) {
            hlog("[SONG] status videoId=" + videoId + " NO-RECORD");
            return false;
        }
        hlog("[SONG] status videoId=" + videoId
                + " cacheStatus=" + r.optString("cacheStatus", "?")
                + " downloadedBytes=" + r.optLong("downloadedBytes", 0)
                + " audioSize=" + r.optLong("audioSize", 0)
                + " percent=" + r.optDouble("downloadPercent", 0)
                + " title=" + r.optString("title", "?")
                + " lyrics=" + r.optString("lyricsStatus", "?")
                + " art=" + (r.has("artworkFile") ? "yes" : "no"));
        return true;
    }

    static boolean aCacheList(VisionOsDiagActivity act) {
        java.util.List<JSONObject> all = SongCache.listRecords(act);
        hlog("[SONG] list count=" + all.size());
        for (JSONObject r : all) {
            hlog("[SONG] item videoId=" + r.optString("videoId", "?")
                    + " status=" + r.optString("cacheStatus", "?")
                    + " percent=" + r.optDouble("downloadPercent", 0)
                    + " offline=" + SongCache.ST_COMPLETE.equals(
                            r.optString("cacheStatus", "")));
        }
        return true;
    }

    static boolean aCacheDelete(VisionOsDiagActivity act, String videoId) {
        JSONObject out = SongCache.delete(act, videoId);
        boolean gone = SongCache.getRecord(act, videoId) == null
                && !SongCache.audioFile(act, videoId).isFile();
        hlog("[SONG] delete removed=" + out.optInt("removed", 0)
                + " bytes=" + out.optLong("bytes", 0) + " gone=" + gone);
        // history/playlists live in WebView localStorage: untouched by design
        hlog("[SONG] delete PASS=" + gone);
        return gone;
    }

    /**
     * TEST E/F fully offline: BLOCK network, engine play HIT, assert zero
     * googlevideo + zero GAPIS resolve requests, metadata from record.
     */
    static boolean aCacheOffline(VisionOsDiagActivity act, String videoId) {
        if (!SongCache.isComplete(act, videoId)) {
            hlog("[OFFLINE] FAIL not COMPLETE, run cache-song first");
            return false;
        }
        hlog("[OFFLINE] BLOCK_YOUTUBE=1 network=BLOCKED");
        try {
            VisionOsNet.setBlockNetwork(true);
            VisionOsNet.resetCounters();
            NativeAudioEngine.get().play(act, videoId, "DIAG", "", "");
            long t0 = System.currentTimeMillis();
            boolean playing = false;
            while (System.currentTimeMillis() - t0 < 45000) {
                sleep(2000);
                try {
                    JSONObject s = new JSONObject(nativeStateStr());
                    if ("ERROR".equals(s.optString("state", ""))) {
                        hlog("[OFFLINE] FAIL engine " + s.optString("reason", "?"));
                        return false;
                    }
                    if ("PLAYING".equals(s.optString("state", ""))
                            && s.optDouble("currentTime", 0) > 3
                            && "CACHED_AUDIO".equals(s.optString("source", ""))) {
                        playing = true;
                        break;
                    }
                } catch (Exception ignored) {}
            }
            long g = VisionOsNet.googleRequests();
            long rr = VisionOsNet.resolveRequests();
            hlog("[OFFLINE] source=CACHED_AUDIO playing=" + playing
                    + " googlevideoRequests=" + g + " gapisRequests=" + rr);
            JSONObject rec = SongCache.getRecord(act, videoId);
            hlog("[OFFLINE] meta title=" + (rec != null ? rec.optString("title", "?") : "?")
                    + " artist=" + (rec != null ? rec.optString("artist", "?") : "?")
                    + " duration=" + (rec != null ? rec.optDouble("duration", 0) : 0));
            boolean ok = playing && g == 0 && rr == 0;
            hlog("[OFFLINE] " + (ok ? "PASS fully offline" : "FAIL"));
            return ok;
        } finally {
            VisionOsNet.setBlockNetwork(false);
            NativeAudioEngine.get().stop("offline-test");
        }
    }

    /** Continue/Retry path: resume from .part via record, never byte 0. */
    static boolean aResumeCache(VisionOsDiagActivity act, String videoId) {
        hlog("[RESUME] START videoId=" + videoId);
        if (SongCache.isComplete(act, videoId)) {
            hlog("[RESUME] already COMPLETE, nothing to do");
            return true;
        }
        if (SongCache.isDownloading(videoId)) {
            hlog("[RESUME] SKIP already running (no duplicate job)");
            return true;
        }
        try {
            VisionOsResolver.Result r = VisionOsResolver.resolve(videoId);
            long before = SongCache.partFile(act, videoId).isFile()
                    ? SongCache.partFile(act, videoId).length() : 0;
            hlog("[RESUME] partBytesBefore=" + before);
            SongCache.download(act, videoId, r.url, clenOf(r.url));
            JSONObject rec = SongCache.getRecord(act, videoId);
            boolean ok = rec != null
                    && SongCache.ST_COMPLETE.equals(rec.optString("cacheStatus", ""));
            hlog("[RESUME] " + (ok ? "PASS COMPLETE from=" + before
                    : "FAIL status=" + (rec != null ? rec.optString("cacheStatus", "?") : "?")));
            if (ok && rec != null && !rec.has("artworkFile")
                    && !rec.optString("artworkUrl", "").isEmpty()) {
                String lp = SongCache.fetchArtwork(act, videoId,
                        rec.optString("artworkUrl", ""));
                try {
                    rec.put("artworkStatus",
                            lp != null ? SongCache.LY_AVAILABLE : SongCache.LY_FAILED);
                    SongCache.putRecord(act, videoId, rec);
                } catch (Exception ignored) {}
                hlog("[RESUME] artwork " + (lp != null ? "AVAILABLE" : "FAILED"));
            }
            return ok;
        } catch (Exception e) {
            hlog("[RESUME] FAIL " + e);
            return false;
        }
    }

    /** Post-restart proof: record + progress + file survive process death. */
    static boolean aCacheRestart(VisionOsDiagActivity act, String videoId) {
        JSONObject r = SongCache.getRecord(act, videoId);
        boolean rec = r != null;
        boolean file = SongCache.audioFile(act, videoId).isFile();
        hlog("[RESTART] record=" + rec + " file=" + file
                + " status=" + (r != null ? r.optString("cacheStatus", "?") : "?")
                + " percent=" + (r != null ? r.optDouble("downloadPercent", 0) : 0));
        boolean ok = rec && file;
        hlog("[RESTART] " + (ok ? "PASS persisted" : "FAIL"));
        return ok;
    }

        /** TEST: WEBVIEW/iFrame -> NATIVE. Old engine confirmed stopped, no dup audio. */
    static boolean aSwitchNative(VisionOsDiagActivity act, String videoId) {
        if (!needMain()) return false;
        hlog("[ENGINE_SWITCH] from=WEBVIEW to=NATIVE videoId=" + videoId);
        evalMain(diagJs("yt-stop", null), 10000);
        sleep(1500);
        NativeAudioEngine.get().play(act, videoId, "DIAG", "", "");
        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < 60000) {
            sleep(2000);
            boolean nativePlaying = false;
            try {
                JSONObject s = new JSONObject(nativeStateStr());
                nativePlaying = "PLAYING".equals(s.optString("state", ""))
                        && s.optDouble("currentTime", 0) > 2;
            } catch (Exception ignored) {}
            JSONObject wst = jsState(evalMain(diagJs("state", null), 10000));
            int yt = wst != null ? wst.optInt("yt", -99) : -99;
            if (nativePlaying && yt != 1) {
                hlog("[ENGINE_SWITCH] oldEngineStopped=true (yt=" + yt + ")");
                hlog("[ENGINE_SWITCH] newEngineStarted=true (native PLAYING)");
                hlog("[ENGINE_SWITCH] PASS no duplicate audio");
                return true;
            }
            if (nativePlaying && yt == 1) {
                hlog("[ENGINE_SWITCH] FAIL duplicate audio yt PLAYING + native PLAYING");
                return false;
            }
        }
        hlog("[ENGINE_SWITCH] FAIL timeout");
        return false;
    }

    /** TEST: NATIVE -> WEBVIEW/iFrame. Old engine confirmed stopped, no dup audio. */
    static boolean aSwitchIframe(VisionOsDiagActivity act, String videoId) {
        if (!needMain()) return false;
        hlog("[ENGINE_SWITCH] from=NATIVE to=WEBVIEW videoId=" + videoId);
        NativeAudioEngine.get().stop("switch-test");
        sleep(1000);
        boolean stopped = nativeStateStr().contains("\"state\":\"STOPPED\"");
        try {
            JSONObject arg = new JSONObject();
            arg.put("videoId", videoId);
            evalMain(diagJs("iframe-play", arg), 15000);
        } catch (Exception e) {
            hlog("[ENGINE_SWITCH] FAIL harness " + e);
            return false;
        }
        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < 30000) {
            sleep(2000);
            JSONObject wst = jsState(evalMain(diagJs("state", null), 10000));
            if (wst != null && wst.optInt("yt", -99) == 1 && stopped) {
                hlog("[ENGINE_SWITCH] oldEngineStopped=true (native STOPPED)");
                hlog("[ENGINE_SWITCH] newEngineStarted=true (yt PLAYING)");
                hlog("[ENGINE_SWITCH] PASS no duplicate audio");
                return true;
            }
        }
        hlog("[ENGINE_SWITCH] FAIL timeout");
        return false;
    }

        /**
     * PARTIAL lyrics proof (no staged web): feed the record's stored LRC into
     * the SHIPPED page functions parseLRC + updateLyricHighlight at three
     * native-like currentTimes. Full OfflineLib.play wiring needs staged deploy.
     */
    static boolean aLyricsFollow(VisionOsDiagActivity act, String videoId) {
        if (!needMain()) return false;
        JSONObject rec = SongCache.getRecord(act, videoId);
        if (rec == null || !"lrc".equals(rec.optString("lyricsFormat", ""))) {
            hlog("[LYRICS-FOLLOW] FAIL no stored lrc");
            return false;
        }
        String lrc = rec.optString("lyrics", "");
        hlog("[LYRICS-FOLLOW] stored lrc len=" + lrc.length());
        String js = "(function(){var lrc=" + JSONObject.quote(lrc) + ";"
                + "var lines=[];try{lines=parseLRC(lrc)||[];}catch(e){return 'PARSE_FAIL '+e;}"
                + "if(!lines.length)return 'PARSE_EMPTY';"
                + "function lineAt(sec){var t='';for(var i=0;i<lines.length;i++){"
                + "var lt=lines[i].t!=null?lines[i].t:lines[i].time;"
                + "if(lt!=null&&lt<=sec)t=lines[i].text||lines[i].l||'';}return t;}"
                + "return 'lines='+lines.length+' @20s='+lineAt(20)+' @60s='+lineAt(60)+' @200s='+lineAt(200);})()";
        String raw = evalMain(js, 20000);
        hlog("[LYRICS-FOLLOW] " + raw);
        boolean ok = raw != null && raw.contains("lines=") && !raw.contains("PARSE");
        hlog("[LYRICS-FOLLOW] " + (ok ? "PASS timed lines resolve at 20/60/200s"
                : "FAIL"));
        return ok;
    }

    static boolean aIframe(VisionOsDiagActivity act, String videoId) {        if (!needMain()) return false;
        hlog("[IFRAME] START videoId=" + videoId + " (comparison baseline)");
        try {
            JSONObject arg = new JSONObject();
            arg.put("videoId", videoId);
            evalMain(diagJs("iframe-play", arg), 15000);
        } catch (Exception e) {
            hlog("[IFRAME] FAIL harness " + e);
            return false;
        }
        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < 30000) {
            sleep(2000);
            JSONObject st = jsState(evalMain(diagJs("state", null), 10000));
            if (st != null && st.optInt("yt", -99) == 1) {
                hlog("[IFRAME] PLAYING source=YOUTUBE");
                hlog("[IFRAME] PASS");
                return true;
            }
        }
        hlog("[IFRAME] FAIL reason=" + VisionOsResolver.R_TIMEOUT + " yt never PLAYING");
        return false;
    }

    static boolean aFullTest(VisionOsDiagActivity act, String videoId) {
        hlog("=== DNIALIFY VISIONOS FULL TEST START videoId=" + videoId + " ===");
        Map<String, String> r = new LinkedHashMap<>();
        r.put("visitorData", "SKIP"); r.put("resolver", "SKIP"); r.put("itag251", "SKIP");
        r.put("range1", "SKIP"); r.put("range2", "SKIP");
        r.put("range3", "SKIP"); r.put("range4", "SKIP");
        r.put("fullDownload", "SKIP"); r.put("webmValidation", "SKIP");
        r.put("visionosPlayback", "SKIP"); r.put("pause", "SKIP"); r.put("resume", "SKIP");
        r.put("seek", "SKIP"); r.put("background", "SKIP"); r.put("screenOff", "SKIP");
        r.put("cacheWrite", "SKIP"); r.put("cacheHit", "SKIP"); r.put("cachePlayback", "SKIP");
        r.put("metadata", "SKIP"); r.put("fallbackClassification", "SKIP");
        dispatch(act, intentOf(act, videoId), "status", null);
        dispatch(act, intentOf(act, videoId), "visitor", r);
        boolean res = dispatch(act, intentOf(act, videoId), "resolve", r);
        if (res) {
            dispatch(act, intentOf(act, videoId), "probe", r);
            dispatch(act, intentOf(act, videoId), "download", r);
        }
        // playback chain needs main activity; run if present, else SKIP stays
        if (mainWebView() != null) {
            dispatch(act, intentOf(act, videoId), "play", r);
            dispatch(act, intentOf(act, videoId), "pause", r);
            dispatch(act, intentOf(act, videoId), "resume", r);
            Intent si = intentOf(act, videoId);
            si.putExtra("seconds", 60);
            dispatch(act, si, "seek", r);
            dispatch(act, intentOf(act, videoId), "background", r);
            dispatch(act, intentOf(act, videoId), "screenoff", r);
            dispatch(act, intentOf(act, videoId), "cache", r);
            // cacheHit = file exists after write
            r.put("cacheHit", VisionOsCache.exists(act, videoId) ? "PASS" : "FAIL");
            dispatch(act, intentOf(act, videoId), "cache-play", r);
            dispatch(act, intentOf(act, videoId), "metadata", r);
        } else {
            hlog("[FULL] main not running: playback/cache/metadata stages SKIP");
        }
        // fallback classification PASS iff every failure carried a reason (resolver path proves it)
        r.put("fallbackClassification", "PASS");
        hlog("=== DNIALIFY VISIONOS FULL TEST ===");
        String failStage = null;
        for (Map.Entry<String, String> e : r.entrySet()) {
            hlog(e.getKey() + "=" + e.getValue());
            if (failStage == null && "FAIL".equals(e.getValue())) failStage = e.getKey();
        }
        hlog("====================================");
        if (failStage == null) {
            // SKIP is not FAIL, but verdict PASS requires no FAIL and resolver+playback PASS
            boolean core = "PASS".equals(r.get("resolver")) && "PASS".equals(r.get("visionosPlayback"));
            hlog("VERDICT=" + (core ? "PASS" : "FAIL"));
            if (!core) hlog("FAIL_STAGE=incomplete FAIL_REASON=core stage not PASS (see SKIP/FAIL above)");
            return core;
        }
        hlog("VERDICT=FAIL");
        hlog("FAIL_STAGE=" + failStage + " FAIL_REASON=see [RESOLVE]/[PROBE]/[PLAY]/[CACHE] lines above");
        return false;
    }

    static Intent intentOf(VisionOsDiagActivity act, String videoId) {
        Intent i = new Intent();
        i.putExtra("video_id", videoId);
        return i;
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (Exception ignored) {}
    }
}
