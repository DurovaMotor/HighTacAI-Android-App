[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'High')]
param(
    [string]$InstallRoot = (Join-Path $env:ProgramFiles 'HighTac\Platform'),

    [string]$DataRoot = (Join-Path $env:ProgramData 'HighTac\Platform'),

    [string]$TemplateRoot,

    [ValidateRange(1, 65535)]
    [int]$WebPort = 8088,

    [ValidateRange(1, 65535)]
    [int]$MqttPort = 1884,

    [string]$StationId,

    [ValidateLength(1, 100)]
    [string]$SiteName = 'HighTac Site',

    [string]$InterfaceAlias,

    [ValidatePattern('^(?:\d{1,3}\.){3}\d{1,3}$')]
    [string]$LanIPv4,

    [ValidatePattern('^[A-Za-z0-9_-]+$')]
    [string]$BackendMqttUsername = 'hightac_backend',

    [ValidatePattern('^[A-Za-z0-9_-]+$')]
    [string]$StationMqttUsername,

    [Security.SecureString]$BackendMqttPassword,

    [Security.SecureString]$StationMqttPassword,

    [string]$LegacyStationPasswordFile,

    [string]$LegacyRuntimeRoot,

    [switch]$SkipNetworkPreflight,

    [switch]$SkipHealthCheck,

    [switch]$Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'HighTacInstaller.Common.psm1') -Force

function Add-HighTacBootstrapAdminEnvironmentDefaults {
    [CmdletBinding(SupportsShouldProcess = $true)]
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    $content = [IO.File]::ReadAllText($Path)
    $defaults = [ordered]@{
        HIGHTAC_BOOTSTRAP_ADMIN_USERNAME = 'Adam'
    }
    $passwordPattern = '(?m)^\s*HIGHTAC_BOOTSTRAP_ADMIN_PASSWORD\s*='
    if ($content -notmatch $passwordPattern) {
        $defaults['HIGHTAC_BOOTSTRAP_ADMIN_PASSWORD'] = New-HighTacRandomPassword
    }
    $linesToAppend = New-Object 'System.Collections.Generic.List[string]'
    foreach ($name in $defaults.Keys) {
        $pattern = '(?m)^\s*' + [regex]::Escape($name) + '\s*='
        if ($content -notmatch $pattern) {
            $escapedValue = ConvertTo-HighTacYamlString -Value $defaults[$name]
            $linesToAppend.Add($name + '="' + $escapedValue + '"')
        }
    }
    if ($linesToAppend.Count -eq 0) {
        return
    }

    $updatedContent = $content
    if ($updatedContent.Length -gt 0 -and
        -not $updatedContent.EndsWith("`n", [StringComparison]::Ordinal)) {
        $updatedContent += "`r`n"
    }
    $updatedContent += ($linesToAppend -join "`r`n") + "`r`n"
    Write-HighTacTextFile -Path $Path -Content $updatedContent -Confirm:$false
}

$stationIdWasSupplied = $PSBoundParameters.ContainsKey('StationId')
$legacyImportRequested = $PSBoundParameters.ContainsKey('LegacyStationPasswordFile') -and
    -not [string]::IsNullOrWhiteSpace($LegacyStationPasswordFile)
if ($stationIdWasSupplied) {
    $StationId = ConvertTo-HighTacStationId -StationId $StationId
}

if ($Force) {
    $ConfirmPreference = 'None'
}
if (-not $TemplateRoot) {
    $scriptParent = Split-Path -Parent $PSScriptRoot
    $templateCandidates = @(
        (Join-Path $scriptParent 'templates'),
        (Join-Path $scriptParent 'config')
    )
    $TemplateRoot = $templateCandidates | Where-Object { Test-Path -LiteralPath $_ -PathType Container } | Select-Object -First 1
    if (-not $TemplateRoot) {
        $TemplateRoot = $templateCandidates[0]
    }
}

Assert-HighTacAdministrator

if (-not [Environment]::Is64BitOperatingSystem) {
    throw 'HighTac Platform requires a 64-bit Windows operating system.'
}
if ([Environment]::OSVersion.Version -lt [Version]'10.0.17763') {
    throw 'HighTac Platform requires Windows 10 version 1809 / Windows Server 2019 or newer.'
}
$InstallRoot = Assert-HighTacSafeRoot -Path $InstallRoot -Kind InstallRoot
$DataRoot = Assert-HighTacSafeRoot -Path $DataRoot -Kind DataRoot
$TemplateRoot = Resolve-HighTacFullPath -Path $TemplateRoot
$canonicalDataRoot = Resolve-HighTacFullPath -Path (Join-Path $env:ProgramData 'HighTac\Platform')
if ($DataRoot -ne $canonicalDataRoot) {
    throw "DataRoot is fixed by the WinSW service contract and must be '$canonicalDataRoot'."
}
if ($LegacyRuntimeRoot) {
    $LegacyRuntimeRoot = Resolve-HighTacFullPath -Path $LegacyRuntimeRoot
    $expectedLegacyRuntimeRoot = Resolve-HighTacFullPath -Path (Join-Path $InstallRoot 'server\runtime')
    if ($LegacyRuntimeRoot -ne $expectedLegacyRuntimeRoot) {
        throw "LegacyRuntimeRoot must be the prior server runtime directory '$expectedLegacyRuntimeRoot'."
    }
}

$existingState = Get-HighTacInstallState -DataRoot $DataRoot
if ($null -ne $existingState) {
    $stateValues = @{
        WebPort = 'web_port'
        MqttPort = 'mqtt_port'
        StationId = 'station_id'
        SiteName = 'site_name'
        InterfaceAlias = 'interface_alias'
        LanIPv4 = 'lan_ipv4'
        BackendMqttUsername = 'backend_mqtt_username'
        StationMqttUsername = 'station_mqtt_username'
    }
    foreach ($parameterName in $stateValues.Keys) {
        if (-not $PSBoundParameters.ContainsKey($parameterName)) {
            $property = $existingState.PSObject.Properties[$stateValues[$parameterName]]
            if ($null -ne $property -and $null -ne $property.Value -and [string]$property.Value -ne '') {
                Set-Variable -Name $parameterName -Value $property.Value
            }
        }
    }
}

if (-not $stationIdWasSupplied -and $StationId) {
    $StationId = ConvertTo-HighTacStationId -StationId $StationId
}
if ($legacyImportRequested) {
    if ($PSBoundParameters.ContainsKey('StationMqttPassword')) {
        throw 'LegacyStationPasswordFile cannot be combined with StationMqttPassword.'
    }
    if ($PSBoundParameters.ContainsKey('StationMqttUsername') -and $StationMqttUsername -cne 'hightac_mqtt') {
        throw "Legacy credential import is fixed to the existing station account 'hightac_mqtt'."
    }
    $StationMqttUsername = 'hightac_mqtt'
}
elseif (-not $StationMqttUsername -and $StationId) {
    $StationMqttUsername = "estation_$StationId"
}
if ($WebPort -eq $MqttPort) {
    throw 'WebPort and MqttPort must be different.'
}
if ($StationMqttUsername -and $BackendMqttUsername -eq $StationMqttUsername) {
    throw 'BackendMqttUsername and StationMqttUsername must be different accounts.'
}

$requiredFiles = @(
    'server\HighTacPlatform.exe',
    'mosquitto\mosquitto.exe',
    'mosquitto\mosquitto_passwd.exe',
    'service\HighTacPlatform.exe',
    'service\HighTacPlatform.xml',
    'service\HighTacMqttBroker.exe',
    'service\HighTacMqttBroker.xml',
    'tools\Start-HighTacPlatformService.ps1'
)
foreach ($relativePath in $requiredFiles) {
    $requiredPath = Join-Path $InstallRoot $relativePath
    if (-not (Test-Path -LiteralPath $requiredPath -PathType Leaf)) {
        throw "Required installed payload is missing: $requiredPath. Build/stage server, official Mosquitto, and WinSW inputs before installation."
    }
}

foreach ($templateName in @(
    'platform.yaml.template',
    'platform.env.template',
    'mosquitto.conf.template',
    'aclfile.template',
    'station-provisioning.txt.template',
    'station-provisioning-legacy.txt.template'
)) {
    $templatePath = Join-Path $TemplateRoot $templateName
    if (-not (Test-Path -LiteralPath $templatePath -PathType Leaf)) {
        throw "Required installer template is missing: $templatePath"
    }
}

$platformConfigPath = Join-Path $DataRoot 'config\platform.yaml'
$platformEnvPath = Join-Path $DataRoot 'config\.env'
$passwordFilePath = Join-Path $DataRoot 'mqtt\passwordfile'
$aclFilePath = Join-Path $DataRoot 'mqtt\aclfile'
$mosquittoConfigPath = Join-Path $DataRoot 'mqtt\mosquitto.conf'
$platformEnvExists = Test-Path -LiteralPath $platformEnvPath -PathType Leaf
$passwordFileExists = Test-Path -LiteralPath $passwordFilePath -PathType Leaf
if ($platformEnvExists -ne $passwordFileExists) {
    throw 'ProgramData contains an incomplete credential set. Restore config\.env and the Mosquitto passwordfile together; the installer will not replace either one.'
}
$isFreshInstall = -not $platformEnvExists
if ($legacyImportRequested -and -not $isFreshInstall) {
    throw 'LegacyStationPasswordFile is allowed only while creating a new managed ProgramData credential set. Upgrades always preserve existing credentials.'
}
if ($LegacyRuntimeRoot -and -not $isFreshInstall) {
    throw 'LegacyRuntimeRoot can be migrated only when creating a new managed ProgramData credential set; existing ProgramData is always preserved as authoritative.'
}
if ($isFreshInstall -and (
    (Test-Path -LiteralPath $platformConfigPath -PathType Leaf) -or
    (Test-Path -LiteralPath $aclFilePath -PathType Leaf) -or
    (Test-Path -LiteralPath $mosquittoConfigPath -PathType Leaf)
)) {
    throw 'ProgramData contains partial HighTac configuration without its credential set. Back it up and reconcile or remove the partial files before installation.'
}

if ($isFreshInstall -and -not $StationId) {
    throw 'StationId is required for a fresh installation and must exactly match uppercase 90A9F followed by 7 uppercase hexadecimal characters.'
}
if ((-not (Test-Path -LiteralPath $aclFilePath -PathType Leaf)) -and (-not $StationId -or -not $StationMqttUsername)) {
    throw 'StationId and StationMqttUsername are required to create a missing MQTT ACL file.'
}
if ($legacyImportRequested) {
    [void](Get-HighTacLegacyMosquittoPasswordEntry `
        -Path $LegacyStationPasswordFile `
        -ExpectedUsername 'hightac_mqtt')
}

$legacyRuntimePlan = $null
if ($LegacyRuntimeRoot) {
    $legacyRuntimePlan = Copy-HighTacLegacyRuntimeData `
        -LegacyRuntimeRoot $LegacyRuntimeRoot `
        -DataRoot $DataRoot `
        -ValidateOnly `
        -Confirm:$false
}

if (-not $SkipNetworkPreflight) {
    $preflightParameters = @{
        Confirm = $false
        WebPort = $WebPort
        MqttPort = $MqttPort
    }
    if ($InterfaceAlias) {
        $preflightParameters.InterfaceAlias = $InterfaceAlias
    }
    $network = & (Join-Path $PSScriptRoot 'Test-HighTacStaticIp.ps1') @preflightParameters
    $InterfaceAlias = $network.InterfaceAlias
    $LanIPv4 = $network.IPv4Address
}
elseif ($isFreshInstall -and -not $LanIPv4) {
    throw 'LanIPv4 must be supplied when SkipNetworkPreflight is used for a fresh installation.'
}

if ($null -eq (Get-HighTacService -Name 'HighTacMqttBroker') -and (Test-HighTacLocalTcpListener -Port $MqttPort)) {
    throw "TCP port $MqttPort is already in use, but HighTacMqttBroker is not installed. Stop or reconfigure the independent broker manually; this installer will not take ownership of it or of the unrelated 'mosquitto' service on port 1883."
}
if ($null -eq (Get-HighTacService -Name 'HighTacPlatform') -and (Test-HighTacLocalTcpListener -Port $WebPort)) {
    throw "TCP port $WebPort is already in use by an application that is not HighTacPlatform. Resolve the conflict before installation."
}

if (-not $PSCmdlet.ShouldProcess("$InstallRoot and $DataRoot", 'Install or reconcile HighTac Platform services and runtime configuration')) {
    return
}

$backendPasswordPlain = $null
$stationPasswordPlain = $null
$bootstrapAdminPasswordPlain = $null
$freshCredentialSetComplete = $false
$legacyRuntimeMigration = $null
$platformServiceBeforeInstall = Get-HighTacService -Name 'HighTacPlatform'
$brokerServiceBeforeInstall = Get-HighTacService -Name 'HighTacMqttBroker'
$platformWasRunning = $null -ne $platformServiceBeforeInstall -and $platformServiceBeforeInstall.Status -eq 'Running'
$brokerWasRunning = $null -ne $brokerServiceBeforeInstall -and $brokerServiceBeforeInstall.Status -eq 'Running'
try {
    Stop-HighTacService -Name 'HighTacPlatform' -Confirm:$false
    Stop-HighTacService -Name 'HighTacMqttBroker' -Confirm:$false

    if ($LegacyRuntimeRoot) {
        $legacyRuntimeMigration = Copy-HighTacLegacyRuntimeData `
            -LegacyRuntimeRoot $LegacyRuntimeRoot `
            -DataRoot $DataRoot `
            -Confirm:$false
    }

    $runtimeDirectories = @(
        'config',
        'db',
        'backups',
        'logs',
        'secrets',
        'mqtt',
        'mqtt\data',
        'run'
    )
    foreach ($relativeDirectory in $runtimeDirectories) {
        $directory = Join-Path $DataRoot $relativeDirectory
        if (-not (Test-Path -LiteralPath $directory)) {
            New-Item -ItemType Directory -Path $directory -Force | Out-Null
        }
    }

    $commonTokens = @{
        DATA_ROOT = ConvertTo-HighTacConfigPath -Path $DataRoot
        INSTALL_ROOT = ConvertTo-HighTacConfigPath -Path $InstallRoot
        WEB_PORT = [string]$WebPort
        MQTT_PORT = [string]$MqttPort
        LAN_IPV4 = [string]$LanIPv4
        SITE_NAME = ConvertTo-HighTacYamlString -Value $SiteName
        STATION_ID = [string]$StationId
        MQTT_PLATFORM_USERNAME = $BackendMqttUsername
        MQTT_STATION_USERNAME = $StationMqttUsername
    }

    if ($isFreshInstall) {
        # The one-time password is generated per installation, stored only in
        # protected operator/runtime files, and must be changed on first login.
        $bootstrapAdminUsername = 'Adam'
        $bootstrapAdminPasswordPlain = New-HighTacRandomPassword
        $bootstrapAdminMustChangePassword = 'true'

        if ($null -ne $BackendMqttPassword) {
            $backendPasswordPlain = ConvertFrom-HighTacSecureString -SecureString $BackendMqttPassword
        }
        else {
            $backendPasswordPlain = New-HighTacRandomPassword
        }
        if (-not $legacyImportRequested) {
            if ($null -ne $StationMqttPassword) {
                $stationPasswordPlain = ConvertFrom-HighTacSecureString -SecureString $StationMqttPassword
            }
            else {
                $stationPasswordPlain = New-HighTacRandomPassword
            }
        }
        if ($backendPasswordPlain.Length -lt 24 -or
            (-not $legacyImportRequested -and $stationPasswordPlain.Length -lt 24)) {
            throw 'MQTT passwords supplied to the installer must contain at least 24 characters.'
        }
        if ($backendPasswordPlain -match '\$\{') {
            throw 'BackendMqttPassword cannot contain a ${...} sequence because the backend reads it from a dotenv file.'
        }

        $passwordFileParameters = @{
            MosquittoPasswordTool = Join-Path $InstallRoot 'mosquitto\mosquitto_passwd.exe'
            DestinationPath = $passwordFilePath
            Credentials = @{
                $BackendMqttUsername = $backendPasswordPlain
            }
            Confirm = $false
        }
        if ($legacyImportRequested) {
            $passwordFileParameters.LegacyStationPasswordFile = $LegacyStationPasswordFile
            $passwordFileParameters.LegacyStationUsername = 'hightac_mqtt'
        }
        else {
            $passwordFileParameters.Credentials[$StationMqttUsername] = $stationPasswordPlain
        }
        New-HighTacMosquittoPasswordFile @passwordFileParameters

        $environmentTokens = @{} + $commonTokens
        $environmentTokens.MQTT_PLATFORM_PASSWORD = ConvertTo-HighTacYamlString -Value $backendPasswordPlain
        $environmentTokens.BOOTSTRAP_ADMIN_USERNAME = ConvertTo-HighTacYamlString -Value $bootstrapAdminUsername
        $environmentTokens.BOOTSTRAP_ADMIN_PASSWORD = ConvertTo-HighTacYamlString -Value $bootstrapAdminPasswordPlain
        $platformEnvironment = Get-HighTacRenderedTemplate `
            -TemplatePath (Join-Path $TemplateRoot 'platform.env.template') `
            -Tokens $environmentTokens
        Write-HighTacTextFile -Path $platformEnvPath -Content $platformEnvironment -Confirm:$false

        $credentialTokens = @{} + $commonTokens
        $credentialTokens.BOOTSTRAP_ADMIN_USERNAME = $bootstrapAdminUsername
        $credentialTokens.BOOTSTRAP_ADMIN_PASSWORD = $bootstrapAdminPasswordPlain
        $credentialTokens.BOOTSTRAP_ADMIN_MUST_CHANGE_PASSWORD = $bootstrapAdminMustChangePassword
        $stationProvisioningTemplate = 'station-provisioning-legacy.txt.template'
        if (-not $legacyImportRequested) {
            $credentialTokens.MQTT_STATION_PASSWORD = $stationPasswordPlain
            $stationProvisioningTemplate = 'station-provisioning.txt.template'
        }
        $provisioningContent = Get-HighTacRenderedTemplate `
            -TemplatePath (Join-Path $TemplateRoot $stationProvisioningTemplate) `
            -Tokens $credentialTokens
        Write-HighTacTextFile `
            -Path (Join-Path $DataRoot 'config\station-mqtt-credentials.txt') `
            -Content $provisioningContent `
            -Confirm:$false
    }
    else {
        # Older managed environments predate explicit bootstrap settings. Add
        # only missing keys; existing values and the database remain untouched.
        Protect-HighTacPath -Path $platformEnvPath -Confirm:$false
        Add-HighTacBootstrapAdminEnvironmentDefaults `
            -Path $platformEnvPath `
            -Confirm:$false
    }

    if (-not (Test-Path -LiteralPath $platformConfigPath -PathType Leaf)) {
        $platformConfig = Get-HighTacRenderedTemplate `
            -TemplatePath (Join-Path $TemplateRoot 'platform.yaml.template') `
            -Tokens $commonTokens
        Write-HighTacTextFile -Path $platformConfigPath -Content $platformConfig -NoClobber -Confirm:$false
    }

    if (-not (Test-Path -LiteralPath $mosquittoConfigPath -PathType Leaf)) {
        $mosquittoConfig = Get-HighTacRenderedTemplate `
            -TemplatePath (Join-Path $TemplateRoot 'mosquitto.conf.template') `
            -Tokens $commonTokens
        Write-HighTacTextFile -Path $mosquittoConfigPath -Content $mosquittoConfig -NoClobber -Confirm:$false
    }
    if (-not (Test-Path -LiteralPath $aclFilePath -PathType Leaf)) {
        $aclConfig = Get-HighTacRenderedTemplate `
            -TemplatePath (Join-Path $TemplateRoot 'aclfile.template') `
            -Tokens $commonTokens
        Write-HighTacTextFile -Path $aclFilePath -Content $aclConfig -NoClobber -Confirm:$false
    }

    if ($isFreshInstall) {
        Write-HighTacInstallState -DataRoot $DataRoot -State ([ordered]@{
            schema_version = 1
            install_root = $InstallRoot
            data_root = $DataRoot
            web_port = $WebPort
            mqtt_port = $MqttPort
            station_id = $StationId
            site_name = $SiteName
            interface_alias = $InterfaceAlias
            lan_ipv4 = $LanIPv4
            backend_mqtt_username = $BackendMqttUsername
            station_mqtt_username = $StationMqttUsername
            station_credential_mode = if ($legacyImportRequested) { 'legacy_hash_import' } else { 'generated' }
            station_password_reprintable = -not $legacyImportRequested
            legacy_runtime_migrated = $null -ne $legacyRuntimeMigration -and $legacyRuntimeMigration.FilesDiscovered -gt 0
            legacy_runtime_files_copied = if ($null -ne $legacyRuntimeMigration) { $legacyRuntimeMigration.FilesCopied } else { 0 }
        }) -Confirm:$false
        $freshCredentialSetComplete = $true
    }

    foreach ($protectedPath in @(
        $DataRoot,
        (Join-Path $DataRoot 'config'),
        (Join-Path $DataRoot 'db'),
        (Join-Path $DataRoot 'backups'),
        (Join-Path $DataRoot 'logs'),
        (Join-Path $DataRoot 'secrets'),
        (Join-Path $DataRoot 'mqtt'),
        (Join-Path $DataRoot 'mqtt\data'),
        (Join-Path $DataRoot 'run'),
        $platformConfigPath,
        $platformEnvPath,
        $passwordFilePath,
        $aclFilePath,
        (Join-Path $DataRoot 'config\station-mqtt-credentials.txt')
    )) {
        Protect-HighTacPath -Path $protectedPath -Confirm:$false
    }

    Install-HighTacWinSWService `
        -WrapperPath (Join-Path $InstallRoot 'service\HighTacMqttBroker.exe') `
        -ServiceName 'HighTacMqttBroker' `
        -Confirm:$false
    Install-HighTacWinSWService `
        -WrapperPath (Join-Path $InstallRoot 'service\HighTacPlatform.exe') `
        -ServiceName 'HighTacPlatform' `
        -Confirm:$false

    & (Join-Path $PSScriptRoot 'Set-HighTacFirewall.ps1') `
        -Action Ensure `
        -WebPort $WebPort `
        -MqttPort $MqttPort `
        -Confirm:$false | Out-Null

    Start-HighTacService -Name 'HighTacMqttBroker' -Confirm:$false
    Start-HighTacService -Name 'HighTacPlatform' -Confirm:$false

    if (-not $SkipHealthCheck) {
        $deadline = [DateTime]::UtcNow.AddSeconds(30)
        do {
            $health = @(& (Join-Path $PSScriptRoot 'Test-HighTacHealth.ps1') `
                -WebPort $WebPort `
                -MqttPort $MqttPort `
                -TimeoutSeconds 3 `
                -AllowUnhealthy `
                -Confirm:$false)
            if (@($health | Where-Object { -not $_.Healthy }).Count -eq 0) {
                break
            }
            Start-Sleep -Seconds 2
        } while ([DateTime]::UtcNow -lt $deadline)

        if (@($health | Where-Object { -not $_.Healthy }).Count -gt 0) {
            $failedChecks = ($health | Where-Object { -not $_.Healthy } | ForEach-Object { $_.Check }) -join ', '
            throw "Services were installed but health checks did not become ready within 30 seconds: $failedChecks"
        }
    }

    [pscustomobject]@{
        InstallRoot = $InstallRoot
        DataRoot = $DataRoot
        WebUrl = "http://$LanIPv4`:$WebPort"
        MqttEndpoint = "$LanIPv4`:$MqttPort"
        PlatformService = 'HighTacPlatform'
        BrokerService = 'HighTacMqttBroker'
        StationCredentialsFile = Join-Path $DataRoot 'config\station-mqtt-credentials.txt'
        StationCredentialMode = if ($legacyImportRequested) { 'legacy_hash_import' } elseif ($isFreshInstall) { 'generated' } else { 'preserved' }
        ExistingDataPreserved = -not $isFreshInstall
        LegacyRuntimeFilesDiscovered = if ($null -ne $legacyRuntimePlan) { $legacyRuntimePlan.FilesDiscovered } else { 0 }
        LegacyRuntimeFilesCopied = if ($null -ne $legacyRuntimeMigration) { $legacyRuntimeMigration.FilesCopied } else { 0 }
    }
}
catch {
    $installationError = $_
    if ($isFreshInstall -and -not $freshCredentialSetComplete) {
        foreach ($partialCredentialPath in @(
            $platformEnvPath,
            $passwordFilePath,
            (Join-Path $DataRoot 'config\station-mqtt-credentials.txt'),
            $platformConfigPath,
            $mosquittoConfigPath,
            $aclFilePath,
            (Join-Path $DataRoot 'config\install-state.json')
        )) {
            if (Test-Path -LiteralPath $partialCredentialPath -PathType Leaf) {
                Remove-Item -LiteralPath $partialCredentialPath -Force -ErrorAction SilentlyContinue
            }
        }
    }
    if ($brokerWasRunning -and $null -ne (Get-HighTacService -Name 'HighTacMqttBroker')) {
        try { Start-HighTacService -Name 'HighTacMqttBroker' -Confirm:$false } catch { Write-Warning $_.Exception.Message }
    }
    if ($platformWasRunning -and $null -ne (Get-HighTacService -Name 'HighTacPlatform')) {
        try { Start-HighTacService -Name 'HighTacPlatform' -Confirm:$false } catch { Write-Warning $_.Exception.Message }
    }
    throw $installationError
}
finally {
    $backendPasswordPlain = $null
    $stationPasswordPlain = $null
    $bootstrapAdminPasswordPlain = $null
}
