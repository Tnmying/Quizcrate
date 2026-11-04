param(
    [string]$Jdk = "",
    [string]$ApkName = "offlinequiz-03a-debug.apk",
    [switch]$SkipTests
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$androidDir = Join-Path $root "android"
$sdkDir = Join-Path $root "work\android-sdk"
$gradleUserHome = Join-Path $root "work\gradle-user-home"
$androidUserHome = Join-Path $root "work\android-user-home"
$buildLogDir = Join-Path $root "work\android-build"
$apkOutDir = Join-Path $root "outputs\apk"
New-Item -ItemType Directory -Force -Path $gradleUserHome, $androidUserHome, $buildLogDir, $apkOutDir | Out-Null

function Find-JdkBin {
    param([string]$Explicit)
    $candidates = @()
    if ($Explicit) { $candidates += $Explicit }
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    $javacCmd = Get-Command javac.exe -ErrorAction SilentlyContinue
    if ($javacCmd) { $candidates += (Split-Path (Split-Path $javacCmd.Source -Parent) -Parent) }
    $candidates += Get-ChildItem -Path (Join-Path $root "work") -Filter "jdk*" -Directory -ErrorAction SilentlyContinue |
        ForEach-Object { $_.FullName }
    foreach ($base in @("C:\Program Files\JetBrains", "C:\Program Files\Eclipse Adoptium", "C:\Program Files\Microsoft")) {
        if (Test-Path $base) {
            $candidates += Get-ChildItem -Path $base -Directory -ErrorAction SilentlyContinue |
                ForEach-Object { Join-Path $_.FullName "jbr" }
            $candidates += Get-ChildItem -Path $base -Filter "jdk*" -Directory -ErrorAction SilentlyContinue |
                ForEach-Object { $_.FullName }
        }
    }
    foreach ($candidate in $candidates) {
        if (-not $candidate) { continue }
        $bin = if ((Split-Path $candidate -Leaf) -eq "bin") { $candidate } else { Join-Path $candidate "bin" }
        if ((Test-Path (Join-Path $bin "javac.exe")) -and
            (Test-Path (Join-Path $bin "java.exe")) -and
            (Test-Path (Join-Path $bin "jlink.exe"))) {
            return $bin
        }
    }
    return $null
}

$jdkBin = Find-JdkBin -Explicit $Jdk
if (-not $jdkBin) { throw "No JDK found. Pass -Jdk <jdk-path>." }
$javaHome = Split-Path $jdkBin -Parent
$env:JAVA_HOME = $javaHome
$env:ANDROID_HOME = $sdkDir
$env:ANDROID_SDK_ROOT = $sdkDir
$env:ANDROID_USER_HOME = $androidUserHome
$env:GRADLE_USER_HOME = $gradleUserHome

if (-not (Test-Path (Join-Path $sdkDir "platforms\android-36\android.jar"))) {
    throw "Android platform 36 is missing under $sdkDir"
}
if (-not (Test-Path (Join-Path $sdkDir "build-tools\36.0.0\aapt2.exe"))) {
    throw "Build-tools 36.0.0 is missing under $sdkDir"
}

$wrapper = Join-Path $androidDir "gradlew.bat"
if (Test-Path $wrapper) {
    $gradleCmd = $wrapper
    $gradlePrefix = @()
} else {
    $gradleHome = Join-Path $root "work\gradle-8.13"
    $gradleCmd = Join-Path $gradleHome "bin\gradle.bat"
    $gradlePrefix = @()
    if (-not (Test-Path $gradleCmd)) { throw "Gradle wrapper missing and $gradleCmd not found" }
}

$log = Join-Path $buildLogDir "build.log"
$commands = @()
if (-not $SkipTests) { $commands += ":app:testDebugUnitTest" }
$commands += ":app:assembleDebug"

foreach ($task in $commands) {
    Write-Host "Running $task"
    $gradleArgs = @("--project-dir", $androidDir, "--no-daemon", "--stacktrace", $task)
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = & $gradleCmd @gradleArgs 2>&1
        $exit = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
    }
    $output | Tee-Object -FilePath $log -Append
    if ($exit -ne 0) { throw "Gradle task $task failed with exit code $exit" }
}

$apk = Join-Path $androidDir "app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path $apk)) { throw "debug APK not found: $apk" }
$target = Join-Path $apkOutDir $ApkName
Copy-Item $apk $target -Force
$size = (Get-Item $target).Length
$hash = (Get-FileHash $target -Algorithm SHA256).Hash
$summary = @(
    "java_home=$javaHome",
    "android_home=$sdkDir",
    "gradle_user_home=$gradleUserHome",
    "apk=$target",
    "apk_bytes=$size",
    "apk_sha256=$hash",
    "tasks=$($commands -join ',')"
) -join "`n"
Set-Content -Path (Join-Path $buildLogDir "build-summary.txt") -Value $summary -Encoding UTF8
Write-Host $summary
