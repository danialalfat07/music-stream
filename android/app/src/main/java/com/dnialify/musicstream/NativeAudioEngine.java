package com.dnialify.musicstream;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;

/**
 * Phase 8 — ENGINE B (NATIVE playback). Single source of truth for native audio.
 *
 * engine=NATIVE, source=VISIONOS (direct googlevideo) | CACHED_AUDIO (local file).
 * ENGINE A (WebView iFrame) untouched; this class never touches WebView audio.
 *
 * States: IDLE RESOLVING BUFFERING PLAYING PAUSED SEEKING STOPPED ENDED ERROR.
 * WebView sends COMMANDS (bridge), engine pushes EVENTS (evaluateJavascript
 * window.__nativeEvent). Time updates 500ms. Notification via PlaybackService.
 * Fallback: LOGIN_REQUIRED -> 1 visitor refresh + 1 retry; other reasons ->
 * event error with reason, caller (WebView) switches to iFrame. No blind retry.
 */
public final class NativeAudioEngine {
    static final String TAG = "DnialifyVisionOS";

    public enum State {
        IDLE, RESOLVING, BUFFERING, PLAYING, PAUSED, SEEKING, STOPPED, ENDED, ERROR
    }

    public enum Source {
        NONE, VISIONOS, CACHED_AUDIO
    }

    private static final NativeAudioEngine INSTANCE = new NativeAudioEngine();

    private final Object lock = new Object();
    private MediaPlayer mp;
    private State state = State.IDLE;
    private Source source = Source.NONE;
    private String videoId = "";
    private String title = "";
    private String artist = "";
    private String artwork = "";
    private String failReason;
    private String failMessage;
    private boolean seeking;
    private Context appCtx;
    private final Handler timer = new Handler(Looper.getMainLooper());
    private long lastTickMs;
    private int currentMs;
    private int durationMs;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            tick();
        }
    };

    private NativeAudioEngine() {}

    public static NativeAudioEngine get() {
        return INSTANCE;
    }

    // ---------- public commands (bridge + harness) ----------

    /** Play: cache HIT -> file; MISS -> resolve then stream-first (+writer if cacheOn). */
    public void play(Context c, String vid, String t, String ar, String art) {
        synchronized (lock) {
            stopLocked("replay");
            appCtx = c.getApplicationContext();
            videoId = vid != null ? vid : "";
            title = t != null ? t : "";
            artist = ar != null ? ar : "";
            artwork = art != null ? art : "";
            failReason = null;
            failMessage = null;
            setStateLocked(State.RESOLVING);
        }
        nlog("[NATIVE_CMD] play videoId=" + videoId);
        pushEvent("sourceChanged", null);
        // metadata persisted at START (never wait for audio to finish)
        try {
            JSONObject meta = new JSONObject();
            meta.put("videoId", videoId);
            meta.put("title", title);
            meta.put("artist", artist);
            meta.put("artworkUrl", artwork);
            meta.put("source", "VISIONOS");
            SongCache.ensureMeta(c.getApplicationContext(), meta);
        } catch (Exception ignored) {}
        new Thread(() -> resolveAndStart(c, vid)).start();
    }

    public void pause() {
        synchronized (lock) {
            if (mp != null && state == State.PLAYING) {
                try {
                    mp.pause();
                } catch (Exception e) {
                    failLocked(VisionOsResolver.R_MEDIA_ERROR, "pause threw " + e);
                    return;
                }
                setStateLocked(State.PAUSED);
                nlog("[NATIVE_CMD] pause videoId=" + videoId);
            }
        }
        pushAll();
    }

    public void resume() {
        synchronized (lock) {
            if (mp != null && state == State.PAUSED) {
                try {
                    mp.start();
                } catch (Exception e) {
                    failLocked(VisionOsResolver.R_MEDIA_ERROR, "resume threw " + e);
                    return;
                }
                setStateLocked(State.PLAYING);
                nlog("[NATIVE_CMD] resume videoId=" + videoId);
            }
        }
        pushAll();
    }

    public void seek(int seconds) {
        synchronized (lock) {
            if (mp != null && (state == State.PLAYING || state == State.PAUSED)) {
                int ms = Math.max(0, seconds * 1000);
                if (durationMs > 0) ms = Math.min(ms, durationMs);
                try {
                    seeking = true;
                    wasPlayingBeforeSeek = (state == State.PLAYING);
                    setStateLocked(State.SEEKING);
                    nlog("[NATIVE_CMD] seek=" + seconds + ".000 videoId=" + videoId);
                    mp.seekTo(ms);
                } catch (Exception e) {
                    seeking = false;
                    failLocked(VisionOsResolver.R_MEDIA_ERROR, "seek threw " + e);
                }
            }
        }
    }

    public void stop(String why) {
        synchronized (lock) {
            stopLocked(why != null ? why : "stop");
        }
        pushAll();
    }

    public void setVolume(float v) {
        synchronized (lock) {
            if (mp != null) {
                float f = Math.max(0f, Math.min(1f, v));
                try {
                    mp.setVolume(f, f);
                } catch (Exception ignored) {}
                nlog("[NATIVE_CMD] volume=" + f);
            }
        }
    }

    public JSONObject getState() {
        synchronized (lock) {
            return snapshotLocked(null);
        }
    }

    public boolean isActive() {
        synchronized (lock) {
            return state == State.RESOLVING || state == State.BUFFERING
                    || state == State.PLAYING || state == State.PAUSED
                    || state == State.SEEKING;
        }
    }

    // ---------- internals ----------

    private void resolveAndStart(Context c, String vid) {
        // 1. cache HIT? record COMPLETE + file valid (repairs stale COMPLETE)
        if (SongCache.isComplete(c, vid)) {
            java.io.File hf = SongCache.audioFile(c, vid);
            nlog("[CACHE] HIT videoId=" + vid + " bytes=" + hf.length());
            startPath(hf.getAbsolutePath(), Source.CACHED_AUDIO, c);
            return;
        }
        // legacy flat file without record still counts as HIT (backward compat)
        java.io.File f = VisionOsCache.fileFor(c, vid);
        if (f.isFile() && f.length() > 0 && SongCache.getRecord(c, vid) == null) {
            nlog("[CACHE] HIT(legacy) videoId=" + vid + " bytes=" + f.length());
            startPath(f.getAbsolutePath(), Source.CACHED_AUDIO, c);
            return;
        }
        nlog("[CACHE] MISS videoId=" + vid);
        // 2. resolve (LOGIN_REQUIRED retry lives inside resolver, max 1x)
        VisionOsResolver.Result r;
        try {
            r = VisionOsResolver.resolve(vid);
        } catch (VisionOsResolver.ResolverException e) {
            synchronized (lock) {
                failLocked(e.reason != null ? e.reason
                        : VisionOsResolver.R_RESOLVER_FAIL,
                        e.stage + " " + e.getMessage());
            }
            pushAll();
            return;
        }
        nlog("[ENGINE] logical=NATIVE source=VISIONOS itag=" + r.itag);
        final String url = r.url;
        // record duration as soon as resolver knows it
        try {
            JSONObject rec = SongCache.getRecord(c, vid);
            if (rec != null && rec.optDouble("duration", 0) <= 0 && r.durationMs > 0) {
                rec.put("duration", r.durationMs / 1000.0);
                SongCache.putRecord(c, vid, rec);
            }
        } catch (Exception ignored) {}
        // 3. stream-first: start playback now; record writer runs apart when cache ON
        if (StreamSettings.isAudioCacheOn(c) && r != null) {
            final long total = clenOf(url);
            new Thread(() -> {
                SongCache.download(c, vid, url, total);
                // best-effort artwork, never fails audio
                try {
                    JSONObject rec = SongCache.getRecord(c, vid);
                    if (rec != null && !rec.has("artworkFile")) {
                        SongCache.fetchArtwork(c, vid, rec.optString("artworkUrl", ""));
                    }
                } catch (Exception ignored) {}
            }).start();
        }
        startUrl(url, Source.VISIONOS);
    }

    private void startPath(String path, Source src, Context c) {
        synchronized (lock) {
            prepareLocked();
            source = src;
            setStateLocked(State.BUFFERING);
            nlog("[ENGINE] logical=NATIVE source=" + src
                    + " data=file videoId=" + videoId);
            try {
                mp.setDataSource(path);
                mp.prepareAsync();
            } catch (Exception e) {
                failLocked(VisionOsResolver.R_MEDIA_ERROR, "setDataSource file " + e);
            }
        }
        pushAll();
    }

    private void startUrl(String url, Source src) {
        synchronized (lock) {
            prepareLocked();
            source = src;
            setStateLocked(State.BUFFERING);
            nlog("[ENGINE] logical=NATIVE source=" + src
                    + " data=stream videoId=" + videoId);
            try {
                Map<String, String> headers = new HashMap<>();
                headers.put("User-Agent",
                        "com.google.visionos.youtube/1.02(RealityDevice14,1; U; CPU visionOS 25_6_0 like Mac OS X; US)");
                mp.setDataSource(appCtx, android.net.Uri.parse(url), headers);
                mp.prepareAsync();
            } catch (Exception e) {
                failLocked(VisionOsResolver.R_MEDIA_ERROR, "setDataSource url " + e);
            }
        }
        pushAll();
    }

    private void prepareLocked() {
        releaseLocked();
        mp = new MediaPlayer();
        try {
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build());
        } catch (Exception ignored) {}
        mp.setOnPreparedListener(p -> {
            synchronized (lock) {
                if (state != State.BUFFERING) return;
                durationMs = 0;
                try {
                    durationMs = p.getDuration();
                } catch (Exception ignored) {}
                currentMs = 0;
                try {
                    p.start();
                } catch (Exception e) {
                    failLocked(VisionOsResolver.R_MEDIA_ERROR, "start threw " + e);
                    pushAll();
                    return;
                }
                setStateLocked(State.PLAYING);
                nlog("[NATIVE_STATE] state=PLAYING videoId=" + videoId
                        + " duration=" + (durationMs / 1000.0));
            }
            pushAll();
        });
        mp.setOnCompletionListener(p -> {
            synchronized (lock) {
                try {
                    currentMs = p.getDuration();
                } catch (Exception ignored) {}
                setStateLocked(State.ENDED);
                nlog("[NATIVE_STATE] state=ENDED videoId=" + videoId);
            }
            pushAll();
        });
        mp.setOnErrorListener((p, what, extra) -> {
            synchronized (lock) {
                failLocked(VisionOsResolver.R_MEDIA_ERROR,
                        "mediaplayer what=" + what + " extra=" + extra);
            }
            pushAll();
            return true;
        });
        mp.setOnSeekCompleteListener(p -> {
            synchronized (lock) {
                seeking = false;
                try {
                    currentMs = p.getCurrentPosition();
                } catch (Exception ignored) {}
                if (state == State.SEEKING) {
                    setStateLocked(wasPlayingBeforeSeek ? State.PLAYING : State.PAUSED);
                }
                nlog("[NATIVE_STATE] seekDone cur=" + (currentMs / 1000.0));
            }
            pushAll();
        });
        mp.setOnBufferingUpdateListener((p, pct) -> {
            nlog("[NATIVE_STATE] buffering pct=" + pct + " videoId=" + videoId);
            pushEvent("bufferingChanged", pct);
        });
        wasPlayingBeforeSeek = false; // reset per track; real value captured in seek()
    }

    private boolean wasPlayingBeforeSeek;

    private void stopLocked(String why) {
        timer.removeCallbacks(ticker);
        releaseLocked();
        state = State.STOPPED;
        source = Source.NONE;
        seeking = false;
        currentMs = 0;
        nlog("[NATIVE_CMD] stop why=" + why + " videoId=" + videoId);
        // hand notification back to idle (WebView mirror resumes on next push)
        try {
            if (appCtx != null) PlaybackService.nativeReleased(appCtx);
        } catch (Exception ignored) {}
    }

    private void releaseLocked() {
        if (mp != null) {
            try {
                mp.reset();
            } catch (Exception ignored) {}
            try {
                mp.release();
            } catch (Exception ignored) {}
            mp = null;
        }
    }

    private void failLocked(String reason, String msg) {
        timer.removeCallbacks(ticker);
        releaseLocked();
        state = State.ERROR;
        failReason = reason;
        failMessage = msg;
        nlog("[FALLBACK] START reason=" + reason + " msg=" + msg
                + " videoId=" + videoId + " action=iFrame");
    }

    private void setStateLocked(State s) {
        state = s;
        if (s == State.PLAYING) {
            timer.removeCallbacks(ticker);
            timer.postDelayed(ticker, 500);
            lastTickMs = System.currentTimeMillis();
        } else if (s != State.SEEKING) {
            timer.removeCallbacks(ticker);
        }
    }

    private void tick() {
        synchronized (lock) {
            if (state != State.PLAYING || mp == null) return;
            try {
                currentMs = mp.getCurrentPosition();
                if (durationMs <= 0) durationMs = mp.getDuration();
            } catch (Exception ignored) {}
            pushEvent("timeUpdate", null);
            // notification progress (throttled: service updates cheap)
            try {
                if (appCtx != null) {
                    PlaybackService.nativeProgress(appCtx, title, artist, artwork,
                            true, currentMs, durationMs);
                }
            } catch (Exception ignored) {}
            timer.postDelayed(ticker, 500);
        }
    }

    private JSONObject snapshotLocked(String event) {
        try {
            JSONObject o = new JSONObject();
            o.put("engine", "NATIVE");
            o.put("source", String.valueOf(source));
            o.put("state", String.valueOf(state));
            o.put("videoId", videoId);
            o.put("currentTime", currentMs / 1000.0);
            o.put("duration", durationMs / 1000.0);
            o.put("title", title);
            o.put("artist", artist);
            o.put("artwork", artwork);
            if (event != null) o.put("event", event);
            if (failReason != null) o.put("reason", failReason);
            if (failMessage != null) o.put("message", failMessage);
            return o;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void pushAll() {
        // NOTE: pass the event NAME, not a prebuilt snapshot — pushEvent(null, snapshot)
        // silently drops the event field, so JS never refreshes the play/pause icon.
        pushEvent("playbackStateChanged", null);
        // notification follows native while active or just ended/errored
        try {
            if (appCtx != null) {
                PlaybackService.nativeProgress(appCtx, title, artist, artwork,
                        state == State.PLAYING, currentMs, durationMs);
            }
        } catch (Exception ignored) {}
    }

    private void pushEvent(String event, Object extra) {
        JSONObject s;
        synchronized (lock) {
            s = snapshotLocked(event);
        }
        if (extra instanceof Integer) {
            try {
                s.put("bufferPct", (Integer) extra);
            } catch (Exception ignored) {}
        }
        final String js = "try{if(window.__nativeEvent)window.__nativeEvent("
                + JSONObject.quote(s.toString()) + ");}catch(e){}";
        try {
            MainActivity a = MainActivity.current;
            if (a == null) return;
            a.runOnUiThread(() -> {
                try {
                    android.webkit.WebView wv = a.getBridge() != null
                            ? a.getBridge().getWebView() : null;
                    if (wv != null) wv.evaluateJavascript(js, null);
                } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
        // ADB mirror of every event
        nlog("[NATIVE_STATE] " + s);
    }

    private static long clenOf(String url) {
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

    static void nlog(String m) {
        Log.d(TAG, m);
    }
}
