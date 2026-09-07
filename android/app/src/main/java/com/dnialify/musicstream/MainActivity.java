package com.dnialify.musicstream;

import android.webkit.JavascriptInterface;
import android.Manifest;
import android.content.pm.PackageManager;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    private static final String BG_SPOOF_JS =
        "(function(){" +
        "function spoof(doc,win){" +
        " try{Object.defineProperty(doc,'hidden',{get:function(){return false;},configurable:true});}catch(e){}" +
        " try{Object.defineProperty(doc,'visibilityState',{get:function(){return 'visible';},configurable:true});}catch(e){}" +
        " try{Object.defineProperty(doc,'webkitHidden',{get:function(){return false;},configurable:true});}catch(e){}" +
        " try{Object.defineProperty(doc,'webkitVisibilityState',{get:function(){return 'visible';},configurable:true});}catch(e){}" +
        " try{Object.defineProperty(doc,'hasFocus',{value:function(){return true;},configurable:true});}catch(e){}" +
        " try{win.addEventListener('visibilitychange',function(e){e.stopImmediatePropagation();},true);}catch(e){}" +
        " try{doc.addEventListener('visibilitychange',function(e){e.stopImmediatePropagation();},true);}catch(e){}" +
        " try{win.addEventListener('webkitvisibilitychange',function(e){e.stopImmediatePropagation();},true);}catch(e){}" +
        " try{doc.addEventListener('webkitvisibilitychange',function(e){e.stopImmediatePropagation();},true);}catch(e){}" +
        " try{win.addEventListener('pagehide',function(e){e.stopImmediatePropagation();},true);}catch(e){}" +
        "}" +
        "try{spoof(document,window);}catch(e){}" +
        "try{" +
        " var obs=new MutationObserver(function(muts){" +
        "  muts.forEach(function(m){" +
        "   m.addedNodes.forEach(function(n){" +
        "    if(n.tagName==='IFRAME' && n.contentWindow){try{spoof(n.contentDocument,n.contentWindow);}catch(e){}}" +
        "   });" +
        "  });" +
        " });" +
        " obs.observe(document.documentElement,{childList:true,subtree:true});" +
        "}catch(e){}" +
        "})();";

    @Override
    public void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        // Bare/Brave: keep WebView media from pausing on Home — allow autoplay without gesture
        try { getBridge().getWebView().getSettings().setMediaPlaybackRequiresUserGesture(false); } catch (Exception ignored) {}
        try { getBridge().getWebView().getSettings().setDomStorageEnabled(true); } catch (Exception ignored) {}
        // Inject Brave-style visibility spoof early, keeps YT IFrame fallback playing in background (like Brave)
        try {
            getBridge().getWebView().setWebViewClient(new android.webkit.WebViewClient() {
                @Override
                public void onPageStarted(android.webkit.WebView view, String url, android.graphics.Bitmap favicon) {
                    super.onPageStarted(view, url, favicon);
                    try { view.evaluateJavascript(BG_SPOOF_JS, null); } catch (Exception ignored) {}
                }
                @Override
                public void onPageFinished(android.webkit.WebView view, String url) {
                    super.onPageFinished(view, url);
                    try { view.evaluateJavascript(BG_SPOOF_JS, null); } catch (Exception ignored) {}
                }
            });
        } catch (Exception ignored) {}
        getBridge().getWebView().addJavascriptInterface(new PlaybackBridge(), "NativePlayback");
        // also inject after bridge ready
        try { getBridge().getWebView().evaluateJavascript(BG_SPOOF_JS, null); } catch (Exception ignored) {}
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
        }
    }

    private final class PlaybackBridge {
        @JavascriptInterface
        public void arm() { PlaybackService.arm(MainActivity.this); }

        @JavascriptInterface
        public void play(String url, String title, String artist, String artwork) {
            PlaybackService.play(MainActivity.this, url, title, artist, artwork);
        }

        @JavascriptInterface
        public void pause() { PlaybackService.pause(MainActivity.this); }

        @JavascriptInterface
        public void seek(double seconds) { PlaybackService.seek(MainActivity.this, seconds); }

        @JavascriptInterface
        public boolean isPlaying() { return PlaybackService.isPlaying(); }

        @JavascriptInterface
        public boolean isEnded() { return PlaybackService.isEnded(); }

        @JavascriptInterface
        public double currentTime() { return PlaybackService.currentTime(); }

        @JavascriptInterface
        public double duration() { return PlaybackService.duration(); }

        @JavascriptInterface
        public void speed(double value) { PlaybackService.speed(MainActivity.this, value); }

        @JavascriptInterface
        public void volume(double value) { PlaybackService.volume(MainActivity.this, value); }
    }
}
