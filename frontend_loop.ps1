$ErrorActionPreference = 'Continue'
$adb = 'adb -s 100.71.211.98:46723'
$ws = 'ws://localhost:9222/devtools/page/60DE2EB998B242E37750CFCAE426107D'
$tracks = @('dQw4w9WgXcQ','9bZkp7q19f0','kJQP7kiw5Fk','JGwWNGJdvx8','RgKAFK5djSk','OPf0YbXqDm0','fJ9rUzIMcZQ','hT_nvWreIhg','YQHsXMglC9A','CevxZvSJLk8','60ItHLz5WEA','09R8_2nJtjg','kXYiU_JCYtU','3JZ_D3ELwOQ','2Vv-BfVoq4g','PT2_F-1esPk','pRpeEdMmmQ0','nfWlot6h_JM','ru0K8uYEZWw','K4DyBUG242c')
Remove-Item 'track_*.log' -Force -ErrorAction SilentlyContinue
Remove-Item 'frontend_403_test.txt' -Force -ErrorAction SilentlyContinue
$incident = $false
for ($i = 0; $i -lt $tracks.Count; $i++) {
  $n = $i + 1
  $id = $tracks[$i]
  & adb -s 100.71.211.98:46723 logcat -c
  node cdp_client.js $ws "window.__diagPlay('$id','Diag','Artist',0)" | Out-Null
  Start-Sleep -Seconds 3
  $started = (& adb -s 100.71.211.98:46723 logcat -d | Select-String -Pattern (('NATIVE_CMD.*play videoId=' + $id)))
  if (-not $started) { Write-Output "track $n $id injection failed"; & adb -s 100.71.211.98:46723 logcat -d | Out-File "track_${n}_${id}.log"; continue }
  $ended = $false
  for ($poll = 0; $poll -lt 28; $poll++) {
    Start-Sleep -Seconds 15
    $lines = @( & adb -s 100.71.211.98:46723 logcat -d | Select-String -Pattern '403|LOGIN|refresh|resume|FAILED|nextTrack|onCompletion|state=ENDED|NATIVE_CMD.*play videoId=' )
    $bad = $lines | Where-Object { $_ -match '403|refreshing URL|FAILED|nextTrack entered' }
    if ($bad) {
      Write-Output "INCIDENT track $n $id poll $poll"
      $lines | Select-Object -Last 20 | ForEach-Object { Write-Output $_ }
      & adb -s 100.71.211.98:46723 shell screencap -p "/sdcard/screen_403_${id}.png"
      & adb -s 100.71.211.98:46723 pull "/sdcard/screen_403_${id}.png" "." | Out-Null
      & adb -s 100.71.211.98:46723 logcat -d | Out-File "track_${n}_${id}_INCIDENT.log"
      $incident = $true
      break
    }
    if ($lines | Where-Object { $_ -match 'state=ENDED' }) { $ended = $true; break }
  }
  if (-not $incident) {
    & adb -s 100.71.211.98:46723 logcat -d | Out-File "track_${n}_${id}.log"
    Write-Output "track $n $id end=$ended"
  }
  if ($incident) { break }
  & adb -s 100.71.211.98:46723 shell am start -n com.dnialify.musicstream/.VisionOsDiagActivity --es diag_action native-stop | Out-Null
}
Get-Content track_*.log -ErrorAction SilentlyContinue | Set-Content frontend_403_test.txt
Write-Output "loop-complete incident=$incident"
