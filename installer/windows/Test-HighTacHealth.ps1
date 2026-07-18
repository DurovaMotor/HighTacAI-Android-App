[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'Low')]
param(
    [ValidateRange(1, 65535)]
    [int]$WebPort = 8088,

    [ValidateRange(1, 65535)]
    [int]$MqttPort = 1884,

    [ValidateRange(1, 30)]
    [int]$TimeoutSeconds = 5,

    [switch]$AllowUnhealthy,

    [switch]$Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'HighTacInstaller.Common.psm1') -Force

if ($Force) {
    $ConfirmPreference = 'None'
}

Assert-HighTacAdministrator

if (-not $PSCmdlet.ShouldProcess('HighTacPlatform and HighTacMqttBroker', 'Run read-only service, TCP, and HTTP health probes')) {
    return
}

$results = New-Object 'System.Collections.Generic.List[object]'

foreach ($serviceName in @('HighTacMqttBroker', 'HighTacPlatform')) {
    $service = Get-HighTacService -Name $serviceName
    $healthy = $null -ne $service -and $service.Status -eq 'Running'
    $detail = if ($null -eq $service) { 'Not installed' } else { [string]$service.Status }
    $results.Add([pscustomobject]@{
        Check = "service:$serviceName"
        Healthy = $healthy
        Detail = $detail
    })
}

foreach ($portCheck in @(
    [pscustomobject]@{ Name = 'tcp:mqtt'; Port = $MqttPort },
    [pscustomobject]@{ Name = 'tcp:web'; Port = $WebPort }
)) {
    $healthy = Test-HighTacTcpPort -HostName '127.0.0.1' -Port $portCheck.Port -TimeoutMilliseconds ($TimeoutSeconds * 1000)
    $results.Add([pscustomobject]@{
        Check = $portCheck.Name
        Healthy = $healthy
        Detail = "127.0.0.1:$($portCheck.Port)"
    })
}

foreach ($endpoint in @('live', 'ready')) {
    $uri = "http://127.0.0.1:$WebPort/api/v1/health/$endpoint"
    $healthy = $false
    $detail = 'No response'
    try {
        $response = Invoke-WebRequest -Uri $uri -UseBasicParsing -TimeoutSec $TimeoutSeconds
        $healthy = $response.StatusCode -ge 200 -and $response.StatusCode -lt 300
        $detail = "HTTP $($response.StatusCode)"
    }
    catch {
        if ($null -ne $_.Exception.Response) {
            $detail = "HTTP $([int]$_.Exception.Response.StatusCode)"
        }
        else {
            $detail = $_.Exception.Message
        }
    }
    $results.Add([pscustomobject]@{
        Check = "http:$endpoint"
        Healthy = $healthy
        Detail = $detail
    })
}

$results
$failures = @($results | Where-Object { -not $_.Healthy })
if (-not $AllowUnhealthy -and $failures.Count -gt 0) {
    throw "HighTac health check failed: $(($failures.Check) -join ', ')"
}
