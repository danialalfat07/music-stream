# MusicStream Chromium Shell — Architecture

## Target
```
MusicStream.apk (com.dnialify.musicstream)
 ├─ MainActivity (Android, no address bar/tab)
 ├─ Chromium Content Shell (WebContents)
 │   ├─ Page Visibility (0050)
 │   ├─ Background permission (0051)
 │   └─ WebMediaPlayerImpl (0043) → audio continue, video suspend
 └─ MusicStream WebApp (<video src="VIDEO_URL">)
```

## Options Evaluated
| Factor | Full Bare | Fork Bare strip | Fork Chromium + minimal patch | Other embed | WebView |
|---|---|---|---|---|---|
| Background `<video>` |  |  |  |  |  |
| Build complexity |  |  |  |  |  |
| APK size |  |  |  |  |  |
| Maintenance |  |  |  |  |  |

> Rekomendasi awal: **Fork Chromium + port minimal 0043/0050/0051** (paling maintainable).

## Minimal Patch Set (hipotesis)
- `web_media_player_impl.cc` — `ShouldPausePlaybackWhenHidden` untuk `video` yang `allow_background_video_playback_`
- `page_visibility` — suppress hidden untuk MusicStream origin
- `media_web_contents_observer` — keep permission

## Android Lifecycle
- `MainActivity` → `WebContents` → `Page` → `WebMediaPlayerImpl` → `AudioFocus` → `Foreground Service` + `MediaSession` untuk lock screen

## Build
- `target_os="android" target_cpu="arm64" is_debug=false`
- Paket ID `com.dnialify.musicstream`, sign debug → release
- Update: rebase Bare patch tiap Chromium bump

## Test Matrix
- Foreground, Home 60s, Lock 60s, Return, Pause→Home, Multi-app, Audio Focus
