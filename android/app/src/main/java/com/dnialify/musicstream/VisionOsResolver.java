package com.dnialify.musicstream;

import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Phase 8 - native VisionOS resolver.
 *
 * Direct adaptation of the proven pipeline (same endpoint, body, UA, selection
 * as the validated server/visionostest flow). No new approach:
 *   visitorData (session-cached, NOT per song) -> VisionOS InnerTube player
 *   -> itag 251 AUDIO-ONLY WebM/Opus preferred -> direct googlevideo URL.
 *
 * No retries without bound: single attempt, plus ONE forced visitorData refresh
 * when the player answers LOGIN_REQUIRED. No proxy, no HLS, no SABR, no yt-dlp.
 *
 * Failure contract (never blind-fallback; caller decides iFrame fallback from reason):
 *   LOGIN_REQUIRED    -> refresh visitorData ONCE, re-resolve, retry max 1x.
 *                        Still failing -> caller falls back to iFrame.
 *   any other reason  -> NO retry; caller logs reason first, then falls back to iFrame.
 * Reason codes: LOGIN_REQUIRED, RESOLVER_FAIL, HTTP_FAIL, GVIDEO_403,
 *   UNSUPPORTED_MEDIA, MEDIA_ERROR, TIMEOUT, NETWORK_ERROR.
 */
public final class VisionOsResolver {
    public static final String R_LOGIN_REQUIRED = "LOGIN_REQUIRED";
    public static final String R_RESOLVER_FAIL = "RESOLVER_FAIL";
    public static final String R_HTTP_FAIL = "HTTP_FAIL";
    public static final String R_GVIDEO_403 = "GVIDEO_403";
    public static final String R_UNSUPPORTED_MEDIA = "UNSUPPORTED_MEDIA";
    public static final String R_MEDIA_ERROR = "MEDIA_ERROR";
    public static final String R_TIMEOUT = "TIMEOUT";
    public static final String R_NETWORK_ERROR = "NETWORK_ERROR";
    private static final String TAG_DIAG = "DnialifyDiag";
    private static final String GAPIS = "https://youtubei.googleapis.com/youtubei/v1/";
    private static final String CLIENT_NAME = "VISIONOS";
    private static final String CLIENT_VERSION = "1.02";
    private static final String DEVICE_MODEL = "RealityDevice14,1";
    private static final String OS_NAME = "visionOS";
    private static final String OS_VERSION = "25.6.0.23O471";
    private static final String UA =
            "com.google.visionos.youtube/1.02(RealityDevice14,1; U; CPU visionOS 25_6_0 like Mac OS X; US)";
    private static final int TIMEOUT_MS = 20000;
    private static final long VISITOR_TTL_MS = 60L * 60L * 1000L;

    private static String cachedVisitorData;
    private static long visitorFetchedAt;

    private VisionOsResolver() {}

    public static final class ResolverException extends Exception {
        public final String stage;
        public final String reason;
        ResolverException(String stage, String message) {
            this(stage, null, message);
        }
        ResolverException(String stage, String reason, String message) {
            super(message);
            this.stage = stage;
            this.reason = reason;
        }
    }

    /**
     * Fallback rule for callers (playback stays safe, errors never hidden):
     *  - First LOGIN_REQUIRED is consumed by the ONE internal visitor refresh.
     *  - If the retry ALSO answers LOGIN_REQUIRED, a ResolverException with
     *    reason LOGIN_REQUIRED escapes ("after 1 visitor refresh") -> caller
     *    logs it, then falls back to iFrame. No further retry.
     *  - Any other reason -> caller logs reason first, then falls back
     *    to iFrame. No retry.
     */
    public static boolean isLoginRequired(ResolverException e) {
        return e != null && R_LOGIN_REQUIRED.equals(e.reason);
    }

    public static final class Result {
        public final String url;
        public final int itag;
        public final String mimeType;
        public final int bitrate;
        public final String host;
        public final boolean visitorFromCache;
        public final String title;
        public final String author;
        public final String thumbnail;
        public final long durationMs;
        public final boolean retriedVisitor;
        public final int formatCount;

        Result(String url, int itag, String mimeType, int bitrate, String host, boolean visitorFromCache,
                String title, String author, String thumbnail, long durationMs, boolean retriedVisitor,
                int formatCount) {
            this.url = url;
            this.itag = itag;
            this.mimeType = mimeType;
            this.bitrate = bitrate;
            this.host = host;
            this.visitorFromCache = visitorFromCache;
            this.title = title;
            this.author = author;
            this.thumbnail = thumbnail;
            this.durationMs = durationMs;
            this.retriedVisitor = retriedVisitor;
            this.formatCount = formatCount;
        }
    }

    /** ms since visitorData was fetched (0 = none). Additive for harness. */
    public static synchronized long visitorAgeMs() {
        if (cachedVisitorData == null) return -1;
        return System.currentTimeMillis() - visitorFetchedAt;
    }

    /** Count of formats[] + adaptiveFormats[] entries. Additive for harness. */
    static int countFormats(JSONObject streamingData) {
        try {
            int n = 0;
            JSONArray a = streamingData.optJSONArray("adaptiveFormats");
            JSONArray f = streamingData.optJSONArray("formats");
            if (a != null) n += a.length();
            if (f != null) n += f.length();
            return n;
        } catch (Exception e) {
            return -1;
        }
    }

    public static synchronized void clearVisitorCache() {
        cachedVisitorData = null;
        visitorFetchedAt = 0;
    }

    /** Session-cached visitorData. force=true bypasses cache (LOGIN_REQUIRED retry only). */
    public static synchronized String getVisitorData(boolean force) throws ResolverException {
        if (!force && cachedVisitorData != null && System.currentTimeMillis() - visitorFetchedAt < VISITOR_TTL_MS) {
            return cachedVisitorData;
        }
        String vd = fetchVisitorData();
        cachedVisitorData = vd;
        visitorFetchedAt = System.currentTimeMillis();
        return vd;
    }

    public static synchronized boolean hasFreshVisitor() {
        return cachedVisitorData != null && System.currentTimeMillis() - visitorFetchedAt < VISITOR_TTL_MS;
    }

    private static String fetchVisitorData() throws ResolverException {
        try {
            JSONObject client = baseClient();
            JSONObject body = new JSONObject().put("context", new JSONObject().put("client", client));
            JSONObject resp = postJson(GAPIS + "visitor_id?prettyPrint=false", body);
            String vd = resp.optJSONObject("responseContext") != null
                    ? resp.optJSONObject("responseContext").optString("visitorData", null) : null;
            if (vd == null || vd.isEmpty()) {
                throw new ResolverException("VISITORDATA", R_RESOLVER_FAIL, "empty visitorData in response");
            }
            Log.d(TAG_DIAG, "VisionOsResolver visitorData PASS (fresh)");
            return vd;
        } catch (ResolverException e) {
            throw e;
        } catch (Exception e) {
            throw new ResolverException("VISITORDATA", classifyException(e), "visitor_id threw " + e);
        }
    }

    /** Resolve direct audio URL. Returns best audio (opus preferred, then highest bitrate). */
    public static Result resolve(String videoId) throws ResolverException {
        return resolve(videoId, false);
    }

    private static Result resolve(String videoId, boolean retried) throws ResolverException {
        final boolean[] usedCache = new boolean[1];
        String vd;
        synchronized (VisionOsResolver.class) {
            usedCache[0] = !retried && cachedVisitorData != null
                    && System.currentTimeMillis() - visitorFetchedAt < VISITOR_TTL_MS;
        }
        vd = retried ? refreshVisitor() : getVisitorData(false);
        if (!retried) {
            synchronized (VisionOsResolver.class) {
                usedCache[0] = cachedVisitorData != null && vd.equals(cachedVisitorData)
                        && System.currentTimeMillis() - visitorFetchedAt < VISITOR_TTL_MS + 5000;
            }
        }
        JSONObject data = postPlayer(videoId, vd);
        if (data == null) {
            throw new ResolverException("RESOLVER", R_HTTP_FAIL, "player HTTP error (no body)");
        }
        JSONObject ps = data.optJSONObject("playabilityStatus");
        JSONObject sd = data.optJSONObject("streamingData");
        if (sd == null) {
            String status = ps != null ? ps.optString("status", "?") : "?";
            String reason = ps != null ? ps.optString("reason", "?") : "?";
            if ("LOGIN_REQUIRED".equals(status)) {
                if (!retried) {
                    Log.d(TAG_DIAG, "VisionOsResolver reason=LOGIN_REQUIRED attempt=1/2"
                            + " action=refresh-visitorData-then-retry-once");
                    return resolve(videoId, true);
                }
                Log.d(TAG_DIAG, "VisionOsResolver reason=LOGIN_REQUIRED attempt=2/2"
                        + " result=STILL_FAILING action=caller-fallback-iFrame");
                throw new ResolverException("RESOLVER", R_LOGIN_REQUIRED,
                        "still LOGIN_REQUIRED after 1 visitor refresh reason=" + reason);
            }
            throw new ResolverException("RESOLVER", R_RESOLVER_FAIL,
                    "no streamingData playability=" + status + " reason=" + reason);
        }
        JSONObject best = pickBestAudio(sd);
        int formatCount = countFormats(sd);
        if (best == null) {
            throw new ResolverException("RESOLVER", R_UNSUPPORTED_MEDIA,
                    "streamingData present but no audio mime (need audio/*, opus preferred)");
        }
        String url = best.optString("url", null);
        if (url == null || url.isEmpty()) {
            throw new ResolverException("RESOLVER", R_RESOLVER_FAIL,
                    "best itag " + best.optInt("itag", -1) + " has no url (ciphered?)");
        }
        String host = "";
        try {
            host = new URL(url).getHost();
        } catch (Exception ignored) {}
        Log.d(TAG_DIAG, "VisionOsResolver RESOLVER PASS itag=" + best.optInt("itag", -1)
                + " host=" + host + " retriedVisitor=" + retried);
        JSONObject videoDetails = data.optJSONObject("videoDetails");
        String title = videoDetails != null ? videoDetails.optString("title", null) : null;
        String author = videoDetails != null ? videoDetails.optString("author", null) : null;
        String thumb = null;
        if (videoDetails != null) {
            JSONObject tn = videoDetails.optJSONObject("thumbnail");
            JSONArray thumbs = tn != null ? tn.optJSONArray("thumbnails") : null;
            if (thumbs != null && thumbs.length() > 0) {
                thumb = thumbs.optJSONObject(thumbs.length() - 1).optString("url", null);
            }
        }
        long durMs = 0;
        try {
            long approx = best.optLong("approxDurationMs", 0);
            if (approx > 0) durMs = approx;
            else if (videoDetails != null) durMs = Long.parseLong(videoDetails.optString("lengthSeconds", "0")) * 1000L;
        } catch (Exception ignored) {}
        return new Result(url, best.optInt("itag", -1), best.optString("mimeType", null),
                best.optInt("bitrate", 0), host, usedCache[0], title, author, thumb, durMs, retried,
                formatCount);
    }

    private static synchronized String refreshVisitor() throws ResolverException {
        cachedVisitorData = null;
        visitorFetchedAt = 0;
        return getVisitorData(true);
    }

    private static JSONObject postPlayer(String videoId, String visitorData) throws ResolverException {
        try {
            JSONObject client = baseClient();
            client.put("visitorData", visitorData);
            JSONObject body = new JSONObject()
                    .put("context", new JSONObject().put("client", client))
                    .put("videoId", videoId)
                    .put("racyCheckOk", true)
                    .put("contentCheckOk", true);
            return postJson(GAPIS + "player?prettyPrint=false", body);
        } catch (ResolverException e) {
            throw e;
        } catch (Exception e) {
            throw new ResolverException("RESOLVER", classifyException(e), "player threw " + e);
        }
    }

    private static JSONObject baseClient() throws Exception {
        return new JSONObject()
                .put("clientName", CLIENT_NAME)
                .put("clientVersion", CLIENT_VERSION)
                .put("deviceModel", DEVICE_MODEL)
                .put("osName", OS_NAME)
                .put("osVersion", OS_VERSION)
                .put("hl", "en")
                .put("gl", "US");
    }

    /** Best audio = opus preferred, then highest bitrate (mirrors proven selection). */
    static JSONObject pickBestAudio(JSONObject streamingData) {
        try {
            JSONArray all = new JSONArray();
            JSONArray adaptive = streamingData.optJSONArray("adaptiveFormats");
            JSONArray formats = streamingData.optJSONArray("formats");
            if (adaptive != null) {
                for (int i = 0; i < adaptive.length(); i++) all.put(adaptive.get(i));
            }
            if (formats != null) {
                for (int i = 0; i < formats.length(); i++) all.put(formats.get(i));
            }
            JSONObject best = null;
            int bestOpus = -1;
            int bestBitrate = -1;
            for (int i = 0; i < all.length(); i++) {
                JSONObject f = all.optJSONObject(i);
                if (f == null) continue;
                String mime = f.optString("mimeType", "");
                if (!mime.contains("audio/")) continue;
                int opus = mime.contains("opus") ? 1 : 0;
                int br = f.optInt("bitrate", 0);
                if (best == null || opus > bestOpus || (opus == bestOpus && br > bestBitrate)) {
                    best = f;
                    bestOpus = opus;
                    bestBitrate = br;
                }
            }
            return best;
        } catch (Exception e) {
            return null;
        }
    }

    private static JSONObject postJson(String urlStr, JSONObject body) throws ResolverException {
        HttpURLConnection c = null;
        try {
            URL url = new URL(urlStr);
            VisionOsNet.noteRequest(url.getHost());
            c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("X-Goog-Api-Format-Version", "2");
            byte[] out = body.toString().getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(out.length);
            OutputStream os = c.getOutputStream();
            os.write(out);
            os.close();
            int status = c.getResponseCode();
            if (status < 200 || status > 299) {
                throw new ResolverException("HTTP", R_HTTP_FAIL, urlStr + " -> HTTP " + status);
            }
            InputStream is = c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            long total = 0;
            long cap = 4L * 1024L * 1024L;
            while ((n = is.read(buf)) != -1) {
                total += n;
                if (total > cap) break;
                bos.write(buf, 0, n);
            }
            is.close();
            return new JSONObject(bos.toString("UTF-8"));
        } catch (ResolverException e) {
            throw e;
        } catch (Exception e) {
            throw new ResolverException("HTTP", classifyException(e), urlStr + " threw " + e);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Transport exception -> reason. SocketTimeout = TIMEOUT, DNS/connect = NETWORK_ERROR. */
    static String classifyException(Exception e) {
        if (e instanceof java.net.SocketTimeoutException) return R_TIMEOUT;
        if (e instanceof java.net.UnknownHostException) return R_NETWORK_ERROR;
        if (e instanceof java.net.ConnectException) return R_NETWORK_ERROR;
        if (e instanceof java.net.NoRouteToHostException) return R_NETWORK_ERROR;
        String s = String.valueOf(e);
        if (s.contains("SocketTimeout") || s.contains("timed out")) return R_TIMEOUT;
        if (s.contains("UnknownHost") || s.contains("ENETUNREACH") || s.contains("EHOSTUNREACH")
                || s.contains("ECONNREFUSED") || s.contains("ECONNRESET")) return R_NETWORK_ERROR;
        return R_HTTP_FAIL;
    }

    /**
     * googlevideo fetch outcome -> reason. Used by chunk probes / cache download
     * reporting so ADB clearly shows GVIDEO_403 vs TIMEOUT vs NETWORK_ERROR.
     */
    public static String classifyFetch(int httpStatus, Exception e) {
        if (e != null) return classifyException(e);
        if (httpStatus == 403) return R_GVIDEO_403;
        if (httpStatus == 200 || httpStatus == 206) return null; // OK
        if (httpStatus <= 0) return R_NETWORK_ERROR;
        return R_HTTP_FAIL;
    }

    /** Best-effort reason guess from a plain (non-Resolver) exception message. */
    public static String reasonFromMessage(String msg) {
        String s = String.valueOf(msg);
        if (s.contains("HTTP 403")) return R_GVIDEO_403;
        if (s.matches("(?s).*HTTP [45][0-9][0-9].*")) return R_HTTP_FAIL;
        return classifyException(new Exception(s));
    }

    /** HTML5 MediaError code -> name. reason is always MEDIA_ERROR. */
    public static String mediaErrorName(int code) {
        switch (code) {
            case 1: return "MEDIA_ERR_ABORTED";
            case 2: return "MEDIA_ERR_NETWORK";
            case 3: return "MEDIA_ERR_DECODE";
            case 4: return "MEDIA_ERR_SRC_NOT_SUPPORTED";
            default: return "MEDIA_ERR_UNKNOWN(" + code + ")";
        }
    }
}
