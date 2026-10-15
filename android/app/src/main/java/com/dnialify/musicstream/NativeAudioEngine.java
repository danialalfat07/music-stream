package com.dnialify.musicstream;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.audiofx.LoudnessEnhancer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

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
    private static volatile int CHUNK_WINDOW_SIZE = 5;
    // G1 watchdog: idle maksimum tanpa byte baru sebelum waitForBytes
    // nyatakan HANG (progress-based; slow network yang byte-nya ngalir
    // tak pernah fire karena lastMoveAt ikut gerak).
    private static final long WATCHDOG_IDLE_MS = 60000L;
    public static final int CHUNK_WINDOW_MIN = 1;
    public static final int CHUNK_WINDOW_MAX = 200;

    private final Object lock = new Object();
    private MediaPlayer mp;

    // Socket 3: Exo backend (VisionOS). Player tunggal dari ExoPlayerHolder,
    // tidak pernah new Builder di sini. mp path (iframe/cached) tak tersentuh.
    private boolean exoMode = false;
    private ExoPlayer exoPlayer;
    private boolean exoListenerAttached = false;
    private int lastResolverDurationMs = 0;
    private int lastWindowDurationMs = 0;
    // Socket 5: window timeline (JS owner). Seq guard: window basi dibuang.
    private int windowSeq = 0;
    private boolean windowApplying = false;
    private int windowAppliedSeq = -1;
    // Socket 7.3.4: tunggu window bounded sebelum first play + skip post-play.
    private int windowWaitGen = -1;
    private String windowWaitVid = null;
    private String windowAppliedVid = null;
    private int windowAppliedGen = -1;
    // F2: gen yang wait-nya dikonsumsi timeout-single (bukan apply).
    private int windowTimeoutGen = -1;
    // Socket 7.3.3 GLOBAL GUARD cacheFirst: bila armed, SEMUA jalur Exo
    // wajib pakai localhost URI untuk videoId yang di-guard. Direct dilarang.
    private volatile boolean cacheFirstActive = false;
    private volatile String cacheFirstUri = null;
    private volatile String cacheFirstVideoId = null;
    // Window terakhir (pending): diterapkan saat videoId cocok + exo siap.
    private java.util.List<String> pendVids;
    private java.util.List<String> pendTitles;
    private java.util.List<String> pendArtists;
    private java.util.List<String> pendThumbs;
    private java.util.List<Double> pendDurs;
    private int pendIdx = -1;
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
    private int recoveryAttempts = 0; // premature-completion recovery tries for current track
    private long lastRecoveryAttemptMs = 0;
    private int consecutiveRecoveryCount = 0;
    private static final long RECOVERY_DEBOUNCE_MS = 5000;
    private static final int MAX_CONSECUTIVE_RECOVERIES = 3;
    private String lastRecoveryStatus = ""; // "", "attempting", "recovered", "failed"
    private boolean recoveryPrepared = false; // one-shot hard-recovery prepare signal, guarded by lock
    private int appVolumePercent = 100; // app volume 0..300, multiplies hardware volume
    private LoudnessEnhancer boostEnhancer; // unity-gain boost, attached to mp session
    private float currentMpVolume = 1.0f; // last gain written to MediaPlayer
    private AudioManager audioManager; // hardware volume readout for debug logs
    // Audio focus (duck on notifications, pause for other players)
    private AudioManager focusManager;
    private AudioFocusRequest focusRequest;
    private android.media.AudioManager.OnAudioFocusChangeListener focusListener;
    private final Handler focusHandler = new Handler(Looper.getMainLooper());
    private boolean pausedByFocus = false;
    private boolean resumeWhenFocusGained = false;
    private float preLossVolumePercent = 100f;
    private boolean isDucked = false;
    private boolean capTested = false; // one-shot LoudnessEnhancer cap probe
    private int enhancerCapMb = -1; // measured device gain ceiling, -1 = unknown
    private long lastDiagTickLog;
    private PowerManager.WakeLock wakeLock;
    private boolean wakeLockHeld = false;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            tick();
        }
    };

    private NativeAudioEngine() {}

    public static NativeAudioEngine get() {
        return INSTANCE;
    }

    /** Alias for listeners/services that expect a getInstance() accessor. */
    public static NativeAudioEngine getInstance() {
        Log.d(TAG, "[DUCK] getInstance called, hash="
                + System.identityHashCode(INSTANCE));
        return INSTANCE;
    }

    public static void setChunkWindowSize(int n) {
        int v = Math.max(CHUNK_WINDOW_MIN, Math.min(CHUNK_WINDOW_MAX, n));
        if (v == CHUNK_WINDOW_SIZE) return;
        CHUNK_WINDOW_SIZE = v;
        final NativeAudioEngine engine = INSTANCE;
        engine.nlog("[ENGINE] chunk window set to " + v);
        engine.timer.post(() -> {
            synchronized (engine.lock) {
                if (engine.lastTotal > 0 && engine.durationMs > 0 && engine.appCtx != null
                        && engine.state != State.IDLE && engine.state != State.STOPPED
                        && engine.state != State.ERROR && engine.state != State.ENDED) {
                    engine.setWindow(engine.videoId, engine.lastUrl, engine.lastTotal,
                            engine.currentMs, engine.durationMs, engine.appCtx);
                }
            }
        });
    }

    // ---------- public commands (bridge + harness) ----------

    /** Play: cache HIT -> file; MISS -> fill first chunk, then play local snapshot. Never streams. */
    public void play(Context c, String vid, String t, String ar, String art) {
        disarmCacheFirst();
        // PROTOTYPE B-revisi: VisionOS resolve-only, no MediaPlayer. Mode
        // dibaca sekali di sini; semua mp.* hanya terjangkau via
        // resolveAndStart (jalur IFRAME/cached di bawah).
        // IFRAME (cached COMPLETE via _playCached) pakai jalur existing.
        int mode = StreamSettings.MODE_VISIONOS;
        try { mode = StreamSettings.getStreamMode(c); } catch (Exception ignored) {}
        if (mode == StreamSettings.MODE_VISIONOS) {
            final int gen;
            final String prevVid;
            synchronized (lock) {
                prevVid = videoId;
                stopLocked("replay");
                stopExoLocked();
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
            if (prevVid != null && !prevVid.isEmpty() && !prevVid.equals(vid)
                    && !SongCache.isFull(prevVid)) {
                SongCache.pauseDownload(prevVid);
            }
            nlog("[NATIVE_CMD] play videoId=" + videoId + " mode=VISIONOS resolve-only gen=" + gen);
            pushEvent("sourceChanged", null);
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
            new Thread(() -> resolveOnly(ac, v, gen)).start();
            return;
        }
        final int gen;
        final String prevVid;
        synchronized (lock) {
            prevVid = videoId;
            stopLocked("replay");
            stopExoLocked();
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
            recoveryAttempts = 0;
            consecutiveRecoveryCount = 0;
            lastRecoveryAttemptMs = 0;
            lastRecoveryStatus = "";
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
            if (exoMode && exoPlayer != null) {
                final ExoPlayer exo = exoPlayer;
                timer.post(() -> {
                    try {
                        exo.pause();
                    } catch (Exception e) {
                        synchronized (lock) {
                            failLocked(VisionOsResolver.R_MEDIA_ERROR, "exo pause threw " + e);
                        }
                        pushAll();
                    }
                });
                nlog("[NATIVE_CMD] pause videoId=" + videoId + " engine=EXO");
                return;
            }
            if (mp != null && state == State.PLAYING) {
                try {
                    mp.pause();
                } catch (Exception e) {
                    failLocked(VisionOsResolver.R_MEDIA_ERROR, "pause threw " + e);
                    return;
                }
                setStateLocked(State.PAUSED);
                releaseWakeLock();
                nlog("[NATIVE_CMD] pause videoId=" + videoId);
            }
        }
        pushAll();
    }

    public void resume() {
        synchronized (lock) {
            if (exoMode && exoPlayer != null) {
                final ExoPlayer exo = exoPlayer;
                timer.post(() -> {
                    try {
                        exo.play();
                    } catch (Exception e) {
                        synchronized (lock) {
                            failLocked(VisionOsResolver.R_MEDIA_ERROR, "exo resume threw " + e);
                        }
                        pushAll();
                    }
                });
                nlog("[NATIVE_CMD] resume videoId=" + videoId + " engine=EXO");
                return;
            }
            if (mp != null && state == State.PAUSED) {
                try {
                    mp.start();
                } catch (Exception e) {
                    failLocked(VisionOsResolver.R_MEDIA_ERROR, "resume threw " + e);
                    return;
                }
                setStateLocked(State.PLAYING);
                acquireWakeLock();
                nlog("[NATIVE_CMD] resume videoId=" + videoId);
            }
        }
        pushAll();
    }

    public void seek(int seconds) {
        // Proxy serves any Range: uncached regions hold (spinner), never EOF.
        // No refill dance: one direct seek, server waits for the bytes.
        synchronized (lock) {
            if (exoMode && exoPlayer != null) {
                final ExoPlayer exo = exoPlayer;
                final int ms = Math.max(0, seconds * 1000);
                timer.post(() -> {
                    try {
                        exo.seekTo(ms);
                    } catch (Exception e) {
                        synchronized (lock) {
                            failLocked(VisionOsResolver.R_MEDIA_ERROR, "exo seek threw " + e);
                        }
                        pushAll();
                    }
                });
                nlog("[NATIVE_CMD] seek=" + seconds + ".000 exo videoId=" + videoId);
                return;
            }
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
            if (exoMode && exoPlayer != null) {
                stopExoLocked();
                nlog("[NATIVE_CMD] stop why=" + why + " videoId=" + vid + " engine=EXO");
            }
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

    // ---------- audio focus (duck on notifications, pause for other players) ----------

    /** Current app volume percent (0..300), single source of truth is appVolumePercent. */
    private float getCurrentVolumePercent() {
        synchronized (lock) {
            return appVolumePercent;
        }
    }

    /** Focus-safe volume write: reuses the existing 0..300 mapping. */
    private void applyVolumePercent(float pct) {
        Log.d(TAG, "[DUCK] applyVolumePercent pct=" + pct + " enhancer=" + (pct > 100));
        synchronized (lock) {
            appVolumePercent = Math.max(0, Math.round(pct));
            applyVolumeLocked();
        }
    }

    private void requestAudioFocus() {
        try {
            if (appCtx == null) return;
            if (focusManager == null) {
                focusManager = (AudioManager) appCtx.getSystemService(Context.AUDIO_SERVICE);
            }
            if (focusManager == null) return;
            if (focusListener == null) {
                focusListener = change -> onAudioFocusChange(change);
            }
            int result;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (focusRequest == null) {
                    AudioAttributes attrs = new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build();
                    focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                            .setAudioAttributes(attrs)
                            .setOnAudioFocusChangeListener(focusListener)
                            .setWillPauseWhenDucked(false)
                            .build();
                }
                result = focusManager.requestAudioFocus(focusRequest);
            } else {
                result = focusManager.requestAudioFocus(focusListener,
                        AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
            }
            if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Log.w(TAG, "[FOCUS] request denied");
                // Still attempt playback but log warning
            }
        } catch (Exception e) {
            Log.w(TAG, "[FOCUS] request failed: " + e);
        }
    }

    private void abandonAudioFocus() {
        try {
            if (focusManager == null) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (focusRequest != null) focusManager.abandonAudioFocusRequest(focusRequest);
            } else if (focusListener != null) {
                focusManager.abandonAudioFocus(focusListener);
            }
        } catch (Exception ignored) {}
    }

    private void acquireWakeLock() {
        try {
            if (appCtx == null) return;
            PowerManager pm = (PowerManager) appCtx.getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            if (wakeLock == null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MusicStream:playback");
                wakeLock.setReferenceCounted(false);
            }
            if (!wakeLockHeld) {
                wakeLock.acquire();
                wakeLockHeld = true;
                Log.d(TAG, "[WAKE] acquired");
            }
        } catch (Exception e) {
            Log.e(TAG, "[WAKE] acquire failed: " + e.getMessage());
        }
    }

    private void releaseWakeLock() {
        if (wakeLockHeld && wakeLock != null) {
            try {
                wakeLock.release();
                wakeLockHeld = false;
                Log.d(TAG, "[WAKE] released");
            } catch (Exception ignored) {}
        }
    }

    public static void releaseWakeLockIfHeld() {
        try {
            NativeAudioEngine e = INSTANCE;
            synchronized (e.lock) {
                if (e.wakeLockHeld && e.wakeLock != null) {
                    try { e.wakeLock.release(); } catch (Exception ignored) {}
                    e.wakeLockHeld = false;
                }
            }
        } catch (Exception ignored) {}
    }

    private void onAudioFocusChange(int change) {
        Log.d(TAG, "[FOCUS] change=" + change);
        switch (change) {
            case AudioManager.AUDIOFOCUS_LOSS:
                // Other app took focus permanently (YouTube, etc.)
                // Pause; user must resume manually
                pauseForFocusLoss(false);
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                // Other app took focus temporarily (call, video ad, etc.)
                // Pause; expect auto-resume
                pauseForFocusLoss(true);
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                // Notification or short sound — duck volume to 50%
                duckForNotification();
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                // Focus regained — restore or resume
                restoreFromFocusGain();
                break;
            default:
                break;
        }
    }

    private void pauseForFocusLoss(boolean autoResume) {
        MediaPlayer player;
        synchronized (lock) {
            if (mp == null) return;
            player = mp;
            try {
                preLossVolumePercent = getCurrentVolumePercent();
                pausedByFocus = true;
                resumeWhenFocusGained = autoResume;
                player.pause();
                setStateLocked(State.PAUSED);
                releaseWakeLock();
            } catch (Exception e) {
                Log.e(TAG, "[FOCUS] pause failed: " + e.getMessage());
                return;
            }
        }
        pushEvent("NATIVE_STATE", "state=PAUSED reason=audio_focus");
        pushAll();
    }

    private void restoreFromFocusGain() {
        if (isDucked) {
            isDucked = false;
            focusHandler.removeCallbacks(this::unduckIfNotGained);
        }
        // Restore volume first
        applyVolumePercent(preLossVolumePercent);
        boolean shouldResume;
        synchronized (lock) {
            shouldResume = pausedByFocus && resumeWhenFocusGained;
            if (shouldResume) {
                pausedByFocus = false;
                resumeWhenFocusGained = false;
            }
        }
        if (shouldResume) {
            synchronized (lock) {
                if (mp == null) return;
                try {
                    mp.start();
                    setStateLocked(State.PLAYING);
                    acquireWakeLock();
                } catch (Exception e) {
                    Log.e(TAG, "[FOCUS] resume failed: " + e.getMessage());
                    return;
                }
            }
            pushEvent("NATIVE_STATE", "state=PLAYING reason=audio_focus_resume");
            pushAll();
        }
    }

    private void duckForNotification() {
        Log.d(TAG, "[DUCK] duckForNotification start, currentVolumePercent="
                + getCurrentVolumePercent() + " isDucked=" + isDucked);
        Log.d(TAG, "[DUCK] duckForNotification called, preLoss saved=" + preLossVolumePercent
                + " current=" + getCurrentVolumePercent());
        synchronized (lock) {
            if (mp == null || state != State.PLAYING) return;
        }
        if (isDucked) return; // already ducked, don't re-save preLoss
        preLossVolumePercent = getCurrentVolumePercent();
        isDucked = true;
        applyVolumePercent(preLossVolumePercent * 0.5f);   // 50% of baseline
        // Schedule restore — some duck events don't send GAIN back
        focusHandler.removeCallbacks(this::unduckIfNotGained);
        focusHandler.postDelayed(this::unduckIfNotGained, 5000);
    }

    private void unduckIfNotGained() {
        Log.d(TAG, "[DUCK] unduck timer fired, state=" + state + " preLoss=" + preLossVolumePercent
                + " current=" + getCurrentVolumePercent());
        if (!isDucked) return;
        isDucked = false;
        synchronized (lock) {
            if (state != State.PLAYING) return;
        }
        Log.d(TAG, "[DUCK] restoring to " + preLossVolumePercent);
        applyVolumePercent(preLossVolumePercent);
    }

    /** Entry point for NotificationDuckService (package-visible on purpose). */
    public void onExternalNotification(String pkg) {
        Log.d(TAG, "[DUCK] onExternalNotification called pkg=" + pkg
                + " state=" + state + " mp=" + (mp != null)
                + " instanceHash=" + System.identityHashCode(this));
        synchronized (lock) {
            if (state != State.PLAYING || mp == null) return;
        }
        duckForNotification();
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

    /** PROTOTYPE B-revisi: resolve URL VisionOS saja (L1 di dalam resolver).
     *  Sukses -> PENDING diam. Gagal -> failLocked (skip) + trackFailed.
     *  L2/L3 tidak dijalankan (butuh playback). Tidak menyentuh mp. */
    private void resolveOnly(Context c, String vid, int gen) {
        // FASE 2.5 cache-first: COMPLETE -> skip network, Result synthetic dari .song.json
        if (c != null && vid != null && !vid.isEmpty() && SongCache.isComplete(c, vid)) {
            Log.d(TAG, "[RESOLVE] SKIP (cache complete) videoId=" + vid);
            VisionOsResolver.Result sr = buildSyntheticResult(c, vid);
            if (sr != null) {
                synchronized (lock) {
                    if (gen != playGen) return;
                    lastResolverDurationMs = sr.durationMs > 0
                            ? (int) Math.min(sr.durationMs, Integer.MAX_VALUE) : 0;
                    setStateLocked(State.IDLE);
                }
                startExoItem(vid, sr, gen);
                return;
            }
            // synthetic gagal -> fall through ke resolve biasa
        }
        VisionOsResolver.Result r;
        try {
            r = VisionOsResolver.resolve(vid);
        } catch (VisionOsResolver.ResolverException e) {
            synchronized (lock) {
                if (gen != playGen) return;
                stopExoLocked();
                failLocked(e.reason != null ? e.reason
                        : VisionOsResolver.R_RESOLVER_FAIL,
                        e.stage + " " + e.getMessage());
            }
            pushAll();
            return;
        }
        synchronized (lock) {
            if (gen != playGen) return;
            lastResolverDurationMs = r.durationMs > 0
                    ? (int) Math.min(r.durationMs, Integer.MAX_VALUE) : 0;
            setStateLocked(State.IDLE);
        }
        startExoItem(vid, r, gen);
    }

    /** FASE 2.5: Result synthetic dari .song.json untuk track COMPLETE. Tanpa network. */
    private static VisionOsResolver.Result buildSyntheticResult(Context c, String vid) {
        try {
            JSONObject rec = SongCache.getRecord(c, vid);
            if (rec == null) return null;
            String title = rec.optString("title", "");
            String artist = rec.optString("artist", "");
            String thumb = rec.optString("artworkThumb", "");
            if (thumb.isEmpty()) thumb = rec.optString("artworkUrl", "");
            double durSec = rec.optDouble("duration", 0);
            long durMs = durSec > 0 ? (long) (durSec * 1000.0) : 0;
            return new VisionOsResolver.Result(
                    "https://resolve/" + vid,
                    0, null, 0, null, false,
                    title, artist, thumb, durMs, false, 0);
        } catch (Exception e) {
            return null;
        }
    }

    private void armCacheFirst(String vid, String localhostUri) {
        if (vid == null || vid.isEmpty() || localhostUri == null) return;
        cacheFirstVideoId = vid;
        cacheFirstUri = localhostUri;
        cacheFirstActive = true;
        nlog("[GUARD] cacheFirst armed videoId=" + vid);
    }

    private void disarmCacheFirst() {
        cacheFirstActive = false;
    }

    /** Central URI decision: guard armed untuk vid -> localhost, else direct. */
    private String guardedUri(String vid, String directUrl) {
        if (cacheFirstActive && vid != null && vid.equals(cacheFirstVideoId)
                && cacheFirstUri != null) {
            return cacheFirstUri;
        }
        return directUrl;
    }

    /** Socket 3: mainkan URL resolve via ExoPlayer tunggal. No MediaSession.
     *  ExoPlayer wajib diakses di main thread: resolve jalan di worker,
     *  wiring dipost ke timer (main). */
    private void startExoItem(String vid, VisionOsResolver.Result r, int gen) {
        synchronized (lock) {
            if (gen != playGen) return;
        }
        timer.post(() -> startExoItemMain(vid, r, gen));
    }

    private void startExoItemMain(String vid, VisionOsResolver.Result r, int gen) {
        final ExoPlayer exo;
        final android.content.Context ctx;
        synchronized (lock) {
            if (gen != playGen) return;
            exoMode = true;
            try {
                exo = ExoPlayerHolder.getInstance(appCtx).getPlayer();
            } catch (Exception e) {
                failLocked(VisionOsResolver.R_MEDIA_ERROR, "exo holder " + e);
                pushAll();
                return;
            }
            exoPlayer = exo;
            attachExoListenerLocked(exo);
            durationMs = r.durationMs > 0
                    ? (int) Math.min(r.durationMs, Integer.MAX_VALUE) : 0;
            ctx = appCtx;
        }
        // Socket 7.3: HIT -> localhost final, MISS -> download + growing .part.
        // No SongCache/LocalStreamServer file change (caller only).
        boolean hit = false;
        java.io.File hitFile = null;
        long hitBytes = 0;
        try {
            if (ctx != null && vid != null && !vid.isEmpty()
                    && SongCache.isComplete(ctx, vid)) {
                java.io.File f = SongCache.audioFile(ctx, vid);
                if (f != null && f.isFile() && f.length() > 0) {
                    hit = true;
                    hitFile = f;
                    hitBytes = f.length();
                }
            }
        } catch (Exception ignored) {}
        String playUri = r.url;
        if (hit) {
            synchronized (lock) {
                if (gen != playGen) return;
                lastTotal = hitBytes;
                playFile = hitFile.getAbsolutePath();
                lastCache = "HIT";
                source = Source.CACHED_AUDIO;
            }
            try {
                LocalStreamServer srv;
                synchronized (lock) {
                    srv = ensureServer(ctx);
                }
                if (srv != null && hitBytes > 0) {
                    srv.register(vid, hitFile.getAbsolutePath(), hitBytes);
                    playUri = "http://127.0.0.1:" + srv.port() + "/local-stream/" + vid;
                    nlog("[CACHE] HIT videoId=" + vid + " bytes=" + hitBytes + " mode=EXO");
                    nlog("[EXO] using local videoId=" + vid + " uri=127.0.0.1 port=" + srv.port());
                    armCacheFirst(vid, playUri);
                } else {
                    nlog("[EXO] local proxy unavailable videoId=" + vid + " -> direct");
                    playUri = r.url;
                }
            } catch (Exception e) {
                nlog("[EXO] local proxy FAIL " + e + " -> direct");
                playUri = r.url;
            }
        } else {
            // Socket 7.3 write path: MISS -> download + growing .part + pre-buffer.
            // Pre-buffer async (worker wait, main play); main thread tak boleh block.
            final long total = clenOf(r.url);
            if (total <= 0) {
                synchronized (lock) {
                    if (gen == playGen) {
                        lastCache = "MISS";
                        source = Source.VISIONOS;
                    }
                }
                nlog("[CACHE] MISS total<=0 videoId=" + vid + " mode=EXO -> direct");
                disarmCacheFirst();
            } else {
                java.io.File partFile = null;
                try {
                    if (ctx != null) partFile = SongCache.partFile(ctx, vid);
                } catch (Exception ignored) {}
                final String fUrl = r.url;
                final long fTotal = total;
                final android.content.Context fCtx = ctx;
                final String fVid = vid;
                if (fCtx != null && !SongCache.isDownloading(fVid)) {
                    new Thread(() -> {
                        try {
                            SongCache.download(fCtx, fVid, fUrl, fTotal);
                        } catch (Exception ignored) {}
                    }).start();
                }
                String growingUri = null;
                try {
                    LocalStreamServer srv;
                    synchronized (lock) {
                        if (gen != playGen) return;
                        lastTotal = fTotal;
                        lastUrl = fUrl;
                        playFile = partFile != null ? partFile.getAbsolutePath() : "";
                        lastCache = "MISS";
                        source = Source.CACHED_AUDIO;
                        srv = ensureServer(ctx);
                    }
                    if (srv != null && partFile != null) {
                        srv.register(fVid, partFile.getAbsolutePath(), fTotal);
                        growingUri = "http://127.0.0.1:" + srv.port() + "/local-stream/" + fVid;
                        armCacheFirst(fVid, growingUri);
                    }
                } catch (Exception e) {
                    nlog("[EXO] growing proxy FAIL " + e + " -> direct");
                }
                if (growingUri != null) {
                    // Fix 7.3 stutter: jangan play saat 0% (hold -> underrun).
                    // Pre-buffer 2 chunk di worker, play via main thread setelah need tiba.
                    final String gUri = growingUri;
                    final long gNeed = Math.min(fTotal,
                            2 * SongCache.chunkEndFor(0,
                                    (int) Math.min(r.durationMs, Integer.MAX_VALUE), fTotal));
                    nlog("[CACHE] MISS videoId=" + vid + " total=" + fTotal
                            + " mode=EXO -> growing local prebuffer need=" + gNeed);
                    synchronized (lock) {
                        if (gen != playGen) return;
                        setStateLocked(State.BUFFERING);
                        currentMs = 0;
                    }
                    pushAll();
                    new Thread(() -> {
                        if (!waitForBytes(fCtx, fVid, gNeed, gen, fTotal)) return;
                        timer.post(() -> playExoUri(vid, r, gen, exo, gUri));
                    }).start();
                    return;
                } else {
                    synchronized (lock) {
                        if (gen == playGen) source = Source.VISIONOS;
                    }
                    playUri = r.url;
                    nlog("[CACHE] MISS videoId=" + vid + " proxy unavailable -> direct");
                    disarmCacheFirst();
                }
            }
        }
        playExoUri(vid, r, gen, exo, playUri);
    }

    /** Eksekusi play Exo di main thread (sync path + deferred pre-buffer). */
    private void playExoUri(String vid, VisionOsResolver.Result r, int gen,
            ExoPlayer exo, String playUri) {
        synchronized (lock) {
            if (gen != playGen) return;
        }
        String finalUri = guardedUri(vid, playUri);
        if (!finalUri.equals(playUri)) {
            nlog("[GUARD] cacheFirst forced localhost videoId=" + vid);
        }
        // 7.3.9: window penuh (n>=2) sudah terpasang duluan (prebuffer lambat)
        // -> JANGAN timpa dengan single (itu bunuh tombol next). Langsung
        // arm-wait; timeout mainkan timeline penuh yang sudah terpasang.
        boolean fullInstalled = false;
        synchronized (lock) {
            if (gen != playGen) return;
            fullInstalled = vid.equals(windowAppliedVid) && gen == windowAppliedGen;
        }
        if (fullInstalled) {
            nlog("[WINDOW] full already installed, skip single gen=" + gen
                    + " videoId=" + vid);
        } else
        try {
            MediaMetadata md = new MediaMetadata.Builder()
                    .setTitle(r.title != null && !r.title.isEmpty() ? r.title : title)
                    .setArtist(r.author != null && !r.author.isEmpty() ? r.author : artist)
                    .setArtworkUri((r.thumbnail != null && !r.thumbnail.isEmpty())
                            ? Uri.parse(r.thumbnail) : null)
                    .build();
            MediaItem.Builder ib = new MediaItem.Builder()
                    .setUri(finalUri)
                    .setMediaMetadata(md);
            if (r.durationMs > 0) {
                ib.setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                        .setEndPositionMs(r.durationMs)
                        .build());
            }
            exo.setMediaItem(ib.build());
            exo.prepare();
            // FIX 4 (7.3.6): cegah autostart. playWhenReady inherited true
            // dari track sebelumnya -> Exo main sendiri sebelum window apply
            // (track 2 play duluan). Play hanya via startExoPlayback.
            try { exo.setPlayWhenReady(false); } catch (Exception ignored) {}
        } catch (Exception e) {
            synchronized (lock) {
                if (gen != playGen) return;
                failLocked(VisionOsResolver.R_MEDIA_ERROR, "exo start " + e);
            }
            pushAll();
            return;
        }
        synchronized (lock) {
            if (gen != playGen) return;
            setStateLocked(State.BUFFERING);
            currentMs = 0;
            // Socket 7.3.4: window DULU, play BELAKANGAN. Tunggu window
            // bounded; timeout main single. Post-play same-vid tidak apply.
            windowWaitGen = gen;
            windowWaitVid = vid;
        }
        nlog("[WINDOW_WAIT] armed gen=" + gen + " videoId=" + vid);
        nlog("[NATIVE_CMD] prepared videoId=" + vid + " engine=EXO waiting-window"
                + " uri=" + (finalUri != null && finalUri.contains("127.0.0.1") ? "127.0.0.1-local" : "direct"));
        pushAll();
        tryApplyWindow();
        timer.postDelayed(() -> {
            synchronized (lock) {
                if (gen != playGen) {
                    nlog("[WINDOW_TO] timeout stale gen=" + gen + " playGen=" + playGen);
                    return;
                }
            }
            // FIX 2 (7.3.6): timeout dilarang fire saat sudah bunyi.
            try {
                if (exo.isPlaying() || exo.getPlayWhenReady()) {
                    nlog("[WINDOW_TO] skip: already playing gen=" + gen);
                    synchronized (lock) {
                        if (gen == windowWaitGen) {
                            windowWaitGen = -1;
                            windowWaitVid = null;
                        }
                    }
                    return;
                }
            } catch (Exception ignored) {}
            boolean fire = false;
            String nofire = "";
            synchronized (lock) {
                if (gen == playGen && gen == windowWaitGen && vid.equals(windowWaitVid)
                        && vid.equals(videoId)) {
                    fire = true;
                    windowTimeoutGen = gen;
                } else {
                    nofire = "playGen=" + playGen + " waitGen=" + windowWaitGen
                            + " waitVid=" + windowWaitVid + " curVid=" + videoId;
                }
                if (gen == windowWaitGen) {
                    windowWaitGen = -1;
                    windowWaitVid = null;
                }
            }
            if (fire) {
                nlog("[WINDOW_TO] timeout fire gen=" + gen + " videoId=" + vid + " -> single");
                timer.post(() -> startExoPlayback(vid, r.host, gen, exo, finalUri, "timeout-single"));
            } else {
                nlog("[WINDOW_TO] timeout no-fire gen=" + gen + " " + nofire);
            }
        }, 1500);
    }

    /** Start playback Exo + post tail. Dipakai timeout-single + window pre-play. */
    private void startExoPlayback(String vid, String host, int gen,
            ExoPlayer exo, String uri, String why) {
        synchronized (lock) {
            if (gen != playGen || !vid.equals(videoId)) {
                nlog("[WINDOW_PLAY] skip stale gen=" + gen + " playGen=" + playGen
                        + " videoId=" + vid + " why=" + why);
                return;
            }
        }
        nlog("[WINDOW_PLAY] start why=" + why + " gen=" + gen + " videoId=" + vid);
        try {
            exo.play();
        } catch (Exception e) {
            synchronized (lock) {
                if (gen != playGen) return;
                failLocked(VisionOsResolver.R_MEDIA_ERROR, "exo start " + e);
            }
            pushAll();
            return;
        }
        synchronized (lock) {
            if (gen != playGen) return;
            setStateLocked(State.BUFFERING);
            currentMs = 0;
        }
        nlog("[NATIVE_CMD] play videoId=" + vid + " mode=VISIONOS engine=EXO host=" + host
                + " uri=" + (uri != null && uri.contains("127.0.0.1") ? "127.0.0.1-local" : "direct")
                + " why=" + why + " playerHash=" + System.identityHashCode(exo));
        pushAll();
        startExoTicker();
        // Socket 4: session service untuk notif 1002 (guard VISIONOS implisit:
        // jalur ini cuma dari play() VISIONOS).
        try {
            if (appCtx != null) {
                android.content.Intent svc = new android.content.Intent(
                        appCtx, ExoMediaSessionService.class);
                androidx.core.content.ContextCompat.startForegroundService(appCtx, svc);
                nlog("[NATIVE_CMD] session service started engine=EXO");
            }
        } catch (Exception e) {
            nlog("[NATIVE_CMD] session service start FAIL " + e);
        }
    }

    private void attachExoListenerLocked(final ExoPlayer exo) {
        if (exoListenerAttached) return;
        exoListenerAttached = true;
        exo.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int st) {
                synchronized (lock) {
                    if (exoPlayer != exo) return;
                    if (st == Player.STATE_BUFFERING) {
                        setStateLocked(State.BUFFERING);
                        pushAll();
                    } else if (st == Player.STATE_ENDED) {
                        try { currentMs = (int) exo.getCurrentPosition(); }
                        catch (Exception ignored) {}
                        setStateLocked(State.ENDED);
                        stopExoTicker();
                        nlog("[NATIVE_STATE] state=ENDED videoId=" + videoId + " engine=EXO");
                        pushAll();
                    }
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                synchronized (lock) {
                    if (exoPlayer != exo) return;
                    if (isPlaying) {
                        setStateLocked(State.PLAYING);
                        nlog("[NATIVE_STATE] state=PLAYING videoId=" + videoId + " engine=EXO");
                    } else if (state == State.PLAYING) {
                        setStateLocked(State.PAUSED);
                        nlog("[NATIVE_STATE] state=PAUSED videoId=" + videoId + " engine=EXO");
                    } else {
                        return;
                    }
                    pushAll();
                }
            }

            @Override
            public void onMediaItemTransition(
                    androidx.media3.common.MediaItem mediaItem, int reason) {
                // Socket 5: Exo dilarang auto-advance sendiri. Pause, kasih
                // ke JS (nextTrack -> window baru). Abaikan saat apply window.
                boolean intercept = false;
                synchronized (lock) {
                    if (exoPlayer != exo || windowApplying) return;
                    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                        intercept = true;
                    }
                }
                if (!intercept) return;
                nlog("[EXO_SOCKET5] auto-advance intercepted videoId=" + videoId);
                try { exo.pause(); } catch (Exception ignored) {}
                pushEvent("autoAdvance", null);
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                String msg = error != null ? String.valueOf(error.getMessage()) : "?";
                synchronized (lock) {
                    if (exoPlayer != exo) return;
                    stopExoLocked();
                    failLocked(VisionOsResolver.R_MEDIA_ERROR, "exo error " + msg);
                }
                pushAll();
            }
        });
    }

    private final Runnable exoTicker = new Runnable() {
        @Override public void run() {
            timer.removeCallbacks(this);
            boolean keep = false;
            synchronized (lock) {
                if (exoMode && exoPlayer != null) {
                    try { currentMs = (int) exoPlayer.getCurrentPosition(); }
                    catch (Exception ignored) {}
                    long playerDurationMs = 0;
                    try { playerDurationMs = exoPlayer.getDuration(); } catch (Exception ignored) {}
                    if (playerDurationMs > 0 && playerDurationMs <= Integer.MAX_VALUE) {
                        durationMs = (int) playerDurationMs;
                    } else if (lastResolverDurationMs > 0) {
                        durationMs = lastResolverDurationMs;
                    } else if (lastWindowDurationMs > 0) {
                        durationMs = lastWindowDurationMs;
                    }
                    pushEvent("timeUpdate", null);
                    keep = true;
                }
            }
            if (keep) timer.postDelayed(this, 500);
        }
    };

    private void startExoTicker() {
        timer.removeCallbacks(exoTicker);
        timer.postDelayed(exoTicker, 500);
    }

    private void stopExoTicker() {
        timer.removeCallbacks(exoTicker);
    }

    /** Hentikan audio Exo saat ganti track/gagal. Player tetap (reuse).
     *  Service session ikut berhenti (notif 1002 hilang). */
    private void stopExoLocked() {
        disarmCacheFirst();
        if (exoPlayer != null) {
            final ExoPlayer exo = exoPlayer;
            timer.post(() -> {
                try { exo.stop(); } catch (Exception ignored) {}
                try { exo.clearMediaItems(); } catch (Exception ignored) {}
            });
        }
        stopExoTicker();
        exoMode = false;
        try {
            if (appCtx != null) {
                appCtx.stopService(new android.content.Intent(appCtx, ExoMediaSessionService.class));
            }
        } catch (Exception ignored) {}
    }

    /**
     * Socket 5: JS owner queue, native cermin window
     * {items:[{videoId,title,artist,thumbnail,duration}], currentIndex}.
     * URL di-resolve di sini (JS tak punya URL). Timeline multi-item bikin
     * tombol prev/next default muncul + gate framework lolos.
     */
    public void setQueueWindow(String windowJson) {
        int mode = StreamSettings.MODE_IFRAME;
        try {
            if (appCtx != null) mode = StreamSettings.getStreamMode(appCtx);
        } catch (Exception ignored) {}
        if (mode != StreamSettings.MODE_VISIONOS) return;
        if (windowJson == null || windowJson.isEmpty()) return;
        final java.util.List<String> vids = new java.util.ArrayList<>();
        final java.util.List<String> titles = new java.util.ArrayList<>();
        final java.util.List<String> artists = new java.util.ArrayList<>();
        final java.util.List<String> thumbs = new java.util.ArrayList<>();
        final java.util.List<Double> durs = new java.util.ArrayList<>();
        final int[] curIdx = new int[]{-1};
        try {
            JSONObject o = new JSONObject(windowJson);
            JSONArray items = o.optJSONArray("items");
            if (items == null || items.length() == 0) return;
            curIdx[0] = o.optInt("currentIndex", -1);
            if (curIdx[0] < 0 || curIdx[0] >= items.length()) return;
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it == null || it.optString("videoId", "").isEmpty()) return;
                vids.add(it.optString("videoId", ""));
                titles.add(it.optString("title", ""));
                artists.add(it.optString("artist", ""));
                thumbs.add(it.optString("thumbnail", ""));
                durs.add(it.optDouble("duration", 0));
            }
        } catch (Exception e) {
            nlog("[EXO_SOCKET5] setQueueWindow parse FAIL " + e);
            return;
        }
        final String curVid = vids.get(curIdx[0]);
        synchronized (lock) {
            // Simpan selalu (pending); diterapkan bila video cocok + exo siap.
            // Ini tangani race push-vs-play (window datang sebelum NAE kenal track).
            pendVids = new java.util.ArrayList<>(vids);
            pendTitles = new java.util.ArrayList<>(titles);
            pendArtists = new java.util.ArrayList<>(artists);
            pendThumbs = new java.util.ArrayList<>(thumbs);
            pendDurs = new java.util.ArrayList<>(durs);
            pendIdx = curIdx[0];
            windowSeq++;
            nlog("[EXO_SOCKET5] setQueueWindow stored n=" + vids.size()
                    + " cur=" + curIdx[0] + " videoId=" + curVid + " seq=" + windowSeq);
        }
        tryApplyWindow();
    }

    /** Terapkan pending window bila video cocok + exo siap. Idempoten. */
    private void tryApplyWindow() {
        final java.util.List<String> vids;
        final java.util.List<String> titles;
        final java.util.List<String> artists;
        final java.util.List<String> thumbs;
        final java.util.List<Double> durs;
        final int curIdx;
        final int gen;
        final int seq;
        final String curVid;
        synchronized (lock) {
            if (!exoMode || exoPlayer == null || pendVids == null || pendIdx < 0
                    || pendIdx >= pendVids.size()) {
                return;
            }
            curVid = pendVids.get(pendIdx);
            if (videoId == null || videoId.isEmpty() || !videoId.equals(curVid)) return;
            // Socket 7.3.4 step 5: 1 vid = 1 apply; post-play same-vid tidak apply.
            if (curVid.equals(windowAppliedVid) && playGen == windowAppliedGen) {
                nlog("[WINDOW_SKIP] applied-vid gen=" + playGen + " videoId=" + curVid);
                return;
            }
            if (curVid.equals(videoId)
                    && (state == State.PLAYING || state == State.PAUSED)) {
                nlog("[WINDOW_SKIP] started state=" + state + " videoId=" + curVid);
                return;
            }
            vids = new java.util.ArrayList<>(pendVids);
            titles = new java.util.ArrayList<>(pendTitles);
            artists = new java.util.ArrayList<>(pendArtists);
            thumbs = new java.util.ArrayList<>(pendThumbs);
            durs = new java.util.ArrayList<>(pendDurs);
            curIdx = pendIdx;
            gen = playGen;
            seq = windowSeq;
        }
        nlog("[EXO_SOCKET5] setQueueWindow n=" + vids.size() + " cur=" + curIdx
                + " videoId=" + curVid + " seq=" + seq);
        new Thread(() -> resolveWindow(vids, titles, artists, thumbs, durs,
                curIdx, curVid, gen, seq)).start();
    }

    private void resolveWindow(java.util.List<String> vids, java.util.List<String> titles,
            java.util.List<String> artists, java.util.List<String> thumbs,
            java.util.List<Double> durs, int curIdx, String curVid, int gen, int seq) {
        final java.util.List<VisionOsResolver.Result> results =
                new java.util.ArrayList<>(vids.size());
        for (int i = 0; i < vids.size(); i++) results.add(null);
        java.util.List<Thread> workers = new java.util.ArrayList<>();
        for (int i = 0; i < vids.size(); i++) {
            final int fi = i;
            Thread t = new Thread(() -> {
                try {
                    results.set(fi, VisionOsResolver.resolve(vids.get(fi)));
                } catch (Exception e) {
                    nlog("[EXO_SOCKET5] window resolve FAIL " + vids.get(fi) + " " + e);
                }
            });
            workers.add(t);
            t.start();
        }
        for (Thread t : workers) {
            try { t.join(25000); } catch (InterruptedException e) { return; }
        }
        synchronized (lock) {
            if (gen != playGen || seq != windowSeq || !curVid.equals(videoId)) return;
            if (results.get(curIdx) == null) {
                nlog("[EXO_SOCKET5] window dropped (current unresolvable)");
                return;
            }
        }
        final java.util.List<MediaItem> items = new java.util.ArrayList<>();
        int resolvedCurrentDurationMs = 0;
        for (int i = 0; i < vids.size(); i++) {
            VisionOsResolver.Result r = results.get(i);
            if (r == null || r.url == null || r.url.isEmpty()) continue;
            if (i == curIdx && r.durationMs > 0) {
                resolvedCurrentDurationMs = (int) Math.min(r.durationMs, Integer.MAX_VALUE);
            }
            MediaMetadata md = new MediaMetadata.Builder()
                    .setTitle(titles.get(i).isEmpty() ? r.title : titles.get(i))
                    .setArtist(artists.get(i).isEmpty() ? r.author : artists.get(i))
                    .setArtworkUri(thumbs.get(i).isEmpty()
                            ? (r.thumbnail != null && !r.thumbnail.isEmpty()
                                    ? Uri.parse(r.thumbnail) : null)
                            : Uri.parse(thumbs.get(i)))
                    .build();
            String itemUri = guardedUri(vids.get(i), r.url);
            MediaItem.Builder ib = new MediaItem.Builder()
                    .setUri(itemUri)
                    .setMediaMetadata(md);
            // Socket 5.2 clip fix (C): resolver ms otoritatif + bounded 1 jam.
            // durs JS campur format; abaikan yang aneh, tanpa clip = durasi natural.
            long dd = 0;
            String ddSrc = "none";
            if (r.durationMs > 0 && r.durationMs <= 3600L * 1000L) {
                dd = r.durationMs;
                ddSrc = "resolver";
            } else {
                double ds = durs.get(i);
                long cand = ds > 0 ? (long) (ds * 1000) : 0;
                if (cand > 0 && cand <= 3600L * 1000L) {
                    dd = cand;
                    ddSrc = "js";
                }
            }
            if (dd > 0) {
                ib.setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                        .setEndPositionMs(dd)
                        .build());
            }
            nlog("[EXO_SOCKET5] window item " + i + " dd=" + dd + " src=" + ddSrc);
            items.add(ib.build());
        }
        // Hitung ulang index current setelah drop item gagal.
        int adjIdx = -1;
        {
            String cv = curVid;
            // Samakan urutan: cari posisi curVid di daftar sukses.
            java.util.List<String> okVids = new java.util.ArrayList<>();
            for (int i = 0; i < vids.size(); i++) {
                if (results.get(i) != null && results.get(i).url != null
                        && !results.get(i).url.isEmpty()) okVids.add(vids.get(i));
            }
            adjIdx = okVids.indexOf(cv);
            if (adjIdx < 0) return;
        }
        synchronized (lock) {
            if (gen != playGen || seq != windowSeq || !curVid.equals(videoId)) return;
            lastWindowDurationMs = resolvedCurrentDurationMs;
            if (lastWindowDurationMs > 0) durationMs = lastWindowDurationMs;
        }
        final java.util.List<MediaItem> fItems = items;
        final int fIdx = adjIdx;
        timer.post(() -> applyWindowMain(fItems, fIdx, curVid, gen, seq));
    }

    private void applyWindowMain(java.util.List<MediaItem> items, int curIdx,
            String curVid, int gen, int seq) {
        final ExoPlayer exo;
        final boolean wasPlaying;
        final long pos;
        nlog("[WINDOW_APPLY] entry videoId=" + curVid + " gen=" + gen + " seq=" + seq);
        synchronized (lock) {
            if (gen != playGen || seq != windowSeq || !curVid.equals(videoId)) return;
            if (!exoMode || exoPlayer == null || items.isEmpty()) return;
            if (seq == windowAppliedSeq) return;
            // 7.3.9 barrier: MISS butuh window n>=2 (tombol next notif) sebelum
            // play. n<2 = queue belum siap -> TAHAN (timeout main single).
            // HIT langsung (file lokal): single-queue offline tidak kena delay.
            if (items.size() < 2 && !"HIT".equals(lastCache)) {
                nlog("[WINDOW_HOLD] n=" + items.size() + " <2, waiting gen=" + gen
                        + " videoId=" + curVid);
                return;
            }
            // FIX 3 (7.3.6): 1 gen = 1 apply. Push dupe same-gen dilarang
            // apply ulang (radio-fill dupe worker).
            if (gen == windowAppliedGen && curVid.equals(windowAppliedVid)) {
                nlog("[WINDOW_APPLY] skip: already applied gen=" + gen + " videoId=" + curVid);
                return;
            }
            if (gen == windowTimeoutGen) {
                nlog("[WINDOW_F2] SKIP: timeout-single already fired gen=" + gen);
                return;
            }
            exo = exoPlayer;
            try {
                wasPlaying = exo.isPlaying();
                pos = Math.max(0, exo.getCurrentPosition());
            } catch (Exception e) {
                return;
            }
            // Penanda appliedvid/gen/seq HANYA di-set bila install benar
            // terjadi (di bawah). SKIP tidak boleh menandai: penanda tanpa
            // install = timeline basi + single di-skip + timeout di-skip
            // = stuck selamanya (gen=3 case).
            windowApplying = true;
        }
        // FIX 7.3.2: jangan timpa URI item yang sedang bunyi.
        // localhost->direct = reconnect + gap ~700ms. Item current ikut URI
        // yang sedang main; prev/next tetap direct.
        try {
            MediaItem curItem = exo.getCurrentMediaItem();
            String curUri = (curItem != null && curItem.localConfiguration != null
                    && curItem.localConfiguration.uri != null)
                    ? curItem.localConfiguration.uri.toString() : null;
            if (curUri != null && curUri.contains("127.0.0.1")
                    && curIdx >= 0 && curIdx < items.size()) {
                MediaItem fixed = items.get(curIdx).buildUpon()
                        .setUri(Uri.parse(curUri)).build();
                items.set(curIdx, fixed);
                nlog("[EXO_SOCKET5] window current kept localhost (no URI swap)");
            }
        } catch (Exception e) {
            nlog("[EXO_SOCKET5] window keep-local FAIL " + e);
        }
        // ===== F1 (7.3.6): TOCTOU FIX, tanpa bypass =====
        // isPlaying/pwr -> SKIP SELALU (reset saat bunyi = gap).
        // READY -> SKIP kecuali pre-play pending (prepare selesai duluan).
        boolean prePlay;
        synchronized (lock) {
            prePlay = (gen == windowWaitGen && curVid.equals(windowWaitVid));
        }
        int pbState = -1;
        boolean playing = false;
        boolean pwr = false;
        try {
            pbState = exo.getPlaybackState();
            playing = exo.isPlaying();
            pwr = exo.getPlayWhenReady();
        } catch (Exception ignored) {}
        nlog("[WINDOW_F1] pre-setMediaItems gen=" + gen + " state=" + pbState
                + " isPlaying=" + playing + " playWhenReady=" + pwr + " prePlay=" + prePlay);
        if (playing || pwr) {
            nlog("[WINDOW_F1] SKIP: isPlaying=" + playing + " playWhenReady=" + pwr);
            return;
        }
        if (pbState == Player.STATE_READY && !prePlay) {
            nlog("[WINDOW_F1] SKIP: ready without pre-play pending");
            return;
        }
        nlog("[WINDOW_F1] PASS guard, proceeding to setMediaItems");
        try {
            // 7.3.9: tahan autostart warisan (pwr=true dari track lama -> Exo
            // main sendiri saat prepare, tanpa WINDOW_PLAY/session/notif).
            // Play eksplisit menyusul via window-preplay / timeout-single.
            try { exo.setPlayWhenReady(false); } catch (Exception ignored) {}
            exo.setMediaItems(items, curIdx, pos);
            exo.prepare();
            // Install terjadi -> baru tandai (dupe suppression jujur).
            synchronized (lock) {
                if (gen == playGen && curVid.equals(videoId)) {
                    windowAppliedSeq = seq;
                    windowAppliedVid = curVid;
                    windowAppliedGen = gen;
                }
            }
            if (wasPlaying) {
                exo.play();
                nlog("[EXO_SOCKET5] window applied n=" + items.size()
                        + " cur=" + curIdx + " resume pos=" + pos);
            } else {
                boolean pending = false;
                synchronized (lock) {
                    if (gen == windowWaitGen && curVid.equals(windowWaitVid)) {
                        pending = true;
                        windowWaitGen = -1;
                        windowWaitVid = null;
                        nlog("[WINDOW_WAIT] cleared by apply gen=" + gen);
                    }
                }
                if (pending) {
                    String appliedUri = "";
                    try {
                        MediaItem ai = items.get(curIdx);
                        if (ai != null && ai.localConfiguration != null
                                && ai.localConfiguration.uri != null) {
                            appliedUri = ai.localConfiguration.uri.toString();
                        }
                    } catch (Exception ignored) {}
                    nlog("[EXO_SOCKET5] window applied n=" + items.size()
                            + " cur=" + curIdx + " pre-play, starting");
                    startExoPlayback(curVid, "window", gen, exo, appliedUri, "window-preplay");
                } else {
                    nlog("[EXO_SOCKET5] window applied n=" + items.size()
                            + " cur=" + curIdx + " stays paused pos=" + pos);
                }
            }
        } catch (Exception e) {
            nlog("[EXO_SOCKET5] window apply FAIL " + e);
        } finally {
            synchronized (lock) {
                windowApplying = false;
            }
        }
        pushAll();
    }

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
            } else {
                long idleMs = now - lastMoveAt;
                if (idleMs > SongCache.TIMEOUT_MS && !SongCache.isDownloading(vid)) {
                    synchronized (lock) {
                        if (gen != playGen) return false;
                        failLocked(VisionOsResolver.R_TIMEOUT, "fill stalled");
                    }
                    pushAll();
                    return false;
                }
                // G1 watchdog: idle lama + writer "hidup" tapi byte berhenti
                // (hang) -> fail juga, kecuali FULL offline-download.
                // Slow network (byte ngalir) tak pernah sampai sini karena
                // lastMoveAt ikut gerak tiap byte baru.
                if (idleMs > WATCHDOG_IDLE_MS && !SongCache.isFull(vid)) {
                    nlog("[WATCHDOG] hang detected videoId=" + vid
                            + " idle=" + idleMs + "ms need=" + need + " have=" + have);
                    synchronized (lock) {
                        if (gen != playGen) return false;
                        failLocked(VisionOsResolver.R_TIMEOUT, "watchdog no bytes 60s");
                    }
                    pushAll();
                    return false;
                }
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                return false;
            }
        }
    }
    /**
     * Sliding chunk window: chunk containing curMs, configurable prefetch ahead, hard stop.
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
        return Math.min(total, edge + CHUNK_WINDOW_SIZE * one);
    }

    /** Set window for a position; (re)starts resume-aware writer when behind. */
    private void setWindow(String vid, String url, long total, int curMs,
            int durMs, Context ac) {
        windowChunk = chunkIdxForPos(curMs, durMs, total);
        windowEnd = windowEndForPos(curMs, durMs, total);
        nlog("[NAE] window set chunk=" + windowChunk + " end=" + windowEnd
                + " curMs=" + curMs + " total=" + total + " writerState="
                + (SongCache.isDownloading(vid) ? "RUNNING" : (SongCache.isFull(vid) ? "COMPLETE" : "IDLE")));
        if (ac == null || total <= 0) return;
        if (SongCache.hasBytes(ac, vid, Math.min(windowEnd, total))) {
            if (SongCache.isDownloading(vid) && !SongCache.isFull(vid)) {
                SongCache.pauseDownload(vid);
                nlog("[NAE] writer paused: reason=window already cached to end=" + windowEnd);
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

    /** Force-advance chunk window on LSS hold timeout (called from proxy thread). */
    public static void requestWindowAdvance(String vid) {
        final NativeAudioEngine engine = INSTANCE;
        if (vid == null || vid.isEmpty()) return;
        engine.timer.post(() -> {
            synchronized (engine.lock) {
                if (!vid.equals(engine.videoId)) return;
                if (engine.lastTotal <= 0 || engine.durationMs <= 0 || engine.appCtx == null) return;
                if (engine.state == State.IDLE || engine.state == State.STOPPED
                        || engine.state == State.ERROR || engine.state == State.ENDED) return;
                int cur = engine.currentMs;
                try {
                    if (engine.mp != null) cur = engine.mp.getCurrentPosition();
                } catch (Exception ignored) {}
                engine.setWindow(vid, engine.lastUrl, engine.lastTotal, cur,
                        engine.durationMs, engine.appCtx);
                nlog("[NAE] window advance on demand videoId=" + vid);
            }
        });
    }

    /** startPath with generation + start offset (initial fill and seek-refill share it). */
    private void startPathAt(String path, Source src, Context c, int gen, int startMs) {
        synchronized (lock) {
            if (gen != playGen) return;
            consecutiveRecoveryCount = 0;
            lastRecoveryAttemptMs = 0;
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
            consecutiveRecoveryCount = 0;
            lastRecoveryAttemptMs = 0;
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
                requestAudioFocus();
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
                acquireWakeLock();
                nlog("[NATIVE_STATE] state=PLAYING videoId=" + videoId
                        + " duration=" + (durationMs / 1000.0));
            }
            pushAll();
        });
        mp.setOnCompletionListener(p -> {
            long pos;
            long dur;
            synchronized (lock) {
                int callbackPosition = -1;
                int callbackDuration = -1;
                try { callbackPosition = p.getCurrentPosition(); } catch (Exception ignored) {}
                try { callbackDuration = p.getDuration(); } catch (Exception ignored) {}
                nlog("[AUTONEXT_DIAG] MediaPlayer.onCompletion fired. currentPosition="
                        + callbackPosition + " duration=" + callbackDuration
                        + " videoId=" + videoId + " writerState=" + writerState(videoId));
                pos = callbackPosition;
                dur = durationMs > 0 ? durationMs : callbackDuration;
            }
            long epsilon = 3000;
            if (dur > 0 && pos >= dur - epsilon) {
                // Genuine end
                Log.d(TAG, "[ENGINE] Genuine onCompletion: pos=" + pos + " dur=" + dur);
                synchronized (lock) {
                    try {
                        currentMs = p.getDuration();
                    } catch (Exception ignored) {}
                    if (state != State.ERROR) {
                        nlog("[AUTONEXT_DIAG] ENDED fired. reason=MediaPlayer.onCompletion"
                                + " currentMs=" + currentMs + " durationMs=" + durationMs
                                + " state=" + state + " videoId=" + videoId
                                + " writerState=" + writerState(videoId) + " bufferPos=n/a");
                        setStateLocked(State.ENDED);
                        nlog("[NATIVE_STATE] state=ENDED videoId=" + videoId);
                    }
                }
                pushAll();
                return;
            }
            // Premature completion: debounce loop, then recover on worker thread
            // (blocking prepare must never run on the callback thread).
            final long lastMs = pos;
            final long trackDur = dur;
            final String recVid;
            final int recGen;
            final String recDs;
            final int recCount;
            synchronized (lock) {
                long now = System.currentTimeMillis();
                if (now - lastRecoveryAttemptMs < RECOVERY_DEBOUNCE_MS) {
                    consecutiveRecoveryCount++;
                } else {
                    consecutiveRecoveryCount = 0;
                }
                lastRecoveryAttemptMs = now;
                if (consecutiveRecoveryCount > MAX_CONSECUTIVE_RECOVERIES) {
                    Log.e(TAG, "[ENGINE] Recovery loop detected, giving up. Firing error.");
                    consecutiveRecoveryCount = 0;
                    long epos = pos;
                    long edur = dur;
                    try { epos = p.getCurrentPosition(); } catch (Exception ignored) {}
                    try { edur = p.getDuration(); } catch (Exception ignored) {}
                    firePrematureError(epos, edur, "RECOVERY_LOOP");
                    return;
                }
                recCount = consecutiveRecoveryCount;
                recVid = videoId;
                recGen = playGen;
                if (streamServer != null && lastTotal > 0) {
                    recDs = "http://127.0.0.1:" + streamServer.port() + "/local-stream/" + videoId;
                } else {
                    recDs = playFile;
                }
                recoveryAttempts = 0;
                lastRecoveryStatus = "attempting";
            }
            double pct = trackDur > 0 ? (100.0 * lastMs / trackDur) : 0;
            Log.e(TAG, "[ENGINE] Premature onCompletion: pos=" + lastMs + " dur=" + trackDur
                    + " (" + String.format("%.1f", pct) + "%) videoId=" + recVid
                    + " consecutive=" + recCount);
            pushRecoveryEvent("attempting", recVid, lastMs, trackDur, null);
            final int skipSoft = recCount > 1 ? 1 : 0;
            new Thread(() -> attemptPrematureRecovery(recVid, recGen, recDs, lastMs, trackDur, skipSoft)).start();
        });
        mp.setOnErrorListener((p, what, extra) -> {
            synchronized (lock) {
                nlog("[AUTONEXT_DIAG] MediaPlayer.onError what=" + what + " extra=" + extra
                        + " currentPosition=" + safePosition(p) + " duration=" + safeDuration(p)
                        + " videoId=" + videoId + " state=" + state
                        + " writerState=" + writerState(videoId));
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
        disarmCacheFirst();
        timer.removeCallbacks(ticker);
        abandonAudioFocus();
        releaseWakeLock();
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
        releaseWakeLock();
        releaseLocked();
        state = State.ERROR;
        failReason = reason;
        failMessage = msg;
        nlog("[FALLBACK] START reason=" + reason + " msg=" + msg
                + " videoId=" + videoId + " action=skip");
        // PROTOTYPE bridge: explicit gagal event untuk JS (FASE C: skip,
        // bukan iframe). ERROR state + pushAll existing tetap (FASE C ubah).
        try { pushEvent("trackFailed", null); } catch (Exception ignored) {}
    }

    /** Terminal error for unrecovered premature completion: ERROR so JS never auto-nexts. */
    private void firePrematureError(long pos, long dur, String reason) {
        synchronized (lock) {
            lastRecoveryStatus = "failed";
            failLocked("PREMATURE_COMPLETION",
                    "pos=" + pos + " dur=" + dur + " reason=" + reason);
        }
        pushRecoveryEvent("failed", videoId, pos, dur, reason);
        pushAll();
    }

    /** Retry a prematurely-completed track from its last position (worker thread). */
    private void attemptPrematureRecovery(String recVid, int recGen, String recDs,
            long lastMs, long trackDur, int skipSoft) {
        // Phase 1: soft-seek with actual playback verification (p2 > p1).
        if (skipSoft <= 0) {
            try {
                try {
                    synchronized (lock) {
                        if (recGen != playGen || !recVid.equals(videoId) || mp == null) return;
                        mp.seekTo((int) Math.max(0, lastMs));
                        mp.start();
                    }
                    Thread.sleep(500);
                    long p1;
                    synchronized (lock) {
                        if (recGen != playGen || !recVid.equals(videoId) || mp == null) return;
                        p1 = mp.getCurrentPosition();
                    }
                    Thread.sleep(500);
                    long p2;
                    boolean playing;
                    synchronized (lock) {
                        if (recGen != playGen || !recVid.equals(videoId) || mp == null) return;
                        p2 = mp.getCurrentPosition();
                        try { playing = mp.isPlaying(); } catch (Exception ignored) { playing = false; }
                    }
                    if (playing && p2 > p1 && p2 >= lastMs - 500) {
                        synchronized (lock) {
                            currentMs = (int) p2;
                            setStateLocked(State.PLAYING);
                            consecutiveRecoveryCount = 0;
                            lastRecoveryStatus = "recovered";
                            acquireWakeLock();
                        }
                        Log.d(TAG, "[ENGINE] Recovery SUCCESS via soft-seek newPos=" + p2);
                        pushRecoveryEvent("recovered", recVid, lastMs, trackDur, "soft-seek");
                        pushAll();
                        return;
                    } else {
                        boolean pl;
                        synchronized (lock) {
                            boolean tmp = false;
                            try { tmp = mp != null && mp.isPlaying(); } catch (Exception ignored) {}
                            pl = tmp;
                        }
                        Log.w(TAG, "[ENGINE] Soft recovery check failed: isPlaying=" + pl
                                + " p1=" + p1 + " p2=" + p2);
                        throw new Exception("soft recovery position did not advance");
                    }
                } catch (Exception e) {
                    if ("soft recovery position did not advance".equals(e.getMessage())) throw e;
                    throw e;
                }
            } catch (Exception e) {
                Log.d(TAG, "[ENGINE] Soft-seek failed, falling back to hard recovery: " + e);
            }
        } else {
            Log.d(TAG, "[ENGINE] Skipping soft recovery, consecutive=" + skipSoft);
        }
        // Phase 2: hard recovery, up to 3 attempts. Async prepare with a
        // one-shot OnPreparedListener (seek + start); no blocking sleep.
        String failReason = null;
        boolean recovered = false;
        for (int attempt = 1; attempt <= 3 && !recovered; attempt++) {
            synchronized (lock) {
                if (recGen != playGen || !recVid.equals(videoId)) return;
                recoveryAttempts = attempt;
            }
            Log.d(TAG, "[ENGINE] Recovery attempt " + attempt + "/3 from pos=" + lastMs);
            synchronized (lock) {
                if (recGen != playGen || !recVid.equals(videoId) || mp == null) return;
                try {
                    recoveryPrepared = false;
                    mp.reset();
                    if (recDs != null && recDs.startsWith("http") && appCtx != null) {
                        mp.setDataSource(appCtx, Uri.parse(recDs));
                    } else if (recDs != null && !recDs.isEmpty()) {
                        mp.setDataSource(recDs);
                    } else {
                        failReason = "no data source available for recovery";
                        continue;
                    }
                    final long seekTarget = Math.max(0, lastMs);
                    mp.setOnPreparedListener(p -> {
                        synchronized (lock) {
                            try { p.seekTo((int) seekTarget); } catch (Exception ignored) {}
                            try { p.start(); } catch (Exception ignored) {}
                            recoveryPrepared = true;
                            lock.notifyAll();
                        }
                    });
                    mp.prepareAsync();
                    long deadline = System.currentTimeMillis() + 10000;
                    while (!recoveryPrepared) {
                        long waitMs = deadline - System.currentTimeMillis();
                        if (waitMs <= 0) break;
                        try {
                            lock.wait(waitMs);
                        } catch (InterruptedException e) {
                            return;
                        }
                        if (recGen != playGen || !recVid.equals(videoId)) return;
                    }
                    if (!recoveryPrepared) {
                        failReason = "prepare timeout on attempt " + attempt;
                        continue;
                    }
                    boolean playingNow = false;
                    long newPos = -1;
                    try {
                        playingNow = mp.isPlaying();
                        newPos = mp.getCurrentPosition();
                    } catch (Exception ignored) {}
                    if (playingNow && newPos >= lastMs - 500) {
                        recovered = true;
                        currentMs = (int) newPos;
                        setStateLocked(State.PLAYING);
                        lastRecoveryStatus = "recovered";
                        acquireWakeLock();
                        Log.d(TAG, "[ENGINE] Recovery SUCCESS at attempt " + attempt
                                + " newPos=" + newPos);
                    } else {
                        failReason = "position_not_advancing after attempt " + attempt;
                    }
                } catch (Exception e) {
                    failReason = e.getClass().getSimpleName() + ": " + e.getMessage();
                    Log.e(TAG, "[ENGINE] Recovery attempt " + attempt + " failed: " + failReason);
                }
            }
        }
        synchronized (lock) {
            if (mp != null) {
                try { mp.setOnPreparedListener(null); } catch (Exception ignored) {}
            }
        }
        if (recovered) {
            pushRecoveryEvent("recovered", recVid, lastMs, trackDur, null);
            pushAll();
        } else {
            Log.e(TAG, "[ENGINE] Recovery FAILED after 3 attempts. reason=" + failReason);
            firePrematureError(lastMs, trackDur, failReason);
        }
    }

    /** Recovery lifecycle event for the WebView toast log. */
    private void pushRecoveryEvent(String status, String recVid, long pos, long dur,
            String reason) {
        try {
            JSONObject s;
            synchronized (lock) {
                s = snapshotLocked("recoveryStatus");
            }
            s.put("status", status);
            s.put("positionMs", pos);
            s.put("durationMs", dur);
            if (recVid != null) s.put("videoId", recVid);
            if (reason != null) s.put("reason", reason);
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
            nlog("[NATIVE_STATE] " + s);
        } catch (Exception e) {
            Log.e(TAG, "[ENGINE] pushRecoveryEvent error: " + e.getMessage());
        }
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
            long nowDiag = System.currentTimeMillis();
            if (nowDiag - lastDiagTickLog >= 2000) {
                lastDiagTickLog = nowDiag;
                nlog("[AUTONEXT_DIAG] tick currentMs=" + currentMs + " durationMs=" + durationMs
                        + " state=" + state + " writerState=" + writerState(videoId)
                        + " windowEnd=" + windowEnd + " windowChunk=" + windowChunk
                        );
            }
            pushEvent("timeUpdate", null);
            // Sliding-window enforcement on existing 500ms clock.
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
                            nlog("[NAE] writer paused: reason=window cap hit end=" + windowEnd);
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
            o.put("state", displayStateLocked());
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
            o.put("recoveryAttempts", recoveryAttempts);
            o.put("lastRecoveryStatus", lastRecoveryStatus != null ? lastRecoveryStatus : "");
            return o;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /**
     * Snapshot state from live Exo truth, not stale lastState. Exo can sit
     * in STATE_BUFFERING (rebuffer) while audio keeps playing; the mirror
     * must say PLAYING or JS spinner/label stuck. MP path: lastState.
     * Call with lock held (reads volatile player ref; Exo getters thread-safe).
     */
    private String displayStateLocked() {
        try {
            if (exoMode && exoPlayer != null) {
                boolean playing = false;
                int ps = Player.STATE_IDLE;
                try { playing = exoPlayer.isPlaying(); } catch (Exception ignored) {}
                try { ps = exoPlayer.getPlaybackState(); } catch (Exception ignored) {}
                if (playing) return "PLAYING";
                if (ps == Player.STATE_BUFFERING) return "BUFFERING";
                if (ps == Player.STATE_READY) return "PAUSED";
            }
        } catch (Exception ignored) {}
        return String.valueOf(state);
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

    private static String writerState(String vid) {
        return SongCache.isDownloading(vid) ? "RUNNING"
                : (SongCache.isFull(vid) ? "COMPLETE" : "IDLE");
    }

    private static int safePosition(MediaPlayer player) {
        try { return player != null ? player.getCurrentPosition() : -1; }
        catch (Exception ignored) { return -1; }
    }

    private static int safeDuration(MediaPlayer player) {
        try { return player != null ? player.getDuration() : -1; }
        catch (Exception ignored) { return -1; }
    }
}
