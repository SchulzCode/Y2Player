# Builds Y2Player's single Android API-19 ARMv7 native runtime through WSL.
# Generated downloads, object files and liby2audio.so remain ignored build
# artifacts. The verified source identities live under third_party/.

[CmdletBinding()]
param(
    [switch]$Offline
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$downloads = Join-Path $root "build\downloads"
$ffmpegArchive = Join-Path $downloads "ffmpeg-8.1.2.tar.xz"
$ndkArchive = Join-Path $downloads "android-ndk-r25c-linux.zip"

$ffmpegUrl = "https://ffmpeg.org/releases/ffmpeg-8.1.2.tar.xz"
$ffmpegSha256 = "464beb5e7bf0c311e68b45ae2f04e9cc2af88851abb4082231742a74d97b524c"
$ndkUrl = "https://dl.google.com/android/repository/android-ndk-r25c-linux.zip"
$ndkSha256 = "769ee342ea75f80619d985c2da990c48b3d8eaf45f48783a2d48870d04b46108"

function Get-FileSha256 {
    param([Parameter(Mandatory = $true)][string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-VerifiedArchive {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Url,
        [Parameter(Mandatory = $true)][string]$Sha256,
        [string]$LocalCandidate
    )

    if ((Test-Path -LiteralPath $Path) -and (Get-FileSha256 $Path) -eq $Sha256) {
        return
    }

    if ($LocalCandidate -and (Test-Path -LiteralPath $LocalCandidate)) {
        if ((Get-FileSha256 $LocalCandidate) -eq $Sha256) {
            Copy-Item -LiteralPath $LocalCandidate -Destination $Path -Force
            return
        }
    }

    if ($Offline) {
        throw "Verified archive is unavailable in offline mode: $Path"
    }

    $temporaryPath = "$Path.download"
    & curl.exe -L --fail --retry 3 --retry-delay 2 --output $temporaryPath $Url
    if ($LASTEXITCODE -ne 0) {
        throw "Download failed: $Url"
    }

    if ((Get-FileSha256 $temporaryPath) -ne $Sha256) {
        Remove-Item -LiteralPath $temporaryPath -Force
        throw "SHA-256 mismatch for $Url"
    }

    Move-Item -LiteralPath $temporaryPath -Destination $Path -Force
}

New-Item -ItemType Directory -Force -Path $downloads | Out-Null

Get-VerifiedArchive `
    -Path $ffmpegArchive `
    -Url $ffmpegUrl `
    -Sha256 $ffmpegSha256 `
    -LocalCandidate (Join-Path $root "ffmpeg-8.1.2.tar.xz")

Get-VerifiedArchive `
    -Path $ndkArchive `
    -Url $ndkUrl `
    -Sha256 $ndkSha256

$wsl = Get-Command wsl.exe -ErrorAction SilentlyContinue
if (-not $wsl) {
    throw "WSL is required to build the Linux-hosted NDK r25c toolchain."
}

$portableRoot = $root.Replace("\", "/")
$translatedRoot = & wsl.exe -- wslpath -a $portableRoot
$rootForWsl = if ($translatedRoot) { $translatedRoot.Trim() } else { $null }
if ($LASTEXITCODE -ne 0 -or -not $rootForWsl) {
    throw "Could not translate the repository path for WSL: $root"
}

$buildScript = "$rootForWsl/tools/native/build-ffmpeg.sh"
& wsl.exe -- bash $buildScript $rootForWsl
if ($LASTEXITCODE -ne 0) {
    throw "Native audio build failed."
}

Write-Host "Native audio runtime built and verified:" -ForegroundColor Green
Write-Host "  app\src\main\jniLibs\armeabi-v7a\liby2audio.so"
Write-Host "  build\native\native-build-report.txt"
