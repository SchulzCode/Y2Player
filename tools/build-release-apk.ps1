# Builds and verifies the production Y2Player APK.
#
# Run from the repository root:
#
#   powershell -ExecutionPolicy Bypass -File .\tools\build-release-apk.ps1
#
# Requirements:
# - JDK 17+
# - Android SDK Platform 36
# - Android Build Tools
# - populated keystore.properties
#
# Output:
#   dist\firmware\Y2Player.apk
#   dist\firmware\apk-build-report.txt

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

# These values remain explicit release invariants.
$expectedPackage = "com.schulzcode.y2player"
$expectedMinSdk = "19"
$expectedTargetSdk = "19"

function Fail {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Message
    )

    Write-Host ""
    Write-Host "FAIL: $Message" -ForegroundColor Red
    exit 1
}

function Pass {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Message
    )

    Write-Host "  ok   $Message" -ForegroundColor Green
}

function Find-AndroidSdk {
    $candidates = New-Object System.Collections.Generic.List[string]

    if ($env:ANDROID_SDK_ROOT) {
        $candidates.Add($env:ANDROID_SDK_ROOT)
    }

    if ($env:ANDROID_HOME) {
        $candidates.Add($env:ANDROID_HOME)
    }

    $localPropertiesPath = Join-Path $root "local.properties"

    if (Test-Path $localPropertiesPath) {
        $sdkLine = Get-Content $localPropertiesPath |
            Where-Object { $_ -match '^\s*sdk\.dir\s*=' } |
            Select-Object -First 1

        if ($sdkLine) {
            $sdkPath = ($sdkLine -split '=', 2)[1].Trim()

            # Decode common Java-properties escaping for Windows paths.
            $sdkPath = $sdkPath.Replace("\\", "\")
            $sdkPath = $sdkPath.Replace("\:", ":")

            if ($sdkPath) {
                $candidates.Add($sdkPath)
            }
        }
    }

    if ($env:LOCALAPPDATA) {
        $candidates.Add(
            (Join-Path $env:LOCALAPPDATA "Android\Sdk")
        )
    }

    if ($env:USERPROFILE) {
        $candidates.Add(
            (Join-Path $env:USERPROFILE "AppData\Local\Android\Sdk")
        )
    }

    foreach ($candidate in $candidates | Select-Object -Unique) {
        if (-not $candidate) {
            continue
        }

        $resolvedCandidate = [Environment]::ExpandEnvironmentVariables(
            $candidate.Trim()
        )

        if (
            (Test-Path $resolvedCandidate) -and
            (Test-Path (Join-Path $resolvedCandidate "build-tools"))
        ) {
            return (Resolve-Path $resolvedCandidate).Path
        }
    }

    return $null
}

function Get-BuildToolsDirectory {
    param(
        [Parameter(Mandatory = $true)]
        [string]$SdkPath
    )

    $buildToolsRoot = Join-Path $SdkPath "build-tools"

    if (-not (Test-Path $buildToolsRoot)) {
        return $null
    }

    $directories = Get-ChildItem $buildToolsRoot -Directory |
        Sort-Object {
            try {
                [version]$_.Name
            }
            catch {
                [version]"0.0"
            }
        } -Descending

    foreach ($directory in $directories) {
        $aapt = Join-Path $directory.FullName "aapt.exe"
        $apksigner = Join-Path $directory.FullName "apksigner.bat"
        $zipalign = Join-Path $directory.FullName "zipalign.exe"

        if (
            (Test-Path $aapt) -and
            (Test-Path $apksigner) -and
            (Test-Path $zipalign)
        ) {
            return $directory
        }
    }

    return $null
}

function Get-AppVersion {
    $gradleCandidates = @(
        (Join-Path $root "app\build.gradle.kts"),
        (Join-Path $root "app\build.gradle")
    )

    $gradleFile = $gradleCandidates |
        Where-Object { Test-Path $_ } |
        Select-Object -First 1

    if (-not $gradleFile) {
        Fail @"
No app Gradle build file was found.

Expected one of:

app\build.gradle.kts
app\build.gradle
"@
    }

    $gradleText = Get-Content $gradleFile -Raw

    # Kotlin DSL:
    #   versionCode = 1
    #
    # Groovy DSL:
    #   versionCode 1
    $versionCodeMatch = [regex]::Match(
        $gradleText,
        '(?m)^\s*versionCode\s*(?:=\s*|\s+)(\d+)\s*(?://.*)?$'
    )

    # Kotlin DSL:
    #   versionName = "1.0"
    #
    # Groovy DSL:
    #   versionName "1.0"
    $versionNameMatch = [regex]::Match(
        $gradleText,
        '(?m)^\s*versionName\s*(?:=\s*|\s+)["'']([^"'']+)["'']\s*(?://.*)?$'
    )

    if (-not $versionCodeMatch.Success) {
        Fail @"
Could not read versionCode from:

$gradleFile

Use a literal versionCode declaration such as:

Kotlin DSL:
    versionCode = 1

Groovy DSL:
    versionCode 1
"@
    }

    if (-not $versionNameMatch.Success) {
        Fail @"
Could not read versionName from:

$gradleFile

Use a literal versionName declaration such as:

Kotlin DSL:
    versionName = "1.0"

Groovy DSL:
    versionName "1.0"
"@
    }

    return @{
        GradleFile = $gradleFile
        VersionCode = $versionCodeMatch.Groups[1].Value
        VersionName = $versionNameMatch.Groups[1].Value
    }
}

Write-Host ""
Write-Host "Y2Player production APK build" -ForegroundColor Cyan
Write-Host "Repository: $root"

if (-not (Test-Path ".\gradlew.bat")) {
    Fail "gradlew.bat not found. Run this script from the Y2Player repository."
}

if (-not (Test-Path ".\keystore.properties")) {
    Fail @"
keystore.properties is missing.

Copy keystore.properties.example to keystore.properties, create the release
keystore and fill in its path, alias and passwords.
"@
}

$releaseKeystoreSetting = Get-Content ".\keystore.properties" |
    Where-Object { $_ -match '^\s*storeFile\s*=' } |
    Select-Object -First 1

if (-not $releaseKeystoreSetting) {
    Fail "storeFile is missing from keystore.properties."
}

# Read version information from the app's Gradle configuration.
$appVersion = Get-AppVersion
$expectedVersionName = $appVersion.VersionName
$expectedVersionCode = $appVersion.VersionCode

Pass "Gradle version source: $($appVersion.GradleFile)"
Pass "Version: $expectedVersionName ($expectedVersionCode)"

$sdk = Find-AndroidSdk

if (-not $sdk) {
    Fail @"
Android SDK not found.

Set ANDROID_SDK_ROOT or ANDROID_HOME, or ensure local.properties contains:

sdk.dir=C\:\\Users\\Luca\\AppData\\Local\\Android\\Sdk
"@
}

Pass "Android SDK found: $sdk"

$buildTools = Get-BuildToolsDirectory -SdkPath $sdk

if (-not $buildTools) {
    Fail @"
No complete Android Build Tools installation was found under:

$sdk\build-tools

Install Build Tools through Android Studio's SDK Manager.
Required tools: aapt.exe, apksigner.bat and zipalign.exe.
"@
}

Pass "Build Tools found: $($buildTools.Name)"

$aapt = Join-Path $buildTools.FullName "aapt.exe"
$apksigner = Join-Path $buildTools.FullName "apksigner.bat"
$zipalign = Join-Path $buildTools.FullName "zipalign.exe"

Write-Host ""
Write-Host "=== [1/4] Clean build, tests and lint ===" -ForegroundColor Cyan

# Y2_BUILD_ID is exported by tools/build-firmware.ps1 so the APK and
# build-manifest.txt carry one identity. A standalone run of this script
# has no such value and lets the Gradle script derive its own.
$buildIdArguments = @()
if ($env:Y2_BUILD_ID) { $buildIdArguments = @("-PbuildId=$env:Y2_BUILD_ID") }

& .\gradlew.bat --no-daemon clean testDebugUnitTest lintDebug @buildIdArguments

if ($LASTEXITCODE -ne 0) {
    Fail "Tests or lint failed."
}

Pass "tests and lint completed"

Write-Host ""
Write-Host "=== [2/4] Assembling release ===" -ForegroundColor Cyan

& .\gradlew.bat --no-daemon assembleRelease @buildIdArguments

if ($LASTEXITCODE -ne 0) {
    Fail "Release build failed."
}

$apk = Join-Path `
    $root `
    "app\build\outputs\apk\release\app-release.apk"

$unsignedApk = Join-Path `
    $root `
    "app\build\outputs\apk\release\app-release-unsigned.apk"

if (-not (Test-Path $apk)) {
    if (Test-Path $unsignedApk) {
        Fail @"
The release APK is unsigned.

Verify that app/build.gradle.kts loads keystore.properties and assigns the
release signingConfig.
"@
    }

    Fail "Release APK not found at: $apk"
}

Pass "signed release APK assembled"

Write-Host ""
Write-Host "=== [3/4] Verifying the APK ===" -ForegroundColor Cyan

$badgingOutput = & $aapt dump badging $apk 2>&1

if ($LASTEXITCODE -ne 0) {
    Fail "aapt could not inspect the APK:`n$($badgingOutput -join "`n")"
}

$badging = $badgingOutput -join "`n"

$checks = @(
    @{
        Name = "package name"
        Pattern = "package: name='$([regex]::Escape($expectedPackage))'"
    },
    @{
        Name = "versionCode"
        Pattern = "versionCode='$([regex]::Escape($expectedVersionCode))'"
    },
    @{
        Name = "versionName"
        Pattern = "versionName='$([regex]::Escape($expectedVersionName))'"
    },
    @{
        Name = "minSdkVersion"
        Pattern = "sdkVersion:'$([regex]::Escape($expectedMinSdk))'"
    },
    @{
        Name = "targetSdkVersion"
        Pattern = "targetSdkVersion:'$([regex]::Escape($expectedTargetSdk))'"
    }
)

foreach ($check in $checks) {
    if ($badging -match $check.Pattern) {
        Pass $check.Name
    }
    else {
        Write-Host ""
        Write-Host "Expected:" -ForegroundColor Yellow
        Write-Host "  Package:     $expectedPackage"
        Write-Host "  Version:     $expectedVersionName"
        Write-Host "  Version code: $expectedVersionCode"
        Write-Host "  minSdk:      $expectedMinSdk"
        Write-Host "  targetSdk:   $expectedTargetSdk"

        Write-Host ""
        Write-Host "APK badging output:" -ForegroundColor Yellow
        Write-Host $badging

        Fail "$($check.Name) mismatch."
    }
}

if ($badging -match "provides-component:'launcher'") {
    Pass "launcher component"
}
else {
    Fail "APK is not recognized as a launcher."
}

if ($badging -match "launchable-activity:\s+name='([^']+)'") {
    $launchableActivity = $Matches[1]
    Pass "launchable activity: $launchableActivity"
}
else {
    Fail "No launchable activity found."
}

# Inspect the compiled manifest directly because `aapt dump badging`
# does not reliably print HOME and DEFAULT intent categories.
$manifestTreeOutput = & $aapt dump xmltree $apk AndroidManifest.xml 2>&1

if ($LASTEXITCODE -ne 0) {
    Fail "aapt could not inspect AndroidManifest.xml:`n$($manifestTreeOutput -join "`n")"
}

$manifestTree = $manifestTreeOutput -join "`n"

if ($manifestTree -match "android\.intent\.category\.HOME") {
    Pass "HOME category"
}
else {
    Fail "HOME category missing from compiled manifest."
}

if ($manifestTree -match "android\.intent\.category\.DEFAULT") {
    Pass "DEFAULT category"
}
else {
    Fail "DEFAULT category missing from compiled manifest."
}

if ($manifestTree -match "android\.intent\.action\.MAIN") {
    Pass "MAIN action"
}
else {
    Fail "MAIN action missing from compiled manifest."
}

$debugPackagePattern = [regex]::Escape("$expectedPackage.debug")

if ($badging -match "package: name='$debugPackagePattern'") {
    Fail "Debug applicationId is present in the release APK."
}

Pass "no debug applicationId"

$nativeEntries = & $aapt list $apk |
    Select-String -Pattern "^lib/"

if ($nativeEntries) {
    Write-Host ""
    Write-Host "  note Native libraries are present:" -ForegroundColor Yellow

    foreach ($entry in $nativeEntries) {
        Write-Host "       $entry" -ForegroundColor Yellow
    }
}
else {
    Pass "no native libraries"
}

& $zipalign -c -v 4 $apk *> $null

if ($LASTEXITCODE -ne 0) {
    Fail "APK is not 4-byte aligned."
}

Pass "zipalign 4-byte aligned"

# ---------------------------------------------------------------------------
# Verify APK signature
# ---------------------------------------------------------------------------

$previousErrorActionPreference = $ErrorActionPreference
$ErrorActionPreference = "Continue"

try {
    $verifyOutput = @(
        & $apksigner `
            verify `
            --min-sdk-version 19 `
            --verbose `
            $apk 2>&1
    )

    $verifyExitCode = $LASTEXITCODE
}
finally {
    $ErrorActionPreference = $previousErrorActionPreference
}

$verifyText = (
    $verifyOutput |
    ForEach-Object { $_.ToString() }
) -join "`n"

if ($verifyExitCode -ne 0) {
    Fail "Signature verification failed:`n$verifyText"
}

if ($verifyText -notmatch "Verified using v1 scheme \(JAR signing\): true") {
    Fail @"
No valid v1 JAR signature found.

Android 4.4 requires v1 signing.

Output:

$verifyText
"@
}

Pass "v1 JAR signature valid"

# ---------------------------------------------------------------------------
# Read signing certificate
# ---------------------------------------------------------------------------

$previousErrorActionPreference = $ErrorActionPreference
$ErrorActionPreference = "Continue"

try {
    $certificateOutput = @(
        & $apksigner `
            verify `
            --print-certs `
            $apk 2>&1
    )

    $certificateExitCode = $LASTEXITCODE
}
finally {
    $ErrorActionPreference = $previousErrorActionPreference
}

$certificateText = (
    $certificateOutput |
    ForEach-Object { $_.ToString() } |
    Where-Object {
        $_ -notmatch "^WARNING: A restricted method"
    }
) -join "`n"

if ($certificateExitCode -ne 0) {
    Fail "Could not read signing certificate:`n$certificateText"
}

Write-Host ""
Write-Host "=== [4/4] Build report ===" -ForegroundColor Cyan

$apkFile = Get-Item $apk
$size = $apkFile.Length

$sha256 = (
    Get-FileHash -Algorithm SHA256 $apk
).Hash.ToLowerInvariant()

$outDir = Join-Path $root "dist\firmware"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$releaseApk = Join-Path $outDir "Y2Player.apk"
$reportFile = Join-Path $outDir "apk-build-report.txt"

Copy-Item $apk $releaseApk -Force

$report = @"
Y2Player release APK build report
=================================

Built            : $(Get-Date -Format "yyyy-MM-dd HH:mm:ss")
Package          : $expectedPackage
Version          : $expectedVersionName
Version code     : $expectedVersionCode
Version source   : $($appVersion.GradleFile)
Minimum SDK      : $expectedMinSdk
Target SDK       : $expectedTargetSdk
Build Tools      : $($buildTools.Name)
Android SDK      : $sdk
Launch activity  : $launchableActivity
ABI              : none expected
Size             : $size bytes
SHA-256          : $sha256
Alignment        : 4-byte verified
Signature        : v1 JAR signature verified for minSdk 19
Launcher intent  : MAIN + HOME + DEFAULT present
Debug package    : absent

Signing certificate
-------------------

$certificateText
"@

$report | Out-File `
    -FilePath $reportFile `
    -Encoding utf8

Write-Host $report

Write-Host ""
Write-Host "Release APK staged successfully:" -ForegroundColor Green
Write-Host "  $releaseApk" -ForegroundColor Green

Write-Host ""
Write-Host "Build report:" -ForegroundColor Green
Write-Host "  $reportFile" -ForegroundColor Green

Write-Host ""
Write-Host "Next step:" -ForegroundColor Cyan
Write-Host "  Open WSL and run:" -ForegroundColor Cyan
Write-Host "  bash tools/firmware/build_packages.sh" -ForegroundColor Cyan
