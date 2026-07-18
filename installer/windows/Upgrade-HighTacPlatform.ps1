[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'High')]
param(
    [ValidateSet('All', 'Prepare', 'Finalize')]
    [string]$Phase = 'All',

    [string]$InstallRoot = (Join-Path $env:ProgramFiles 'HighTac\Platform'),

    [string]$DataRoot = (Join-Path $env:ProgramData 'HighTac\Platform'),

    [string]$PayloadRoot,

    [switch]$SkipHealthCheck,

    [switch]$SkipDependencyInstall,

    [switch]$Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'HighTacInstaller.Common.psm1') -Force

if ($Force) {
    $ConfirmPreference = 'None'
}

Assert-HighTacAdministrator

$InstallRoot = Assert-HighTacSafeRoot -Path $InstallRoot -Kind InstallRoot
$DataRoot = Assert-HighTacSafeRoot -Path $DataRoot -Kind DataRoot

function Test-ReleasePayload {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string]$Root
    )

    $required = @(
        'server\HighTacPlatform.exe',
        'mosquitto\mosquitto.exe',
        'mosquitto\mosquitto_passwd.exe',
        'service\HighTacPlatform.exe',
        'service\HighTacPlatform.xml',
        'service\HighTacMqttBroker.exe',
        'service\HighTacMqttBroker.xml',
        'tools\Start-HighTacPlatformService.ps1',
        'dependencies\VC_redist.x64.exe',
        'licenses\Microsoft-VC-Runtime-Notice.txt',
        'tools\Install-HighTacPlatform.ps1',
        'tools\HighTacInstaller.Common.psm1',
        'templates\platform.yaml.template',
        'templates\platform.env.template',
        'SHA256SUMS.txt'
    )
    foreach ($relativePath in $required) {
        $path = Join-Path $Root $relativePath
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            throw "Upgrade payload is incomplete; required file is missing: $path"
        }
    }

    $manifestPath = Join-Path $Root 'SHA256SUMS.txt'
    $manifestEntries = @{}
    foreach ($line in [IO.File]::ReadAllLines($manifestPath)) {
        if ([string]::IsNullOrWhiteSpace($line) -or $line.StartsWith('#')) {
            continue
        }
        if ($line -notmatch '^([A-Fa-f0-9]{64})\s+\*(.+)$') {
            throw "Invalid checksum manifest line: $line"
        }
        $expectedHash = $Matches[1].ToUpperInvariant()
        $relativePath = $Matches[2].Replace('/', '\')
        if ([IO.Path]::IsPathRooted($relativePath) -or $relativePath -match '(^|\\)\.\.(\\|$)') {
            throw "Unsafe relative path in checksum manifest: $relativePath"
        }
        $manifestKey = $relativePath.ToLowerInvariant()
        if ($manifestEntries.ContainsKey($manifestKey)) {
            throw "Duplicate path in checksum manifest: $relativePath"
        }
        $manifestEntries[$manifestKey] = $expectedHash
        $filePath = Join-Path $Root $relativePath
        if (-not (Test-Path -LiteralPath $filePath -PathType Leaf)) {
            throw "Checksum manifest references a missing file: $filePath"
        }
        $actualHash = (Get-FileHash -LiteralPath $filePath -Algorithm SHA256).Hash
        if ($actualHash -ne $expectedHash) {
            throw "Checksum mismatch for upgrade payload file: $relativePath"
        }
    }

    $payloadFiles = @(Get-ChildItem -LiteralPath $Root -Recurse -File | Where-Object { $_.FullName -ne $manifestPath })
    if ($manifestEntries.Count -eq 0) {
        throw 'Checksum manifest contains no payload entries.'
    }
    foreach ($file in $payloadFiles) {
        $relativePath = $file.FullName.Substring($Root.Length + 1).Replace('/', '\')
        if (-not $manifestEntries.ContainsKey($relativePath.ToLowerInvariant())) {
            throw "Upgrade payload contains a file that is not covered by SHA256SUMS.txt: $relativePath"
        }
    }
    if ($manifestEntries.Count -ne $payloadFiles.Count) {
        throw 'Checksum manifest entry count does not match the release payload file count.'
    }
}

function Stop-OwnedServices {
    [CmdletBinding()]
    param()

    Stop-HighTacService -Name 'HighTacPlatform' -Confirm:$false
    Stop-HighTacService -Name 'HighTacMqttBroker' -Confirm:$false
}

function Invoke-Finalize {
    [CmdletBinding()]
    param()

    if (-not $SkipDependencyInstall) {
        $vcRedist = Join-Path $InstallRoot 'dependencies\VC_redist.x64.exe'
        if (-not (Test-Path -LiteralPath $vcRedist -PathType Leaf)) {
            throw "Microsoft Visual C++ runtime dependency is missing: $vcRedist"
        }
        $vcOutput = & $vcRedist /install /quiet /norestart 2>&1
        $vcExitCode = $LASTEXITCODE
        if ($vcExitCode -notin @(0, 1638, 3010)) {
            throw "Microsoft Visual C++ runtime installation failed (exit $vcExitCode): $($vcOutput -join ' ')"
        }
        if ($vcExitCode -eq 3010) {
            Write-Warning 'Microsoft Visual C++ runtime requested a Windows restart.'
        }
    }

    $installScript = Join-Path $InstallRoot 'tools\Install-HighTacPlatform.ps1'
    if (-not (Test-Path -LiteralPath $installScript -PathType Leaf)) {
        throw "Installed upgrade finalizer is missing: $installScript"
    }

    $installParameters = @{
        InstallRoot = $InstallRoot
        DataRoot = $DataRoot
        TemplateRoot = (Join-Path $InstallRoot 'templates')
        SkipNetworkPreflight = $true
        SkipHealthCheck = $SkipHealthCheck
        Confirm = $false
    }
    & $installScript @installParameters
}

if ($Phase -eq 'Prepare') {
    if ($PSCmdlet.ShouldProcess('HighTacPlatform and HighTacMqttBroker', 'Stop owned services before installer file replacement')) {
        Stop-OwnedServices
    }
    return
}

if ($Phase -eq 'Finalize') {
    if ($PSCmdlet.ShouldProcess($InstallRoot, 'Refresh services and health-check the upgraded HighTac payload')) {
        Invoke-Finalize
    }
    return
}

if (-not $PayloadRoot) {
    throw 'PayloadRoot is required when Phase is All.'
}
$PayloadRoot = Resolve-HighTacFullPath -Path $PayloadRoot
if (-not (Test-Path -LiteralPath $PayloadRoot -PathType Container)) {
    throw "Upgrade payload directory does not exist: $PayloadRoot"
}
if ($PayloadRoot -eq $InstallRoot -or $PayloadRoot.StartsWith($InstallRoot + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'PayloadRoot must be outside InstallRoot.'
}
Test-ReleasePayload -Root $PayloadRoot

if (-not $PSCmdlet.ShouldProcess("$PayloadRoot -> $InstallRoot", 'Verify, copy, and activate HighTac upgrade payload while preserving ProgramData')) {
    return
}

Stop-OwnedServices
if (-not (Test-Path -LiteralPath $InstallRoot)) {
    New-Item -ItemType Directory -Path $InstallRoot -Force | Out-Null
}
foreach ($item in Get-ChildItem -LiteralPath $PayloadRoot -Force) {
    Copy-Item -LiteralPath $item.FullName -Destination $InstallRoot -Recurse -Force
}
Invoke-Finalize
