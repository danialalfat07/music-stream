# Offline Test Plan - Regression + Platform

## Regression (must pass after WebViewClient added, Flag false)

- Online play via `<audio>` → `playViaAudio` `public/app.js:718` still fetches `/api/stream` via network, no intercept.
- YT IFrame fallback → `YT.Player.loadVideoById` `public/app.js:1234` still works, not intercepted.
- Artwork `/api/thumb` `server.js:1132` → 200 `image/jpeg` cacheable.
- Lyrics `/api/lyrics` `server.js:1028` → `{synced,plain,source}`.
- Navigasi `#/home|#/search|#/library` + `closeNowPlaying` `public/app.js:112` still minimize correctly.
- SW static assets `public/sw.js` still `caches.match` fallback.

Run: `npm test` + manual play 2 tracks + check `chrome://inspect` WebView network no `shouldInterceptRequest` for `/api/thumb`.

## Platform Gate

- `isAndroidNative()` `public/app.js:5` → `true` only if `Capacitor.isNativePlatform()` or `NativePlayback` exists.
- Desktop browser: `Save Offline` hidden/disabled + label "Hanya di app Android", filter `Offline` hidden.
- Android app: buttons visible, `FeatureFlags.CACHE_INTERCEPT` false → still online-only, no native file read yet.

## Future Phase 1 Enable (Flag true)

- Set `FeatureFlags.CACHE_INTERCEPT = true` + implement `OfflineInterceptClient` file check → 206 slice from `files/offline-beta/`.
