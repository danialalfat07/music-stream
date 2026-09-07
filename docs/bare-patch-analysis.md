# Bare Patch Analysis — 0043 / 0050 / 0051

> Template per patch — diisi setelah baca diff asli Bare

## Patch 0043 — Let supported sites keep playing media in the background
- **Patch:**
- **Purpose:**
- **Files changed:**
- **Classes/Functions changed:**
- **Original behavior:**
- **New behavior:**
- **Why it matters (audio vs video background):**
- **Dependencies:**
- **Can it be isolated:**
- **Side effects:**

## Patch 0050 — Keep sites told the page is visible in the background
- **Patch:**
- **Purpose:**
- **Files changed:**
- **Classes/Functions changed:**
- **Original behavior (visibilityState hidden):**
- **New behavior:**
- **Why it matters (JS `video.pause()` on visibilitychange):**
- **Dependencies:**
- **Can it be isolated:**
- **Side effects:**

## Patch 0051 — Keep background playback permission when hidden
- **Patch:**
- **Purpose:**
- **Files changed:**
- **Classes/Functions changed:**
- **Original behavior (`allow_background_video_playback_` revoked):**
- **New behavior:**
- **Why it matters (`OnPageHidden` permission):**
- **Dependencies:**
- **Can it be isolated:**
- **Side effects:**

## Checklist
- [ ] Clone https://github.com/BareBrowser/bare-browser
- [ ] `git log --oneline --grep=0043` + `git show`
- [ ] `git log --oneline --grep=0050` + `git show`
- [ ] `git log --oneline --grep=0051` + `git show`
- [ ] Catat Chromium base version + Bare patch baseline
