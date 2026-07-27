# Regenerates SOURCE_SHA256SUMS.txt for the public source and tooling tree.
# Run from the repository root before signing:
#   powershell -ExecutionPolicy Bypass -File tools\update-source-sums.ps1

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$sourceFiles = @(& git -C $root ls-files --cached --others --exclude-standard)
if ($LASTEXITCODE -ne 0) { throw "Could not read the maintained source-file list." }

$lines = $sourceFiles |
    Where-Object {
        $_ -ne "SOURCE_SHA256SUMS.txt" -and
        (Test-Path -LiteralPath (Join-Path $root $_) -PathType Leaf)
    } | Sort-Object -Unique |
    ForEach-Object {
        $relative = $_.Replace('\', '/')
        $path = Join-Path $root $_
        $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $path).Hash.ToLower()
        "$hash  $relative"
    }

# sha256sum-compatible format (LF, trailing newline).
[IO.File]::WriteAllText((Join-Path $root "SOURCE_SHA256SUMS.txt"), (($lines -join "`n") + "`n"))
Write-Host "Wrote $($lines.Count) entries to SOURCE_SHA256SUMS.txt"
