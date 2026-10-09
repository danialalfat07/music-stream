$ErrorActionPreference = 'Continue'
$serial = '100.71.211.98:46723'
$ws = 'ws://localhost:9222/devtools/page/6B24BD8C09C1DA3E1A75121378E8B846'
$appPid = '32516'
$ids = @('dQw4w9WgXcQ','9bZkp7q19f0','kJQP7kiw5Fk','JGwWNGJdvx8','RgKAFK5djSk','OPf0YbXqDm0','fJ9rUzIMcZQ','hT_nvWreIhg','YQHsXMglC9A','CevxZvSJLk8','60ItHLz5WEA','09R8_2nJtjg','kXYiU_JCYtU','3JZ_D3ELwOQ','2Vv-BfVoq4g','PT2_F-1esPk','pRpeEdMmmQ0','nfWlot6h_JM','ru0K8uYEZWw','K4DyBUG242c')
Remove-Item 'strict_track_*.log','frontend_403_test.txt' -Force -ErrorAction SilentlyContinue
$incident = $false
$attempted = 0
for ($i = 0; $i -lt $ids.Count; $i++) {
  $n = $i + 1; $id = $ids[$i]; $attempted = $n
  adb -s $serial logcat -c
  node cdp_client.js $ws "window.__diagPlay('$id','Diag','Artist',0)" | Out-Null
  Start-Sleep -Seconds 3
  $raw = @(adb -s $serial logcat -d)
  $app = @($raw | Where-Object { $_ -match "\s$appPid\s" })
  $started = @($app | Where-Object { $_ -match "\[NATIVE_CMD\] play videoId=$id" })
  if ($started.Count -eq 0) {
    $app | Set-Content "strict_track_${n}_${id}.log"
    continue
  }
  $done = $false
  for ($poll = 0; $poll -lt 32; $poll++) {
    Start-Sleep -Seconds 15
    $raw = @(adb -s $serial logcat -d)
    $app = @($raw | Where-Object { $_ -match "\s$appPid\s" })
    $cacheBad = @($app | Where-Object {
      $_ -match '\[CACHE\] 403' -or
      $_ -match '\[CACHE\] 401' -or
      $_ -match 'LOGIN_REQUIRED' -or
      $_ -match 'refreshing URL' -or
      $_ -match '\[CACHE\] FAILED'
    })
    if ($cacheBad.Count -gt 0) {
      adb -s $serial shell screencap -p "/sdcard/screen_403_${id}.png" | Out-Null
      adb -s $serial pull "/sdcard/screen_403_${id}.png" "." | Out-Null
      $app | Set-Content "strict_track_${n}_${id}_INCIDENT.log"
      $incident = $true
      break
    }
    $ended = @($app | Where-Object { $_ -match '\[NATIVE_STATE\] state=ENDED' })
    $next = @($app | Where-Object { $_ -match 'nextTrack entered|\[NATIVE_CMD\] play videoId=' -and $_ -notmatch "play videoId=$id" })
    if ($ended.Count -gt 0 -or $next.Count -gt 0) { $done = $true; break }
  }
  $app | Set-Content "strict_track_${n}_${id}.log"
  if ($incident) { break }
  adb -s $serial shell am start -n com.dnialify.musicstream/.VisionOsDiagActivity --es diag_action native-stop | Out-Null
}
Get-Content strict_track_*.log -ErrorAction SilentlyContinue | Set-Content frontend_403_test.txt
"attempted=$attempted incident=$incident" | Set-Content strict_loop_result.txt
