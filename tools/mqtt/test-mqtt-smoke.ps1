[CmdletBinding()]
param(
    [ValidateSet('Auto', 'Native', 'Docker')]
    [string]$ClientMode = 'Auto',

    [string]$HostName = '127.0.0.1',

    [ValidateRange(1, 65535)]
    [int]$Port = 1884,

    [string]$Username = 'hightac_mqtt',

    [string]$Password,

    [string]$StationId = '90A9F0000000',

    [string]$Image = 'eclipse-mosquitto:2'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ($Username -match '[:\s]') {
    throw "Username must not contain ':' or whitespace."
}

function Get-ToolPath {
    param([Parameter(Mandatory = $true)][string]$Name)

    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }

    return $null
}

function ConvertFrom-SecureStringToPlainText {
    param([Parameter(Mandatory = $true)][securestring]$SecureString)

    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($SecureString)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
    }
    finally {
        if ($bstr -ne [IntPtr]::Zero) {
            [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
        }
    }
}

function Get-MqttPassword {
    if ($Password) {
        return $Password
    }

    $secure = Read-Host "Enter MQTT password for user '$Username'" -AsSecureString
    $plain = ConvertFrom-SecureStringToPlainText -SecureString $secure
    if (-not $plain) {
        throw "MQTT password must not be empty."
    }

    return $plain
}

function Resolve-ClientMode {
    if ($ClientMode -ne 'Auto') {
        return $ClientMode
    }

    if ((Get-ToolPath -Name 'mosquitto_pub') -and (Get-ToolPath -Name 'mosquitto_sub')) {
        return 'Native'
    }

    if (Get-ToolPath -Name 'docker') {
        return 'Docker'
    }

    throw "No MQTT client found. Install mosquitto_pub/sub, or install Docker Desktop and rerun with -ClientMode Docker."
}

function Convert-HostForDockerClient {
    param([Parameter(Mandatory = $true)][string]$InputHost)

    if ($InputHost -in @('127.0.0.1', 'localhost', '::1')) {
        return 'host.docker.internal'
    }

    return $InputHost
}

$plainPassword = Get-MqttPassword
$mode = Resolve-ClientMode
$topic = "/estation/$StationId/heartbeat"
$payload = '{"ID":"' + $StationId + '","MAC":"00:00:00:00:00:00","Alias":"local-smoke","ServerAddress":"' + $HostName + ':' + $Port + '","Parameters":["' + $Username + '","***"],"Heartbeat":20,"AppVersion":"smoke","TotalCount":0,"SendCount":0}'

Write-Host "Subscribing to $topic, then publishing one heartbeat payload."

if ($mode -eq 'Native') {
    $subExe = Get-ToolPath -Name 'mosquitto_sub'
    $pubExe = Get-ToolPath -Name 'mosquitto_pub'

    $job = Start-Job -ScriptBlock {
        param($SubExe, $HostName, $Port, $Username, $Password, $Topic)
        & $SubExe -h $HostName -p $Port -u $Username -P $Password -t $Topic -C 1 -W 10
    } -ArgumentList $subExe, $HostName, $Port, $Username, $plainPassword, $topic

    Start-Sleep -Seconds 1
    & $pubExe -h $HostName -p $Port -u $Username -P $plainPassword -t $topic -m $payload
    if ($LASTEXITCODE -ne 0) {
        throw "mosquitto_pub failed with exit code $LASTEXITCODE."
    }

    $result = Receive-Job -Job $job -Wait -AutoRemoveJob
}
else {
    if (-not (Get-ToolPath -Name 'docker')) {
        throw "docker.exe was not found on PATH."
    }

    $clientHost = Convert-HostForDockerClient -InputHost $HostName
    $job = Start-Job -ScriptBlock {
        param($Image, $HostName, $Port, $Username, $Password, $Topic)
        & docker run --rm $Image mosquitto_sub -h $HostName -p $Port -u $Username -P $Password -t $Topic -C 1 -W 10
    } -ArgumentList $Image, $clientHost, $Port, $Username, $plainPassword, $topic

    Start-Sleep -Seconds 3
    & docker run --rm $Image mosquitto_pub -h $clientHost -p $Port -u $Username -P $plainPassword -t $topic -m $payload
    if ($LASTEXITCODE -ne 0) {
        throw "docker mosquitto_pub failed with exit code $LASTEXITCODE."
    }

    $result = Receive-Job -Job $job -Wait -AutoRemoveJob
}

Write-Host ''
Write-Host "Received payload:"
$result | Write-Host

if (($result -join "`n") -notmatch [regex]::Escape($StationId)) {
    throw "Smoke test did not receive the expected StationId."
}

Write-Host ''
Write-Host "MQTT smoke test passed."
