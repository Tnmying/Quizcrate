param()

# Deterministic packaging resize only; the approved original is kept untouched in docs/.
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$sourcePath = Join-Path $projectRoot 'docs/design/assets/tixia-icon-concept-v1.png'
$outputDir = Join-Path $projectRoot 'android/app/src/main/res/drawable-nodpi'
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
Add-Type -AssemblyName System.Drawing
$sourceImage = [System.Drawing.Image]::FromFile($sourcePath)
$resultImage = [System.Drawing.Bitmap]::new(432, 432)
$graphics = [System.Drawing.Graphics]::FromImage($resultImage)
try {
    $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $graphics.DrawImage($sourceImage, 0, 0, 432, 432)
    $resultImage.Save((Join-Path $outputDir 'ic_launcher_art.png'), [System.Drawing.Imaging.ImageFormat]::Png)
} finally {
    $graphics.Dispose()
    $resultImage.Dispose()
    $sourceImage.Dispose()
}
Get-FileHash -LiteralPath (Join-Path $outputDir 'ic_launcher_art.png') -Algorithm SHA256
