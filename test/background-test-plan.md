# Background Test Plan — MusicStream Shell

Load `test/background-test.html` di shell (atau Chrome/Bare untuk baseline).

## Page
- `test/background-test.html` punya `<video controls playsinline>` + `<audio>` + log `visibilityState/visibilitychange/document.hidden` + `timeupdate`.

## Steps
1. Foreground play video → cek log `playing`, `timeupdate` ++, `visibilityState=visible`.
2. Home 60s → `document.visibilityState` harus tetap `visible` (spoof), `hiddenForBinding=false`, log tidak ada `visibilitychange`, `currentTime` terus ++, audio lanjut. Jika pause → FAIL (cek `allow_background_video_playback_`).
3. Lock 60s → sama, plus MediaSession notif muncul, audio tetap.
4. Return → video frame resume, tidak perlu tap, `currentTime` continuous.
5. Pause → Home → Return → tetap pause.
6. AudioFocus: play musik lain / call → duck/pause, balik focus → resume jika fokus transient.
7. Seek via MediaSession (notif) saat background → `currentTime` jump ok.
8. Bandingkan `<audio opus>` control — harus selalu lanjut (baseline Chromium audio).

## Log Checklist
- [ ] `visibilityState` spoof `visible` saat hidden
- [ ] `hidden` spoof `false` saat hidden
- [ ] `visibilitychange` tidak fire saat Home/lock
- [ ] `timeupdate` continue 60s background
- [ ] `pause` tidak ke-trigger otomatis
- [ ] Foreground service notif ada

## Result Template
```
Device: Pixel 7 Android 14, Chromium 153.0.7999.0 + 3 patches
A foreground: PASS
B home 60s: PASS/FAIL (time X→Y)
C lock 60s: PASS/FAIL
...
N spoof: PASS/FAIL
```
