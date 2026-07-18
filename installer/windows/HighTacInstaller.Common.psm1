Set-StrictMode -Version Latest

$script:HighTacPlatformService = 'HighTacPlatform'
$script:HighTacBrokerService = 'HighTacMqttBroker'

function Assert-HighTacOwnedServiceName {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name
    )

    if ($Name -notin @($script:HighTacPlatformService, $script:HighTacBrokerService)) {
        throw "Refusing to manage a service outside HighTac ownership: $Name"
    }
}

function Assert-HighTacWindows {
    [CmdletBinding()]
    param()

    if ($env:OS -ne 'Windows_NT') {
        throw 'HighTac Windows deployment scripts can only run on Windows.'
    }
}

function Test-HighTacAdministrator {
    [CmdletBinding()]
    [OutputType([bool])]
    param()

    Assert-HighTacWindows
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($identity)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Assert-HighTacAdministrator {
    [CmdletBinding()]
    param()

    if (-not (Test-HighTacAdministrator)) {
        throw 'Administrator privileges are required. Re-run PowerShell with Run as administrator.'
    }
}

function Resolve-HighTacFullPath {
    [CmdletBinding()]
    [OutputType([string])]
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    $expanded = [Environment]::ExpandEnvironmentVariables($Path)
    return [IO.Path]::GetFullPath($expanded).TrimEnd('\')
}

function Assert-HighTacSafeRoot {
    [CmdletBinding()]
    [OutputType([string])]
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,

        [Parameter(Mandatory = $true)]
        [ValidateSet('InstallRoot', 'DataRoot')]
        [string]$Kind
    )

    $fullPath = Resolve-HighTacFullPath -Path $Path
    $pathRoot = [IO.Path]::GetPathRoot($fullPath).TrimEnd('\')
    if ($fullPath -eq $pathRoot) {
        throw "$Kind cannot be a drive root: $fullPath"
    }

    $leaf = Split-Path -Leaf $fullPath
    $parentLeaf = Split-Path -Leaf (Split-Path -Parent $fullPath)
    if ($leaf -ne 'Platform' -or $parentLeaf -ne 'HighTac') {
        throw "$Kind must end in 'HighTac\Platform': $fullPath"
    }

    # Resolve existing paths to catch junctions before any recursive removal.
    if (Test-Path -LiteralPath $fullPath) {
        $item = Get-Item -LiteralPath $fullPath -Force
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "$Kind cannot be a symbolic link or junction: $fullPath"
        }
        $fullPath = $item.FullName.TrimEnd('\')
        $leaf = Split-Path -Leaf $fullPath
        $parentLeaf = Split-Path -Leaf (Split-Path -Parent $fullPath)
        if ($leaf -ne 'Platform' -or $parentLeaf -ne 'HighTac') {
            throw "$Kind resolved outside the expected HighTac\Platform root: $fullPath"
        }
    }

    return $fullPath
}

function ConvertTo-HighTacStationId {
    [CmdletBinding()]
    [OutputType([string])]
    param(
        [Parameter(Mandatory = $true)]
        [AllowEmptyString()]
        [string]$StationId
    )

    $normalizedStationId = $StationId.Trim()
    if ($normalizedStationId -cnotmatch '^90A9F[0-9A-F]{7}$') {
        throw 'Station SN must exactly match uppercase 90A9F followed by 7 uppercase hexadecimal characters (12 characters total).'
    }
    return $normalizedStationId
}

function ConvertTo-HighTacConfigPath {
    [CmdletBinding()]
    [OutputType([string])]
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    return (Resolve-HighTacFullPath -Path $Path).Replace('\', '/')
}

function ConvertTo-HighTacYamlString {
    [CmdletBinding()]
    [OutputType([string])]
    param(
        [AllowEmptyString()]
        [Parameter(Mandatory = $true)]
        [string]$Value
    )

    return $Value.Replace('\', '\\').Replace('"', '\"').Replace("`r", '\r').Replace("`n", '\n')
}

function ConvertFrom-HighTacSecureString {
    [CmdletBinding()]
    [OutputType([string])]
    param(
        [Parameter(Mandatory = $true)]
        [Security.SecureString]$SecureString
    )

    $pointer = [IntPtr]::Zero
    try {
        $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($SecureString)
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    }
    finally {
        if ($pointer -ne [IntPtr]::Zero) {
            [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
        }
    }
}

function Get-HighTacRandomInteger {
    [CmdletBinding()]
    [OutputType([int])]
    param(
        [Parameter(Mandatory = $true)]
        [ValidateRange(1, 2147483647)]
        [int]$UpperExclusive
    )

    $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $bytes = New-Object byte[] 4
        $range = [uint64]4294967296
        $limit = $range - ($range % [uint64]$UpperExclusive)
        do {
            $generator.GetBytes($bytes)
            $value = [BitConverter]::ToUInt32($bytes, 0)
        } while ([uint64]$value -ge $limit)
        return [int]([uint64]$value % [uint64]$UpperExclusive)
    }
    finally {
        $generator.Dispose()
    }
}

function New-HighTacRandomPassword {
    [CmdletBinding()]
    [OutputType([string])]
    param(
        [ValidateRange(24, 128)]
        [int]$Length = 32
    )

    $characterSets = @(
        'ABCDEFGHJKLMNPQRSTUVWXYZ',
        'abcdefghijkmnopqrstuvwxyz',
        '23456789',
        '!#$%&*+-=?@^_'
    )
    $allCharacters = ($characterSets -join '')
    $characters = New-Object 'System.Collections.Generic.List[char]'

    foreach ($set in $characterSets) {
        $characters.Add($set[(Get-HighTacRandomInteger -UpperExclusive $set.Length)])
    }
    while ($characters.Count -lt $Length) {
        $characters.Add($allCharacters[(Get-HighTacRandomInteger -UpperExclusive $allCharacters.Length)])
    }

    for ($index = $characters.Count - 1; $index -gt 0; $index--) {
        $swapIndex = Get-HighTacRandomInteger -UpperExclusive ($index + 1)
        $temporary = $characters[$index]
        $characters[$index] = $characters[$swapIndex]
        $characters[$swapIndex] = $temporary
    }
    return -join $characters
}

function Get-HighTacRenderedTemplate {
    [CmdletBinding()]
    [OutputType([string])]
    param(
        [Parameter(Mandatory = $true)]
        [string]$TemplatePath,

        [Parameter(Mandatory = $true)]
        [hashtable]$Tokens
    )

    if (-not (Test-Path -LiteralPath $TemplatePath -PathType Leaf)) {
        throw "Required template was not found: $TemplatePath"
    }

    $content = [IO.File]::ReadAllText((Resolve-HighTacFullPath -Path $TemplatePath))
    foreach ($tokenName in $Tokens.Keys) {
        $content = $content.Replace("__$tokenName`__", [string]$Tokens[$tokenName])
    }

    $unresolved = @([regex]::Matches($content, '__[A-Z0-9_]+__') | ForEach-Object { $_.Value } | Sort-Object -Unique)
    if ($unresolved.Count -gt 0) {
        throw "Template '$TemplatePath' has unresolved tokens: $($unresolved -join ', ')"
    }
    return $content
}

function Write-HighTacTextFile {
    [CmdletBinding(SupportsShouldProcess = $true)]
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,

        [Parameter(Mandatory = $true)]
        [AllowEmptyString()]
        [string]$Content,

        [switch]$NoClobber
    )

    $fullPath = Resolve-HighTacFullPath -Path $Path
    if ($NoClobber -and (Test-Path -LiteralPath $fullPath)) {
        return
    }

    if ($PSCmdlet.ShouldProcess($fullPath, 'Write UTF-8 configuration file')) {
        $parent = Split-Path -Parent $fullPath
        if (-not (Test-Path -LiteralPath $parent)) {
            New-Item -ItemType Directory -Path $parent -Force | Out-Null
        }
        $utf8WithoutBom = New-Object Text.UTF8Encoding($false)
        [IO.File]::WriteAllText($fullPath, $Content, $utf8WithoutBom)
    }
}

function Protect-HighTacPath {
    [CmdletBinding(SupportsShouldProcess = $true)]
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    $fullPath = Resolve-HighTacFullPath -Path $Path
    if (-not (Test-Path -LiteralPath $fullPath)) {
        return
    }

    if ($PSCmdlet.ShouldProcess($fullPath, 'Restrict access to LocalSystem and Administrators')) {
        $item = Get-Item -LiteralPath $fullPath -Force
        $acl = Get-Acl -LiteralPath $fullPath
        $acl.SetAccessRuleProtection($true, $false)

        foreach ($rule in @($acl.Access)) {
            [void]$acl.RemoveAccessRuleSpecific($rule)
        }

        $inheritance = [Security.AccessControl.InheritanceFlags]::None
        if ($item.PSIsContainer) {
            $inheritance = [Security.AccessControl.InheritanceFlags]'ContainerInherit, ObjectInherit'
        }
        $propagation = [Security.AccessControl.PropagationFlags]::None
        $allow = [Security.AccessControl.AccessControlType]::Allow
        $rights = [Security.AccessControl.FileSystemRights]::FullControl

        foreach ($sidValue in @('S-1-5-18', 'S-1-5-32-544')) {
            $sid = New-Object Security.Principal.SecurityIdentifier($sidValue)
            $rule = New-Object Security.AccessControl.FileSystemAccessRule($sid, $rights, $inheritance, $propagation, $allow)
            $acl.AddAccessRule($rule)
        }
        Set-Acl -LiteralPath $fullPath -AclObject $acl
    }
}

function Get-HighTacLegacyMosquittoPasswordEntry {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,

        [ValidatePattern('^[A-Za-z0-9_-]+$')]
        [string]$ExpectedUsername = 'hightac_mqtt'
    )

    $fullPath = Resolve-HighTacFullPath -Path $Path
    if (-not (Test-Path -LiteralPath $fullPath -PathType Leaf)) {
        throw "Legacy Mosquitto password file was not found: $fullPath"
    }

    $item = Get-Item -LiteralPath $fullPath -Force
    if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw 'Legacy Mosquitto password file cannot be a symbolic link or junction.'
    }
    if ($item.Length -gt 16384) {
        throw 'Legacy Mosquitto password file is unexpectedly large.'
    }

    $entries = @([IO.File]::ReadAllLines($fullPath) | Where-Object {
        -not [string]::IsNullOrWhiteSpace($_) -and -not $_.TrimStart().StartsWith('#')
    })
    if ($entries.Count -ne 1) {
        throw "Legacy Mosquitto password file must contain exactly one account named '$ExpectedUsername'."
    }

    $entry = [string]$entries[0]
    if ($entry -ne $entry.Trim() -or $entry.IndexOf(':') -le 0) {
        throw 'Legacy Mosquitto password entry has an invalid format.'
    }
    $parts = $entry.Split(':', 2)
    if ($parts[0] -cne $ExpectedUsername) {
        throw "Legacy Mosquitto password file must contain only the '$ExpectedUsername' account."
    }
    if ($parts[1] -notmatch '^\$7\$[0-9]{1,6}\$[A-Za-z0-9+/=]{40,128}\$[A-Za-z0-9+/=]{40,128}$') {
        throw 'Legacy Mosquitto password entry is not a supported modern Mosquitto password hash.'
    }

    return [pscustomobject]@{
        Username = $parts[0]
        PasswordEntry = $entry
    }
}

function New-HighTacMosquittoPasswordFile {
    [CmdletBinding(SupportsShouldProcess = $true)]
    param(
        [Parameter(Mandatory = $true)]
        [string]$MosquittoPasswordTool,

        [Parameter(Mandatory = $true)]
        [string]$DestinationPath,

        [Parameter(Mandatory = $true)]
        [hashtable]$Credentials,

        [string]$LegacyStationPasswordFile,

        [ValidatePattern('^[A-Za-z0-9_-]+$')]
        [string]$LegacyStationUsername = 'hightac_mqtt'
    )

    if (-not (Test-Path -LiteralPath $MosquittoPasswordTool -PathType Leaf)) {
        throw "Official mosquitto_passwd executable was not found: $MosquittoPasswordTool"
    }
    foreach ($username in $Credentials.Keys) {
        $password = [string]$Credentials[$username]
        if ($username -match '[:\r\n]' -or $password -match '[\r\n]') {
            throw 'MQTT usernames cannot contain colons or newlines, and passwords cannot contain newlines.'
        }
    }

    $legacyEntry = $null
    if ($LegacyStationPasswordFile) {
        if ($Credentials.ContainsKey($LegacyStationUsername)) {
            throw "Legacy station account '$LegacyStationUsername' cannot also be supplied as a plaintext credential."
        }
        $legacyEntry = Get-HighTacLegacyMosquittoPasswordEntry `
            -Path $LegacyStationPasswordFile `
            -ExpectedUsername $LegacyStationUsername
    }

    $destination = Resolve-HighTacFullPath -Path $DestinationPath
    if ($LegacyStationPasswordFile -and
        $destination -eq (Resolve-HighTacFullPath -Path $LegacyStationPasswordFile)) {
        throw 'Legacy Mosquitto password source and managed destination must be different files.'
    }
    if (-not $PSCmdlet.ShouldProcess($destination, 'Generate hashed Mosquitto password file')) {
        return
    }

    $temporaryPath = "$destination.$([Guid]::NewGuid().ToString('N')).plaintext"
    try {
        $lines = foreach ($username in ($Credentials.Keys | Sort-Object)) {
            "$username`:$($Credentials[$username])"
        }
        Write-HighTacTextFile -Path $temporaryPath -Content (($lines -join "`n") + "`n") -Confirm:$false
        Protect-HighTacPath -Path $temporaryPath -Confirm:$false

        $output = & $MosquittoPasswordTool -U $temporaryPath 2>&1
        if ($LASTEXITCODE -ne 0) {
            throw "mosquitto_passwd failed to hash the runtime password file (exit $LASTEXITCODE): $($output -join ' ')"
        }

        if ($null -ne $legacyEntry) {
            $utf8WithoutBom = New-Object Text.UTF8Encoding($false)
            $hashedContent = [IO.File]::ReadAllText($temporaryPath)
            if ($hashedContent.Length -gt 0 -and -not $hashedContent.EndsWith("`n")) {
                [IO.File]::AppendAllText($temporaryPath, "`n", $utf8WithoutBom)
            }
            [IO.File]::AppendAllText(
                $temporaryPath,
                ([string]$legacyEntry.PasswordEntry + "`n"),
                $utf8WithoutBom
            )
        }
        Move-Item -LiteralPath $temporaryPath -Destination $destination -Force
        Protect-HighTacPath -Path $destination -Confirm:$false
    }
    finally {
        if (Test-Path -LiteralPath $temporaryPath) {
            Remove-Item -LiteralPath $temporaryPath -Force
        }
    }
}

function Get-HighTacService {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name
    )

    Assert-HighTacOwnedServiceName -Name $Name
    return Get-Service -Name $Name -ErrorAction SilentlyContinue
}

function Stop-HighTacService {
    [CmdletBinding(SupportsShouldProcess = $true)]
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name,

        [ValidateRange(1, 120)]
        [int]$TimeoutSeconds = 15
    )

    $service = Get-HighTacService -Name $Name
    if ($null -eq $service -or $service.Status -eq [System.ServiceProcess.ServiceControllerStatus]::Stopped) {
        return
    }
    if ($PSCmdlet.ShouldProcess($Name, 'Stop Windows service')) {
        Stop-Service -Name $Name -Force -ErrorAction Stop
        $service.WaitForStatus([System.ServiceProcess.ServiceControllerStatus]::Stopped, [TimeSpan]::FromSeconds($TimeoutSeconds))
    }
}

function Start-HighTacService {
    [CmdletBinding(SupportsShouldProcess = $true)]
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name,

        [ValidateRange(1, 120)]
        [int]$TimeoutSeconds = 15
    )

    $service = Get-HighTacService -Name $Name
    if ($null -eq $service) {
        throw "Windows service is not installed: $Name"
    }
    if ($service.Status -eq [System.ServiceProcess.ServiceControllerStatus]::Running) {
        return
    }
    if ($PSCmdlet.ShouldProcess($Name, 'Start Windows service')) {
        Start-Service -Name $Name -ErrorAction Stop
        $service.WaitForStatus([System.ServiceProcess.ServiceControllerStatus]::Running, [TimeSpan]::FromSeconds($TimeoutSeconds))
    }
}

function Install-HighTacWinSWService {
    [CmdletBinding(SupportsShouldProcess = $true)]
    param(
        [Parameter(Mandatory = $true)]
        [string]$WrapperPath,

        [Parameter(Mandatory = $true)]
        [string]$ServiceName
    )

    Assert-HighTacOwnedServiceName -Name $ServiceName
    if (-not (Test-Path -LiteralPath $WrapperPath -PathType Leaf)) {
        throw "WinSW wrapper was not found for $ServiceName`: $WrapperPath"
    }
    $serviceExists = $null -ne (Get-HighTacService -Name $ServiceName)
    $action = if ($serviceExists) { 'Replace' } else { 'Install' }
    if ($PSCmdlet.ShouldProcess($ServiceName, "$action WinSW service registration")) {
        if ($serviceExists) {
            Uninstall-HighTacWinSWService `
                -WrapperPath $WrapperPath `
                -ServiceName $ServiceName `
                -Confirm:$false
        }
        $output = & $WrapperPath install 2>&1
        if ($LASTEXITCODE -ne 0) {
            throw "WinSW 'install' failed for $ServiceName (exit $LASTEXITCODE): $($output -join ' ')"
        }
    }
}

function Uninstall-HighTacWinSWService {
    [CmdletBinding(SupportsShouldProcess = $true)]
    param(
        [Parameter(Mandatory = $true)]
        [string]$WrapperPath,

        [Parameter(Mandatory = $true)]
        [string]$ServiceName
    )

    Assert-HighTacOwnedServiceName -Name $ServiceName
    if ($null -eq (Get-HighTacService -Name $ServiceName)) {
        return
    }
    Stop-HighTacService -Name $ServiceName -Confirm:$false -WhatIf:$WhatIfPreference

    if ($PSCmdlet.ShouldProcess($ServiceName, 'Uninstall WinSW service')) {
        if (Test-Path -LiteralPath $WrapperPath -PathType Leaf) {
            $output = & $WrapperPath uninstall 2>&1
            if ($LASTEXITCODE -ne 0) {
                throw "WinSW uninstall failed for $ServiceName (exit $LASTEXITCODE): $($output -join ' ')"
            }
        }
        else {
            $output = & "$env:SystemRoot\System32\sc.exe" delete $ServiceName 2>&1
            if ($LASTEXITCODE -ne 0) {
                throw "sc.exe could not delete $ServiceName (exit $LASTEXITCODE): $($output -join ' ')"
            }
        }

        $deadline = [DateTime]::UtcNow.AddSeconds(20)
        while ($null -ne (Get-HighTacService -Name $ServiceName) -and [DateTime]::UtcNow -lt $deadline) {
            Start-Sleep -Milliseconds 250
        }
        if ($null -ne (Get-HighTacService -Name $ServiceName)) {
            throw "Windows service '$ServiceName' is still registered after the uninstall request."
        }
    }
}

function Test-HighTacTcpPort {
    [CmdletBinding()]
    [OutputType([bool])]
    param(
        [Parameter(Mandatory = $true)]
        [string]$HostName,

        [Parameter(Mandatory = $true)]
        [ValidateRange(1, 65535)]
        [int]$Port,

        [ValidateRange(100, 30000)]
        [int]$TimeoutMilliseconds = 3000
    )

    $client = New-Object Net.Sockets.TcpClient
    try {
        $result = $client.BeginConnect($HostName, $Port, $null, $null)
        if (-not $result.AsyncWaitHandle.WaitOne($TimeoutMilliseconds, $false)) {
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

function Test-HighTacLocalTcpListener {
    [CmdletBinding()]
    [OutputType([bool])]
    param(
        [Parameter(Mandatory = $true)]
        [ValidateRange(1, 65535)]
        [int]$Port
    )

    if ($null -ne (Get-Command Get-NetTCPConnection -ErrorAction SilentlyContinue)) {
        $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)
        if ($listeners.Count -gt 0) {
            return $true
        }
    }
    return Test-HighTacTcpPort -HostName '127.0.0.1' -Port $Port -TimeoutMilliseconds 500
}

function Copy-HighTacLegacyRuntimeData {
    [CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'High')]
    param(
        [Parameter(Mandatory = $true)]
        [string]$LegacyRuntimeRoot,

        [Parameter(Mandatory = $true)]
        [string]$DataRoot,

        [switch]$ValidateOnly
    )

    $sourceRoot = Resolve-HighTacFullPath -Path $LegacyRuntimeRoot
    $destinationRoot = Resolve-HighTacFullPath -Path $DataRoot
    if (-not (Test-Path -LiteralPath $sourceRoot -PathType Container)) {
        throw "Legacy server runtime directory was not found: $sourceRoot"
    }
    if ($sourceRoot -eq $destinationRoot -or
        $sourceRoot.StartsWith($destinationRoot + '\', [StringComparison]::OrdinalIgnoreCase) -or
        $destinationRoot.StartsWith($sourceRoot + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Legacy runtime source and managed ProgramData destination must be separate directory trees.'
    }

    $sourceRootItem = Get-Item -LiteralPath $sourceRoot -Force
    if (($sourceRootItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw 'Legacy server runtime directory cannot be a symbolic link or junction.'
    }
    if (Test-Path -LiteralPath $destinationRoot) {
        $destinationRootItem = Get-Item -LiteralPath $destinationRoot -Force
        if (-not $destinationRootItem.PSIsContainer) {
            throw "Managed ProgramData destination is not a directory: $destinationRoot"
        }
        if (($destinationRootItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw 'Managed ProgramData destination cannot be a symbolic link or junction.'
        }
    }

    $directoryPlan = New-Object 'System.Collections.Generic.List[string]'
    $filePlan = New-Object 'System.Collections.Generic.List[object]'
    $sourceFilesForLock = New-Object 'System.Collections.Generic.List[string]'
    $filesDiscovered = 0
    $filesAlreadyPresent = 0
    foreach ($directoryName in @('db', 'backups', 'logs', 'secrets')) {
        $sourceDirectory = Join-Path $sourceRoot $directoryName
        if (-not (Test-Path -LiteralPath $sourceDirectory)) {
            continue
        }
        if (-not (Test-Path -LiteralPath $sourceDirectory -PathType Container)) {
            throw "Legacy runtime entry must be a directory: $sourceDirectory"
        }

        $sourceItems = @(Get-ChildItem -LiteralPath $sourceDirectory -Force -Recurse -ErrorAction Stop)
        foreach ($sourceItem in @((Get-Item -LiteralPath $sourceDirectory -Force)) + $sourceItems) {
            if (($sourceItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw "Legacy runtime data cannot contain symbolic links or junctions: $($sourceItem.FullName)"
            }
        }

        $sourceDirectories = @((Get-Item -LiteralPath $sourceDirectory -Force)) +
            @($sourceItems | Where-Object { $_.PSIsContainer })
        foreach ($sourceDirectoryItem in $sourceDirectories) {
            $relativeDirectory = $sourceDirectoryItem.FullName.Substring($sourceRoot.Length + 1)
            $destinationDirectory = Join-Path $destinationRoot $relativeDirectory
            if (Test-Path -LiteralPath $destinationDirectory) {
                if (-not (Test-Path -LiteralPath $destinationDirectory -PathType Container)) {
                    throw "Legacy runtime directory conflicts with an existing ProgramData file: $relativeDirectory"
                }
                $destinationDirectoryItem = Get-Item -LiteralPath $destinationDirectory -Force
                if (($destinationDirectoryItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                    throw "Managed ProgramData cannot contain a symbolic link or junction in a migration target: $relativeDirectory"
                }
            }
            else {
                $directoryPlan.Add($destinationDirectory)
            }
        }

        foreach ($sourceFile in @($sourceItems | Where-Object { -not $_.PSIsContainer })) {
            $filesDiscovered++
            $sourceFilesForLock.Add($sourceFile.FullName)
            $relativeFile = $sourceFile.FullName.Substring($sourceRoot.Length + 1)
            $destinationFile = Join-Path $destinationRoot $relativeFile
            if (Test-Path -LiteralPath $destinationFile) {
                if (-not (Test-Path -LiteralPath $destinationFile -PathType Leaf)) {
                    throw "Legacy runtime file conflicts with an existing ProgramData directory: $relativeFile"
                }
                $destinationFileItem = Get-Item -LiteralPath $destinationFile -Force
                if (($destinationFileItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                    throw "Managed ProgramData cannot contain a symbolic link in a migration target: $relativeFile"
                }
                if ($sourceFile.Length -ne $destinationFileItem.Length -or
                    (Get-FileHash -LiteralPath $sourceFile.FullName -Algorithm SHA256).Hash -ne
                    (Get-FileHash -LiteralPath $destinationFile -Algorithm SHA256).Hash) {
                    throw "Legacy runtime file conflicts with different ProgramData content: $relativeFile"
                }
                $filesAlreadyPresent++
                continue
            }
            $filePlan.Add([pscustomobject]@{
                Source = $sourceFile.FullName
                Destination = $destinationFile
                LastWriteTimeUtc = $sourceFile.LastWriteTimeUtc
            })
        }
    }

    if (-not $ValidateOnly -and -not $PSCmdlet.ShouldProcess(
            "$sourceRoot -> $destinationRoot",
            'Copy legacy server runtime data without deleting or modifying the source'
        )) {
        return [pscustomobject]@{
            LegacyRuntimeRoot = $sourceRoot
            DataRoot = $destinationRoot
            FilesDiscovered = $filesDiscovered
            FilesCopied = 0
            FilesAlreadyPresent = $filesAlreadyPresent
            ValidatedOnly = $false
        }
    }

    $sourceReadLocks = New-Object 'System.Collections.Generic.List[object]'
    try {
        try {
            foreach ($sourceFilePath in $sourceFilesForLock) {
                $sourceReadLocks.Add([IO.File]::Open(
                    $sourceFilePath,
                    [IO.FileMode]::Open,
                    [IO.FileAccess]::Read,
                    [IO.FileShare]::Read
                ))
            }
        }
        catch {
            throw 'Legacy runtime contains a file that is unreadable or still open for writing. Stop the previous server and retry; no source data was removed.'
        }

        if ($ValidateOnly) {
            return [pscustomobject]@{
                LegacyRuntimeRoot = $sourceRoot
                DataRoot = $destinationRoot
                FilesDiscovered = $filesDiscovered
                FilesCopied = 0
                FilesAlreadyPresent = $filesAlreadyPresent
                ValidatedOnly = $true
            }
        }

        if (-not (Test-Path -LiteralPath $destinationRoot)) {
            New-Item -ItemType Directory -Path $destinationRoot -Force | Out-Null
        }
        foreach ($directory in @($directoryPlan | Sort-Object Length)) {
            if (-not (Test-Path -LiteralPath $directory)) {
                New-Item -ItemType Directory -Path $directory -Force | Out-Null
            }
        }
        $filesCopied = 0
        foreach ($file in $filePlan) {
            [IO.File]::Copy($file.Source, $file.Destination, $false)
            [IO.File]::SetLastWriteTimeUtc($file.Destination, $file.LastWriteTimeUtc)
            $filesCopied++
        }
    }
    finally {
        foreach ($sourceReadLock in $sourceReadLocks) {
            $sourceReadLock.Dispose()
        }
    }

    [pscustomobject]@{
        LegacyRuntimeRoot = $sourceRoot
        DataRoot = $destinationRoot
        FilesDiscovered = $filesDiscovered
        FilesCopied = $filesCopied
        FilesAlreadyPresent = $filesAlreadyPresent
        ValidatedOnly = $false
    }
}

function Get-HighTacInstallState {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string]$DataRoot
    )

    $statePath = Join-Path (Resolve-HighTacFullPath -Path $DataRoot) 'config\install-state.json'
    if (-not (Test-Path -LiteralPath $statePath -PathType Leaf)) {
        return $null
    }
    return Get-Content -LiteralPath $statePath -Raw -Encoding UTF8 | ConvertFrom-Json
}

function Write-HighTacInstallState {
    [CmdletBinding(SupportsShouldProcess = $true)]
    param(
        [Parameter(Mandatory = $true)]
        [string]$DataRoot,

        [Parameter(Mandatory = $true)]
        [System.Collections.IDictionary]$State
    )

    $statePath = Join-Path (Resolve-HighTacFullPath -Path $DataRoot) 'config\install-state.json'
    $content = $State | ConvertTo-Json -Depth 4
    Write-HighTacTextFile -Path $statePath -Content ($content + "`n") -Confirm:$false -WhatIf:$WhatIfPreference
}

Export-ModuleMember -Function @(
    'Assert-HighTacAdministrator',
    'Assert-HighTacSafeRoot',
    'Assert-HighTacWindows',
    'ConvertFrom-HighTacSecureString',
    'ConvertTo-HighTacConfigPath',
    'ConvertTo-HighTacStationId',
    'ConvertTo-HighTacYamlString',
    'Copy-HighTacLegacyRuntimeData',
    'Get-HighTacLegacyMosquittoPasswordEntry',
    'Get-HighTacInstallState',
    'Get-HighTacRandomInteger',
    'Get-HighTacRenderedTemplate',
    'Get-HighTacService',
    'Install-HighTacWinSWService',
    'New-HighTacMosquittoPasswordFile',
    'New-HighTacRandomPassword',
    'Protect-HighTacPath',
    'Resolve-HighTacFullPath',
    'Start-HighTacService',
    'Stop-HighTacService',
    'Test-HighTacAdministrator',
    'Test-HighTacLocalTcpListener',
    'Test-HighTacTcpPort',
    'Uninstall-HighTacWinSWService',
    'Write-HighTacInstallState',
    'Write-HighTacTextFile'
)
