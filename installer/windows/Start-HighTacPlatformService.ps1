[CmdletBinding()]
param(
    [string]$InstallRoot = (Join-Path $env:ProgramFiles 'HighTac\Platform'),

    [string]$DataRoot = (Join-Path $env:ProgramData 'HighTac\Platform'),

    [ValidateRange(5, 120)]
    [int]$BrokerStartTimeoutSeconds = 45,

    [ValidateRange(5, 120)]
    [int]$TrustedLanProfileTimeoutSeconds = 45
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Resolve-ServicePath {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    return [IO.Path]::GetFullPath([Environment]::ExpandEnvironmentVariables($Path)).TrimEnd('\')
}

function ConvertFrom-DotEnvQuotedValue {
    param(
        [Parameter(Mandatory = $true)]
        [AllowEmptyString()]
        [string]$Value
    )

    $builder = New-Object Text.StringBuilder
    for ($index = 0; $index -lt $Value.Length; $index++) {
        $character = $Value[$index]
        if ($character -ne '\') {
            [void]$builder.Append($character)
            continue
        }
        if ($index + 1 -ge $Value.Length) {
            throw 'Runtime environment file contains an incomplete escape sequence.'
        }
        $index++
        switch ($Value[$index]) {
            'n' { [void]$builder.Append("`n") }
            'r' { [void]$builder.Append("`r") }
            '"' { [void]$builder.Append('"') }
            '\' { [void]$builder.Append('\') }
            default { throw 'Runtime environment file contains an unsupported escape sequence.' }
        }
    }
    return $builder.ToString()
}

function Read-HighTacServiceEnvironment {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Protected HighTac runtime environment file is missing: $Path"
    }
    $item = Get-Item -LiteralPath $Path -Force
    if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw 'Protected HighTac runtime environment file cannot be a symbolic link.'
    }

    $allowedSids = @('S-1-5-18', 'S-1-5-32-544')
    $acl = Get-Acl -LiteralPath $Path
    foreach ($rule in @($acl.Access)) {
        if ($rule.AccessControlType -ne [Security.AccessControl.AccessControlType]::Allow) {
            continue
        }
        try {
            $sid = $rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value
        }
        catch {
            throw 'Protected HighTac runtime environment file has an unrecognized access principal.'
        }
        if ($sid -notin $allowedSids) {
            throw 'Protected HighTac runtime environment file grants access outside LocalSystem and Administrators.'
        }
    }

    $values = @{}
    foreach ($line in [IO.File]::ReadAllLines($Path)) {
        if ([string]::IsNullOrWhiteSpace($line) -or $line.TrimStart().StartsWith('#')) {
            continue
        }
        if ($line -notmatch '^([A-Z][A-Z0-9_]*)="((?:[^"\\]|\\.)*)"$') {
            throw 'Protected HighTac runtime environment file contains an invalid entry.'
        }
        $name = $Matches[1]
        $encodedValue = $Matches[2]
        if ($name -notmatch '^HIGHTAC_[A-Z0-9_]+$') {
            throw 'Protected runtime environment file contains a variable outside the HIGHTAC_ namespace.'
        }
        if ($values.ContainsKey($name)) {
            throw "Protected runtime environment file contains duplicate variable '$name'."
        }
        $values[$name] = ConvertFrom-DotEnvQuotedValue -Value $encodedValue
    }
    return $values
}

function Test-LocalTcpPort {
    param(
        [Parameter(Mandatory = $true)]
        [int]$Port
    )

    $client = New-Object Net.Sockets.TcpClient
    try {
        $result = $client.BeginConnect('127.0.0.1', $Port, $null, $null)
        if (-not $result.AsyncWaitHandle.WaitOne(500, $false)) {
            return $false
        }
        $client.EndConnect($result)
        return $true
    }
    catch {
        return $false
    }
    finally {
        $client.Dispose()
    }
}

function Repair-HighTacTrustedLanProfile {
    param(
        [Parameter(Mandatory = $true)]
        [string]$DataRoot,

        [Parameter(Mandatory = $true)]
        [int]$TimeoutSeconds
    )

    $statePath = Join-Path $DataRoot 'config\install-state.json'
    if (-not (Test-Path -LiteralPath $statePath -PathType Leaf)) {
        return
    }

    $item = Get-Item -LiteralPath $statePath -Force
    if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw 'HighTac install state cannot be a symbolic link.'
    }

    try {
        $state = [IO.File]::ReadAllText($statePath) | ConvertFrom-Json -ErrorAction Stop
    }
    catch {
        throw "HighTac install state is not valid JSON: $($_.Exception.Message)"
    }

    $interfaceAlias = [string]$state.interface_alias
    $lanIPv4 = [string]$state.lan_ipv4
    $parsedAddress = $null
    if ([string]::IsNullOrWhiteSpace($interfaceAlias) -or
        -not [Net.IPAddress]::TryParse($lanIPv4, [ref]$parsedAddress) -or
        $parsedAddress.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork -or
        [Net.IPAddress]::IsLoopback($parsedAddress)) {
        return
    }

    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    $lastFailure = 'The configured LAN adapter has not become ready.'
    do {
        try {
            $addresses = @(Get-NetIPAddress `
                -InterfaceAlias $interfaceAlias `
                -AddressFamily IPv4 `
                -ErrorAction Stop | Where-Object { $_.IPAddress -ceq $lanIPv4 })
            if ($addresses.Count -gt 1) {
                throw "Configured LAN address '$lanIPv4' is ambiguous on adapter '$interfaceAlias'."
            }
            if ($addresses.Count -eq 1) {
                $interface = Get-NetIPInterface `
                    -InterfaceIndex $addresses[0].InterfaceIndex `
                    -AddressFamily IPv4 `
                    -ErrorAction Stop
                if ([string]$interface.Dhcp -ne 'Disabled') {
                    throw "Configured HighTac LAN adapter '$interfaceAlias' no longer uses a static IPv4 address."
                }

                $profiles = @(Get-NetConnectionProfile `
                    -InterfaceIndex $addresses[0].InterfaceIndex `
                    -ErrorAction SilentlyContinue)
                if ($profiles.Count -gt 1) {
                    throw "Configured LAN adapter '$interfaceAlias' has an ambiguous Windows network profile."
                }
                if ($profiles.Count -eq 1) {
                    if ([string]$profiles[0].NetworkCategory -eq 'Private') {
                        return
                    }
                    if ([string]$profiles[0].NetworkCategory -eq 'DomainAuthenticated') {
                        throw "Configured LAN adapter '$interfaceAlias' is domain-authenticated and cannot be changed by HighTac."
                    }

                    Set-NetConnectionProfile `
                        -InterfaceIndex $addresses[0].InterfaceIndex `
                        -NetworkCategory Private `
                        -ErrorAction Stop
                    $verified = Get-NetConnectionProfile `
                        -InterfaceIndex $addresses[0].InterfaceIndex `
                        -ErrorAction Stop
                    if ([string]$verified.NetworkCategory -eq 'Private') {
                        return
                    }
                    throw "Configured LAN adapter '$interfaceAlias' did not remain on the Private network profile."
                }
            }
        }
        catch {
            $lastFailure = $_.Exception.Message
        }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)

    throw "HighTac could not restore the trusted Private LAN profile for '$interfaceAlias' ($lanIPv4): $lastFailure"
}

if ($env:OS -ne 'Windows_NT') {
    throw 'HighTacPlatform service launcher requires Windows.'
}

$InstallRoot = Resolve-ServicePath -Path $InstallRoot
$DataRoot = Resolve-ServicePath -Path $DataRoot
$canonicalInstallRoot = Resolve-ServicePath -Path (Join-Path $env:ProgramFiles 'HighTac\Platform')
$canonicalDataRoot = Resolve-ServicePath -Path (Join-Path $env:ProgramData 'HighTac\Platform')
if ($InstallRoot -ne $canonicalInstallRoot -or $DataRoot -ne $canonicalDataRoot) {
    throw 'HighTacPlatform service paths do not match the fixed installation contract.'
}

$environmentPath = Join-Path $DataRoot 'config\.env'
$serviceEnvironment = Read-HighTacServiceEnvironment -Path $environmentPath
$requiredVariables = @(
    'HIGHTAC_ENVIRONMENT',
    'HIGHTAC_DATA_DIR',
    'HIGHTAC_BOOTSTRAP_ADMIN_USERNAME',
    'HIGHTAC_BOOTSTRAP_ADMIN_PASSWORD',
    'HIGHTAC_MQTT_HOST',
    'HIGHTAC_MQTT_PORT',
    'HIGHTAC_MQTT_USERNAME',
    'HIGHTAC_MQTT_PASSWORD',
    'HIGHTAC_BROKER_MODE',
    'HIGHTAC_BROKER_SERVICE_NAME'
)
foreach ($name in $requiredVariables) {
    if (-not $serviceEnvironment.ContainsKey($name) -or [string]::IsNullOrWhiteSpace([string]$serviceEnvironment[$name])) {
        throw "Protected runtime environment is missing required variable '$name'."
    }
}
if ($serviceEnvironment.HIGHTAC_ENVIRONMENT -cne 'production' -or
    $serviceEnvironment.HIGHTAC_BROKER_MODE -cne 'windows_service' -or
    $serviceEnvironment.HIGHTAC_BROKER_SERVICE_NAME -cne 'HighTacMqttBroker' -or
    $serviceEnvironment.HIGHTAC_MQTT_HOST -cne '127.0.0.1') {
    throw 'Protected runtime environment does not match the production Windows service contract.'
}

Repair-HighTacTrustedLanProfile `
    -DataRoot $DataRoot `
    -TimeoutSeconds $TrustedLanProfileTimeoutSeconds

$mqttPort = 0
if (-not [int]::TryParse([string]$serviceEnvironment.HIGHTAC_MQTT_PORT, [ref]$mqttPort) -or
    $mqttPort -lt 1 -or $mqttPort -gt 65535) {
    throw 'Protected runtime environment contains an invalid MQTT port.'
}

foreach ($name in $serviceEnvironment.Keys) {
    [Environment]::SetEnvironmentVariable($name, [string]$serviceEnvironment[$name], 'Process')
}

try {
    $broker = Get-Service -Name 'HighTacMqttBroker' -ErrorAction SilentlyContinue
    if ($null -eq $broker) {
        throw 'Required HighTacMqttBroker Windows service is not installed.'
    }
    if ($broker.Status -ne [System.ServiceProcess.ServiceControllerStatus]::Running) {
        Start-Service -Name 'HighTacMqttBroker' -ErrorAction Stop
    }

    $deadline = [DateTime]::UtcNow.AddSeconds($BrokerStartTimeoutSeconds)
    do {
        $broker = Get-Service -Name 'HighTacMqttBroker' -ErrorAction Stop
        if ($broker.Status -eq [System.ServiceProcess.ServiceControllerStatus]::Running -and
            (Test-LocalTcpPort -Port $mqttPort)) {
            break
        }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $deadline)

    if ($broker.Status -ne [System.ServiceProcess.ServiceControllerStatus]::Running -or
        -not (Test-LocalTcpPort -Port $mqttPort)) {
        throw 'HighTacMqttBroker did not become ready before the platform service startup timeout.'
    }

    $platformExecutable = Join-Path $InstallRoot 'server\HighTacPlatform.exe'
    if (-not (Test-Path -LiteralPath $platformExecutable -PathType Leaf)) {
        throw "HighTacPlatform executable is missing: $platformExecutable"
    }

    & $platformExecutable serve
    $platformExitCode = $LASTEXITCODE
}
finally {
    foreach ($name in @($serviceEnvironment.Keys)) {
        [Environment]::SetEnvironmentVariable($name, $null, 'Process')
    }
    $serviceEnvironment.Clear()
}

exit $platformExitCode
