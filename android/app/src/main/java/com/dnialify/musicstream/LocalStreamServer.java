package com.dnialify.musicstream;

import android.content.Context;
import android.util.Log;
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
    private static final int HOLD_TIMEOUT_MS = 30000;
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

    /** Blocking stream: reads file, holds (max 30s) while writer fills. */
    final class HoldStream extends InputStream {
        private final Track track;
        private final long end;
        private long pos;
        private volatile boolean closed;
        private final long bornAt = System.currentTimeMillis();

        HoldStream(Track track, long start, long end) {
            this.track = track;
            this.pos = start;
            this.end = end;
        }

        private long fileLen() {
            try {
                File f = new File(track.path);
                if (f.isFile()) return f.length();
            } catch (Exception ignored) {}
            return 0;
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
                long have = fileLen();
                if (pos < have) {
                    long want = Math.min((long) len, Math.min(end, have - 1) - pos + 1);
                    if (want <= 0) return -1;
                    RandomAccessFile raf = null;
                    try {
                        raf = new RandomAccessFile(track.path, "r");
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
                // at current EOF: true end only when download COMPLETE
                if (complete() || pos >= track.total) return -1;
                if (System.currentTimeMillis() - bornAt > HOLD_TIMEOUT_MS) return -1;
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
