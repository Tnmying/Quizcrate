param(
    [string]$Jdk = "",
    [string]$Sample = "local-inputs/sample.pdf"
)

$ErrorActionPreference = "Stop"
$scriptDir = $PSScriptRoot
$root = (Resolve-Path (Join-Path $scriptDir "..\..")).Path
$samplePath = if ([System.IO.Path]::IsPathRooted($Sample)) { $Sample } else { Join-Path $root $Sample }
$prepareOut = Join-Path $root "work\phase-01-regression-bootstrap"
$runScript = Join-Path $scriptDir "run.ps1"

# Prepare JDK, dependencies and probe classes without requiring a private sample.
$runArgs = @(
    "-NoProfile", "-ExecutionPolicy", "Bypass",
    "-File", $runScript,
    "-OutDir", $prepareOut,
    "-NoRender",
    "-PrepareOnly"
)
if ($Jdk) { $runArgs += @("-Jdk", $Jdk) }
$prepareLog = & powershell.exe @runArgs
if ($LASTEXITCODE -ne 0) { throw "probe preparation failed with exit code $LASTEXITCODE" }

$jdkLine = $prepareLog | Where-Object { $_ -like "JDK_BIN=*" } | Select-Object -First 1
if (-not $jdkLine) { throw "JDK_BIN not found in preparation output" }
$jdkBin = $jdkLine.Substring("JDK_BIN=".Length)
$java = Join-Path $jdkBin "java.exe"
$javac = Join-Path $jdkBin "javac.exe"
if (-not (Test-Path $java) -or -not (Test-Path $javac)) {
    throw "preparation JDK is incomplete: $jdkBin"
}

$lib = Join-Path $scriptDir "lib"
$cp = (@(
    (Join-Path $lib "pdfbox-2.0.27.jar"),
    (Join-Path $lib "fontbox-2.0.27.jar"),
    (Join-Path $lib "commons-logging-1.2.jar"),
    (Join-Path $scriptDir "build")
) -join ";")
$testBuild = Join-Path $root "work\phase-01-regression-build"
New-Item -ItemType Directory -Force -Path $testBuild | Out-Null
$tests = @(Get-ChildItem -Path (Join-Path $scriptDir "tests") -Recurse -Filter "*.java" |
    ForEach-Object { $_.FullName })
if ($tests.Count -eq 0) { throw "no regression tests found" }

& $javac -encoding UTF-8 -cp $cp -d $testBuild $tests
if ($LASTEXITCODE -ne 0) { throw "regression javac failed with exit code $LASTEXITCODE" }

& $java "-Dfile.encoding=UTF-8" "-Djava.awt.headless=true" -cp "$cp;$testBuild" `
    pdfprobe.regression.PdfProbeRegression $samplePath
$exit = $LASTEXITCODE
Write-Host "regression_exit=$exit"
exit $exit
