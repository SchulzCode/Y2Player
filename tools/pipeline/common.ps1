# Shared helpers for the Y2Player firmware pipeline.
# Dot-sourced by tools/build-firmware.ps1; not meant to be run directly.

Set-StrictMode -Version Latest

$script:CurrentStage = "startup"
$script:BuildLogPath = $null
$script:TranscriptActive = $false

function Write-Stage {
    param([Parameter(Mandatory = $true)][string]$Name)
    $script:CurrentStage = $Name
    Write-Host ""
    Write-Host "=== $Name ===" -ForegroundColor Cyan
}

function Write-Detail {
    param([Parameter(Mandatory = $true)][string]$Message)
    Write-Host "      $Message"
}

function Start-BuildLog {
    param([Parameter(Mandatory = $true)][string]$Path)
    try {
        Start-Transcript -Path $Path -Force | Out-Null
        $script:TranscriptActive = $true
    }
    catch {
        # A transcript is useful, not essential; never fail the build over it.
        Write-Warning "Could not start the build log at ${Path}: $($_.Exception.Message)"
    }
}

function Stop-BuildLog {
    if ($script:TranscriptActive) {
        try { Stop-Transcript | Out-Null } catch { }
        $script:TranscriptActive = $false
    }
}

<#
    External commands do not raise terminating errors in PowerShell, so every
    invocation must have its exit code checked explicitly or a failed Gradle run
    would silently continue into image packaging.
#>
function Assert-ExitCode {
    param(
        [Parameter(Mandatory = $true)][AllowNull()][int]$Code,
        [Parameter(Mandatory = $true)][string]$Stage
    )
    if ($Code -ne 0) {
        throw "$Stage failed with exit code $Code."
    }
}

<#
    Runs an external program safely under $ErrorActionPreference = "Stop".

    Windows PowerShell 5.1 turns anything a native program writes to stderr into
    an ErrorRecord, and with EAP=Stop that becomes a *terminating* error — even
    when the program succeeded. `java -version`, Gradle and bash all write
    perfectly normal output to stderr, so invoking them directly would abort the
    build for no reason. Success is therefore judged the only way that is
    meaningful for a process: its exit code.
#>
function Invoke-Native {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [string[]]$Arguments = @(),
        [Parameter(Mandatory = $true)][string]$Stage,

        # Return the combined output instead of streaming it to the console.
        [switch]$Capture,

        # Report the exit code to the caller rather than throwing on failure.
        [switch]$AllowFailure,

        <#
            Re-emit each line through Write-Host while still streaming live.

            Linux programs run through wsl.exe terminate lines with LF only. The
            Windows console does not treat that as a carriage return, so output
            marches diagonally across the screen and later PowerShell writes
            inherit the broken column. Passing each line through Write-Host
            restores proper CRLF endings without buffering the run.
        #>
        [switch]$NormalizeLineEndings
    )

    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $output = @()
    try {
        if ($Capture) {
            $output = & $FilePath @Arguments 2>&1 | ForEach-Object { $_.ToString() }
        }
        elseif ($NormalizeLineEndings) {
            # Lines a native program wrote to stderr arrive as ErrorRecords whose
            # ToString() is the exception type, not the text. Reach for the
            # original string so the console shows the program's own message.
            & $FilePath @Arguments 2>&1 | ForEach-Object {
                if ($_ -is [System.Management.Automation.ErrorRecord]) {
                    if ($null -ne $_.TargetObject) { Write-Host ([string]$_.TargetObject) }
                    else { Write-Host $_.Exception.Message }
                }
                else {
                    Write-Host ([string]$_)
                }
            }
        }
        else {
            & $FilePath @Arguments
        }
        $code = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previous
    }
    if ($null -eq $code) { $code = 0 }

    if (-not $AllowFailure) {
        Assert-ExitCode -Code $code -Stage $Stage
    }
    if ($Capture) {
        return [pscustomobject]@{ ExitCode = $code; Output = $output }
    }
    return [pscustomobject]@{ ExitCode = $code; Output = @() }
}

<#
    Converts a Windows path to the form WSL sees. Uses wslpath when available
    (it handles UNC paths, mapped drives and mount-point configuration) and
    falls back to the standard /mnt/<drive> convention.
#>
function ConvertTo-WslPath {
    param([Parameter(Mandatory = $true)][string]$WindowsPath)

    $full = [System.IO.Path]::GetFullPath($WindowsPath)

    # Forward slashes, not backslashes: wsl.exe's own command-line parsing eats
    # backslashes before wslpath ever sees them, turning C:\Users\Developer into
    # C:UsersDeveloper. wslpath accepts C:/Users/Developer just as happily.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $converted = & wsl.exe wslpath -a -u ($full -replace '\\', '/') 2>$null
        if ($LASTEXITCODE -eq 0 -and $converted) {
            return ($converted | Select-Object -First 1).Trim()
        }
    }
    catch {
        # fall through to the manual conversion below
    }
    finally {
        $ErrorActionPreference = $previous
    }

    if ($full -match '^([A-Za-z]):\\(.*)$') {
        $drive = $Matches[1].ToLowerInvariant()
        $rest = $Matches[2] -replace '\\', '/'
        return "/mnt/$drive/$rest"
    }
    throw "Cannot convert '$WindowsPath' to a WSL path. UNC paths are not supported; build from a local drive."
}

<#
    Locates the built debug APK without hardcoding a filename: Gradle's output
    name changes with flavour and suffix configuration, and picking the wrong
    file would integrate the wrong build into the image.
#>
function Find-DebugApk {
    param([Parameter(Mandatory = $true)][string]$Root)

    $searchRoot = Join-Path $Root "app\build\outputs\apk"
    if (-not (Test-Path $searchRoot)) { return $null }

    $candidates = Get-ChildItem -Path $searchRoot -Recurse -Filter "*.apk" -File |
        Where-Object { $_.Name -notlike "*unsigned*" -and $_.FullName -match '\\debug\\' } |
        Sort-Object LastWriteTime -Descending

    if (-not $candidates) { return $null }
    return $candidates[0].FullName
}

<#
    Derives the build identity shared by every artifact.

    One value stamped into the APK and build-manifest.txt, so a log
    line collected from a device six months later identifies exactly which
    artifacts produced it. The dirty marker is not cosmetic: a build made from
    an unclean working tree cannot be reproduced from its commit, and a log that
    silently claims a clean commit would be actively misleading.
#>
function Get-BuildId {
    param([Parameter(Mandatory = $true)][string]$Root)

    $commit = "nogit"
    $dirty = ""
    $git = Get-Command git -ErrorAction SilentlyContinue
    if ($git) {
        $described = Invoke-Native -FilePath "git" `
            -Arguments @("-C", $Root, "rev-parse", "--short=12", "HEAD") `
            -Stage "git commit" -Capture -AllowFailure
        if ($described.ExitCode -eq 0 -and $described.Output) {
            $first = ($described.Output | Select-Object -First 1)
            if ($first) { $commit = ([string]$first).Trim() }
        }
        $status = Invoke-Native -FilePath "git" `
            -Arguments @("-C", $Root, "status", "--porcelain") `
            -Stage "git status" -Capture -AllowFailure
        if ($status.ExitCode -eq 0 -and @($status.Output | Where-Object { ([string]$_).Trim() }).Count -gt 0) {
            $dirty = "-dirty"
        }
    }
    $stamp = (Get-Date).ToUniversalTime().ToString("yyyyMMdd'T'HHmmss'Z'")

    [pscustomobject]@{
        BuildId = "$stamp-$commit$dirty"
        Commit  = $commit
        Dirty   = [bool]$dirty
        Stamp   = $stamp
    }
}

function Find-AndroidSdk {
    param([Parameter(Mandatory = $true)][string]$Root)

    $candidates = New-Object System.Collections.Generic.List[string]
    if ($env:ANDROID_SDK_ROOT) { $candidates.Add($env:ANDROID_SDK_ROOT) }
    if ($env:ANDROID_HOME) { $candidates.Add($env:ANDROID_HOME) }

    $localProperties = Join-Path $Root "local.properties"
    if (Test-Path $localProperties) {
        $line = Get-Content $localProperties |
            Where-Object { $_ -match '^\s*sdk\.dir\s*=' } |
            Select-Object -First 1
        if ($line) {
            $value = ($line -split '=', 2)[1].Trim().Replace("\\", "\").Replace("\:", ":")
            if ($value) { $candidates.Add($value) }
        }
    }
    if ($env:LOCALAPPDATA) { $candidates.Add((Join-Path $env:LOCALAPPDATA "Android\Sdk")) }

    foreach ($candidate in ($candidates | Select-Object -Unique)) {
        if ($candidate -and (Test-Path $candidate)) { return $candidate }
    }
    return $null
}

<#
    Records package name, versions and the signing certificate for the manifest.
    Best-effort: the pipeline still produces images when the SDK build-tools are
    absent, it simply records less. It never changes how the APK was signed.
#>
function Write-ApkMetadata {
    param(
        [Parameter(Mandatory = $true)][string]$ApkPath,
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$Destination
    )

    $report = New-Object System.Collections.Generic.List[string]
    $sdk = Find-AndroidSdk -Root $Root
    $aapt = $null
    $apksigner = $null

    if ($sdk) {
        $buildToolsRoot = Join-Path $sdk "build-tools"
        if (Test-Path $buildToolsRoot) {
            $buildTools = Get-ChildItem $buildToolsRoot -Directory |
                Sort-Object { try { [version]$_.Name } catch { [version]"0.0" } } -Descending |
                Select-Object -First 1
            if ($buildTools) {
                $candidateAapt = Join-Path $buildTools.FullName "aapt.exe"
                $candidateSigner = Join-Path $buildTools.FullName "apksigner.bat"
                if (Test-Path $candidateAapt) { $aapt = $candidateAapt }
                if (Test-Path $candidateSigner) { $apksigner = $candidateSigner }
            }
        }
    }

    if ($aapt) {
        $badgingResult = Invoke-Native -FilePath $aapt -Arguments @("dump", "badging", $ApkPath) `
            -Stage "aapt dump badging" -Capture -AllowFailure
        $badging = $badgingResult.Output -join "`n"
        if ($badging -match "package: name='([^']+)'") {
            $report.Add("APK package name     : $($Matches[1])")
        }
        if ($badging -match "versionCode='([^']+)'") {
            $report.Add("APK versionCode      : $($Matches[1])")
        }
        if ($badging -match "versionName='([^']+)'") {
            $report.Add("APK versionName      : $($Matches[1])")
        }
    }
    else {
        $report.Add("APK package name     : (aapt unavailable; not extracted)")
    }

    if ($apksigner) {
        $signerResult = Invoke-Native -FilePath $apksigner `
            -Arguments @("verify", "--print-certs", $ApkPath) `
            -Stage "apksigner verify" -Capture -AllowFailure
        $certificate = $signerResult.Output -join "`n"
        $fingerprint = [regex]::Match($certificate, 'SHA-256 digest:\s*([0-9a-fA-F]+)')
        if ($fingerprint.Success) {
            $report.Add("APK signer SHA-256   : $($fingerprint.Groups[1].Value)")
        }
        if ($certificate -match 'Signer #1 certificate DN:\s*(.+)') {
            $report.Add("APK signer DN        : $($Matches[1].Trim())")
        }
    }
    else {
        $report.Add("APK signer           : (apksigner unavailable; not extracted)")
    }

    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Destination) | Out-Null
    $report -join "`n" | Out-File -FilePath $Destination -Encoding ascii
    foreach ($line in $report) { Write-Detail $line }
}
