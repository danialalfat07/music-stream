// Reference: https://github.com/brave/adblock-resources/blob/master/resources/brave-disable-pageview-api.js
(function(){
  document.addEventListener('visibilitychange', function(e){ e.stopImmediatePropagation(); }, true);
  Object.defineProperty(Document.prototype, 'hidden', {
    get: function(){ return false; }, enumerable: true, configurable: true
  });
  Object.defineProperty(Document.prototype, 'visibilityState', {
    get: function(){ return 'visible'; }, enumerable: true, configurable: true
  });
})();
