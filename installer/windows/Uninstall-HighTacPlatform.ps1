[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'High')]
param(
    [string]$InstallRoot = (Join-Path $env:ProgramFiles 'HighTac\Platform'),

    [string]$DataRoot = (Join-Path $env:ProgramData 'HighTac\Platform'),

    [switch]$PreserveData,

    [switch]$RemoveData,

    [switch]$RemoveApplicationFiles,

    [switch]$Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'HighTacInstaller.Common.psm1') -Force

if ($Force) {
    $ConfirmPreference = 'None'
}

Assert-HighTacAdministrator

if ($PreserveData -and $RemoveData) {
    throw 'PreserveData and RemoveData cannot be used together.'
}

$InstallRoot = Assert-HighTacSafeRoot -Path $InstallRoot -Kind InstallRoot
$DataRoot = Assert-HighTacSafeRoot -Path $DataRoot -Kind DataRoot
$canonicalDataRoot = Resolve-HighTacFullPath -Path (Join-Path $env:ProgramData 'HighTac\Platform')
if ($DataRoot -ne $canonicalDataRoot) {
    throw "DataRoot is fixed by the service contract and must be '$canonicalDataRoot'."
}
$deleteData = [bool]$RemoveData

if (-not $PSCmdlet.ShouldProcess($InstallRoot, 'Uninstall HighTac Platform services and firewall rules')) {
    return
}

Uninstall-HighTacWinSWService `
    -WrapperPath (Join-Path $InstallRoot 'service\HighTacPlatform.exe') `
    -ServiceName 'HighTacPlatform' `
    -Confirm:$false
Uninstall-HighTacWinSWService `
    -WrapperPath (Join-Path $InstallRoot 'service\HighTacMqttBroker.exe') `
    -ServiceName 'HighTacMqttBroker' `
    -Confirm:$false

Stop-HighTacProcessByExecutablePath -ExecutablePath @(
    (Join-Path $InstallRoot 'server\HighTacPlatform.exe'),
    (Join-Path $InstallRoot 'mosquitto\mosquitto.exe')
) -Confirm:$false

& (Join-Path $PSScriptRoot 'Set-HighTacFirewall.ps1') -Action Remove -Confirm:$false | Out-Null

if ($deleteData -and (Test-Path -LiteralPath $DataRoot)) {
    $verifiedDataRoot = Assert-HighTacSafeRoot -Path $DataRoot -Kind DataRoot
    if ($PSCmdlet.ShouldProcess($verifiedDataRoot, 'Permanently remove HighTac runtime data, credentials, databases, logs, and backups')) {
        Enable-HighTacTreeRemoval -DataRoot $verifiedDataRoot -Confirm:$false
        Remove-Item -LiteralPath $verifiedDataRoot -Recurse -Force
    }
}

if ($RemoveApplicationFiles -and (Test-Path -LiteralPath $InstallRoot)) {
    $verifiedInstallRoot = Assert-HighTacSafeRoot -Path $InstallRoot -Kind InstallRoot
    if ($PSCmdlet.ShouldProcess($verifiedInstallRoot, 'Remove HighTac application files')) {
        Remove-Item -LiteralPath $verifiedInstallRoot -Recurse -Force
    }
}

[pscustomobject]@{
    PlatformServiceRemoved = $null -eq (Get-HighTacService -Name 'HighTacPlatform')
    BrokerServiceRemoved = $null -eq (Get-HighTacService -Name 'HighTacMqttBroker')
    ProgramDataPreserved = -not $deleteData
    DataRoot = $DataRoot
}
