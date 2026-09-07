// Brave reference: brave/adblock-resources/resources/brave-video-bg-play.js + brave-disable-pageview-api.js
// Verbatim MIT, adapted for Music Stream stock WebView + Capacitor
// Source: https://github.com/brave/adblock-resources/raw/master/resources/brave-video-bg-play.js
// Source: https://github.com/brave/adblock-resources/raw/master/resources/brave-disable-pageview-api.js
(function(){
// --- brave-disable-pageview-api (strict Document.prototype) ---
try {
  Object.defineProperty(Document.prototype, "hidden", { get: function(){ return false; }, enumerable:true, configurable:true });
} catch(e){}
try {
  Object.defineProperty(Document.prototype, "visibilityState", { get: function(){ return "visible"; }, enumerable:true, configurable:true });
} catch(e){}
try {
  Object.defineProperty(Document.prototype, "webkitHidden", { get: function(){ return false; }, enumerable:true, configurable:true });
} catch(e){}
try {
  Object.defineProperty(Document.prototype, "webkitVisibilityState", { get: function(){ return "visible"; }, enumerable:true, configurable:true });
} catch(e){}
try { document.addEventListener("visibilitychange", function(e){ e.stopImmediatePropagation(); }, true); } catch(e){}
try { document.addEventListener("webkitvisibilitychange", function(e){ e.stopImmediatePropagation(); }, true); } catch(e){}
try { window.addEventListener("visibilitychange", function(e){ e.stopImmediatePropagation(); }, true); } catch(e){}
try { window.addEventListener("webkitvisibilitychange", function(e){ e.stopImmediatePropagation(); }, true); } catch(e){}

// --- brave-video-bg-play (IS_ANDROID logic + _lact) ---
try {
  const IS_YOUTUBE = window.location.hostname.search(/(?:^|.+\.)youtube\.com/) > -1 || window.location.hostname.search(/(?:^|.+\.)youtube-nocookie\.com/) > -1;
  const IS_MOBILE_YOUTUBE = window.location.hostname == 'm.youtube.com';
  const IS_DESKTOP_YOUTUBE = IS_YOUTUBE && !IS_MOBILE_YOUTUBE;
  const IS_VIMEO = window.location.hostname.search(/(?:^|.+\.)vimeo\.com/) > -1;
  const IS_ANDROID = window.navigator.userAgent.indexOf('Android') > -1;
  if (IS_ANDROID || !IS_DESKTOP_YOUTUBE) {
    try { Object.defineProperties(document, { 'hidden': {value: false}, 'visibilityState': {value: 'visible'} }); } catch(e){}
    try { Object.defineProperties(document, { 'webkitHidden': {value: false}, 'webkitVisibilityState': {value: 'visible'} }); } catch(e){}
  }
  if (IS_VIMEO) {
    try { window.addEventListener('fullscreenchange', function(e){ e.stopImmediatePropagation(); }, true); } catch(e){}
  }
  // keep YouTube alive - _lact refresh every 5min (Brave #38910)
  if (IS_YOUTUBE) {
    function waitForYoutubeLactInit(aCallback, aCallbackInterval, aDelay) {
      aDelay = aDelay || 1000;
      var pageWin = window;
      if (pageWin.hasOwnProperty('_lact')) {
        window.setInterval(aCallback, aCallbackInterval);
      } else {
        window.setTimeout(function(){ waitForYoutubeLactInit(aCallback, aCallbackInterval, aDelay*2); }, aDelay);
      }
    }
    function refreshLact(){ try{ window._lact = Date.now(); }catch(e){} }
    try { waitForYoutubeLactInit(function(){ refreshLact(); }, 5*60*1000); } catch(e){}
    // also watch for dynamically added iframes (YT player iframe)
    try {
      var obs = new MutationObserver(function(muts){
        muts.forEach(function(m){
          m.addedNodes.forEach(function(n){
            if (n.tagName === 'IFRAME' && n.contentWindow) {
              try {
                Object.defineProperty(n.contentDocument, 'hidden', {get:function(){return false}, configurable:true});
                Object.defineProperty(n.contentDocument, 'visibilityState', {get:function(){return 'visible'}, configurable:true});
                n.contentWindow.addEventListener('visibilitychange', function(e){e.stopImmediatePropagation();}, true);
              } catch(e){}
            }
          });
        });
      });
      obs.observe(document.documentElement, {childList:true, subtree:true});
    } catch(e){}
  }
} catch(e){}
})();
