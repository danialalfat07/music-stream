# Offline Scope — Apa Bisa Offline vs Tidak

## 1. Bisa Offline (audioStream via googlevideo) — native-first

- Source `getAudioUrl` return `url` (`server.js:1177`) → `mime audio/webm|audio/mp4`, `content_length` via `Range 0-0` total.
- Chunk `1MB` `ceil(len/1M)` (3-7 seg untuk 3-6MB track), native Capacitor Filesystem `files/offline-beta/{songId}/{sourceId}/seg_*.bin` (500MB cap native), SQLite `dnialify-offline-beta`.
- Intercept: `shouldInterceptRequest` in `MainActivity.java:88` `WebViewClient` → return `WebResourceResponse` `206` slice, not SW. SW `public/sw.js` only static.
- Need: metadata + artwork `/api/thumb` + lyrics `{synced,plain,source}` + `status=full`.
- `offline_ready = metadata && artwork cached && lyrics cached && source.status==='full' && stream_kind==='googlevideo'`.

## 2. Tidak Bisa Offline (IFrame only)

- `getAudioUrl` return `null` (cipher not decipherable `server.js:1220` or no audio format) → `sources.status='unavailable_offline'`.
- UI: badge `⚠ Tidak bisa offline`, tombol `Save Offline` disabled + tooltip "Sumber tidak tersedia untuk offline", jangan auto-fallback download iframe (cross-origin `https://www.youtube.com` `public/app.js:876` tidak interceptable).
- Jangan error teknis ke user.

## 3. Keputusan Tabel

| Kind | stream_kind | Bisa offline? | Action Save | Badge |
|------|-------------|---------------|-------------|-------|
| audio ok | googlevideo | Ya | Enable Save → chunk download | ✓ / 82% |
| audio null | unknown | Tidak | Disabled + tooltip | ⚠ Tidak bisa offline |
| IFrame only | iframeStream | Tidak | Disabled | ⚠ Tidak bisa offline |

## 4. Signed URL Handling

- `expire` ~6h, `sig`/`lsig`. Mid-download `403` → re-resolve `getAudioUrl` via `/api/audio` then continue, jangan fail job (`offline_jobs` retry).

## 5. Lyrics & Artwork

- Lyrics `LRCLIB` LRC (30 baris sample), plain fallback, simpan `raw_synced+raw_plain+lines[]` (`public/app.js:1824`).
- Artwork `w544` `~77KB` `image/jpeg` cacheable 1d, must be cached for offline.

## 6. Test Matrix Reference

- Chrome desktop, Chrome Android WebView, Firefox, Safari iOS.
- Full cache airplane → play pass, partial → graceful, corrupt → re-download, expire → re-resolve.
