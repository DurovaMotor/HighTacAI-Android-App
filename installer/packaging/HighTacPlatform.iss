#ifndef PayloadRoot
  #error "PayloadRoot is required. Run New-ReleasePayload.ps1, then pass /DPayloadRoot=<payload directory>."
#endif
#ifndef AppVersion
  #error "AppVersion is required. Pass /DAppVersion=<semantic version>."
#endif
#ifndef OutputDir
  #error "OutputDir is required. Pass /DOutputDir=<ignored installer build directory>."
#endif

#define ReleasePayload RemoveBackslashUnlessRoot(PayloadRoot)
#define InstallerOutput RemoveBackslashUnlessRoot(OutputDir)

#if !DirExists(ReleasePayload)
  #error "PayloadRoot does not exist. Build the release payload before compiling the installer."
#endif
#if !FileExists(ReleasePayload + "\server\HighTacPlatform.exe")
  #error "Missing PyInstaller one-folder artifact: server\HighTacPlatform.exe"
#endif
#if !FileExists(ReleasePayload + "\mosquitto\mosquitto.exe")
  #error "Missing official Mosquitto build input: mosquitto\mosquitto.exe"
#endif
#if !FileExists(ReleasePayload + "\mosquitto\mosquitto_passwd.exe")
  #error "Missing official Mosquitto credential utility: mosquitto\mosquitto_passwd.exe"
#endif
#if !FileExists(ReleasePayload + "\mosquitto\MSVCP140.dll") || !FileExists(ReleasePayload + "\mosquitto\VCRUNTIME140.dll") || !FileExists(ReleasePayload + "\mosquitto\VCRUNTIME140_1.dll")
  #error "Missing signed app-local Microsoft VC++ runtime DLLs required by Mosquitto on a clean Windows installation."
#endif
#if !FileExists(ReleasePayload + "\service\HighTacPlatform.exe") || !FileExists(ReleasePayload + "\service\HighTacMqttBroker.exe")
  #error "Missing renamed WinSW x64 wrappers in the release payload."
#endif
#if !FileExists(ReleasePayload + "\licenses\THIRD-PARTY-NOTICES.md") || !FileExists(ReleasePayload + "\licenses\WinSW-License.txt")
  #error "Missing third-party notices or WinSW license in the release payload."
#endif
#if !FileExists(ReleasePayload + "\dependencies\VC_redist.x64.exe") || !FileExists(ReleasePayload + "\licenses\Microsoft-VC-Runtime-Notice.txt")
  #error "Missing reviewed Microsoft Visual C++ x64 redistributable or redistribution notice."
#endif
#if !FileExists(ReleasePayload + "\SHA256SUMS.txt")
  #error "Missing SHA256SUMS.txt. Rebuild the release payload before compiling."
#endif
#if !FileExists(ReleasePayload + "\BUILD-MANIFEST.json")
  #error "Missing BUILD-MANIFEST.json. Rebuild the release payload before compiling."
#endif

[Setup]
AppId={{3D2FA228-66D2-4D1C-905A-B5BCA011CE32}
AppName=HighTac Platform
AppVersion={#AppVersion}
AppPublisher=HighTac
DefaultDirName={autopf}\HighTac\Platform
DefaultGroupName=HighTac Platform
DisableProgramGroupPage=yes
DisableDirPage=yes
DisableReadyMemo=no
DisableWelcomePage=no
#if FileExists(ReleasePayload + "\licenses\HighTac-Product-License.txt")
  LicenseFile={#ReleasePayload}\licenses\HighTac-Product-License.txt
#endif
OutputDir={#InstallerOutput}
OutputBaseFilename=HighTacPlatform-{#AppVersion}-x64
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
PrivilegesRequired=admin
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0.17763
CloseApplications=no
RestartApplications=no
SetupLogging=yes
UninstallDisplayIcon={app}\server\HighTacPlatform.exe

[Files]
Source: "{#ReleasePayload}\server\*"; DestDir: "{app}\server"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "{#ReleasePayload}\mosquitto\*"; DestDir: "{app}\mosquitto"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "{#ReleasePayload}\service\*"; DestDir: "{app}\service"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "{#ReleasePayload}\tools\*"; DestDir: "{app}\tools"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "{#ReleasePayload}\templates\*"; DestDir: "{app}\templates"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "{#ReleasePayload}\licenses\*"; DestDir: "{app}\licenses"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "{#ReleasePayload}\dependencies\*"; DestDir: "{app}\dependencies"; Flags: ignoreversion recursesubdirs createallsubdirs
  Source: "{#ReleasePayload}\SHA256SUMS.txt"; DestDir: "{app}"; Flags: ignoreversion
  Source: "{#ReleasePayload}\BUILD-MANIFEST.json"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#ReleasePayload}\DEPLOYMENT-README.md"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#ReleasePayload}\tools\HighTacInstaller.Common.psm1"; Flags: dontcopy
  Source: "{#ReleasePayload}\tools\Test-HighTacStaticIp.ps1"; Flags: dontcopy
  Source: "{#ReleasePayload}\tools\Test-HighTacLegacyMqttCredential.ps1"; Flags: dontcopy
  Source: "{#ReleasePayload}\tools\Upgrade-HighTacPlatform.ps1"; Flags: dontcopy

[Code]
var
  ConfigurationPage: TInputQueryWizardPage;
  LegacyCredentialPage: TInputFileWizardPage;
  UpgradeDetected: Boolean;
  DeleteDataOnUninstall: Boolean;
  ServicesPreparedForUpgrade: Boolean;
  DependencyRestartRequired: Boolean;
  PostInstallFailed: Boolean;

function IsOwnedServiceInstalled(const ServiceName: String): Boolean;
begin
  Result := RegKeyExists(HKLM, 'SYSTEM\CurrentControlSet\Services\' + ServiceName);
end;

function HasManagedProgramData(): Boolean;
begin
  Result :=
    FileExists(ExpandConstant('{commonappdata}\HighTac\Platform\config\install-state.json')) or
    (FileExists(ExpandConstant('{commonappdata}\HighTac\Platform\config\.env')) and
     FileExists(ExpandConstant('{commonappdata}\HighTac\Platform\mqtt\passwordfile')));
end;

function DetectUpgrade(): Boolean;
begin
  Result := HasManagedProgramData();
end;

function IsValidStationId(const Value: String): Boolean;
var
  Index: Integer;
  Character: Char;
begin
  Result := Length(Value) = 12;
  if not Result then
    Exit;
  Result := Copy(Value, 1, 5) = '90A9F';
  if not Result then
    Exit;
  for Index := 1 to Length(Value) do
  begin
    Character := Value[Index];
    if not (((Character >= '0') and (Character <= '9')) or
            ((Character >= 'A') and (Character <= 'F'))) then
    begin
      Result := False;
      Exit;
    end;
  end;
end;

procedure InitializeWizard();
begin
  UpgradeDetected := DetectUpgrade();
  ServicesPreparedForUpgrade := False;
  DependencyRestartRequired := False;
  PostInstallFailed := False;

  ConfigurationPage := CreateInputQueryPage(
    wpSelectDir,
    'HighTac site configuration',
    'Configure the trusted LAN endpoint',
    'The selected adapter must already use a static IPv4 address and the Private network profile.');
  ConfigurationPage.Add('Site name:', False);
  ConfigurationPage.Add('Station SN (90A9F followed by 7 hexadecimal characters):', False);
  ConfigurationPage.Add('LAN adapter alias (blank for automatic selection):', False);
  ConfigurationPage.Add('Web/API port:', False);
  ConfigurationPage.Add('MQTT port (fixed):', False);
  ConfigurationPage.Values[0] := ExpandConstant('{param:SITENAME|HighTac Site}');
  ConfigurationPage.Values[1] := ExpandConstant('{param:STATIONID|}');
  ConfigurationPage.Values[2] := ExpandConstant('{param:INTERFACEALIAS|}');
  ConfigurationPage.Values[3] := ExpandConstant('{param:WEBPORT|8088}');
  ConfigurationPage.Values[4] := '1884';
  ConfigurationPage.Edits[4].ReadOnly := True;

  LegacyCredentialPage := CreateInputFilePage(
    ConfigurationPage.ID,
    'Existing station credential',
    'Optional reviewed legacy Mosquitto password hash import',
    'Select the existing passwordfile only when the deployed station already uses hightac_mqtt. The password is preserved but cannot be recovered or printed. Leave this blank on other PCs.');
  LegacyCredentialPage.Add(
    'Existing Mosquitto passwordfile:',
    'Mosquitto password file|passwordfile|All files|*',
    '');
  LegacyCredentialPage.Values[0] := ExpandConstant('{param:LEGACYPASSWORDFILE|}');
end;

function ShouldSkipPage(PageID: Integer): Boolean;
begin
  Result := ((PageID = ConfigurationPage.ID) or
             (PageID = LegacyCredentialPage.ID)) and UpgradeDetected;
end;

function GetLegacyPasswordFile(Param: String): String;
begin
  Result := Trim(LegacyCredentialPage.Values[0]);
end;

function IsRehearsalMode(): Boolean;
begin
  Result := CompareText(ExpandConstant('{param:REHEARSAL|0}'), '1') = 0;
end;

function NextButtonClick(CurPageID: Integer): Boolean;
var
  WebPort: Integer;
begin
  Result := True;
  if CurPageID = wpSelectDir then
    UpgradeDetected := DetectUpgrade();

  if (CurPageID = ConfigurationPage.ID) and not UpgradeDetected then
  begin
    if Trim(ConfigurationPage.Values[0]) = '' then
    begin
      MsgBox('Site name is required.', mbError, MB_OK);
      Result := False;
      Exit;
    end;
    if (Pos('"', ConfigurationPage.Values[0]) > 0) or
       (Pos('"', ConfigurationPage.Values[2]) > 0) then
    begin
      MsgBox('Site name and adapter alias cannot contain quotation marks.', mbError, MB_OK);
      Result := False;
      Exit;
    end;
    if not IsValidStationId(Trim(ConfigurationPage.Values[1])) then
    begin
      MsgBox('Station SN must exactly match uppercase 90A9F followed by 7 uppercase hexadecimal characters (12 characters total).', mbError, MB_OK);
      Result := False;
      Exit;
    end;
    WebPort := StrToIntDef(Trim(ConfigurationPage.Values[3]), 0);
    if (WebPort < 1) or (WebPort > 65535) or (WebPort = 1884) then
    begin
      MsgBox('Web/API port must be between 1 and 65535 and cannot be 1884.', mbError, MB_OK);
      Result := False;
      Exit;
    end;
  end;
  if (CurPageID = LegacyCredentialPage.ID) and not UpgradeDetected and
     (GetLegacyPasswordFile('') <> '') and
     not FileExists(GetLegacyPasswordFile('')) then
  begin
    MsgBox('The selected legacy Mosquitto password file does not exist.', mbError, MB_OK);
    Result := False;
  end;
end;

function GetSiteName(Param: String): String;
begin
  Result := Trim(ConfigurationPage.Values[0]);
end;

function GetStationId(Param: String): String;
begin
  Result := Trim(ConfigurationPage.Values[1]);
end;

function GetInterfaceAlias(Param: String): String;
begin
  Result := Trim(ConfigurationPage.Values[2]);
end;

function GetWebPort(Param: String): String;
begin
  Result := Trim(ConfigurationPage.Values[3]);
end;

function RunPowerShellScript(const ScriptPath, Arguments: String; var ResultCode: Integer): Boolean;
var
  PowerShellPath: String;
  Parameters: String;
begin
  PowerShellPath := ExpandConstant('{sys}\WindowsPowerShell\v1.0\powershell.exe');
  Parameters := '-NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File ' +
                AddQuotes(ScriptPath) + ' ' + Arguments + ' -Force';
  Log('HighTac PowerShell script: ' + ScriptPath);
  Log('HighTac PowerShell non-secret arguments: ' + Arguments + ' -Force');
  Result := Exec(PowerShellPath, Parameters, ExpandConstant('{tmp}'), SW_HIDE, ewWaitUntilTerminated, ResultCode);
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
var
  ResultCode: Integer;
  PreflightArguments: String;
  UpgradeArguments: String;
  LegacyValidationArguments: String;
  WebPort: Integer;
begin
  Result := '';
  UpgradeDetected := DetectUpgrade();

  ExtractTemporaryFile('HighTacInstaller.Common.psm1');
  if IsOwnedServiceInstalled('HighTacPlatform') or
     IsOwnedServiceInstalled('HighTacMqttBroker') then
  begin
    ExtractTemporaryFile('Upgrade-HighTacPlatform.ps1');
    UpgradeArguments := '-Phase Prepare -InstallRoot ' + AddQuotes(WizardDirValue) +
                        ' -DataRoot ' + AddQuotes(ExpandConstant('{commonappdata}\HighTac\Platform'));
    if not RunPowerShellScript(ExpandConstant('{tmp}\Upgrade-HighTacPlatform.ps1'), UpgradeArguments, ResultCode) or
       (ResultCode <> 0) then
    begin
      Result := 'HighTac upgrade preparation failed while stopping the owned services. No unrelated Mosquitto service was modified. Review the setup log and retry.';
      Exit;
    end;
    ServicesPreparedForUpgrade := True;
  end;

  if not UpgradeDetected then
  begin
    if Trim(GetSiteName('')) = '' then
    begin
      Result := 'Site name is required.';
      Exit;
    end;
    if (Pos('"', GetSiteName('')) > 0) or
       (Pos('"', GetInterfaceAlias('')) > 0) then
    begin
      Result := 'Site name and adapter alias cannot contain quotation marks.';
      Exit;
    end;
    if not IsValidStationId(GetStationId('')) then
    begin
      Result := 'Station SN must exactly match uppercase 90A9F followed by 7 uppercase hexadecimal characters.';
      Exit;
    end;
    WebPort := StrToIntDef(GetWebPort(''), 0);
    if (WebPort < 1) or (WebPort > 65535) or (WebPort = 1884) then
    begin
      Result := 'Web/API port must be between 1 and 65535 and cannot be 1884.';
      Exit;
    end;

    if GetLegacyPasswordFile('') <> '' then
    begin
      if not FileExists(GetLegacyPasswordFile('')) then
      begin
        Result := 'The selected legacy Mosquitto password file does not exist.';
        Exit;
      end;
      ExtractTemporaryFile('Test-HighTacLegacyMqttCredential.ps1');
      LegacyValidationArguments := '-PasswordFile ' + AddQuotes(GetLegacyPasswordFile(''));
      if not RunPowerShellScript(ExpandConstant('{tmp}\Test-HighTacLegacyMqttCredential.ps1'), LegacyValidationArguments, ResultCode) or
         (ResultCode <> 0) then
      begin
        Result := 'Legacy station credential validation failed. The file must contain exactly one modern Mosquitto hash for hightac_mqtt; no hash or password was displayed.';
        Exit;
      end;
    end;

    if not IsRehearsalMode() then
    begin
      ExtractTemporaryFile('Test-HighTacStaticIp.ps1');
      PreflightArguments := '-WebPort ' + GetWebPort('') + ' -MqttPort 1884';
      if GetInterfaceAlias('') <> '' then
        PreflightArguments := '-InterfaceAlias ' + AddQuotes(GetInterfaceAlias('')) +
                              ' ' + PreflightArguments;
      if not RunPowerShellScript(ExpandConstant('{tmp}\Test-HighTacStaticIp.ps1'), PreflightArguments, ResultCode) or
         (ResultCode <> 0) then
      begin
        Result := 'HighTac network preflight failed. The LAN adapter must use a static IPv4 address, the Private profile, and an available configured Web/API port plus MQTT 1884. Run Test-HighTacStaticIp.ps1 from an elevated PowerShell for details.';
        Exit;
      end;
    end;
  end;
end;

procedure CurStepChanged(CurStep: TSetupStep);
var
  ResultCode: Integer;
  DependencyPath: String;
  DeploymentScript: String;
  DeploymentArguments: String;
  LegacyRuntimePath: String;
begin
  if CurStep = ssPostInstall then
  begin
    if not IsRehearsalMode() then
    begin
      ResultCode := -1;
      DependencyPath := ExpandConstant('{app}\dependencies\VC_redist.x64.exe');
      WizardForm.StatusLabel.Caption := 'Installing Microsoft Visual C++ runtime dependency...';
      if not Exec(DependencyPath, '/install /quiet /norestart', ExpandConstant('{app}\dependencies'), SW_HIDE, ewWaitUntilTerminated, ResultCode) then
      begin
        PostInstallFailed := True;
        RaiseException('Could not launch the Microsoft Visual C++ runtime installer.');
      end;
      if (ResultCode <> 0) and (ResultCode <> 1638) and (ResultCode <> 3010) then
      begin
        PostInstallFailed := True;
        RaiseException(Format('Microsoft Visual C++ runtime installation failed with exit code %d.', [ResultCode]));
      end;
      if ResultCode = 3010 then
        DependencyRestartRequired := True;
    end;

    if UpgradeDetected then
    begin
      WizardForm.StatusLabel.Caption := 'Refreshing HighTac services and validating the upgrade...';
      DeploymentScript := ExpandConstant('{app}\tools\Upgrade-HighTacPlatform.ps1');
      DeploymentArguments := '-Phase Finalize -InstallRoot ' + AddQuotes(ExpandConstant('{app}')) +
                             ' -DataRoot ' + AddQuotes(ExpandConstant('{commonappdata}\HighTac\Platform')) +
                             ' -SkipDependencyInstall';
    end
    else
    begin
      WizardForm.StatusLabel.Caption := 'Installing HighTac services and secure runtime configuration...';
      DeploymentScript := ExpandConstant('{app}\tools\Install-HighTacPlatform.ps1');
      DeploymentArguments := '-InstallRoot ' + AddQuotes(ExpandConstant('{app}')) +
                             ' -DataRoot ' + AddQuotes(ExpandConstant('{commonappdata}\HighTac\Platform')) +
                             ' -TemplateRoot ' + AddQuotes(ExpandConstant('{app}\templates')) +
                             ' -SiteName ' + AddQuotes(GetSiteName('')) +
                             ' -StationId ' + AddQuotes(GetStationId('')) +
                             ' -WebPort ' + GetWebPort('') + ' -MqttPort 1884';
      if GetInterfaceAlias('') <> '' then
        DeploymentArguments := DeploymentArguments +
                               ' -InterfaceAlias ' + AddQuotes(GetInterfaceAlias(''));
      LegacyRuntimePath := ExpandConstant('{app}\server\runtime');
      if DirExists(LegacyRuntimePath) then
        DeploymentArguments := DeploymentArguments +
                               ' -LegacyRuntimeRoot ' + AddQuotes(LegacyRuntimePath);
      if GetLegacyPasswordFile('') <> '' then
        DeploymentArguments := DeploymentArguments +
                               ' -LegacyStationPasswordFile ' + AddQuotes(GetLegacyPasswordFile(''));
      if IsRehearsalMode() then
        DeploymentArguments := DeploymentArguments +
                               ' -SkipNetworkPreflight -LanIPv4 127.0.0.1';
    end;
    if not RunPowerShellScript(DeploymentScript, DeploymentArguments, ResultCode) or
       (ResultCode <> 0) then
    begin
      PostInstallFailed := True;
      RaiseException(Format('HighTac deployment configuration failed with exit code %d. Review setup and ProgramData logs before retrying.', [ResultCode]));
    end;
  end;
end;

function NeedRestart(): Boolean;
begin
  Result := DependencyRestartRequired;
end;

procedure DeinitializeSetup();
var
  ResultCode: Integer;
  ServiceControl: String;
begin
  if ServicesPreparedForUpgrade then
  begin
    ServiceControl := ExpandConstant('{sys}\sc.exe');
    Exec(ServiceControl, 'start HighTacMqttBroker', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
    Exec(ServiceControl, 'start HighTacPlatform', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  end;
end;

function GetCustomSetupExitCode(): Integer;
begin
  if PostInstallFailed then
    Result := 1
  else
    Result := 0;
end;

function InitializeUninstall(): Boolean;
begin
  Result := True;
  DeleteDataOnUninstall := CompareText(ExpandConstant('{param:DELETEDATA|0}'), '1') = 0;
  if not UninstallSilent then
    DeleteDataOnUninstall := MsgBox(
      'Remove all HighTac runtime data, including configuration, MQTT credentials, database, logs, and backups?' + #13#10 + #13#10 +
      'Choose No to preserve ProgramData for a reinstall or upgrade (recommended).',
      mbConfirmation,
      MB_YESNO or MB_DEFBUTTON2) = IDYES;
end;

procedure CurPageChanged(CurPageID: Integer);
begin
  if CurPageID = wpFinished then
    WizardForm.FinishedLabel.Caption := WizardForm.FinishedLabel.Caption + #13#10 + #13#10 +
      'Protected station, app, and initial administrator configuration is stored at:' + #13#10 +
      ExpandConstant('{commonappdata}\HighTac\Platform\config\station-mqtt-credentials.txt');
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var
  ResultCode: Integer;
  UninstallArguments: String;
begin
  if CurUninstallStep = usUninstall then
  begin
    ResultCode := -1;
    UninstallProgressForm.StatusLabel.Caption := 'Removing HighTac services and firewall rules...';
    UninstallArguments := '-InstallRoot ' + AddQuotes(ExpandConstant('{app}')) +
                          ' -DataRoot ' + AddQuotes(ExpandConstant('{commonappdata}\HighTac\Platform'));
    if DeleteDataOnUninstall then
      UninstallArguments := UninstallArguments + ' -RemoveData'
    else
      UninstallArguments := UninstallArguments + ' -PreserveData';

    if not RunPowerShellScript(ExpandConstant('{app}\tools\Uninstall-HighTacPlatform.ps1'), UninstallArguments, ResultCode) or
       (ResultCode <> 0) then
      RaiseException(Format('HighTac service cleanup failed with exit code %d. Application files were not intentionally removed by the cleanup script.', [ResultCode]));
  end;
end;
