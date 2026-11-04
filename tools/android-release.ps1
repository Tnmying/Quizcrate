param(
    [string]$Jdk = "",
    [string]$Sdk = "",
    [string]$Keystore = "",
    [string]$PasswordFile = "",
    [string]$KeyAlias = "quizcrate-release",
    [switch]$Online,
    [switch]$RerunTasks
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $Jdk) { $Jdk = Join-Path $projectRoot 'work/jdk-21.0.13+9' }
if (-not $Sdk) { $Sdk = Join-Path $projectRoot 'work/android-sdk' }
if (-not $Keystore) { $Keystore = Join-Path $projectRoot 'local-inputs/signing/quizcrate-release.p12' }
if (-not $PasswordFile) { $PasswordFile = Join-Path $projectRoot 'local-inputs/signing/keystore-password.txt' }
foreach ($requiredPath in @($Jdk, $Sdk, $Keystore, $PasswordFile)) {
    if (-not (Test-Path -LiteralPath $requiredPath)) { throw "Required local path missing: $requiredPath" }
}
$env:JAVA_HOME = (Resolve-Path -LiteralPath $Jdk).Path
$env:ANDROID_HOME = (Resolve-Path -LiteralPath $Sdk).Path
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:GRADLE_USER_HOME = Join-Path $projectRoot 'work/gradle-user-home'
$env:ANDROID_USER_HOME = Join-Path $projectRoot 'work/android-user-home'
$buildTools = Join-Path $env:ANDROID_HOME 'build-tools/36.0.0'
$gradle = Join-Path $projectRoot 'android/gradlew.bat'
$buildFile = Get-Content -LiteralPath (Join-Path $projectRoot 'android/app/build.gradle.kts') -Raw
$versionMatch = [regex]::Match($buildFile, 'versionName\s*=\s*"([0-9]+\.[0-9]+\.[0-9]+)"')
if (-not $versionMatch.Success) { throw 'Expected a numeric major.minor.patch versionName.' }
$version = $versionMatch.Groups[1].Value
$releaseDir = Join-Path $projectRoot "outputs/releases/$version"
$runDir = Join-Path $projectRoot ('work/release-build-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
$target = Join-Path $releaseDir "Quizcrate-$version.apk"
if (Test-Path -LiteralPath $target) { throw "Refusing to overwrite existing release: $target" }
New-Item -ItemType Directory -Force -Path $runDir, $releaseDir | Out-Null

function Invoke-LoggedNative {
    param([string]$Command, [string[]]$Arguments, [string]$LogPath)
    $savedPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $Command @Arguments *> $LogPath
        $nativeExit = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $savedPreference
    }
    if ($nativeExit -ne 0) { throw "Native command failed ($nativeExit). See $LogPath" }
}

$gradleArgs = @('--project-dir', (Join-Path $projectRoot 'android'), '--no-daemon',
    '-Pkotlin.incremental=false', '-Pksp.incremental=false',
    ':app:testReleaseUnitTest', ':app:assembleRelease', ':app:lintRelease')
if (-not $Online) { $gradleArgs += '--offline' }
if ($RerunTasks) { $gradleArgs += '--rerun-tasks' }
Write-Output "Building and checking release $version..."
Invoke-LoggedNative -Command $gradle -Arguments $gradleArgs -LogPath (Join-Path $runDir 'build.log')

$unsigned = Join-Path $projectRoot 'android/app/build/outputs/apk/release/app-release-unsigned.apk'
if (-not (Test-Path -LiteralPath $unsigned)) { throw 'Unsigned release APK was not produced.' }
$aligned = Join-Path $runDir 'aligned-unsigned.apk'
Invoke-LoggedNative -Command (Join-Path $buildTools 'zipalign.exe') -Arguments @('-P', '16', '4', $unsigned, $aligned) -LogPath (Join-Path $runDir 'align.log')
$signed = Join-Path $runDir "Quizcrate-$version.apk"
Invoke-LoggedNative -Command (Join-Path $buildTools 'apksigner.bat') -Arguments @('sign', '--ks', (Resolve-Path -LiteralPath $Keystore).Path,
    '--ks-type', 'PKCS12', '--ks-key-alias', $KeyAlias,
    '--ks-pass', ('file:' + (Resolve-Path -LiteralPath $PasswordFile).Path),
    '--v4-signing-enabled', 'false', '--out', $signed, $aligned) -LogPath (Join-Path $runDir 'sign.log')
Invoke-LoggedNative -Command (Join-Path $buildTools 'apksigner.bat') -Arguments @('verify', '--verbose', '--print-certs', $signed) -LogPath (Join-Path $runDir 'signature.log')
Invoke-LoggedNative -Command (Join-Path $buildTools 'zipalign.exe') -Arguments @('-c', '-P', '16', '4', $signed) -LogPath (Join-Path $runDir 'alignment-check.log')
Invoke-LoggedNative -Command (Join-Path $buildTools 'aapt.exe') -Arguments @('dump', 'badging', $signed) -LogPath (Join-Path $runDir 'badging.log')
$badging = Get-Content -LiteralPath (Join-Path $runDir 'badging.log') -Raw
if ($badging -match '(?m)^application-debuggable') { throw 'Release APK is unexpectedly debuggable.' }

Copy-Item -LiteralPath $signed -Destination $target
$sha256 = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant()
$checksumPath = Join-Path $releaseDir "Quizcrate-$version.apk.sha256"
[IO.File]::WriteAllText($checksumPath, ($sha256 + '  ' + (Split-Path $target -Leaf) + "`n"), [Text.Encoding]::ASCII)
$result = [ordered]@{ version = $version; apk = $target; bytes = (Get-Item -LiteralPath $target).Length; sha256 = $sha256; logs = $runDir }
$result | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $runDir 'result.json') -Encoding UTF8
$result | ConvertTo-Json
