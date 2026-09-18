package com.dnialify.musicstream;

import android.content.Context;
import android.util.Log;
import org.json.JSONObject;

/**
 * Cache event bus: one payload shape for WebView (__nativeEvent) + ADB log.
 * Throttling lives with callers (progress per RECORD threshold, not per byte).
 */
public final class CacheEvents {
    static final String TAG = "DnialifyVisionOS";

    private CacheEvents() {}

    public static void emit(Context c, String event, String videoId, JSONObject record) {
        try {
            JSONObject p = new JSONObject();
            p.put("channel", "cache");
            p.put("event", event);
            p.put("videoId", videoId);
            if (record != null) {
                p.put("status", record.optString("cacheStatus", "?"));
                p.put("downloadedBytes", record.optLong("downloadedBytes", 0));
                p.put("totalBytes", record.optLong("audioSize", 0));
                p.put("percent", record.optDouble("downloadPercent", 0));
                if (record.has("failReason")) {
                    p.put("reason", String.valueOf(record.opt("failReason")));
                }
                if (record.has("title")) p.put("title", record.optString("title", ""));
                if (record.has("artist")) p.put("artist", record.optString("artist", ""));
            }
            final String js = "try{if(window.__nativeEvent)window.__nativeEvent("
                    + JSONObject.quote(p.toString()) + ");}catch(e){}";
            try {
                MainActivity a = MainActivity.current;
                if (a != null) {
                    a.runOnUiThread(() -> {
                        try {
                            android.webkit.WebView wv = a.getBridge() != null
                                    ? a.getBridge().getWebView() : null;
                            if (wv != null) wv.evaluateJavascript(js, null);
                        } catch (Exception ignored) {}
                    });
                }
            } catch (Exception ignored) {}
            Log.d(TAG, "[CACHE-EVENT] " + p);
        } catch (Exception ignored) {}
    }
}
