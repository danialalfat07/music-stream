# Analisis Stutter 4.093s — hgqirobgM8E @44.9s (investigasi kode, TANPA fix)

Status: analisis statis selesai. Tidak ada kode diubah, tidak ada log ditambah.

## 1. Trace read: ExoPlayer → GrowingFileDataSource → file

`ExoPlaybackEngine.startItem` (ExoPlaybackEngine.java:395-427):
`ProgressiveMediaSource.Factory(dsFactory).createMediaSource(MediaItem.fromUri(uri))`
(405-406) → `player.setMediaSource(source, startMs)` (419/421) →
`prepare()` + `play()` (423-424). Extractor membaca via Loader thread
memanggil `GrowingFileDataSource.read()` berulang.

`GrowingFileDataSource.read()` (GrowingFileDataSource.java:124-188),
tiap iterasi re-resolve file (129) lalu `GrowingReadPolicy.decide`
(131-133):

- `READ` (pos < have, policy GrowingReadPolicy.java:39): `want =
  min(length, have-position)` (136); open `RandomAccessFile`, `seek(position)`,
  `read(≤ min(want, SERVE_BUF=65536))` (141-146, SERVE_BUF line 70);
  `position += n`, `consumed` update (148-149); close RAF (157-160);
  `return n` (151). **Tanpa sleep, tanpa block.**
- `HOLD` (pos >= have, file belum COMPLETE, policy lines 40-44):
  log throttled 5s (170-174), timeout 20s → IOException (175-178),
  `Thread.sleep(POLL_MS=100)` (179-184, POLL_MS line 68).
- `END_OF_INPUT` hanya bila COMPLETE (policy line 40; source 162-163).
- `ERROR_FAILED` bila writer FAILED (policy 38; source 164-165).

`open()` (91-121): block sampai file ada (98-116), return
`LENGTH_UNSET` (119-120) — selalu, karena `totalBytes()` hardcoded `-1L`
(ExoPlaybackEngine.java:342-344) dan MediaItem tanpa length (405-406).

Jawaban kunci FASE 1:
- read() TIDAK sleep per call di jalur data; maks 64KB/call.
- Random access YA via RAF seek (144); backward seek bebas (pos<have→READ);
  forward seek melewati have → HOLD.
- Source "tahu" growing: EOF hanya saat COMPLETE; selain itu HOLD/retry.
  `.part→.webm` rename terdeteksi karena re-resolve tiap iterasi (129).

## 2. LoadControl aktual (ExoPlaybackEngine.java:243-247)

`setBufferDurationsMs(5000, 10000, 2500, 5000)` =
min 5s / max 10s / playback 2.5s / afterRebuffer 5s;
`setTargetBufferBytes(8MB)`; `prioritizeTimeOverSize(true)`; backBuffer 30s.

Penilaian: config wajar, tidak terlalu kecil. Keluar rebuffer butuh 5s
media (~40-80KB @opus 251). `targetBufferBytes` 8MB tak relevan untuk file
kecil karena time-threshold menang. Max 10s membatasi read-ahead (~160KB),
jauh di bawah windowEnd — tidak ada parameter yang memaksa rebuffer
berulang dari sisi config.

## 3. Interaksi dengan SongCache window + deadlock?

Reader NOL window-coupling: tidak ada referensi setWindow/pauseDownload/
isDownloading di GrowingFileDataSource.java maupun GrowingReadPolicy.java
(hanya isComplete/getStatus/partFile via FileState). **Tidak ada deadlock**
(circular wait mustahil: reader tak pernah menahan lock yang writer butuhkan;
pause writer bersifat advisory flag).

Writer gating (semua di NativeAudioEngine.java):
- `setWindow` (900-920): pause bila `hasBytes(min(windowEnd,total))`
  (908-911); resume via thread download baru (915-918).
- Tick enforcement (1958-1972): chunk-cross → setWindow; cap-hit → pause
  (1968-1969). **Tick hanya jalan saat PLAYING.**
- Watchdog re-drive (1993-2006): frozen 3s + have<windowEnd → setWindow.
  **Di dalam tick → mati saat BUFFERING.**
- Stall delegate: engine poll 3s → `onPlayerStalled` → `requestWindowAdvance`
  (1129-1135 → 975-992). Tapi poll engine mensyaratkan `player.isPlaying()`
  — false saat rebuffer — **jadi mati juga saat BUFFERING.**

**Liveness gap terkonfirmasi**: selama BUFFERING, ketiga pemicu resume-writer
yang bergantung pada PLAYING mati. Kombinasi HOLD + writer-paused hanya bisa
pulih via (a) resume eksternal, atau (b) timeout 20s → IOException → error.
Stutter ini pulih dalam 4s → mekanisme (a), penyebab resume tak terlihat dari
cuplikan log yang diberikan.

Paradoks "data 2.15MB ada tapi butuh 5KB": jika `have` benar 2.15MB selama
stall dan posisi read ≈640KB, jalur READ (tanpa sleep, ≤64KB/call, overhead
~1ms) **tidak mungkin** selambat 1.25KB/s. Maka salah satu: (i) posisi read
extractor jauh di depan pos playback (read-ahead menabrak region belum
tertulis saat writer paused — perlu `byte needed` log untuk konfirmasi);
(ii) angka 2.15MB diukur SETELAH recovery, bukan selama stall; atau
(iii) bottleneck di sisi loader/extractor (stream LENGTH_UNSET). TIDAK YAKIN
mana yang benar tanpa log timestamp per-baris — lihat bukti yang diminta.

## 4. Root cause (ranking)

1. **HOLD + writer-paused + liveness gap (kandidat utama).** Poll 100ms/
   iter + tidak ada pemicu resume saat BUFFERING menjelaskan stall
   multi-detik yang pulih sendiri begitu writer jalan lagi. 5KB/4s =
   trickle writer yang baru resume (atau jaringan seret).
2. **Inefisiensi per-read (sekunder).** RAF open+close tiap call (141-160)
   + cap 64KB (70, 145-146): overhead nyata tapi orde ms — tidak menjelaskan
   1.25KB/s sendirian. Formulir "tahu file tumbuh" sudah benar (HOLD, bukan EOF).
3. **LENGTH_UNSET permanen (sekunder).** `totalBytes()=-1` (ExoPlaybackEngine.
   java:342-344) → extractor tanpa length: seeking/buffering suboptimal,
   END_OF_INPUT akurat hanya via COMPLETE. Berkontribusi, bukan pemicu 4s.
4. **Bukan LoadControl.** Nilai wajar; tidak memaksa rebuffer.

Bukan deadlock. File .part secara prinsip BISA dibaca full-speed — yang
melambat adalah ketersediaan byte (writer) atau posisi read, bukan syscall read.

## 5. Hipotesis fix (JANGAN implementasi — prioritas + risiko)

- **H1 (prioritas 1): sinyal reader→writer.** Saat `read()` masuk HOLD
  (atau N hold beruntun), panggil `requestWindowAdvance`/setWindow untuk
  membangunkan writer seketika, ganti trigger PLAYING-gated. Risiko: churn
  restart writer di jaringan flaky; mitigasi debounce ala RECOVERY_DEBOUNCE.
- **H2 (prioritas 2): RAF reuse + SERVE_BUF 256KB.** Satu handle terbuka
  lintas read (reopen saat identitas file berubah), cap naik 64→256KB.
  Risiko: handle basi saat rename .part→.webm; mitigasi reopen on change.
- **H3 (prioritas 3): totalBytes nyata.** Umbar `clen` resolver (sudah ada
  sebagai `lastTotal` di NAE) ke FileState agar length known → seek/buffer
  extractor akurat. Risiko: clen bohong/berubah (refresh 403) → EOF prematur;
  mitigasi fallback -1 saat mismatch.
- **H4 (wajib dulu): log diagnostik.** Sampling latensi+size READ, entry/exit
  HOLD (pos/have/writerState), pause/resume writer + alasan + timestamp.
  Satu run repro dengan log ini untuk memilih H1 vs H3 sebelum sentuh kode.

Test runtime perlu sebelum fix: YA — run instrumen tangkap stall + log H4.

## 6. Pertanyaan ke user

1. Pilih approach: H1 (wake writer), H2 (efisiensi read), H3 (length nyata),
   atau H4-dulu (log + repro)?
2. Minta cuplikan log ±30s sekitar 11:53:05: baris `[EXO] byte needed`,
   `[NAE] window`, `[NAE] writer`, `writerState`, dan ukuran file per menit
   — untuk memastikan `have` saat stall vs sesudahnya.
3. Konfirmasi satuan "44.9KB→50.0KB" (byte buffered-ahead? `buf=` ms?
   baris log mentah mana?).
