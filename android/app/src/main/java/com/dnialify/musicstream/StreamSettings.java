package com.dnialify.musicstream;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Phase 8 — native-only stream configuration (never touches web UI or production web).
 *
 * Stream:
 *   MODE_IFRAME   (default) — existing YouTube iFrame flow, byte-for-byte behavior.
 *   MODE_VISIONOS           — proven VisionOS pipeline (visitorData + direct googlevideo URL).
 * Audio Cache / Max Cached Songs are configuration/state only for Phase 9
 * (no files, no LRU, no eviction, no download manager here).
 */
public final class StreamSettings {
    private static final String PREFS = "visionos_stream_prefs";
    private static final String KEY_MODE = "stream_mode";
    private static final String KEY_AUDIO_CACHE = "audio_cache";
    private static final String KEY_MAX_CACHED = "max_cached_songs";

    public static final int MODE_IFRAME = 0;
    public static final int MODE_VISIONOS = 1;
    public static final int DEFAULT_MAX_CACHED = 50;

    private StreamSettings() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static int getStreamMode(Context c) {
        try {
            return prefs(c).getInt(KEY_MODE, MODE_IFRAME);
        } catch (Exception e) {
            return MODE_IFRAME;
        }
    }

    public static void setStreamMode(Context c, int mode) {
        try {
            prefs(c).edit().putInt(KEY_MODE, mode == MODE_VISIONOS ? MODE_VISIONOS : MODE_IFRAME).apply();
        } catch (Exception ignored) {}
    }

    public static boolean isAudioCacheOn(Context c) {
        try {
            return prefs(c).getBoolean(KEY_AUDIO_CACHE, false);
        } catch (Exception e) {
            return false;
        }
    }

    public static void setAudioCache(Context c, boolean on) {
        try {
            prefs(c).edit().putBoolean(KEY_AUDIO_CACHE, on).apply();
        } catch (Exception ignored) {}
    }

    public static int getMaxCachedSongs(Context c) {
        try {
            int v = prefs(c).getInt(KEY_MAX_CACHED, DEFAULT_MAX_CACHED);
            return v < 1 ? 1 : v;
        } catch (Exception e) {
            return DEFAULT_MAX_CACHED;
        }
    }

    public static void setMaxCachedSongs(Context c, int v) {
        try {
            prefs(c).edit().putInt(KEY_MAX_CACHED, Math.max(1, v)).apply();
        } catch (Exception ignored) {}
    }
}
