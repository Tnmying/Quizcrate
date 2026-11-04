param(
    [string]$FontPath = "",
    [string]$SourceFont = "C:\Windows\Fonts\NotoSerifSC-VF.ttf",
    [string]$OutFile = (Join-Path $PSScriptRoot "offlinequiz-example.pdf")
)

$ErrorActionPreference = "Continue"
$root = Split-Path $PSScriptRoot -Parent
$jdk = Join-Path $root "work\jdk-21.0.13+9"
$javac = Join-Path $jdk "bin\javac.exe"
$java = Join-Path $jdk "bin\java.exe"
$lib = Join-Path $root "tools\pdf-probe\lib"
$classes = Join-Path $root "work\04a\classes"

if (-not (Test-Path $javac)) { throw "JDK not found: $javac" }
if (-not (Test-Path (Join-Path $lib "pdfbox-2.0.27.jar"))) {
    throw "PDFBox jars not found in $lib (run tools/pdf-probe/run-tests.ps1 once)"
}
if (-not $FontPath) {
    # Stock Noto Serif SC maps some everyday characters to Kangxi-radical code points.
    # Derive a working copy with only those compatibility cmap entries removed; see
    # prepare-example-font.py and examples/README.md.
    $prepared = Join-Path $root "work\04a\NotoSerifSC-nocompat.ttf"
    if (-not (Test-Path $prepared)) {
        if (-not (Test-Path $SourceFont)) {
            throw "CJK source font not found: $SourceFont; pass -SourceFont or -FontPath"
        }
        py (Join-Path $PSScriptRoot "prepare-example-font.py") $SourceFont $prepared
        if ($LASTEXITCODE -ne 0) { throw "font preparation failed" }
    }
    $FontPath = $prepared
}
if (-not (Test-Path $FontPath)) {
    throw "CJK font not found: $FontPath. Pass -FontPath with a local CJK TTF/OTF."
}
New-Item -ItemType Directory -Force -Path $classes | Out-Null

$cp = @(
    (Join-Path $lib "pdfbox-2.0.27.jar"),
    (Join-Path $lib "fontbox-2.0.27.jar"),
    (Join-Path $lib "commons-logging-1.2.jar")
) -join ";"

& $javac -encoding UTF-8 -cp $cp -d $classes (Join-Path $PSScriptRoot "MakeExamplePdf.java")
if ($LASTEXITCODE -ne 0) { throw "javac failed" }
& $java -cp "$classes;$cp" MakeExamplePdf $FontPath $OutFile
if ($LASTEXITCODE -ne 0) { throw "java failed" }
Write-Host "generated: $OutFile"
