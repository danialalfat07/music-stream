param([string]$Branch="feature/apk-chromium-shell", [int]$TimeoutMin=60, [int]$IntervalSec=30)
$start = Get-Date
Write-Host "Monitor CI branch $Branch timeout ${TimeoutMin}m interval ${IntervalSec}s" -ForegroundColor Cyan
while (((Get-Date)-$start).TotalMinutes -lt $TimeoutMin) {
  try {
    $json = rtk gh run list --branch $Branch --limit 1 --json databaseId,status,conclusion,headSha,createdAt 2>&1
    if ($LASTEXITCODE -ne 0) { throw $json }
    $run = $json | ConvertFrom-Json
    if ($run -is [Array]) { $run = $run[0] }
    if (-not $run) { Write-Host "no run yet, retry $IntervalSec sec..." ; Start-Sleep $IntervalSec; continue }
    $id = $run.databaseId; $st=$run.status; $con=$run.conclusion; $sha=$run.headSha.Substring(0,7)
    Write-Host "$(Get-Date -Format HH:mm:ss) run $id $sha $st/$con"
    if ($st -eq "completed") {
      rtk gh run view $id --log 2>&1 | Out-File -Append "ci-monitor.log"
      if ($con -eq "success") { Write-Host "SUCCESS $id" -ForegroundColor Green; exit 0 }
      else { Write-Host "FAILED $con" -ForegroundColor Red; rtk gh run view $id --log 2>&1 | Select-Object -Last 80; exit 1 }
    }
  } catch {
    Write-Host "gh error: $_" -ForegroundColor Yellow
  }
  Start-Sleep $IntervalSec
}
Write-Host "TIMEOUT ${TimeoutMin}m" -ForegroundColor Yellow; exit 2
