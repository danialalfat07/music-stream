# MusicStream Chromium Shell — Architecture

Branch: `feature/apk-chromium-shell` (base `88b4603` = main Vercel tidak disentuh)

## Target
```
MusicStream.apk (com.dnialify.musicstream)
 ├─ MainActivity (Android, no address bar/tab)
 ├─ Chromium Content Shell (WebContents)
 │   ├─ Page Visibility (0043+0050) → JS lihat visible
 │   ├─ Background permission (0051) → allow_background_video_playback_ keep
 │   └─ WebMediaPlayerImpl → audio lanjut, video suspend tapi tidak pause
 └─ MusicStream WebApp (<video src="VIDEO_URL" playsinline controls> + MediaSession)
     └─ Foreground Service + MediaSession + AudioFocus → lock screen controls
```

Flow background:
```
Home/lock
 → Activity.onPause/onStop
 → WebContentsImpl::WasHidden()
 → Blink Page::SetVisibilityState(kHidden)  // Blink internal tetap hidden
 → Document::visibilityState/hiddenForBinding → spoof visible ke JS (0043/0050)
 → WebMediaPlayerImpl::OnPageHidden/OnFrameHidden → cek background_media_playback_enabled_ (0051) → jangan clear allow_background_video_playback_
 → Media pipeline: video layer suspend, audio demux lanjut
 → Android AudioFocus + MediaSession notif + ForegroundService jaga proses
 → JS tidak fire visibilitychange, tidak panggil pause()
```

## Options Evaluated

| Factor | Full Bare | Fork Bare strip | **Fork Chromium + minimal patch** | Other embed (Cef/GeckoView) | WebView |
|---|---|---|---|---|---|
| Background `<video>` | ✅ (0043/0050/0051) | ✅ | ✅ port 3 patch | ⚠️ Cef tidak ada fix, Gecko lain | ❌ tidak bisa patch Blink |
| Build complexity | Tinggi (semua patch Bare) | Sedang | **Rendah** (3 patch saja) | Tinggi | Rendah |
| APK size | ~180MB | ~150MB | ~150MB | ~120-180MB | ~kecil tapi fail |
| Maintenance | Ikut Bare release | Manual strip tiap bump | **Rebase 3 patch tiap Chromium bump** | Fork lain | Tidak perlu tapi fitur gagal |
| Site allowlist | `music/youtube.com/watch` opt-in | sama | **`*` atau `dnialify-music-stream.vercel.app` always-on untuk com.dnialify.musicstream** | custom | tidak ada |
| Setting UI | Settings>Media switch | hapus | **Hapus, always true** | custom | - |

Rekomendasi: **Fork Chromium + port minimal 0043/0050/0051** — paling maintainable, tidak bawa patch Bare lain (adblock, UI, dll). Alternatif jika mau cepat verifikasi: Fork Bare lalu strip non-media patches, tapi merge conflict lebih banyak.

## Minimal Patch Set

### 1. Pref + wiring `background_media_playback_enabled`
Asal: `0043` (20 files)
- `chrome/common/pref_names.h` → `kBackgroundMediaPlayback` (`bare.background_media_playback`)
- `chrome/browser/prefs/browser_prefs.cc` → `RegisterBooleanPref(..., false)` (Android only)
- `third_party/blink/public/mojom/webpreferences/web_preferences.mojom` → `bool background_media_playback_enabled`
- `public/common/web_preferences/{web_preferences.h, mojom_traits.*}` + `public/web/web_settings.h` + `renderer/core/exported/{web_settings_impl.*, web_view_impl.cc}` + `renderer/core/frame/settings.json5` (`backgroundMediaPlaybackEnabled: false`)
- `chrome/browser/chrome_content_browser_client.cc` → `OverrideWebPreferences` + `OverrideWebPreferencesAfterNavigation` hitung `ShouldReportVisibleForBackgroundMedia()` lalu set `web_prefs->background_media_playback_enabled`

Untuk MusicStream (always-on):
- Hapus `MediaSettingsFragment.java` + `media_preferences.xml` + string `IDS_*`
- Ganti `ShouldReportVisibleForBackgroundMedia()` jadi `return true` untuk origin MusicStream, atau `return url.host() == "dnialify-music-stream.vercel.app"` atau unconditional `true` karena paket ini dedicated (`com.dnialify.musicstream` tidak buka situs lain). Paling simpel: `return true` di `OverrideWebPreferences` (dalam `BUILDFLAG(IS_ANDROID)`).
- Atau pertahankan allowlist Bare tapi tambah `{"dnialify-music-stream.vercel.app", nullptr}` dan selalu true — lebih aman jika nanti buka YouTube juga.

### 2. Blink visibility spoof (JS)
Asal: `0043` + `0050` (3 files)
- `renderer/core/dom/document.cc` → helper `ReportsVisibleForBackgroundMedia(document)` + `hiddenForBinding() { return hidden() && !Reports... }` + `visibilityState() { if(hidden && !Reports) hidden else visible }` + `DidChangeVisibilityState()` jangan dispatch `visibilitychange/webkitvisibilitychange` jika `Reports==true`. Housekeeping (font pruning/layout) tetap jalan karena `Document::hidden()` internal tetap true.
- `renderer/core/dom/document.h` → tambah `hiddenForBinding()`
- `renderer/core/dom/document.idl` → `[ImplementedAs=hiddenForBinding] hidden` + `webkitHidden`

### 3. Media permission keep
Asal: `0051` (4 files)
- `public/platform/web_media_player.h` → virtual `SetBackgroundMediaPlaybackEnabled(bool)`
- `renderer/platform/media/web_media_player_impl.h` → override + field `bool background_media_playback_enabled_ = false`
- `renderer/platform/media/web_media_player_impl.cc` → `SetBackgroundMediaPlaybackEnabled()` + guard di `OnPageHidden()` dan `OnFrameHidden()`: `if (IsPageHidden() && !background_media_playback_enabled_) allow_background_video_playback_ = false;` (sebelumnya tanpa guard)
- `renderer/core/html/media/html_media_element.cc` → `StartPlayerLoad()` baca `GetDocument().GetSettings()->GetBackgroundMediaPlaybackEnabled()` lalu `web_media_player_->SetBackgroundMediaPlaybackEnabled(...)` tiap load (cover navigasi)

Total minimal: ~10 file inti jika strip UI (`chrome_content_browser_client.cc`, `web_preferences.*` 5 file, `web_settings.*` 2 file, `settings.json5`, `document.*` 3 file, `web_media_player.*` 3 file, `html_media_element.cc`). Tanpa UI pref, bisa <30KB patch.

### Yang TIDAK dibawa
- Patch Bare lain: adblock, search, toolbar, dll — skip untuk MusicStream.
- Injeksi JS (ala Brave) — tidak perlu karena build Blink sendiri.

## Android Lifecycle Detail
- `MainActivity` extends `ChromeActivity` atau `ContentShellActivity` minimal (tanpa tab, tanpa omnibox). `onCreate` → `WebContents` load `https://dnialify-music-stream.vercel.app` (atau `https://music-stream-production.up.railway.app` untuk test `VIDEO_URL` langsung).
- `onPause/onStop` tidak panggil `WebContents.onHide()` secara agresif? Di Chromium normal `WasHidden()` dipanggil; dengan patch, `WebContents` hidden internal tetap tapi JS spoof jadi tidak pause.
- Tambah `ForegroundService` (`mediaPlayback` type Android 14+) saat `HTMLMediaElement.play()` → `MediaSession` update metadata (title/artist dari MusicStream JS via `navigator.mediaSession`) → notif lock screen. `AudioFocus` (`AudioManager.requestAudioFocus`) biar duck/pause saat telpon.
- `AndroidManifest.xml` perlu `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `POST_NOTIFICATIONS`.

## Build
- Base Chromium: `153.0.7999.0` (Bare `945b5115`) — pin sampai patch port sukses, lalu ikut stable berikutnya.
- GN args minimal:
  ```
  target_os="android"
  target_cpu="arm64"  # + arm untuk 32bit jika dual APK
  is_debug=false
  is_official_build=true
  is_chrome_branded=false  # atau true jika mau Chrome branding strip
  android_package_name="com.dnialify.musicstream"
  # Bare pakai is_desktop_android=true
  ```
- `autoninja -C out/ReleaseMusicStream chrome_public_apk` (atau `monochrome_public_apk`)
- Signing: debug keystore untuk sideload, release keystore di GitHub Secrets untuk Play.
- Update: tiap Chromium bump → `git rebase` + `git apply patches/minimal/*.patch` + `autoninja` ulang. 3 patch kecil → conflict minimal.

## Package
- `com.dnialify.musicstream`, `versionName 1.0.0-alpha.2` ikut Bare VERSION (MAJOR 1 MINOR 0 PATCH 0 BUILD 3), `versionCode` logic ABI.
- Icon/branding MusicStream (bukan Bare).

## Test Matrix
| # | Skenario | Harapan |
|---|---|---|
| A | Foreground play `<video>` | video+audio jalan |
| B | Home 60s | audio lanjut, video freeze/black tapi tidak pause, time++ |
| C | Lock 60s | sama B + MediaSession notif ada |
| D | Return foreground | video resume tanpa tap, time continue |
| E | Pause → Home → Return | tetap pause (jangan auto-play) |
| F | Multi-app 30s | audio lanjut |
| G | AudioFocus (telpon/notif) | duck/pause sesuai OS, resume jika focus balik |
| H | Seek saat background | jalan via MediaSession |
| I | `<audio>` opus (control) | selalu lanjut (baseline) |
| N | `visibilityState`/`hidden` di JS | log tetap `visible/false` saat hidden (verify spoof) |

Detail langkah & log ada di `test/background-test-plan.md`.

## Risks & Mitigations
- `document.hidden` spoof bisa rusak canvas/layout jika salah scope → mitigasi: `hidden()` internal tetap true, hanya `hiddenForBinding` untuk JS (sudah di 0050).
- Google Play policy background playback tanpa ForegroundService → wajib service + notif.
- Chromium bump break patch → patch kecil + test matrix otomatis (`test/background-test.html`).
