package com.dnialify.musicstream;

import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Locale;

/**
 * Phase 8 — loopback HTTP server for cached audio (diagnostic aid, tiny).
 *
 * WHY: page-context fetch()/HTML5 audio cannot read content:// FileProvider
 * URIs (proven: fetch=TypeError Failed to fetch, audio code=4). A same-device
 * http://127.0.0.1 origin serves the EXACT cached bytes with proper Range/206
 * + audio/webm MIME. No proxy, no network, no new dependency, no.yt-dlp.
 * Binds 127.0.0.1 only, ephemeral port, serves only files under the
 * visionos_cache dir. Stop with stop().
 */
public final class VisionOsLoopback {
    private static final String TAG = "DnialifyVisionOS";
    private static ServerSocket server;
    private static Thread thread;
    private static int port;
    private static File dir;

    private VisionOsLoopback() {}

    public static synchronized String baseUrl(android.content.Context c) {
        try {
            if (server == null || server.isClosed()) {
                dir = VisionOsCache.dir(c);
                server = new ServerSocket(0, 4,
                        java.net.InetAddress.getByName("127.0.0.1"));
                port = server.getLocalPort();
                thread = new Thread(VisionOsLoopback::loop);
                thread.setDaemon(true);
                thread.start();
                Log.d(TAG, "loopback START http://127.0.0.1:" + port);
            }
            return "http://127.0.0.1:" + port;
        } catch (Exception e) {
            Log.d(TAG, "loopback START FAIL " + e);
            return null;
        }
    }

    public static synchronized void stop() {
        try {
            if (server != null) server.close();
        } catch (Exception ignored) {}
        server = null;
        Log.d(TAG, "loopback STOP");
    }

    /** URL for a cached videoId, or null when no cache file. */
    public static String urlFor(android.content.Context c, String videoId) {
        File f = VisionOsCache.fileFor(c, videoId);
        if (!f.isFile() || f.length() == 0) return null;
        String base = baseUrl(c);
        return base == null ? null : base + "/" + videoId + ".webm";
    }

    private static void loop() {
        while (server != null && !server.isClosed()) {
            try {
                Socket s = server.accept();
                new Thread(() -> serve(s)).start();
            } catch (Exception e) {
                break;
            }
        }
    }

    private static void serve(Socket s) {
        try {
            s.setSoTimeout(15000);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            StringBuilder head = new StringBuilder();
            int b;
            while ((b = in.read()) != -1) {
                head.append((char) b);
                if (head.length() > 8192
                        || head.toString().endsWith("\r\n\r\n")) break;
            }
            String[] lines = head.toString().split("\r\n");
            String req = lines.length > 0 ? lines[0] : "";
            String range = null;
            for (String l : lines) {
                if (l.toLowerCase(Locale.US).startsWith("range:")) {
                    range = l.substring(6).trim();
                }
            }
            String path = "/";
            try {
                String[] parts = req.split(" ");
                if (parts.length >= 2) path = parts[1];
            } catch (Exception ignored) {}
            String name = path.startsWith("/") ? path.substring(1) : path;
            int q = name.indexOf('?');
            if (q >= 0) name = name.substring(0, q);
            File f = new File(dir, name);
            // jail: flat <id>.webm directly under cache dir, nothing else
            boolean okName = name.matches("[A-Za-z0-9_-]+\\.webm");
            boolean okPlace = false;
            boolean okFile = false;
            try {
                okPlace = f.getParentFile() != null
                        && f.getParentFile().getCanonicalPath()
                                .equals(dir.getCanonicalPath());
                okFile = f.isFile() && f.length() > 0;
            } catch (Exception ignored) {}
            if (!okName || !okPlace || !okFile) {
                Log.d(TAG, "loopback 404 req=" + req + " name=" + name);
                write(out, "HTTP/1.1 404 Not Found\r\nConnection: close\r\n"
                        + "Content-Length: 0\r\n\r\n", null, 0, -1);
                return;
            }
            long total = f.length();
            long start = 0;
            long end = total - 1;
            boolean partial = false;            if (range != null && range.startsWith("bytes=")) {
                try {
                    String r = range.substring(6);
                    int dash = r.indexOf('-');
                    if (dash == 0) {
                        long suf = Long.parseLong(r.substring(1));
                        start = Math.max(0, total - suf);
                    } else if (dash > 0) {
                        start = Long.parseLong(r.substring(0, dash));
                        if (dash < r.length() - 1) {
                            end = Long.parseLong(r.substring(dash + 1));
                        }
                    }
                    partial = true;
                } catch (Exception ignored) {
                    partial = false;
                }
            }
            if (start < 0) start = 0;
            if (end >= total) end = total - 1;
            if (start > end) {
                write(out, "HTTP/1.1 416 Range Not Satisfiable\r\n"
                        + "Content-Range: bytes */" + total + "\r\n"
                        + "Connection: close\r\nContent-Length: 0\r\n\r\n",
                        null, 0, -1);
                return;
            }
            long len = end - start + 1;
            StringBuilder h = new StringBuilder();
            h.append(partial ? "HTTP/1.1 206 Partial Content\r\n"
                    : "HTTP/1.1 200 OK\r\n");
            h.append("Content-Type: audio/webm\r\n");
            h.append("Accept-Ranges: bytes\r\n");
            if (partial) h.append("Content-Range: bytes " + start + "-" + end + "/" + total + "\r\n");
            h.append("Content-Length: " + len + "\r\n");
            h.append("Connection: close\r\n\r\n");
            Log.d(TAG, "loopback " + (partial ? 206 : 200) + " req=" + req
                    + " range=" + range + " serve=" + start + "-" + end + "/" + total);
            write(out, h.toString(), f, start, len);
        } catch (Exception ignored) {
        } finally {
            try {
                s.close();
            } catch (Exception ignored) {}
        }
    }

    private static void write(OutputStream out, String headers, File f,
            long start, long len) {
        try {
            out.write(headers.getBytes("UTF-8"));
            if (f != null && len > 0) {
                FileInputStream fin = new FileInputStream(f);
                try {
                    long skipped = 0;
                    while (skipped < start) {
                        long n = fin.skip(start - skipped);
                        if (n <= 0) break;
                        skipped += n;
                    }
                    byte[] buf = new byte[65536];
                    long left = len;
                    int n;
                    while (left > 0
                            && (n = fin.read(buf, 0,
                                    (int) Math.min(buf.length, left))) != -1) {
                        out.write(buf, 0, n);
                        left -= n;
                    }
                } finally {
                    fin.close();
                }
            }
            out.flush();
        } catch (Exception ignored) {}
    }
}
