for ($i=0; $i -lt 20; $i++) {
  try {
    $r = Invoke-RestMethod -Uri 'https://api.github.com/repos/danialalfat07/music-stream/actions/runs?per_page=5' -Headers @{'Accept'='application/vnd.github+json'}
    $r.workflow_runs | ForEach-Object { Write-Host "$($_.name) $($_.head_sha.Substring(0,7)) $($_.status)/$($_.conclusion) $($_.created_at)" }
    $cap = $r.workflow_runs | Where-Object { $_.name -eq 'Build Capacitor APK' } | Select-Object -First 1
    if ($cap) {
      Write-Host "CAP $($cap.status)/$($cap.conclusion) id $($cap.id) $($cap.html_url)"
      if ($cap.status -eq 'completed') {
        Write-Host "DONE $($cap.conclusion)"
        if ($cap.conclusion -eq 'success') { Write-Host "ARTIFACT $($cap.artifacts_url)" }
        break
      }
    } else { Write-Host "no cap run yet" }
  } catch { Write-Host "err $_" }
  Write-Host "sleep 30..."
  Start-Sleep 30
}
