# F5: ProGuard/R8 rules for Dnialify MusicStream.
#
# Keep all app classes (PlaybackBridge and friends are reached
# reflectively from the WebView via @JavascriptInterface).
-keep class com.dnialify.musicstream.** { *; }

# MANDATORY: every @JavascriptInterface method must survive
# obfuscation or the WebView JS bridge breaks silently.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep Capacitor plugin entry points (loaded reflectively).
-keep class com.getcapacitor.** { *; }
-keep @com.getcapacitor.annotation.CapacitorPlugin class * { *; }
-keep class * extends com.getcapacitor.Plugin { *; }

# Keep WebView JS interface classes explicitly (belt & braces).
-keepclassmembers class com.dnialify.musicstream.MainActivity$* {
    public *;
}
