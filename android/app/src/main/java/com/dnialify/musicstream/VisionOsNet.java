package com.dnialify.musicstream;

import android.util.Log;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 8 — network accounting for the VisionOS pipeline (diagnostic aid).
 * Counts every googlevideo request and can hard-refuse YouTube/googlevideo
 * hosts during cache-playback tests ("no YouTube" proof). No retries here.
 */
public final class VisionOsNet {
    private static final String TAG = "DnialifyVisionOS";
    private static final AtomicLong GOOGLE_REQUESTS = new AtomicLong(0);
    private static final AtomicLong RESOLVE_REQUESTS = new AtomicLong(0);
    private static volatile boolean blockNetwork = false;

    private VisionOsNet() {}

    public static void setBlockNetwork(boolean block) {
        blockNetwork = block;
        Log.d(TAG, "net block=" + block);
    }

    public static boolean isBlocked() {
        return blockNetwork;
    }

    public static long googleRequests() {
        return GOOGLE_REQUESTS.get();
    }

    public static long resolveRequests() {
        return RESOLVE_REQUESTS.get();
    }

    public static void resetCounters() {
        GOOGLE_REQUESTS.set(0);
        RESOLVE_REQUESTS.set(0);
    }

    /** Call before any googlevideo/youtube request. Throws when blocked (test mode). */
    public static void noteRequest(String host) throws VisionOsResolver.ResolverException {
        String h = host != null ? host.toLowerCase(Locale.US) : "";
        boolean yt = h.contains("googlevideo.com") || h.contains("youtube.com")
                || h.contains("youtu.be") || h.contains("ytimg.com")
                || h.contains("youtubei.googleapis.com");
        if (blockNetwork && yt) {
            throw new VisionOsResolver.ResolverException("BLOCKED", "network blocked for " + h);
        }
        if (h.contains("googlevideo.com")) {
            GOOGLE_REQUESTS.incrementAndGet();
        }
        if (h.contains("youtubei.googleapis.com")) {
            RESOLVE_REQUESTS.incrementAndGet();
        }
    }
}
