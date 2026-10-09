# FASE 0 — AUDIT TOTAL Playback/Download/Cache DnialifyVisionOS

Read-only. Tanpa coding, tanpa test device, tanpa commit. Counts via `wc` kini. Semua klaim ada file:line.

## BAGIAN 1 — Inventaris File

Base native: `C:\Project\music-stream\android\app\src\main\java\com\dnialify\musicstream\`

| File | Baris (wc) | Fungsi Utama | Playback? | Klasifikasi + alasan |
|---|---|---|---|---|
| NativeAudioEngine.java | 2034 | Engine playback tunggal: resolve, window, MediaPlayer+Exo seam, EOS classify, event JS | Ya langsung | REFACTOR — otak playback; entry/resolve/monitor tetap, sumber audio + progress pindah SimpleCache |
| SongCache.java | 849 | Writer chunk `.part`, resume, record JSON, FULL offline, 403 ladder | Ya langsung | REFACTOR — writer chunk custom diganti CacheDataSource; record/meta + FULL exempt KEEP sementara |
| LocalStreamServer.java | 271 | NanoHTTPD proxy `127.0.0.1 /local-stream/{id}`, growing-hold 90s | Ya (MediaPlayer path) | DELETE — proxy tak perlu; Exo baca SimpleCache langsung |
| GrowingFileDataSource.java | 232 | Media3 DataSource baca `.part` growing, block 100ms/timeout 20s | Ya (Exo path) | DELETE — diganti CacheDataSource.Factory |
| GrowingReadPolicy.java | 42 | Pure verdict READ/HOLD/END/ERROR | Ya | DELETE — logika hold diganti cache blocking; atau KEEP bila dipakai test |
| ExoPlaybackEngine.java | 579 | Wrapper ExoPlayer: MediaSource, poll 500ms, stall, EOS delegate | Ya langsung | REFACTOR — ganti DataSource factory ke CacheDataSource + Resolving |
| ExoStateMirror.java | 50 | Map Player.STATE_* ke IDLE/BUFFERING/PLAYING/PAUSED | Ya | KEEP — cegah auto-next prematur |
| ExoEosClassifier.java | 95 | Pure classify GENUINE/PREMATURE, same-spot give-up 4 | Ya | KEEP — unit-testable, dipakai engine |
| ExoRecoveryPolicy.java | 30 | Budget MAX_ATTEMPTS=4 | Ya | KEEP — anti-loop |
| ExoVolumePolicy.java | 20 | Map persen ke gain/dB/duck | Ya | KEEP — math volume share |
| ExoPlayerFlags.java | 47 | `USE_EXOPLAYER=true` default + override test | Ya | KEEP — selector backend |
| MainActivity.java | 1482 | Capacitor bridge PlaybackBridge/Native*/cacheSong, PiP, lyrics | Ya langsung | KEEP — jembatan JS-native tunggal; tambah bridge kuota bila perlu |
| PlaybackService.java | 566 | Legacy foreground Service + MediaSessionCompat notif 1001 | Ya (non-Exo path) | KEEP — notif/session MediaPlayer path; hapus bila MediaPlayer pensiun |
| PlaybackMediaSessionService.java | 310 | Media3 MediaSessionService + notif 1002 | Ya (Exo path) | KEEP — notif/session Exo path |
| VisionOsResolver.java | 385 | InnerTube VISIONOS resolve itag251 opus + visitorData TTL 1h | Ya langsung | REFACTOR — resolve tetap, dibungkus ResolvingDataSource |
| VisionOsCache.java | 135 | POC downloader 100KB chunk + EBML validate + atomic rename | Tidak (legacy) | DELETE — duplikat writer; SongCache/SimpleCache ganti |
| VisionOsNet.java | 48 | Counter google/resolve + blockNetwork test | Tidak | KEEP — bukti no-YouTube test |
| VisionOsLoopback.java | 191 | Socket server static `.webm` Range/206 (diag) | Tidak | DELETE — duplikat LSS |
| VisionOsHarness.java | 1692 | ADB dispatch status/resolve/probe/download/play/seek | Tidak (tool) | MOVE — pindah debug/test sourceSet |
| VisionOsDiagActivity.java | 488 | Layar diag 16 check + DiagBridge | Tidak | MOVE — pindah debug |
| OfflineStorage.java | 157 | FS wrapper `filesDir/offline[-beta]/{song}/{src}/seg_*.bin`, cap 500MB | Tidak | KEEP — fase offline beta isolasi |
| OfflineDatabase.java | 69 | SQLiteOpenHelper `offline-beta.db`/`offline.db`, songs/sources/segments/assets/offline_jobs | Tidak | KEEP — schema siap, tanpa Room |
| OfflineInterceptClient.java | 49 | WebViewClient skeleton intercept `/api/stream`, gated false | Tidak | DELETE — stub TODO, return delegate saja |
| CacheEvents.java | 45 | Event bus cache ke `window.__nativeEvent` + ADB | Ya | KEEP — payload tunggal cache |
| FeatureFlags.java | 10 | `CACHE_INTERCEPT=false` | Tidak | KEEP — rollback switch murah |
| ExoTestFlagReceiver.java | 33 | Debug broadcast SET_EXO_FLAG ke prefs | Tidak | MOVE — debug sourceSet |
| ExoPlaybackTestActivity.java | 750 | Spike UI standalone + broadcast harness | Tidak (tool) | MOVE — debug sourceSet, bloat APK |
| TestForegroundService.java | 139 | WakeLock + notif 4201 untuk test 30 mnt | Tidak | MOVE — test-only |
| StreamSettings.java | 82 | SharedPrefs stream_mode/audio_cache/max_cached/pinned | Tidak langsung | KEEP — dipakai enforceLimit |
| StreamSettingsActivity.java | 191 | UI radio iFrame/VisionOS + Test Play | Tidak | MOVE — gabung diag |
| StreamTileService.java | 38 | QS tile buka settings | Tidak | MOVE — ikut settings |
| NotificationDuckService.java | 44 | NotificationListener duck ke engine | Ya (duck) | KEEP — fokus/duck eksternal |
| Log.java | 157 | Shadow Log + ring 20000 + whitelist | Tidak langsung | KEEP — dipakai semua file |

JS/build: `C:\Project\music-stream\public\app.js` 9184 baris, `C:\Project\music-stream\public\index.html` 571 baris, `C:\Project\music-stream\android\app\build.gradle` 111 baris, `C:\Project\music-stream\android\variables.gradle` 16 baris, `C:\Project\music-stream\package.json` 32 baris.

## BAGIAN 2 — Alur Data End-to-End (Saat Ini)

```
1. User tap play
   → public/app.js:3222-3225 nativePlayAt/nativePlay(videoId,...)
   → public/app.js:3314-3317 wantNative = getStreamMode()===1
   → MainActivity.java:1149 nativePlay → NativeAudioEngine.play
   → NativeAudioEngine.java:165 play(vid,startMs), NativeAudioEngine.java:199 exoMode=useExo()
   → thread resolveAndStart NativeAudioEngine.java:223
2. Resolve
   → resolveAndStart NativeAudioEngine.java:720
   → HIT NativeAudioEngine.java:722,730 / legacy HIT 735,742
   → MISS VisionOsResolver.resolve NativeAudioEngine.java:749
   → total<=0 fallback stream NativeAudioEngine.java:774,779
3. Download chunk
   → writer start NativeAudioEngine.java:791 SongCache.download
   → SongCache.java:359 guard RUNNING, SongCache.java:386 downloadInner
   → cap via NativeAudioEngine.getCurrentWindowEnd SongCache.java:375
   → fetch SongCache.java:456, tulis .part SongCache.java:463
   → SIMPLE kini: 1x fetch per chunk, gagal throw → FAILED → fallback iFrame SongCache.java:451-453
4. Window + consume
   → prefix 2 chunk NativeAudioEngine.java:803-805
   → setWindow NativeAudioEngine.java:825,900 (math chunk/windowEnd 906-907, log 915)
   → startPathAt NativeAudioEngine.java:827
   → Exo branch exo.play NativeAudioEngine.java:1031-1042 (GrowingFileDataSource baca .part)
   → MediaPlayer branch: LSS register NativeAudioEngine.java:1049-1052,
     mp.setDataSource NativeAudioEngine.java:1062,1064, prepareAsync 1066
   → audio keluar speaker
5. Monitor
   → tick 500ms NativeAudioEngine.java:1945: exo pos 1951 / mp pos 1961
   → timeUpdate NativeAudioEngine.java:1973 → pushEvent NativeAudioEngine.java:2058
     → window.__nativeEvent NativeAudioEngine.java:2068 + ADB 2082
   → JS mirror Player.native.state/cur/dur/bufferPct/isBuffering public/app.js:2201-2208
   → UI np-cur/np-dur/np-range/mini-* public/app.js:2253-2260
6. 403 recovery
   → SongCache ladder: SAME_URL_RETRIES_403=1 SongCache.java:47-51,
     refreshStreamUrl SongCache.java:849, refreshStreamUrlFreshVd 866
     (+ VisionOsResolver.refreshVisitor VisionOsResolver.java:255)
   → JS literal 403: TIDAK DITEMUKAN; fallback generik STALLED_NO_DATA/MEDIA_ERROR/STUCK_00_00
     public/app.js:2365,2376,3854
7. Auto-next
   → onCompletion NativeAudioEngine.java:1351 / onExoEnded 1237
     → handlePlaybackEnd 1395 (FixA COMPLETE 1417-1420, FixB playerDur 1428-1431)
   → ENDED ke JS → nextTrack(true) public/app.js:2297-2303
   → PREMATURE_COMPLETION tak pernah auto-next public/app.js:2289-2294
   → web audio ended → nextTrack(true) public/app.js:2329
8. Cache monitoring (playbar abu-abu)
   → literal playbar abu-abu: TIDAK DITEMUKAN di app.js
   → dl fills mini-dl-fill/np-dl-fill public/app.js:3252-3253, index.html:182,224-227
   → have = downloadedBytes public/app.js:4696, total = audioSize 4697
   → buffered/seekable/audio.buffered: TIDAK DITEMUKAN di app.js
```

Alur chunk: trigger setWindow → writer fetch cap → tulis `.part` → reader (LSS hold 90s / Growing block 100ms) consume.
Alur consume: LSS `LocalStreamServer.java:77 serve` Range 206/200 `131-137`, HOLD_POLL 100ms `22`, HOLD_TIMEOUT 90s `23`; Exo `GrowingFileDataSource.java:126 open`, `158 read`, HOLD `201-235` timeout 20s `225`.
Alur offline download: `downloadOffline` public/app.js:4525 → `OfflineLib.cacheSong` 3022 → `NativePlayback.cacheSong` 4529-4533 → `MainActivity.java:1216 cacheSong` → `downloadFull` `SongCache.java:62-78` (FULL exempt window-cap).

## BAGIAN 3 — Peta Interaksi Antar File

```
public/app.js
  ├── nativePlay/nativePlayAt ──► MainActivity.PlaybackBridge (MainActivity.java:1149,1162)
  │                                  ├── NativeAudioEngine.play (NativeAudioEngine.java:165)
  │                                  │     ├── VisionOsResolver.resolve (NativeAudioEngine.java:749)
  │                                  │     ├── SongCache.download (NativeAudioEngine.java:791)
  │                                  │     ├── setWindow (NativeAudioEngine.java:900)
  │                                  │     ├── ExoPlaybackEngine.play (NativeAudioEngine.java:1031-1042)
  │                                  │     │     └── GrowingFileDataSource (baca .part)
  │                                  │     └── LocalStreamServer.register (NativeAudioEngine.java:1049-1052)
  │                                  │           └── MediaPlayer.setDataSource (1062,1064)
  │                                  ├── nativePause/nativeResume/nativeSeek/nativeStop (MainActivity.java:1178-1189)
  │                                  └── cacheSong/downloadFull (MainActivity.java:1216 → SongCache.java:62-78)
  ├── getStreamMode ──► wantNative (public/app.js:3314-3317) + StreamSettings.mode
  └── window.__nativeEvent ◄── NativeAudioEngine.pushEvent (NativeAudioEngine.java:2068)
                                ├── timeUpdate/playbackStateChanged (tick 1945-1994)
                                └── CacheEvents cacheProgress/cacheError/cacheComplete (SongCache.java:497,539,559)
YT iFrame (fallback): YT.Player public/app.js:2609,2620; PLAYING→setPlayMethod iFrame 2660-2661
Notif/session: PlaybackService (legacy) ↔ PlaybackMediaSessionService (Exo, sharedPlayer 61-72)
DB beta: MainActivity.java:1409,1427,1468,1479 ↔ OfflineDatabase ↔ OfflineStorage
```

## BAGIAN 4 — Titik Integrasi SimpleCache

1. **Titik masuk audio** — `NativeAudioEngine.java:1031-1042` (exo branch, `eng.play` via GrowingFileDataSource) dan `NativeAudioEngine.java:1062,1064,1066` (mp.setDataSource LSS proxy + prepareAsync). Ganti dengan `CacheDataSource.Factory(SimpleCache, upstream HttpDataSource, FileDataSource)` di `ExoPlaybackEngine` (kini `ProgressiveMediaSource` + custom factory `ExoPlaybackEngine.java:430,432`); MediaPlayer path pensiun atau tetap LSS bila MediaPlayer dipertahankan.
2. **Titik resolve URL** — `NativeAudioEngine.java:749` (`VisionOsResolver.resolve`) + refresh `SongCache.java:849,866`. Ganti dengan `ResolvingDataSource.Factory` (atau `ResolvingMediaSource`) panggil `VisionOsResolver.resolve(videoId)` per `MediaItem`-key videoId; visitorData refresh tetap di dalam resolver.
3. **Titik cache management** — tulis: `SongCache.java:456,463` (fetch + append `.part`); baca: `SongCache.java:633 localBytes`, `656 hasBytes`, `647 chunkEndFor`; hapus: `SongCache.java:721 dropPart`, `729 delete`, `304 enforceLimit`. Ganti tulis/baca/hapus dengan `SimpleCache` API (`commitFile`, `getCachedBytes`, `removeResource`, `CacheEvictor`); record `.song.json` (`SongCache.java:114-116`) KEEP sebagai metadata (atau pindah kolom DB).
4. **Titik monitor progress** — kirim: `NativeAudioEngine.java:1973` timeUpdate + `SongCache.java:559` cacheProgress via `CacheEvents.java:15`; terima JS: `public/app.js:2201-2208` mirror + `4696-4699` have/total. Ganti dengan `player.getBufferedPosition()` + `simpleCache.getCachedBytes(key)`; dl-fill (`public/app.js:3246-3248`) dari cachedBytes/total.
5. **Titik download offline** — `public/app.js:4525 downloadOffline` → `MainActivity.java:1216 cacheSong` → `SongCache.downloadFull SongCache.java:62-78`. Ganti dengan `DownloadManager` + `DownloadService`, key = videoId, meta dari `.song.json`.

## BAGIAN 5 — Database & Storage Audit

| Aspek | Detail | File:Line |
|---|---|---|
| Room DB entity/DAO | TIDAK DITEMUKAN; tanpa `androidx.room`, tanpa `@Entity/@Dao` | — (grep `androidx.room` nihil) |
| SQLite manual | `OfflineDatabase extends SQLiteOpenHelper`, `offline-beta.db`/`offline.db`, v1, tabel songs/sources/segments/assets/offline_jobs, onUpgrade tak pernah drop | OfflineDatabase.java:12-15,53-61,65-71 |
| Dipakai di | `getReadableDatabase` / `getWritableDatabase` | MainActivity.java:1409,1427,1468,1479 |
| Path cache `.part` | `getCacheDir()/visionos_cache/<id>.webm.part` | SongCache.java:102-104,110-112; VisionOsCache.java:39-43,58-59 |
| Path `.webm` final | `<id>.webm`, atomic rename + EBML validate | SongCache.java:106-108,474-483; VisionOsCache.java:106-114 |
| Path `.song.json` | `<id>.song.json` (meta + lyrics + artworkFile) | SongCache.java:114-116,205-213,591 |
| Path artwork | `<id>.art` | SongCache.java:118-120 |
| Path lyrics | Kolom record `lyrics/lyricsFormat/lyricsStatus` (LY_AVAILABLE/UNAVAILABLE/PENDING/FAILED) | SongCache.java:40-43,205-213 |
| Offline beta path | `filesDir/offline[-beta]/{song}/{src}/seg_{index}.bin`, CAP 500MB | OfflineStorage.java:10-11,18,48-61,85-86,155-173,112-152 |
| Kuota cache lagu | `max_cached_songs` default 100, clamp 1-1000; JS fallback 200; `CHUNK_WINDOW_MAX=200` | StreamSettings.java:21,26,62-76; public/app.js:7473; NativeAudioEngine.java:44 |
| Cleanup | `part.delete()` saat fail/validasi; `deleteSong` rekursif; `NO LRU NO eviction` | VisionOsCache.java:99-107; OfflineStorage.java:134-152; VisionOsCache.java:13 |
| Server cache (non-android) | `<id>.webm` + `<id>.webm.part` | server.js:1420-1427,1522-1566 |

TIDAK YAKIN: rata-rata MB cache (butuh ukur device); exact enforce eviction native (perlu grep `enforceLimit` lanjutan).

## BAGIAN 6 — Dependencies & Konfigurasi

`android/app/build.gradle` (111 baris), versi dari `android/variables.gradle` (16 baris): minSdk 22, compile/target 34.

| Dependency | Versi | Dipakai di | Akan diganti? |
|---|---|---|---|
| androidx.media3:media3-exoplayer | 1.4.1 | ExoPlaybackEngine, PlaybackMediaSessionService | Tetap |
| androidx.media3:media3-datasource-okhttp | 1.4.1 | Upstream HTTP Exo | Tetap (jadi upstream CacheDataSource) |
| androidx.media3:media3-session | 1.4.1 | PlaybackMediaSessionService | Tetap |
| androidx.media3:media3-ui | 1.4.1 | Notifikasi player | Tetap |
| androidx.media3:media3-datasource (cache pkg) | BELUM ADA | — | TAMBAH (CacheDataSource) |
| androidx.media3:media3-database | BELUM ADA | — | TAMBAH (StandaloneDatabaseProvider) atau custom CacheDatabaseProvider |
| androidx.media:media | 1.7.0 legacy | PlaybackService compat | Hapus bila MediaPlayer pensiun |
| org.nanohttpd:nanohttpd (fi.iki.elonen) | 2.3.1 | LocalStreamServer | Hapus (ikut LSS) |
| okhttp langsung | TIDAK DITEMUKAN | — | Transitif via datasource-okhttp saja |
| room/sqlite main | TIDAK DITEMUKAN (cuma sqlite-jdbc 3.45 test) | — | Tambah Room bila metadata pindah DB, atau KEEP SQLite manual |
| junit/robolectric/espresso | 4.13.2/4.11.1/3.5.1 | Test | Tetap |
| capacitor android/core/cli | ^6.2.1 | App shell | Tetap |

`package.json`: `dnialify-music-stream` 2.3.5, webVersion 2.3.68; scripts start/bump/release/test/lint/fmt.

## BAGIAN 7 — Risiko, Blocker, dan Pertanyaan

### 7.1 Risiko
1. **Key cache tak stabil** — SimpleCache key default = URL; URL VisionOS expire → cache miss tiap resolve baru. Dampak: cache tak pernah hit, kuota boros. Mitigasi: `CacheKeyFactory` tetap = videoId (+itag), bukan URL.
2. **`.part` lama yatim** — migrasi tinggalkan `.webm.part`/`.webm` tanpa referensi SimpleCache. Dampak: storage ganda. Mitigasi: sekali jalan migrasi atau hapus `.part` + re-download; catat di CHANGELOG.
3. **Hold-to-wake hilang** — growing-hold custom (LSS 90s / Growing 20s) hilang saat pindah CacheDataSource. Dampak: balik stuck baca di luar window. Mitigasi: andalkan `getBufferedPosition` + `ResolvingDataSource` retry; uji lagu >5 mnt.
4. **EOS premature balik** — classifier andalkan consumed/avail custom. Dampak: auto-next salah. Mitigasi: KEEP ExoStateMirror + ExoEosClassifier, peta ke `STATE_ENDED` + buffered-DUR check.
5. **403 identity** — refresh visitorData kini manual per chunk. Dampak: ResolvingDataSource naive perma-403. Mitigasi: resolver wrapper panggil `refreshVisitor` + re-resolve maks 2x (budget kini).
6. **Notif/session ganda** — dua service (1001 legacy vs 1002 media3). Dampak: notif dobel. Mitigasi: gate `useExo()` sudah ada; hapus legacy setelah MediaPlayer pensiun.
7. **Offline beta terpisah** — dua sistem cache (lagu vs beta segments). Dampak: user bingung, kuota ganda 500MB. Mitigasi: satukan DownloadManager, beta flag tetap.

### 7.2 Blocker (HARUS selesai sebelum Fase 1)
1. Tambah `media3-datasource` (CacheDataSource) + putuskan provider DB (`media3-database` vs custom) — grep kini nihil SimpleCache/CacheDataSource/DatabaseProvider.
2. Konfirmasi backend default: `USE_EXOPLAYER=true` (`ExoPlayerFlags.java:17`) — YA default Exo, MediaPlayer legacy. Putuskan: MediaPlayer dipertahankan atau pensiun (tentukan nasib LSS + PlaybackService).
3. Putuskan nasib `.part` lama: migrasi sekali vs hapus + re-download (pengaruhi storage + UX offline).
4. Putuskan key cache: videoId vs URL (wajib sebelum tulis kode cache).

### 7.3 Pertanyaan untuk User
1. Tombol "Download offline" tetap ada (DownloadManager) atau cukup streaming cache?
2. User perlu atur max cache size (kuota MB), atau cukup max_cached_songs kini?
3. `.part`/`.webm` lama dimigrasi atau boleh re-download?
4. MediaPlayer legacy dipertahankan sebagai fallback, atau Exo penuh?
5. Metadata lagu tetap `.song.json` file atau pindah Room/SQLite?

## READY FOR FASE 1?

❌ BELUM. Blocker 1-4 (7.2) belum diputuskan. Setelah user jawab 5 pertanyaan + tambah dependency media3-datasource/database, audit ini cukup sebagai fondasi Fase 1 (SimpleCache migration).
