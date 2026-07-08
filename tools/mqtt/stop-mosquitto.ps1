[CmdletBinding()]
param(
    [ValidateSet('Auto', 'Native', 'Docker')]
    [string]$Mode = 'Auto',

    [string]$ContainerName = 'hightac-mqtt'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$ScriptDir = Split-Path -Parent $PSCommandPath
$RuntimeDir = Join-Path $ScriptDir 'runtime'
$PidFile = Join-Path $RuntimeDir 'mosquitto.pid'

function Get-ToolPath {
    param([Parameter(Mandatory = $true)][string]$Name)

    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }

    return $null
}

function Stop-NativeMosquitto {
    if (-not (Test-Path -LiteralPath $PidFile)) {
        Write-Host "No native Mosquitto PID file found at $PidFile."
        return
    }

    $pidText = Get-Content -LiteralPath $PidFile | Select-Object -First 1
    if (-not $pidText) {
        Write-Host "Native Mosquitto PID file is empty."
        return
    }

    $process = Get-Process -Id ([int]$pidText) -ErrorAction SilentlyContinue
    if (-not $process) {
        Write-Host "No process is running for PID $pidText."
        Remove-Item -LiteralPath $PidFile -Force
        return
    }

    Stop-Process -Id ([int]$pidText) -Force
    Remove-Item -LiteralPath $PidFile -Force
    Write-Host "Stopped native Mosquitto PID $pidText."
}

function Stop-DockerMosquitto {
    if (-not (Get-ToolPath -Name 'docker')) {
        Write-Host "docker.exe was not found on PATH."
        return
    }

    $containerId = (& docker ps -a --filter "name=^/$ContainerName$" --format '{{.ID}}').Trim()
    if (-not $containerId) {
        Write-Host "Docker container '$ContainerName' was not found."
        return
    }

    $isRunning = (& docker inspect -f '{{.State.Running}}' $ContainerName).Trim()
    if ($isRunning -ne 'true') {
        Write-Host "Docker container '$ContainerName' is already stopped."
        return
    }

    & docker stop $ContainerName | Out-Null
    Write-Host "Stopped Docker container '$ContainerName'."
}

if ($Mode -eq 'Native') {
    Stop-NativeMosquitto
}
elseif ($Mode -eq 'Docker') {
    Stop-DockerMosquitto
}
else {
    Stop-NativeMosquitto
    Stop-DockerMosquitto
}
