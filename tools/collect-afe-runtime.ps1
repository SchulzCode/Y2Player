<#
.SYNOPSIS
    Read-only, state-by-state AFE runtime capture for the Innioasis Y2.

.DESCRIPTION
    Phase-2 runtime verification for docs/Y2_AUDIO_PATH_PHASE2_ANALYSIS.md.

    Walks the operator through a sequence of audio states and, at each one,
    captures /proc/audio plus the Android audio service dumps, interrupts, the
    h2w switch, device time and a logcat dump. Produces a manifest with host and
    device timestamps, the declared route and source format, and a SHA-256 for
    every captured file.

    SAFETY. This collector is strictly read-only and performs no device
    modification whatsoever. It never:
      - opens or writes /dev/eac or /dev/cs43131_dac
      - issues any ioctl
      - writes any AFE or codec register
      - modifies system files, properties or settings
      - remounts, reboots, flashes or uses su/root
      - clears logcat (logcat is only ever dumped with -d)
    Playback itself is started and stopped BY THE OPERATOR on the device. The
    script only observes and prompts.

.PARAMETER States
    Subset of state ids to run. Default is all of them. Ids:
      01-idle              device idle, nothing playing, no headphones
      02-wired-idle        wired headphones connected, no playback
      03-wired-44k1        wired, playing a verified 44.1 kHz file
      04-wired-48k         wired, playing a verified 48 kHz file (optional)
      05-bluetooth         Bluetooth playback (optional)
      06-paused            playback paused
      07-resumed           playback resumed
      08-stopped           playback stopped
      09-wired-disconnect  headphones unplugged during playback
      10-wired-reconnect   headphones replugged

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\collect-afe-runtime.ps1

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\collect-afe-runtime.ps1 `
        -States 01-idle,02-wired-idle,03-wired-44k1,06-paused,07-resumed,08-stopped
#>
[CmdletBinding()]
param(
    [string]$Serial = "0123456789ABCDEF",
    [string]$OutputRoot = "out\afe-runtime",
    [string]$AdbPath,
    [string[]]$States
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

if (-not $AdbPath) {
    $candidates = @(
        (Join-Path $root "build\toolchains\platform-tools-r28.0.2-windows\platform-tools\adb.exe"),
        (Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe")
    )
    $AdbPath = $candidates | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } |
        Select-Object -First 1
}
if (-not $AdbPath -or -not (Test-Path -LiteralPath $AdbPath -PathType Leaf)) {
    throw "ADB was not found. The Android 4.4-compatible r28 client is preferred."
}
$AdbPath = (Resolve-Path -LiteralPath $AdbPath).Path

$outputBase = if ([IO.Path]::IsPathRooted($OutputRoot)) {
    [IO.Path]::GetFullPath($OutputRoot)
} else {
    [IO.Path]::GetFullPath((Join-Path $root $OutputRoot))
}
$stamp = (Get-Date).ToString("yyyy-MM-dd_HHmmss")
$session = Join-Path $outputBase $stamp
New-Item -ItemType Directory -Force -Path $session | Out-Null

$manifest = [Collections.Generic.List[object]]::new()
$adbPrefix = @("-s", $Serial)

function Invoke-AdbCapture {
    param([Parameter(Mandatory)][string[]]$Arguments)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $lines = @(& $AdbPath @($adbPrefix + $Arguments) 2>&1 | ForEach-Object {
            if ($_ -is [Management.Automation.ErrorRecord] -and $null -ne $_.TargetObject) {
                [string]$_.TargetObject
            } else {
                [string]$_
            }
        })
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    [pscustomobject]@{ ExitCode = $exitCode; Text = ($lines -join "`n") }
}

function Get-Sha256 {
    param([Parameter(Mandatory)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return "" }
    (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Save-Shell {
    param(
        [Parameter(Mandatory)][string]$StateDir,
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string]$Command,
        [string]$State = "global"
    )
    $result = Invoke-AdbCapture -Arguments @("shell", $Command)
    $target = Join-Path $StateDir "$Name.txt"
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target) | Out-Null
    $text = "# adb shell $Command`n# exit-code: $($result.ExitCode)`n# host-utc: $((Get-Date).ToUniversalTime().ToString('o'))`n`n$($result.Text)`n"
    [IO.File]::WriteAllText($target, $text, [Text.UTF8Encoding]::new($false))

    $status = if ($result.Text -match "Permission denied|Operation not permitted") {
        "DENIED"
    } elseif ($result.ExitCode -ne 0 -or $result.Text -match "No such file or directory|not found") {
        "UNAVAILABLE"
    } elseif ([string]::IsNullOrWhiteSpace($result.Text)) {
        "EMPTY"
    } else {
        "COLLECTED"
    }

    # [IO.Path]::GetRelativePath is .NET Core only; Windows PowerShell 5.1 lacks it.
    $rel = if ($target.StartsWith($session, [StringComparison]::OrdinalIgnoreCase)) {
        $target.Substring($session.Length).TrimStart('\', '/') -replace '\\', '/'
    } else {
        Split-Path -Leaf $target
    }
    $manifest.Add([pscustomobject]@{
        State    = $State
        Name     = $Name
        Status   = $status
        ExitCode = $result.ExitCode
        Path     = $rel
        Sha256   = Get-Sha256 -Path $target
        Command  = $Command
        HostUtc  = (Get-Date).ToUniversalTime().ToString("o")
    }) | Out-Null
    Write-Host ("  {0,-11} {1}" -f $status, $Name)
}

Write-Host "Y2 AFE runtime verification capture (READ-ONLY)" -ForegroundColor Cyan
Write-Host "ADB:    $AdbPath"
Write-Host "Output: $session"
Write-Host "No device state is modified by this script.`n" -ForegroundColor Yellow

$devices = Invoke-AdbCapture -Arguments @("devices", "-l")
[IO.File]::WriteAllText((Join-Path $session "adb-devices.txt"), $devices.Text, [Text.UTF8Encoding]::new($false))
if ($devices.ExitCode -ne 0 -or $devices.Text -notmatch "(?m)^$([regex]::Escape($Serial))\s+device\b") {
    throw "Device $Serial is not online:`n$($devices.Text)"
}

Write-Host "Global identity, driver and permission checks" -ForegroundColor Cyan
$globalDir = Join-Path $session "00-global"
New-Item -ItemType Directory -Force -Path $globalDir | Out-Null

$globalCommands = @(
    @{ N = "getprop-full";        C = "getprop" },
    @{ N = "ro-hardware";         C = "getprop ro.hardware" },
    @{ N = "build-fingerprint";   C = "getprop ro.build.fingerprint" },
    @{ N = "uname";               C = "uname -a" },
    @{ N = "shell-id";            C = "id" },
    @{ N = "selinux";             C = "getenforce" },
    @{ N = "dev-eac-perms";       C = "ls -l /dev/eac /dev/cs43131_dac" },
    @{ N = "dev-eac-perms-num";   C = "ls -ln /dev/eac /dev/cs43131_dac" },
    @{ N = "proc-audio-perms";    C = "ls -l /proc/audio" },
    @{ N = "proc-audio-readable"; C = "cat /proc/audio | head -3" },
    @{ N = "proc-interrupts-afe"; C = "cat /proc/interrupts" },
    @{ N = "proc-iomem";          C = "cat /proc/iomem" },
    @{ N = "misc-devices";        C = "cat /proc/misc" },
    @{ N = "asound-cards";        C = "cat /proc/asound/cards" },
    @{ N = "i2c-cs43131";         C = "ls -la /sys/bus/i2c/devices/1-0030/" },
    @{ N = "y2player-version";    C = "dumpsys package com.schulzcode.y2player | grep -i -E 'versionName|versionCode|codePath'" }
)
foreach ($c in $globalCommands) {
    Save-Shell -StateDir $globalDir -Name $c.N -Command $c.C -State "00-global"
}

$allStates = @(
    @{ Id = "01-idle";             Prompt = "Device idle. NO playback. Headphones UNPLUGGED. Bluetooth off." }
    @{ Id = "02-wired-idle";       Prompt = "Wired headphones CONNECTED. Still NO playback." }
    @{ Id = "03-wired-44k1";       Prompt = "Wired. PLAYING a verified 44.1 kHz file. Let it run ~10 s first." }
    @{ Id = "04-wired-48k";        Prompt = "Wired. PLAYING a verified 48 kHz file (skip if the build cannot request native 48 kHz)." }
    @{ Id = "05-bluetooth";        Prompt = "Bluetooth headset connected and PLAYING (skip if not readily available)." }
    @{ Id = "06-paused";           Prompt = "Wired. Playback PAUSED (same file as 03)." }
    @{ Id = "07-resumed";          Prompt = "Wired. Playback RESUMED." }
    @{ Id = "08-stopped";          Prompt = "Wired. Playback STOPPED." }
    @{ Id = "09-wired-disconnect"; Prompt = "Headphones UNPLUGGED while playback was active (optional)." }
    @{ Id = "10-wired-reconnect";  Prompt = "Headphones RE-PLUGGED (optional)." }
)
if ($States) {
    $allStates = $allStates | Where-Object { $States -contains $_.Id }
    if (-not $allStates) { throw "No matching states. See -States in the help." }
}

$perStateCommands = @(
    @{ N = "proc-audio";        C = "cat /proc/audio" },
    @{ N = "audio-flinger";     C = "dumpsys media.audio_flinger" },
    @{ N = "audio-policy";      C = "dumpsys media.audio_policy" },
    @{ N = "audio-service";     C = "dumpsys audio" },
    @{ N = "proc-interrupts";   C = "cat /proc/interrupts" },
    @{ N = "h2w-state";         C = "cat /sys/class/switch/h2w/state" },
    @{ N = "device-date";       C = "date" },
    @{ N = "media-processes";   C = "ps | grep -i -E 'mediaserver|y2player'" }
)

$stateRecords = [Collections.Generic.List[object]]::new()

foreach ($state in $allStates) {
    Write-Host ""
    Write-Host ("=" * 78) -ForegroundColor DarkGray
    Write-Host "STATE $($state.Id)" -ForegroundColor Green
    Write-Host "  $($state.Prompt)"
    Write-Host ("=" * 78) -ForegroundColor DarkGray
    $answer = Read-Host "Press ENTER when the device is in this state, or type 'skip'"
    if ($answer -eq "skip") {
        Write-Host "  skipped" -ForegroundColor DarkYellow
        # Same property set as a captured state, so Export-Csv keeps every column.
        $stateRecords.Add([pscustomobject]@{
            State         = $state.Id
            Skipped       = $true
            HostUtcBefore = ""
            HostUtcAfter  = ""
            DeviceDate    = ""
            Route         = ""
            SourceFile    = ""
            SourceFormat  = ""
            Notes         = "skipped by operator"
        }) | Out-Null
        continue
    }

    $route  = Read-Host "  Active route (wired / bluetooth / speaker / none)"
    $src    = Read-Host "  Active source file (full path on device, or 'none')"
    $fmt    = Read-Host "  Source format (e.g. '44100 Hz / 16-bit / 2ch', or 'none')"
    $notes  = Read-Host "  Notes (optional)"

    $hostBefore = (Get-Date).ToUniversalTime().ToString("o")
    $stateDir = Join-Path $session $state.Id
    New-Item -ItemType Directory -Force -Path $stateDir | Out-Null

    # Device-side clock first, so logcat can be windowed offline.
    $devDate = Invoke-AdbCapture -Arguments @("shell", "date +`"%m-%d %H:%M:%S.000`"")

    foreach ($c in $perStateCommands) {
        Save-Shell -StateDir $stateDir -Name $c.N -Command $c.C -State $state.Id
    }

    # logcat is DUMPED only. It is never cleared.
    Save-Shell -StateDir $stateDir -Name "logcat-main" -Command "logcat -d -v threadtime" -State $state.Id
    Save-Shell -StateDir $stateDir -Name "logcat-events" -Command "logcat -d -b events -v threadtime" -State $state.Id

    $stateRecords.Add([pscustomobject]@{
        State         = $state.Id
        Skipped       = $false
        HostUtcBefore = $hostBefore
        HostUtcAfter  = (Get-Date).ToUniversalTime().ToString("o")
        DeviceDate    = $devDate.Text.Trim()
        Route         = $route
        SourceFile    = $src
        SourceFormat  = $fmt
        Notes         = $notes
    }) | Out-Null
}

$manifestPath = Join-Path $session "manifest.json"
[pscustomobject]@{
    Tool          = "collect-afe-runtime.ps1"
    Version       = "1.0"
    ReadOnly      = $true
    CapturedUtc   = (Get-Date).ToUniversalTime().ToString("o")
    Serial        = $Serial
    AdbPath       = $AdbPath
    States        = $stateRecords
    Files         = $manifest
} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $manifestPath -Encoding UTF8

$manifest | Export-Csv -LiteralPath (Join-Path $session "manifest-files.csv") -NoTypeInformation -Encoding UTF8
$stateRecords | Export-Csv -LiteralPath (Join-Path $session "manifest-states.csv") -NoTypeInformation -Encoding UTF8

Write-Host ""
Write-Host "Capture complete." -ForegroundColor Cyan
Write-Host "  $session"
Write-Host ""
Write-Host "Next: run the offline decoder over this directory:" -ForegroundColor Yellow
Write-Host "  python3 tools/analyze-afe-captures.py `"$session`""
