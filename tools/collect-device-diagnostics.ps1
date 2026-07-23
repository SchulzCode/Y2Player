<#
.SYNOPSIS
    Collects a complete, read-only diagnostic bundle from a connected Y2 device.

.DESCRIPTION
    Gathers everything needed to diagnose launcher startup, playback, scanning,
    stock storage/USB transitions, or a crash into one timestamped ZIP.

    THIS SCRIPT NEVER MODIFIES THE DEVICE.

    It does not flash, push files, reboot, power off, change USB mode, mount or
    unmount storage, clear logcat, or delete anything. Every adb invocation is a
    read: shell commands that only print, `pull`, and `run-as ... cat`. That
    restriction is not incidental — the whole point is to capture the state that
    produced a failure, and any write would destroy the evidence being collected.

    Missing evidence is recorded explicitly rather than silently skipped. A
    permission denial on one item never aborts the run; it is written into the
    manifest as UNAVAILABLE with the
    reason, so a reader can tell "this did not happen" from "this was not
    collected".

.PARAMETER OutputRoot
    Where to create the timestamped bundle directory. Defaults to out\diagnostics.

.PARAMETER Serial
    Target a specific device when more than one is attached (adb -s).

.PARAMETER NoZip
    Leave the directory uncompressed.

.EXAMPLE
    .\tools\collect-device-diagnostics.ps1

.EXAMPLE
    .\tools\collect-device-diagnostics.ps1 -Serial 0123456789ABCDEF
#>
[CmdletBinding()]
param(
    [string] $OutputRoot,
    [string] $Serial,
    [switch] $NoZip
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

# --------------------------------------------------------------- repo layout
$RepoRoot = $PSScriptRoot
while ($RepoRoot -and -not (Test-Path (Join-Path $RepoRoot "gradlew.bat"))) {
    $parent = Split-Path $RepoRoot -Parent
    if ($parent -eq $RepoRoot) { break }
    $RepoRoot = $parent
}
if (-not $RepoRoot) { $RepoRoot = $PSScriptRoot }

if (-not $OutputRoot) { $OutputRoot = Join-Path $RepoRoot "out\diagnostics" }

$stamp     = (Get-Date).ToString("yyyy-MM-dd_HHmmss")
$BundleDir = Join-Path $OutputRoot $stamp
$LogsDir   = Join-Path $BundleDir "logs"
$FilesDir  = Join-Path $BundleDir "files"
New-Item -ItemType Directory -Force -Path $BundleDir, $LogsDir, $FilesDir | Out-Null

$CollectionLog = Join-Path $BundleDir "collection.log"
$Manifest      = [System.Collections.Generic.List[object]]::new()
$PackageName   = "com.schulzcode.y2player"

function Write-Log {
    param([string] $Message, [string] $Level = "INFO")
    $line = "{0} [{1}] {2}" -f (Get-Date).ToString("HH:mm:ss"), $Level, $Message
    Add-Content -Path $CollectionLog -Value $line -Encoding UTF8
    switch ($Level) {
        "WARN"  { Write-Host $line -ForegroundColor Yellow }
        "ERROR" { Write-Host $line -ForegroundColor Red }
        default { Write-Host $line }
    }
}

function Add-Manifest {
    param(
        [string] $Item,
        [ValidateSet("COLLECTED", "EMPTY", "UNAVAILABLE", "DENIED")] [string] $Status,
        [string] $Detail = "",
        [string] $Path = ""
    )
    $Manifest.Add([pscustomobject]@{
        Item = $Item; Status = $Status; Detail = $Detail; Path = $Path
    }) | Out-Null
}

# ------------------------------------------------------------------- adb I/O
$adbArgs = @()
if ($Serial) { $adbArgs += @("-s", $Serial) }

function Test-AdbPresent {
    $command = Get-Command adb -ErrorAction SilentlyContinue
    if (-not $command) { return $null }
    return $command.Source
}

<#
    Runs one adb command and captures stdout, stderr and the exit code.

    Native stderr is redirected rather than allowed to surface as a PowerShell
    error: under $ErrorActionPreference = "Stop", a tool that merely writes a
    warning to stderr would otherwise terminate the whole collection. Every
    caller inspects the returned exit code instead.
#>
function Invoke-Adb {
    param([Parameter(Mandatory)] [string[]] $Arguments)
    $stdoutFile = [System.IO.Path]::GetTempFileName()
    $stderrFile = [System.IO.Path]::GetTempFileName()
    try {
        $process = Start-Process -FilePath "adb" `
            -ArgumentList ($adbArgs + $Arguments) `
            -NoNewWindow -Wait -PassThru `
            -RedirectStandardOutput $stdoutFile -RedirectStandardError $stderrFile
        [pscustomobject]@{
            ExitCode = $process.ExitCode
            StdOut   = (Get-Content $stdoutFile -Raw -ErrorAction SilentlyContinue)
            StdErr   = (Get-Content $stderrFile -Raw -ErrorAction SilentlyContinue)
        }
    } finally {
        Remove-Item $stdoutFile, $stderrFile -Force -ErrorAction SilentlyContinue
    }
}

<#
    Runs a read-only device shell command and saves its output.

    $Command is always a literal from this script. No caller-supplied string is
    ever interpolated into a device shell command.
#>
function Save-ShellOutput {
    param(
        [Parameter(Mandatory)] [string] $Command,
        [Parameter(Mandatory)] [string] $FileName,
        [string] $Item
    )
    if (-not $Item) { $Item = $FileName }
    $target = Join-Path $BundleDir $FileName
    $result = Invoke-Adb @("shell", $Command)

    $body = $result.StdOut
    if ($result.ExitCode -ne 0) {
        $body = "# adb exit code $($result.ExitCode)`n# stderr: $($result.StdErr)`n$body"
    }
    "# command: adb shell $Command" | Set-Content -Path $target -Encoding UTF8
    if ($body) { Add-Content -Path $target -Value $body -Encoding UTF8 }

    if ($result.ExitCode -ne 0) {
        Add-Manifest -Item $Item -Status "UNAVAILABLE" -Detail "exit $($result.ExitCode): $($result.StdErr.Trim())" -Path $FileName
        Write-Log "unavailable: $Item (exit $($result.ExitCode))" "WARN"
    } elseif ([string]::IsNullOrWhiteSpace($result.StdOut)) {
        Add-Manifest -Item $Item -Status "EMPTY" -Detail "command produced no output" -Path $FileName
        Write-Log "empty: $Item"
    } elseif ($result.StdOut -match "Permission denied|Operation not permitted") {
        Add-Manifest -Item $Item -Status "DENIED" -Detail "permission denied on device" -Path $FileName
        Write-Log "denied: $Item (root ADB required)" "WARN"
    } else {
        Add-Manifest -Item $Item -Status "COLLECTED" -Path $FileName
        Write-Log "collected: $Item"
    }
    return $result.StdOut
}

function Save-DevicePull {
    param(
        [Parameter(Mandatory)] [string] $RemotePath,
        [Parameter(Mandatory)] [string] $LocalDirectory,
        [string] $Item
    )
    if (-not $Item) { $Item = $RemotePath }
    New-Item -ItemType Directory -Force -Path $LocalDirectory | Out-Null
    $result = Invoke-Adb @("pull", $RemotePath, $LocalDirectory)
    if ($result.ExitCode -eq 0) {
        Add-Manifest -Item $Item -Status "COLLECTED" -Path (Split-Path $LocalDirectory -Leaf)
        Write-Log "pulled: $RemotePath"
    } else {
        $reason = ($result.StdErr + $result.StdOut).Trim()
        $status = if ($reason -match "Permission denied") { "DENIED" } else { "UNAVAILABLE" }
        Add-Manifest -Item $Item -Status $status -Detail $reason
        Write-Log "could not pull $RemotePath - $reason" "WARN"
    }
}

# =============================================================== preconditions
Write-Host ""
Write-Host "Y2 device diagnostics collection" -ForegroundColor Cyan
Write-Host "Read-only: this script never flashes, reboots, mounts or modifies the device."
Write-Host ""

$adbPath = Test-AdbPresent
if (-not $adbPath) {
    Write-Host "adb was not found on PATH." -ForegroundColor Red
    Write-Host "Install Android platform-tools, or add them to PATH, then retry."
    exit 1
}
Write-Log "adb: $adbPath"

$devices = Invoke-Adb @("devices", "-l")
Set-Content -Path (Join-Path $BundleDir "adb-devices.txt") -Value $devices.StdOut -Encoding UTF8
$deviceLines = @($devices.StdOut -split "`n" | Where-Object { $_ -match "\sdevice(\s|$)" })
if ($deviceLines.Count -eq 0) {
    Write-Host "No device is connected (or it is unauthorized)." -ForegroundColor Red
    Write-Host $devices.StdOut
    Add-Manifest -Item "device" -Status "UNAVAILABLE" -Detail "no device in adb devices"
    exit 1
}
if ($deviceLines.Count -gt 1 -and -not $Serial) {
    Write-Host "More than one device is attached; pass -Serial to choose one." -ForegroundColor Red
    Write-Host $devices.StdOut
    exit 1
}
Add-Manifest -Item "adb devices" -Status "COLLECTED" -Path "adb-devices.txt"
Write-Log "device present"

# Root ADB unlocks protected package and app-log data. Its absence is recorded,
# not worked around: the script never attempts to gain privileges.
$idOutput = (Invoke-Adb @("shell", "id")).StdOut
$isRoot = $idOutput -match "uid=0"
$privilege = if ($isRoot) { " (root)" } else { " (unprivileged)" }
Write-Log ("adb shell identity: " + $idOutput.Trim() + $privilege)

# ========================================================= device identity
Write-Host ""
Write-Host "-- device identity" -ForegroundColor Cyan
Save-ShellOutput -Command "getprop"    -FileName "getprop.txt"  -Item "system properties" | Out-Null
Save-ShellOutput -Command "uname -a"   -FileName "uname.txt"    -Item "kernel version"    | Out-Null
Save-ShellOutput -Command "id"         -FileName "adb-id.txt"   -Item "adb shell identity" | Out-Null
Save-ShellOutput -Command "date"       -FileName "device-date.txt" -Item "device clock"   | Out-Null
Save-ShellOutput -Command "cat /proc/uptime" -FileName "uptime.txt" -Item "uptime"        | Out-Null

# ============================================================= build identity
Write-Host ""
Write-Host "-- build identity" -ForegroundColor Cyan
Save-ShellOutput -Command "dumpsys package $PackageName" -FileName "dumpsys-package.txt" -Item "package info" | Out-Null
Save-ShellOutput -Command "pm path $PackageName"         -FileName "apk-path.txt"        -Item "installed APK path" | Out-Null
# Only the app's own line, never the whole package database.
Save-ShellOutput -Command "grep $PackageName /data/system/packages.list" -FileName "packages-list-entry.txt" -Item "packages.list entry (root only)" | Out-Null

Save-ShellOutput -Command "getprop ro.build.fingerprint" -FileName "firmware-fingerprint.txt" -Item "firmware fingerprint" | Out-Null

# ========================================================== init and processes
Write-Host ""
Write-Host "-- init and process state" -ForegroundColor Cyan
Save-ShellOutput -Command "ps"    -FileName "ps.txt"    -Item "process list" | Out-Null
# ps -Z shows SELinux domains; unsupported on some 4.4 builds, hence recorded
# rather than required.
Save-ShellOutput -Command "ps -Z" -FileName "ps-selinux.txt" -Item "process SELinux contexts" | Out-Null
Save-ShellOutput -Command "getenforce" -FileName "selinux-enforce.txt" -Item "SELinux mode" | Out-Null

# =================================================================== app logs
Write-Host ""
Write-Host "-- application logs" -ForegroundColor Cyan
# The card mirror is readable without any privilege at all — this is the path
# that works on a locked-down device.
Save-DevicePull -RemotePath "/storage/sdcard1/Y2Player/logs/." -LocalDirectory (Join-Path $FilesDir "app-logs-card") -Item "app logs (card mirror)"

# The internal copy is authoritative and survives the card being unmounted.
# run-as works only for a debuggable build; on a release APK this legitimately
# fails and the card mirror plus root ADB are the routes.
$runAsProbe = Invoke-Adb @("shell", "run-as", $PackageName, "ls", "files/logs")
if ($runAsProbe.ExitCode -eq 0 -and $runAsProbe.StdOut -and $runAsProbe.StdOut -notmatch "not debuggable|unknown package") {
    $internalDir = Join-Path $FilesDir "app-logs-internal"
    New-Item -ItemType Directory -Force -Path $internalDir | Out-Null
    foreach ($name in ($runAsProbe.StdOut -split "`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ })) {
        $content = Invoke-Adb @("shell", "run-as", $PackageName, "cat", "files/logs/$name")
        if ($content.ExitCode -eq 0) {
            Set-Content -Path (Join-Path $internalDir $name) -Value $content.StdOut -Encoding UTF8
        }
    }
    Add-Manifest -Item "app logs (internal, run-as)" -Status "COLLECTED" -Path "files/app-logs-internal"
    Write-Log "collected internal app logs via run-as"
} elseif ($isRoot) {
    Save-DevicePull -RemotePath "/data/data/$PackageName/files/logs/." -LocalDirectory (Join-Path $FilesDir "app-logs-internal") -Item "app logs (internal, root)"
} else {
    Add-Manifest -Item "app logs (internal)" -Status "UNAVAILABLE" `
        -Detail "run-as refused (release build) and adb is not root; use the card mirror or 'adb root'"
    Write-Log "internal app logs unavailable: not debuggable and not root" "WARN"
}

# The app's own export directory, if the user ran Export diagnostics.
Save-DevicePull -RemotePath "/storage/sdcard0/Y2Player/diagnostics/." -LocalDirectory (Join-Path $FilesDir "app-export") -Item "app diagnostic export"

# ============================================================= Android logs
Write-Host ""
Write-Host "-- logcat buffers" -ForegroundColor Cyan
foreach ($buffer in @("main", "system", "events", "radio", "crash")) {
    $target = Join-Path $LogsDir "logcat-$buffer.txt"
    # -d dumps and exits; it never clears. -v threadtime keeps timestamps, pid
    # and tid, which is what correlates a logcat line with an NDJSON event.
    $result = Invoke-Adb @("logcat", "-d", "-v", "threadtime", "-b", $buffer)
    if ($result.ExitCode -eq 0 -and $result.StdOut) {
        Set-Content -Path $target -Value $result.StdOut -Encoding UTF8
        Add-Manifest -Item "logcat $buffer" -Status "COLLECTED" -Path "logs/logcat-$buffer.txt"
        Write-Log "collected logcat buffer '$buffer'"
    } else {
        # 'crash' and 'radio' do not exist on every 4.4 build.
        Add-Manifest -Item "logcat $buffer" -Status "UNAVAILABLE" -Detail ($result.StdErr).Trim()
        Write-Log "logcat buffer '$buffer' unavailable on this build" "WARN"
    }
}
# A pre-filtered view for the impatient reader.
$filtered = Invoke-Adb @("logcat", "-d", "-v", "threadtime", "-s",
    "Y2Player:V", "Y2PlayerDb:V", "vold:V", "MountService:V",
    "UsbDeviceManager:V", "ActivityManager:E", "init:V", "auditd:V")
if ($filtered.StdOut) {
    Set-Content -Path (Join-Path $LogsDir "logcat-filtered.txt") -Value $filtered.StdOut -Encoding UTF8
    Add-Manifest -Item "logcat (filtered view)" -Status "COLLECTED" -Path "logs/logcat-filtered.txt"
}

# ================================================== kernel and boot diagnostics
Write-Host ""
Write-Host "-- kernel and boot" -ForegroundColor Cyan
Save-ShellOutput -Command "dmesg"              -FileName "logs/dmesg.txt"      -Item "kernel ring buffer" | Out-Null
# The previous boot's kernel log: the only evidence of a crash-reboot.
Save-ShellOutput -Command "cat /proc/last_kmsg" -FileName "logs/last_kmsg.txt" -Item "previous boot kernel log" | Out-Null
Save-ShellOutput -Command "ls -l /sys/fs/pstore" -FileName "logs/pstore-listing.txt" -Item "pstore records" | Out-Null

# ============================================================ USB diagnostics
Write-Host ""
Write-Host "-- USB state" -ForegroundColor Cyan
Save-ShellOutput -Command "getprop sys.usb.config" -FileName "usb-config.txt" -Item "sys.usb.config" | Out-Null
Save-ShellOutput -Command "getprop sys.usb.state"  -FileName "usb-state.txt"  -Item "sys.usb.state"  | Out-Null
foreach ($node in @(
    @{ Path = "/sys/class/android_usb/android0/state";     File = "usb-node-state.txt" },
    @{ Path = "/sys/class/android_usb/android0/functions"; File = "usb-node-functions.txt" },
    @{ Path = "/sys/class/android_usb/android0/enable";    File = "usb-node-enable.txt" },
    @{ Path = "/sys/class/android_usb/android0/f_mass_storage/lun/file"; File = "usb-node-lun.txt" }
)) {
    # Read only. Nothing in this script ever writes a sysfs node.
    Save-ShellOutput -Command "cat $($node.Path)" -FileName $node.File -Item "sysfs $($node.Path)" | Out-Null
}
Save-ShellOutput -Command "dumpsys usb"     -FileName "dumpsys-usb.txt"     -Item "dumpsys usb"     | Out-Null
Save-ShellOutput -Command "dumpsys mount"   -FileName "dumpsys-mount.txt"   -Item "dumpsys mount"   | Out-Null
Save-ShellOutput -Command "cat /proc/mounts" -FileName "proc-mounts.txt"    -Item "mount table"     | Out-Null
Save-ShellOutput -Command "mount"           -FileName "mount.txt"           -Item "mount output"    | Out-Null
Save-ShellOutput -Command "df"              -FileName "df.txt"              -Item "filesystem usage" | Out-Null
Save-ShellOutput -Command "ls -l /dev/socket/vold" -FileName "ls-vold-socket.txt" -Item "vold socket" | Out-Null
# Storage roots only — never a recursive listing of the user's music.
Save-ShellOutput -Command "ls -l /storage" -FileName "ls-storage.txt" -Item "storage roots" | Out-Null

# ========================================================== power diagnostics
Write-Host ""
Write-Host "-- power" -ForegroundColor Cyan
Save-ShellOutput -Command "dumpsys power"   -FileName "dumpsys-power.txt"   -Item "dumpsys power"   | Out-Null
Save-ShellOutput -Command "dumpsys battery" -FileName "dumpsys-battery.txt" -Item "dumpsys battery" | Out-Null

# ===================================================================== output
Write-Host ""
Write-Host "-- writing manifest" -ForegroundColor Cyan

$manifestPath = Join-Path $BundleDir "diagnostic-manifest.txt"
$header = @(
    "Y2Player device diagnostic bundle",
    "collected        : $((Get-Date).ToString('yyyy-MM-dd HH:mm:ss zzz'))",
    "host             : $env:COMPUTERNAME",
    "adb              : $adbPath",
    "device serial    : $(if ($Serial) { $Serial } else { '(single attached device)' })",
    "adb root         : $isRoot",
    "package          : $PackageName",
    "",
    "This bundle is read-only evidence. Nothing on the device was modified.",
    "",
    "STATUS LEGEND",
    "  COLLECTED   evidence is present in this bundle",
    "  EMPTY       the command ran but produced no output",
    "  DENIED      the device refused access (usually needs 'adb root')",
    "  UNAVAILABLE the item does not exist on this build, or the command failed",
    "",
    ("{0,-11} {1,-46} {2}" -f "STATUS", "ITEM", "DETAIL"),
    ("-" * 100)
)
$rows = $Manifest | ForEach-Object {
    "{0,-11} {1,-46} {2}" -f $_.Status, $_.Item, $_.Detail
}
Set-Content -Path $manifestPath -Value ($header + $rows) -Encoding UTF8

# Checksums over everything collected, so a bundle emailed onward is verifiable.
$checksumPath = Join-Path $BundleDir "checksums.txt"
Get-ChildItem -Path $BundleDir -Recurse -File |
    Where-Object { $_.FullName -ne $checksumPath } |
    ForEach-Object {
        $relative = $_.FullName.Substring($BundleDir.Length + 1)
        "{0}  {1}" -f (Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLower(), $relative
    } | Set-Content -Path $checksumPath -Encoding UTF8

$collected   = @($Manifest | Where-Object { $_.Status -eq "COLLECTED" }).Count
$unavailable = @($Manifest | Where-Object { $_.Status -ne "COLLECTED" }).Count

Write-Host ""
Write-Host "Collected $collected items; $unavailable unavailable or denied (see the manifest)." -ForegroundColor Green

$finalPath = $BundleDir
if (-not $NoZip) {
    $zipPath = "$BundleDir.zip"
    if (Test-Path $zipPath) { Remove-Item $zipPath -Force }
    Compress-Archive -Path (Join-Path $BundleDir "*") -DestinationPath $zipPath
    $finalPath = $zipPath
}

Write-Host ""
Write-Host "Diagnostic bundle:" -ForegroundColor Cyan
Write-Host "  $finalPath"
Write-Host ""
