$ErrorActionPreference = "Stop"

$apk = Join-Path $PSScriptRoot "..\app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path $apk)) {
    throw "Build the APK first with .\gradlew.bat assembleDebug"
}

$destination = Join-Path $env:USERPROFILE "Desktop\Y2Player-debug.apk"
Copy-Item $apk $destination -Force
Write-Host "Copied to $destination"
