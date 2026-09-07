# Bare Patch Analysis — 0043 / 0050 / 0051 (Chromium 153.0.7999.0)

## 0043 — Keep media playing in background on supported sites
**Patch:** `0043-Keep-media-playing-in-the-background-on-supported-si.patch` (240 ins, 20 files)
**Purpose:** Izinkan situs video yang didukung tetap play audio saat browser di-background/lock. Chromium sudah jaga audio pipeline (scheduler tidak freeze page yang play audio, notification, audio focus, foreground service), yang bikin YouTube stop adalah JS-nya sendiri yang `pause()` saat lihat `visibilityState=hidden`. Patch ini ubah apa yang dilihat page, bukan cara playback.
**Files:** `chrome_content_browser_client.cc`, `web_preferences.*`, `document.cc/h`, `web_settings*`, `web_view_impl.cc`, `settings.json5`, `MediaSettingsFragment.java`, `media_preferences.xml`
**Classes/Functions:** `BackgroundMediaSite`, `IsBackgroundMediaSite()`, `ShouldReportVisibleForBackgroundMedia()`, `OverrideWebPreferences()` (+ `AfterNavigation`), `WebPreferences.background_media_playback_enabled`, `Document::ReportsVisibleForBackgroundMedia()`, `Document::visibilityState()`, `DidChangeVisibilityState()`
**Before:** `visibilityState()` = `hidden` jika `hidden()==true`, selalu fire `visibilitychange`.
**After:** Jika `background_media_playback_enabled==true` (user ON + host di allowlist `m.youtube.com, www.youtube.com, youtube.com, music.youtube.com` path `/watch`), `visibilityState()` tetap `visible` meski `hidden()==true`, dan `visibilitychange` tidak di-dispatch. Blink internal tetap tahu hidden (untuk canvas/layout).
**Why:** YouTube pause karena JS lihat hidden. Dengan tetap visible, JS tidak pause.
**Dependencies:** Pref `bare.background_media_playback` (default false), allowlist host, WebPreferences mojom.
**Isolasi:** Bisa, cukup port `web_preferences` + `Document` + `ChromeContentBrowserClient` (allowlist bisa diganti `*` untuk MusicStream).
**Side effect:** `document.hidden` masih truthful (sengaja dibiarkan), jadi site yang cek `hidden` tetap pause — diperbaiki di 0050.

## 0050 — Report page as visible while background media plays
**Patch:** `0050-Report-the-page-as-visible-while-background-media-pl.patch` (28 ins)
**Purpose:** Lengkapi 0043: `hidden` juga harus bohong ke JS, karena YouTube cek `document.hidden` (bukan cuma `visibilityState`), pause 3 detik setelah background.
**Files:** `document.cc`, `document.h`, `document.idl`
**Before:** `document.hidden` → `hidden()` (truthful), `visibilityState` sudah di-override di 0043, tapi `hidden` masih true.
**After:** `hiddenForBinding()` = `hidden() && !ReportsVisibleForBackgroundMedia()`, IDL `hidden` & `webkitHidden` → `ImplementedAs=hiddenForBinding`. `Document::hidden()` tetap truthful untuk internal (canvas font pruning, layout).
**Why:** Tanpa ini, YouTube tetap pause meski 0043 aktif.
**Isolasi:** Wajib bareng 0043, 1 fungsi + 2 IDL binding.

## 0051 — Keep background playback permission when hidden
**Patch:** `0051-Keep-background-playback-permission-when-the-page-is.patch` (32 ins, 4 files)
**Purpose:** Jaga `allow_background_video_playback_ = true` tidak dicabut saat `OnPageHidden()`/`OnFrameHidden()`. Normal Chromium: background video butuh user gesture untuk resume, jadi saat hidden permission dicabut → pause.
**Files:** `web_media_player.h`, `html_media_element.cc`, `web_media_player_impl.cc/h`
**Original:** `OnPageHidden(){ if(IsPageHidden()) allow_background_video_playback_=false; }` (sama untuk `OnFrameHidden`)
**After:** `if(IsPageHidden() && !background_media_playback_enabled_) allow_background_video_playback_=false;` + `SetBackgroundMediaPlaybackEnabled(bool)` dipanggil dari `HTMLMediaElement::StartPlayerLoad()` baca `GetSettings()->GetBackgroundMediaPlaybackEnabled()`, disimpan di `WebMediaPlayerImpl.background_media_playback_enabled_`.
**Why:** Tanpa ini, meski page dilaporkan visible, Blink tetap cabut permission di level media player → tetap pause.
**Isolasi:** Bisa, 1 pref di `WebPreferences` + 1 bool di `WebMediaPlayerImpl`.

## Kesimpulan Minimal
Ketiganya saling melengkapi (0043=visibilityState, 0050=hidden, 0051=permission). Untuk `<video>` background audio, **semua perlu** atau video akan tetap pause (JS pause atau Blink permission). Bisa di-port sebagai 1 pref `backgroundMediaPlaybackEnabled` tanpa UI allowlist, selalu true untuk `com.dnialify.musicstream`.
