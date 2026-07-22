[CmdletBinding()]
param(
    [string]$DataRoot = (Join-Path $env:ProgramData 'HighTac\Platform'),
    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$AndroidSdk = (Join-Path $env:LOCALAPPDATA 'Android\Sdk')
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function ConvertFrom-DotEnvValue {
    param([Parameter(Mandatory)][string]$Value)

    $trimmed = $Value.Trim()
    if ($trimmed.Length -ge 2 -and $trimmed[0] -eq '"' -and $trimmed[-1] -eq '"') {
        return [Regex]::Unescape($trimmed.Substring(1, $trimmed.Length - 2))
    }
    if ($trimmed.Length -ge 2 -and $trimmed[0] -eq "'" -and $trimmed[-1] -eq "'") {
        return $trimmed.Substring(1, $trimmed.Length - 2)
    }
    return $trimmed
}

function Read-DotEnvFile {
    param([Parameter(Mandatory)][string]$Path)

    $values = @{}
    foreach ($line in [IO.File]::ReadAllLines($Path, [Text.Encoding]::UTF8)) {
        if ($line -notmatch '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') { continue }
        $values[$Matches[1]] = ConvertFrom-DotEnvValue -Value $Matches[2]
    }
    return $values
}

function ConvertTo-PropertiesValue {
    param([Parameter(Mandatory)][string]$Value)

    if ($Value.Contains("`r") -or $Value.Contains("`n")) {
        throw 'Cloud configuration values must not contain line breaks.'
    }
    return $Value.Replace('\', '\\')
}

$sourcePath = Join-Path $DataRoot 'config\.env'
if (-not [IO.File]::Exists($sourcePath)) {
    throw "Protected HighTac configuration was not found at the expected path."
}
if (-not [IO.Directory]::Exists($AndroidSdk)) {
    throw 'Android SDK directory was not found.'
}

$requiredExplicitKeys = @(
    'HIGHTAC_OPENAI_API_KEY',
    'HIGHTAC_JIANDAOYUN_API_KEY',
    'HIGHTAC_JIANDAOYUN_APP_ID',
    'HIGHTAC_JIANDAOYUN_ENTRY_ID'
)
$effectiveDefaults = [ordered]@{
    HIGHTAC_OPENAI_BASE_URL = 'https://api.openai.com/v1'
    HIGHTAC_OPENAI_MODEL = 'gpt-5.5'
    HIGHTAC_JIANDAOYUN_BASE_URL = 'https://api.jiandaoyun.com/api'
}
$outputKeys = @(
    'HIGHTAC_OPENAI_API_KEY',
    'HIGHTAC_OPENAI_BASE_URL',
    'HIGHTAC_OPENAI_MODEL',
    'HIGHTAC_JIANDAOYUN_API_KEY',
    'HIGHTAC_JIANDAOYUN_APP_ID',
    'HIGHTAC_JIANDAOYUN_ENTRY_ID',
    'HIGHTAC_JIANDAOYUN_BASE_URL'
)
$sourceValues = Read-DotEnvFile -Path $sourcePath
$missing = @($requiredExplicitKeys | Where-Object {
    -not $sourceValues.ContainsKey($_) -or [string]::IsNullOrWhiteSpace($sourceValues[$_])
})
if ($missing.Count -gt 0) {
    throw "Protected HighTac configuration is incomplete. Missing fields: $($missing -join ', ')"
}
foreach ($entry in $effectiveDefaults.GetEnumerator()) {
    if (-not $sourceValues.ContainsKey($entry.Key) -or
        [string]::IsNullOrWhiteSpace($sourceValues[$entry.Key])) {
        $sourceValues[$entry.Key] = $entry.Value
    }
}

foreach ($urlKey in @('HIGHTAC_OPENAI_BASE_URL', 'HIGHTAC_JIANDAOYUN_BASE_URL')) {
    $uri = $null
    if (-not [Uri]::TryCreate($sourceValues[$urlKey], [UriKind]::Absolute, [ref]$uri) -or
        $uri.Scheme -ne 'https') {
        throw "$urlKey must be an absolute HTTPS URL."
    }
}

$targetPath = Join-Path $ProjectRoot 'local.properties'
$lines = [Collections.Generic.List[string]]::new()
$lines.Add('# Generated from protected HighTac configuration. Do not commit or share this file.')
$lines.Add("sdk.dir=$(ConvertTo-PropertiesValue -Value ($AndroidSdk.Replace('\', '/')))" )
foreach ($key in $outputKeys) {
    $lines.Add("$key=$(ConvertTo-PropertiesValue -Value $sourceValues[$key])")
}
[IO.File]::WriteAllLines($targetPath, $lines, [Text.UTF8Encoding]::new($false))

$resolvedTarget = [IO.Path]::GetFullPath($targetPath)
Write-Output "Android local configuration imported successfully."
Write-Output "Target: $resolvedTarget"
Write-Output "Imported cloud fields: $($outputKeys.Count) (values redacted)"
