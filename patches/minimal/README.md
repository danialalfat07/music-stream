# Minimal Patches — MusicStream

Port dari Bare `0043/0050/0051` (Chromium 153.0.7999.0) strip ke always-on untuk `com.dnialify.musicstream`.

- `001-background-media-pref.patch` — pref + mojo + WebSettings + OverrideWebPreferences (tanpa Settings UI)
- `002-blink-visibility-spoof.patch` — Document hiddenForBinding + visibilityState + suppress visibilitychange
- `003-media-permission-keep.patch` — WebMediaPlayerImpl guard

Apply order 001→002→003 via `git apply`.

Bare source: `bare-browser/patches/0043-*`, `0050-*`, `0051-*`. Lihat `docs/bare-patch-analysis.md`.
