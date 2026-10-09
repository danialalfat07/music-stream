window.__diagPlay = function (videoId, title, artist, duration) {
  try {
    try { if (window.NativePlayback && NativePlayback.diagLog) NativePlayback.diagLog('[DIAG_PLAY] called for videoId=' + videoId); } catch (e) {}
    var track = {
      videoId: videoId,
      title: title || 'Diag Track',
      artist: artist || 'Diag Artist',
      duration: duration || 0,
      thumbnail: 'https://i.ytimg.com/vi/' + videoId + '/hqdefault.jpg'
    };
    playSong(track);
    return 'ok';
  } catch (e) { return 'error: ' + (e && e.message || e); }
};
window.__diagState = function () {
  try {
    var cur = Player.current;
    var cache = null;
    try {
      var e = (typeof OfflineLib !== 'undefined' && cur) ? OfflineLib.get(cur.videoId) : null;
      cache = e ? { downloadedBytes: e.downloadedBytes, audioSize: e.audioSize, status: e.cacheStatus } : null;
    } catch (x) { cache = null; }
    return JSON.stringify({
      currentTrack: cur ? cur.videoId : null,
      queueIndex: Player.index,
      state: Player.native ? Player.native.state : 'n/a',
      cur: Player.native ? Player.native.cur : 0,
      dur: Player.native ? Player.native.dur : 0,
      cacheProgress: cache
    });
  } catch (e) { return JSON.stringify({ error: String((e && e.message) || e) }); }
};
'hooks-installed ct=' + (document.querySelectorAll('*').length);
