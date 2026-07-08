[CmdletBinding()]
param(
    [ValidateRange(1, 65535)]
    [int]$Port = 1883,

    [string]$ContainerName = 'hightac-mqtt'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-ToolStatus {
    param([Parameter(Mandatory = $true)][string]$Name)

    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }

    return 'not found'
}

Write-Host "Tool status"
Write-Host "  docker        : $(Get-ToolStatus -Name 'docker')"
Write-Host "  mosquitto     : $(Get-ToolStatus -Name 'mosquitto')"
Write-Host "  mosquitto_pub : $(Get-ToolStatus -Name 'mosquitto_pub')"
Write-Host "  mosquitto_sub : $(Get-ToolStatus -Name 'mosquitto_sub')"
Write-Host "  choco         : $(Get-ToolStatus -Name 'choco')"
Write-Host ''

Write-Host "LAN IPv4 candidates"
$ips = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
    Where-Object {
        $_.IPAddress -notlike '127.*' -and
        $_.IPAddress -notlike '169.254.*' -and
        $_.PrefixOrigin -ne 'WellKnown'
    } |
    Select-Object InterfaceAlias, IPAddress, PrefixLength

if ($ips) {
    $ips | Format-Table -AutoSize | Out-String | Write-Host
}
else {
    Write-Host "  no non-loopback IPv4 address detected"
}

Write-Host "TCP listener on port $Port"
$listeners = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
if ($listeners) {
    foreach ($listener in $listeners) {
        $process = Get-Process -Id $listener.OwningProcess -ErrorAction SilentlyContinue
        $processName = if ($process) { $process.ProcessName } else { '<unknown>' }
        Write-Host ("  {0}:{1} PID {2} {3}" -f $listener.LocalAddress, $listener.LocalPort, $listener.OwningProcess, $processName)
    }
}
else {
    Write-Host "  no listener detected"
}
Write-Host ''

Write-Host "Docker container"
if ((Get-Command docker -ErrorAction SilentlyContinue)) {
    $container = (& docker ps -a --filter "name=^/$ContainerName$" --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}')
    if ($container) {
        $container | Write-Host
    }
    else {
        Write-Host "  container '$ContainerName' not found"
    }
}
else {
    Write-Host "  docker.exe not found"
}
Write-Host ''

Write-Host "Windows firewall rule hint"
$ruleName = "HighTac MQTT Broker TCP $Port"
$rule = Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue
if ($rule) {
    $rule | Select-Object DisplayName, Enabled, Direction, Action, Profile | Format-Table -AutoSize | Out-String | Write-Host
}
else {
    Write-Host "  rule '$ruleName' not found"
    Write-Host "  suggested admin command:"
    Write-Host "  New-NetFirewallRule -DisplayName '$ruleName' -Direction Inbound -Action Allow -Protocol TCP -LocalPort $Port -Profile Private"
}
