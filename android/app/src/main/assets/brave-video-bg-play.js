// Reference: https://github.com/brave/adblock-resources/blob/master/resources/brave-video-bg-play.js
// Adapted only for stock Android WebView. YouTube remains video source.
(function(){
  const h = window.location.hostname;
  const isYoutube = /(?:^|\.)youtube(?:-nocookie)?\.com$/.test(h);
  const isMobileYoutube = h === 'm.youtube.com';
  const isDesktopYoutube = isYoutube && !isMobileYoutube;
  const isAndroid = /Android/i.test(navigator.userAgent);
  if (isAndroid || !isDesktopYoutube) {
    try { Object.defineProperties(document, { hidden:{value:false}, visibilityState:{value:'visible'} }); } catch(e) {}
  }
  window.addEventListener('visibilitychange', function(e){ e.stopImmediatePropagation(); }, true);
  if (isYoutube) {
    function refreshLact() { try { window._lact = Date.now(); } catch(e) {} }
    function wait() {
      if (Object.prototype.hasOwnProperty.call(window, '_lact')) {
        refreshLact();
        window.setInterval(refreshLact, 5 * 60 * 1000);
      } else window.setTimeout(wait, 1000);
    }
    wait();
  }
})();
