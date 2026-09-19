package com.dnialify.musicstream;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Phase 8 — proof-of-concept local cache (NO LRU, NO eviction, NO manager).
 * Sequential 512KB Range chunks of ONE url, atomic .part -> final rename,
 * validated by exact size + EBML/WebM magic. Any failure deletes .part and
 * the file is never treated as valid cache. Phase 9 owns real management.
 */
public final class VisionOsCache {
    static final String TAG = "DnialifyVisionOS";
    private static final int CHUNK = 102400;
    private static final int TIMEOUT_MS = 20000;
    private static final String UA =
            "com.google.visionos.youtube/1.02(RealityDevice14,1; U; CPU visionOS 25_6_0 like Mac OS X; US)";

    public static final class CacheResult {
        public final File file;
        public final long bytes;
        public final int chunks;

        CacheResult(File file, long bytes, int chunks) {
            this.file = file;
            this.bytes = bytes;
            this.chunks = chunks;
        }
    }

    private VisionOsCache() {}

    public static File dir(Context c) {
        File d = new File(c.getCacheDir(), "visionos_cache");
        d.mkdirs();
        return d;
    }

    public static File fileFor(Context c, String videoId) {
        return new File(dir(c), videoId + ".webm");
    }

    public static boolean exists(Context c, String videoId) {
        File f = fileFor(c, videoId);
        return f.isFile() && f.length() > 0;
    }

    /** Download full url to cache. Throws with stage info on first failure (no retry loop). */
    public static CacheResult download(Context c, String videoId, String url, long total) throws Exception {
        if (total <= 0) throw new Exception("CACHE total<=0");
        VisionOsNet.noteRequest(new URL(url).getHost());
        File part = new File(dir(c), videoId + ".webm.part");
        File fin = fileFor(c, videoId);
        if (part.exists()) part.delete();
        FileOutputStream out = new FileOutputStream(part);
        long received = 0;
        int chunks = 0;
        try {
            while (received < total) {
                long end = Math.min(received + CHUNK - 1, total - 1);
                HttpURLConnection h = null;
                try {
                    h = (HttpURLConnection) new URL(url).openConnection();
                    h.setRequestMethod("GET");
                    h.setConnectTimeout(TIMEOUT_MS);
                    h.setReadTimeout(TIMEOUT_MS);
                    h.setRequestProperty("Range", "bytes=" + received + "-" + end);
                    h.setRequestProperty("User-Agent", UA);
                    int status = h.getResponseCode();
                    Log.d(TAG, "chunk " + chunks + " range=" + received + "-" + end + " status=" + status);
                    if (status != 206) {
                        throw new Exception("CACHE chunk HTTP " + status + " at " + received);
                    }
                    String cr = h.getHeaderField("Content-Range");
                    InputStream in = h.getInputStream();
                    byte[] buf = new byte[65536];
                    int n;
                    long want = end - received + 1;
                    long got = 0;
                    while (got < want && (n = in.read(buf, 0, (int) Math.min(buf.length, want - got))) != -1) {
                        out.write(buf, 0, n);
                        got += n;
                    }
                    in.close();
                    if (got != want) throw new Exception("CACHE short chunk at " + received);
                    received += got;
                    chunks++;
                } finally {
                    if (h != null) h.disconnect();
                }
            }
        } catch (Exception e) {
            try {
                out.close();
            } catch (Exception ignored) {}
            part.delete();
            throw e;
        }
        out.close();
        if (part.length() != total || !ebmlMagic(part)) {
            part.delete();
            throw new Exception("CACHE validation failed size=" + part.length() + " want=" + total);
        }
        if (fin.exists()) fin.delete();
        if (!part.renameTo(fin)) {
            part.delete();
            throw new Exception("CACHE atomic rename failed");
        }
        Log.d(TAG, "cache write PASS bytes=" + total + " chunks=" + chunks + " path=" + fin.getAbsolutePath());
        return new CacheResult(fin, total, chunks);
    }

    static boolean ebmlMagic(File f) {
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream(f);
            byte[] b = new byte[4];
            int n = 0;
            while (n < 4) {
                int r = in.read(b, n, 4 - n);
                if (r < 0) break;
                n += r;
            }
            return n == 4 && (b[0] & 0xFF) == 0x1A && (b[1] & 0xFF) == 0x45
                    && (b[2] & 0xFF) == 0xDF && (b[3] & 0xFF) == 0xA3;
        } catch (Exception e) {
            return false;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {}
        }
    }

    /** content:// URI for the cached file (same-process WebView media playback). */
    public static Uri contentUri(Context c, String videoId) {
        File f = fileFor(c, videoId);
        return FileProvider.getUriForFile(c, c.getPackageName() + ".fileprovider", f);
    }
}
