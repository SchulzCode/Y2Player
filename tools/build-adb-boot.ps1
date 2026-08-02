<#
.SYNOPSIS
    Builds a Y2 boot.img with secure ADB and USB mass storage enabled together.

.DESCRIPTION
    This is intentionally separate from the system-image firmware pipeline. It
    builds a pinned Android 4.4.2 ARM adbd, inserts it into a copy of the stock
    MediaTek ramdisk, selects mass_storage,adb, and validates the resulting
    legacy Android boot image. It builds files only and never flashes a device.
#>
[CmdletBinding()]
param(
    [string]$OutputDirectory = "out\boot-adb"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
. (Join-Path $root "tools\pipeline\common.ps1")

$outputPath = if ([System.IO.Path]::IsPathRooted($OutputDirectory)) {
    [System.IO.Path]::GetFullPath($OutputDirectory)
} else {
    [System.IO.Path]::GetFullPath((Join-Path $root $OutputDirectory))
}
$outRoot = [System.IO.Path]::GetFullPath((Join-Path $root "out"))
if (-not $outputPath.StartsWith($outRoot + [IO.Path]::DirectorySeparatorChar,
        [StringComparison]::OrdinalIgnoreCase)) {
    throw "OutputDirectory must be a child of the repository out directory."
}

$stockBoot = Join-Path $root "OriginalFirmware\boot.img"
$adbd = Join-Path $root "build\adb\output\adbd"
$lockPath = Join-Path $root "build\adb-boot-build.lock"
$script:BuildLock = $null
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $lockPath) | Out-Null
try {
    $script:BuildLock = [System.IO.File]::Open(
        $lockPath, [System.IO.FileMode]::OpenOrCreate,
        [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::None
    )
} catch {
    throw "Another ADB boot-image build is already running."
}

try {
    Write-Stage "Y2 secure ADB boot-image pipeline"
    Write-Detail "input      : $stockBoot"
    Write-Detail "output     : $outputPath"
    Write-Detail "USB mode   : mass_storage,adb"
    Write-Detail "ADB mode   : RSA-authorized, non-root shell"

    Write-Stage "1. Validating environment"
    if (-not (Test-Path -LiteralPath $stockBoot -PathType Leaf)) {
        throw "Canonical OriginalFirmware\boot.img is missing."
    }
    if (-not (Get-Command wsl.exe -ErrorAction SilentlyContinue)) {
        throw "WSL was not found."
    }
    $python = Get-Command python -ErrorAction SilentlyContinue
    if (-not $python) { throw "Python 3 was not found." }
    $wslRoot = ConvertTo-WslPath -WindowsPath $root
    $probe = Invoke-Native -FilePath "wsl.exe" -Arguments @("-e", "uname", "-s") `
        -Stage "WSL probe" -Capture -AllowFailure
    if ($probe.ExitCode -ne 0) {
        throw "WSL is installed but no Linux distribution responded."
    }

    Write-Stage "2. Verifying pinned official AOSP sources"
    Invoke-Native -FilePath "wsl.exe" -Arguments @(
        "--cd", $wslRoot, "bash", "tools/adb/fetch-aosp-sources.sh", "."
    ) -Stage "AOSP source verification" -NormalizeLineEndings | Out-Null

    Write-Stage "3. Building static Android 4.4.2 ARM adbd"
    Invoke-Native -FilePath "wsl.exe" -Arguments @(
        "--cd", $wslRoot, "bash", "tools/adb/build-adbd.sh", "."
    ) -Stage "adbd build" -NormalizeLineEndings | Out-Null
    if (-not (Test-Path -LiteralPath $adbd -PathType Leaf)) {
        throw "adbd build completed without producing $adbd"
    }

    Write-Stage "4. Repacking and verifying boot.img"
    New-Item -ItemType Directory -Force -Path $outputPath | Out-Null
    foreach ($name in @(
        "boot.img", "boot-stock.img", "ramdisk-adb.img",
        "verification-report.txt", "build-manifest.txt", "checksums.txt"
    )) {
        $path = Join-Path $outputPath $name
        if (Test-Path -LiteralPath $path) {
            Remove-Item -LiteralPath $path -Force
        }
    }
    Invoke-Native -FilePath $python.Source -Arguments @(
        (Join-Path $root "tools\adb\build_adb_boot.py"),
        "--stock-boot", $stockBoot,
        "--adbd", $adbd,
        "--output-dir", $outputPath,
        "--partition-size", "0x1000000"
    ) -Stage "boot image build" -NormalizeLineEndings | Out-Null

    $requiredOutputs = @(
        "boot.img", "boot-stock.img", "ramdisk-adb.img", "verification-report.txt"
    )
    foreach ($name in $requiredOutputs) {
        $path = Join-Path $outputPath $name
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            throw "Expected output is missing: $name"
        }
    }

    Write-Stage "5. Writing provenance and checksums"
    $build = Get-BuildId -Root $root
    $bootHash = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $outputPath "boot.img")).Hash.ToLower()
    $stockHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $stockBoot).Hash.ToLower()
    $adbdHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $adbd).Hash.ToLower()
    $manifest = @(
        "Y2Player secure ADB boot-image build manifest",
        "============================================",
        "",
        "Build ID             : $($build.BuildId)",
        "Repository commit    : $($build.Commit)$(if ($build.Dirty) { ' (dirty)' } else { '' })",
        "Stock boot SHA-256   : $stockHash",
        "Output boot SHA-256  : $bootHash",
        "adbd SHA-256         : $adbdHash",
        "AOSP system/core     : https://android.googlesource.com/platform/system/core.git",
        "AOSP system/core ref : android-4.4.2_r1 @ e65b7ea8801145626504c724c28aedd0e5038a28",
        "AOSP libhardware     : https://android.googlesource.com/platform/hardware/libhardware.git",
        "AOSP libhardware ref : android-4.4.2_r1 @ 7ccf148f5066ceb1a161f0d7a7d66f75c6e8d420",
        "Target               : ARMv7, Android API 19, static executable",
        "USB composition      : mass_storage,adb",
        "FM audio path        : af.fm.force_direct_mode_type=2 (FM_FORCE_INDIRECT_MODE)",
        "ADB authentication   : RSA authorization required",
        "ADB privilege        : non-root shell",
        "BOOTIMG limit        : 16777216 bytes",
        "Flash target         : BOOTIMG partition only",
        ""
    )
    $manifestPath = Join-Path $outputPath "build-manifest.txt"
    [IO.File]::WriteAllText($manifestPath, ($manifest -join "`n"), [Text.UTF8Encoding]::new($false))

    $checksumPaths = @(
        (Join-Path $outputPath "boot.img"),
        (Join-Path $outputPath "boot-stock.img"),
        (Join-Path $outputPath "ramdisk-adb.img"),
        (Join-Path $outputPath "verification-report.txt"),
        $manifestPath,
        $adbd
    )
    $checksumLines = foreach ($path in $checksumPaths) {
        $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $path).Hash.ToLower()
        "$hash  $([IO.Path]::GetFileName($path))"
    }
    [IO.File]::WriteAllText(
        (Join-Path $outputPath "checksums.txt"),
        (($checksumLines -join "`n") + "`n"),
        [Text.ASCIIEncoding]::new()
    )

    Write-Stage "Build complete"
    foreach ($name in @($requiredOutputs + @("build-manifest.txt", "checksums.txt"))) {
        $path = Join-Path $outputPath $name
        Write-Detail ("{0,-25} {1,12:N0} bytes" -f $name, (Get-Item -LiteralPath $path).Length)
    }
    Write-Host "`nADB boot image ready: $(Join-Path $outputPath 'boot.img')" -ForegroundColor Green
    Write-Host "Flash this file to BOOTIMG only. Nothing was flashed by this pipeline." -ForegroundColor Cyan
}
finally {
    if ($script:BuildLock) {
        $script:BuildLock.Dispose()
        $script:BuildLock = $null
    }
}
