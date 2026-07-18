[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'Medium')]
param(
    [ValidateSet('Ensure', 'Remove')]
    [string]$Action = 'Ensure',

    [ValidateRange(1, 65535)]
    [int]$WebPort = 8088,

    [ValidateRange(1, 65535)]
    [int]$MqttPort = 1884,

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

$ruleSpecifications = @(
    [pscustomobject]@{
        Name = 'HighTac.Platform.Web.LocalSubnet'
        DisplayName = 'HighTac Platform Web/API (Private LAN)'
        Port = $WebPort
    },
    [pscustomobject]@{
        Name = 'HighTac.Platform.Mqtt.LocalSubnet'
        DisplayName = 'HighTac MQTT Broker (Private LAN)'
        Port = $MqttPort
    }
)

foreach ($specification in $ruleSpecifications) {
    $existing = @(Get-NetFirewallRule -Name $specification.Name -ErrorAction SilentlyContinue)

    if ($Action -eq 'Remove') {
        foreach ($rule in $existing) {
            if ($PSCmdlet.ShouldProcess($rule.Name, 'Remove HighTac firewall rule')) {
                Remove-NetFirewallRule -InputObject $rule
            }
        }
        continue
    }

    $isCompliant = $false
    if ($existing.Count -eq 1) {
        $rule = $existing[0]
        $portFilter = $rule | Get-NetFirewallPortFilter
        $addressFilter = $rule | Get-NetFirewallAddressFilter
        $remoteAddresses = @($addressFilter.RemoteAddress)
        $isCompliant = (
            [string]$rule.Enabled -eq 'True' -and
            [string]$rule.Direction -eq 'Inbound' -and
            [string]$rule.Action -eq 'Allow' -and
            [string]$rule.Profile -eq 'Private' -and
            ([string]$portFilter.Protocol -eq 'TCP' -or [string]$portFilter.Protocol -eq '6') -and
            [string]$portFilter.LocalPort -eq [string]$specification.Port -and
            $remoteAddresses.Count -eq 1 -and
            [string]$remoteAddresses[0] -eq 'LocalSubnet'
        )
    }

    if ($isCompliant) {
        [pscustomobject]@{
            Name = $specification.Name
            Port = $specification.Port
            Status = 'Unchanged'
        }
        continue
    }

    if ($existing.Count -gt 0 -and $PSCmdlet.ShouldProcess($specification.Name, 'Replace noncompliant HighTac firewall rule')) {
        $existing | Remove-NetFirewallRule
    }

    if ($PSCmdlet.ShouldProcess($specification.Name, 'Create Private LocalSubnet inbound firewall rule')) {
        New-NetFirewallRule `
            -Name $specification.Name `
            -DisplayName $specification.DisplayName `
            -Group 'HighTac Platform' `
            -Enabled True `
            -Direction Inbound `
            -Action Allow `
            -Profile Private `
            -Protocol TCP `
            -LocalPort $specification.Port `
            -RemoteAddress LocalSubnet | Out-Null

        [pscustomobject]@{
            Name = $specification.Name
            Port = $specification.Port
            Status = 'Created'
        }
    }
}
