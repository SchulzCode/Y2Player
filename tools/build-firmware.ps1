<#
.SYNOPSIS
    Builds Y2Player.apk and a system-only Y2Player firmware image.

.DESCRIPTION
    Builds and verifies the Android application, integrates it into a clean
    copy of the canonical stock system.img, and produces no boot image. Image
    work runs under WSL because offline ext4 editing needs e2fsprogs.

    Run from the repository root as .\tools\build-firmware.ps1. This script builds
    files only. It never flashes, pushes, or reboots a device.
#>
[CmdletBinding()]
param(
    [switch]$Clean,
    [switch]$ValidateOnly,
    [switch]$SkipTests,
    [switch]$DebugApk,
    [string]$OutputDirectory = "out\firmware"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$root = $PSScriptRoot
while ($root -and -not (Test-Path (Join-Path $root "gradlew.bat"))) {
    $parent = Split-Path -Parent $root
    if ($parent -eq $root -or [string]::IsNullOrEmpty($parent)) { break }
    $root = $parent
}
if (-not (Test-Path (Join-Path $root "gradlew.bat"))) {
    throw "Could not locate the repository root (gradlew.bat not found)."
}
Set-Location $root
. (Join-Path $root "tools\pipeline\common.ps1")

$workDirectory = Join-Path $root "build\work"
$outputPath = if ([System.IO.Path]::IsPathRooted($OutputDirectory)) {
    $OutputDirectory
} else { Join-Path $root $OutputDirectory }
$lockPath = Join-Path $root "build\firmware-build.lock"
$script:BuildLock = $null
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $lockPath) | Out-Null
try {
    $script:BuildLock = [System.IO.File]::Open(
        $lockPath, [System.IO.FileMode]::OpenOrCreate,
        [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::None
    )
} catch {
    throw "Another firmware validation/build is already running."
}

if ($Clean) {
    Write-Stage "Clean"
    foreach ($path in @($workDirectory, $outputPath)) {
        if (Test-Path -LiteralPath $path) {
            $resolved = (Resolve-Path -LiteralPath $path).Path
            $buildRoot = (Resolve-Path -LiteralPath (Join-Path $root "build")).Path
            $outRoot = $null
            $resolvedOut = Resolve-Path -LiteralPath (Join-Path $root "out") -ErrorAction SilentlyContinue
            if ($resolvedOut) { $outRoot = $resolvedOut.Path }
            $allowed = $resolved.StartsWith($buildRoot + [IO.Path]::DirectorySeparatorChar,
                [StringComparison]::OrdinalIgnoreCase) -or
                ($outRoot -and $resolved.StartsWith($outRoot + [IO.Path]::DirectorySeparatorChar,
                    [StringComparison]::OrdinalIgnoreCase))
            if (-not $allowed) { throw "Refusing to clean outside generated build/out roots: $resolved" }
            Remove-Item -LiteralPath $resolved -Recurse -Force
            Write-Detail "removed $resolved"
        }
    }
    Write-Detail "kept canonical OriginalFirmware images and signing material"
    exit 0
}

# Delete exact known final artifacts on the Windows side before WSL opens the
# output directory. Deleting a previous WSL-created sparse file from drvfs is
# not reliable on every Windows/WSL combination. This also guarantees a failed
# build cannot be mistaken for the previous successful run.
if (-not $ValidateOnly) {
    New-Item -ItemType Directory -Force -Path $outputPath | Out-Null
    $knownOutputs = @(
        "Y2Player.apk", "system.img", "boot.img", "boot-stock.img", "y2bridged",
        "build-manifest.txt", "checksums.txt", "verification-report.txt", "build.log"
    )
    foreach ($name in $knownOutputs) {
        $path = Join-Path $outputPath $name
        if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Force }
    }
    Get-ChildItem -LiteralPath $outputPath -File -Filter "bridge*" -ErrorAction SilentlyContinue |
        Remove-Item -Force
}

$logDirectory = if ($ValidateOnly) { Join-Path $workDirectory "validate" } else { $outputPath }
New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null
$script:BuildLogPath = Join-Path $logDirectory "build.log"
Start-BuildLog -Path $script:BuildLogPath
$buildSucceeded = $false

try {
    Write-Stage "Y2Player system-image pipeline"
    Write-Detail "repository : $root"
    Write-Detail "output     : $outputPath"
    Write-Detail "boot image : untouched and not emitted"

    Write-Stage "1. Validating environment"
    if (-not (Get-Command wsl.exe -ErrorAction SilentlyContinue)) { throw "WSL was not found." }
    $probe = Invoke-Native -FilePath "wsl.exe" -Arguments @("-e", "uname", "-s") `
        -Stage "WSL probe" -Capture -AllowFailure
    if ($probe.ExitCode -ne 0) { throw "WSL is installed but no Linux distribution responded." }
    if (-not (Test-Path (Join-Path $root "OriginalFirmware\system.img"))) {
        throw "Canonical OriginalFirmware\system.img is missing."
    }
    if (-not (Test-Path (Join-Path $root "gradlew.bat"))) { throw "gradlew.bat is missing." }
    $java = Invoke-Native -FilePath "java" -Arguments @("-version") `
        -Stage "java -version" -Capture -AllowFailure
    Write-Detail "java: $(if ($java.ExitCode -eq 0) { $java.Output[0] } else { 'Gradle-managed JDK will be used' })"
    Write-Detail "system.img: $((Get-Item (Join-Path $root 'OriginalFirmware\system.img')).Length) bytes"

    $build = Get-BuildId -Root $root
    $env:Y2_BUILD_ID = $build.BuildId
    $env:Y2_GIT_COMMIT = $build.Commit
    Write-Detail "build id: $($build.BuildId)"

    $wslRoot = ConvertTo-WslPath -WindowsPath $root
    if ($ValidateOnly) {
        Write-Stage "2. Application inputs"
        Write-Detail "Gradle wrapper and Android source present; compilation skipped"
        $args = @("bash", "tools/firmware/build_firmware.sh", "--validate-only",
            "--out", (ConvertTo-WslPath -WindowsPath $outputPath),
            "--work", (ConvertTo-WslPath -WindowsPath $workDirectory))
        Invoke-Native -FilePath "wsl.exe" -Arguments (@("--cd", $wslRoot) + $args) `
            -Stage "System-image validation (WSL)" -NormalizeLineEndings | Out-Null
        Write-Stage "Validation complete"
        Write-Host "No artifacts were produced." -ForegroundColor Green
        exit 0
    }

    $apkPath = $null
    $appBuildCommand = $null
    $testsStatus = "passed"
    if ($DebugApk) {
        Write-Stage "2. Building debug APK"
        $gradleArgs = if ($SkipTests) {
            $testsStatus = "skipped by -SkipTests (development build)"
            @("assembleDebug", "-PbuildId=$($build.BuildId)")
        } else {
            @("testDebugUnitTest", "lintDebug", "assembleDebug", "-PbuildId=$($build.BuildId)")
        }
        $appBuildCommand = ".\gradlew.bat $($gradleArgs[0..($gradleArgs.Count - 2)] -join ' ')"
        Invoke-Native -FilePath (Join-Path $root "gradlew.bat") -Arguments $gradleArgs `
            -Stage "Gradle debug build" | Out-Null
        $apkPath = Find-DebugApk -Root $root
    } else {
        Write-Stage "2. Building signed release APK"
        if (-not (Test-Path (Join-Path $root "keystore.properties"))) {
            throw "keystore.properties is required for a flashable release APK. Use -DebugApk only for development."
        }
        if ($SkipTests) { Write-Warning "-SkipTests is ignored for release builds." }
        $appBuildCommand = ".\gradlew.bat clean testDebugUnitTest lintDebug assembleRelease"
        Invoke-Native -FilePath "powershell.exe" -Arguments @(
            "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
            (Join-Path $root "tools\build-release-apk.ps1")
        ) -Stage "Release APK build" | Out-Null
        $apkPath = Join-Path $root "dist\firmware\Y2Player.apk"
    }
    if (-not $apkPath -or -not (Test-Path $apkPath)) { throw "APK build succeeded but no APK was found." }

    $metadataPath = Join-Path $workDirectory "apk-metadata.txt"
    Write-ApkMetadata -ApkPath $apkPath -Root $root -Destination $metadataPath

    Write-Stage "3. Integrating and verifying system.img"
    $args = @(
        "bash", "tools/firmware/build_firmware.sh",
        "--apk", (ConvertTo-WslPath -WindowsPath $apkPath),
        "--apk-metadata", (ConvertTo-WslPath -WindowsPath $metadataPath),
        "--out", (ConvertTo-WslPath -WindowsPath $outputPath),
        "--work", (ConvertTo-WslPath -WindowsPath $workDirectory),
        "--app-build-command", $appBuildCommand,
        "--app-tests-status", $testsStatus,
        "--build-id", $build.BuildId
    )
    Invoke-Native -FilePath "wsl.exe" -Arguments (@("--cd", $wslRoot) + $args) `
        -Stage "System image build (WSL)" -NormalizeLineEndings | Out-Null

    Write-Stage "4. Confirming output artifacts"
    foreach ($name in @("Y2Player.apk", "system.img", "build-manifest.txt", "checksums.txt", "verification-report.txt", "build.log")) {
        $path = Join-Path $outputPath $name
        if (-not (Test-Path $path)) { throw "Expected output is missing: $name" }
        Write-Detail ("{0,-25} {1,12:N0} bytes" -f $name, (Get-Item $path).Length)
    }
    $forbidden = Get-ChildItem -LiteralPath $outputPath -File | Where-Object {
        $_.Name -in @("boot.img", "boot-stock.img", "y2bridged") -or $_.Name -like "bridge*"
    }
    if ($forbidden) { throw "Forbidden stale artifacts remain: $($forbidden.Name -join ', ')" }

    Write-Host "`nFirmware build complete: $outputPath" -ForegroundColor Green
    Write-Host "Only system.img should be selected in SP Flash Tool. Nothing was flashed." -ForegroundColor Cyan
    $buildSucceeded = $true
} catch {
    Write-Host "`nBUILD FAILED" -ForegroundColor Red
    Write-Host "  stage : $script:CurrentStage" -ForegroundColor Red
    Write-Host "  error : $($_.Exception.Message)" -ForegroundColor Red
    if (-not $ValidateOnly -and (Test-Path -LiteralPath $outputPath)) {
        foreach ($name in @(
            "Y2Player.apk", "system.img", "boot.img", "boot-stock.img", "y2bridged",
            "build-manifest.txt", "checksums.txt", "verification-report.txt"
        )) {
            $path = Join-Path $outputPath $name
            if (Test-Path -LiteralPath $path) {
                Remove-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue
            }
        }
    }
    exit 1
} finally {
    Stop-BuildLog
    if ($buildSucceeded -and -not $ValidateOnly) {
        $checksumLines = foreach ($name in @(
            "Y2Player.apk", "system.img", "build-manifest.txt",
            "verification-report.txt", "build.log"
        )) {
            $item = Join-Path $outputPath $name
            if (-not (Test-Path -LiteralPath $item)) { throw "Cannot checksum missing artifact: $name" }
            $hash = (Get-FileHash -LiteralPath $item -Algorithm SHA256).Hash.ToLowerInvariant()
            "$hash  $name"
        }
        [System.IO.File]::WriteAllLines(
            (Join-Path $outputPath "checksums.txt"),
            [string[]]$checksumLines,
            (New-Object System.Text.UTF8Encoding($false))
        )
    }
    if ($script:BuildLock) {
        $script:BuildLock.Dispose()
        Remove-Item -LiteralPath $lockPath -Force -ErrorAction SilentlyContinue
    }
}
