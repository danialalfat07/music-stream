package com.dnialify.musicstream;

import android.content.Context;
import fi.iki.elonen.NanoHTTPD;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONObject;

/**
 * Local HTTP proxy: MediaPlayer reads cache files through
 * http://127.0.0.1:PORT/local-stream/{videoId} instead of setDataSource(path).
 * A growing .part file looks like an ordinary (stalling, never truncating)
 * HTTP stream: Range seeks land instantly when bytes exist, block (hold)
 * while the writer fills, and EOF only at true end. No re-prepare gaps.
 */
public class LocalStreamServer extends NanoHTTPD {
    static final String TAG = "DnialifyVisionOS";
    private static final int HOLD_POLL_MS = 100;
    private static final int HOLD_TIMEOUT_MS = 90000;
    private static final int HOLD_EXTENSION_MS = 30000;
    private static final int MAX_HOLD_EXTENSIONS = 3;
    private static final int SERVE_BUF = 65536;

    private final Context appCtx;

    static final class Track {
        final String videoId;
        volatile String path;
        volatile long total;
        Track(String videoId, String path, long total) {
            this.videoId = videoId;
            this.path = path;
            this.total = total;
        }
    }

    private final Map<String, Track> tracks = new ConcurrentHashMap<>();

    public LocalStreamServer(Context c) {
        super(0); // port 0 = OS picks a free port
        appCtx = c.getApplicationContext();
    }

    public int port() {
        return getListeningPort();
    }

    public void register(String videoId, String path, long total) {
        if (videoId == null || videoId.isEmpty() || path == null) return;
        tracks.put(videoId, new Track(videoId, path, total));
    }

    public void unregister(String videoId) {
        if (videoId == null) return;
        tracks.remove(videoId);
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        String vid = null;
        try {
            if (uri != null && uri.startsWith("/local-stream/")) {
                vid = uri.substring("/local-stream/".length());
                int q = vid.indexOf('?');
                if (q >= 0) vid = vid.substring(0, q);
                int s = vid.indexOf('/');
                if (s >= 0) vid = vid.substring(0, s);
            }
        } catch (Exception ignored) {}
        Track t = vid != null ? tracks.get(vid) : null;
        Log.d(TAG, "[LSS] request range=" + String.valueOf(session.getHeaders().get("range"))
                + " videoId=" + vid + " total=" + (t == null ? -1 : t.total));
        if (t == null) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND,
                    "text/plain", "gone");
        }
        long total = t.total;
        if (total <= 0) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND,
                    "text/plain", "no-length");
        }
        long start = 0;
        long end = total - 1;
        try {
            String rg = session.getHeaders().get("range");
            if (rg == null) rg = session.getHeaders().get("Range");
            if (rg != null && rg.startsWith("bytes=")) {
                String spec = rg.substring(6).trim();
                int dash = spec.indexOf('-');
                if (dash >= 0) {
                    String a = spec.substring(0, dash).trim();
                    String b = spec.substring(dash + 1).trim();
                    if (!a.isEmpty()) start = Math.max(0, Long.parseLong(a));
                    if (!b.isEmpty()) end = Math.min(total - 1, Long.parseLong(b));
                }
            }
        } catch (Exception ignored) {}
        if (start < 0) start = 0;
        if (end >= total) end = total - 1;
        if (start > end) {
            Response r = newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE,
                    "text/plain", "bad-range");
            r.addHeader("Content-Range", "bytes */" + total);
            return r;
        }
        boolean partial = session.getHeaders().get("range") != null
                || session.getHeaders().get("Range") != null;
        HoldStream in = new HoldStream(t, start, end);
        Log.d(TAG, "[LSS] response start=" + start + " end=" + end
                + " expectedBytes=" + (end - start + 1) + " total=" + total);
        Response r;
        if (partial) {
            r = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT,
                    "audio/webm", in, end - start + 1);
            r.addHeader("Content-Range",
                    "bytes " + start + "-" + end + "/" + total);
        } else {
            r = newFixedLengthResponse(Response.Status.OK, "audio/webm", in, total);
        }
        r.addHeader("Accept-Ranges", "bytes");
        return r;
    }

    /** Blocking stream: reads file, holds (max 90s + extensions) while writer fills. */
    final class HoldStream extends InputStream {
        private final Track track;
        private final long end;
        private long pos;
        private volatile boolean closed;
        private long bornAt = System.currentTimeMillis();
        private int holdExtensions = 0;
        private long lastLoggedHave = -1;
        private boolean loggedHold;

        HoldStream(Track track, long start, long end) {
            this.track = track;
            this.pos = start;
            this.end = end;
        }

        private boolean complete() {
            try {
                JSONObject rec = SongCache.getRecord(appCtx, track.videoId);
                return rec != null
                        && SongCache.ST_COMPLETE.equals(rec.optString("cacheStatus", ""));
            } catch (Exception ignored) {
                return false;
            }
        }

        private File sourceFile(boolean isComplete) {
            if (isComplete) {
                File fin = SongCache.audioFile(appCtx, track.videoId);
                if (fin.isFile() && fin.length() == track.total) return fin;
            }
            return new File(track.path);
        }

        @Override
        public int read() throws IOException {
            byte[] b = new byte[1];
            int n = read(b, 0, 1);
            return n < 0 ? -1 : (b[0] & 0xFF);
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            if (closed) return -1;
            if (buf == null) throw new NullPointerException();
            if (len <= 0) return 0;
            // track switched/stopped meanwhile -> close, don't serve stale bytes
            Track cur = tracks.get(track.videoId);
            if (cur == null || cur != track) return -1;
            if (pos > end) return -1;
            for (;;) {
                if (closed) return -1;
                Track live = tracks.get(track.videoId);
                if (live == null || live != track) return -1;
                boolean isComplete = complete();
                File source = sourceFile(isComplete);
                long have = source.isFile() ? source.length() : 0;
                if (have != lastLoggedHave) {
                    lastLoggedHave = have;
                    Log.d(TAG, "[LSS] file size now=" + have + " expectedTotal="
                            + track.total + " writerState=" + (isComplete ? "COMPLETE" : "RUNNING_OR_PAUSED")
                            + " path=" + source.getName());
                }
                if (pos < have) {
                    long want = Math.min((long) len, Math.min(end, have - 1) - pos + 1);
                    if (want <= 0) return -1;
                    RandomAccessFile raf = null;
                    try {
                        raf = new RandomAccessFile(source, "r");
                        raf.seek(pos);
                        int n = raf.read(buf, off, (int) Math.min(want, SERVE_BUF));
                        if (n < 0) return -1;
                        pos += n;
                        return n;
                    } catch (IOException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new IOException(String.valueOf(e));
                    } finally {
                        try {
                            if (raf != null) raf.close();
                        } catch (Exception ignored) {}
                    }
                }
                // At complete state, re-resolve final file before declaring EOF.
                if (isComplete) {
                    File fin = SongCache.audioFile(appCtx, track.videoId);
                    if (fin.isFile() && fin.length() == track.total && pos < fin.length()) {
                        continue;
                    }
                    if (pos >= track.total) return -1;
                    Log.d(TAG, "[LSS] writer COMPLETE or true EOF pos=" + pos
                            + " total=" + track.total + " finalFile=" + fin.getName() + " closing");
                    return -1;
                }
                if (pos >= track.total) return -1;
                if (!loggedHold) {
                    loggedHold = true;
                    Log.d(TAG, "[LSS] reached current EOF, waiting for writer pos=" + pos
                            + " have=" + have + " end=" + end);
                }
                if (System.currentTimeMillis() - bornAt > HOLD_TIMEOUT_MS) {
                    boolean failed = false;
                    try {
                        JSONObject rec = SongCache.getRecord(appCtx, track.videoId);
                        failed = rec != null
                                && SongCache.ST_FAILED.equals(rec.optString("cacheStatus", ""));
                    } catch (Exception ignored) {}
                    if (isComplete || failed) {
                        Log.d(TAG, "[LSS] hold timeout exceeded " + (HOLD_TIMEOUT_MS / 1000)
                                + "s pos=" + pos + " have=" + have + " end=" + end
                                + " writerState=" + (isComplete ? "COMPLETE" : "FAILED"));
                        return -1;
                    }
                    if (have < track.total && holdExtensions < MAX_HOLD_EXTENSIONS) {
                        holdExtensions++;
                        bornAt += HOLD_EXTENSION_MS;
                        Log.w(TAG, "[LSS] hold timeout, forcing window advance for videoId="
                                + track.videoId + " ext=" + holdExtensions + "/" + MAX_HOLD_EXTENSIONS);
                        try {
                            NativeAudioEngine.requestWindowAdvance(track.videoId);
                        } catch (Exception ignored) {}
                    } else {
                        Log.d(TAG, "[LSS] hold timeout exceeded " + (HOLD_TIMEOUT_MS / 1000)
                                + "s pos=" + pos + " have=" + have + " end=" + end
                                + " ext=" + holdExtensions);
                        return -1;
                    }
                }
                try {
                    Thread.sleep(HOLD_POLL_MS);
                } catch (InterruptedException e) {
                    return -1;
                }
            }
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
