# Background Playback Analysis — MusicStream

> Branch: `feature/apk-chromium-shell` — base `88b4603` (main Vercel tidak disentuh)

## 1. Current Behavior (PWA + WebView)
- Foreground: `<video>` play normal (video+audio)
- Home/lock: Activity `onPause/onStop` → WebContents hidden → Blink `Page.visibilityState = hidden` → `WebMediaPlayerImpl::OnPageHidden()` → Chromium **suspend/pause video** (hemat baterai) → audio stop
- Audio-only (`<audio>` opus) di Chromium diperlakukan beda: boleh lanjut background (hypothesis Bare 0043)

## 2. Root Cause Hypothesis
- Chromium bedakan `audio-only` vs `video` saat hidden:
  - `audio-only → allow background`
  - `video → ShouldPausePlaybackWhenHidden() == true` → pause/disable video
- + Page Visibility `visibilitychange` → JS bisa `video.pause()` sendiri
- + Permission `allow_background_video_playback_` dicabut saat `OnPageHidden()` (patch 0051)

## 3. Bare Patches Relevant
- **0043** `Let supported sites keep playing media in the background` — ubah `ShouldPausePlaybackWhenHidden()` untuk site yang didukung
- **0050** `Keep sites told the page is visible in the background` — suppress `visibilityState = hidden`
- **0051** `Keep background playback permission when hidden` — jangan cabut `allow_background_video_playback_`

> Detail diff akan diisi di `docs/bare-patch-analysis.md` setelah clone Bare.

## 4. Files to Inspect
- `third_party/blink/renderer/platform/media/web_media_player_impl.cc` (`ShouldPausePlaybackWhenHidden`, `ShouldDisableVideoWhenHidden`, `OnPageHidden`, `Play`)
- `third_party/blink/renderer/core/page/page_visibility_state.*`
- `content/browser/media/media_web_contents_observer.*`
- `chrome/android/java/src/org/chromium/chrome/browser/ChromeActivity.java` lifecycle
- Bare patches dir `patches/` di `bare-browser` repo

## 5. Android Lifecycle Map
```
Home press
  → Activity.onPause/onStop
  → Content: WebContentsImpl::WasHidden()
  → Blink: Page::SetVisibilityState(kHidden)
  → WebMediaPlayerImpl::OnPageHidden()
  → Media pipeline: suspend video, (audio lanjut jika allow_background_video_playback_)
  → AudioFocus / MediaSession / Foreground Service (jaga proses)
```

## 6. Next
- Clone Bare, catat Chromium version + GN args
- Test minimal `<video>` page di Chrome vs Bare vs WebView
- Klasifikasi gagal A-N
