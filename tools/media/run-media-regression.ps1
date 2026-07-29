param(
    [ValidateSet('all', 'metadata', 'scan', 'decode', 'resource')]
    [string]$Mode = 'all',
    [ValidateRange(1, 100)]
    [int]$Repeats = 1,
    [string]$Serial = '0123456789ABCDEF',
    [int]$TimeoutSeconds = 600,
    [ValidateRange(32768, 5242880)]
    [int]$ProbeBytes = 32768,
    [ValidateRange(0, 10000000)]
    [int]$AnalyzeUs = 100000,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$corpus = Join-Path $PSScriptRoot 'corpus'
$manifestPath = Join-Path $corpus 'manifest.json'
$packageName = 'com.schulzcode.y2player.debug'
$component = "$packageName/com.schulzcode.y2player.debug.MediaRegressionService"
$deviceBase = '/storage/sdcard1/Y2MediaRegression'
$deviceCorpus = "$deviceBase/corpus"
$deviceResult = "$deviceBase/device-result.json"
$stamp = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ')
$reportDirectory = Join-Path $repo "build\media-regression\$stamp-$Mode"

if (-not (Test-Path -LiteralPath $adb -PathType Leaf)) {
    throw "ADB not found: $adb"
}
if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
    throw "Corpus manifest not found. Run tools/media/generate-corpus.py first."
}

# Refuse to push a corpus whose bytes no longer match its independent manifest.
$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
foreach ($fixture in $manifest.fixtures) {
    $fixturePath = Join-Path $corpus ($fixture.path -replace '/', '\')
    if (-not (Test-Path -LiteralPath $fixturePath -PathType Leaf)) {
        throw "Missing fixture: $($fixture.path)"
    }
    $actualHash = (Get-FileHash -LiteralPath $fixturePath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualHash -ne $fixture.sha256) {
        throw "Fixture hash mismatch: $($fixture.path)"
    }
}

New-Item -ItemType Directory -Force -Path $reportDirectory | Out-Null

if (-not $SkipBuild) {
    Push-Location $repo
    try {
        & .\gradlew.bat assembleDebug --console=plain
        if ($LASTEXITCODE -ne 0) { throw "assembleDebug failed ($LASTEXITCODE)" }
    } finally {
        Pop-Location
    }
}

$apk = Join-Path $repo 'app\build\outputs\apk\debug\app-debug.apk'
if (-not (Test-Path -LiteralPath $apk -PathType Leaf)) {
    throw "Debug APK not found: $apk"
}
& $adb -s $Serial get-state | Out-Null
if ($LASTEXITCODE -ne 0) { throw "ADB device $Serial is unavailable" }
$savedErrorAction = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$installOutput = (& $adb -s $Serial install -r $apk 2>$null | Out-String)
$installExit = $LASTEXITCODE
$ErrorActionPreference = $savedErrorAction
Write-Output $installOutput.Trim()
if ($installExit -ne 0 -or $installOutput -notmatch '(?m)^Success\s*$') {
    throw "APK installation failed: $($installOutput.Trim())"
}
$clearOutput = (& $adb -s $Serial shell pm clear $packageName 2>&1 | Out-String)
if ($clearOutput -notmatch 'Success') { throw "Could not clear debug application data: $($clearOutput.Trim())" }

# This is the suite's dedicated generated directory; no user-library path is touched.
& $adb -s $Serial shell rm -rf $deviceBase
& $adb -s $Serial shell mkdir -p $deviceCorpus
& $adb -s $Serial push "$corpus\." "$deviceCorpus/"
if ($LASTEXITCODE -ne 0) { throw "Corpus push failed ($LASTEXITCODE)" }

& $adb -s $Serial logcat -c
& $adb -s $Serial shell am startservice -n $component -a 'com.schulzcode.y2player.debug.RUN_MEDIA_REGRESSION' `
    --es corpus $deviceCorpus --es output $deviceResult --es mode $Mode --ei repeats $Repeats `
    --ei probeBytes $ProbeBytes --ei analyzeUs $AnalyzeUs
if ($LASTEXITCODE -ne 0) { throw "Could not start device regression service" }

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$resultReady = $false
while ((Get-Date) -lt $deadline) {
    $remoteListing = (& $adb -s $Serial shell ls $deviceResult 2>$null | Out-String).Trim()
    if ($remoteListing -eq $deviceResult) {
        $resultReady = $true
        break
    }
    Start-Sleep -Seconds 1
}
if (-not $resultReady) {
    & $adb -s $Serial logcat -d -v threadtime | Set-Content -LiteralPath (Join-Path $reportDirectory 'logcat-timeout.txt')
    throw "Timed out waiting for $deviceResult"
}

Start-Sleep -Milliseconds 500
$actualPath = Join-Path $reportDirectory 'device-result.json'
& $adb -s $Serial pull $deviceResult $actualPath | Out-Null
if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $actualPath -PathType Leaf)) {
    throw "Device result pull failed"
}
& $adb -s $Serial logcat -d -v threadtime | Set-Content -LiteralPath (Join-Path $reportDirectory 'logcat.txt')

$jsonReport = Join-Path $reportDirectory 'report.json'
$markdownReport = Join-Path $reportDirectory 'report.md'
& py -3 (Join-Path $PSScriptRoot 'compare-results.py') `
    --manifest $manifestPath --actual $actualPath `
    --json-report $jsonReport --markdown-report $markdownReport
$comparisonExit = $LASTEXITCODE

Write-Output "Device result: $actualPath"
Write-Output "JSON report:   $jsonReport"
Write-Output "Markdown:      $markdownReport"
exit $comparisonExit
