package com.dnialify.musicstream;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONObject;

/**
 * Phase 8 — FULL SONG CACHE. One videoId = one logical cached song.
 *
 * Flat files next to the proven audio cache (no second database):
 *   <id>.webm  final audio (atomic rename from .part, EBML+size validated)
 *   <id>.part  partial download (resume base)
 *   <id>.song.json  record: metadata + lyrics + artwork ref + progress
 *   <id>.art   artwork bytes (best effort, never fails audio)
 *
 * videoId is the primary identity (titles can change/duplicate).
 * Progress persists per chunk-threshold so kill/restart keeps last state.
 * COMPLETE is only set after size + EBML validation pass.
 */
public final class SongCache {
    static final String TAG = "DnialifyVisionOS";
    static final int METADATA_VERSION = 1;

    public static final String ST_NONE = "NONE";
    public static final String ST_QUEUED = "QUEUED";
    public static final String ST_DOWNLOADING = "DOWNLOADING";
    public static final String ST_PAUSED = "PAUSED";
    public static final String ST_COMPLETE = "COMPLETE";
    public static final String ST_FAILED = "FAILED";

    public static final String LY_AVAILABLE = "AVAILABLE";
    public static final String LY_UNAVAILABLE = "UNAVAILABLE";
    public static final String LY_PENDING = "PENDING";
    public static final String LY_FAILED = "FAILED";

    static final int CHUNK = 102400;
    static final int TIMEOUT_MS = 20000;
    private static final long RECORD_EVERY_BYTES = 2L * 1024L * 1024L;
    private static final String UA =
            "com.google.visionos.youtube/1.02(RealityDevice14,1; U; CPU visionOS 25_6_0 like Mac OS X; US)";

    private static final java.util.Map<String, Boolean> CANCEL =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Set<String> RUNNING =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private SongCache() {}

    static File dir(Context c) {
        return VisionOsCache.dir(c);
    }

    public static File audioFile(Context c, String videoId) {
        return VisionOsCache.fileFor(c, videoId);
    }

    public static File partFile(Context c, String videoId) {
        return new File(dir(c), videoId + ".webm.part");
    }

    public static File recordFile(Context c, String videoId) {
        return new File(dir(c), videoId + ".song.json");
    }

    public static File artFile(Context c, String videoId) {
        return new File(dir(c), videoId + ".art");
    }

    /** Load record or null. Never throws. */
    public static synchronized JSONObject getRecord(Context c, String videoId) {
        try {
            File f = recordFile(c, videoId);
            if (!f.isFile()) return null;
            byte[] b = new byte[(int) f.length()];
            FileInputStream in = new FileInputStream(f);
            try {
                int n = 0;
                while (n < b.length) {
                    int r = in.read(b, n, b.length - n);
                    if (r < 0) break;
                    n += r;
                }
            } finally {
                in.close();
            }
            return new JSONObject(new String(b, "UTF-8"));
        } catch (Exception e) {
            return null;
        }
    }

    public static synchronized void putRecord(Context c, String videoId, JSONObject r) {
        try {
            r.put("videoId", videoId);
            r.put("updatedAt", System.currentTimeMillis());
            File f = recordFile(c, videoId);
            FileOutputStream out = new FileOutputStream(f);
            try {
                out.write(r.toString().getBytes("UTF-8"));
            } finally {
                out.close();
            }
        } catch (Exception ignored) {}
    }

    /** Create/refresh metadata shell. Audio NOT waited for. Returns record. */
    public static synchronized JSONObject ensureMeta(Context c, JSONObject meta) {
        String vid = meta.optString("videoId", "");
        JSONObject r = getRecord(c, vid);
        if (r == null) {
            r = new JSONObject();
            try {
                r.put("metadataVersion", METADATA_VERSION);
                r.put("createdAt", System.currentTimeMillis());
                r.put("cacheStatus", ST_QUEUED);
                r.put("downloadedBytes", 0);
                r.put("audioSize", 0);
                r.put("downloadPercent", 0);
                r.put("lyricsStatus", LY_PENDING);
            } catch (Exception ignored) {}
        }
        try {
            // never clobber real metadata with placeholder shells (e.g. engine
            // play passes title=DIAG): fill blanks only, keep first truth
            if (meta.has("title")) {
                String v = meta.optString("title", "");
                if (!v.isEmpty() && (r.optString("title", "").isEmpty()
                        || r.optString("title", "").equals("DIAG"))) r.put("title", v);
            }
            if (meta.has("artist")) {
                String v = meta.optString("artist", "");
                if (!v.isEmpty() && r.optString("artist", "").isEmpty()) r.put("artist", v);
            }
            if (meta.has("album")) {
                String v = meta.optString("album", "");
                if (!v.isEmpty() && r.optString("album", "").isEmpty()) r.put("album", v);
            }
            if (meta.has("artworkUrl")) {
                String v = meta.optString("artworkUrl", "");
                if (!v.isEmpty() && r.optString("artworkUrl", "").isEmpty()) {
                    r.put("artworkUrl", v);
                }
            }
            if (meta.has("duration")) {
                double d = meta.optDouble("duration", 0);
                if (d > 0 && r.optDouble("duration", 0) <= 0) r.put("duration", d);
            }
            if (meta.has("source")) r.put("source", meta.optString("source", "VISIONOS"));
            r.put("engine", "NATIVE");
            if (meta.has("audioFormat")) r.put("audioFormat", meta.optString("audioFormat", ""));
            // lyrics: keep raw timed structure, never flatten
            if (meta.has("lyrics")) {
                String ly = meta.optString("lyrics", null);
                if (ly != null && !ly.isEmpty()) {
                    r.put("lyrics", ly);
                    r.put("lyricsFormat", meta.optString("lyricsFormat", "lrc"));
                    r.put("lyricsStatus", LY_AVAILABLE);
                } else {
                    if (!r.has("lyrics") || r.optString("lyrics", "").isEmpty()) {
                        r.put("lyricsStatus", LY_UNAVAILABLE);
                    }
                }
            }
            if (ST_NONE.equals(r.optString("cacheStatus", ST_NONE))) {
                r.put("cacheStatus", ST_QUEUED);
            }
        } catch (Exception ignored) {}
        putRecord(c, vid, r);
        Log.d(TAG, "[SONG] meta videoId=" + vid + " status=" + r.optString("cacheStatus", "?"));
        return r;
    }

    public static List<JSONObject> listRecords(Context c) {
        List<JSONObject> out = new ArrayList<>();
        try {
            File[] fs = dir(c).listFiles();
            if (fs == null) return out;
            for (File f : fs) {
                if (f.getName().endsWith(".song.json")) {
                    JSONObject r = getRecord(c,
                            f.getName().substring(0, f.getName().length() - 10));
                    if (r != null) out.add(r);
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** True only when record COMPLETE + file present + size match. Repairs stale COMPLETE. */
    public static boolean isComplete(Context c, String videoId) {
        try {
            JSONObject r = getRecord(c, videoId);
            File a = audioFile(c, videoId);
            if (r == null) return a.isFile() && a.length() > 0;
            if (!ST_COMPLETE.equals(r.optString("cacheStatus", ""))) return false;
            long want = r.optLong("audioSize", 0);
            if (!a.isFile() || (want > 0 && a.length() != want)) {
                // stale COMPLETE (file gone/replaced) -> repair to FAILED, never lie
                try {
                    r.put("cacheStatus", ST_FAILED);
                    r.put("failReason", "file-missing");
                    putRecord(c, videoId, r);
                } catch (Exception ignored) {}
                Log.d(TAG, "[SONG] stale COMPLETE repaired videoId=" + videoId);
                return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static void pauseDownload(String videoId) {
        CANCEL.put(videoId, true);
        Log.d(TAG, "[SONG] pause requested videoId=" + videoId);
    }

    /** True while a writer thread owns this videoId (duplicate-job guard). */
    public static boolean isDownloading(String videoId) {
        return RUNNING.contains(videoId);
    }

    /**
     * Download audio (FULL song) with persistent progress + resume.
     * Sparse writer: 100KB chunks at exact offsets, present-bitmap persisted
     * in the record, COMPLETE only after exact size + EBML magic.
     * Re-entrant safe: second caller while RUNNING gets skipped.
     */
    public static void download(Context c, String videoId, String url, long total) {
        if (!RUNNING.add(videoId)) {
            Log.d(TAG, "[SONG] download SKIP already running videoId=" + videoId);
            return;
        }
        try {
            RUNNING_CAPS.put(videoId, Long.MAX_VALUE);
            fillUpTo(c, videoId, url, total, total);
        } finally {
            RUNNING.remove(videoId);
            RUNNING_CAPS.remove(videoId);
        }
    }

    /**
     * Windowed fill: fetch missing chunks with start < limitBytes only.
     * Never renames to final unless the whole song landed (finishIfComplete).
     * Powers cache-first playback + 5-ahead prefetch without quota waste.
     */
    public static void downloadUpTo(Context c, String videoId, String url,
            long total, long limitBytes) {
        if (!RUNNING.add(videoId)) {
            Log.d(TAG, "[SONG] downloadUpTo SKIP already running videoId=" + videoId);
            return;
        }
        try {
            RUNNING_CAPS.put(videoId, limitBytes);
            fillUpTo(c, videoId, url, total, limitBytes);
        } finally {
            RUNNING.remove(videoId);
            RUNNING_CAPS.remove(videoId);
        }
    }

    /** Fill-cap currently owned by the running writer (null when idle). */
    static Long runningCap(String videoId) {
        return RUNNING_CAPS.get(videoId);
    }

    private static final java.util.Map<String, Long> RUNNING_CAPS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Window end covering targetMs + 5 chunks ahead. Reuses 100KB CHUNK. */
    public static long windowEndFor(long targetMs, long durationMs, long total) {
        if (total <= 0) return total;
        long idx = 0;
        if (durationMs > 0 && targetMs > 0) {
            idx = (total * Math.max(0, targetMs) / durationMs) / CHUNK;
        }
        return Math.min(total, (idx + 6) * (long) CHUNK);
    }

    static int chunkCount(long total) {
        if (total <= 0) return 0;
        return (int) ((total + CHUNK - 1) / CHUNK);
    }

    // ---------- sparse present-bitmap (memory mirror + record persist) ----------

    private static final java.util.Map<String, boolean[]> CHUNKMAP =
            new java.util.concurrent.ConcurrentHashMap<>();

    static synchronized boolean[] chunkMap(Context c, String videoId, long total) {
        int n = chunkCount(total);
        boolean[] m = CHUNKMAP.get(videoId);
        if (m != null && m.length == n) return m;
        m = new boolean[n];
        boolean rebuilt = false;
        try {
            File part = partFile(c, videoId);
            boolean gone = !part.isFile();
            if (!gone && total > 0) {
                JSONObject r = getRecord(c, videoId);
                String s = r != null ? r.optString("chunkMap", null) : null;
                if (s != null && s.length() == n) {
                    for (int i = 0; i < n; i++) m[i] = s.charAt(i) == '1';
                    rebuilt = true;
                }
            }
            if (!rebuilt) {
                // legacy sequential part (or nothing): present == contiguous length
                long len = 0;
                try {
                    File p = partFile(c, videoId);
                    if (p.isFile()) len = p.length();
                    if (isComplete(c, videoId)) len = total;
                } catch (Exception ignored) {}
                for (int i = 0; i < n; i++) {
                    m[i] = ((long) (i + 1) * CHUNK) <= len;
                }
            }
        } catch (Exception ignored) {}
        CHUNKMAP.put(videoId, m);
        return m;
    }

    static synchronized void markChunk(Context c, String videoId, long total, int idx) {
        try {
            boolean[] m = chunkMap(c, videoId, total);
            if (idx < 0 || idx >= m.length || m[idx]) return;
            m[idx] = true;
            StringBuilder sb = new StringBuilder(m.length);
            for (boolean b : m) sb.append(b ? '1' : '0');
            JSONObject r = getRecord(c, videoId);
            if (r == null) r = new JSONObject();
            r.put("chunkMap", sb.toString());
            putRecord(c, videoId, r);
        } catch (Exception ignored) {}
    }

    static synchronized void clearChunkMap(String videoId) {
        try {
            CHUNKMAP.remove(videoId);
        } catch (Exception ignored) {}
    }

    static boolean isChunkPresent(Context c, String videoId, long total, int idx) {
        try {
            boolean[] m = chunkMap(c, videoId, total);
            return idx >= 0 && idx < m.length && m[idx];
        } catch (Exception e) {
            return false;
        }
    }

    static long contiguousBytes(Context c, String videoId, long total) {
        try {
            boolean[] m = chunkMap(c, videoId, total);
            long cont = 0;
            for (int i = 0; i < m.length; i++) {
                if (!m[i]) break;
                cont += Math.min((long) CHUNK, total - (long) i * CHUNK);
            }
            return cont;
        } catch (Exception e) {
            return 0;
        }
    }

    static boolean allPresent(Context c, String videoId, long total) {
        try {
            if (total <= 0) return false;
            boolean[] m = chunkMap(c, videoId, total);
            for (boolean b : m) if (!b) return false;
            return m.length > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** Drop unaligned/oversize .part (legacy 512KB-era) so bitmap stays exact. */
    static void sanitizePart(Context c, String videoId, long total) {
        try {
            File part = partFile(c, videoId);
            if (!part.isFile()) {
                clearChunkMap(videoId);
                return;
            }
            if (total > 0 && (part.length() > total || part.length() % CHUNK != 0)) {
                Log.d(TAG, "[SONG] sanitize drop part videoId=" + videoId
                        + " len=" + part.length());
                part.delete();
                clearChunkMap(videoId);
            }
        } catch (Exception ignored) {}
    }

    private static void fetchChunk(Context c, String videoId, String url,
            long total, int idx) throws Exception {
        long start = (long) idx * CHUNK;
        long end = Math.min(start + CHUNK - 1, total - 1);
        if (start >= total) return;
        if (Boolean.TRUE.equals(CANCEL.get(videoId))) {
            throw new PausedException(contiguousBytes(c, videoId, total));
        }
        VisionOsNet.noteRequest(new URL(url).getHost());
        HttpURLConnection h = null;
        try {
            h = (HttpURLConnection) new URL(url).openConnection();
            h.setRequestMethod("GET");
            h.setConnectTimeout(TIMEOUT_MS);
            h.setReadTimeout(TIMEOUT_MS);
            h.setRequestProperty("Range", "bytes=" + start + "-" + end);
            h.setRequestProperty("User-Agent", UA);
            int status = h.getResponseCode();
            Log.d(TAG, "chunk " + idx + " range=" + start + "-" + end + " status=" + status);
            if (status != 206) {
                throw new Exception("chunk HTTP " + status + " at " + start);
            }
            InputStream in = h.getInputStream();
            java.io.RandomAccessFile raf =
                    new java.io.RandomAccessFile(partFile(c, videoId), "rw");
            try {
                raf.seek(start);
                byte[] buf = new byte[65536];
                long want = end - start + 1;
                long got = 0;
                int n;
                while (got < want
                        && (n = in.read(buf, 0, (int) Math.min(buf.length, want - got))) != -1) {
                    raf.write(buf, 0, n);
                    got += n;
                }
                if (got != want) throw new Exception("short chunk at " + start);
            } finally {
                try { in.close(); } catch (Exception ignored) {}
                try { raf.close(); } catch (Exception ignored) {}
            }
            markChunk(c, videoId, total, idx);
        } finally {
            if (h != null) h.disconnect();
        }
    }

    private static void fillUpTo(Context c, String videoId, String url,
            long total, long limit) {
        CANCEL.remove(videoId);
        JSONObject r = getRecord(c, videoId);
        if (r == null) {
            r = new JSONObject();
            try {
                r.put("videoId", videoId);
                r.put("metadataVersion", METADATA_VERSION);
                r.put("createdAt", System.currentTimeMillis());
                r.put("engine", "NATIVE");
                r.put("source", "VISIONOS");
            } catch (Exception ignored) {}
        }
        try {
            r.put("cacheStatus", ST_DOWNLOADING);
            r.put("audioSize", total);
            r.put("failReason", JSONObject.NULL);
            putRecord(c, videoId, r);
        } catch (Exception ignored) {}
        CacheEvents.emit(c, "cacheState", videoId, r);
        sanitizePart(c, videoId, total);
        long lastRecordAt = contiguousBytes(c, videoId, total);
        int n = chunkCount(total);
        try {
            for (int i = 0; i < n; i++) {
                long start = (long) i * CHUNK;
                if (start >= limit) break;
                if (isChunkPresent(c, videoId, total, i)) continue;
                if (Boolean.TRUE.equals(CANCEL.get(videoId))) {
                    throw new PausedException(contiguousBytes(c, videoId, total));
                }
                fetchChunk(c, videoId, url, total, i);
                long cont = contiguousBytes(c, videoId, total);
                if (cont - lastRecordAt >= RECORD_EVERY_BYTES) {
                    lastRecordAt = cont;
                    progress(c, videoId, r, cont, total);
                }
            }
            progress(c, videoId, r, contiguousBytes(c, videoId, total), total);
            finishIfComplete(c, videoId, total);
        } catch (PausedException p) {
            try {
                r.put("cacheStatus", ST_PAUSED);
                r.put("downloadedBytes", p.received);
                r.put("downloadPercent", pct(p.received, total));
                putRecord(c, videoId, r);
            } catch (Exception ignored) {}
            Log.d(TAG, "[SONG] PAUSED videoId=" + videoId + " at=" + p.received);
            CacheEvents.emit(c, "cacheState", videoId, r);
        } catch (Exception e) {
            long received = contiguousBytes(c, videoId, total);
            String reason = VisionOsResolver.reasonFromMessage(String.valueOf(e.getMessage()));
            try {
                r.put("cacheStatus", ST_FAILED);
                r.put("failReason", reason + " " + e.getMessage());
                r.put("downloadedBytes", received);
                r.put("downloadPercent", pct(received, total));
                putRecord(c, videoId, r);
            } catch (Exception ignored) {}
            Log.d(TAG, "[SONG] FAILED videoId=" + videoId + " reason=" + reason
                    + " " + e.getMessage());
            CacheEvents.emit(c, "cacheError", videoId, r);
        } finally {
            CANCEL.remove(videoId);
        }
    }

    /** Validate + atomic rename + COMPLETE, but ONLY when every chunk landed. */
    private static void finishIfComplete(Context c, String videoId, long total) throws Exception {
        if (!allPresent(c, videoId, total)) return;
        File part = partFile(c, videoId);
        File fin = audioFile(c, videoId);
        if (part.length() != total || !VisionOsCache.ebmlMagic(part)) {
            throw new Exception("validation failed size=" + part.length()
                    + " want=" + total);
        }
        if (fin.exists()) fin.delete();
        if (!part.renameTo(fin)) {
            throw new Exception("atomic rename failed");
        }
        JSONObject r = getRecord(c, videoId);
        if (r == null) r = new JSONObject();
        try {
            r.put("cacheStatus", ST_COMPLETE);
            r.put("downloadedBytes", total);
            r.put("downloadPercent", 100);
            r.put("audioFile", fin.getAbsolutePath());
            putRecord(c, videoId, r);
        } catch (Exception ignored) {}
        Log.d(TAG, "[SONG] COMPLETE videoId=" + videoId + " bytes=" + total
                + " chunks=" + chunkCount(total));
        CacheEvents.emit(c, "cacheComplete", videoId, r);
    }

    static double pct(long got, long total) {
        if (total <= 0) return 0;
        return Math.round(got * 10000.0 / total) / 100.0;
    }

    static void progress(Context c, String videoId, JSONObject r, long got, long total) {
        try {
            r.put("downloadedBytes", got);
            r.put("downloadPercent", pct(got, total));
            putRecord(c, videoId, r);
        } catch (Exception ignored) {}
        Log.d(TAG, "[SONG] progress videoId=" + videoId + " " + got + "/" + total
                + " (" + pct(got, total) + "%)");
        CacheEvents.emit(c, "cacheProgress", videoId, r);
    }

    /** Best-effort artwork fetch. Never fails audio. Returns local path or null. */
    public static String fetchArtwork(Context c, String videoId, String url) {
        if (url == null || url.isEmpty()) return null;
        if (!url.startsWith("http")) return null;
        HttpURLConnection h = null;
        try {
            h = (HttpURLConnection) new URL(url).openConnection();
            h.setConnectTimeout(15000);
            h.setReadTimeout(15000);
            h.setRequestProperty("User-Agent", UA);
            int status = h.getResponseCode();
            if (status < 200 || status > 299) return null;
            String ct = String.valueOf(h.getHeaderField("Content-Type"));
            if (!ct.contains("image/")) return null;
            File f = artFile(c, videoId);
            FileOutputStream out = new FileOutputStream(f);
            InputStream in = h.getInputStream();
            byte[] buf = new byte[32768];
            int n;
            long got = 0;
            while ((n = in.read(buf)) != -1) {
                got += n;
                if (got > 2L * 1024L * 1024L) break;
                out.write(buf, 0, n);
            }
            in.close();
            out.close();
            JSONObject r = getRecord(c, videoId);
            if (r != null) {
                r.put("artworkFile", f.getAbsolutePath());
                // 128px data-URI thumb so WebView can show LOCAL art offline
                // (page context cannot read native files; proven content/loopback block)
                try {
                    android.graphics.Bitmap full =
                            android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath());
                    if (full != null) {
                        int fw = full.getWidth();
                        int fh = full.getHeight();
                        int div = Math.max(1, Math.max(fw, fh) / 128);
                        android.graphics.Bitmap small =
                                android.graphics.Bitmap.createScaledBitmap(
                                        full, Math.max(1, fw / div), Math.max(1, fh / div), true);
                        java.io.ByteArrayOutputStream bos =
                                new java.io.ByteArrayOutputStream();
                        small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, bos);
                        String b64 = android.util.Base64.encodeToString(
                                bos.toByteArray(), android.util.Base64.NO_WRAP);
                        r.put("artworkThumb", "data:image/jpeg;base64," + b64);
                        if (small != full) small.recycle();
                        full.recycle();
                    }
                } catch (Exception e) {
                    Log.d(TAG, "[SONG] artworkThumb SKIP " + e);
                }
                putRecord(c, videoId, r);
            }
            Log.d(TAG, "[SONG] artwork videoId=" + videoId + " bytes=" + got);
            return f.getAbsolutePath();
        } catch (Exception e) {
            Log.d(TAG, "[SONG] artwork SKIP videoId=" + videoId + " " + e);
            return null;
        } finally {
            if (h != null) h.disconnect();
        }
    }

    /**
     * CACHE-FIRST helpers (engine playback never reads network).
     * Contiguous present bytes from offset 0 WITHOUT any network call:
     * final size when COMPLETE+valid, else bitmap-contiguous prefix length.
     * (File length alone lies for sparse parts — holes read as zeros.)
     */
    public static long localBytes(Context c, String videoId) {
        try {
            if (isComplete(c, videoId)) {
                File a = audioFile(c, videoId);
                return a.isFile() ? a.length() : 0;
            }
            JSONObject r = getRecord(c, videoId);
            long total = r != null ? r.optLong("audioSize", 0) : 0;
            if (total > 0) return contiguousBytes(c, videoId, total);
            File p = partFile(c, videoId);
            return p.isFile() ? p.length() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Chunk end (exclusive byte count) covering targetMs. Reuses 512KB CHUNK. */
    public static long chunkEndFor(long targetMs, long durationMs, long total) {
        if (total <= 0) return total;
        if (durationMs <= 0 || targetMs <= 0) return Math.min(total, (long) CHUNK);
        long need = total * Math.max(0, targetMs) / durationMs;
        long idx = need / CHUNK;
        return Math.min(total, (idx + 1) * (long) CHUNK);
    }

    /** True when the whole chunk containing targetMs is on disk (final or .part). */
    public static boolean hasBytes(Context c, String videoId, long needEnd) {
        if (needEnd <= 0) return true;
        return localBytes(c, videoId) >= needEnd;
    }

    /**
     * Stable playback snapshot: copies available prefix [0, needEnd) to
     * <id>.play.webm so MediaPlayer never reads a file the writer is
     * appending to. Returns null when source short or EBML header invalid.
     */
    public static File snapshotPrefix(Context c, String videoId, long needEnd) {
        try {
            // hard gate: never copy a range with sparse holes (zeros = corruption)
            if (needEnd > 0 && localBytes(c, videoId) < needEnd) return null;
            File src = null;
            if (isComplete(c, videoId)) {
                File a = audioFile(c, videoId);
                if (a.isFile() && (needEnd <= 0 || a.length() >= needEnd)) src = a;
            }
            if (src == null) {
                File p = partFile(c, videoId);
                if (p.isFile() && (needEnd <= 0 || p.length() >= needEnd)) src = p;
            }
            if (src == null) return null;
            long n = needEnd > 0 ? Math.min(needEnd, src.length()) : src.length();
            if (n <= 0) return null;
            File dst = new File(dir(c), videoId + ".play.webm");
            FileInputStream in = new FileInputStream(src);
            FileOutputStream out = new FileOutputStream(dst);
            try {
                byte[] buf = new byte[65536];
                long left = n;
                int r;
                while (left > 0
                        && (r = in.read(buf, 0, (int) Math.min(buf.length, left))) != -1) {
                    out.write(buf, 0, r);
                    left -= r;
                }
            } finally {
                try { in.close(); } catch (Exception ignored) {}
                try { out.close(); } catch (Exception ignored) {}
            }
            if (dst.length() != n || !VisionOsCache.ebmlMagic(dst)) {
                dst.delete();
                return null;
            }
            return dst;
        } catch (Exception e) {
            return null;
        }
    }

    /** Drop corrupt .part so the next wait re-fetches from byte 0. Never throws. */
    public static void dropPart(Context c, String videoId) {
        try {
            File p = partFile(c, videoId);
            if (p.isFile()) p.delete();
        } catch (Exception ignored) {}
        clearChunkMap(videoId);
    }

    /** Delete owned files only (audio/part/record/art). History/playlists untouched. */
    public static JSONObject delete(Context c, String videoId) {        JSONObject out = new JSONObject();
        int n = 0;
        long bytes = 0;
        try {
            File[] fs = {audioFile(c, videoId), partFile(c, videoId),
                    recordFile(c, videoId), artFile(c, videoId),
                    new File(dir(c), videoId + ".play.webm")};
            for (File f : fs) {
                if (f.isFile()) {
                    bytes += f.length();
                    if (f.delete()) n++;
                }
            }
            out.put("removed", n);
            out.put("bytes", bytes);
        } catch (Exception ignored) {}
        clearChunkMap(videoId);
        Log.d(TAG, "[SONG] delete videoId=" + videoId + " files=" + n);
        return out;
    }

    static final class PausedException extends Exception {
        final long received;
        PausedException(long r) {
            super("paused");
            received = r;
        }
    }
}
