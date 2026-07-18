[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'High')]
param(
    [string]$DataRoot = (Join-Path $env:ProgramData 'HighTac\Platform'),

    [Security.SecureString]$OpenAiApiKey,

    [AllowNull()]
    [AllowEmptyString()]
    [ValidateScript({ [string]::IsNullOrWhiteSpace($_) -or $_ -match '^https?://[^\s]+$' })]
    [string]$OpenAiBaseUrl,

    [AllowNull()]
    [AllowEmptyString()]
    [ValidateLength(0, 128)]
    [string]$OpenAiModel,

    [Security.SecureString]$JianDaoYunApiKey,

    [AllowNull()]
    [AllowEmptyString()]
    [ValidateLength(0, 128)]
    [string]$JianDaoYunAppId,

    [AllowNull()]
    [AllowEmptyString()]
    [ValidateLength(0, 128)]
    [string]$JianDaoYunEntryId,

    [AllowNull()]
    [AllowEmptyString()]
    [ValidateScript({ [string]::IsNullOrWhiteSpace($_) -or $_ -match '^https?://[^\s]+$' })]
    [string]$JianDaoYunBaseUrl,

    [switch]$UseProcessEnvironment,

    [switch]$ClearOpenAiApiKey,

    [switch]$ClearJianDaoYunApiKey,

    [switch]$RestartService,

    [switch]$Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'HighTacInstaller.Common.psm1') -Force

function Get-ProcessEnvironmentValue {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Names
    )

    foreach ($name in $Names) {
        $value = [Environment]::GetEnvironmentVariable($name, 'Process')
        if (-not [string]::IsNullOrWhiteSpace($value)) {
            return $value
        }
    }
    return $null
}

function Assert-HighTacIntegrationValue {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name,

        [Parameter(Mandatory = $true)]
        [AllowEmptyString()]
        [string]$Value,

        [ValidateRange(1, 16384)]
        [int]$MaximumLength = 16384
    )

    if ([string]::IsNullOrWhiteSpace($Value)) {
        throw "$Name cannot be empty. Use the matching clear switch to remove a credential."
    }
    if ($Value.Length -gt $MaximumLength) {
        throw "$Name exceeds the maximum supported length of $MaximumLength characters."
    }
    if ($Value -match '[\x00-\x1F\x7F]') {
        throw "$Name cannot contain control characters."
    }
}

function Assert-HighTacIntegrationUrl {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name,

        [Parameter(Mandatory = $true)]
        [string]$Value
    )

    Assert-HighTacIntegrationValue -Name $Name -Value $Value -MaximumLength 2048
    $uri = $null
    if ($Value -match '\s' -or
        -not [Uri]::TryCreate($Value, [UriKind]::Absolute, [ref]$uri) -or
        $uri.Scheme -notin @('http', 'https') -or
        [string]::IsNullOrWhiteSpace($uri.Host) -or
        -not [string]::IsNullOrEmpty($uri.UserInfo) -or
        -not [string]::IsNullOrEmpty($uri.Query) -or
        -not [string]::IsNullOrEmpty($uri.Fragment)) {
        throw "$Name must be an HTTP(S) origin/path without credentials, whitespace, query, or fragment."
    }
}

function Assert-HighTacProtectedPath {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,

        [Parameter(Mandatory = $true)]
        [ValidateSet('Container', 'Leaf')]
        [string]$PathType,

        [ValidateRange(0, 1048576)]
        [long]$MaximumBytes = 0
    )

    if (-not (Test-Path -LiteralPath $Path -PathType $PathType)) {
        throw "Protected HighTac $PathType path is missing: $Path"
    }

    $item = Get-Item -LiteralPath $Path -Force
    if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "Protected HighTac $PathType path cannot be a symbolic link or junction: $Path"
    }
    if ($PathType -eq 'Leaf' -and $MaximumBytes -gt 0 -and $item.Length -gt $MaximumBytes) {
        throw "Protected HighTac credential file exceeds the $MaximumBytes-byte safety limit."
    }

    $allowedSids = @('S-1-5-18', 'S-1-5-32-544')
    $acl = Get-Acl -LiteralPath $item.FullName
    foreach ($rule in @($acl.Access)) {
        if ($rule.AccessControlType -ne [Security.AccessControl.AccessControlType]::Allow) {
            continue
        }
        try {
            $sid = $rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value
        }
        catch {
            throw "Protected HighTac $PathType path has an unrecognized access principal: $Path"
        }
        if ($sid -notin $allowedSids) {
            throw "Protected HighTac $PathType path grants access outside LocalSystem and Administrators: $Path"
        }
    }

    return $item
}

function Assert-HighTacDotEnvContent {
    param(
        [Parameter(Mandatory = $true)]
        [AllowEmptyString()]
        [string]$Content
    )

    $names = @{}
    $lineNumber = 0
    foreach ($line in [regex]::Split($Content, '\r\n|\n|\r')) {
        $lineNumber++
        if ([string]::IsNullOrWhiteSpace($line) -or $line.TrimStart().StartsWith('#')) {
            continue
        }

        $match = [regex]::Match($line, '^([A-Z][A-Z0-9_]*)="((?:[^"\\]|\\.)*)"$')
        if (-not $match.Success) {
            throw "Protected runtime environment contains an invalid entry at line $lineNumber."
        }

        $name = $match.Groups[1].Value
        $encodedValue = $match.Groups[2].Value
        if ($name -notmatch '^HIGHTAC_[A-Z0-9_]+$') {
            throw "Protected runtime environment contains a variable outside the HIGHTAC_ namespace at line $lineNumber."
        }
        $valueWithoutSupportedEscapes = [regex]::Replace($encodedValue, '\\[nr"\\]', '')
        if ($valueWithoutSupportedEscapes.Contains('\')) {
            throw "Protected runtime environment contains an unsupported escape sequence at line $lineNumber."
        }
        if ($names.ContainsKey($name)) {
            throw "Protected runtime environment contains duplicate variable '$name'."
        }
        $names[$name] = $true
    }
}

function Set-DotEnvValue {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Content,

        [Parameter(Mandatory = $true)]
        [ValidatePattern('^HIGHTAC_[A-Z0-9_]+$')]
        [string]$Name,

        [Parameter(Mandatory = $true)]
        [AllowEmptyString()]
        [string]$Value
    )

    $encoded = ConvertTo-HighTacYamlString -Value $Value
    $line = $Name + '="' + $encoded + '"'
    $pattern = '(?m)^' + [regex]::Escape($Name) + '="(?:[^"\\]|\\.)*"[ \t]*(?=\r?$)'
    $matches = [regex]::Matches($Content, $pattern)
    if ($matches.Count -gt 1) {
        throw "Protected runtime environment contains duplicate variable '$Name'."
    }
    if ($matches.Count -eq 1) {
        return [regex]::Replace(
            $Content,
            $pattern,
            [Text.RegularExpressions.MatchEvaluator]{ param($match) $line }
        )
    }

    $newLine = "`r`n"
    if ($Content.Contains("`r`n")) {
        $newLine = "`r`n"
    }
    elseif ($Content.Contains("`n")) {
        $newLine = "`n"
    }
    elseif ($Content.Contains("`r")) {
        $newLine = "`r"
    }

    $updated = $Content
    if ($updated.Length -gt 0 -and -not $updated.EndsWith("`n") -and -not $updated.EndsWith("`r")) {
        $updated += $newLine
    }
    return $updated + $line + $newLine
}

function Move-HighTacCredentialFileAtomically {
    param(
        [Parameter(Mandatory = $true)]
        [string]$SourcePath,

        [Parameter(Mandatory = $true)]
        [string]$DestinationPath
    )

    if ($null -eq ('HighTac.Installer.CredentialFileNativeMethods' -as [type])) {
        Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;

namespace HighTac.Installer
{
    public static class CredentialFileNativeMethods
    {
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        public static extern bool MoveFileEx(
            string existingFileName,
            string newFileName,
            uint flags
        );
    }
}
'@
    }

    $moveFileReplaceExisting = [uint32]0x1
    $moveFileWriteThrough = [uint32]0x8
    $flags = $moveFileReplaceExisting -bor $moveFileWriteThrough
    if (-not [HighTac.Installer.CredentialFileNativeMethods]::MoveFileEx(
            $SourcePath,
            $DestinationPath,
            $flags
        )) {
        $errorCode = [Runtime.InteropServices.Marshal]::GetLastWin32Error()
        throw (New-Object ComponentModel.Win32Exception(
            $errorCode,
            'Windows could not atomically replace the protected integration credential file.'
        ))
    }
}

function Write-HighTacCredentialEnvironment {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,

        [Parameter(Mandatory = $true)]
        [AllowEmptyString()]
        [string]$Content
    )

    $destinationPath = [IO.Path]::GetFullPath($Path)
    $parentPath = Split-Path -Parent $destinationPath
    $temporaryName = (Split-Path -Leaf $destinationPath) + '.' + [Guid]::NewGuid().ToString('N') + '.tmp'
    $temporaryPath = Join-Path $parentPath $temporaryName
    $bytes = $null
    $stream = $null
    try {
        $utf8WithoutBom = New-Object Text.UTF8Encoding($false)
        $bytes = $utf8WithoutBom.GetBytes($Content)
        $stream = [IO.File]::Open(
            $temporaryPath,
            [IO.FileMode]::CreateNew,
            [IO.FileAccess]::Write,
            [IO.FileShare]::None
        )
        try {
            $stream.Write($bytes, 0, $bytes.Length)
            $stream.Flush($true)
        }
        finally {
            $stream.Dispose()
            $stream = $null
        }

        # The parent is already restricted, then the complete temporary file is
        # explicitly protected before it can replace the live credential file.
        Protect-HighTacPath -Path $temporaryPath -Confirm:$false
        Move-HighTacCredentialFileAtomically `
            -SourcePath $temporaryPath `
            -DestinationPath $destinationPath
        Protect-HighTacPath -Path $destinationPath -Confirm:$false
    }
    finally {
        if ($null -ne $stream) {
            $stream.Dispose()
        }
        if (Test-Path -LiteralPath $temporaryPath) {
            Remove-Item -LiteralPath $temporaryPath -Force -ErrorAction Stop
        }
        if ($null -ne $bytes) {
            [Array]::Clear($bytes, 0, $bytes.Length)
        }
    }
}

if ($Force) {
    $ConfirmPreference = 'None'
}
Assert-HighTacAdministrator
$DataRoot = Assert-HighTacSafeRoot -Path $DataRoot -Kind DataRoot
$canonicalDataRoot = Resolve-HighTacFullPath -Path (Join-Path $env:ProgramData 'HighTac\Platform')
if ($DataRoot -ne $canonicalDataRoot) {
    throw "DataRoot must be the managed HighTac path '$canonicalDataRoot'."
}
if ($ClearOpenAiApiKey -and $null -ne $OpenAiApiKey) {
    throw 'OpenAiApiKey and ClearOpenAiApiKey cannot be used together.'
}
if ($ClearJianDaoYunApiKey -and $null -ne $JianDaoYunApiKey) {
    throw 'JianDaoYunApiKey and ClearJianDaoYunApiKey cannot be used together.'
}

$updates = [ordered]@{}
$updatedNames = @()
$configurationChanged = $false
$serviceRestarted = $false
$openAiKeyFromEnvironment = $false
$jianDaoYunKeyFromEnvironment = $false
$processValue = $null
$openAiKeyPlain = $null
$jianDaoYunKeyPlain = $null
$content = $null
$updatedContent = $null
try {
    if ($UseProcessEnvironment) {
        if ($null -eq $OpenAiApiKey -and -not $ClearOpenAiApiKey) {
            $processValue = Get-ProcessEnvironmentValue -Names @('HIGHTAC_OPENAI_API_KEY', 'OPENAI_API_KEY')
            if ($processValue) {
                $OpenAiApiKey = ConvertTo-SecureString -String $processValue -AsPlainText -Force
                $openAiKeyFromEnvironment = $true
            }
            $processValue = $null
        }
        if (-not $OpenAiBaseUrl) {
            $OpenAiBaseUrl = Get-ProcessEnvironmentValue -Names @('HIGHTAC_OPENAI_BASE_URL', 'OPENAI_BASE_URL')
        }
        if (-not $OpenAiModel) {
            $OpenAiModel = Get-ProcessEnvironmentValue -Names @('HIGHTAC_OPENAI_MODEL', 'OPENAI_MODEL')
        }
        if ($null -eq $JianDaoYunApiKey -and -not $ClearJianDaoYunApiKey) {
            $processValue = Get-ProcessEnvironmentValue -Names @('HIGHTAC_JIANDAOYUN_API_KEY', 'JIANDAOYUN_API_KEY')
            if ($processValue) {
                $JianDaoYunApiKey = ConvertTo-SecureString -String $processValue -AsPlainText -Force
                $jianDaoYunKeyFromEnvironment = $true
            }
            $processValue = $null
        }
        if (-not $JianDaoYunAppId) {
            $JianDaoYunAppId = Get-ProcessEnvironmentValue -Names @('HIGHTAC_JIANDAOYUN_APP_ID', 'JIANDAOYUN_APP_ID')
        }
        if (-not $JianDaoYunEntryId) {
            $JianDaoYunEntryId = Get-ProcessEnvironmentValue -Names @('HIGHTAC_JIANDAOYUN_ENTRY_ID', 'JIANDAOYUN_ENTRY_ID')
        }
        if (-not $JianDaoYunBaseUrl) {
            $JianDaoYunBaseUrl = Get-ProcessEnvironmentValue -Names @('HIGHTAC_JIANDAOYUN_BASE_URL', 'JIANDAOYUN_BASE_URL')
        }
    }

    if ($ClearOpenAiApiKey) {
        $updates.HIGHTAC_OPENAI_API_KEY = ''
    }
    elseif ($null -ne $OpenAiApiKey) {
        if ($OpenAiApiKey.Length -lt 1 -or $OpenAiApiKey.Length -gt 16384) {
            throw 'OpenAiApiKey must contain between 1 and 16384 characters.'
        }
        $openAiKeyPlain = ConvertFrom-HighTacSecureString -SecureString $OpenAiApiKey
        Assert-HighTacIntegrationValue -Name 'OpenAiApiKey' -Value $openAiKeyPlain
        $updates.HIGHTAC_OPENAI_API_KEY = $openAiKeyPlain
    }
    if (-not [string]::IsNullOrWhiteSpace($OpenAiBaseUrl)) {
        Assert-HighTacIntegrationUrl -Name 'OpenAiBaseUrl' -Value $OpenAiBaseUrl
        $updates.HIGHTAC_OPENAI_BASE_URL = $OpenAiBaseUrl.TrimEnd('/')
    }
    if (-not [string]::IsNullOrWhiteSpace($OpenAiModel)) {
        $normalizedOpenAiModel = $OpenAiModel.Trim()
        Assert-HighTacIntegrationValue -Name 'OpenAiModel' -Value $normalizedOpenAiModel -MaximumLength 128
        $updates.HIGHTAC_OPENAI_MODEL = $normalizedOpenAiModel
    }

    if ($ClearJianDaoYunApiKey) {
        $updates.HIGHTAC_JIANDAOYUN_API_KEY = ''
    }
    elseif ($null -ne $JianDaoYunApiKey) {
        if ($JianDaoYunApiKey.Length -lt 1 -or $JianDaoYunApiKey.Length -gt 16384) {
            throw 'JianDaoYunApiKey must contain between 1 and 16384 characters.'
        }
        $jianDaoYunKeyPlain = ConvertFrom-HighTacSecureString -SecureString $JianDaoYunApiKey
        Assert-HighTacIntegrationValue -Name 'JianDaoYunApiKey' -Value $jianDaoYunKeyPlain
        $updates.HIGHTAC_JIANDAOYUN_API_KEY = $jianDaoYunKeyPlain
    }
    if (-not [string]::IsNullOrWhiteSpace($JianDaoYunAppId)) {
        $normalizedJianDaoYunAppId = $JianDaoYunAppId.Trim()
        Assert-HighTacIntegrationValue -Name 'JianDaoYunAppId' -Value $normalizedJianDaoYunAppId -MaximumLength 128
        $updates.HIGHTAC_JIANDAOYUN_APP_ID = $normalizedJianDaoYunAppId
    }
    if (-not [string]::IsNullOrWhiteSpace($JianDaoYunEntryId)) {
        $normalizedJianDaoYunEntryId = $JianDaoYunEntryId.Trim()
        Assert-HighTacIntegrationValue -Name 'JianDaoYunEntryId' -Value $normalizedJianDaoYunEntryId -MaximumLength 128
        $updates.HIGHTAC_JIANDAOYUN_ENTRY_ID = $normalizedJianDaoYunEntryId
    }
    if (-not [string]::IsNullOrWhiteSpace($JianDaoYunBaseUrl)) {
        Assert-HighTacIntegrationUrl -Name 'JianDaoYunBaseUrl' -Value $JianDaoYunBaseUrl
        $updates.HIGHTAC_JIANDAOYUN_BASE_URL = $JianDaoYunBaseUrl.TrimEnd('/')
    }
    if ($updates.Count -eq 0) {
        throw 'No integration settings were supplied.'
    }
    $updatedNames = @($updates.Keys)

    [void](Assert-HighTacProtectedPath -Path $DataRoot -PathType Container)
    $configPath = Join-Path $DataRoot 'config'
    [void](Assert-HighTacProtectedPath -Path $configPath -PathType Container)
    $environmentPath = Join-Path $configPath '.env'
    [void](Assert-HighTacProtectedPath -Path $environmentPath -PathType Leaf -MaximumBytes 1048576)

    $action = 'Atomically update protected integration settings'
    if ($RestartService) {
        $action += ' and restart HighTacPlatform'
    }
    if ($PSCmdlet.ShouldProcess($environmentPath, $action)) {
        $content = [IO.File]::ReadAllText($environmentPath)
        Assert-HighTacDotEnvContent -Content $content
        $updatedContent = $content
        foreach ($name in $updates.Keys) {
            $updatedContent = Set-DotEnvValue `
                -Content $updatedContent `
                -Name $name `
                -Value ([string]$updates[$name])
        }
        Assert-HighTacDotEnvContent -Content $updatedContent

        $configurationChanged = -not [string]::Equals(
            $content,
            $updatedContent,
            [StringComparison]::Ordinal
        )
        if ($configurationChanged) {
            Write-HighTacCredentialEnvironment -Path $environmentPath -Content $updatedContent
        }
        if ($RestartService) {
            Stop-HighTacService -Name 'HighTacPlatform' -Confirm:$false
            Start-HighTacService -Name 'HighTacPlatform' -Confirm:$false
            $serviceRestarted = $true
        }
    }
}
finally {
    foreach ($name in @($updates.Keys)) {
        $updates[$name] = $null
    }
    $updates.Clear()
    $processValue = $null
    $openAiKeyPlain = $null
    $jianDaoYunKeyPlain = $null
    $content = $null
    $updatedContent = $null
    if ($openAiKeyFromEnvironment -and $null -ne $OpenAiApiKey) {
        $OpenAiApiKey.Dispose()
        $OpenAiApiKey = $null
    }
    if ($jianDaoYunKeyFromEnvironment -and $null -ne $JianDaoYunApiKey) {
        $JianDaoYunApiKey.Dispose()
        $JianDaoYunApiKey = $null
    }
}

[pscustomobject]@{
    EnvironmentPath = $environmentPath
    UpdatedNames = $updatedNames
    ConfigurationChanged = $configurationChanged
    ServiceRestartRequested = [bool]$RestartService
    ServiceRestarted = $serviceRestarted
}
