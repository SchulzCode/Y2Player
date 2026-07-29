<#
.SYNOPSIS
    Captures a read-only hardware and audio-stack snapshot from a connected Y2.

.DESCRIPTION
    Records kernel, sysfs, ALSA, Android service, USB, storage, display, input,
    sensor, power, process, and Y2Player state. It also pulls the exact stock
    audio policy, HAL, and related libraries for offline analysis.

    The collector never writes to the device: no mixer changes, ioctls, playback,
    pushes, remounts, property changes, log clearing, or root attempts. In
    particular, it never opens /dev/cs43131_dac, whose protocol is unverified.
#>
[CmdletBinding()]
param(
    [string]$Serial = "0123456789ABCDEF",
    [string]$OutputRoot = "out\hardware-snapshots",
    [string]$AdbPath
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
$snapshot = Join-Path $outputBase $stamp
$commandsDir = Join-Path $snapshot "commands"
$logsDir = Join-Path $snapshot "logs"
$filesDir = Join-Path $snapshot "pulled"
$hostDir = Join-Path $snapshot "host"
New-Item -ItemType Directory -Force -Path $snapshot, $commandsDir, $logsDir, $filesDir, $hostDir | Out-Null

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

function Save-Text {
    param(
        [Parameter(Mandatory)][string]$RelativePath,
        [Parameter(Mandatory)][string]$Header,
        [AllowEmptyString()][string]$Body,
        [int]$ExitCode = 0
    )
    $target = Join-Path $snapshot $RelativePath
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target) | Out-Null
    $text = "$Header`n# exit-code: $ExitCode`n`n$Body`n"
    [IO.File]::WriteAllText($target, $text, [Text.UTF8Encoding]::new($false))
}

function Save-Shell {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string]$Command,
        [string]$Category = "commands"
    )
    $result = Invoke-AdbCapture -Arguments @("shell", $Command)
    $relative = "$Category/$Name.txt"
    Save-Text -RelativePath $relative -Header "# adb shell $Command" -Body $result.Text -ExitCode $result.ExitCode
    $status = if ($result.Text -match "Permission denied|Operation not permitted") {
        "DENIED"
    } elseif ($result.ExitCode -ne 0 -or $result.Text -match "not found|No such file or directory") {
        "UNAVAILABLE"
    } elseif ([string]::IsNullOrWhiteSpace($result.Text)) {
        "EMPTY"
    } else {
        "COLLECTED"
    }
    $manifest.Add([pscustomobject]@{
        Category = $Category; Name = $Name; Status = $status
        ExitCode = $result.ExitCode; Path = $relative; Command = $Command
    }) | Out-Null
    Write-Host ("{0,-11} {1}/{2}" -f $status, $Category, $Name)
}

function Pull-File {
    param([Parameter(Mandatory)][string]$RemotePath)
    $leaf = ($RemotePath.TrimStart('/') -replace '/', '__')
    $target = Join-Path $filesDir $leaf
    $result = Invoke-AdbCapture -Arguments @("pull", $RemotePath, $target)
    $exists = Test-Path -LiteralPath $target -PathType Leaf
    $status = if ($exists -and $result.ExitCode -eq 0) { "COLLECTED" } else { "UNAVAILABLE" }
    $manifest.Add([pscustomobject]@{
        Category = "pulled"; Name = $RemotePath; Status = $status
        ExitCode = $result.ExitCode; Path = if ($exists) { "pulled/$leaf" } else { "" }
        Command = "adb pull $RemotePath"
    }) | Out-Null
    if (-not $exists) {
        Save-Text -RelativePath "pulled/$leaf.error.txt" -Header "# adb pull $RemotePath" `
            -Body $result.Text -ExitCode $result.ExitCode
    }
    Write-Host ("{0,-11} pulled/{1}" -f $status, $leaf)
}

Write-Host "Y2 read-only hardware snapshot" -ForegroundColor Cyan
Write-Host "ADB: $AdbPath"
Write-Host "Output: $snapshot"
Write-Host "No device state will be changed.`n"

$devices = Invoke-AdbCapture -Arguments @("devices", "-l")
Save-Text -RelativePath "adb-devices.txt" -Header "# adb devices -l" -Body $devices.Text -ExitCode $devices.ExitCode
if ($devices.ExitCode -ne 0 -or $devices.Text -notmatch "(?m)^$([regex]::Escape($Serial))\s+device\b") {
    throw "Device $Serial is not online: $($devices.Text)"
}
$adbVersion = Invoke-AdbCapture -Arguments @("version")
Save-Text -RelativePath "adb-version.txt" -Header "# adb version" -Body $adbVersion.Text -ExitCode $adbVersion.ExitCode

$shellCommands = @(
    # Identity, kernel, CPU, memory, and buses.
    @{ N="getprop"; C="getprop"; G="identity" },
    @{ N="uname"; C="uname -a"; G="identity" },
    @{ N="build-fingerprint"; C="getprop ro.build.fingerprint"; G="identity" },
    @{ N="selinux"; C="getenforce"; G="identity" },
    @{ N="shell-id"; C="id"; G="identity" },
    @{ N="proc-version"; C="cat /proc/version"; G="hardware" },
    @{ N="proc-cmdline"; C="cat /proc/cmdline"; G="hardware" },
    @{ N="proc-cpuinfo"; C="cat /proc/cpuinfo"; G="hardware" },
    @{ N="proc-meminfo"; C="cat /proc/meminfo"; G="hardware" },
    @{ N="proc-devices"; C="cat /proc/devices"; G="hardware" },
    @{ N="proc-interrupts"; C="cat /proc/interrupts"; G="hardware" },
    @{ N="proc-partitions"; C="cat /proc/partitions"; G="hardware" },
    @{ N="proc-modules"; C="cat /proc/modules"; G="hardware" },
    @{ N="proc-iomem"; C="cat /proc/iomem"; G="hardware" },
    @{ N="cpu-topology"; C="cat /sys/devices/system/cpu/possible; cat /sys/devices/system/cpu/present; cat /sys/devices/system/cpu/online; cat /sys/devices/system/cpu/offline"; G="hardware" },
    @{ N="cpu-frequency"; C='for f in /sys/devices/system/cpu/cpu*/cpufreq/scaling_{cur,min,max}_freq; do echo ===$f; cat $f; done'; G="hardware" },
    @{ N="i2c-devices"; C="ls -la /sys/bus/i2c/devices"; G="hardware" },
    @{ N="i2c-identities"; C='for f in /sys/bus/i2c/devices/*/name /sys/bus/i2c/devices/*/modalias /sys/bus/i2c/devices/*/uevent; do echo ===$f; cat $f; done'; G="hardware" },
    @{ N="cs43131-sysfs"; C="ls -la /sys/bus/i2c/devices/1-0030/"; G="audio" },
    @{ N="spi-devices"; C="ls -la /sys/bus/spi/devices"; G="hardware" },
    @{ N="platform-devices"; C="ls -la /sys/bus/platform/devices"; G="hardware" },
    @{ N="input-devices"; C="cat /proc/bus/input/devices"; G="hardware" },
    @{ N="device-tree"; C="ls -la /proc/device-tree /sys/firmware/devicetree/base"; G="hardware" },
    @{ N="relevant-kallsyms"; C="cat /proc/kallsyms | grep -i -E 'cs43131|cirrus|codec|audio|i2s|afe|pcm|dac'"; G="hardware" },

    # ALSA, character device metadata, Android policy, and live audio services.
    @{ N="dev-sound"; C="ls -la /dev/snd /dev/cs43131_dac"; G="audio" },
    @{ N="dac-node-metadata"; C="ls -ln /dev/cs43131_dac"; G="audio" },
    @{ N="vendor-audio-device-nodes"; C="ls -la /dev | grep -i -E 'audio|eac|afe|pcm|snd|sound|dac|amp'"; G="audio" },
    @{ N="proc-asound-tree"; C="ls -laR /proc/asound"; G="audio" },
    @{ N="alsa-cards"; C="cat /proc/asound/cards"; G="audio" },
    @{ N="alsa-devices"; C="cat /proc/asound/devices"; G="audio" },
    @{ N="alsa-pcm"; C="cat /proc/asound/pcm"; G="audio" },
    @{ N="alsa-version"; C="cat /proc/asound/version"; G="audio" },
    @{ N="alsa-hw-params"; C='for f in /proc/asound/card*/pcm*/sub*/hw_params; do echo ===$f; cat $f; done'; G="audio" },
    @{ N="alsa-pcm-status"; C='for f in /proc/asound/card*/pcm*/sub*/status; do echo ===$f; cat $f; done'; G="audio" },
    @{ N="sys-class-sound"; C="ls -laR /sys/class/sound"; G="audio" },
    @{ N="sound-card-ids"; C='for f in /sys/class/sound/card*/id; do echo ===$f; cat $f; done'; G="audio" },
    @{ N="audio-tools"; C="which tinymix tinyplay tinycap alsa_aplay alsa_amixer tinyalsa"; G="audio" },
    @{ N="tinymix-state"; C="tinymix"; G="audio" },
    @{ N="audio-policy-conf"; C="cat /system/etc/audio_policy.conf"; G="audio" },
    @{ N="audio-effects-conf"; C="cat /system/etc/audio_effects.conf"; G="audio" },
    @{ N="audio-files"; C="ls -la /system/etc/*audio* /system/etc/*mixer* /system/lib/hw/*audio* /system/lib/libaudio* /system/bin/*audio*"; G="audio" },
    @{ N="service-list"; C="service list"; G="services" },
    @{ N="dumpsys-audio"; C="dumpsys audio"; G="services" },
    @{ N="dumpsys-audio-flinger"; C="dumpsys media.audio_flinger"; G="services" },
    @{ N="dumpsys-audio-policy"; C="dumpsys media.audio_policy"; G="services" },
    @{ N="dumpsys-media-player"; C="dumpsys media.player"; G="services" },
    @{ N="mediaserver-fds"; C='p=$(pidof mediaserver); echo pid=$p; ls -la /proc/$p/fd'; G="audio" },
    @{ N="audio-processes"; C="ps -Z | grep -i -E 'media|audio|y2player'"; G="audio" },

    # Current system state outside audio.
    @{ N="mount"; C="mount"; G="system" },
    @{ N="df"; C="df"; G="system" },
    @{ N="vold-volumes"; C="vdc volume list"; G="system" },
    @{ N="dumpsys-mount"; C="dumpsys mount"; G="system" },
    @{ N="usb-state"; C="cat /sys/class/android_usb/android0/state; cat /sys/class/android_usb/android0/functions"; G="system" },
    @{ N="dumpsys-power"; C="dumpsys power"; G="system" },
    @{ N="dumpsys-battery"; C="dumpsys battery"; G="system" },
    @{ N="dumpsys-display"; C="dumpsys display"; G="system" },
    @{ N="dumpsys-sensorservice"; C="dumpsys sensorservice"; G="system" },
    @{ N="dumpsys-input"; C="dumpsys input"; G="system" },
    @{ N="dumpsys-surfaceflinger"; C="dumpsys SurfaceFlinger"; G="system" },
    @{ N="dumpsys-cpuinfo"; C="dumpsys cpuinfo"; G="system" },
    @{ N="dumpsys-meminfo"; C="dumpsys meminfo"; G="system" },
    @{ N="processes"; C="ps -Z"; G="system" },
    @{ N="y2player-package"; C="dumpsys package com.schulzcode.y2player"; G="y2player" },
    @{ N="y2player-meminfo"; C="dumpsys meminfo com.schulzcode.y2player"; G="y2player" },
    @{ N="y2player-activity"; C="dumpsys activity com.schulzcode.y2player"; G="y2player" },
    @{ N="dmesg"; C="dmesg"; G="logs" },
    @{ N="dmesg-audio"; C="dmesg | grep -i -E 'cs43131|cirrus|codec|audio|i2s|afe|pcm|dac'"; G="logs" }
)

foreach ($entry in $shellCommands) {
    Save-Shell -Name $entry.N -Command $entry.C -Category $entry.G
}

foreach ($buffer in @("main", "system", "events", "radio")) {
    $result = Invoke-AdbCapture -Arguments @("logcat", "-d", "-v", "threadtime", "-b", $buffer)
    Save-Text -RelativePath "logs/logcat-$buffer.txt" -Header "# adb logcat -d -v threadtime -b $buffer" `
        -Body $result.Text -ExitCode $result.ExitCode
    $manifest.Add([pscustomobject]@{
        Category="logs"; Name="logcat-$buffer"; Status=if ($result.ExitCode -eq 0) { "COLLECTED" } else { "UNAVAILABLE" }
        ExitCode=$result.ExitCode; Path="logs/logcat-$buffer.txt"; Command="adb logcat -b $buffer"
    }) | Out-Null
}

$pullFiles = @(
    "/system/etc/audio_policy.conf",
    "/system/etc/audio_effects.conf",
    "/system/etc/media_codecs.xml",
    "/system/lib/hw/audio.primary.default.so",
    "/system/lib/hw/audio_policy.default.so",
    "/system/lib/libaudio.primary.default.so",
    "/system/lib/libaudioflinger.so",
    "/system/lib/libaudio-resampler.so",
    "/system/lib/libaudiocustparam.so",
    "/system/lib/libaudiosetting.so",
    "/system/lib/libtinyalsa.so",
    "/system/bin/audiocmdservice_atci",
    "/system/bin/mediaserver",
    "/init.rc",
    "/init.project.rc",
    "/file_contexts",
    "/sepolicy",
    "/proc/config.gz"
)
foreach ($remote in $pullFiles) { Pull-File -RemotePath $remote }

# Host-side view of the same composite USB device. These calls are read-only.
$pnpPath = Join-Path $hostDir "pnp-y2.txt"
try {
    $pnp = Get-PnpDevice -PresentOnly -ErrorAction Stop | Where-Object {
        $_.InstanceId -match 'VID_0BB4&PID_0C03'
    } | Select-Object Status,Class,FriendlyName,InstanceId | Format-List | Out-String
    [IO.File]::WriteAllText($pnpPath, $pnp, [Text.UTF8Encoding]::new($false))
} catch {
    [IO.File]::WriteAllText($pnpPath, "UNAVAILABLE: $($_.Exception.Message)`n", [Text.UTF8Encoding]::new($false))
}

$manifestPath = Join-Path $snapshot "manifest.csv"
$manifest | Export-Csv -LiteralPath $manifestPath -NoTypeInformation -Encoding UTF8

$checksumPath = Join-Path $snapshot "checksums.txt"
$checksumLines = Get-ChildItem -LiteralPath $snapshot -Recurse -File | Where-Object {
    $_.FullName -ne $checksumPath
} | Sort-Object FullName | ForEach-Object {
    $relative = $_.FullName.Substring($snapshot.Length + 1).Replace('\', '/')
    "$((Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName).Hash.ToLower())  $relative"
}
[IO.File]::WriteAllText($checksumPath, (($checksumLines -join "`n") + "`n"), [Text.ASCIIEncoding]::new())

$zip = "$snapshot.zip"
Compress-Archive -Path (Join-Path $snapshot "*") -DestinationPath $zip -Force

$collected = @($manifest | Where-Object Status -eq "COLLECTED").Count
$limited = $manifest.Count - $collected
Write-Host "`nSnapshot complete: $snapshot" -ForegroundColor Green
Write-Host "Archive          : $zip" -ForegroundColor Green
Write-Host "Collected        : $collected"
Write-Host "Unavailable/denied/empty: $limited"
