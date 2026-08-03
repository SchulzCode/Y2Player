<#
.SYNOPSIS
    Builds a complete Innioasis Updater-compatible Y2 ROM archive.

.DESCRIPTION
    This is the one-command release entry point for a full Y2Player ROM. It:

      1. calls build-firmware.ps1 to create a fresh signed APK and system.img;
      2. combines that system image with the matching stock Y2 partitions;
      3. desparses system, cache, and userdata as required by the updater format;
      4. adds a pinned portable SP Flash Tool distribution;
      5. creates and reopens rom_y2.zip using Deflate level 9; and
      6. writes checksums, provenance, and an independent verification report.

    The script only creates files. It never flashes, pushes, uploads, or reboots
    a device. The resulting full-ROM archive is intended for the Innioasis
    Updater and performs a full-device installation, unlike system.img alone.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\tools\build-updater-rom.ps1

.EXAMPLE
    .\tools\build-updater-rom.ps1 -ValidateOnly

.EXAMPLE
    .\tools\build-updater-rom.ps1 -TemplateZip D:\Downloads\rom_y2.zip
#>
[CmdletBinding()]
param(
    [switch]$Clean,
    [switch]$ValidateOnly,
    [switch]$RefreshTemplate,
    [switch]$KeepStaging,
    [string]$TemplateZip,
    [string]$OutputDirectory = "out\updater",
    [string]$FirmwareOutputDirectory = "out\firmware"
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

$templateUri = "https://github.com/y1-community/rockbox-y2-rom/releases/download/0.5/rom_y2.zip"
$templateSha256 = "092a5f39e2db5fb8be7b8c442cd713e0db567fd69f589aace2243ab8e7eedeb7"
$templateLength = 433341038L
$templateCache = Join-Path $root "build\downloads\innioasis-updater\rockbox-y2-0.5-rom_y2.zip"
$stockDirectory = Join-Path $root "OriginalFirmware"
$scatterName = "MT6582_Android_scatter.txt"
$scatterPath = Join-Path $stockDirectory $scatterName
$stagingDirectory = Join-Path $root "build\work\updater-rom"
$zipHelperPath = Join-Path $root "build\work\updater-rom-zip.py"

$outputPath = if ([System.IO.Path]::IsPathRooted($OutputDirectory)) {
    [System.IO.Path]::GetFullPath($OutputDirectory)
} else {
    [System.IO.Path]::GetFullPath((Join-Path $root $OutputDirectory))
}
$firmwareOutputPath = if ([System.IO.Path]::IsPathRooted($FirmwareOutputDirectory)) {
    [System.IO.Path]::GetFullPath($FirmwareOutputDirectory)
} else {
    [System.IO.Path]::GetFullPath((Join-Path $root $FirmwareOutputDirectory))
}
$archivePath = Join-Path $outputPath "rom_y2.zip"

$partitionFiles = @(
    [pscustomobject]@{ Partition = "PRELOADER"; File = "preloader_eastaeon82_wet_kk.bin"; FileSystem = $false },
    [pscustomobject]@{ Partition = "MBR";       File = "MBR";                              FileSystem = $false },
    [pscustomobject]@{ Partition = "EBR1";      File = "EBR1";                             FileSystem = $false },
    [pscustomobject]@{ Partition = "UBOOT";     File = "lk.bin";                           FileSystem = $false },
    [pscustomobject]@{ Partition = "BOOTIMG";   File = "boot.img";                         FileSystem = $false },
    [pscustomobject]@{ Partition = "RECOVERY";  File = "recovery.img";                     FileSystem = $false },
    [pscustomobject]@{ Partition = "SEC_RO";    File = "secro.img";                        FileSystem = $false },
    [pscustomobject]@{ Partition = "LOGO";      File = "logo.bin";                         FileSystem = $false },
    [pscustomobject]@{ Partition = "EBR2";      File = "EBR2";                             FileSystem = $false },
    [pscustomobject]@{ Partition = "ANDROID";   File = "system.img";                       FileSystem = $true  },
    [pscustomobject]@{ Partition = "CACHE";     File = "cache.img";                        FileSystem = $true  },
    [pscustomobject]@{ Partition = "USRDATA";   File = "userdata.img";                     FileSystem = $true  }
)

# Only these support files are imported from the pinned template. Its firmware
# images are deliberately never trusted or copied into a Y2Player release.
$flashToolFiles = @(
    "BromAdapterTool.ini",
    "console_mode.xsd",
    "console_readback.xml",
    "CustPT.ini",
    "DA_PL.bin",
    "DA_PL_CRYPTO20.bin",
    "DA_SWSEC.bin",
    "DA_SWSEC_CRYPTO20.bin",
    "dl_without_scatter.xml",
    "download_scene.ini",
    "factory.ini",
    "flash_tool.exe",
    "flashtool.qch",
    "flashtool.qhc",
    "FlashToolLib.dll",
    "FlashToolLib.v1.dll",
    "FlashtoollibEx.dll",
    "hwparam.json",
    "key.ini",
    "msvcp90.dll",
    "msvcr90.dll",
    "MTK_AllInOne_DA.bin",
    "option.ini",
    "phonon4.dll",
    "platform.xml",
    "QtCLucene4.dll",
    "QtCore4.dll",
    "QtGui4.dll",
    "QtHelp4.dll",
    "QtNetwork4.dll",
    "QtSql4.dll",
    "QtWebKit4.dll",
    "QtXml4.dll",
    "QtXmlPatterns4.dll",
    "rb_without_scatter.xml",
    "readback.log",
    "readback.xml",
    "readback_ui_bak.xsd",
    "registry.ini",
    "SLA_Challenge.dll",
    "sp_readback.xml",
    "storage_setting.xml",
    "usb_setting.xml"
)

function Write-Utf8NoBomLines {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][AllowEmptyString()][string[]]$Lines
    )
    [System.IO.File]::WriteAllLines(
        $Path,
        $Lines,
        (New-Object System.Text.UTF8Encoding($false))
    )
}

function Assert-GeneratedDirectory {
    param([Parameter(Mandatory = $true)][string]$Path)

    $full = [System.IO.Path]::GetFullPath($Path).TrimEnd('\', '/')
    $allowedRoots = @(
        [System.IO.Path]::GetFullPath((Join-Path $root "build")).TrimEnd('\', '/'),
        [System.IO.Path]::GetFullPath((Join-Path $root "out")).TrimEnd('\', '/')
    )
    foreach ($allowedRoot in $allowedRoots) {
        if ($full.StartsWith(
            $allowedRoot + [System.IO.Path]::DirectorySeparatorChar,
            [System.StringComparison]::OrdinalIgnoreCase
        )) { return }
    }
    throw "Refusing to clean a path outside the generated build/out trees: $full"
}

function Remove-GeneratedDirectory {
    param([Parameter(Mandatory = $true)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { return }
    Assert-GeneratedDirectory -Path $Path
    Remove-Item -LiteralPath $Path -Recurse -Force
}

function Get-Sha256 {
    param([Parameter(Mandatory = $true)][string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-ImageInformation {
    param([Parameter(Mandatory = $true)][string]$Path)

    $stream = [System.IO.File]::OpenRead($Path)
    try {
        if ($stream.Length -lt 4) { throw "Image is too short: $Path" }
        $header = New-Object byte[] 28
        $read = $stream.Read($header, 0, $header.Length)
        $magic = [System.BitConverter]::ToUInt32($header, 0)
        # Windows PowerShell 5.1 parses 0xed26ff3a as a negative Int32 while
        # BitConverter returns UInt32. Use its unsigned decimal value so the
        # comparison works on both Windows PowerShell and PowerShell 7.
        if ($magic -eq 3978755898L) {
            if ($read -lt 28) { throw "Sparse image header is truncated: $Path" }
            $blockSize = [System.BitConverter]::ToUInt32($header, 12)
            $totalBlocks = [System.BitConverter]::ToUInt32($header, 16)
            return [pscustomobject]@{
                IsSparse = $true
                StoredSize = $stream.Length
                ExpandedSize = [int64]$blockSize * [int64]$totalBlocks
            }
        }
        return [pscustomobject]@{
            IsSparse = $false
            StoredSize = $stream.Length
            ExpandedSize = $stream.Length
        }
    }
    finally {
        $stream.Dispose()
    }
}

function Assert-FileMagic {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][byte[]]$Expected,
        [Parameter(Mandatory = $true)][string]$Description
    )
    $stream = [System.IO.File]::OpenRead($Path)
    try {
        $actual = New-Object byte[] $Expected.Length
        if ($stream.Read($actual, 0, $actual.Length) -ne $actual.Length) {
            throw "$Description is truncated: $Path"
        }
        for ($index = 0; $index -lt $Expected.Length; $index++) {
            if ($actual[$index] -ne $Expected[$index]) {
                throw "$Description has an unexpected header: $Path"
            }
        }
    }
    finally {
        $stream.Dispose()
    }
}

function Get-ScatterPartitions {
    param([Parameter(Mandatory = $true)][string]$Path)

    $text = Get-Content -LiteralPath $Path -Raw
    if ($text -notmatch '(?m)^\s*platform:\s*MT6582\s*$') {
        throw "The scatter file is not for platform MT6582: $Path"
    }
    if ($text -notmatch '(?m)^\s*project:\s*eastaeon82_wet_kk\s*$') {
        throw "The scatter file is not for project eastaeon82_wet_kk: $Path"
    }

    $result = @{}
    $blocks = [regex]::Matches(
        $text,
        '(?ms)^- partition_index:\s*.*?(?=^- partition_index:|\z)'
    )
    foreach ($blockMatch in $blocks) {
        $block = $blockMatch.Value
        $nameMatch = [regex]::Match($block, '(?m)^\s*partition_name:\s*(\S+)\s*$')
        $fileMatch = [regex]::Match($block, '(?m)^\s*file_name:\s*(\S+)\s*$')
        $downloadMatch = [regex]::Match($block, '(?m)^\s*is_download:\s*(true|false)\s*$')
        $sizeMatch = [regex]::Match($block, '(?m)^\s*partition_size:\s*(0x[0-9a-fA-F]+)\s*$')
        if (-not ($nameMatch.Success -and $fileMatch.Success -and
            $downloadMatch.Success -and $sizeMatch.Success)) {
            throw "Could not parse a partition block in $Path"
        }
        $name = $nameMatch.Groups[1].Value
        $result[$name] = [pscustomobject]@{
            Name = $name
            File = $fileMatch.Groups[1].Value
            IsDownload = $downloadMatch.Groups[1].Value -eq "true"
            Size = [System.Convert]::ToInt64($sizeMatch.Groups[1].Value.Substring(2), 16)
        }
    }
    return $result
}

function Test-FullRomInputs {
    param([Parameter(Mandatory = $true)][string]$SystemImage)

    if (-not (Test-Path -LiteralPath $scatterPath -PathType Leaf)) {
        throw "Missing stock scatter file: $scatterPath"
    }
    if (-not (Test-Path -LiteralPath $SystemImage -PathType Leaf)) {
        throw "Missing system image: $SystemImage"
    }
    if (-not (Test-Path (Join-Path $root "tools\firmware\sparse.py") -PathType Leaf)) {
        throw "Missing sparse-image helper: tools\firmware\sparse.py"
    }

    $scatter = Get-ScatterPartitions -Path $scatterPath
    $expectedNames = @($partitionFiles | ForEach-Object { $_.Partition })
    $downloadNames = @($scatter.Values | Where-Object { $_.IsDownload } |
        ForEach-Object { $_.Name } | Sort-Object)
    $expectedSorted = @($expectedNames | Sort-Object)
    if (($downloadNames -join "|") -ne ($expectedSorted -join "|")) {
        throw "The scatter file's downloadable partition set does not match the guarded Y2 layout."
    }

    foreach ($definition in $partitionFiles) {
        if (-not $scatter.ContainsKey($definition.Partition)) {
            throw "Scatter partition is missing: $($definition.Partition)"
        }
        $entry = $scatter[$definition.Partition]
        if (-not $entry.IsDownload -or $entry.File -cne $definition.File) {
            throw "Unexpected scatter mapping for $($definition.Partition): $($entry.File)"
        }
        $source = if ($definition.Partition -eq "ANDROID") {
            $SystemImage
        } else {
            Join-Path $stockDirectory $definition.File
        }
        if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
            throw "Required partition image is missing: $source"
        }
        $image = Get-ImageInformation -Path $source
        if ($definition.FileSystem) {
            if ($image.ExpandedSize -gt $entry.Size) {
                throw "$($definition.File) expands beyond its declared partition."
            }
        } elseif ($image.StoredSize -gt $entry.Size) {
            throw "$($definition.File) is larger than its declared partition."
        }
    }

    Assert-FileMagic -Path (Join-Path $stockDirectory "boot.img") `
        -Expected ([System.Text.Encoding]::ASCII.GetBytes("ANDROID!")) -Description "Stock boot.img"
    Assert-FileMagic -Path (Join-Path $stockDirectory "recovery.img") `
        -Expected ([System.Text.Encoding]::ASCII.GetBytes("ANDROID!")) -Description "Stock recovery.img"
    return $scatter
}

function Resolve-TemplateArchive {
    if ($TemplateZip) {
        $candidate = if ([System.IO.Path]::IsPathRooted($TemplateZip)) {
            [System.IO.Path]::GetFullPath($TemplateZip)
        } else {
            [System.IO.Path]::GetFullPath((Join-Path $root $TemplateZip))
        }
        if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) {
            throw "Template ZIP was not found: $candidate"
        }
    } else {
        $candidate = $templateCache
        if ($RefreshTemplate -and (Test-Path -LiteralPath $candidate)) {
            Remove-Item -LiteralPath $candidate -Force
        }
        if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) {
            Write-Stage "Downloading pinned updater template"
            Write-Detail "source: $templateUri"
            Write-Detail "This one-time download is approximately 413 MB."
            New-Item -ItemType Directory -Force -Path (Split-Path -Parent $candidate) | Out-Null
            $partial = "$candidate.partial"
            if (Test-Path -LiteralPath $partial) { Remove-Item -LiteralPath $partial -Force }
            try {
                [System.Net.ServicePointManager]::SecurityProtocol =
                    [System.Net.ServicePointManager]::SecurityProtocol -bor
                    [System.Net.SecurityProtocolType]::Tls12
                $client = New-Object System.Net.WebClient
                try {
                    $client.Headers.Add("User-Agent", "Y2Player-ROM-Builder")
                    $client.DownloadFile($templateUri, $partial)
                }
                finally {
                    $client.Dispose()
                }
                Move-Item -LiteralPath $partial -Destination $candidate -Force
            }
            catch {
                if (Test-Path -LiteralPath $partial) {
                    Remove-Item -LiteralPath $partial -Force -ErrorAction SilentlyContinue
                }
                throw "Could not download the pinned updater template. Supply a local copy with -TemplateZip. $($_.Exception.Message)"
            }
        }
    }

    Write-Stage "Verifying updater template"
    $item = Get-Item -LiteralPath $candidate
    if ($item.Length -ne $templateLength) {
        throw "Updater template size mismatch: expected $templateLength bytes, found $($item.Length)."
    }
    $hash = Get-Sha256 -Path $candidate
    if ($hash -cne $templateSha256) {
        throw "Updater template SHA-256 mismatch. Expected $templateSha256, found $hash."
    }
    Write-Detail "verified SHA-256: $hash"
    return $candidate
}

function Expand-FlashToolFiles {
    param(
        [Parameter(Mandatory = $true)][string]$ArchivePath,
        [Parameter(Mandatory = $true)][string]$Destination
    )

    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::OpenRead($ArchivePath)
    try {
        $entries = @{}
        foreach ($entry in $archive.Entries) {
            if (-not $entries.ContainsKey($entry.FullName)) {
                $entries[$entry.FullName] = $entry
            }
        }
        foreach ($name in $flashToolFiles) {
            if (-not $entries.ContainsKey($name)) {
                throw "Pinned updater template is missing SP Flash Tool file: $name"
            }
            $entry = $entries[$name]
            if ($entry.Length -le 0 -and $name -ne "readback.log") {
                throw "Pinned updater template contains an empty required file: $name"
            }
            $target = Join-Path $Destination $name
            $input = $entry.Open()
            try {
                $output = [System.IO.File]::Create($target)
                try { $input.CopyTo($output) } finally { $output.Dispose() }
            }
            finally {
                $input.Dispose()
            }
        }
    }
    finally {
        $archive.Dispose()
    }
}

function Copy-VerifiedFile {
    param(
        [Parameter(Mandatory = $true)][string]$Source,
        [Parameter(Mandatory = $true)][string]$Destination
    )
    $sourceHash = Get-Sha256 -Path $Source
    Copy-Item -LiteralPath $Source -Destination $Destination -Force
    $destinationHash = Get-Sha256 -Path $Destination
    if ($sourceHash -cne $destinationHash) {
        throw "Copy verification failed for $(Split-Path -Leaf $Source)."
    }
    return $sourceHash
}

function Expand-ImageForPackage {
    param(
        [Parameter(Mandatory = $true)][string]$Source,
        [Parameter(Mandatory = $true)][string]$Destination,
        [Parameter(Mandatory = $true)][int64]$ExpectedSize
    )

    $info = Get-ImageInformation -Path $Source
    if ($info.ExpandedSize -ne $ExpectedSize) {
        throw "Image size mismatch before expansion: $Source"
    }
    if ($info.IsSparse) {
        $wslRoot = ConvertTo-WslPath -WindowsPath $root
        Invoke-Native -FilePath "wsl.exe" -Arguments @(
            "--cd", $wslRoot,
            "python3", "tools/firmware/sparse.py", "unpack",
            (ConvertTo-WslPath -WindowsPath $Source),
            (ConvertTo-WslPath -WindowsPath $Destination)
        ) -Stage "Desparse $(Split-Path -Leaf $Source)" -NormalizeLineEndings | Out-Null
    } else {
        Copy-Item -LiteralPath $Source -Destination $Destination -Force
    }

    $expanded = Get-ImageInformation -Path $Destination
    if ($expanded.IsSparse) { throw "Packaged image is still sparse: $Destination" }
    if ($expanded.StoredSize -ne $ExpectedSize) {
        throw "Expanded image has the wrong size: $Destination"
    }
}

function New-LevelNineZip {
    param(
        [Parameter(Mandatory = $true)][string]$SourceDirectory,
        [Parameter(Mandatory = $true)][string]$Destination
    )

    $python = @'
import os
import sys
import zipfile

source, destination = sys.argv[1:3]
names = sorted(os.listdir(source), key=str.casefold)
if not names:
    raise SystemExit("staging directory is empty")
for name in names:
    path = os.path.join(source, name)
    if not os.path.isfile(path) or "/" in name or "\\" in name:
        raise SystemExit("archive staging must contain flat files only: " + name)

with zipfile.ZipFile(
    destination,
    mode="w",
    compression=zipfile.ZIP_DEFLATED,
    compresslevel=9,
    allowZip64=True,
) as archive:
    for name in names:
        print("adding " + name, flush=True)
        archive.write(
            os.path.join(source, name),
            arcname=name,
            compress_type=zipfile.ZIP_DEFLATED,
            compresslevel=9,
        )
'@
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $zipHelperPath) | Out-Null
    [System.IO.File]::WriteAllText(
        $zipHelperPath,
        ($python -replace "`r`n", "`n"),
        (New-Object System.Text.UTF8Encoding($false))
    )
    try {
        $partial = "$Destination.partial"
        if (Test-Path -LiteralPath $partial) { Remove-Item -LiteralPath $partial -Force }
        Invoke-Native -FilePath "wsl.exe" -Arguments @(
            "python3",
            (ConvertTo-WslPath -WindowsPath $zipHelperPath),
            (ConvertTo-WslPath -WindowsPath $SourceDirectory),
            (ConvertTo-WslPath -WindowsPath $partial)
        ) -Stage "Deflate level 9 ROM archive" -NormalizeLineEndings | Out-Null
        Move-Item -LiteralPath $partial -Destination $Destination -Force
    }
    finally {
        if (Test-Path -LiteralPath $zipHelperPath) {
            Remove-Item -LiteralPath $zipHelperPath -Force -ErrorAction SilentlyContinue
        }
    }
}

function Test-RomArchive {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string[]]$ExpectedNames,
        [Parameter(Mandatory = $true)][string]$StagingPath
    )

    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::OpenRead($Path)
    try {
        $actualNames = @($archive.Entries | ForEach-Object { $_.FullName })
        if (@($actualNames | Select-Object -Unique).Count -ne $actualNames.Count) {
            throw "ROM archive contains duplicate entry names."
        }
        foreach ($name in $actualNames) {
            if ($name -ne [System.IO.Path]::GetFileName($name)) {
                throw "ROM archive contains a nested path: $name"
            }
        }
        $actualSorted = @($actualNames | Sort-Object)
        $expectedSorted = @($ExpectedNames | Sort-Object)
        if (($actualSorted -join "|") -cne ($expectedSorted -join "|")) {
            throw "ROM archive contents differ from the guarded release file list."
        }

        # Read every entry completely. Besides catching truncation, this makes
        # ZipArchive validate each entry's Deflate stream and CRC before release.
        $buffer = New-Object byte[] (4MB)
        foreach ($entry in ($archive.Entries | Sort-Object FullName)) {
            $staged = Get-Item -LiteralPath (Join-Path $StagingPath $entry.FullName)
            if ($entry.Length -ne $staged.Length) {
                throw "ZIP entry size mismatch: $($entry.FullName)"
            }
            $stream = $entry.Open()
            try {
                $readTotal = 0L
                while (($read = $stream.Read($buffer, 0, $buffer.Length)) -gt 0) {
                    $readTotal += $read
                }
                if ($readTotal -ne $entry.Length) {
                    throw "ZIP entry readback was incomplete: $($entry.FullName)"
                }
            }
            finally {
                $stream.Dispose()
            }
            Write-Detail ("verified ZIP entry: {0} ({1:N0} bytes)" -f $entry.FullName, $entry.Length)
        }
    }
    finally {
        $archive.Dispose()
    }
}

$lockPath = Join-Path $root "build\updater-rom-build.lock"
$script:UpdaterBuildLock = $null
$exitCode = 0
$logStopped = $false
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $lockPath) | Out-Null
try {
    $script:UpdaterBuildLock = [System.IO.File]::Open(
        $lockPath,
        [System.IO.FileMode]::OpenOrCreate,
        [System.IO.FileAccess]::ReadWrite,
        [System.IO.FileShare]::None
    )
} catch {
    throw "Another full-ROM validation/build is already running."
}

try {
    Assert-GeneratedDirectory -Path $outputPath
    Assert-GeneratedDirectory -Path $firmwareOutputPath
    Assert-GeneratedDirectory -Path $stagingDirectory

    if ($Clean) {
        Write-Stage "Cleaning generated ROM outputs"
        Remove-GeneratedDirectory -Path $outputPath
        Remove-GeneratedDirectory -Path $stagingDirectory
        if (Test-Path -LiteralPath $zipHelperPath) {
            Remove-Item -LiteralPath $zipHelperPath -Force
        }
        Invoke-Native -FilePath "powershell.exe" -Arguments @(
            "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
            (Join-Path $root "tools\build-firmware.ps1"),
            "-Clean", "-OutputDirectory", $firmwareOutputPath
        ) -Stage "Clean system-image outputs" -NormalizeLineEndings | Out-Null
        Write-Detail "kept the verified updater-template cache"
        Write-Host "`nFull-ROM outputs cleaned." -ForegroundColor Green
        return
    }

    Write-Stage "Y2Player full-ROM pipeline"
    Write-Detail "repository : $root"
    Write-Detail "output     : $archivePath"
    Write-Detail "target     : Innioasis Y2 / MT6582 / eastaeon82_wet_kk"
    Write-Warning "rom_y2.zip is a full-device package. Installing it erases user data."

    Write-Stage "1. Validating stock full-ROM inputs"
    $stockSystemPath = Join-Path $stockDirectory "system.img"
    $null = Test-FullRomInputs -SystemImage $stockSystemPath
    if (-not (Get-Command wsl.exe -ErrorAction SilentlyContinue)) {
        throw "WSL was not found. WSL with Python 3 is required."
    }
    Write-Detail "scatter platform, project, files, sizes, and boot headers verified"

    if ($ValidateOnly) {
        Write-Stage "2. Validating the existing system-image pipeline"
        Invoke-Native -FilePath "powershell.exe" -Arguments @(
            "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
            (Join-Path $root "tools\build-firmware.ps1"),
            "-ValidateOnly", "-OutputDirectory", $firmwareOutputPath
        ) -Stage "System-image pipeline validation" -NormalizeLineEndings | Out-Null

        $candidate = $null
        if ($TemplateZip) {
            $candidate = Resolve-TemplateArchive
        } elseif (Test-Path -LiteralPath $templateCache -PathType Leaf) {
            $candidate = Resolve-TemplateArchive
        } else {
            Write-Detail "updater template: will be downloaded and pinned on the first full build"
        }
        if ($candidate) { Write-Detail "updater template: $candidate" }
        Write-Host "`nFull-ROM validation complete. No artifacts were produced." -ForegroundColor Green
        return
    }

    New-Item -ItemType Directory -Force -Path $outputPath | Out-Null
    $script:BuildLogPath = Join-Path $outputPath "build.log"
    Start-BuildLog -Path $script:BuildLogPath

    foreach ($name in @(
        "rom_y2.zip", "rom_y2.zip.partial", "checksums.txt",
        "build-manifest.txt", "verification-report.txt"
    )) {
        $old = Join-Path $outputPath $name
        if (Test-Path -LiteralPath $old) { Remove-Item -LiteralPath $old -Force }
    }
    Remove-GeneratedDirectory -Path $stagingDirectory

    Write-Stage "2. Building a fresh signed system image"
    # Clean only generated system-image work/output first. This prevents an
    # unrelated artifact from an older build (for example system.img.zip) from
    # being mistaken for part of the current release.
    Invoke-Native -FilePath "powershell.exe" -Arguments @(
        "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
        (Join-Path $root "tools\build-firmware.ps1"),
        "-Clean", "-OutputDirectory", $firmwareOutputPath
    ) -Stage "Clean previous system-image outputs" -NormalizeLineEndings | Out-Null
    Invoke-Native -FilePath "powershell.exe" -Arguments @(
        "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
        (Join-Path $root "tools\build-firmware.ps1"),
        "-OutputDirectory", $firmwareOutputPath
    ) -Stage "Fresh signed system-image build" -NormalizeLineEndings | Out-Null

    $generatedSystem = Join-Path $firmwareOutputPath "system.img"
    foreach ($name in @("system.img", "Y2Player.apk", "build-manifest.txt", "verification-report.txt")) {
        if (-not (Test-Path -LiteralPath (Join-Path $firmwareOutputPath $name) -PathType Leaf)) {
            throw "System-image builder did not produce $name."
        }
    }
    $scatter = Test-FullRomInputs -SystemImage $generatedSystem
    $template = Resolve-TemplateArchive

    Write-Stage "3. Staging stock partitions and flashing tools"
    New-Item -ItemType Directory -Force -Path $stagingDirectory | Out-Null
    Expand-FlashToolFiles -ArchivePath $template -Destination $stagingDirectory

    $copiedHashes = New-Object System.Collections.Generic.List[string]
    foreach ($definition in ($partitionFiles | Where-Object { -not $_.FileSystem })) {
        $source = Join-Path $stockDirectory $definition.File
        $destination = Join-Path $stagingDirectory $definition.File
        $hash = Copy-VerifiedFile -Source $source -Destination $destination
        $copiedHashes.Add("$hash  $($definition.File)")
        Write-Detail "stock partition: $($definition.File)"
    }
    $scatterHash = Copy-VerifiedFile -Source $scatterPath `
        -Destination (Join-Path $stagingDirectory $scatterName)
    $copiedHashes.Add("$scatterHash  $scatterName")

    # Generate this ourselves so it always points to the root-level files in
    # the archive, independent of the machine that supplied the template.
    $historyText = @(
        "[LastDAFilePath]",
        "lastDir=MTK_AllInOne_DA.bin",
        "",
        "[RecentOpenFile]",
        "lastDir=",
        "scatterHistory=$scatterName",
        "authHistory=",
        ""
    ) -join "`r`n"
    [System.IO.File]::WriteAllText(
        (Join-Path $stagingDirectory "history.ini"),
        $historyText,
        [System.Text.Encoding]::ASCII
    )

    Write-Stage "4. Creating raw filesystem images"
    foreach ($definition in ($partitionFiles | Where-Object { $_.FileSystem })) {
        $source = if ($definition.Partition -eq "ANDROID") {
            $generatedSystem
        } else {
            Join-Path $stockDirectory $definition.File
        }
        $expandedSize = (Get-ImageInformation -Path $source).ExpandedSize
        Expand-ImageForPackage -Source $source `
            -Destination (Join-Path $stagingDirectory $definition.File) `
            -ExpectedSize $expandedSize
        Write-Detail ("raw image: {0} ({1:N0} bytes)" -f
            $definition.File, $expandedSize)
    }

    $expectedArchiveNames = @(
        $partitionFiles | ForEach-Object { $_.File }
    ) + @($scatterName, "history.ini") + $flashToolFiles
    $expectedArchiveNames = @($expectedArchiveNames | Sort-Object -Unique)
    $stagedNames = @(Get-ChildItem -LiteralPath $stagingDirectory -File |
        ForEach-Object { $_.Name } | Sort-Object -Unique)
    if (($stagedNames -join "|") -cne (($expectedArchiveNames | Sort-Object) -join "|")) {
        throw "Staging contents differ from the guarded release file list."
    }
    if (Get-ChildItem -LiteralPath $stagingDirectory -Directory) {
        throw "ROM staging contains an unexpected subdirectory."
    }

    Write-Stage "5. Verifying staged full-ROM contents"
    foreach ($definition in $partitionFiles) {
        $path = Join-Path $stagingDirectory $definition.File
        $entry = $scatter[$definition.Partition]
        $info = Get-ImageInformation -Path $path
        if ($definition.FileSystem) {
            if ($info.IsSparse -or $info.StoredSize -gt $entry.Size) {
                throw "Staged filesystem image is not raw or does not fit its partition: $($definition.File)"
            }
        } elseif ($info.StoredSize -gt $entry.Size) {
            throw "Staged partition exceeds its scatter allocation: $($definition.File)"
        }
    }
    Assert-FileMagic -Path (Join-Path $stagingDirectory "boot.img") `
        -Expected ([System.Text.Encoding]::ASCII.GetBytes("ANDROID!")) -Description "Packaged boot.img"
    Assert-FileMagic -Path (Join-Path $stagingDirectory "recovery.img") `
        -Expected ([System.Text.Encoding]::ASCII.GetBytes("ANDROID!")) -Description "Packaged recovery.img"

    $imageHashes = foreach ($definition in $partitionFiles) {
        $path = Join-Path $stagingDirectory $definition.File
        "$(Get-Sha256 -Path $path)  $($definition.File)"
    }
    $build = Get-BuildId -Root $root
    $firmwareManifest = Get-Content -LiteralPath (Join-Path $firmwareOutputPath "build-manifest.txt") -Raw
    $manifestLines = New-Object System.Collections.Generic.List[string]
    $manifestLines.Add("Y2Player Innioasis Updater full-ROM build")
    $manifestLines.Add("============================================")
    $manifestLines.Add("")
    $manifestLines.Add("Build ID            : $($build.BuildId)")
    $manifestLines.Add("Target              : Innioasis Y2")
    $manifestLines.Add("Platform            : MT6582")
    $manifestLines.Add("Project             : eastaeon82_wet_kk")
    $manifestLines.Add("Archive name        : rom_y2.zip")
    $manifestLines.Add("Archive layout      : flat/root-level files")
    $manifestLines.Add("Compression         : Deflate level 9, Zip64 enabled")
    $manifestLines.Add("Filesystem images   : raw/desparsed system, cache, userdata")
    $manifestLines.Add("Installation effect : full-device flash; user data is erased")
    $manifestLines.Add("Template source     : $templateUri")
    $manifestLines.Add("Template SHA-256    : $templateSha256")
    $manifestLines.Add("Template use        : allowlisted SP Flash Tool support files only")
    $manifestLines.Add("Boot image          : unmodified OriginalFirmware/boot.img")
    $manifestLines.Add("")
    $manifestLines.Add("Packaged image SHA-256")
    $manifestLines.Add("-------------------------")
    foreach ($line in $imageHashes) { $manifestLines.Add($line) }
    $manifestLines.Add("")
    $manifestLines.Add("Byte-verified stock copies")
    $manifestLines.Add("--------------------------")
    foreach ($line in $copiedHashes) { $manifestLines.Add($line) }
    $manifestLines.Add("")
    $manifestLines.Add("Embedded system-image build manifest")
    $manifestLines.Add("------------------------------------")
    foreach ($line in ($firmwareManifest -split "`r?`n")) { $manifestLines.Add($line) }
    Write-Utf8NoBomLines -Path (Join-Path $outputPath "build-manifest.txt") `
        -Lines $manifestLines.ToArray()

    Write-Stage "6. Creating rom_y2.zip"
    New-LevelNineZip -SourceDirectory $stagingDirectory -Destination $archivePath

    Write-Stage "7. Reopening and testing the finished archive"
    Test-RomArchive -Path $archivePath -ExpectedNames $expectedArchiveNames `
        -StagingPath $stagingDirectory

    $verificationLines = @(
        "Y2Player full-ROM verification report",
        "======================================",
        "PASS: filename is exactly rom_y2.zip",
        "PASS: archive has a flat root layout and no duplicate entries",
        "PASS: every archive entry was fully decompressed and read back",
        "PASS: portable SP Flash Tool files came from the pinned SHA-256 template",
        "PASS: template firmware images were not imported",
        "PASS: scatter platform is MT6582 and project is eastaeon82_wet_kk",
        "PASS: every downloadable scatter partition has its expected image",
        "PASS: stock partition copies are byte-identical to OriginalFirmware",
        "PASS: boot and recovery contain Android boot-image magic",
        "PASS: system, cache, and userdata are raw, source-sized, and fit their partitions",
        "PASS: system.img came from a fresh signed build-firmware.ps1 run",
        "PASS: history.ini selects the bundled DA and Y2 scatter by relative path",
        "PASS: archive uses Deflate level 9 with Zip64 support",
        "",
        "WARNING: Installing this full-ROM package erases user data."
    )
    Write-Utf8NoBomLines -Path (Join-Path $outputPath "verification-report.txt") `
        -Lines $verificationLines

    Stop-BuildLog
    $logStopped = $true
    $checksumLines = foreach ($name in @(
        "rom_y2.zip", "build-manifest.txt", "verification-report.txt", "build.log"
    )) {
        $path = Join-Path $outputPath $name
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            throw "Cannot checksum missing output: $name"
        }
        "$(Get-Sha256 -Path $path)  $name"
    }
    Write-Utf8NoBomLines -Path (Join-Path $outputPath "checksums.txt") `
        -Lines $checksumLines

    Write-Host "`nFull updater ROM complete: $archivePath" -ForegroundColor Green
    Write-Host "Upload rom_y2.zip as a GitHub release asset without renaming it." -ForegroundColor Cyan
    Write-Host "The separately built system-only artifacts remain in $firmwareOutputPath." -ForegroundColor Cyan
    Write-Warning "This archive is for a full-device install and erases user data."

    if (-not $KeepStaging) {
        Remove-GeneratedDirectory -Path $stagingDirectory
        Write-Detail "removed temporary raw-image staging data"
    } else {
        Write-Detail "kept staging directory: $stagingDirectory"
    }
}
catch {
    $exitCode = 1
    Write-Host "`nFULL-ROM BUILD FAILED" -ForegroundColor Red
    Write-Host "  stage : $script:CurrentStage" -ForegroundColor Red
    Write-Host "  error : $($_.Exception.Message)" -ForegroundColor Red
    foreach ($path in @($archivePath, "$archivePath.partial")) {
        if (Test-Path -LiteralPath $path) {
            Remove-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue
        }
    }
}
finally {
    if (-not $logStopped) { Stop-BuildLog }
    if (Test-Path -LiteralPath $zipHelperPath) {
        Remove-Item -LiteralPath $zipHelperPath -Force -ErrorAction SilentlyContinue
    }
    if ($script:UpdaterBuildLock) {
        $script:UpdaterBuildLock.Dispose()
        Remove-Item -LiteralPath $lockPath -Force -ErrorAction SilentlyContinue
    }
}

if ($exitCode -ne 0) { exit $exitCode }
