// ADB harness hook (injected via evaluateJavascript when absent).
// Drives/queries the PRODUCTION audio element + YT player. Control-only +
// event forwarding. Never changes production decisions. Mirrors the
// window.__vosDiag helper bundled in public/app.js for future deploys.
(function () {
  if (typeof window.__vosDiag === 'function') return 'present';
  window.__vosHost = function (u) {
    try { return new URL(u).host; } catch (e) { return String(u || '').slice(0, 60); }
  };
  window.__vosDiag = function (action, argJson) {
    var out = { ok: false };
    try {
      var a = Player.audio;
      var p = {};
      try { p = JSON.parse(argJson || '{}'); } catch (e) {}
      var dlog = function (m) {
        try { if (window.NativePlayback && NativePlayback.diagLog) NativePlayback.diagLog(m); } catch (e) {}
      };
      var ytState = function () {
        try { return Player.yt && Player.yt.getPlayerState ? Player.yt.getPlayerState() : -99; }
        catch (e) { return -98; }
      };
      var snap = function () {
        var method = '';
        try { method = document.getElementById('np-method').textContent || ''; } catch (e) {}
        return {
          cur: a ? a.currentTime : -1, dur: a ? (a.duration || 0) : 0,
          paused: a ? !!a.paused : true, rs: a ? a.readyState : -1, ns: a ? a.networkState : -1,
          err: a && a.error ? a.error.code : 0, src: a ? (a.currentSrc || a.src || '') : '',
          useAudio: !!Player.useAudio, yt: ytState(),
          vid: Player.current ? (Player.current.videoId || '') : '', method: method,
        };
      };
      if (!window.__vosDiagWired && a) {
        window.__vosDiagWired = true;
        ['loadstart', 'loadedmetadata', 'canplay', 'playing', 'waiting', 'stalled', 'pause', 'error', 'ended'].forEach(function (ev) {
          a.addEventListener(ev, function () {
            try {
              var ec = a.error ? a.error.code : 0;
              dlog('[MEDIA] ' + ev + ' cur=' + (a.currentTime || 0).toFixed(1)
                + ' dur=' + (a.duration || 0) + ' rs=' + a.readyState + ' ns=' + a.networkState
                + (ev === 'error' ? ' code=' + ec : ''));
            } catch (e) {}
          });
        });
      }
      if (action === 'state') { out.ok = true; out.state = snap(); }
      else if (action === 'visionos-play') {
        Player._fallbackTried = null;
        Player.current = { videoId: p.videoId || 'M7lc1UVf-VE', title: p.title || 'DIAG', artist: '', thumbnail: p.thumb || '' };
        Player.useAudio = true;
        a.preload = 'auto';
        try { a.crossOrigin = null; } catch (e) {}
        a.src = p.url || '';
        dlog('[PLAY] source=set requestedMethod=VisionOS host=' + window.__vosHost(p.url));
        try { var r = a.play(); if (r && r.catch) r.catch(function (e) { dlog('[MEDIA] play rejected ' + e); }); }
        catch (e) { dlog('[MEDIA] play threw ' + e); }
        out.ok = true; out.state = snap();
      } else if (action === 'pause') { a.pause(); out.ok = true; out.state = snap(); }
      else if (action === 'resume') { try { var r2 = a.play(); if (r2 && r2.catch) r2.catch(function () {}); } catch (e) {} out.ok = true; out.state = snap(); }
      else if (action === 'seek') { a.currentTime = Number(p.seconds || 0); out.ok = true; out.state = snap(); }
      else if (action === 'stop') { try { a.pause(); } catch (e) {} try { a.removeAttribute('src'); a.load(); } catch (e) {} out.ok = true; out.state = snap(); }
      else if (action === 'yt-stop') {
        try {
          if (Player.yt) { try { Player.yt.pauseVideo(); } catch (e) {} try { Player.yt.stopVideo(); } catch (e) {} }
          out.ok = true;
        } catch (e) { out.err = String(e); }
        out.state = snap();
      }
      else if (action === 'iframe-play') {
        Player.useAudio = false;
        try { Player.yt.loadVideoById({ videoId: p.videoId || 'M7lc1UVf-VE', suggestedQuality: suggestedQuality() }); Player.yt.playVideo(); out.ok = true; }
        catch (e) { out.err = String(e); }
        out.state = snap();
      } else if (action === 'metadata') {
        out.ok = true; out.state = snap();
        out.meta = Player.current ? { title: Player.current.title || '', artist: Player.current.artist || Player.current.subtitle || '', thumb: Player.current.thumbnail || '', videoId: Player.current.videoId || '' } : null;
      }
    } catch (e) { out.err = String((e && e.message) || e); }
    return JSON.stringify(out);
  };
  return 'injected';
})();
