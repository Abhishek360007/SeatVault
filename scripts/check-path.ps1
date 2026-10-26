# ============================================================================
# check-path.ps1 — READ-ONLY diagnostic.
#
# Prints PATH entries that look malformed (do not start with a drive letter like
# C:\ and are not UNC \\server paths). A leftover Windows Explorer saved-search
# URI such as one beginning with "search-ms:" can break tools that enumerate PATH
# — including the Docker/Testcontainers executable probe used by `mvnw verify`.
#
# This script DOES NOT modify your environment. To fix a bad entry, remove it via
# "Edit environment variables for your account" (see README ▸ Local Testcontainers
# troubleshooting), then open a new terminal and re-run `.\mvnw.cmd verify`.
# ============================================================================

Write-Host "Scanning PATH for suspicious entries (read-only)..." -ForegroundColor Cyan

$entries = $env:Path -split ';' | Where-Object { $_ -ne '' }
$bad = $entries | Where-Object { $_ -notmatch '^[A-Za-z]:\\' -and $_ -notmatch '^\\\\' }

if ($bad.Count -eq 0) {
    Write-Host "OK: no malformed PATH entries found." -ForegroundColor Green
} else {
    Write-Host "Found $($bad.Count) suspicious PATH entr$(if ($bad.Count -eq 1) {'y'} else {'ies'}):" -ForegroundColor Yellow
    $bad | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
    Write-Host ""
    Write-Host "Remove these via 'Edit environment variables for your account', open a new" -ForegroundColor Yellow
    Write-Host "terminal, then re-run: .\mvnw.cmd verify" -ForegroundColor Yellow
    Write-Host "(This script changed nothing.)" -ForegroundColor DarkGray
}
