package com.dnialify.musicstream;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.audiofx.LoudnessEnhancer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;

/**
 * Phase 8 - ENGINE B (NATIVE playback). Single source of truth for native audio.
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
    // CACHE-FIRST bookkeeping (never read network for playback)
    private int playGen = 0;          // idempotency: stale waiters/threads abort
    private int pendingStartMs = 0;   // startPathAt offset (initial + seek-refill)
    private long lastTotal = -1;      // clen of current track (time<->byte math)
    private String lastUrl = "";      // stream url (refill restarts writer with it)
    private String lastCache = "";    // HIT | MISS (surfaced to WebView badge)
    private String playFile = "";     // local cache path currently served
    private LocalStreamServer streamServer; // 127.0.0.1 proxy (no frozen EOF)
    private long windowEnd = -1;      // sliding prefetch cap (bytes), -1 = unset
    private int windowChunk = -1;     // playback chunk the window was built for
    private int appVolumePercent = 100; // app volume 0..300, multiplies hardware volume
    private LoudnessEnhancer boostEnhancer; // unity-gain boost, attached to mp session
    private float currentMpVolume = 1.0f; // last gain written to MediaPlayer
    private AudioManager audioManager; // hardware volume readout for debug logs
    private boolean capTested = false; // one-shot LoudnessEnhancer cap probe
    private int enhancerCapMb = -1; // measured device gain ceiling, -1 = unknown

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

    /** Play: cache HIT -> file; MISS -> fill first chunk, then play local snapshot. Never streams. */
    public void play(Context c, String vid, String t, String ar, String art) {
        final int gen;
        final String prevVid;
        synchronized (lock) {
            prevVid = videoId;
            stopLocked("replay");
            playGen++;
            gen = playGen;
            pendingStartMs = 0;
            lastTotal = -1;
            lastCache = "";
            appCtx = c.getApplicationContext();
            videoId = vid != null ? vid : "";
            title = t != null ? t : "";
            artist = ar != null ? ar : "";
            artwork = art != null ? art : "";
            failReason = null;
            failMessage = null;
            setStateLocked(State.RESOLVING);
            windowChunk = -1;
            windowEnd = -1;
        }
        // track change: cancel old window, never stack fills.
        // FULL jobs ("Download offline") survive: writer runs to COMPLETE.
        if (prevVid != null && !prevVid.isEmpty() && !prevVid.equals(vid)
                && !SongCache.isFull(prevVid)) {
            SongCache.pauseDownload(prevVid);
        }
        nlog("[NATIVE_CMD] play videoId=" + videoId + " gen=" + gen);
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
        final Context ac = c.getApplicationContext();
        final String v = vid;
        new Thread(() -> resolveAndStart(ac, v, gen)).start();
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
        // Proxy serves any Range: uncached regions hold (spinner), never EOF.
        // No refill dance: one direct seek, server waits for the bytes.
        synchronized (lock) {
            if (mp == null || (state != State.PLAYING && state != State.PAUSED)) return;
            int ms = Math.max(0, seconds * 1000);
            if (durationMs > 0) ms = Math.min(ms, durationMs);
            try {
                seeking = true;
                wasPlayingBeforeSeek = (state == State.PLAYING);
                setStateLocked(State.SEEKING);
                nlog("[NATIVE_CMD] seek=" + seconds + ".000 proxy videoId=" + videoId);
                mp.seekTo(ms);
            } catch (Exception e) {
                seeking = false;
                failLocked(VisionOsResolver.R_MEDIA_ERROR, "seek threw " + e);
            }
        }
        pushAll();
    }

    public void stop(String why) {
        final String vid;
        synchronized (lock) {
            vid = videoId;
            stopLocked(why != null ? why : "stop");
            windowChunk = -1;
            windowEnd = -1;
        }
        if (vid != null && !vid.isEmpty() && !SongCache.isFull(vid)) {
            SongCache.pauseDownload(vid);
        }
        pushAll();
    }

    public void setVolume(float v) {
        setVolumePercent(Math.round(v * 100));
    }

    /** App volume in percent (0..300). Multiplies hardware volume linearly.
     *  <=100: plain MediaPlayer gain. >100: unity gain + LoudnessEnhancer boost. */
    public void setVolumePercent(int percent) {
        synchronized (lock) {
            appVolumePercent = Math.max(0, percent);
            applyVolumeLocked();
        }
    }

    private void applyVolumeLocked() {
        if (mp == null) return;
        if (audioManager == null && appCtx != null) {
            try { audioManager = (AudioManager) appCtx.getSystemService(Context.AUDIO_SERVICE); }
            catch (Exception ignored) {}
        }
        float appFraction = appVolumePercent / 100f;
        int gainMb = 0;
        try {
            if (appFraction <= 1.0f) {
                currentMpVolume = appFraction;
                mp.setVolume(appFraction, appFraction);
                if (boostEnhancer != null) {
                    try { boostEnhancer.setTargetGain(0); } catch (Exception ignored) {}
                    try { boostEnhancer.setEnabled(false); } catch (Exception ignored) {}
                }
            } else {
                currentMpVolume = 1.0f;
                mp.setVolume(1.0f, 1.0f);
                gainMb = (int) (2000 * Math.log10(appFraction));
                if (boostEnhancer == null) {
                    probeEnhancerCapLocked();
                    boostEnhancer = new LoudnessEnhancer(mp.getAudioSessionId());
                    boostEnhancer.setEnabled(true);
                } else {
                    try { boostEnhancer.setEnabled(true); } catch (Exception ignored) {}
                }
                boostEnhancer.setTargetGain(gainMb);
            }
        } catch (Exception e) {
            nlog("[NATIVE_CMD] volume FAIL " + e);
            return;
        }
        boolean enhOn = false;
        int enhGain = -1;
        try {
            if (boostEnhancer != null) {
                enhOn = boostEnhancer.getEnabled();
                enhGain = (int) boostEnhancer.getTargetGain();
            }
        } catch (Exception ignored) {}
        int hwLevel = -1, hwMax = -1;
        try {
            if (audioManager != null) {
                hwLevel = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                hwMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            }
        } catch (Exception ignored) {}
        nlog("[NATIVE_CMD] volumePercent=" + appVolumePercent + " gainMb=" + gainMb
                + " mpVolume=" + currentMpVolume + " enhancerEnabled=" + (enhOn ? "1" : "0")
                + " enhancerGain=" + enhGain + " hwVolLevel=" + hwLevel + " hwVolMax=" + hwMax
                + " enhancerCapMb=" + enhancerCapMb);
    }

    /** One-shot probe: request 2000 mB on this session, read back the real
     *  ceiling. Reveals device LoudnessEnhancer caps (some cap near 1000 mB). */
    private void probeEnhancerCapLocked() {
        if (capTested || mp == null) return;
        capTested = true;
        LoudnessEnhancer test = null;
        try {
            test = new LoudnessEnhancer(mp.getAudioSessionId());
            test.setEnabled(true);
            test.setTargetGain(2000);
            enhancerCapMb = (int) test.getTargetGain();
            nlog("[NATIVE_CMD] LoudnessEnhancer cap test: requested=2000 actual=" + enhancerCapMb);
        } catch (Exception e) {
            nlog("[NATIVE_CMD] LoudnessEnhancer cap test failed " + e);
        } finally {
            try { if (test != null) test.release(); } catch (Exception ignored) {}
        }
    }

    /** Debug snapshot callable from bridge/ADB: full volume chain in one line. */
    public String dumpVolumeState() {
        synchronized (lock) {
            int enhGain = -1;
            boolean enhOn = false;
            try {
                if (boostEnhancer != null) {
                    enhGain = (int) boostEnhancer.getTargetGain();
                    enhOn = boostEnhancer.getEnabled();
                }
            } catch (Exception ignored) {}
            int hwLevel = -1, hwMax = -1;
            try {
                if (audioManager != null) {
                    hwLevel = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                    hwMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                }
            } catch (Exception ignored) {}
            return "appVol=" + appVolumePercent + " gainMb=" + enhGain
                    + " enhEnabled=" + enhOn + " hwVol=" + hwLevel + "/" + hwMax
                    + " enhancerCapMb=" + enhancerCapMb;
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

    private void resolveAndStart(Context c, String vid, int gen) {
        // 1. cache HIT? record COMPLETE + file valid (repairs stale COMPLETE)
        if (SongCache.isComplete(c, vid)) {
            java.io.File hf = SongCache.audioFile(c, vid);
            nlog("[CACHE] HIT videoId=" + vid + " bytes=" + hf.length());
            synchronized (lock) {
                lastCache = "HIT";
                lastTotal = hf.length();
                playFile = hf.getAbsolutePath();
            }
            startPathAt(hf.getAbsolutePath(), Source.CACHED_AUDIO, c, gen, 0);
            return;
        }
        // legacy flat file without record still counts as HIT (backward compat)
        java.io.File f = VisionOsCache.fileFor(c, vid);
        if (f.isFile() && f.length() > 0 && SongCache.getRecord(c, vid) == null) {
            nlog("[CACHE] HIT(legacy) videoId=" + vid + " bytes=" + f.length());
            synchronized (lock) {
                lastCache = "HIT";
                lastTotal = f.length();
                playFile = f.getAbsolutePath();
            }
            startPathAt(f.getAbsolutePath(), Source.CACHED_AUDIO, c, gen, 0);
            return;
        }
        nlog("[CACHE] MISS videoId=" + vid + " gen=" + gen);
        // 2. resolve (LOGIN_REQUIRED retry lives inside resolver, max 1x)
        VisionOsResolver.Result r;
        try {
            r = VisionOsResolver.resolve(vid);
        } catch (VisionOsResolver.ResolverException e) {
            synchronized (lock) {
                if (gen != playGen) return;
                failLocked(e.reason != null ? e.reason
                        : VisionOsResolver.R_RESOLVER_FAIL,
                        e.stage + " " + e.getMessage());
            }
            pushAll();
            return;
        }
        synchronized (lock) {
            if (gen != playGen) return;
        }
        nlog("[ENGINE] logical=NATIVE source=CACHE_FILL itag=" + r.itag);
        final String url = r.url;
        // record duration as soon as resolver knows it
        try {
            JSONObject rec = SongCache.getRecord(c, vid);
            if (rec != null && rec.optDouble("duration", 0) <= 0 && r.durationMs > 0) {
                rec.put("duration", r.durationMs / 1000.0);
                SongCache.putRecord(c, vid, rec);
            }
        } catch (Exception ignored) {}
        final long total = clenOf(url);
        if (total <= 0) {            // bottom fallback only: no length, chunking impossible -> legacy stream
            nlog("[CACHE] total<=0, bottom-fallback stream videoId=" + vid);
            synchronized (lock) {
                lastCache = "MISS";
            }
            startUrl(url, Source.VISIONOS);
            return;
        }
        synchronized (lock) {
            lastTotal = total;
            lastUrl = url;
            lastCache = "MISS";
        }
        // 3. CACHE-FIRST: writer fills (resume-aware, proven path), waiter plays
        // local snapshot as soon as the first chunk lands. Never streams.
        final Context ac = c;
        new Thread(() -> {
            SongCache.download(ac, vid, url, total);
            // best-effort artwork, never fails audio
            try {
                JSONObject rec = SongCache.getRecord(ac, vid);
                if (rec != null && !rec.has("artworkFile")) {
                    SongCache.fetchArtwork(ac, vid, rec.optString("artworkUrl", ""));
                }
            } catch (Exception ignored) {}
        }).start();
        // initial need = first TWO chunks (prepare needs real clusters now).
        // Playback goes through the local proxy (never a frozen snapshot),
        // so no snapshot copy here: register the live file and start.
        long oneChunk = SongCache.chunkEndFor(0, (int) r.durationMs, total);
        long need = Math.min(total, 2 * oneChunk);
        if (!waitForBytes(ac, vid, need, gen, total)) return;
        synchronized (lock) {
            if (gen != playGen) return;
        }
        java.io.File live = SongCache.isComplete(ac, vid)
                ? SongCache.audioFile(ac, vid) : SongCache.partFile(ac, vid);
        synchronized (lock) {
            if (gen != playGen) return;
            playFile = live.getAbsolutePath();
        }
        nlog("[CACHE] prefix ready videoId=" + vid + " bytes=" + live.length());
        synchronized (lock) {
            if (gen != playGen) return;
            setWindow(vid, url, total, 0, (int) r.durationMs, ac);
        }
        startPathAt(live.getAbsolutePath(), Source.CACHED_AUDIO, ac, gen, 0);
    }

    /**
     * Wait (off main thread) until localBytes >= need or terminal condition.
     * Terminal: gen stale, stop, record FAILED, or no progress for TIMEOUT_MS
     * (reuses SongCache timeout, no new watchdog). Returns true when playable.
     */
    private boolean waitForBytes(Context c, String vid, long need, int gen, long total) {
        long lastSeen = -1;
        long lastMoveAt = System.currentTimeMillis();
        for (;;) {
            synchronized (lock) {
                if (gen != playGen) return false;
                if (state == State.STOPPED || state == State.IDLE || state == State.ERROR) return false;
            }
            if (SongCache.hasBytes(c, vid, need)) return true;
            try {
                JSONObject rec = SongCache.getRecord(c, vid);
                if (rec != null
                        && SongCache.ST_FAILED.equals(rec.optString("cacheStatus", ""))) {
                    synchronized (lock) {
                        if (gen != playGen) return false;
                        failLocked(rec.optString("failReason", "cache-failed"), "fill failed");
                    }
                    pushAll();
                    return false;
                }
            } catch (Exception ignored) {}
            long have = SongCache.localBytes(c, vid);
            long now = System.currentTimeMillis();
            if (have != lastSeen) {
                lastSeen = have;
                lastMoveAt = now;
            } else if (now - lastMoveAt > SongCache.TIMEOUT_MS
                    && !SongCache.isDownloading(vid)) {
                synchronized (lock) {
                    if (gen != playGen) return false;
                    failLocked(VisionOsResolver.R_TIMEOUT, "fill stalled");
                }
                pushAll();
                return false;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                return false;
            }
        }
    }
    /**
     * Sliding window-5: chunk containing curMs, prefetch 5 ahead, hard stop.
     * Chunk size derived from SongCache (follows the 100KB const, no dup).
     * Enforced from the 4 triggers only: play start, chunk cross (tick),
     * seek-refill, track change. No timer, no polling thread.
     */
    private int chunkIdxForPos(int curMs, int durMs, long total) {
        long one = SongCache.chunkEndFor(0, durMs, total);
        if (one <= 0 || total <= 0 || durMs <= 0 || curMs <= 0) return 0;
        long need = total * (long) curMs / durMs;
        long idx = need / one;
        return idx > 1000000L ? 1000000 : (int) idx;
    }

    private long windowEndForPos(int curMs, int durMs, long total) {
        long one = SongCache.chunkEndFor(0, durMs, total);
        if (one <= 0) return total;
        long edge = SongCache.chunkEndFor(curMs, durMs, total);
        if (edge <= 0) return total;
        return Math.min(total, edge + 5 * one);
    }

    /** Set window for a position; (re)starts resume-aware writer when behind. */
    private void setWindow(String vid, String url, long total, int curMs,
            int durMs, Context ac) {
        windowChunk = chunkIdxForPos(curMs, durMs, total);
        windowEnd = windowEndForPos(curMs, durMs, total);
        if (ac == null || total <= 0) return;
        if (SongCache.hasBytes(ac, vid, Math.min(windowEnd, total))) {
            if (SongCache.isDownloading(vid) && !SongCache.isFull(vid)) {
                SongCache.pauseDownload(vid);
            }
            return;
        }
        if (!SongCache.isDownloading(vid) && url != null && !url.isEmpty()) {
            final Context fc = ac;
            nlog("[CACHE] window fetch videoId=" + vid + " chunk=" + windowChunk
                    + " end=" + windowEnd);
            new Thread(() -> SongCache.download(fc, vid, url, total)).start();
        }
    }

    private void startPath(String path, Source src, Context c) {        synchronized (lock) {
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

    /** Lazy local proxy; null when it fails (caller falls back to file path). */
    private LocalStreamServer ensureServer(Context c) {
        if (streamServer != null) return streamServer;
        try {
            Context ac = c != null ? c.getApplicationContext() : appCtx;
            if (ac == null) return null;
            LocalStreamServer s = new LocalStreamServer(ac);
            s.start(10000, true);
            streamServer = s;
            nlog("[ENGINE] local proxy port=" + s.port());
            return s;
        } catch (Exception e) {
            nlog("[ENGINE] proxy start FAIL " + e + " -> file fallback");
            streamServer = null;
            return null;
        }
    }

    /** startPath with generation + start offset (initial fill and seek-refill share it). */
    private void startPathAt(String path, Source src, Context c, int gen, int startMs) {
        synchronized (lock) {
            if (gen != playGen) return;
            pendingStartMs = Math.max(0, startMs);
            prepareLocked();
            seeking = false;
            source = src;
            playFile = path;
            setStateLocked(State.BUFFERING);
            // Serve through the local proxy so a growing file never looks
            // truncated: the proxy holds Range reads until bytes land.
            String ds = path;
            try {
                LocalStreamServer srv = ensureServer(appCtx != null ? appCtx : c);
                if (srv != null && lastTotal > 0) {
                    srv.register(videoId, path, lastTotal);
                    ds = "http://127.0.0.1:" + srv.port() + "/local-stream/" + videoId;
                    nlog("[ENGINE] proxy serve videoId=" + videoId);
                }
            } catch (Exception ignored) {}
            nlog("[ENGINE] logical=NATIVE source=" + src
                    + " data=" + (ds.startsWith("http") ? "proxy" : "file")
                    + " gen=" + gen + " startMs=" + pendingStartMs
                    + " videoId=" + videoId);
            try {
                if (ds.startsWith("http")) {
                    mp.setDataSource(appCtx != null ? appCtx : c, Uri.parse(ds));
                } else {
                    mp.setDataSource(ds);
                }
                mp.prepareAsync();
            } catch (Exception e) {
                failLocked(VisionOsResolver.R_MEDIA_ERROR, "setDataSource file " + e);
            }
        }
        pushAll();
    }

    private void startUrl(String url, Source src) {
        // BOTTOM FALLBACK ONLY (cache-first bypass): total<=0, chunking impossible.
        nlog("[ENGINE] BOTTOM-FALLBACK stream (cache-first bypassed) videoId=" + videoId);
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
                if (pendingStartMs > 0) {
                    try {
                        int at = durationMs > 0
                                ? Math.min(pendingStartMs, durationMs) : pendingStartMs;
                        p.seekTo(at);
                        currentMs = at;
                    } catch (Exception ignored) {}
                    pendingStartMs = 0;
                }
                setStateLocked(State.PLAYING);
                applyVolumeLocked();
                nlog("[NATIVE_STATE] state=PLAYING videoId=" + videoId
                        + " duration=" + (durationMs / 1000.0));
            }
            pushAll();
        });
        mp.setOnCompletionListener(p -> {
            // Proxy closes the stream only at true end (or hold timeout):
            // completion is always real. No re-prepare, no resume loop.
            synchronized (lock) {
                try {
                    currentMs = p.getDuration();
                } catch (Exception ignored) {}
                if (state != State.ERROR) {
                    setStateLocked(State.ENDED);
                    nlog("[NATIVE_STATE] state=ENDED videoId=" + videoId);
                }
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
        try {
            if (streamServer != null) streamServer.unregister(videoId);
        } catch (Exception ignored) {}
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
        if (boostEnhancer != null) {
            try { boostEnhancer.release(); } catch (Exception ignored) {}
            boostEnhancer = null;
        }
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
            // sliding window-5 enforcement on the existing 500ms clock
            // (chunk-cross trigger; I/O only on cross, memory math otherwise).
            // Cap: bytes at window end + writer running -> pause (quota).
            // Extend: crossed a chunk + behind window + writer idle -> resume.
            try {
                int ci = chunkIdxForPos(currentMs, durationMs, lastTotal);
                if (lastTotal > 0 && durationMs > 0 && appCtx != null) {
                    if (ci != windowChunk) {
                        setWindow(videoId, lastUrl, lastTotal, currentMs,
                                durationMs, appCtx);
                    } else if (windowEnd > 0 && SongCache.isDownloading(videoId)
                            && !SongCache.isFull(videoId)) {
                        long have = 0;
                        try {
                            java.io.File pf = SongCache.partFile(appCtx, videoId);
                            if (pf.isFile()) have = pf.length();
                        } catch (Exception ignored) {}
                        if (have >= Math.min(windowEnd, lastTotal)) {
                            SongCache.pauseDownload(videoId);
                            nlog("[CACHE] window cap hit videoId=" + videoId
                                    + " end=" + windowEnd);
                        }
                    }
                }
            } catch (Exception ignored) {}
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
            o.put("cache", lastCache);
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
        // NOTE: pass the event NAME, not a prebuilt snapshot - pushEvent(null, snapshot)
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
