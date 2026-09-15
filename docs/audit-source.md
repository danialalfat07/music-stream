# Audit Source — Dnialify Music Stream

> Generated: 2026-09-15 Phase 0.5 live capture. Read-only, grounded in code + live curl.

## 1. Audio Stream

**Bentuk:** Direct progressive `googlevideo` single file, bukan HLS/DASH.

- Resolve: `server.js:1177 getAudioUrl(videoId)` → `POST https://www.youtube.com/youtubei/v1/player` with ANDROID context `server.js:1162`, pick `adaptiveFormats` mime `audio/*` prefer `opus` `server.js:1198-1213`.
- Cache: `server.js:1230-1237` 6 min, key `audio_<id>`.
- Client: `public/app.js:718 playViaAudio()` → `audio.src = /api/stream?videoId=` `public/app.js:740`, fallback ke `YT.Player.loadVideoById` `public/app.js:1234`.
- No manifest: `grep m3u8|mpd|EXT-X` 0 hit in `server.js`/`public/app.js`.
- HLS questions (TARGETDURATION, segment count, ENDLIST) → N/A.

**Live sample /api/audio `jNQXAC9IVRw` (2026-09-15):**
```json
{
  "url": "https://rr1---sn-4pcxgnu5g-jb3s.googlevideo.com/videoplayback?expire=1789507034&...&itag=251&mime=audio%2Fwebm&clen=255427&dur=19.021&sig=AE0s...&lsig=APa...",
  "mimeType": "audio/webm; codecs=\"opus\"",
  "bitrate": 108013,
  "approxDurationMs": "19021"
}
```
- `expire` delta ~21478s ≈ 6h, `sig`+`lsig` true, `s` false. Refresh via re-call `getAudioUrl`.
- 10 random ids from `/api/home` → `10/10 OK 0% null` (cipher fallback not hit). Larger tracks: `3607269` (~3.4MB), `6676394` (~6.3MB), `3888611` (~3.7MB).

**Range support: Ya**
- `server.js:1252-1266` forward `Range`, set `Accept-Ranges: bytes`, pass `content-range`.
- Live: `GET Range: bytes=0-0` → `206 PartialContent` `content-range: bytes 0-0/3607269` `content-length:1`.
- `GET Range: bytes=0-1023` → `206` `bytes 0-1023/255427` `1024` body.
- No-range `HEAD` → `200` `Accept-Ranges: bytes` `Content-Length: 255427` `Content-Type: audio/webm` `Cache-Control: private, max-age=21299`.

**Failure mode:** `server.js:1220` if `signatureCipher` present → skip (require decipher) → fallback to IFrame. Rare for tested ids (0% in sample).

## 2. IFrame Stream

- External `https://www.youtube.com` via `public/app.js:876 new YT.Player('yt-player', {host: 'https://www.youtube.com'})`, loader `https://www.youtube.com/iframe_api` `public/app.js:944`, holder `public/index.html:363`.
- Cross-origin, no direct media request visibility. Control via `YT.Player` API only. Spoof via `public/app.js:31-57` + `android/.../brave-video-bg-play.js` `addDocumentStartJavaScript` `MainActivity.java:98-104`.
- Media inside iframe: UNKNOWN, opaque DASH internal, not exposed.

## 3. DRM & Proteksi

- No EME/Widevine `grep requestMediaKeySystemAccess` 0 hit.
- Signed URL with `expire`/`sig`/`lsig` is blocker, ~6h expiry, must re-resolve.

## 4. Player

- `<audio id="bg-audio">` `public/index.html:361` primary + `YT.Player` fallback. No `hls.js`/`dash.js`.
- `<audio>` can be intercepted via Service Worker; IFrame cannot.
- Reads via `audio.src = /api/stream?videoId=` same-origin proxy `server.js:1246`.

## 5. Assets

- Lyrics multi-fallback `server.js:1028 /api/lyrics` → LRCLIB `lrclibGet` `server.js:935`, NetEase `server.js:880`, lyrics.ovh, textyl `server.js:992`, YTM `MPLYt` `server.js:522`. Format `server.js:918` LRC `[mm:ss.xx]` + plain. JSON `{synced, plain, source}`.
- Sample: `About You` LRCLIB `synced 1425` 30 lines, `plain 1062`, source `LRCLIB`. `parseLRC` `public/app.js:1824`.
- Artwork: `server.js:108 thumbs()` pick largest `w544-h544`, proxy `server.js:1132 /api/thumb` `Content-Type: image/jpeg` `Cache-Control: public, max-age=86400` `~77KB`.
- Metadata needed: `title, artist, thumbnail, duration, videoId, playlistId`.

## 6. Web Infra

- PWA `public/manifest.json:1-11`, SW `public/sw.js:1-17` static only `if(/api/) return`, Cache API `dnialify-assets-v*`, `localStorage smw_*`, no IndexedDB/OPFS yet.
- Build: vanilla JS + Express, Capacitor 6.2.1 `package.json`, `MainActivity.java:28 BridgeActivity`.
- Target: desktop + mobile web + WebView Android.

## 7. Chunk Strategy (Lock Q1)

- **1MB virtual chunk** `chunk_size=1048576`, `total=ceil(content_length/1M)`. For 3-7 MB tracks → 4-7 segments, not 100. For 255KB short track → 1 segment.
- Need `HEAD` or `Range 0-0` to get `Content-Range` total before chunking.
- Revisit trigger if `content_length > 20MB` (long mix) → keep 1MB, total ~20 seg still ok.

## 8. Samples for PRD

- Manifest: none (direct file).
- Segment URL: googlevideo URL above (single file, sliced via Range).
- Response header manifest: N/A. Sample stream header: `206` `bytes 0-0/3607269` `Accept-Ranges: bytes`.

## 9. Blockers

1. Signed URL expiry 6h
2. IFrame opaque
3. No native 100 segments
4. Quota/eviction (need `navigator.storage.estimate()` manual on device)
5. SW currently skips `/api/`

## 10. Captures Pending Manual

- `navigator.storage.estimate()` on Android WebView target device (await result before finalize chunk if quota < 200MB).
