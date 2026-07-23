# Regenerates SOURCE_SHA256SUMS.txt for the public source and tooling tree.
# Run from the repository root before signing:
#   powershell -ExecutionPolicy Bypass -File tools\update-source-sums.ps1

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$trackedFiles = @(& git -C $root ls-files)
if ($LASTEXITCODE -ne 0) { throw "Could not read the Git tracked-file list." }

$lines = $trackedFiles |
    Where-Object { $_ -ne "SOURCE_SHA256SUMS.txt" } | Sort-Object -Unique |
    ForEach-Object {
        $relative = $_.Replace('\', '/')
        $path = Join-Path $root $_
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            throw "Tracked file is missing: $relative"
        }
        $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $path).Hash.ToLower()
        "$hash  $relative"
    }

# sha256sum-compatible format (LF, trailing newline).
[IO.File]::WriteAllText((Join-Path $root "SOURCE_SHA256SUMS.txt"), (($lines -join "`n") + "`n"))
Write-Host "Wrote $($lines.Count) entries to SOURCE_SHA256SUMS.txt"
