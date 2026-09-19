# Changelog

## 2.1.0-beta.1 - 2026-09-15
- **Audit source Phase 0.5 live capture**: googlevideo direct `audio/webm opus` `expire ~6h`, Range `206` verified `server.js:1252-1266`, thumb `image/jpeg` cacheable, lyrics `{synced,plain,source}` consistent.
- **Chunk strategy lock**: 1MB virtual `ceil(len/1M)` → 4-7 seg for 3-6MB tracks (not 100). Single file fallback for <1MB.
- **Same-domain beta isolation** (main branch, no subdomain):
  - `localStorage` read `smw_*` fallback, write `smw_beta_*` `public/app.js:195 store` wrapper.
  - IndexedDB `dnialify-offline-beta` + OPFS `/offline-beta/` (planned, not yet implemented).
  - SW cache `dnialify-assets-v2.1.0-beta.1` `public/sw.js:1` with activate cleanup of `dnialify-assets-*`.
- **BUILD_CHANNEL** `server.js:8` env `BUILD_CHANNEL` (`beta` if version contains `-beta`), exposed via `GET /api/app-version` `server.js:1287`.
- **Force update OFF for beta**: `public/app.js:442 checkAppVersion` soft toast only, `public/app.js:467 ensureAppVersionBeforePlay` skip block, `public/app.js:408 applyVersionUpdate` soft notify, `public/index.html:368` inline skip cache clear. Badge `BETA` in header `public/index.html:100`.
- **Single source of truth version**: `package.json` → `scripts/inject-version.js` injects to `public/app.js`, `public/index.html`, `public/sw.js`, `public/manifest.json`.
- **Docs**: `docs/audit-source.md`, `docs/beta-versioning.md`, `docs/offline-scope.md`.

### Promote plan (beta → stable)
- `2.1.0-beta.N → 2.1.0` drop suffix, bump, re-enable force update, ensure `CACHE` name diff triggers SW update, decide `smw_beta_*` copy vs drop (see `docs/beta-versioning.md`).

### Rollback plan
- `git revert` + redeploy stable version, beta caches auto-cleaned via SW activate handler.

## 2.0.4 - 2026-09-15
- Fix: minimize now playing on navigation (#beta isolation prep).

## 2.0.3 - 2026-09-14
- Volume boost 100/200/300, native loudness.

## 2.0.1 - ...
- Prior releases.
