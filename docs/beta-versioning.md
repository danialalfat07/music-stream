# Beta Versioning & Same-Domain Isolation

> Same origin, branch `main`, no subdomain. Isolasi manual wajib.

## 1. Same-Domain Consequences

- IndexedDB/OPFS/localStorage/SW cache share namespace (same origin).
- User stable load kode beta upon reload → no parallel stable.
- Rollback = `git revert` + redeploy.

## 2. Storage Isolation (Wajib)

### localStorage user data (fav, playlist, history, stats, theme)

- **Read:** `smw_*` shared (existing data tetap kebaca).
- **Write:** `smw_beta_*` isolated (beta tidak merusak stable).

Wrapper `public/app.js:195 store`:

```js
const BETA = window.__BUILD_CHANNEL === 'beta';
const store = {
  get(k, d) {
    try {
      if (BETA) {
        const v = localStorage.getItem('smw_beta_' + k);
        if (v !== null) return JSON.parse(v) ?? d;
      }
      return JSON.parse(localStorage.getItem('smw_' + k)) ?? d;
    } catch { return d; }
  },
  set(k, v) {
    const key = (BETA ? 'smw_beta_' : 'smw_') + k;
    localStorage.setItem(key, JSON.stringify(v));
  }
};
```

- Saat promote, 2 opsi (pilih satu, doc di CHANGELOG):
  - (a) copy `smw_beta_*` → `smw_*` (merge, preserve beta edits)
  - (b) drop `smw_beta_*` (beta edits hilang, stable tetap)
- Rekom: (a) copy dengan last-write-wins per key.

### IndexedDB / SQLite native (offline cache baru)

- DB name: `dnialify-offline-beta` SQLite native (`files/offline-beta.db` via Capacitor SQLite / Room) beta vs `dnialify-offline` stable. Full isolated, jangan baca/tulis silang. WebView IndexedDB not used for offline.

### OPFS / Native Filesystem

- Native private: `files/offline-beta/{songId}/{sourceId}/seg_*.bin` beta vs `files/offline/...` stable. Capacitor Filesystem primary, OPFS not used for audio chunks. Cap 500MB enforced native.

### Service Worker Cache

- `CACHE = 'dnialify-assets-v' + APP_VERSION` → beta `dnialify-assets-v2.1.0-beta.1`.
- Activate handler hapus `dnialify-assets-*` yang bukan current:

```js
self.addEventListener('activate', (e) => {
  e.waitUntil((async () => {
    const keys = await caches.keys();
    await Promise.all(
      keys.filter(k => k.startsWith('dnialify-assets-') && k !== CACHE)
          .map(k => caches.delete(k))
    );
    await self.clients.claim();
  })());
});
```

- Jangan sentuh prefix lain. Otomatis hapus stable lama saat promote, hapus beta lama saat bump.

## 3. BUILD_CHANNEL

- Env `server.js:8` `BUILD_CHANNEL = process.env.BUILD_CHANNEL || 'stable'` (`stable|beta`).
- Kirim ke client via `GET /api/app-version` → `{version, channel, assetCache, ...}`.
- Client simpan `window.__BUILD_CHANNEL` dari fetch atau meta.

Behaviour:

- `beta`: badge `BETA` di header, skip `checkAppVersion` `public/app.js:442` mandatory modal, skip `ensureAppVersionBeforePlay` `public/app.js:467` block, skip `applyVersionUpdate` `public/app.js:408` auto reload+clear, skip `public/index.html:372` inline clear.
- `stable`: behaviour lama (force update aktif).

## 4. Force Update OFF Detail (beta)

- `public/app.js:442 checkAppVersion` → if `channel==='beta'` jangan `showVersionUpdateModal`, cukup `console.log` soft.
- `public/app.js:467 ensureAppVersionBeforePlay` → if beta return `true`.
- `public/app.js:408 applyVersionUpdate` → if beta jangan `clearWebsiteCacheOnly`+`skipWaiting`+reload, cukup toast "Versi baru tersedia, reload manual".
- `public/index.html:372` inline → if beta skip `caches.delete`.
- `server.js:1287` include `channel`.

## 5. Single Source of Truth Version

- `package.json:3` is source. `server.js:7-8` reads `PKG.version`.
- Inject to `public/app.js:1 APP_VERSION`, `public/index.html:371 VER`, `public/sw.js:1 CACHE` via `scripts/inject-version.js` before `node server.js` (or at `npm run version:bump`).
- Beta: `package.json version = 2.1.0-beta.1` (then `.2`, `.3`).

## 6. Promote & Rollback

- Promote: `2.1.0-beta.5 → 2.1.0` drop suffix, bump, aktivkan kembali force update, `CACHE` beda trigger SW update, update `CHANGELOG.md`, opsi copy `smw_beta_*` → `smw_*`.
- Rollback: `git revert <beta commit>` + redeploy `2.0.4` (or current stable). Beta caches `beta.1` tetap terhapus via activate handler on next stable load.

## 7. CHANGELOG

- Catat tiap `2.1.0-beta.N`, behavior change, namespace, promote plan.
