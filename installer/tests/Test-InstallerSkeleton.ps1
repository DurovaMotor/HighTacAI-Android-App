[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$installerRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$failures = New-Object 'System.Collections.Generic.List[string]'
$passed = 0

function Assert-InstallerCondition {
    param(
        [Parameter(Mandatory = $true)]
        [bool]$Condition,

        [Parameter(Mandatory = $true)]
        [string]$Message
    )

    if ($Condition) {
        $script:passed++
    }
    else {
        $script:failures.Add($Message)
    }
}

function Get-InstallerText {
    param(
        [Parameter(Mandatory = $true)]
        [string]$RelativePath
    )

    return [IO.File]::ReadAllText((Join-Path $installerRoot $RelativePath))
}

$powerShellFiles = @(Get-ChildItem -LiteralPath $installerRoot -Recurse -File | Where-Object {
    $_.FullName -notmatch '[\\/]build[\\/]' -and $_.Extension -in '.ps1', '.psm1'
})
foreach ($file in $powerShellFiles) {
    $tokens = $null
    $parseErrors = $null
    [void][Management.Automation.Language.Parser]::ParseFile($file.FullName, [ref]$tokens, [ref]$parseErrors)
    $parseErrorMessage = ($parseErrors | ForEach-Object { $_.Message }) -join '; '
    Assert-InstallerCondition ($parseErrors.Count -eq 0) "PowerShell parse errors in $($file.FullName): $parseErrorMessage"
}

$managementScripts = @(
    'windows\Install-HighTacPlatform.ps1',
    'windows\Uninstall-HighTacPlatform.ps1',
    'windows\Upgrade-HighTacPlatform.ps1',
    'windows\Test-HighTacHealth.ps1',
    'windows\Set-HighTacFirewall.ps1',
    'windows\Test-HighTacStaticIp.ps1',
    'windows\Test-HighTacLegacyMqttCredential.ps1'
)
foreach ($relativePath in $managementScripts) {
    $text = Get-InstallerText -RelativePath $relativePath
    Assert-InstallerCondition ($text -match 'CmdletBinding\(SupportsShouldProcess\s*=\s*\$true') "$relativePath must declare SupportsShouldProcess."
    Assert-InstallerCondition ($text -match 'Assert-HighTacAdministrator') "$relativePath must perform an explicit administrator check."
    Assert-InstallerCondition ($text -match '\[switch\]\$Force' -and $text -match '\$ConfirmPreference\s*=\s*''None''') "$relativePath must provide noninteractive -Force handling without removing ShouldProcess support."
}

foreach ($xmlName in @('HighTacPlatform.xml', 'HighTacMqttBroker.xml')) {
    $xmlPath = Join-Path $installerRoot "winsw\$xmlName"
    try {
        $xml = [xml][IO.File]::ReadAllText($xmlPath)
        Assert-InstallerCondition ($null -ne $xml.service) "$xmlName must contain a WinSW service root."
    }
    catch {
        Assert-InstallerCondition $false "$xmlName is not valid XML: $($_.Exception.Message)"
    }
}

$platformXml = [xml](Get-InstallerText -RelativePath 'winsw\HighTacPlatform.xml')
$brokerXml = [xml](Get-InstallerText -RelativePath 'winsw\HighTacMqttBroker.xml')
Assert-InstallerCondition ($platformXml.service.id -eq 'HighTacPlatform') 'Platform WinSW service ID must be HighTacPlatform.'
Assert-InstallerCondition ($brokerXml.service.id -eq 'HighTacMqttBroker') 'Broker WinSW service ID must be HighTacMqttBroker.'
Assert-InstallerCondition ($platformXml.service.executable -match 'powershell\.exe$') 'Platform WinSW service must invoke the secure PowerShell launcher.'
Assert-InstallerCondition ($platformXml.service.arguments -match 'Start-HighTacPlatformService\.ps1') 'Platform WinSW service must invoke the secure runtime environment launcher.'
Assert-InstallerCondition ($platformXml.service.workingdirectory -match '%ProgramData%\\HighTac\\Platform\\config') 'Platform service must load the protected ProgramData .env settings.'
Assert-InstallerCondition ($brokerXml.service.executable -match 'mosquitto\.exe$') 'Broker WinSW service must execute official mosquitto.exe.'
Assert-InstallerCondition ($brokerXml.service.arguments -match 'mosquitto\.conf') 'Broker WinSW service must use the generated Mosquitto configuration.'
Assert-InstallerCondition ($brokerXml.service.arguments -notmatch '1883') 'Broker WinSW XML must not take ownership of port 1883.'
foreach ($serviceXml in @($platformXml, $brokerXml)) {
    Assert-InstallerCondition ([string]$serviceXml.service.startmode -eq 'Automatic') 'Both WinSW services must use Automatic start.'
    Assert-InstallerCondition (@($serviceXml.service.depend) -contains 'Tcpip') 'Both WinSW services must declare the Windows TCP/IP dependency.'
    Assert-InstallerCondition (@($serviceXml.service.onfailure).Count -ge 3) 'Both WinSW services must configure at least three recovery restart actions.'
    Assert-InstallerCondition (@($serviceXml.service.onfailure | Where-Object { $_.action -ne 'restart' }).Count -eq 0) 'Every configured WinSW failure action must restart the service.'
}
Assert-InstallerCondition (@($platformXml.service.depend) -notcontains 'HighTacMqttBroker') 'Platform must not use an SCM broker dependency that would block Web broker stop control.'

$serviceLauncherText = Get-InstallerText -RelativePath 'windows\Start-HighTacPlatformService.ps1'
Assert-InstallerCondition ($serviceLauncherText -match 'Read-HighTacServiceEnvironment' -and $serviceLauncherText -match 'HIGHTAC_MQTT_PASSWORD') 'Platform launcher must load and validate protected HIGHTAC_MQTT settings.'
Assert-InstallerCondition ($serviceLauncherText -match '(?s)\$name\s*=\s*\$Matches\[1\]\s+\$encodedValue\s*=\s*\$Matches\[2\].*?ConvertFrom-DotEnvQuotedValue\s+-Value\s+\$encodedValue') 'Platform launcher must capture the dotenv value before another regex operation can overwrite PowerShell $Matches.'
Assert-InstallerCondition ($serviceLauncherText -match 'SetEnvironmentVariable\(\$name, \[string\]\$serviceEnvironment\[\$name\], ''Process''\)') 'Platform launcher must pass runtime settings through the child process environment.'
Assert-InstallerCondition ($serviceLauncherText -match "Start-Service -Name 'HighTacMqttBroker'" -and $serviceLauncherText -match 'Test-LocalTcpPort') 'Platform launcher must order broker startup and readiness before API launch.'
Assert-InstallerCondition ($serviceLauncherText -match '& \$platformExecutable serve' -and $serviceLauncherText -match 'exit \$platformExitCode') 'Platform launcher must propagate the packaged process exit code so WinSW recovery is real.'
Assert-InstallerCondition ($serviceLauncherText -notmatch 'Write-Host|Write-Output') 'Platform launcher must not print runtime environment values.'

$launcherTokens = $null
$launcherParseErrors = $null
$launcherAst = [Management.Automation.Language.Parser]::ParseFile(
    (Join-Path $installerRoot 'windows\Start-HighTacPlatformService.ps1'),
    [ref]$launcherTokens,
    [ref]$launcherParseErrors
)
$parserFunctionNames = @('ConvertFrom-DotEnvQuotedValue', 'Read-HighTacServiceEnvironment')
$parserFunctionDefinitions = @($launcherAst.FindAll({
    param($node)
    $node -is [Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -in $parserFunctionNames
}, $true))
Assert-InstallerCondition ($parserFunctionDefinitions.Count -eq 2) 'Launcher parser regression test must locate both dotenv parser functions.'
if ($parserFunctionDefinitions.Count -eq 2) {
    $parserFunctionDefinitions | ForEach-Object { Invoke-Expression $_.Extent.Text }
    $dotenvFixturePath = [IO.Path]::GetTempFileName()
    function Get-Acl {
        param([string]$LiteralPath)
        return [pscustomobject]@{ Access = @() }
    }
    try {
        [IO.File]::WriteAllLines(
            $dotenvFixturePath,
            @(
                'HIGHTAC_MQTT_HOST="127.0.0.1"',
                'HIGHTAC_MQTT_PORT="1884"',
                'HIGHTAC_MQTT_PASSWORD="historical-regression-secret"'
            ),
            (New-Object Text.UTF8Encoding($false))
        )
        $parsedEnvironment = Read-HighTacServiceEnvironment -Path $dotenvFixturePath
        Assert-InstallerCondition (
            $parsedEnvironment.HIGHTAC_MQTT_HOST -ceq '127.0.0.1' -and
            $parsedEnvironment.HIGHTAC_MQTT_PORT -ceq '1884' -and
            $parsedEnvironment.HIGHTAC_MQTT_PASSWORD -ceq 'historical-regression-secret'
        ) 'Launcher parser must preserve non-empty dotenv values after namespace validation; this guards the historical install/upgrade exit-code-1 failure.'
    }
    catch {
        Assert-InstallerCondition $false "Launcher dotenv behavior regression failed: $($_.Exception.Message)"
    }
    finally {
        Remove-Item -LiteralPath $dotenvFixturePath -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath Function:\Get-Acl -Force -ErrorAction SilentlyContinue
    }
}

$firewallText = Get-InstallerText -RelativePath 'windows\Set-HighTacFirewall.ps1'
Assert-InstallerCondition ($firewallText -match '-Profile Private') 'Firewall rules must be restricted to the Private profile.'
Assert-InstallerCondition ($firewallText -match '-RemoteAddress LocalSubnet') 'Firewall rules must be restricted to LocalSubnet.'
Assert-InstallerCondition ($firewallText -notmatch '\b1883\b') 'Firewall script must not manage port 1883.'

$allWindowsText = ($managementScripts | ForEach-Object { Get-InstallerText -RelativePath $_ }) -join "`n"
Assert-InstallerCondition ($allWindowsText -notmatch '(?i)(?:-Name|-ServiceName)\s+[''"]mosquitto[''"]') 'Lifecycle scripts must never target the unrelated mosquitto Windows service.'
Assert-InstallerCondition ($allWindowsText -notmatch 'SimpleMqttBroker') 'Installer must not reference a hand-written MQTT broker.'
$commonModuleText = Get-InstallerText -RelativePath 'windows\HighTacInstaller.Common.psm1'
Assert-InstallerCondition ($commonModuleText -match 'Refusing to manage a service outside HighTac ownership') 'Shared service helpers must reject names outside the two HighTac services.'
Assert-InstallerCondition ($commonModuleText -match 'function Copy-HighTacLegacyRuntimeData' -and $commonModuleText -match "@\('db', 'backups', 'logs', 'secrets'\)") 'Legacy runtime migration must be limited to the four server persistence directories.'
Assert-InstallerCondition ($commonModuleText -match 'ReparsePoint' -and $commonModuleText -match 'conflicts with different ProgramData content') 'Legacy runtime migration must reject links and conflicting ProgramData files.'
Assert-InstallerCondition ($commonModuleText -match '\[IO\.FileShare\]::Read' -and $commonModuleText -match 'still open for writing') 'Legacy runtime migration must hold read locks so an active database or log writer stops the copy.'

$uninstallText = Get-InstallerText -RelativePath 'windows\Uninstall-HighTacPlatform.ps1'
Assert-InstallerCondition ($uninstallText -match '\$deleteData\s*=\s*\[bool\]\$RemoveData') 'Uninstall must preserve ProgramData unless RemoveData is explicit.'
Assert-InstallerCondition ($uninstallText -match 'Assert-HighTacSafeRoot') 'Recursive uninstall deletion must verify the resolved HighTac root.'

$platformTemplate = Get-InstallerText -RelativePath 'config\platform.yaml.template'
$environmentTemplate = Get-InstallerText -RelativePath 'config\platform.env.template'
$mosquittoTemplate = Get-InstallerText -RelativePath 'config\mosquitto.conf.template'
$aclTemplate = Get-InstallerText -RelativePath 'config\aclfile.template'
$credentialTemplate = Get-InstallerText -RelativePath 'config\station-provisioning.txt.template'
$legacyCredentialTemplate = Get-InstallerText -RelativePath 'config\station-provisioning-legacy.txt.template'
Assert-InstallerCondition ($platformTemplate -match 'password_env:\s*"HIGHTAC_MQTT_PASSWORD"') 'Platform YAML manifest must reference the runtime password environment variable.'
Assert-InstallerCondition ($environmentTemplate -match 'HIGHTAC_MQTT_PASSWORD="__MQTT_PLATFORM_PASSWORD__"') 'Runtime platform password must remain a placeholder in Git.'
Assert-InstallerCondition ($environmentTemplate -match 'HIGHTAC_BOOTSTRAP_ADMIN_USERNAME="__BOOTSTRAP_ADMIN_USERNAME__"') 'Runtime bootstrap admin username must be rendered by the installer.'
Assert-InstallerCondition ($environmentTemplate -match 'HIGHTAC_BOOTSTRAP_ADMIN_PASSWORD="__BOOTSTRAP_ADMIN_PASSWORD__"') 'Runtime bootstrap admin password must remain a placeholder in Git.'
Assert-InstallerCondition ($credentialTemplate -match 'station_password=__MQTT_STATION_PASSWORD__') 'Station password must remain a placeholder in Git.'
Assert-InstallerCondition ($credentialTemplate -match 'app_server_url=' -and $credentialTemplate -match 'initial_admin_username=__BOOTSTRAP_ADMIN_USERNAME__' -and $credentialTemplate -match 'initial_admin_password=__BOOTSTRAP_ADMIN_PASSWORD__' -and $credentialTemplate -match 'admin_must_change_password=__BOOTSTRAP_ADMIN_MUST_CHANGE_PASSWORD__') 'Fresh operator output must contain tokenized app and forced-change one-time admin configuration.'
Assert-InstallerCondition ($legacyCredentialTemplate -match 'preserved_existing_hash_not_recoverable' -and $legacyCredentialTemplate -notmatch 'MQTT_STATION_PASSWORD') 'Legacy operator output must state that the station password cannot be recovered or reprinted.'
Assert-InstallerCondition ($mosquittoTemplate -match 'listener __MQTT_PORT__ 0\.0\.0\.0') 'Mosquitto listener must be rendered explicitly.'
Assert-InstallerCondition ($mosquittoTemplate -match 'allow_anonymous false') 'Mosquitto must reject anonymous clients.'
Assert-InstallerCondition ($aclTemplate -match 'user __MQTT_PLATFORM_USERNAME__') 'MQTT ACL must contain a backend username placeholder.'
Assert-InstallerCondition ($aclTemplate -match 'user __MQTT_STATION_USERNAME__') 'MQTT ACL must contain a station username placeholder.'

$installText = Get-InstallerText -RelativePath 'windows\Install-HighTacPlatform.ps1'
$preflightText = Get-InstallerText -RelativePath 'windows\Test-HighTacStaticIp.ps1'
$issText = Get-InstallerText -RelativePath 'packaging\HighTacPlatform.iss'
$upgradeText = Get-InstallerText -RelativePath 'windows\Upgrade-HighTacPlatform.ps1'
$payloadBuildText = Get-InstallerText -RelativePath 'packaging\New-ReleasePayload.ps1'
$installerBuildText = Get-InstallerText -RelativePath 'packaging\Build-Installer.ps1'
foreach ($runtimeName in @('MSVCP140.dll', 'VCRUNTIME140.dll', 'VCRUNTIME140_1.dll')) {
    foreach ($requiredText in @($installText, $upgradeText, $installerBuildText, $issText)) {
        Assert-InstallerCondition ($requiredText.Contains($runtimeName)) "$runtimeName must be required throughout install, upgrade, and installer compilation."
    }
    Assert-InstallerCondition ($payloadBuildText.Contains("'$runtimeName'")) "$runtimeName must be copied into the Mosquitto app-local runtime directory."
}
Assert-InstallerCondition (
    $payloadBuildText -match '(?s)Get-AuthenticodeSignature.*?Microsoft Corporation.*?VersionInfo\.ProductVersion.*?VcRedistVersion'
) 'App-local VC++ runtime DLLs must be Microsoft-signed and version-matched before packaging.'
Assert-InstallerCondition ($installText -match '\[int\]\$MqttPort\s*=\s*1884') 'Install script must default MQTT to 1884.'
Assert-InstallerCondition ($installText -match '\[int\]\$WebPort\s*=\s*8088') 'Install script must default the HighTac platform to 8088.'
Assert-InstallerCondition ($installText -match '\[string\]\$LegacyRuntimeRoot' -and $installText -match 'Copy-HighTacLegacyRuntimeData') 'Install script must explicitly validate and copy a legacy server runtime source.'
Assert-InstallerCondition ($installText -match '\$bootstrapAdminUsername\s*=\s*''Adam''' -and $installText -match '\$bootstrapAdminPasswordPlain\s*=\s*New-HighTacRandomPassword' -and $installText -match '\$bootstrapAdminMustChangePassword\s*=\s*''true''') 'Fresh install must provision Adam with a generated one-time password and a mandatory password change.'
Assert-InstallerCondition ($installText -notmatch '(?i)bootstrapAdminPassword(?:Plain)?\s*=\s*[''"]Adam[''"]' -and $installText -notmatch "HIGHTAC_BOOTSTRAP_ADMIN_PASSWORD\s*=\s*'Adam'") 'Installer source must never embed the historical fixed bootstrap password.'
Assert-InstallerCondition ($installText -match '(?s)Add-HighTacBootstrapAdminEnvironmentDefaults.*?HIGHTAC_BOOTSTRAP_ADMIN_PASSWORD.*?New-HighTacRandomPassword') 'Missing bootstrap settings on managed upgrades must receive a generated random password.'
Assert-InstallerCondition ($installText -match 'Add-HighTacBootstrapAdminEnvironmentDefaults' -and $installText -match 'Older managed environments predate explicit bootstrap settings') 'Upgrade must add only missing explicit bootstrap settings to the preserved environment.'
Assert-InstallerCondition ($serviceLauncherText -match "'HIGHTAC_BOOTSTRAP_ADMIN_USERNAME'" -and $serviceLauncherText -match "'HIGHTAC_BOOTSTRAP_ADMIN_PASSWORD'") 'Platform launcher must require protected bootstrap admin settings.'
Assert-InstallerCondition ($preflightText -match 'HighTacMqttBroker') 'Port preflight must recognize only the owned broker service.'
Assert-InstallerCondition ($issText -match '-MqttPort 1884') 'Inno installer must configure the HighTac broker on 1884.'
Assert-InstallerCondition (
    $issText -match "(?s)if GetInterfaceAlias\(''\) <> '' then\s+PreflightArguments := '-InterfaceAlias '" -and
    $issText -match "(?s)if GetInterfaceAlias\(''\) <> '' then\s+DeploymentArguments := DeploymentArguments \+\s+' -InterfaceAlias '"
) 'Inno must omit the InterfaceAlias switch when automatic adapter selection leaves it blank.'
Assert-InstallerCondition ($issText -match '-Force' -and $issText -notmatch '-Confirm:\$false') 'Inno must use PowerShell 5.1-compatible noninteractive Force switches.'
Assert-InstallerCondition ($issText -match 'MB_DEFBUTTON2') 'Inno uninstall prompt must default to preserving ProgramData.'
Assert-InstallerCondition ($issText -match "DeleteDataOnUninstall := CompareText\(ExpandConstant\('\{param:DELETEDATA\|0\}'\), '1'\) = 0") 'Silent uninstall must preserve ProgramData unless DELETEDATA=1 is explicit.'
Assert-InstallerCondition ($issText -match 'Test-HighTacStaticIp\.ps1') 'Inno must run network preflight before installation.'
Assert-InstallerCondition ($issText -match 'CurUninstallStepChanged' -and $issText -match 'HighTac service cleanup failed') 'Inno must fail explicitly when checked service cleanup fails.'
Assert-InstallerCondition ($issText -match 'HighTac deployment configuration failed') 'Inno must fail explicitly when checked deployment configuration fails.'
Assert-InstallerCondition ($issText -match '(?s)if not IsRehearsalMode\(\) then.*?VC_redist\.x64\.exe') 'Windows Sandbox rehearsal must skip the VC++ Burn bootstrapper that cannot extract its attached container there.'
Assert-InstallerCondition ($issText -match 'PostInstallFailed\s*:=\s*True' -and $issText -match '(?s)function GetCustomSetupExitCode\(\): Integer;.*?if PostInstallFailed then\s*Result := 1') 'Silent post-install failures must propagate a nonzero installer process exit code through the documented Inno event.'
Assert-InstallerCondition ($issText -notmatch 'ExitProcess@kernel32') 'Installer exit handling must not bypass Inno cleanup through kernel32 ExitProcess.'
Assert-InstallerCondition (([regex]::Matches($installText, 'ConvertTo-HighTacStationId\s+-StationId\s+\$StationId')).Count -eq 2) 'Install script must validate both explicitly supplied and restored station IDs.'
Assert-InstallerCondition ($commonModuleText.Contains("'^90A9F[0-9A-F]{7}$'")) 'PowerShell station ID validation must require the 90A9F prefix and 12 uppercase hexadecimal characters total.'
Assert-InstallerCondition ($issText -match "Copy\(Value,\s*1,\s*5\)\s*=\s*'90A9F'" -and $issText -notmatch 'Uppercase\(Copy\(Value') 'Inno station ID validation must require an exact uppercase 90A9F prefix.'
Assert-InstallerCondition ($issText -cnotmatch "Character >= 'a'|Character <= 'f'") 'Inno station ID validation must reject lowercase hexadecimal input.'
Assert-InstallerCondition ($issText -match 'Station SN must exactly match uppercase 90A9F.*7 uppercase hexadecimal characters') 'Inno station ID validation must explain the strict uppercase format.'
Assert-InstallerCondition ($issText -match 'LEGACYPASSWORDFILE' -and $issText -match 'Test-HighTacLegacyMqttCredential\.ps1') 'Inno must expose and preflight the explicit legacy station hash import path.'
Assert-InstallerCondition ($issText -match 'DELETEDATA\|0' -and $issText -match '-RemoveData') 'Inno must support deterministic explicit delete-data uninstall while preserving by default.'
Assert-InstallerCondition ($issText -match 'install-state\.json') 'Inno must treat preserved ProgramData as an upgrade/reinstall.'
Assert-InstallerCondition ($issText -match 'function HasManagedProgramData' -and $issText -match 'config\\\.env' -and $issText -match 'mqtt\\passwordfile') 'Inno must recognize a complete preserved ProgramData credential set as a managed upgrade.'
$detectUpgradeText = [regex]::Match($issText, '(?s)function DetectUpgrade\(\): Boolean;.*?end;').Value
Assert-InstallerCondition ($detectUpgradeText -match 'HasManagedProgramData' -and $detectUpgradeText -notmatch 'DirExists') 'A legacy application directory alone must not skip fresh configuration and runtime migration.'
Assert-InstallerCondition ($issText -match '-LegacyRuntimeRoot' -and $issText -match '\{app\}\\server\\runtime') 'Inno must pass the legacy server runtime only through the constrained migration parameter.'

$payloadText = Get-InstallerText -RelativePath 'packaging\New-ReleasePayload.ps1'
$platformBuildText = Get-InstallerText -RelativePath 'packaging\Build-Platform.ps1'
$releaseBuildText = Get-InstallerText -RelativePath 'packaging\Build-Release.ps1'
$installerBuildText = Get-InstallerText -RelativePath 'packaging\Build-Installer.ps1'
$releaseReportText = Get-InstallerText -RelativePath 'packaging\New-ReleaseReport.ps1'
Assert-InstallerCondition ($payloadText -match 'SHA256SUMS\.txt') 'Release payload must generate a SHA-256 manifest.'
Assert-InstallerCondition ($payloadText -match 'epl-v20' -and $payloadText -match 'edl-v10' -and $payloadText -match 'WinSWLicenseFile') 'Release payload must require Mosquitto and WinSW license inputs.'
Assert-InstallerCondition ($payloadText -match 'Authenticode signer' -and $payloadText -match 'not a license grant') 'Release payload must generate factual VC++ provenance without inventing license terms.'
Assert-InstallerCondition ($payloadText -notmatch '\[Parameter\(Mandatory\s*=\s*\$true\)\]\s*\r?\n\s*\[string\]\$ProductLicenseFile') 'Product license must be optional when no approved product EULA exists.'
Assert-InstallerCondition ($payloadText -notmatch "Get-ChildItem[^\r\n]+MosquittoRoot[^\r\n]+-Recurse") 'Release staging must not copy the entire installed Mosquitto tree or its system configuration.'
Assert-InstallerCondition ($platformBuildText -match 'StaticFiles' -and $platformBuildText -match 'embedded_web_fallback') 'PyInstaller entrypoint must serve embedded Vite assets and SPA routes.'
Assert-InstallerCondition ($platformBuildText -match 'hightac_platform/_migrations') 'PyInstaller output must place Alembic migrations at the backend packaged fallback path.'
Assert-InstallerCondition ($releaseBuildText -match "LOCALAPPDATA.*Programs\\Inno Setup 6\\ISCC\.exe") 'One-click build must discover per-user Inno Setup.'
Assert-InstallerCondition ($releaseBuildText -match 'sep=chr\(46\)' -and $releaseBuildText -notmatch 'print\(\"\.\"') 'Python probe must remain quote-free for Windows PowerShell 5.1 and PowerShell 7 native argument handling.'
Assert-InstallerCondition ($releaseBuildText -match 'LegacyPasswordFileForScan') 'One-click build must compare artifacts against the live legacy password file without staging it.'
Assert-InstallerCondition ($installerBuildText -match '\$isccOutput\s*=\s*@\(' -and $installerBuildText -match '\$isccOutput\s*\|\s*Out-Host') 'Inno compiler logs must not contaminate the structured Build-Installer return object.'
Assert-InstallerCondition ($releaseBuildText -match 'did not return the expected installer artifact contract') 'One-click build must fail clearly when the installer builder return contract changes.'
Assert-InstallerCondition ($installerBuildText -match "\[string\]\`$AppVersion\s*=\s*'2\.0\.0'" -and $releaseBuildText -match "\[string\]\`$AppVersion\s*=\s*'2\.0\.0'") 'Installer and one-click release builds must default to platform version 2.0.0.'
Assert-InstallerCondition ($installerBuildText -match 'SigningPfxPath' -and $installerBuildText -match 'SigningCertificateThumbprint' -and $installerBuildText -match 'SigningPfxPassword.*SecureString') 'Installer build must support either a protected PFX input or an installed code-signing certificate.'
Assert-InstallerCondition ($installerBuildText -match 'Set-AuthenticodeSignature' -and $installerBuildText -match 'Get-AuthenticodeSignature' -and $installerBuildText -match "Status\s*-ne\s*'Valid'") 'Installer signing must be followed by strong Authenticode verification.'
Assert-InstallerCondition ($installerBuildText -match 'TimeStamperCertificate' -and $installerBuildText -match 'does not contain the required trusted timestamp') 'Signed release candidates must require a timestamp when a timestamp server is configured.'
Assert-InstallerCondition ($installerBuildText -match 'internal_lan_unsigned' -and $installerBuildText -match 'INTERNAL LAN UNSIGNED PACKAGE' -and $installerBuildText -match 'RequireSignedInstaller') 'An unsigned installer must be explicitly classified as internal LAN and fail when a signature is required.'
Assert-InstallerCondition ($installerBuildText -match "ValidateSet\('internal_lan', 'public_distribution'\)" -and $installerBuildText -match "DistributionTarget\s*-eq\s*'public_distribution'") 'Installer signing must be optional for internal LAN and mandatory for public distribution.'
Assert-InstallerCondition ($releaseReportText -match 'signatureGatePassed' -and $releaseReportText -match 'internal_lan_unsigned' -and $releaseReportText -match 'public_distribution_signed') 'Release readiness must apply Authenticode as a gate only to the public distribution profile.'
Assert-InstallerCondition ($installerBuildText -match '(?s)Set-AuthenticodeSignature.*?Get-FileHash[^\r\n]+\$installerPath') 'Installer SHA-256 must be calculated after Authenticode signing changes the final bytes.'
Assert-InstallerCondition ($releaseBuildText -match 'SigningPfxPassword\s*=\s*\$SigningPfxPassword' -and $releaseBuildText -match 'SigningCertificateThumbprint') 'One-click release build must forward optional signing inputs without serializing the PFX password.'
Assert-InstallerCondition ($releaseBuildText -match 'New-ReleaseReport\.ps1' -and $releaseBuildText -match 'ReleaseReportJson' -and $releaseBuildText -match 'ReleaseReady') 'One-click build must generate and return the version-bound release report.'
Assert-InstallerCondition ($releaseReportText -match 'expected_git_tag' -and $releaseReportText -match 'Installer checksum sidecar does not match' -and $releaseReportText -match 'Get-AuthenticodeSignature') 'Release report generation must bind the expected tag, final checksum, and live signature state.'
Assert-InstallerCondition ($installerBuildText -match 'source_commit\s*=\s*\$sourceState\.Commit' -and $installerBuildText -match 'source_worktree_clean\s*=\s*\[bool\]\$sourceState\.WorktreeClean' -and $installerBuildText -match 'status --porcelain=v1 --untracked-files=all') 'Installer metadata must bind the source commit and exact clean-worktree state.'
Assert-InstallerCondition ($releaseReportText -match 'scale_acceptance_10_phones_2000_tags' -and $releaseReportText -match 'non-release-gating') 'Release reporting must preserve the user-owned scale acceptance boundary.'
Assert-InstallerCondition ($releaseReportText -match 'windows_reboot_autostart' -and $releaseReportText -match 'two_android_client_sync' -and $releaseReportText -match 'fault_injection_recovery' -and $releaseReportText -match 'provider_credentials_rotated') 'Release reporting must require reboot, two-client, fault-recovery, and provider-rotation evidence for 2.0.0.'

$repositoryRoot = Split-Path -Parent $installerRoot
$webPackage = [IO.File]::ReadAllText((Join-Path $repositoryRoot 'web\package.json')) | ConvertFrom-Json
$webLockText = [IO.File]::ReadAllText((Join-Path $repositoryRoot 'web\package-lock.json'))
$webLockVersionedRoot = $webLockText -match '(?s)^\s*\{\s*"name"\s*:\s*"hightac-admin-console"\s*,\s*"version"\s*:\s*"2\.0\.0".*?"packages"\s*:\s*\{\s*""\s*:\s*\{\s*"name"\s*:\s*"hightac-admin-console"\s*,\s*"version"\s*:\s*"2\.0\.0"'
Assert-InstallerCondition ($webPackage.version -ceq '2.0.0' -and $webLockVersionedRoot) 'Web package and lockfile root versions must be 2.0.0.'
$releaseEvidenceText = [IO.File]::ReadAllText((Join-Path $repositoryRoot 'docs\releases\v2.0.0-evidence.json'))
$releaseEvidence = $releaseEvidenceText | ConvertFrom-Json
Assert-InstallerCondition ($releaseEvidence.product_version -ceq '2.0.0' -and $releaseEvidence.expected_git_tag -ceq 'v2.0.0' -and $releaseEvidence.distribution_target -ceq 'internal_lan') 'Release evidence must bind internal-LAN product 2.0.0 to expected tag v2.0.0.'
Assert-InstallerCondition ($releaseEvidenceText -match '36f9c347-b233-42f0-b6cb-ec0cf0624eb5' -and $releaseEvidenceText -match '681e9718-1c10-448f-8e8c-a6976289dc84') 'Sanitized real station/light-strip command evidence must remain in the 2.0.0 release record.'
$scaleEvidence = @($releaseEvidence.manual_checks | Where-Object { $_.id -eq 'scale_acceptance_10_phones_2000_tags' })
Assert-InstallerCondition ($scaleEvidence.Count -eq 1 -and -not [bool]$scaleEvidence[0].required_for_release -and $scaleEvidence[0].owner -ceq 'user') 'Only the external 10-phone/2000-tag scale acceptance may remain user-owned and non-gating.'

$artifactScanText = Get-InstallerText -RelativePath 'tests\Test-ReleaseArtifacts.ps1'
Assert-InstallerCondition ($artifactScanText -match 'Mosquitto password hash entry' -and $artifactScanText -match 'rendered platform MQTT password') 'Artifact scan must detect hashed password files and rendered platform credentials.'
$sandboxLauncherText = Get-InstallerText -RelativePath 'rehearsal\Invoke-HighTacWindowsSandbox.ps1'
$sandboxRunnerText = Get-InstallerText -RelativePath 'rehearsal\Run-HighTacSandboxRehearsal.ps1'
Assert-InstallerCondition ($sandboxLauncherText -match 'RepositoryMapped\s*=\s*\$false' -and $sandboxLauncherText -match 'LegacyPasswordFileMapped\s*=\s*\$false') 'Sandbox preparation must not map the repository or live legacy password file.'
Assert-InstallerCondition ($sandboxLauncherText -match "-BaselineVersion '1\.0\.0'" -and $sandboxLauncherText -match "-CandidateVersion '2\.0\.0'" -and $sandboxLauncherText -match 'ProductVersion must be') 'Sandbox preparation must verify baseline ProductVersion 1.0.0 and candidate ProductVersion 2.0.0 before launch.'
Assert-InstallerCondition ($sandboxLauncherText -match '\$InstallerPath\.sha256' -and $sandboxLauncherText -match '\$InstallerPath\.release\.json' -and $sandboxLauncherText -match 'checksum sidecar is required' -and $sandboxLauncherText -match 'release sidecar is required' -and $sandboxLauncherText -match 'source_worktree_clean' -and $sandboxLauncherText -match 'Get-RehearsalFileSha256') 'Sandbox preparation must require provenance sidecars, a clean source commit, and WhatIf-safe SHA-256 verification.'
Assert-InstallerCondition ($sandboxLauncherText -match 'different files' -and $sandboxLauncherText -match 'different SHA-256 hashes' -and $sandboxLauncherText -match 'ExpectedBaselineSha256' -and $sandboxLauncherText -match 'ExpectedCandidateSha256') 'Sandbox preparation must reject identical artifacts and bind the mapped copies to the preflight hashes.'
Assert-InstallerCondition ($sandboxRunnerText -match "USERNAME -cne 'WDAGUtilityAccount'" -and $sandboxRunnerText -match 'Refusing to run service-changing rehearsal outside Windows Sandbox') 'Service-changing rehearsal must refuse to run on the host.'
Assert-InstallerCondition ($sandboxRunnerText -match 'baseline_installer_hash_verified' -and $sandboxRunnerText -match 'candidate_installer_hash_verified' -and $sandboxRunnerText -match 'baseline_installer_sha256' -and $sandboxRunnerText -match 'candidate_installer_sha256') 'Sandbox report must retain only non-secret installer hashes and verify them before installation.'
Assert-InstallerCondition ($sandboxRunnerText -notmatch "Name='HighTacPlatform\.exe'" -and $sandboxRunnerText -notmatch 'Select-Object\s+-First\s+1' -and $sandboxRunnerText -match "Name='HighTacPlatform'" -and $sandboxRunnerText -match 'server\\HighTacPlatform\.exe' -and $sandboxRunnerText -match 'ParentProcessId') 'Restore detection must identify the packaged backend by full path and ancestry under the HighTacPlatform service PID, never by the first process name match.'
Assert-InstallerCondition ($sandboxRunnerText -match "Step 'admin_must_change_password'" -and $sandboxRunnerText -match 'must_change_password\s+-eq\s+\$true' -and $sandboxRunnerText -match 'must_change_password\s+-eq\s+\$false') 'Sandbox release health flow must verify the first-login password-change state before and after changing it.'
Assert-InstallerCondition ($sandboxRunnerText -match '\$operatorSettings\[''initial_admin_password''\]' -and $sandboxRunnerText -match 'current_password\s*=\s*\$initialAdminPassword' -and $sandboxRunnerText -notmatch '-Password\s+[''"]Adam[''"]') 'Sandbox rehearsal must consume the generated one-time admin password from protected operator output and never assume a fixed password.'
Assert-InstallerCondition ($sandboxRunnerText -notmatch '(?im)Write-(?:Host|Output|Information|Verbose).*password') 'Sandbox rehearsal must not log an administrator password.'
Assert-InstallerCondition ($sandboxRunnerText -match "ValidateSet\('GET', 'POST', 'PATCH'\)" -and $sandboxRunnerText -match '/settings/site' -and $sandboxRunnerText -match '/stations' -and $sandboxRunnerText -match '/tags/register' -and $sandboxRunnerText -match '/products' -and $sandboxRunnerText -match '/bindings' -and $sandboxRunnerText -match '/operation-logs' -and $sandboxRunnerText -match '/backups') 'Sandbox rehearsal must create and re-query site, station, tag, product, binding, audit, and backup records through public APIs.'
Assert-InstallerCondition ($sandboxRunnerText -match '\$tag\s*=\s*\$tagDetail\.tag' -and $sandboxRunnerText -match '\$tagDetail\.active_binding\.id\s+-ceq\s+\$Expected\.BindingId') 'Sandbox business verification must inspect the tag-detail response and its active binding rather than hashing empty top-level tag fields.'
Assert-InstallerCondition ($sandboxRunnerText -match 'SQLITE_OPEN_READONLY' -and $sandboxRunnerText -match 'PRAGMA integrity_check' -and $sandboxRunnerText -match 'PRAGMA foreign_key_check') 'Sandbox upgrade and reinstall checks must validate SQLite integrity through a read-only handle.'
Assert-InstallerCondition ($sandboxRunnerText -match 'function Test-RehearsalUninstallState' -and $sandboxRunnerText -match '\$installRemoved\s*=\s*-not' -and $sandboxRunnerText -match 'DataMustRemain \$true' -and $sandboxRunnerText -match 'DataMustRemain \$false') 'Sandbox uninstall checks must require Program Files removal for both keep-data and delete-data flows while distinguishing ProgramData retention.'
Assert-InstallerCondition ($sandboxRunnerText -match 'business_state_after_upgrade_sha256' -and $sandboxRunnerText -match 'business_state_after_reinstall_sha256' -and $sandboxRunnerText -match "Step 'upgrade_preserves_business_data'" -and $sandboxRunnerText -match "Step 'reinstall_restores_business_data'") 'Sandbox rehearsal must compare redacted business-state fingerprints after upgrade and preserved-data reinstall.'
Assert-InstallerCondition ($sandboxRunnerText -match 'schema_version\s*=\s*2' -and $sandboxRunnerText -match 'checks\s*=\s*\$checksCopy' -and $sandboxRunnerText -match 'counts\s*=\s*\$countsCopy' -and $sandboxRunnerText -match 'hashes\s*=\s*\$hashesCopy' -and $sandboxRunnerText -notmatch 'checked_at_utc|started_at_utc|completed_at_utc|\bdetail\s*=') 'Sandbox result JSON must be limited to Boolean checks, redacted counts, and SHA-256 hashes.'
Assert-InstallerCondition ($sandboxRunnerText -match "meAfterPasswordChangeResponse\.Headers\['X-CSRF-Token'\]") 'Sandbox writes after the mandatory password change must use the rotated CSRF token.'
foreach ($requiredStep in @('fresh_install', 'web_broker_stop', 'restore_real_service_restart', 'upgrade_preserves_programdata', 'keep_data_uninstall', 'delete_data_uninstall', 'legacy_runtime_migration')) {
    Assert-InstallerCondition ($sandboxRunnerText -match [regex]::Escape($requiredStep)) "Sandbox rehearsal must cover $requiredStep."
}

$committedVendorFiles = @(Get-ChildItem -LiteralPath $installerRoot -Recurse -File | Where-Object {
    $_.FullName -notmatch '[\\/]build[\\/]' -and $_.Extension -in '.exe', '.dll'
})
$committedVendorPaths = ($committedVendorFiles | ForEach-Object { $_.FullName }) -join ', '
Assert-InstallerCondition ($committedVendorFiles.Count -eq 0) "Vendor binaries must not be committed under installer: $committedVendorPaths"

Import-Module (Join-Path $installerRoot 'windows\HighTacInstaller.Common.psm1') -Force
$unownedServiceRejected = $false
try {
    [void](Get-HighTacService -Name 'mosquitto')
}
catch {
    $unownedServiceRejected = $_.Exception.Message -match 'outside HighTac ownership'
}
Assert-InstallerCondition $unownedServiceRejected 'Shared service helpers must reject the unrelated system mosquitto service before issuing service commands.'

$validStationIdCases = [ordered]@{
    '90A9F0000000' = '90A9F0000000'
    '90A9FABCDEF0' = '90A9FABCDEF0'
    ' 90A9F1234ABC ' = '90A9F1234ABC'
}
foreach ($inputStationId in $validStationIdCases.Keys) {
    try {
        $normalizedStationId = ConvertTo-HighTacStationId -StationId $inputStationId
        Assert-InstallerCondition ($normalizedStationId -ceq $validStationIdCases[$inputStationId]) "Valid station ID '$inputStationId' must be accepted without case normalization."
    }
    catch {
        Assert-InstallerCondition $false "Valid station ID '$inputStationId' was rejected: $($_.Exception.Message)"
    }
}

$invalidStationIds = @(
    '',
    '001122AABBCC',
    '90A9E1234567',
    '90A9F123456',
    '90A9F12345678',
    '90A9F12345G7',
    '90a9fabcdef0',
    '90A9F1234abc'
)
foreach ($invalidStationId in $invalidStationIds) {
    $wasRejected = $false
    $formatWasExplained = $false
    try {
        [void](ConvertTo-HighTacStationId -StationId $invalidStationId)
    }
    catch {
        $wasRejected = $true
        $formatWasExplained = $_.Exception.Message -match 'exactly match uppercase 90A9F.*7 uppercase hexadecimal characters'
    }
    Assert-InstallerCondition $wasRejected "Invalid station ID '$invalidStationId' must be rejected."
    Assert-InstallerCondition $formatWasExplained "Station ID rejection for '$invalidStationId' must explain the strict uppercase 90A9F plus 7 hexadecimal character format."
}

$migrationTestRoot = Join-Path ([IO.Path]::GetTempPath()) ("hightac-installer-migration-" + [guid]::NewGuid().ToString('N'))
$migrationSource = Join-Path $migrationTestRoot 'program\HighTac\Platform\server\runtime'
$migrationDestination = Join-Path $migrationTestRoot 'programdata\HighTac\Platform'
$migrationFiles = [ordered]@{
    'db\hightac.db' = 'database-fixture'
    'backups\daily.backup' = 'backup-fixture'
    'logs\platform.log' = 'log-fixture'
    'secrets\device.key' = 'key-fixture'
}
try {
    foreach ($relativePath in $migrationFiles.Keys) {
        $sourcePath = Join-Path $migrationSource $relativePath
        New-Item -ItemType Directory -Path (Split-Path -Parent $sourcePath) -Force | Out-Null
        [IO.File]::WriteAllText($sourcePath, $migrationFiles[$relativePath], (New-Object Text.UTF8Encoding($false)))
    }
    $ignoredPath = Join-Path $migrationSource 'pytest-agent\ignored.txt'
    New-Item -ItemType Directory -Path (Split-Path -Parent $ignoredPath) -Force | Out-Null
    [IO.File]::WriteAllText($ignoredPath, 'ignored-fixture', (New-Object Text.UTF8Encoding($false)))

    $migrationValidation = Copy-HighTacLegacyRuntimeData `
        -LegacyRuntimeRoot $migrationSource `
        -DataRoot $migrationDestination `
        -ValidateOnly `
        -Confirm:$false
    Assert-InstallerCondition ($migrationValidation.FilesDiscovered -eq $migrationFiles.Count -and $migrationValidation.FilesCopied -eq 0) 'Legacy runtime validation must inventory persistent files without copying them.'
    Assert-InstallerCondition (-not (Test-Path -LiteralPath $migrationDestination)) 'Legacy runtime validation must not create ProgramData.'

    $activeWriterRejected = $false
    $activeWriter = [IO.File]::Open(
        (Join-Path $migrationSource 'logs\platform.log'),
        [IO.FileMode]::Open,
        [IO.FileAccess]::ReadWrite,
        [IO.FileShare]::None
    )
    try {
        try {
            [void](Copy-HighTacLegacyRuntimeData `
                -LegacyRuntimeRoot $migrationSource `
                -DataRoot $migrationDestination `
                -Confirm:$false)
        }
        catch {
            $activeWriterRejected = $_.Exception.Message -match 'still open for writing'
        }
    }
    finally {
        $activeWriter.Dispose()
    }
    Assert-InstallerCondition $activeWriterRejected 'Legacy runtime migration must reject a source that is still open for writing.'
    Assert-InstallerCondition (-not (Test-Path -LiteralPath $migrationDestination)) 'An active legacy writer must be rejected before ProgramData is created.'

    $migrationResult = Copy-HighTacLegacyRuntimeData `
        -LegacyRuntimeRoot $migrationSource `
        -DataRoot $migrationDestination `
        -Confirm:$false
    Assert-InstallerCondition ($migrationResult.FilesCopied -eq $migrationFiles.Count) 'Legacy runtime migration must copy every discovered persistent file.'
    foreach ($relativePath in $migrationFiles.Keys) {
        $sourcePath = Join-Path $migrationSource $relativePath
        $destinationPath = Join-Path $migrationDestination $relativePath
        Assert-InstallerCondition (Test-Path -LiteralPath $sourcePath -PathType Leaf) "Legacy migration must preserve the source file: $relativePath"
        Assert-InstallerCondition (
            (Test-Path -LiteralPath $destinationPath -PathType Leaf) -and
            (Get-FileHash -LiteralPath $sourcePath -Algorithm SHA256).Hash -eq
            (Get-FileHash -LiteralPath $destinationPath -Algorithm SHA256).Hash
        ) "Legacy migration must copy bytes exactly: $relativePath"
    }
    Assert-InstallerCondition (-not (Test-Path -LiteralPath (Join-Path $migrationDestination 'pytest-agent'))) 'Legacy migration must ignore test and transient runtime directories.'

    $repeatResult = Copy-HighTacLegacyRuntimeData `
        -LegacyRuntimeRoot $migrationSource `
        -DataRoot $migrationDestination `
        -Confirm:$false
    Assert-InstallerCondition ($repeatResult.FilesCopied -eq 0 -and $repeatResult.FilesAlreadyPresent -eq $migrationFiles.Count) 'Legacy runtime migration must be idempotent when ProgramData already contains identical files.'

    [IO.File]::WriteAllText((Join-Path $migrationDestination 'db\hightac.db'), 'conflicting-fixture', (New-Object Text.UTF8Encoding($false)))
    $migrationConflictRejected = $false
    try {
        [void](Copy-HighTacLegacyRuntimeData `
            -LegacyRuntimeRoot $migrationSource `
            -DataRoot $migrationDestination `
            -Confirm:$false)
    }
    catch {
        $migrationConflictRejected = $_.Exception.Message -match 'conflicts with different ProgramData content'
    }
    Assert-InstallerCondition $migrationConflictRejected 'Legacy runtime migration must stop instead of overwriting different ProgramData content.'
    Assert-InstallerCondition (Test-Path -LiteralPath (Join-Path $migrationSource 'db\hightac.db') -PathType Leaf) 'A migration conflict must leave the legacy source intact.'
}
finally {
    Remove-Item -LiteralPath $migrationTestRoot -Recurse -Force -ErrorAction SilentlyContinue
}

$legacyTestPath = [IO.Path]::GetTempFileName()
try {
    $fakeLegacyEntry = 'hightac_mqtt:$7$101$' + ('A' * 88) + '$' + ('B' * 88)
    [IO.File]::WriteAllText($legacyTestPath, ($fakeLegacyEntry + "`n"), (New-Object Text.UTF8Encoding($false)))
    $legacyEntry = Get-HighTacLegacyMosquittoPasswordEntry -Path $legacyTestPath
    Assert-InstallerCondition ($legacyEntry.Username -ceq 'hightac_mqtt') 'Legacy import must accept exactly the hightac_mqtt station account.'
    Assert-InstallerCondition ($legacyEntry.PasswordEntry -ceq $fakeLegacyEntry) 'Legacy import must preserve the reviewed station hash entry byte-for-byte.'

    [IO.File]::AppendAllText($legacyTestPath, ('other_user:$7$101$' + ('C' * 88) + '$' + ('D' * 88) + "`n"))
    $extraUserRejected = $false
    try {
        [void](Get-HighTacLegacyMosquittoPasswordEntry -Path $legacyTestPath)
    }
    catch {
        $extraUserRejected = $_.Exception.Message -match 'exactly one account'
    }
    Assert-InstallerCondition $extraUserRejected 'Legacy import must reject password files containing any additional account.'
}
finally {
    Remove-Item -LiteralPath $legacyTestPath -Force -ErrorAction SilentlyContinue
}

$liveLegacyPath = Join-Path (Split-Path -Parent $installerRoot) 'tools\mqtt\runtime\config\passwordfile'
if (Test-Path -LiteralPath $liveLegacyPath -PathType Leaf) {
    try {
        $liveLegacyEntry = Get-HighTacLegacyMosquittoPasswordEntry -Path $liveLegacyPath
        Assert-InstallerCondition ($liveLegacyEntry.Username -ceq 'hightac_mqtt') 'Reviewed live legacy password file must validate without printing its hash.'
    }
    catch {
        Assert-InstallerCondition $false "Reviewed live legacy password file failed validation: $($_.Exception.Message)"
    }
}

$templateTokens = @{
    WEB_PORT = '8088'
    MQTT_PORT = '1884'
    LAN_IPV4 = '192.0.2.10'
    INSTALL_ROOT = 'C:/Program Files/HighTac/Platform'
    DATA_ROOT = 'C:/ProgramData/HighTac/Platform'
    MQTT_PLATFORM_USERNAME = 'backend_placeholder'
    MQTT_PLATFORM_PASSWORD = 'runtime_placeholder'
    MQTT_STATION_USERNAME = 'station_placeholder'
    MQTT_STATION_PASSWORD = 'runtime_placeholder'
    BOOTSTRAP_ADMIN_USERNAME = 'Adam'
    BOOTSTRAP_ADMIN_PASSWORD = New-HighTacRandomPassword
    BOOTSTRAP_ADMIN_MUST_CHANGE_PASSWORD = 'true'
    SITE_NAME = 'Test Site'
    STATION_ID = '90A9F1234ABC'
}
foreach ($templateName in @(
    'platform.yaml.template',
    'platform.env.template',
    'mosquitto.conf.template',
    'aclfile.template',
    'station-provisioning.txt.template',
    'station-provisioning-legacy.txt.template'
)) {
    try {
        $rendered = Get-HighTacRenderedTemplate -TemplatePath (Join-Path $installerRoot "config\$templateName") -Tokens $templateTokens
        Assert-InstallerCondition ($rendered -notmatch '__[A-Z0-9_]+__') "$templateName must render without unresolved placeholders."
    }
    catch {
        Assert-InstallerCondition $false "$templateName rendering failed: $($_.Exception.Message)"
    }
}

$renderedPlatformTemplate = Get-HighTacRenderedTemplate `
    -TemplatePath (Join-Path $installerRoot 'config\platform.yaml.template') `
    -Tokens $templateTokens
$renderedMosquittoTemplate = Get-HighTacRenderedTemplate `
    -TemplatePath (Join-Path $installerRoot 'config\mosquitto.conf.template') `
    -Tokens $templateTokens
Assert-InstallerCondition ($renderedPlatformTemplate -match '(?m)^\s*port:\s*8088\s*$' -and $renderedPlatformTemplate -match '(?ms)^mqtt:.*?^\s*port:\s*1884\s*$') 'Rendered platform configuration must bind Web/API 8088 and connect to the HighTac broker on 1884.'
Assert-InstallerCondition ($renderedMosquittoTemplate -match '(?m)^listener 1884 0\.0\.0\.0\s*$' -and $renderedMosquittoTemplate -notmatch '\b1883\b') 'Rendered HighTac Mosquitto configuration must listen on 1884 and never claim 1883.'

$password = New-HighTacRandomPassword
$secondPassword = New-HighTacRandomPassword
Assert-InstallerCondition ($password.Length -eq 32) 'Generated MQTT passwords must be 32 characters.'
Assert-InstallerCondition ($password -match '[A-Z]' -and $password -match '[a-z]' -and $password -match '[0-9]' -and $password -match '[!#$%&*+\-=?@^_]') 'Generated MQTT passwords must contain all required character classes.'
Assert-InstallerCondition ($password -cne $secondPassword) 'Platform and station random password generation must produce separate credentials.'

if ($failures.Count -gt 0) {
    Write-Output "Installer skeleton checks failed ($($failures.Count)):"
    $failures | ForEach-Object { Write-Output " - $_" }
    exit 1
}

Write-Output "Installer skeleton checks passed: $passed assertions."
