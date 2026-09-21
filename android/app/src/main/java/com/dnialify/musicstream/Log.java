package com.dnialify.musicstream;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Date;
import java.util.Deque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Local shadow of android.util.Log.
 * Writes to real logcat AND to an in-memory ring buffer
 * consumed by the JS layer via LogBridge (see MainActivity).
 *
 * Java resolves same-package classes before imported ones.
 * Remove the platform Log import (android.util) from any file in this package
 * to route its calls here instead of the platform class.
 */
public final class Log {

    private static final int MAX_ENTRIES = 20000;
    private static final Object LOCK = new Object();
    private static final Deque<String> BUFFER = new ArrayDeque<>(1024);
    private static final SimpleDateFormat SDF =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    /** Only these tags are captured at D/I/V level. W/E are always captured. */
    private static final Set<String> TAG_WHITELIST = new HashSet<>(Arrays.asList(
            // Playback engine
            "DnialifyVisionOS",
            "NativeAudioEngine",
            "NAE",
            "PlaybackService",
            // Caching
            "SongCache",
            "VisionOsCache",
            "CacheEvents",
            "LocalStreamServer",
            "LSS",
            // Resolver / network
            "VisionOsResolver",
            "VisionOsNet",
            "VisionOsLoopback",
            "OfflineInterceptClient",
            // Harness / diag
            "VisionOsHarness",
            "DnialifyDiag",
            // Web bridge
            "WEBVIEW_JS"
    ));

    private Log() {}

    private static String formatLine(String level, String tag, String msg) {
        String ts;
        synchronized (SDF) { ts = SDF.format(new Date()); }
        return "[" + ts + "] [" + level + "/" + (tag == null ? "" : tag) + "] "
                + (msg == null ? "" : msg);
    }

    private static void pushToRing(String level, String tag, String msg) {
        boolean isWarnOrError = "W".equals(level) || "E".equals(level);
        boolean tagAllowed = tag != null && TAG_WHITELIST.contains(tag);
        if (!isWarnOrError && !tagAllowed) return;

        String line = formatLine(level, tag, msg);
        synchronized (LOCK) {
            BUFFER.addLast(line);
            int overflow = BUFFER.size() - MAX_ENTRIES;
            for (int i = 0; i < overflow; i++) BUFFER.removeFirst();
        }
    }

    /** Atomically drains the ring buffer. Returns "" if empty. */
    public static String drain() {
        synchronized (LOCK) {
            if (BUFFER.isEmpty()) return "";
            StringBuilder sb = new StringBuilder(BUFFER.size() * 64);
            boolean first = true;
            while (!BUFFER.isEmpty()) {
                if (!first) sb.append('\n');
                first = false;
                sb.append(BUFFER.removeFirst());
            }
            return sb.toString();
        }
    }

    public static int v(String tag, String msg) {
        pushToRing("V", tag, msg);
        return android.util.Log.v(tag, msg);
    }

    public static int v(String tag, String msg, Throwable tr) {
        String full = (msg == null ? "" : msg)
                + (tr != null ? " :: " + tr.toString() : "");
        pushToRing("V", tag, full);
        return android.util.Log.v(tag, msg, tr);
    }

    public static int d(String tag, String msg) {
        pushToRing("D", tag, msg);
        return android.util.Log.d(tag, msg);
    }

    public static int d(String tag, String msg, Throwable tr) {
        String full = (msg == null ? "" : msg)
                + (tr != null ? " :: " + tr.toString() : "");
        pushToRing("D", tag, full);
        return android.util.Log.d(tag, msg, tr);
    }

    public static int i(String tag, String msg) {
        pushToRing("I", tag, msg);
        return android.util.Log.i(tag, msg);
    }

    public static int i(String tag, String msg, Throwable tr) {
        String full = (msg == null ? "" : msg)
                + (tr != null ? " :: " + tr.toString() : "");
        pushToRing("I", tag, full);
        return android.util.Log.i(tag, msg, tr);
    }

    public static int w(String tag, String msg) {
        pushToRing("W", tag, msg);
        return android.util.Log.w(tag, msg);
    }

    public static int w(String tag, String msg, Throwable tr) {
        String full = (msg == null ? "" : msg)
                + (tr != null ? " :: " + tr.toString() : "");
        pushToRing("W", tag, full);
        return android.util.Log.w(tag, msg, tr);
    }

    public static int w(String tag, Throwable tr) {
        String full = tr == null ? "" : tr.toString();
        pushToRing("W", tag, full);
        return android.util.Log.w(tag, tr);
    }

    public static int e(String tag, String msg) {
        pushToRing("E", tag, msg);
        return android.util.Log.e(tag, msg);
    }

    public static int e(String tag, String msg, Throwable t) {
        String full = (msg == null ? "" : msg)
                + (t != null ? " :: " + t.toString() : "");
        pushToRing("E", tag, full);
        return android.util.Log.e(tag, msg, t);
    }

    public static int wtf(String tag, String msg) {
        pushToRing("E", tag, msg);
        return android.util.Log.wtf(tag, msg);
    }

    public static int wtf(String tag, String msg, Throwable t) {
        String full = (msg == null ? "" : msg)
                + (t != null ? " :: " + t.toString() : "");
        pushToRing("E", tag, full);
        return android.util.Log.wtf(tag, msg, t);
    }

    public static int wtf(String tag, Throwable t) {
        String full = t == null ? "" : t.toString();
        pushToRing("E", tag, full);
        return android.util.Log.wtf(tag, t);
    }

    public static String getStackTraceString(Throwable t) {
        return android.util.Log.getStackTraceString(t);
    }

    public static boolean isLoggable(String tag, int level) {
        return android.util.Log.isLoggable(tag, level);
    }
}
