# KEYSTORE — cara update tanpa bentrok

## Kenapa bentrok
- Android identifikasi update via `applicationId` + `SHA signing` + `versionCode` harus naik.
- Sebelum `2026-09-09 17:44` (`d0efb58`), `android/app/build.gradle` `versionCode 2` static dan debug keystore ephemeral per runner (`~/.android/debug.keystore` lokal beda dengan GH). Tiap build SHA beda → `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.
- Sejak `d0efb58`, `versionCode = 2 + GITHUB_RUN_NUMBER` monotik (GH) dan `android/app/build.gradle:32` `signingConfigs.debug` pakai `android/app/debug.keystore` persistent. Workflow `android-build.yml:42` restore `DEBUG_KEYSTORE_BASE64` → semua GH build SHA sama:
  ```
  SHA256: F3:A2:83:B7:84:DD:A7:8A:53:03:7B:25:44:41:F2:41:C6:7F:C3:34:11:0B:68:11:DF:26:41:AB:53:CA:E1:90
  Alias: androiddebugkey, pass: android, Valid until 2056-08-30
  ```
  Contoh: `versionCode 33 (2026-09-10 21:34) → 34 (2026-09-11 10:46) → 35 (2026-09-11 11:29)` semua SHA `F3:A2...` → update lancar via GH artifact.

- Bentrok yang kamu alami `sebelum 11.09.2026 00:50` kemungkinan kamu install APK build lokal (`./gradlew assembleDebug` tanpa `android/app/debug.keystore`), SHA lokal `~/.android/debug.keystore` beda dengan GH `F3:A2...`. Atau install dari branch `feature/apk-chromium-shell` lama sebelum persistent.

## Fix yang sudah dilakukan
1. `android/app/debug.keystore` (2618 bytes, SHA `F3:A2...`) sekarang **di-commit** ke repo `android/app/debug.keystore`. Baik GH maupun build lokal pakai file ini.
2. `.gitignore` diubah: `*.keystore` tetap ignore tapi `!android/app/debug.keystore` whitelist, jadi debug keystore ter-track.
3. `android/app/build.gradle` sudah pakai `if (debugStore.exists()) signingConfigs.debug` → otomatis pakai file ini lokal maupun GH. Workflow `android-build.yml` tetap restore dari secret (overwrite file sama, SHA tetap sama) — tidak bentrok.

## Cara verifikasi SHA
```bash
keytool -list -v -keystore android/app/debug.keystore -storepass android | grep SHA256
keytool -printcert -jarfile app-debug.apk | grep SHA256
# GH log: "Verify APK" → package: versionCode='35' + SHA256 F3:A2...
adb shell dumpsys package com.dnialify.musicstream | grep -A2 versionCode
```

## Cara update tanpa bentrok (mulai sekarang)
### Via GH Artifacts (recommended)
1. Buka Actions → `Android Build (Debug APK)` → latest run `main` → Download `app-debug` → `app-debug.apk` `versionCode 36+` SHA `F3:A2...`
2. `adb install -r app-debug.apk` atau install via file manager → harusnya `Success`, tidak perlu uninstall. `versionCode` otomatis naik dari GH `GITHUB_RUN_NUMBER`.

### Via Build Lokal
1. Pastikan `android/app/debug.keystore` ada (sudah di-commit, `git pull`).
2. Jangan hapus file tersebut. Jika pernah generate manual, `git checkout -- android/app/debug.keystore`.
3. `npm ci && npx cap sync android`
4. `cd android && ./gradlew assembleDebug` → APK `app/build/outputs/apk/debug/app-debug.apk` SHA `F3:A2...`, `versionCode` lokal `2` (karena `GITHUB_RUN_NUMBER` tidak ada). Untuk update dari GH `versionCode 35` ke lokal `2` akan **downgrade gagal**. Solusi:
   - Selalu update via GH artifact jika sebelumnya install dari GH.
   - Jika mau pakai lokal sebagai sumber update, bump `android/app/build.gradle` `base = 40` (di atas GH terakhir) atau set env `GITHUB_RUN_NUMBER=100` sebelum build: `GITHUB_RUN_NUMBER=100 ./gradlew assembleDebug` → `versionCode 102`.

### One-time migrasi jika masih bentrok
Jika kamu masih pakai APK lama SHA beda (sebelum commit debug.keystore):
1. Backup data: Buka app → Library → Backup/Export (atau `adb backup` / copy `smw_*` via `localStorage`).
2. `adb uninstall com.dnialify.musicstream` (wajib jika SHA beda, `install -r` tidak bisa).
3. Install APK baru GH `versionCode 35+` SHA `F3:A2...`.
4. Restore backup.

Setelah migrasi ini, semua update berikutnya (GH atau lokal dengan file committed) akan SHA sama dan tidak bentrok lagi.

## Catatan
- Jangan commit `release.keystore` / `*.jks` — hanya debug.
- Jika mau ganti debug keystore lagi, harus commit baru + update `DEBUG_KEYSTORE_BASE64` secret (`base64 -w0 android/app/debug.keystore`) dan semua user harus uninstall sekali.
- `versionCode` GH monotik, lokal default `2` — untuk hindari downgrade, prefer GH artifact untuk distribusi.
