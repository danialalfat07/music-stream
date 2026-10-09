# PROTOTYPE: unhook VisionOS dari MediaPlayer (base 2.2.25 = fc2593a)

## FASE A — TEMUAN (DONE, FASE B STOP tunggu approve)

### A1. Jalur VisionOS vs iframe
- Mode pilih di JS: `public/app.js:2270-2278` — `NativePlayback.getStreamMode()===1`
  (atau `_playCached`) → `startNativeTrack()`; else path iframe
  (`playViaAudio` :2314, else `yt.loadVideoById` :2322).
- Konstanta: `StreamSettings.java:24-25` (`MODE_IFRAME=0`, `MODE_VISIONOS=1`,
  default VISIONOS :36-38). Bridge: `MainActivity.java:1052`.
- NAE tidak punya cabang iframe — selalu VisionOS/cache (`play()` di
  `NativeAudioEngine.java:138` → `resolveAndStart()` → resolve :637 →
  `startPathAt()` :860 / `startUrl()` :900 (bottom-fallback :901) /
  `startPath()` :804).
- Titik MediaPlayer start untuk VisionOS:
  `NativeAudioEngine.java:811-812` (file), `:888-892` (proxy/file),
  `:915-916` (stream URL), `:943` (`p.start()` onPrepared),
  recovery `:1146`, `:1222`, `:1209` (`mp.reset`).
- `onError` → `failLocked` :1045-1056. `onCompletion` premature → recovery
  thread :1043; genuine end → ENDED :981-998.

### A2. Recovery VisionOS aktual (tidak persis "3 lapis" tugas)
- L1 resolver-internal: LOGIN_REQUIRED → `refreshVisitor()` 1x + re-resolve 1x.
  `VisionOsResolver.java:185` (pilih vd), `:204` (log retry),
  `:210` (throw bila masih LOGIN), `:254-257` (`refreshVisitor`),
  `:139-146` (`getVisitorData`), `:174` (`resolve`).
- L2 NAE soft-seek + verifikasi posisi: `NativeAudioEngine.java:1139-1194`.
- L3 NAE hard recovery 3x **dengan data source SAMA** (`recDs`, :1199-1265);
  sukses → PLAYING, gagal → `firePrematureError` :1275-1276 → `failLocked`
  PREMATURE_COMPLETION (JS: no auto-next, no retry — `app.js:1316-1321`).
- Tanya user: tidak ada lapis "refresh URL" / "ganti URL" terpisah di NAE
  (resolve fresh cuma di awal :637; recovery pakai ulang URL/proxy sama).
  FASE C1 "pertahankan 3 lapis" = L1+L2+L3 di atas, tanpa ubah. OK?

### A3. Titik fallback VisionOS → iframe
- N1 `NativeAudioEngine.java:1114-1123` `failLocked()` → state ERROR + log
  `[FALLBACK] ... action=iFrame`. Event error ke JS.
- N2 `public/app.js:1332-1341` — `ev.state==='ERROR' && nativeActive`:
  `_nativeFailed=videoId`, `nativeActive=false`, `nativeStop()`, toast,
  `startCurrent()` → karena `_nativeFailed===videoId`, cabang native
  (:2270) dilewat → iframe (`playViaAudio` / `yt.loadVideoById` :2322).
  INI fallback utama yang FASE C2 harus ganti jadi skip.
- N3 `app.js:2190-2197` `startNativeTrack` catch → `startCurrent()` (retry
  implisit). N4 `app.js:2804-2814` STUCK_00_00 → iframe (path audio WebView,
  bukan native — prototype tidak sentuh). N5 `app.js:1376-1395` audio element
  stall/error → iframe (path WebView, tidak sentuh).
- Util siap pakai: `toast()` `app.js:412`, `nextTrack(auto)` `app.js:2451`
  (preseden `nextTrack(true)` di :1329), `Player.skipStreak` belum ada
  (baru, FASE C3).

## FASE B — Unhook (DONE, 2 commit, build PASS, FASE C tunggu approve)
- [x] B1 routing + import (`4ef1b7a`). No import perlu (sama package).
  B2 verifikasi: mp.* cuma dari play() (create :926 via start* :805/:866/:906
  ← resolveAndStart ← play); resume :208 / seek :233 / focus :539 / pause
  semua null-check; recovery cuma dari onCompletion (mp hidup). Guard play()
  cukup. No commit (no change).
- [x] B3 bridge `trackFailed` di `failLocked` (`8e4f984`). JS abaikan sampai C2.
- [x] Build `assembleDebug` PASS (deprecation notes saja).
- [ ] B1 routing tegas di `play()` NAE: VISIONOS → log
  `[NATIVE_CMD] play mode=VISIONOS engine=PENDING`, return sebelum mp.
  Perlu mode di NAE: baca `StreamSettings.getStreamMode()` (import baru,
  file non-proteksi — OK?). Commit `proto(visionos): unhook VisionOS from MediaPlayer`.
- [ ] B2 guard semua `mp.start/prepareAsync/setDataSource` ke IFRAME-only.
  Commit `proto(visionos): guard MediaPlayer calls to iframe only`.
- Tanya: mode check di NAE tiap `play()` cukup, atau guard per-call juga
  (pause/resume/seek/recovery pakai mp yang tak pernah start → no-op aman)?

## FASE C — edits DONE (belum commit, belum test)
- [x] C1 L1/L2/L3 + SongCache/PlaybackService/VisionOsResolver: diff nol.
- [x] C2 `app.js` N2 → skip + skipStreak (log `action=skip`).
- [x] C3 reset `skipStreak=0` saat PLAYING.
- [x] C4 `failLocked` action `iFrame`→`skip`.
- [ ] C5 BLOCKED: konflik B1 vs TEST C (bawah).

## KONFLIK B1 vs TEST C (putusan user)
- B1: VISIONOS → PENDING + return. Resolve mati, recovery mati, error nihil
  → C2 skip tak pernah trigger. TEST C (invalid id → 3 retry → skip) mustahil.
- Opsi B-revisi: VISIONOS tetap resolve + L1/L2/L3, tapi JANGAN start MP;
  triple-fail → `failLocked` → JS skip. Tetap diam (no mp).
- TEST C executable via harness intent (`native-play xxxxxxxxxxx`, no bridge)
  + CDP/logcat. C2/C3/C4 tak perlu ubah.

## TEST A — iframe background: PASS (11:51-11:54, commit 8e4f984)
- `startCurrent()` via CDP → iframe path (`nativePlay` undefined → YT).
  `ytState=1`, time 31→48→95 unfinished, `useAudio=false`.
- Audio: `AudioPlaybackConfiguration piid:237975 ... state:started`,
  `uid 10484 is using audio`. Notif legacy 1001 PLAYING + artwork
  (MediaSession PLAYING, NowBar lockscreen ada aksi).
- Kunci 65 dtk (`KEYCODE_SLEEP`): t 95→161 kontinu, audio tetap started.
- Artinya FASE B tidak merusak iframe.

## TEST B — VisionOS diam: BLOCKED (bukan FAIL kode, bridge pincang)
- Fakta: `window.NativePlayback` cuma expose `arm/updateWebViewState/
  nativeState`; `nativePlay/getStreamMode/setStreamMode/diagLog` undefined.
  Page reload → bridge hilang total. onStart injeksi balapan dengan load.
  Pre-existing 2.2.25 (bukan FASE B).
- Akibat: `startNativeTrack` unreachable (`app.js:2271` false) → semua
  selalu iframe. B1 (`enginePending`) dormant, tak teruji. Mode toggle UI
  juga mati (`setStreamMode` undefined).
- Butuh putusan: perbaiki injeksi bridge dulu (re-inject onPageFinished,
  sentuh `MainActivity.java`) baru TEST B/C/D bermakna. JANGAN FASE C dulu.
- [ ] D1 build+install. D2 force-stop. D3 iframe PASS wajib. D4 VisionOS diam.
  D5 invalid videoId → 3 retry + skip, no iframe, no suara.
- [ ] E1-E4 commit + APK `/sdcard/Download/Dnialify/dnialify-2.2.25-proto1.apk` + report.
