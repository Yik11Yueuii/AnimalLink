param([string]$EnvFile = (Join-Path $PSScriptRoot '..\infra\.env'))

if (-not (Test-Path -LiteralPath $EnvFile)) {
    throw "Missing $EnvFile. Copy infra/.env.example to infra/.env and replace the example secrets."
}

foreach ($line in Get-Content -LiteralPath $EnvFile) {
    $trimmed = $line.Trim()
    if (-not $trimmed -or $trimmed.StartsWith('#')) { continue }
    if ($trimmed -notmatch '^([A-Za-z_][A-Za-z0-9_]*)=(.*)$') {
        throw "Invalid .env entry: $trimmed"
    }
    [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
}
