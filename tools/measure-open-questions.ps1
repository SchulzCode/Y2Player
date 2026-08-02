<#
.SYNOPSIS
    Captures the device evidence needed to close the open feature questions in
    docs/FEATURE_ASSESSMENT_2026-07-31.md section 6.

.DESCRIPTION
    Companion to docs/OPEN_QUESTION_MEASUREMENT_PLAN.md. Collects the input,
    audio-route, database and playback evidence for questions Q1-Q14 into one
    timestamped bundle that can be handed back for analysis.

    THIS SCRIPT NEVER MODIFIES THE DEVICE.

    Same contract as collect-device-diagnostics.ps1: every adb invocation is a
    read - shell commands that only print, `pull`, and `run-as ... cat`. It does
    not flash, push, reboot, clear logcat, change USB mode, mount or unmount, or
    delete anything. It deliberately does NOT use `adb shell input keyevent`:
    injected events carry a virtual device id, so HardwareKeyGate.isLocalKeypad()
    classifies them as remote transport - the opposite of the local key press the
    input questions are trying to measure. Those steps are prompted for and
    performed by hand.

    Missing evidence is recorded as UNAVAILABLE with a reason rather than being
    silently skipped, so a reader can tell "this did not happen" from "this was
    not collected".

.PARAMETER OutputRoot
    Where to create the timestamped bundle. Defaults to out\measurements.

.PARAMETER Serial
    Target a specific device when more than one is attached (adb -s).

.PARAMETER Section
    Which groups to run. Any of: Input, Headset, Database, Playback, Routes, All.
    Defaults to All. Input and Headset are interactive.

.PARAMETER NoZip
    Leave the bundle directory uncompressed.

.EXAMPLE
    .\tools\measure-open-questions.ps1
    Full guided run.

.EXAMPLE
    .\tools\measure-open-questions.ps1 -Section Input
    Just the feature-4 dispatch-order evidence (Q1, Q2).

.NOTES
    PREREQUISITE: Settings -> System -> Diagnostics -> Verbose Diagnostics -> On.
    Y2Player writes almost nothing to logcat; the MediaButtonInput detail this
    script depends on only exists in files/diagnostics/y2player.log, and only
    when verbose diagnostics are enabled. The per-process budget is 512 input
    lines (MediaButtonDiagnosticBudget), so avoid long idle sessions before a
    capture.
#>
[CmdletBinding()]
param(
    [string] $OutputRoot,
    [string] $Serial,
    [ValidateSet("Input", "Headset", "Database", "Playback", "Routes", "All")]
    [string[]] $Section = @("All"),
    [switch] $NoZip
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

<#
    One failed capture must not abandon the rest of the run.

    Observed: a database pull that reported success but produced no file threw a
    FileNotFoundException, which under $ErrorActionPreference = "Stop" aborted
    the script before the Playback and Routes sections ran - losing evidence that
    had nothing to do with the failure. `continue` resumes at the next statement,
    and the failure is recorded in the manifest rather than ending the session.
#>
trap {
    Write-Log "unhandled error: $($_.Exception.Message)" "ERROR"
    Add-Manifest -Item "run (unhandled error)" -Status "UNAVAILABLE" -Detail $_.Exception.Message
    continue
}

$RepoRoot = $PSScriptRoot
while ($RepoRoot -and -not (Test-Path (Join-Path $RepoRoot "gradlew.bat"))) {
    $parent = Split-Path $RepoRoot -Parent
    if ($parent -eq $RepoRoot) { break }
    $RepoRoot = $parent
}
if (-not $RepoRoot) { $RepoRoot = $PSScriptRoot }

if (-not $OutputRoot) { $OutputRoot = Join-Path $RepoRoot "out\measurements" }

$stamp     = (Get-Date).ToString("yyyy-MM-dd_HHmmss")
$BundleDir = Join-Path $OutputRoot $stamp
$LogsDir   = Join-Path $BundleDir "logs"
$FilesDir  = Join-Path $BundleDir "files"
$DbDir     = Join-Path $BundleDir "database"
New-Item -ItemType Directory -Force -Path $BundleDir, $LogsDir, $FilesDir, $DbDir | Out-Null

$CollectionLog = Join-Path $BundleDir "collection.log"
$Manifest      = [System.Collections.Generic.List[object]]::new()
$PackageName   = "com.schulzcode.y2player"
$RunAllSections = $Section -contains "All"

function Write-Log {
    param([string] $Message, [string] $Level = "INFO")
    $line = "{0} [{1}] {2}" -f (Get-Date).ToString("HH:mm:ss"), $Level, $Message
    Add-Content -Path $CollectionLog -Value $line -Encoding UTF8
    switch ($Level) {
        "WARN"  { Write-Host $line -ForegroundColor Yellow }
        "ERROR" { Write-Host $line -ForegroundColor Red }
        "STEP"  { Write-Host $line -ForegroundColor Cyan }
        default { Write-Host $line }
    }
}

function Add-Manifest {
    param(
        [string] $Item,
        [string] $Status,
        [string] $Path = "",
        [string] $Detail = "",
        [string] $Question = ""
    )
    $Manifest.Add([pscustomobject]@{
        question = $Question
        item     = $Item
        status   = $Status
        path     = $Path
        detail   = $Detail
    })
}

function Should-Run {
    param([string] $Name)
    return $RunAllSections -or ($Section -contains $Name)
}

<#
    Null-safe text for manifest details.

    Invoke-Adb returns $null for StdErr when a command wrote nothing to it, and
    under Set-StrictMode calling .Trim() on $null throws "You cannot call a
    method on a null-valued expression" - which is what aborted the Routes
    section of the 2026-07-31 run after 'dumpsys audio'.
#>
function Get-Text {
    param($Value)
    if ($null -eq $Value) { return "" }
    return ([string]$Value).Trim()
}

$adbArgs = @()
if ($Serial) { $adbArgs = @("-s", $Serial) }

<#
    Runs one adb command and captures stdout, stderr and the exit code.

    Deliberately identical in shape to Invoke-Adb in collect-device-diagnostics.ps1:
    Start-Process with temp files, not ProcessStartInfo.ArgumentList. ArgumentList
    on ProcessStartInfo is .NET Core 2.1+ only and does not exist in Windows
    PowerShell 5.1 (.NET Framework), where this script is expected to run.

    Native stderr is redirected rather than allowed to surface as a PowerShell
    error: under $ErrorActionPreference = "Stop", a tool that merely writes a
    warning to stderr would otherwise terminate the whole run. Callers inspect
    the returned exit code instead.
#>
function Invoke-Adb {
    param([Parameter(Mandatory)] [string[]] $Arguments)
    $stdoutFile = [System.IO.Path]::GetTempFileName()
    $stderrFile = [System.IO.Path]::GetTempFileName()
    try {
        $process = Start-Process -FilePath $script:AdbExe `
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
    Runs one read-only device shell command and saves its output. The command is
    a literal from this file; no caller-supplied value is ever interpolated into
    a device shell command.
#>
function Save-ShellOutput {
    param(
        [string] $Command,
        [string] $FileName,
        [string] $Item,
        [string] $Question = "",
        [string] $Directory = $LogsDir
    )
    $target = Join-Path $Directory $FileName
    $result = Invoke-Adb @("shell", $Command)
    $body   = $result.StdOut
    if ($result.ExitCode -ne 0 -or [string]::IsNullOrWhiteSpace($body)) {
        $body = "# adb exit code $($result.ExitCode)`n# stderr: $($result.StdErr)`n$body"
    }
    "# command: adb shell $Command" | Set-Content -Path $target -Encoding UTF8
    Add-Content -Path $target -Value $body -Encoding UTF8
    if ($result.ExitCode -eq 0 -and -not [string]::IsNullOrWhiteSpace($result.StdOut)) {
        Add-Manifest -Item $Item -Status "COLLECTED" -Path $FileName -Question $Question
        Write-Log "collected: $Item"
    }
    else {
        Add-Manifest -Item $Item -Status "UNAVAILABLE" -Detail (Get-Text $result.StdErr) -Question $Question
        Write-Log "unavailable: $Item - $(Get-Text $result.StdErr)" "WARN"
    }
    return $target
}

<#
    Reads an app-private file. Tries run-as first (works on a debuggable build),
    then the removable-card mirror, then records why neither worked. A release
    APK legitimately refuses run-as; that is recorded, not treated as an error.
#>
function Save-AppFile {
    param(
        [string] $RelativePath,
        [string] $FileName,
        [string] $Item,
        [string] $Question = "",
        [string] $MirrorPath = ""
    )
    $target = Join-Path $FilesDir $FileName
    foreach ($package in @($PackageName, "$PackageName.debug")) {
        $result = Invoke-Adb @("shell", "run-as", $package, "cat", $RelativePath)
        $body = $result.StdOut
        # run-as prints its refusal to stdout and still exits 0, so a non-empty
        # stdout is NOT evidence of success. Observed: every app-log capture in
        # the 2026-07-31 run stored the 67-byte string
        # "run-as: Package 'com.schulzcode.y2player' is not debuggable"
        # and was reported as COLLECTED.
        $refused = [string]::IsNullOrWhiteSpace($body) -or
                   $body -match "is not debuggable" -or
                   $body -match "^run-as: " -or
                   $body -match "Package .* is unknown"
        if (-not $refused) {
            Set-Content -Path $target -Value $body -Encoding UTF8
            Add-Manifest -Item $Item -Status "COLLECTED" -Path "files/$FileName" `
                -Detail "via run-as $package" -Question $Question
            Write-Log "collected: $Item (run-as $package)"
            return $target
        }
    }
    if ($MirrorPath) {
        # Same reason as the database pull: old adb returns 0 for a failed pull,
        # so the file's existence is the evidence, not the exit code.
        Invoke-Adb @("pull", $MirrorPath, $target) | Out-Null
        if ((Test-Path $target) -and (Get-Item $target).Length -gt 0) {
            Add-Manifest -Item $Item -Status "COLLECTED" -Path "files/$FileName" `
                -Detail "via card mirror $MirrorPath" -Question $Question
            Write-Log "collected: $Item (card mirror)"
            return $target
        }
    }
    Add-Manifest -Item $Item -Status "UNAVAILABLE" `
        -Detail "run-as refused (release build) and no card mirror; use Export Diagnostics in the app, a debug build, or 'adb root'" `
        -Question $Question
    Write-Log "unavailable: $Item" "WARN"
    return $null
}

<#
    Collects the logs the app itself writes to the removable card.

    This is the supported route on a RELEASE build, where run-as is refused:
      - EventLog mirrors events.ndjson to <card>/Y2Player/logs/ automatically.
      - Settings -> System -> Diagnostics -> Export Diagnostics writes a full
        prose bundle to <card>/Y2Player/diagnostics/.
    Neither needs run-as or root, and both are plain adb pull targets.
#>
function Save-CardLogs {
    param([string] $Question = "")
    $roots = @("/storage/sdcard1", "/storage/sdcard0", "/mnt/sdcard")
    $found = $false
    foreach ($root in $roots) {
        foreach ($sub in @("logs", "diagnostics")) {
            $listing = Invoke-Adb @("shell", "ls $root/Y2Player/$sub")
            if ([string]::IsNullOrWhiteSpace($listing.StdOut) -or
                $listing.StdOut -match "No such file|Permission denied|not found") { continue }
            $localDir = Join-Path $FilesDir "card-$sub"
            New-Item -ItemType Directory -Force -Path $localDir | Out-Null
            Invoke-Adb @("pull", "$root/Y2Player/$sub", $localDir) | Out-Null
            $count = (Get-ChildItem $localDir -Recurse -File -ErrorAction SilentlyContinue |
                      Measure-Object).Count
            if ($count -gt 0) {
                Add-Manifest -Item "app $sub from card ($root)" -Status "COLLECTED" `
                    -Path "files/card-$sub" -Detail "$count files" -Question $Question
                Write-Log "collected: app $sub from $root ($count files)"
                $found = $true
            }
        }
        if ($found) { break }
    }
    if (-not $found) {
        Add-Manifest -Item "app logs from card" -Status "UNAVAILABLE" `
            -Detail "no Y2Player/logs or Y2Player/diagnostics on any card root - run Export Diagnostics in the app first" `
            -Question $Question
        Write-Log "unavailable: app logs from card - run Export Diagnostics in the app" "WARN"
    }
    return $found
}

<#
    Captures logcat for a fixed window while the operator performs a physical
    action. logcat is NOT cleared - clearing would destroy evidence that the
    diagnostic bundle may need, and the timestamps make the window findable.
#>
function Capture-Logcat {
    param(
        [string] $FileName,
        [string] $Item,
        [int] $Seconds = 20,
        [string[]] $Tags = @(),
        [string] $Question = ""
    )
    $target = Join-Path $LogsDir $FileName
    $arguments = $adbArgs + @("logcat", "-v", "threadtime")
    if ($Tags.Count -gt 0) { $arguments += ($Tags + "*:S") }

    Write-Log "capturing logcat for $Seconds s -> $FileName" "STEP"
    $process = Start-Process -FilePath $script:AdbExe -ArgumentList $arguments `
        -RedirectStandardOutput $target -NoNewWindow -PassThru
    Start-Sleep -Seconds $Seconds
    if (-not $process.HasExited) { Stop-Process -Id $process.Id -Force }

    if ((Test-Path $target) -and (Get-Item $target).Length -gt 0) {
        Add-Manifest -Item $Item -Status "COLLECTED" -Path "logs/$FileName" -Question $Question
        Write-Log "collected: $Item"
    }
    else {
        Add-Manifest -Item $Item -Status "EMPTY" `
            -Detail "no matching lines - if this was a Y2Input capture, probe A is not present in the running build" `
            -Question $Question
        Write-Log "empty: $Item" "WARN"
    }
    return $target
}

<#
    Pulls a BINARY stream off the device without writing anything to it.

    Uses `adb exec-out`, which returns the raw stream with no PTY line-ending
    translation. `adb shell ... cat` of a binary is corrupted on Windows because
    the shell service translates LF to CRLF, which silently produces an
    unopenable SQLite file - the failure only shows up much later as
    "file is not a database".

    Start-Process redirection writes the child's stdout to the file byte-for-byte,
    so nothing passes through PowerShell string encoding either.
#>
function Save-BinaryStream {
    param(
        [string[]] $Arguments,
        [string] $TargetPath,
        [string] $Item,
        [string] $Question = ""
    )
    if ($Arguments[0] -eq "exec-out" -and -not $script:HasExecOut) {
        Add-Manifest -Item $Item -Status "UNAVAILABLE" `
            -Detail "adb $script:AdbVersion has no exec-out; needs platform-tools r24+ (this repo vendors r28.0.2)" `
            -Question $Question
        Write-Log "skipped: $Item - adb too old for exec-out" "WARN"
        return $false
    }
    $stderrFile = [System.IO.Path]::GetTempFileName()
    try {
        $process = Start-Process -FilePath $script:AdbExe `
            -ArgumentList ($adbArgs + $Arguments) `
            -NoNewWindow -Wait -PassThru `
            -RedirectStandardOutput $TargetPath -RedirectStandardError $stderrFile
        $size = if (Test-Path $TargetPath) { (Get-Item $TargetPath).Length } else { 0 }
        if ($process.ExitCode -eq 0 -and $size -gt 0) {
            Add-Manifest -Item $Item -Status "COLLECTED" `
                -Path (Split-Path $TargetPath -Leaf) -Detail "$size bytes" -Question $Question
            Write-Log "collected: $Item ($size bytes)"
            return $true
        }
        $reason = (Get-Content $stderrFile -Raw -ErrorAction SilentlyContinue)
        Add-Manifest -Item $Item -Status "UNAVAILABLE" -Detail (Get-Text $reason) -Question $Question
        Write-Log "unavailable: $Item - $(Get-Text $reason)" "WARN"
        if (Test-Path $TargetPath) { Remove-Item $TargetPath -Force -ErrorAction SilentlyContinue }
        return $false
    } finally {
        Remove-Item $stderrFile -Force -ErrorAction SilentlyContinue
    }
}

function Read-Step {
    param([string] $Instruction, [string] $Detail = "")
    Write-Host ""
    Write-Host "  ACTION REQUIRED" -ForegroundColor Yellow
    Write-Host "  $Instruction" -ForegroundColor White
    if ($Detail) { Write-Host "  $Detail" -ForegroundColor DarkGray }
    Read-Host "  Press Enter when ready"
}

Write-Host ""
Write-Host "Y2 open-question measurement" -ForegroundColor Cyan
Write-Host "Read-only. logcat is never cleared; nothing is pushed or modified." -ForegroundColor DarkGray
Write-Host ""

<#
    adb discovery, NEWEST FIRST.

    Deliberately not "PATH first". A very old adb on PATH is worse than useless
    here: 1.0.31 (Android SDK r19 era, which this repo also vendors) has no
    `exec-out`, so every binary capture degrades into adb printing its own help
    text, and it returns exit code 0 for a `pull` that actually failed - so a
    missing file looks like a successful collection. Both were observed on this
    machine.

    The repo vendors platform-tools r19.0.1, r23.1 and r28.0.2 under
    build\toolchains; r28's adb (1.0.40) supports exec-out and reports honest
    exit codes, so the highest available release is preferred and the resolved
    version is recorded in the manifest.
#>
$AdbCandidates = @()
$vendoredRoot = Join-Path $RepoRoot "build\toolchains"
if (Test-Path $vendoredRoot) {
    $AdbCandidates += Get-ChildItem -Path $vendoredRoot -Filter "adb.exe" -Recurse -ErrorAction SilentlyContinue |
        Sort-Object {
            if ($_.FullName -match 'platform-tools-r(\d+)') { [int]$Matches[1] } else { 0 }
        } -Descending |
        Select-Object -ExpandProperty FullName
}
$onPath = Get-Command adb -ErrorAction SilentlyContinue
if ($onPath) { $AdbCandidates += $onPath.Source }

$AdbExe = $null
$AdbVersion = ""
foreach ($candidate in $AdbCandidates) {
    $probe = & $candidate version 2>&1 | Out-String
    if ($probe -match "version (\d+\.\d+\.\d+)") {
        $AdbExe = $candidate
        $AdbVersion = $Matches[1]
        break
    }
}
if (-not $AdbExe) {
    Write-Host "No usable adb found on PATH or under build\toolchains." -ForegroundColor Red
    Write-Host "Install Android platform-tools, or run tools\build-adb-boot.ps1 to fetch them." -ForegroundColor Red
    exit 1
}
Write-Log "using adb $AdbVersion : $AdbExe"
Add-Manifest -Item "adb client" -Status "COLLECTED" -Detail "$AdbVersion at $AdbExe"

# exec-out arrived in adb 1.0.32. Without it, binary captures are impossible
# without writing a temp file to the device, which this script will not do.
$script:HasExecOut = $false
if ($AdbVersion -match "^(\d+)\.(\d+)\.(\d+)$") {
    $build = [int]$Matches[3]
    $script:HasExecOut = ($build -ge 32)
}
if (-not $script:HasExecOut) {
    Write-Log "adb $AdbVersion has no 'exec-out'; binary captures (database, screenshot) will be skipped" "WARN"
}

$devices = Invoke-Adb @("devices", "-l")
Set-Content -Path (Join-Path $BundleDir "adb-devices.txt") -Value $devices.StdOut -Encoding UTF8
if ($devices.StdOut -notmatch "\sdevice(\s|$)") {
    Write-Log "no device in 'adb devices' - connect the Y2 and authorise the host" "ERROR"
    Add-Manifest -Item "device" -Status "UNAVAILABLE" -Detail "no device attached"
    $Manifest | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $BundleDir "manifest.json") -Encoding UTF8
    exit 1
}
Add-Manifest -Item "adb devices" -Status "COLLECTED" -Path "adb-devices.txt"
Write-Log "device attached"

Write-Host ""
Write-Host "  PREREQUISITE" -ForegroundColor Yellow
Write-Host "  Settings -> System -> Diagnostics -> Verbose Diagnostics must be ON." -ForegroundColor White
Write-Host "  Without it the MediaButtonInput lines this run depends on are not recorded." -ForegroundColor DarkGray
Read-Host "  Press Enter to confirm it is on"

Save-ShellOutput -Command "getprop ro.build.version.release" -FileName "build-release.txt" -Item "android version" | Out-Null
Save-ShellOutput -Command "getprop ro.build.display.id"      -FileName "build-display.txt" -Item "firmware build" | Out-Null
Save-ShellOutput -Command "date"                             -FileName "device-clock.txt"  -Item "device clock" -Question "Q8" | Out-Null
Save-ShellOutput -Command "dumpsys window"                   -FileName "dumpsys-window.txt" -Item "display metrics" -Question "A6" | Out-Null
Save-ShellOutput -Command "dumpsys package $PackageName"     -FileName "dumpsys-package.txt" -Item "installed package" | Out-Null

if (Should-Run "Input") {
    Write-Log "=== Section: Input (Q1 dispatch order, Q2 key repeats)" "STEP"

    Save-ShellOutput -Command "dumpsys input" -FileName "dumpsys-input.txt" `
        -Item "input device table" -Question "Q1,Q3" | Out-Null
    Save-ShellOutput -Command "getevent -p" -FileName "getevent-capabilities.txt" `
        -Item "input device capabilities" -Question "Q3" | Out-Null

    Read-Step "Open Y2Player, load a track, and PAUSE it. Navigate to Settings." `
              "The capture starts as soon as you press Enter."

    Write-Host ""
    Write-Host "  During the next 25 seconds:" -ForegroundColor White
    Write-Host "   1. PRESS AND HOLD the physical Play button for ~2 s. Release." -ForegroundColor White
    Write-Host "   2. Wait ~5 s." -ForegroundColor White
    Write-Host "   3. Repeat twice more." -ForegroundColor White
    Write-Host "  Do NOT use adb input keyevent - it injects a virtual device id" -ForegroundColor DarkGray
    Write-Host "  and would be classified as a remote headset command, not a local key." -ForegroundColor DarkGray
    Capture-Logcat -FileName "q1-longpress-play.txt" -Item "Q1 long-press Play (Y2Input probe)" `
        -Seconds 25 -Tags @("Y2Input:I") -Question "Q1,Q2" | Out-Null

    Read-Step "Now press the Play button THREE TIMES with normal short presses." `
              "This gives the short-press baseline to compare the hold against."
    Capture-Logcat -FileName "q1-shortpress-play.txt" -Item "Q1 short-press Play (Y2Input probe)" `
        -Seconds 15 -Tags @("Y2Input:I") -Question "Q1" | Out-Null

    # The app-side record of both paths, whether or not probe A is present.
    Save-AppFile -RelativePath "files/diagnostics/y2player.log" -FileName "y2player-input.log" `
        -Item "prose log (MediaButtonInput lines)" -Question "Q1,Q2,Q4,Q6" | Out-Null
    Save-AppFile -RelativePath "files/logs/events.ndjson" -FileName "events-input.ndjson" `
        -Item "structured events (reducer actions)" -Question "Q1,A1,A2" `
        -MirrorPath "/storage/sdcard1/Y2Player/logs/events.ndjson" | Out-Null
}

if (Should-Run "Headset") {
    Write-Log "=== Section: Headset (Q3 hook hardware, Q4 routing, Q6 screen-off)" "STEP"

    Read-Step "UNPLUG everything from the 3.5 mm jack."
    Save-ShellOutput -Command "cat /sys/class/switch/h2w/state" -FileName "q3-h2w-unplugged.txt" `
        -Item "h2w state, nothing plugged" -Question "Q3" | Out-Null

    Read-Step "Plug in ORDINARY 3-POLE HEADPHONES (no inline button)."
    Save-ShellOutput -Command "cat /sys/class/switch/h2w/state" -FileName "q3-h2w-3pole.txt" `
        -Item "h2w state, 3-pole headphones" -Question "Q3" | Out-Null

    Read-Step "Plug in a 4-POLE HEADSET WITH AN INLINE BUTTON." `
              "Use one you have confirmed works on a phone. This is the whole question."
    Save-ShellOutput -Command "cat /sys/class/switch/h2w/state" -FileName "q3-h2w-4pole.txt" `
        -Item "h2w state, 4-pole headset" -Question "Q3" | Out-Null
    Save-ShellOutput -Command "dumpsys audio" -FileName "q3-dumpsys-audio.txt" `
        -Item "audio route state with headset" -Question "Q3" | Out-Null
    Save-ShellOutput -Command "getevent -p" -FileName "q3-getevent-capabilities-headset.txt" `
        -Item "input capabilities with headset attached" -Question "Q3" | Out-Null

    Write-Host ""
    Write-Host "  During the next 25 seconds, press the INLINE BUTTON:" -ForegroundColor White
    Write-Host "   1. one short press, wait 5 s" -ForegroundColor White
    Write-Host "   2. two quick presses, wait 5 s" -ForegroundColor White
    Write-Host "   3. three quick presses" -ForegroundColor White
    Capture-Logcat -FileName "q3-getevent-raw.txt" -Item "Q3 raw input events on inline button" `
        -Seconds 25 -Question "Q3,Q4" | Out-Null

    Write-Log "note: for a raw kernel view run 'adb shell getevent -lt' manually and press the button" "WARN"

    Read-Step "Turn the SCREEN OFF (do not enable 'Wheel When Screen Off'), then press the inline button twice."
    Save-AppFile -RelativePath "files/diagnostics/y2player.log" -FileName "y2player-headset.log" `
        -Item "prose log after headset tests (look for rejected=screen_gate)" -Question "Q4,Q6" | Out-Null
}

if (Should-Run "Database") {
    Write-Log "=== Section: Database (Q7 bitrate coverage, Q12 backup space, Q13 id stability)" "STEP"

    # exec-out keeps the stream binary-clean; `adb shell ... cat` would be
    # LF->CRLF translated on Windows and produce an unopenable SQLite file.
    $pulled = $false
    foreach ($package in @($PackageName, "$PackageName.debug")) {
        $pulled = Save-BinaryStream `
            -Arguments @("exec-out", "run-as", $package, "cat", "databases/y2player.db") `
            -TargetPath (Join-Path $DbDir "y2player.db") `
            -Item "library database (run-as $package)" -Question "Q7,Q13"
        if ($pulled) { break }
    }
    $dbPath = Join-Path $DbDir "y2player.db"
    if (-not $pulled) {
        # NOTE: adb 1.0.31 returns exit code 0 for a pull that failed with
        # "permission denied", so the exit code alone is not evidence. The file
        # itself is. Observed on this machine: "collected via adb pull" followed
        # by a FileNotFoundException.
        Write-Log "run-as unavailable; trying 'adb pull' in case adbd runs as root" "WARN"
        Invoke-Adb @("pull", "/data/data/$PackageName/databases/y2player.db", $dbPath) | Out-Null
        if ((Test-Path $dbPath) -and (Get-Item $dbPath).Length -gt 0) {
            Add-Manifest -Item "library database" -Status "COLLECTED" -Path "database/y2player.db" `
                -Detail "via adb pull (rooted adbd)" -Question "Q7,Q13"
            Write-Log "collected: library database (adb pull)"
            $pulled = $true
        }
    }
    if (-not $pulled) {
        Add-Manifest -Item "library database" -Status "UNAVAILABLE" `
            -Detail "not readable: needs the debug build (run-as) or 'adb root', and adb r24+ for exec-out" `
            -Question "Q7,Q13"
        Write-Log "unavailable: library database - see manifest" "WARN"
    }
    elseif (Test-Path $dbPath) {
        # Cheap integrity check so a truncated or CRLF-mangled pull is caught
        # now rather than as "file is not a database" on the host later.
        $bytes = [System.IO.File]::ReadAllBytes($dbPath)
        $magic = if ($bytes.Length -ge 15) {
            [System.Text.Encoding]::ASCII.GetString($bytes[0..14])
        } else { "<file too short: $($bytes.Length) bytes>" }
        if ($magic -notlike "SQLite format 3*") {
            Write-Log "pulled database does not start with the SQLite magic - treat as corrupt" "ERROR"
            Add-Manifest -Item "library database integrity" -Status "UNAVAILABLE" `
                -Detail "header was '$magic', expected 'SQLite format 3'" -Question "Q7,Q13"
        }
        else {
            Write-Log "database header OK (SQLite format 3)"
            Add-Manifest -Item "library database integrity" -Status "COLLECTED" -Detail "SQLite format 3" -Question "Q7,Q13"
        }
    }

    Save-ShellOutput -Command "df /data" -FileName "q12-data-free-space.txt" `
        -Item "free space on /data (migration backup headroom)" -Question "Q12" | Out-Null
    Save-ShellOutput -Command "ls -l /data/data/$PackageName/databases" -FileName "q12-db-sizes.txt" `
        -Item "database file sizes" -Question "Q12" | Out-Null

    Write-Host ""
    Write-Host "  Host-side follow-up once the database is pulled:" -ForegroundColor DarkGray
    Write-Host "    sqlite3 database\y2player.db `"pragma user_version;`"" -ForegroundColor DarkGray
    Write-Host "    sqlite3 database\y2player.db `"select codec, count(*), sum(bitrate is null), avg(bitrate) from tracks group by codec;`"" -ForegroundColor DarkGray
}

if (Should-Run "Playback") {
    Write-Log "=== Section: Playback (Q8 audiobook trees, Q9 crossfade, Q11 write cost)" "STEP"

    Save-ShellOutput -Command "ls -R /storage/sdcard1" `
        -FileName "q8-tree-sdcard.txt" -Item "card directory tree (grep AUDIOBOOKS host-side)" -Question "Q8" | Out-Null
    Save-ShellOutput -Command "ls -R /storage/sdcard0" `
        -FileName "q8-tree-internal.txt" -Item "internal directory tree (grep AUDIOBOOKS host-side)" -Question "Q8" | Out-Null
    
    Read-Step "Set Crossfade to 4 seconds, then play an album and let ONE track end naturally." `
              "Do not skip - the transition must happen by itself."
    Save-AppFile -RelativePath "files/diagnostics/y2player.log" -FileName "y2player-crossfade.log" `
        -Item "prose log (crossfade begins / ownership promoted)" -Question "Q9,A1,A2" | Out-Null
    Save-AppFile -RelativePath "files/logs/events.ndjson" -FileName "events-playback.ndjson" `
        -Item "structured playback events" -Question "Q9,A1,A2,A5" `
        -MirrorPath "/storage/sdcard1/Y2Player/logs/events.ndjson" | Out-Null

    Save-ShellOutput -Command "ps" -FileName "q11-ps.txt" -Item "process list (find the Y2Player pid host-side)" -Question "Q11" | Out-Null

    Read-Step "Open Now Playing on a track with a LONG album name, then press Enter." `
              "A screenshot is taken for the feature-6 layout check."
    # exec-out streams the PNG straight to the host. The obvious alternative -
    # `screencap -p /sdcard/x.png` then `adb pull` - would write a file to the
    # device, which this script promises not to do.
    Save-BinaryStream -Arguments @("exec-out", "screencap", "-p") `
        -TargetPath (Join-Path $FilesDir "q10-nowplaying.png") `
        -Item "Now Playing screenshot" -Question "Q10" | Out-Null
}

if (Should-Run "Routes") {
    Write-Log "=== Section: Routes (A3 lock screen, A4 Bluetooth)" "STEP"

    Save-ShellOutput -Command "dumpsys power"   -FileName "a3-dumpsys-power.txt"   -Item "power / screen state" -Question "A3" | Out-Null
    Save-ShellOutput -Command "dumpsys audio"   -FileName "a4-dumpsys-audio.txt"   -Item "audio policy routes"  -Question "A4" | Out-Null
    Save-ShellOutput -Command "dumpsys bluetooth_manager" -FileName "a4-dumpsys-bluetooth.txt" -Item "bluetooth state" -Question "A4" | Out-Null
    Save-ShellOutput -Command "dumpsys media.audio_flinger" -FileName "a4-audioflinger.txt" -Item "audio flinger outputs" -Question "A4" | Out-Null

    Read-Step "With the screen LOCKED/OFF, press the wheel and the Play button a few times."
    Save-AppFile -RelativePath "files/diagnostics/y2player.log" -FileName "y2player-screenoff.log" `
        -Item "prose log (look for rejected=screen_gate)" -Question "A3,Q6" | Out-Null
}

# Done last so the export contains this whole session. On a RELEASE build this
# is the ONLY way to get the app's own logs: run-as is refused, and the
# 2026-07-31 run proved that silently - every "prose log" file in that bundle
# was the 67-byte string "Package ... is not debuggable".
Write-Host ""
Write-Host "  ACTION REQUIRED - this is what makes Q1/Q2/Q4/Q6/Q9 answerable" -ForegroundColor Yellow
Write-Host "  In Y2Player: Settings -> System -> Diagnostics -> Export Diagnostics" -ForegroundColor White
Write-Host "  It writes the prose log and event log to the card, where adb can read" -ForegroundColor DarkGray
Write-Host "  them without run-as or root." -ForegroundColor DarkGray
Read-Host "  Press Enter once the export has finished"
Save-CardLogs -Question "Q1,Q2,Q4,Q6,Q9,A1,A2,A5" | Out-Null

$Manifest | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $BundleDir "manifest.json") -Encoding UTF8

$summary = @(
    "Y2 open-question measurement bundle",
    "Created        : $stamp",
    "Sections       : $($Section -join ', ')",
    "Package        : $PackageName",
    "Plan           : docs/OPEN_QUESTION_MEASUREMENT_PLAN.md",
    "",
    "Status values:",
    "  COLLECTED   captured successfully",
    "  EMPTY       ran, but produced no matching lines (see detail)",
    "  UNAVAILABLE could not be captured (see detail)",
    "",
    "If every Y2Input capture is EMPTY, probe A (see the plan, section 3) is not",
    "present in the installed build. Q1 can still be answered offline by",
    "correlating files/y2player-input.log against files/events-input.ndjson, but",
    "adding the probe makes it a single-glance answer.",
    ""
) -join "`r`n"
Set-Content -Path (Join-Path $BundleDir "README.txt") -Value $summary -Encoding UTF8

Write-Host ""
Write-Log "bundle written to $BundleDir"
$Manifest | Group-Object status | ForEach-Object {
    Write-Log ("  {0,-12} {1}" -f $_.Name, $_.Count)
}

if (-not $NoZip) {
    $zip = "$BundleDir.zip"
    Compress-Archive -Path (Join-Path $BundleDir "*") -DestinationPath $zip -Force
    Write-Log "compressed to $zip"
}

Write-Host ""
Write-Host "The bundle is inside the repo, so no upload is needed." -ForegroundColor Cyan
Write-Host ""
Write-Host "What actually carries the evidence (2026-07-31 sessions):" -ForegroundColor Cyan
Write-Host "  files\card-logs\logs\events*.ndjson  - the structured event log."   -ForegroundColor Gray
Write-Host "                                         Closes Q1, A1, A2, A4."      -ForegroundColor Gray
Write-Host "  logs\getevent-capabilities.txt       - input device key tables."    -ForegroundColor Gray
Write-Host "                                         Closes Q3 and the button map." -ForegroundColor Gray
Write-Host "  logs\q3-h2w-*.txt                    - headset detection state."    -ForegroundColor Gray
Write-Host "  logs\dumpsys-window.txt              - display metrics (A6)."       -ForegroundColor Gray
Write-Host ""
Write-Host "Release builds are minified, so 'action' and 'screen' in the event" -ForegroundColor DarkGray
Write-Host "log are obfuscated. Decode them with:" -ForegroundColor DarkGray
Write-Host "  app\build\outputs\mapping\release\mapping.txt" -ForegroundColor DarkGray
Write-Host "from the SAME build that produced the log." -ForegroundColor DarkGray
Write-Host ""
Write-Host "The Y2Input captures are only useful once probe A exists in the" -ForegroundColor DarkGray
Write-Host "installed build; without it they are logcat headers and nothing else." -ForegroundColor DarkGray
Write-Host ""
