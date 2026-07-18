[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'Low')]
param(
    [Parameter(Mandatory = $true)]
    [string]$PasswordFile,

    [switch]$Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'HighTacInstaller.Common.psm1') -Force

if ($Force) {
    $ConfirmPreference = 'None'
}

Assert-HighTacAdministrator
if (-not $PSCmdlet.ShouldProcess($PasswordFile, 'Validate the single legacy station account without displaying its hash')) {
    return
}

$entry = Get-HighTacLegacyMosquittoPasswordEntry `
    -Path $PasswordFile `
    -ExpectedUsername 'hightac_mqtt'

[pscustomobject]@{
    Username = $entry.Username
    AccountCount = 1
    PasswordPreserved = $true
    PasswordCanBeReprinted = $false
}
