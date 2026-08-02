<#
.SYNOPSIS
    Live, read-only log view for Y2Player and stock storage/USB behavior.

.DESCRIPTION
    Follows logcat without clearing it. No command in this script changes the
    device, its USB mode, mounts, files, or processes.
#>
[CmdletBinding(DefaultParameterSetName = "Default")]
param(
    [Parameter(ParameterSetName = "All")] [switch] $All,
    [Parameter(ParameterSetName = "Usb")] [switch] $Usb,
    [Parameter(ParameterSetName = "App")] [switch] $App,
    [string] $Serial,
    [string] $Tee
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    Write-Host "adb was not found on PATH." -ForegroundColor Red
    exit 1
}

$adbArgs = @()
if ($Serial) { $adbArgs += @("-s", $Serial) }
$tagSets = @{
    Usb = @("vold:V", "MountService:V", "StorageManager:V", "UsbDeviceManager:V",
        "UsbSettingsManager:V", "MediaProvider:V", "auditd:V", "*:S")
    # Y2Player keeps its diagnostics in files, not logcat: the only tags it
    # actually emits are Y2PlayerDb (database faults) and, in debug builds,
    # Y2Input (the temporary key probe). Filtering on a "Y2Player" tag returned
    # an empty view and looked like a broken device.
    # For app diagnostics use Export Diagnostics, or read
    # <card>/Y2Player/logs/events.ndjson.
    App = @("Y2PlayerDb:V", "Y2Input:V", "AndroidRuntime:E", "ActivityManager:E", "*:S")
    Default = @("Y2PlayerDb:V", "Y2Input:V", "vold:V", "MountService:V",
        "StorageManager:V", "UsbDeviceManager:V", "MediaProvider:V",
        "AndroidRuntime:E", "ActivityManager:E", "auditd:V", "*:S")
}
$mode = if ($All) { "All" } elseif ($Usb) { "Usb" } elseif ($App) { "App" } else { "Default" }
$logcatArgs = @("logcat", "-v", "threadtime")
if ($mode -ne "All") { $logcatArgs += $tagSets[$mode] }

Write-Host "`nY2 live log view - mode: $mode" -ForegroundColor Cyan
Write-Host "Read-only; logcat is not cleared. Press Ctrl+C to stop.`n" -ForegroundColor DarkGray
if ($Tee) {
    $parent = Split-Path $Tee -Parent
    if ($parent -and -not (Test-Path $parent)) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
}

function Get-LineColour {
    param([string] $Line)
    switch -Regex ($Line) {
        "avc:\s+denied"       { return "Magenta" }
        "bad_removal|unmounted|eject" { return "Magenta" }
        "\s+E\s+|FATAL|fatal" { return "Red" }
        "\s+W\s+|warn"       { return "Yellow" }
        "mounted|scan_complete" { return "Green" }
        "Y2Player"            { return "White" }
        default               { return "Gray" }
    }
}

try {
    & adb @adbArgs @logcatArgs 2>&1 | ForEach-Object {
        $line = [string] $_
        Write-Host $line -ForegroundColor (Get-LineColour $line)
        if ($Tee) { Add-Content -Path $Tee -Value $line -Encoding UTF8 }
    }
} catch [System.Management.Automation.PipelineStoppedException] {
} finally {
    Write-Host "`nStopped watching. The device log buffer was not cleared." -ForegroundColor DarkGray
}
