Add-Type -AssemblyName System.Drawing
$src = Resolve-Path "public\icon-512.png"
$img = [System.Drawing.Image]::FromFile($src.Path)
Write-Host "src $($img.Width)x$($img.Height)"
$sizes = @{
  'mipmap-mdpi'=48
  'mipmap-hdpi'=72
  'mipmap-xhdpi'=96
  'mipmap-xxhdpi'=144
  'mipmap-xxxhdpi'=192
}
foreach ($k in $sizes.Keys) {
  $s = $sizes[$k]
  $bmp = New-Object System.Drawing.Bitmap $s,$s
  $g = [System.Drawing.Graphics]::FromImage($bmp)
  $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
  $g.Clear([System.Drawing.Color]::Black)
  $g.DrawImage($img,0,0,$s,$s)
  $path = "android\app\src\main\res\$k\ic_launcher.png"
  $bmp.Save((Resolve-Path $path).Path, [System.Drawing.Imaging.ImageFormat]::Png)
  $g.Dispose(); $bmp.Dispose()
  Write-Host "wrote $k $s"
  # also round
  $pathR = "android\app\src\main\res\$k\ic_launcher_round.png"
  $bmp2 = New-Object System.Drawing.Bitmap $s,$s
  $g2 = [System.Drawing.Graphics]::FromImage($bmp2)
  $g2.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
  $g2.Clear([System.Drawing.Color]::Transparent)
  # clip circle
  $gp = New-Object System.Drawing.Drawing2D.GraphicsPath
  $gp.AddEllipse(0,0,$s,$s)
  $g2.SetClip($gp)
  $g2.DrawImage($img,0,0,$s,$s)
  $bmp2.Save((Resolve-Path $pathR).Path, [System.Drawing.Imaging.ImageFormat]::Png)
  $g2.Dispose(); $bmp2.Dispose()
  Write-Host "wrote $k round"
  # foreground (same for adaptive)
  $pathF = "android\app\src\main\res\$k\ic_launcher_foreground.png"
  $bmp3 = New-Object System.Drawing.Bitmap $s,$s
  $g3 = [System.Drawing.Graphics]::FromImage($bmp3)
  $g3.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
  $g3.Clear([System.Drawing.Color]::Transparent)
  $g3.DrawImage($img,0,0,$s,$s)
  $bmp3.Save((Resolve-Path $pathF).Path, [System.Drawing.Imaging.ImageFormat]::Png)
  $g3.Dispose(); $bmp3.Dispose()
  Write-Host "wrote $k fg"
}
$img.Dispose()
Write-Host "done"
