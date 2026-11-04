param(
    [string]$InputFile = "local-inputs/sample.pdf",
    [string]$OutDir = "work/phase-01",
    [string]$Jdk = "",
    [string]$RenderPages = "1,10,11,18,20,21,23",
    [switch]$NoRender,
    [switch]$PrepareOnly
)

$ErrorActionPreference = "Stop"
$scriptDir = $PSScriptRoot
$root = (Resolve-Path (Join-Path $scriptDir "..\..")).Path
$libDir = Join-Path $scriptDir "lib"
$buildDir = Join-Path $scriptDir "build"
$inputPath = if ([System.IO.Path]::IsPathRooted($InputFile)) { $InputFile } else { Join-Path $root $InputFile }
$outPath = if ([System.IO.Path]::IsPathRooted($OutDir)) { $OutDir } else { Join-Path $root $OutDir }

function Find-JdkBin {
    param([string]$Explicit)
    $candidates = @()
    if ($Explicit) { $candidates += $Explicit }
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    $javacCmd = Get-Command javac.exe -ErrorAction SilentlyContinue
    if ($javacCmd) { $candidates += (Split-Path (Split-Path $javacCmd.Source -Parent) -Parent) }
    $roots = @("C:\Program Files\JetBrains", "C:\Program Files\Eclipse Adoptium", "C:\Program Files\Microsoft")
    foreach ($base in $roots) {
        if (Test-Path $base) {
            $candidates += Get-ChildItem -Path $base -Filter "jbr" -Directory -ErrorAction SilentlyContinue |
                ForEach-Object { $_.FullName }
            $candidates += Get-ChildItem -Path $base -Directory -ErrorAction SilentlyContinue |
                ForEach-Object { Join-Path $_.FullName "jbr" }
            $candidates += Get-ChildItem -Path $base -Filter "jdk*" -Directory -ErrorAction SilentlyContinue |
                ForEach-Object { $_.FullName }
        }
    }
    foreach ($candidate in $candidates) {
        if (-not $candidate) { continue }
        $bin = if ((Split-Path $candidate -Leaf) -eq "bin") { $candidate } else { Join-Path $candidate "bin" }
        if ((Test-Path (Join-Path $bin "javac.exe")) -and (Test-Path (Join-Path $bin "java.exe"))) {
            return $bin
        }
    }
    return $null
}

$jdkBin = Find-JdkBin -Explicit $Jdk
if (-not $jdkBin) {
    throw "No JDK found. Pass -Jdk <path-to-jdk> or set JAVA_HOME. Tested with JBR 21.0.9."
}
$java = Join-Path $jdkBin "java.exe"
$javac = Join-Path $jdkBin "javac.exe"

New-Item -ItemType Directory -Force -Path $libDir, $buildDir, $outPath | Out-Null

$deps = @(
    @{ Name = "pdfbox-2.0.27.jar"; Sha = "A25AD2A0BE6B0BF9EB0E972ABD09C34C0E797A3CE2A980D5FF035FF4CF078037"; Url = "https://repo1.maven.org/maven2/org/apache/pdfbox/pdfbox/2.0.27/pdfbox-2.0.27.jar" },
    @{ Name = "fontbox-2.0.27.jar"; Sha = "DC7429868AAF3D313C524B9AAB846A405E89CA4927F35762CA4D1A60BCE1D7F4"; Url = "https://repo1.maven.org/maven2/org/apache/pdfbox/fontbox/2.0.27/fontbox-2.0.27.jar" },
    @{ Name = "commons-logging-1.2.jar"; Sha = "DADDEA1EA0BE0F56978AB3006B8AC92834AFEEFBD9B7E4E6316FCA57DF0FA636"; Url = "https://repo1.maven.org/maven2/commons-logging/commons-logging/1.2/commons-logging-1.2.jar" }
)
foreach ($dep in $deps) {
    $dest = Join-Path $libDir $dep.Name
    $needsDownload = $true
    if (Test-Path $dest) {
        $hash = (Get-FileHash $dest -Algorithm SHA256).Hash
        $needsDownload = ($hash -ne $dep.Sha)
    }
    if ($needsDownload) {
        $tmp = "$dest.download"
        if (Test-Path $tmp) { Remove-Item $tmp -Force }
        & curl.exe -L --fail --silent --show-error $dep.Url -o $tmp
        if ($LASTEXITCODE -ne 0) { throw "download failed: $($dep.Url)" }
        $hash = (Get-FileHash $tmp -Algorithm SHA256).Hash
        if ($hash -ne $dep.Sha) {
            Remove-Item $tmp -Force
            throw "SHA-256 mismatch for $($dep.Name): $hash"
        }
        Move-Item $tmp $dest -Force
    }
}

$cp = (@(
    (Join-Path $libDir "pdfbox-2.0.27.jar"),
    (Join-Path $libDir "fontbox-2.0.27.jar"),
    (Join-Path $libDir "commons-logging-1.2.jar")
) -join ";") + ";" + $buildDir

$sources = @(Get-ChildItem -Path (Join-Path $scriptDir "src") -Recurse -Filter "*.java" |
    ForEach-Object { $_.FullName })
& $javac -encoding UTF-8 -cp $cp -d $buildDir $sources
if ($LASTEXITCODE -ne 0) { throw "javac failed with exit code $LASTEXITCODE" }

Write-Output "JDK_BIN=$jdkBin"
if ($PrepareOnly) {
    Write-Host "Prepared JDK, dependencies and probe classes (no PDF executed)."
    exit 0
}

$arguments = @("--input=$inputPath", "--out=$outPath")
if (-not $NoRender -and $RenderPages) { $arguments += "--render=$RenderPages" }
$commandLine = "`"$java`" -Dfile.encoding=UTF-8 -Djava.awt.headless=true -cp `"$cp`" pdfprobe.PdfProbe $($arguments -join ' ')"
Write-Host "JDK: $jdkBin"
Write-Host "Command: $commandLine"
& $java "-Dfile.encoding=UTF-8" "-Djava.awt.headless=true" -cp $cp pdfprobe.PdfProbe @arguments
if ($LASTEXITCODE -ne 0) { throw "probe failed with exit code $LASTEXITCODE" }

$runInfo = @(
    "JDK_BIN=$jdkBin",
    "INPUT=$inputPath",
    "OUTPUT=$outPath",
    "COMMAND=$commandLine"
) -join "`n"
Set-Content -Path (Join-Path $outPath "raw\run-command.txt") -Value $runInfo -Encoding UTF8
Write-Host "Done: $(Join-Path $outPath 'questions.json')"
