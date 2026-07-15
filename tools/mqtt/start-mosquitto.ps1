[CmdletBinding()]
param(
    [ValidateSet('Auto', 'Native', 'Docker')]
    [string]$Mode = 'Auto',

    [ValidateRange(1, 65535)]
    [int]$Port = 1884,

    [string]$Username = 'hightac_mqtt',

    [string]$Password,

    [switch]$ResetPassword,

    [string]$ContainerName = 'hightac-mqtt',

    [string]$Image = 'eclipse-mosquitto:2',

    [switch]$Foreground
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ($Username -match '[:\s]') {
    throw "Username must not contain ':' or whitespace."
}

$ScriptDir = Split-Path -Parent $PSCommandPath
$RuntimeDir = Join-Path $ScriptDir 'runtime'
$ConfigDir = Join-Path $RuntimeDir 'config'
$DataDir = Join-Path $RuntimeDir 'data'
$LogDir = Join-Path $RuntimeDir 'log'
$PidFile = Join-Path $RuntimeDir 'mosquitto.pid'

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

function Convert-ToMosquittoPath {
    param([Parameter(Mandatory = $true)][string]$Path)

    return $Path.Replace('\', '/')
}

function Test-DockerReady {
    if (-not (Get-ToolPath -Name 'docker')) {
        return $false
    }

    & docker info *> $null
    return ($LASTEXITCODE -eq 0)
}

function Resolve-RunMode {
    $nativeMosquitto = Get-ToolPath -Name 'mosquitto'
    $nativePasswd = Get-ToolPath -Name 'mosquitto_passwd'
    $docker = Get-ToolPath -Name 'docker'

    if ($Mode -eq 'Auto') {
        if ($nativeMosquitto -and $nativePasswd) {
            return 'Native'
        }

        if ($docker -and (Test-DockerReady)) {
            return 'Docker'
        }

        if ($docker) {
            throw "Docker is installed but not ready. Start Docker Desktop, then rerun this script."
        }

        throw "No Mosquitto runtime found. Install native Mosquitto, or install Docker Desktop and rerun with -Mode Docker."
    }

    if ($Mode -eq 'Native') {
        if (-not $nativeMosquitto) {
            throw "mosquitto.exe was not found on PATH."
        }

        if (-not $nativePasswd) {
            throw "mosquitto_passwd.exe was not found on PATH."
        }
    }

    if ($Mode -eq 'Docker') {
        if (-not $docker) {
            throw "docker.exe was not found on PATH."
        }

        if (-not (Test-DockerReady)) {
            throw "Docker is installed but not ready. Start Docker Desktop, then rerun this script."
        }
    }

    return $Mode
}

function Ensure-RuntimeDirectories {
    foreach ($dir in @($RuntimeDir, $ConfigDir, $DataDir, $LogDir)) {
        if (-not (Test-Path -LiteralPath $dir)) {
            New-Item -ItemType Directory -Path $dir | Out-Null
        }
    }
}

function Ensure-PasswordFile {
    param([Parameter(Mandatory = $true)][string]$ResolvedMode)

    $passwordFile = Join-Path $ConfigDir 'passwordfile'
    $hasRequestedUser = $false

    if (Test-Path -LiteralPath $passwordFile) {
        $pattern = '^{0}:' -f [regex]::Escape($Username)
        $hasRequestedUser = [bool](Select-String -LiteralPath $passwordFile -Pattern $pattern -Quiet)
    }

    if ((Test-Path -LiteralPath $passwordFile) -and $hasRequestedUser -and -not $ResetPassword) {
        return $passwordFile
    }

    $plainPassword = Get-MqttPassword
    $createFlag = (-not (Test-Path -LiteralPath $passwordFile)) -or $ResetPassword

    Write-Host "Creating/updating Mosquitto password file for user '$Username'."

    if ($ResolvedMode -eq 'Native') {
        $passwdExe = Get-ToolPath -Name 'mosquitto_passwd'
        $args = @()
        if ($createFlag) {
            $args += '-c'
        }
        $args += @('-b', $passwordFile, $Username, $plainPassword)

        & $passwdExe @args | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "mosquitto_passwd failed with exit code $LASTEXITCODE."
        }
    }
    else {
        $volume = '{0}:/mosquitto/config' -f $ConfigDir
        $args = @('run', '--rm', '-v', $volume, $Image, 'mosquitto_passwd')
        if ($createFlag) {
            $args += '-c'
        }
        $args += @('-b', '/mosquitto/config/passwordfile', $Username, $plainPassword)

        & docker @args | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "docker mosquitto_passwd failed with exit code $LASTEXITCODE."
        }
    }

    return $passwordFile
}

function Write-AclFile {
    $aclFile = Join-Path $ConfigDir 'aclfile'
    @(
        '# Generated by tools/mqtt/start-mosquitto.ps1',
        '# Keep this LAN broker scoped to HighTac eStation topics.',
        "user $Username",
        'topic readwrite /estation/#',
        'topic readwrite hightac/local/#',
        'topic read $SYS/#'
    ) | Set-Content -LiteralPath $aclFile -Encoding ASCII

    return $aclFile
}

function Write-MosquittoConfig {
    param(
        [Parameter(Mandatory = $true)][string]$ResolvedMode,
        [Parameter(Mandatory = $true)][string]$PasswordFile,
        [Parameter(Mandatory = $true)][string]$AclFile
    )

    $configFile = Join-Path $ConfigDir 'mosquitto.conf'

    if ($ResolvedMode -eq 'Docker') {
        $listenPort = 1883
        $passwordPath = '/mosquitto/config/passwordfile'
        $aclPath = '/mosquitto/config/aclfile'
        $persistencePath = '/mosquitto/data/'
        $logPath = '/mosquitto/log/mosquitto.log'
    }
    else {
        $listenPort = $Port
        $passwordPath = Convert-ToMosquittoPath -Path $PasswordFile
        $aclPath = Convert-ToMosquittoPath -Path $AclFile
        $persistencePath = Convert-ToMosquittoPath -Path ($DataDir.TrimEnd('\') + '\')
        $logPath = Convert-ToMosquittoPath -Path (Join-Path $LogDir 'mosquitto.log')
    }

    $config = @"
# Generated by tools/mqtt/start-mosquitto.ps1
listener $listenPort 0.0.0.0
protocol mqtt

allow_anonymous false
password_file $passwordPath
acl_file $aclPath

persistence true
persistence_location $persistencePath
autosave_interval 60

log_dest stdout
log_dest file $logPath
log_type error
log_type warning
log_type notice
log_type information
connection_messages true
"@

    $config | Set-Content -LiteralPath $configFile -Encoding ASCII
    return $configFile
}

function Get-PrimaryLanAddress {
    $ip = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object {
            $_.IPAddress -notlike '127.*' -and
            $_.IPAddress -notlike '169.254.*' -and
            $_.PrefixOrigin -ne 'WellKnown' -and
            $_.InterfaceAlias -notmatch '^(vEthernet|VirtualBox|VMware|Loopback|utun|vgate)'
        } |
        Sort-Object @{ Expression = { if ($_.InterfaceAlias -match 'Wi-?Fi|WLAN|Ethernet') { 0 } else { 1 } } }, PrefixLength, InterfaceMetric, InterfaceAlias |
        Select-Object -First 1 -ExpandProperty IPAddress

    if ($ip) {
        return $ip
    }

    return '<this-pc-lan-ip>'
}

function Test-NativeProcessRunning {
    if (-not (Test-Path -LiteralPath $PidFile)) {
        return $false
    }

    $pidText = (Get-Content -LiteralPath $PidFile -ErrorAction SilentlyContinue | Select-Object -First 1)
    if (-not $pidText) {
        return $false
    }

    $process = Get-Process -Id ([int]$pidText) -ErrorAction SilentlyContinue
    return [bool]$process
}

function Start-NativeMosquitto {
    param([Parameter(Mandatory = $true)][string]$ConfigFile)

    $mosquittoExe = Get-ToolPath -Name 'mosquitto'

    if ((Test-NativeProcessRunning) -and -not $ResetPassword) {
        $pidText = Get-Content -LiteralPath $PidFile | Select-Object -First 1
        Write-Host "Native Mosquitto already appears to be running, PID $pidText."
        return
    }

    if ((Test-NativeProcessRunning) -and $ResetPassword) {
        $pidText = Get-Content -LiteralPath $PidFile | Select-Object -First 1
        Write-Host "Restarting native Mosquitto after password/config update, PID $pidText."
        Stop-Process -Id ([int]$pidText) -Force
        Start-Sleep -Seconds 1
    }

    if ($Foreground) {
        Write-Host "Starting native Mosquitto in the foreground. Press Ctrl+C to stop."
        & $mosquittoExe -c $ConfigFile -v
        return
    }

    $argumentLine = '-c "{0}" -v' -f $ConfigFile
    $process = Start-Process -FilePath $mosquittoExe -ArgumentList $argumentLine -WorkingDirectory $RuntimeDir -WindowStyle Hidden -PassThru
    $process.Id | Set-Content -LiteralPath $PidFile -Encoding ASCII
    Start-Sleep -Seconds 1

    if (-not (Get-Process -Id $process.Id -ErrorAction SilentlyContinue)) {
        throw "Native Mosquitto exited immediately. Check $LogDir."
    }

    Write-Host "Native Mosquitto started, PID $($process.Id)."
}

function Start-DockerMosquitto {
    param([Parameter(Mandatory = $true)][string]$ConfigFile)

    $containerId = (& docker ps -a --filter "name=^/$ContainerName$" --format '{{.ID}}').Trim()

    if ($containerId) {
        $isRunning = (& docker inspect -f '{{.State.Running}}' $ContainerName).Trim()
        if ($isRunning -eq 'true') {
            if ($ResetPassword) {
                Write-Host "Restarting Docker container '$ContainerName' after password/config update."
                & docker restart $ContainerName | Out-Null
            }
            else {
                Write-Host "Docker container '$ContainerName' is already running."
            }
        }
        else {
            Write-Host "Starting existing Docker container '$ContainerName'."
            & docker start $ContainerName | Out-Null
        }

        return
    }

    $configVolume = '{0}:/mosquitto/config' -f $ConfigDir
    $dataVolume = '{0}:/mosquitto/data' -f $DataDir
    $logVolume = '{0}:/mosquitto/log' -f $LogDir
    $portMapping = '{0}:1883' -f $Port

    Write-Host "Starting Docker container '$ContainerName' from image '$Image'."
    & docker run `
        --detach `
        --name $ContainerName `
        --publish $portMapping `
        --volume $configVolume `
        --volume $dataVolume `
        --volume $logVolume `
        $Image

    if ($LASTEXITCODE -ne 0) {
        throw "docker run failed with exit code $LASTEXITCODE."
    }
}

Ensure-RuntimeDirectories
$resolvedMode = Resolve-RunMode
$passwordFile = Ensure-PasswordFile -ResolvedMode $resolvedMode
$aclFile = Write-AclFile
$configFile = Write-MosquittoConfig -ResolvedMode $resolvedMode -PasswordFile $passwordFile -AclFile $aclFile

if ($resolvedMode -eq 'Native') {
    Start-NativeMosquitto -ConfigFile $configFile
}
else {
    Start-DockerMosquitto -ConfigFile $configFile
}

$lanIp = Get-PrimaryLanAddress
Write-Host ''
Write-Host "MQTT broker mode : $resolvedMode"
Write-Host "Broker address   : mqtt://$lanIp`:$Port"
Write-Host "Username         : $Username"
Write-Host "Config file      : $configFile"
Write-Host "Runtime data     : $RuntimeDir"
Write-Host ''
Write-Host "If LAN clients cannot connect, allow inbound TCP $Port on the Windows Private network profile."
