[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'Low')]
param(
    [string]$InterfaceAlias,

    [ValidateRange(1, 65535)]
    [int]$WebPort = 8088,

    [ValidateRange(1, 65535)]
    [int]$MqttPort = 1884,

    [switch]$AllowPublicProfile,

    [switch]$AllowDhcp,

    [switch]$SkipPortAvailabilityCheck,

    [switch]$Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'HighTacInstaller.Common.psm1') -Force

if ($Force) {
    $ConfirmPreference = 'None'
}

Assert-HighTacAdministrator

if ($WebPort -eq $MqttPort) {
    throw 'WebPort and MqttPort must be different.'
}

if (-not $PSCmdlet.ShouldProcess('active IPv4 network adapters', 'Run static-IP deployment preflight')) {
    return
}

$configurations = @(Get-NetIPConfiguration | Where-Object {
    $_.NetAdapter.Status -eq 'Up' -and $null -ne $_.IPv4Address
})

if ($InterfaceAlias) {
    $configurations = @($configurations | Where-Object { $_.InterfaceAlias -eq $InterfaceAlias })
    if ($configurations.Count -eq 0) {
        throw "No active IPv4 adapter was found with interface alias '$InterfaceAlias'."
    }
}

$candidates = New-Object 'System.Collections.Generic.List[object]'
foreach ($configuration in $configurations) {
    $addresses = @($configuration.IPv4Address | Where-Object {
        $_.IPAddress -notmatch '^(127\.|169\.254\.)'
    })
    if ($addresses.Count -eq 0) {
        continue
    }

    $profile = Get-NetConnectionProfile -InterfaceIndex $configuration.InterfaceIndex -ErrorAction SilentlyContinue
    $interface = Get-NetIPInterface -InterfaceIndex $configuration.InterfaceIndex -AddressFamily IPv4 -ErrorAction Stop
    foreach ($address in $addresses) {
        $candidates.Add([pscustomobject]@{
            InterfaceAlias = $configuration.InterfaceAlias
            InterfaceIndex = $configuration.InterfaceIndex
            IPv4Address = $address.IPAddress
            PrefixLength = $address.PrefixLength
            DefaultGateway = if ($null -ne $configuration.IPv4DefaultGateway) { $configuration.IPv4DefaultGateway.NextHop } else { $null }
            Dhcp = [string]$interface.Dhcp
            NetworkCategory = if ($null -ne $profile) { [string]$profile.NetworkCategory } else { 'Unknown' }
        })
    }
}

if ($candidates.Count -eq 0) {
    throw 'No active non-loopback IPv4 adapter is available. Connect the LAN adapter before installing HighTac.'
}
if (-not $InterfaceAlias -and $candidates.Count -gt 1) {
    $aliases = ($candidates | ForEach-Object { $_.InterfaceAlias } | Sort-Object -Unique) -join ', '
    throw "Multiple active IPv4 addresses were found ($aliases). Re-run with -InterfaceAlias to select the HighTac LAN adapter."
}

$selected = $candidates[0]
if (-not $AllowDhcp -and $selected.Dhcp -ne 'Disabled') {
    throw "Adapter '$($selected.InterfaceAlias)' uses DHCP. Configure a reserved/static IPv4 address, then rerun the preflight."
}
if (-not $AllowPublicProfile -and $selected.NetworkCategory -ne 'Private') {
    throw "Adapter '$($selected.InterfaceAlias)' is '$($selected.NetworkCategory)'. Set its Windows network profile to Private before installation."
}
if ([string]::IsNullOrWhiteSpace([string]$selected.DefaultGateway)) {
    throw "Adapter '$($selected.InterfaceAlias)' has no IPv4 default gateway. Confirm the site LAN configuration before installation."
}

if (-not $SkipPortAvailabilityCheck) {
    if ($null -eq (Get-HighTacService -Name 'HighTacMqttBroker') -and (Test-HighTacLocalTcpListener -Port $MqttPort)) {
        throw "TCP port $MqttPort is already in use, but HighTacMqttBroker is not installed. Stop or reconfigure the independent listener manually; HighTac will not take ownership of another broker or of port 1883."
    }
    if ($null -eq (Get-HighTacService -Name 'HighTacPlatform') -and (Test-HighTacLocalTcpListener -Port $WebPort)) {
        throw "TCP port $WebPort is already in use, but HighTacPlatform is not installed. Resolve the listener conflict before installation."
    }
}

$selected
