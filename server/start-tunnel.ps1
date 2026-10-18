[CmdletBinding()]
param(
    [int]$Port = 8080
)

$envFile = Join-Path $PSScriptRoot ".env"
if (-not (Test-Path -LiteralPath $envFile -PathType Leaf)) {
    throw "Missing $envFile. Create it and set NGROK_DOMAIN."
}

$domainLine = Get-Content -LiteralPath $envFile |
    Where-Object { $_ -match '^\s*NGROK_DOMAIN\s*=\s*(.+?)\s*$' } |
    Select-Object -First 1
if (-not $domainLine) {
    throw "NGROK_DOMAIN is missing from $envFile."
}

$domain = ($domainLine -replace '^\s*NGROK_DOMAIN\s*=\s*', '').Trim()
if ([string]::IsNullOrWhiteSpace($domain) -or $domain -match '\s') {
    throw "NGROK_DOMAIN must be a domain without spaces."
}

& ngrok http "--domain=$domain" $Port
