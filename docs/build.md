# Build — MusicStream Chromium Shell

Branch `feature/apk-chromium-shell` only. `main` tidak pernah build Chromium.

## Prereq (local)
- Ubuntu 22.04, 32GB RAM, 100GB disk, `depot_tools` di PATH.
- `gclient` + `fetch` Chromium `153.0.7999.0` (commit Bare `945b5115`). Full checkout ~40GB, jangan di Windows host langsung — pakai WSL2 atau Actions.
- Java 17, Android SDK/NDK (via `build/install-build-deps-android.sh`).

## Quick (CI)
Push ke `feature/apk-chromium-shell` → `.github/workflows/build-apk.yml` jalan (manual `workflow_dispatch` juga). Artifact `MusicStream-apk`.

## Local Steps
```bash
# 1. depot_tools
git clone https://chromium.googlesource.com/chromium/tools/depot_tools.git /tmp/depot_tools
export PATH=/tmp/depot_tools:$PATH

# 2. fetch Chromium (shallow, android)
mkdir -p /tmp/chromium && cd /tmp/chromium
fetch --nohooks android --no-history
# atau jika fetch gagal: gclient sync --nohooks --with_branch_heads --with_tags

cd src
git checkout 153.0.7999.0  # atau tag sesuai Bare base
gclient sync --with_branch_heads --with_tags --nohooks

# 3. apply minimal patches (MusicStream always-on, tanpa Settings UI)
git apply ../../patches/minimal/001-background-media-pref.patch
git apply ../../patches/minimal/002-blink-visibility-spoof.patch
git apply ../../patches/minimal/003-media-permission-keep.patch

# 4. GN gen
gn gen out/ReleaseMusicStream --args='
  target_os="android"
  target_cpu="arm64"
  is_debug=false
  is_official_build=true
  android_package_name="com.dnialify.musicstream"
  is_desktop_android=true
'

# 5. build
autoninja -C out/ReleaseMusicStream chrome_public_apk
ls -lh out/ReleaseMusicStream/apks/*apk

# 6. install
adb install -r out/ReleaseMusicStream/apks/ChromePublic.apk
# atau rename ke MusicStream.apk
```

## Patches
- `patches/minimal/001-background-media-pref.patch` — pref + wiring `background_media_playback_enabled`, `OverrideWebPreferences` always-true untuk MusicStream (strip MediaSettingsFragment).
- `patches/minimal/002-blink-visibility-spoof.patch` — `Document::hiddenForBinding` + `visibilityState` + `document.idl` + suppress `visibilitychange`.
- `patches/minimal/003-media-permission-keep.patch` — `WebMediaPlayerImpl::SetBackgroundMediaPlaybackEnabled` + guard `OnPageHidden/OnFrameHidden`.

Asal Bare `0043/0050/0051` (Chromium `153.0.7999.0`), sudah strip allowlist check + pref UI.

## Signing
- Debug: `out/.../apks` sudah signed debug, bisa sideload langsung.
- Release: `keystore` di `~/.android/` atau GitHub Secrets `ANDROID_KEYSTORE_BASE64` + `KEYSTORE_PASSWORD` → `jarsigner`.

## Rebase
Tiap Chromium bump: `git fetch origin && git rebase <new_tag>` lalu `git apply` ulang. 3 patch kecil → conflict biasanya hanya di `chrome_content_browser_client.cc` dan `document.cc`.

## Troubleshooting
- `gn gen` fail → `build/install-build-deps.sh --android`
- `gclient sync` OOM → `GCLIENT_PY3=1`, `ulimit -n 4096`
- Patch reject → cek `git diff` Bare di `bare-browser/patches/0043,0050,0051` — line number geser tiap bump, pakai `patch -p1 --merge`.
- APK 180MB normal. Untuk split `arm64`/`arm` set `target_cpu` berganti.
