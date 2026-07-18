[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$BaselineInstaller,

    [Parameter(Mandatory = $true)]
    [string]$CandidateInstaller,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9A-Fa-f]{64}$')]
    [string]$ExpectedBaselineSha256,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9A-Fa-f]{64}$')]
    [string]$ExpectedCandidateSha256,

    [Parameter(Mandatory = $true)]
    [string]$OutputRoot
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ($env:USERNAME -cne 'WDAGUtilityAccount' -or
    -not (Test-Path -LiteralPath 'C:\HighTacRehearsal' -PathType Container)) {
    throw 'Refusing to run service-changing rehearsal outside Windows Sandbox.'
}
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = New-Object Security.Principal.WindowsPrincipal($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Windows Sandbox rehearsal requires the built-in elevated sandbox account.'
}

$webPort = 18088
$mqttPort = 1884
$baseUrl = 'http://127.0.0.1:{0}/api/v1' -f $webPort
$installRoot = 'C:\Program Files\HighTac\Platform'
$dataRoot = 'C:\ProgramData\HighTac\Platform'
$databasePath = Join-Path $dataRoot 'db\hightac.db'
$checks = [ordered]@{}
$counts = [ordered]@{}
$hashes = [ordered]@{}
$failure = $null

function Add-RehearsalResult {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Step,

        [Parameter(Mandatory = $true)]
        [bool]$Passed
    )

    $script:checks[$Step] = $Passed
    if (-not $Passed) {
        throw "Rehearsal step failed: $Step"
    }
}

function Start-RehearsalCheckpoint {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Step
    )

    $script:checks[$Step] = $false
}

function Complete-RehearsalCheckpoint {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Step
    )

    if (-not $script:checks.Contains($Step)) {
        throw "Rehearsal checkpoint was not started: $Step"
    }
    $script:checks[$Step] = $true
}

function Set-RehearsalCount {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name,

        [Parameter(Mandatory = $true)]
        [long]$Value
    )

    if ($Value -lt 0) {
        throw "Rehearsal count cannot be negative: $Name"
    }
    $script:counts[$Name] = $Value
}

function Set-RehearsalHash {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name,

        [Parameter(Mandatory = $true)]
        [string]$Value
    )

    $normalized = $Value.Trim().ToLowerInvariant()
    if ($normalized -notmatch '^[0-9a-f]{64}$') {
        throw "Rehearsal hash is not SHA-256: $Name"
    }
    $script:hashes[$Name] = $normalized
}

function New-RehearsalReport {
    param(
        [Parameter(Mandatory = $true)]
        [System.Collections.IDictionary]$Checks,

        [Parameter(Mandatory = $true)]
        [System.Collections.IDictionary]$Counts,

        [Parameter(Mandatory = $true)]
        [System.Collections.IDictionary]$Hashes
    )

    $checksCopy = [ordered]@{}
    $passed = $true
    foreach ($key in $Checks.Keys) {
        $value = $Checks[$key]
        if (-not ($value -is [bool])) {
            throw "Rehearsal report check is not Boolean: $key"
        }
        $checksCopy[[string]$key] = [bool]$value
        if (-not $value) {
            $passed = $false
        }
    }

    $countsCopy = [ordered]@{}
    foreach ($key in $Counts.Keys) {
        $value = $Counts[$key]
        if (-not ($value -is [byte] -or
                $value -is [int16] -or
                $value -is [int32] -or
                $value -is [int64] -or
                $value -is [uint16] -or
                $value -is [uint32])) {
            throw "Rehearsal report count is not an integer: $key"
        }
        if ([long]$value -lt 0) {
            throw "Rehearsal report count is negative: $key"
        }
        $countsCopy[[string]$key] = [long]$value
    }

    $hashesCopy = [ordered]@{}
    foreach ($key in $Hashes.Keys) {
        $value = [string]$Hashes[$key]
        if ($value -notmatch '^[0-9a-f]{64}$') {
            throw "Rehearsal report hash is not lowercase SHA-256: $key"
        }
        $hashesCopy[[string]$key] = $value
    }

    return [ordered]@{
        schema_version = 2
        sandbox_account_verified = $env:USERNAME -ceq 'WDAGUtilityAccount'
        passed = $passed
        secrets_included = $false
        checks = $checksCopy
        counts = $countsCopy
        hashes = $hashesCopy
    }
}

function Get-RehearsalCanonicalHash {
    param(
        [Parameter(Mandatory = $true)]
        [object]$Value
    )

    $json = $Value | ConvertTo-Json -Compress -Depth 12
    $bytes = (New-Object Text.UTF8Encoding($false)).GetBytes($json)
    $sha256 = [Security.Cryptography.SHA256]::Create()
    try {
        $digest = $sha256.ComputeHash($bytes)
    }
    finally {
        $sha256.Dispose()
    }
    return (($digest | ForEach-Object { $_.ToString('x2') }) -join '')
}

function Wait-RehearsalCondition {
    param(
        [Parameter(Mandatory = $true)]
        [scriptblock]$Condition,

        [int]$TimeoutSeconds = 60
    )

    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        if (& $Condition) {
            return $true
        }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    return $false
}

function Test-RehearsalTcpPort {
    param([int]$Port)

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

function Test-RehearsalServiceRunning {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name
    )

    $service = Get-Service -Name $Name -ErrorAction SilentlyContinue
    return $null -ne $service -and $service.Status -eq 'Running'
}

function Test-RehearsalApiReady {
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/health/ready" -TimeoutSec 3
        return $response.StatusCode -eq 200
    }
    catch {
        return $false
    }
}

function Invoke-RehearsalExecutable {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,

        [Parameter(Mandatory = $true)]
        [string[]]$Arguments,

        [Parameter(Mandatory = $true)]
        [string]$Step
    )

    $process = Start-Process -FilePath $Path -ArgumentList $Arguments -Wait -PassThru -WindowStyle Hidden
    if ($process.ExitCode -ge 0) {
        Set-RehearsalCount -Name "process_exit_code_$Step" -Value ([long]$process.ExitCode)
    }
    Add-RehearsalResult -Step $Step -Passed ($process.ExitCode -eq 0)
}

function Invoke-RehearsalApi {
    param(
        [Parameter(Mandatory = $true)]
        [ValidateSet('GET', 'POST', 'PATCH')]
        [string]$Method,

        [Parameter(Mandatory = $true)]
        [string]$Path,

        [Parameter(Mandatory = $true)]
        [Microsoft.PowerShell.Commands.WebRequestSession]$WebSession,

        [hashtable]$Headers = @{},

        [AllowNull()]
        [object]$Body = $null
    )

    $parameters = @{
        UseBasicParsing = $true
        Uri = "$baseUrl$Path"
        Method = $Method
        WebSession = $WebSession
        Headers = $Headers
        TimeoutSec = 30
    }
    if ($null -ne $Body) {
        $parameters.ContentType = 'application/json'
        $parameters.Body = $Body | ConvertTo-Json -Compress -Depth 6
    }
    return Invoke-WebRequest @parameters
}

function Invoke-RehearsalJsonApi {
    param(
        [Parameter(Mandatory = $true)]
        [ValidateSet('GET', 'POST', 'PATCH')]
        [string]$Method,

        [Parameter(Mandatory = $true)]
        [string]$Path,

        [Parameter(Mandatory = $true)]
        [Microsoft.PowerShell.Commands.WebRequestSession]$WebSession,

        [hashtable]$Headers = @{},

        [AllowNull()]
        [object]$Body = $null
    )

    $response = Invoke-RehearsalApi -Method $Method -Path $Path -WebSession $WebSession -Headers $Headers -Body $Body
    if ([string]::IsNullOrWhiteSpace([string]$response.Content)) {
        throw "Rehearsal API returned no JSON for $Method $Path"
    }
    return ($response.Content | ConvertFrom-Json)
}

function Test-RehearsalProcessDescendant {
    param(
        [Parameter(Mandatory = $true)]
        [uint32]$ProcessId,

        [Parameter(Mandatory = $true)]
        [uint32]$AncestorProcessId,

        [Parameter(Mandatory = $true)]
        [System.Collections.IDictionary]$ProcessById
    )

    $visited = New-Object 'System.Collections.Generic.HashSet[uint32]'
    $currentProcessId = $ProcessId
    while ($currentProcessId -ne 0) {
        if ($currentProcessId -eq $AncestorProcessId) {
            return $true
        }
        if (-not $visited.Add($currentProcessId)) {
            return $false
        }
        $key = [string]$currentProcessId
        if (-not $ProcessById.Contains($key)) {
            return $false
        }
        $process = $ProcessById[$key]
        $parentProperty = $process.PSObject.Properties['ParentProcessId']
        if ($null -eq $parentProperty) {
            return $false
        }
        $parentProcessId = [uint32]$parentProperty.Value
        if ($parentProcessId -eq $currentProcessId) {
            return $false
        }
        $currentProcessId = $parentProcessId
    }
    return $false
}

function Select-RehearsalPlatformProcess {
    param(
        [Parameter(Mandatory = $true)]
        [object[]]$Processes,

        [Parameter(Mandatory = $true)]
        [uint32]$ServiceProcessId,

        [Parameter(Mandatory = $true)]
        [string]$ExpectedExecutablePath
    )

    if ($ServiceProcessId -eq 0) {
        return $null
    }
    $expectedPath = [IO.Path]::GetFullPath($ExpectedExecutablePath).TrimEnd('\')
    $processById = @{}
    foreach ($process in $Processes) {
        $processIdProperty = $process.PSObject.Properties['ProcessId']
        if ($null -eq $processIdProperty -or $null -eq $processIdProperty.Value) {
            continue
        }
        $processId = [uint32]$processIdProperty.Value
        if ($processId -ne 0) {
            $processById[[string]$processId] = $process
        }
    }

    $matches = New-Object 'System.Collections.Generic.List[object]'
    foreach ($process in $Processes) {
        $processIdProperty = $process.PSObject.Properties['ProcessId']
        $pathProperty = $process.PSObject.Properties['ExecutablePath']
        if ($null -eq $processIdProperty -or $null -eq $pathProperty -or
            $null -eq $processIdProperty.Value -or
            [string]::IsNullOrWhiteSpace([string]$pathProperty.Value)) {
            continue
        }
        $processId = [uint32]$processIdProperty.Value
        try {
            $processPath = [IO.Path]::GetFullPath([string]$pathProperty.Value).TrimEnd('\')
        }
        catch {
            continue
        }
        if (-not [string]::Equals($processPath, $expectedPath, [StringComparison]::OrdinalIgnoreCase) -or
            -not (Test-RehearsalProcessDescendant `
                -ProcessId $processId `
                -AncestorProcessId $ServiceProcessId `
                -ProcessById $processById)) {
            continue
        }

        $creationProperty = $process.PSObject.Properties['CreationDate']
        $creationDate = if ($null -eq $creationProperty -or $null -eq $creationProperty.Value) {
            ''
        }
        elseif ($creationProperty.Value -is [DateTime]) {
            $creationProperty.Value.ToUniversalTime().ToString('o')
        }
        else {
            [string]$creationProperty.Value
        }
        $parentProperty = $process.PSObject.Properties['ParentProcessId']
        $matches.Add([pscustomobject]@{
            ProcessId = $processId
            ParentProcessId = if ($null -eq $parentProperty) { [uint32]0 } else { [uint32]$parentProperty.Value }
            ServiceProcessId = $ServiceProcessId
            ExecutablePath = $processPath
            CreationDate = $creationDate
        })
    }

    if ($matches.Count -gt 1) {
        throw 'Multiple service-owned processes matched the packaged HighTac backend path.'
    }
    if ($matches.Count -eq 0) {
        return $null
    }
    return $matches[0]
}

function Get-RehearsalPlatformProcess {
    $serviceModels = @(Get-CimInstance Win32_Service -Filter "Name='HighTacPlatform'" -ErrorAction SilentlyContinue)
    if ($serviceModels.Count -eq 0) {
        return $null
    }
    if ($serviceModels.Count -ne 1) {
        throw 'HighTacPlatform service lookup returned an ambiguous result.'
    }
    if ([uint32]$serviceModels[0].ProcessId -eq 0) {
        return $null
    }

    $expectedExecutablePath = Join-Path $installRoot 'server\HighTacPlatform.exe'
    $processes = @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue)
    return Select-RehearsalPlatformProcess `
        -Processes $processes `
        -ServiceProcessId ([uint32]$serviceModels[0].ProcessId) `
        -ExpectedExecutablePath $expectedExecutablePath
}

function Test-RehearsalPlatformProcessRestarted {
    param(
        [Parameter(Mandatory = $true)]
        [object]$Before,

        [Parameter(Mandatory = $true)]
        [AllowNull()]
        [object]$After
    )

    if ($null -eq $After) {
        return $false
    }
    if ([uint32]$After.ProcessId -ne [uint32]$Before.ProcessId) {
        return $true
    }
    return (-not [string]::IsNullOrWhiteSpace([string]$Before.CreationDate) -and
        -not [string]::IsNullOrWhiteSpace([string]$After.CreationDate) -and
        [string]$After.CreationDate -cne [string]$Before.CreationDate)
}

function Get-RehearsalInstallerProductVersion {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    return ([string][Diagnostics.FileVersionInfo]::GetVersionInfo($Path).ProductVersion).Trim()
}

function Get-RehearsalBackupPath {
    param(
        [Parameter(Mandatory = $true)]
        [string]$BackupFileName
    )

    if ([IO.Path]::GetFileName($BackupFileName) -cne $BackupFileName -or
        [IO.Path]::GetExtension($BackupFileName) -cne '.db') {
        throw 'Backup API returned an unsafe backup filename.'
    }
    $backupRoot = [IO.Path]::GetFullPath((Join-Path $dataRoot 'backups')).TrimEnd('\')
    $backupPath = [IO.Path]::GetFullPath((Join-Path $backupRoot $BackupFileName))
    if (-not $backupPath.StartsWith($backupRoot + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Backup API returned a path outside the managed backup directory.'
    }
    return $backupPath
}

function Get-RehearsalFileHashes {
    param(
        [Parameter(Mandatory = $true)]
        [string]$BackupFileName
    )

    $paths = [ordered]@{
        environment = Join-Path $dataRoot 'config\.env'
        platform_config = Join-Path $dataRoot 'config\platform.yaml'
        install_state = Join-Path $dataRoot 'config\install-state.json'
        operator_output = Join-Path $dataRoot 'config\station-mqtt-credentials.txt'
        mqtt_passwordfile = Join-Path $dataRoot 'mqtt\passwordfile'
        mqtt_acl = Join-Path $dataRoot 'mqtt\aclfile'
        mqtt_config = Join-Path $dataRoot 'mqtt\mosquitto.conf'
        database_sentinel = Join-Path $dataRoot 'db\rehearsal-preserve.txt'
        backup_sentinel = Join-Path $dataRoot 'backups\rehearsal-preserve.txt'
        business_backup = Get-RehearsalBackupPath -BackupFileName $BackupFileName
    }
    $fileHashes = [ordered]@{}
    foreach ($name in $paths.Keys) {
        $path = $paths[$name]
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            throw "Preservation probe file is missing: $name"
        }
        $fileHashes[$name] = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    return $fileHashes
}

function Initialize-RehearsalSqliteReader {
    if ($null -ne ('HighTac.Rehearsal.SqliteReadOnly' -as [type])) {
        return
    }

    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
using System.Text;

namespace HighTac.Rehearsal
{
    public static class SqliteReadOnly
    {
        private const int SQLITE_OK = 0;
        private const int SQLITE_ROW = 100;
        private const int SQLITE_DONE = 101;
        private const int SQLITE_OPEN_READONLY = 0x00000001;

        [DllImport("winsqlite3.dll", CallingConvention = CallingConvention.Cdecl)]
        private static extern int sqlite3_open_v2(
            [MarshalAs(UnmanagedType.LPStr)] string filename,
            out IntPtr database,
            int flags,
            IntPtr vfs);

        [DllImport("winsqlite3.dll", CallingConvention = CallingConvention.Cdecl)]
        private static extern int sqlite3_close(IntPtr database);

        [DllImport("winsqlite3.dll", CallingConvention = CallingConvention.Cdecl)]
        private static extern int sqlite3_busy_timeout(IntPtr database, int milliseconds);

        [DllImport("winsqlite3.dll", CallingConvention = CallingConvention.Cdecl)]
        private static extern int sqlite3_prepare_v2(
            IntPtr database,
            [MarshalAs(UnmanagedType.LPStr)] string sql,
            int byteCount,
            out IntPtr statement,
            IntPtr tail);

        [DllImport("winsqlite3.dll", CallingConvention = CallingConvention.Cdecl)]
        private static extern int sqlite3_step(IntPtr statement);

        [DllImport("winsqlite3.dll", CallingConvention = CallingConvention.Cdecl)]
        private static extern int sqlite3_finalize(IntPtr statement);

        [DllImport("winsqlite3.dll", CallingConvention = CallingConvention.Cdecl)]
        private static extern IntPtr sqlite3_column_text(IntPtr statement, int column);

        [DllImport("winsqlite3.dll", CallingConvention = CallingConvention.Cdecl)]
        private static extern IntPtr sqlite3_errmsg(IntPtr database);

        public static string ExecuteScalar(string path, string sql)
        {
            IntPtr database = IntPtr.Zero;
            int result = sqlite3_open_v2(path, out database, SQLITE_OPEN_READONLY, IntPtr.Zero);
            if (result != SQLITE_OK)
            {
                string message = database == IntPtr.Zero ? "open failed" : Utf8(sqlite3_errmsg(database));
                if (database != IntPtr.Zero)
                {
                    sqlite3_close(database);
                }
                throw new InvalidOperationException("SQLite read-only open failed: " + message);
            }

            try
            {
                sqlite3_busy_timeout(database, 30000);
                IntPtr statement = IntPtr.Zero;
                result = sqlite3_prepare_v2(database, sql, -1, out statement, IntPtr.Zero);
                if (result != SQLITE_OK)
                {
                    throw new InvalidOperationException("SQLite prepare failed: " + Utf8(sqlite3_errmsg(database)));
                }

                try
                {
                    result = sqlite3_step(statement);
                    if (result == SQLITE_DONE)
                    {
                        return null;
                    }
                    if (result != SQLITE_ROW)
                    {
                        throw new InvalidOperationException("SQLite query failed: " + Utf8(sqlite3_errmsg(database)));
                    }
                    return Utf8(sqlite3_column_text(statement, 0));
                }
                finally
                {
                    sqlite3_finalize(statement);
                }
            }
            finally
            {
                sqlite3_close(database);
            }
        }

        private static string Utf8(IntPtr value)
        {
            if (value == IntPtr.Zero)
            {
                return null;
            }
            int length = 0;
            while (Marshal.ReadByte(value, length) != 0)
            {
                length++;
            }
            byte[] bytes = new byte[length];
            Marshal.Copy(value, bytes, 0, length);
            return Encoding.UTF8.GetString(bytes);
        }
    }
}
'@
}

function Test-RehearsalSqliteIntegrity {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return $false
    }
    try {
        Initialize-RehearsalSqliteReader
        $integrity = [HighTac.Rehearsal.SqliteReadOnly]::ExecuteScalar($Path, 'PRAGMA integrity_check;')
        $foreignKeyViolation = [HighTac.Rehearsal.SqliteReadOnly]::ExecuteScalar($Path, 'PRAGMA foreign_key_check;')
        return $integrity -ceq 'ok' -and $null -eq $foreignKeyViolation
    }
    catch {
        return $false
    }
}

function New-RehearsalMutationHeaders {
    param(
        [Parameter(Mandatory = $true)]
        [string]$CsrfToken
    )

    return @{
        'X-CSRF-Token' = $CsrfToken
        'Idempotency-Key' = [Guid]::NewGuid().ToString()
    }
}

function Connect-RehearsalAdmin {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Username,

        [Parameter(Mandatory = $true)]
        [string]$Password
    )

    $webSession = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    $login = Invoke-RehearsalApi -Method POST -Path '/auth/login' -WebSession $webSession -Body @{
        username = $Username
        password = $Password
    }
    $me = Invoke-RehearsalApi -Method GET -Path '/auth/me' -WebSession $webSession
    $csrf = [string]$me.Headers['X-CSRF-Token']
    if ([string]::IsNullOrWhiteSpace($csrf)) {
        $csrf = [string]$login.Headers['X-CSRF-Token']
    }
    if ([string]::IsNullOrWhiteSpace($csrf)) {
        throw 'Authenticated rehearsal session did not receive a CSRF token.'
    }
    return [pscustomobject]@{
        WebSession = $webSession
        CsrfToken = $csrf
        Me = $me.Content | ConvertFrom-Json
    }
}

function New-RehearsalBusinessFixture {
    $nonce = [Guid]::NewGuid().ToString('N').ToUpperInvariant()
    $marker = $nonce.Substring(0, 12)
    return [pscustomobject]@{
        SiteName = "HighTac Sandbox $marker"
        SiteAddress = "Sandbox address $marker"
        SiteNotes = "Upgrade rehearsal $marker"
        StationId = '90A9F' + $nonce.Substring(0, 7)
        StationAlias = "Sandbox station $marker"
        TagId = 'AD1' + $nonce.Substring(7, 9)
        ProductCode = "REHEARSAL-$marker"
        ProductName = "Sandbox product $marker"
    }
}

function New-RehearsalBusinessData {
    param(
        [Parameter(Mandatory = $true)]
        [pscustomobject]$Connection,

        [Parameter(Mandatory = $true)]
        [pscustomobject]$Fixture
    )

    $siteBefore = Invoke-RehearsalJsonApi -Method GET -Path '/settings/site' -WebSession $Connection.WebSession
    $site = Invoke-RehearsalJsonApi -Method PATCH -Path '/settings/site' -WebSession $Connection.WebSession -Headers @{
        'X-CSRF-Token' = $Connection.CsrfToken
    } -Body @{
        name = $Fixture.SiteName
        address = $Fixture.SiteAddress
        notes = $Fixture.SiteNotes
        low_battery_threshold = 30
    }
    $station = Invoke-RehearsalJsonApi -Method POST -Path '/stations' -WebSession $Connection.WebSession -Headers (
        New-RehearsalMutationHeaders -CsrfToken $Connection.CsrfToken
    ) -Body @{
        station_id = $Fixture.StationId
        site_id = $siteBefore.id
        alias = $Fixture.StationAlias
    }
    $tag = Invoke-RehearsalJsonApi -Method POST -Path '/tags/register' -WebSession $Connection.WebSession -Headers (
        New-RehearsalMutationHeaders -CsrfToken $Connection.CsrfToken
    ) -Body @{
        tag_id = $Fixture.TagId
        station_id = $Fixture.StationId
    }
    $product = Invoke-RehearsalJsonApi -Method POST -Path '/products' -WebSession $Connection.WebSession -Headers (
        New-RehearsalMutationHeaders -CsrfToken $Connection.CsrfToken
    ) -Body @{
        product_code = $Fixture.ProductCode
        product_name = $Fixture.ProductName
    }
    $binding = Invoke-RehearsalJsonApi -Method POST -Path '/bindings' -WebSession $Connection.WebSession -Headers (
        New-RehearsalMutationHeaders -CsrfToken $Connection.CsrfToken
    ) -Body @{
        product_code = $Fixture.ProductCode
        product_name = $Fixture.ProductName
        tag_id = $Fixture.TagId
        station_id = $Fixture.StationId
    }

    $seedMatches = (
        $site.id -ceq $siteBefore.id -and
        $site.name -ceq $Fixture.SiteName -and
        $site.address -ceq $Fixture.SiteAddress -and
        $site.notes -ceq $Fixture.SiteNotes -and
        [int]$site.low_battery_threshold -eq 30 -and
        $station.station_id -ceq $Fixture.StationId -and
        $station.site_id -ceq $site.id -and
        $station.alias -ceq $Fixture.StationAlias -and
        $tag.tag_id -ceq $Fixture.TagId -and
        $tag.station_id -ceq $Fixture.StationId -and
        $product.product_code -ceq $Fixture.ProductCode -and
        $product.product_name -ceq $Fixture.ProductName -and
        $binding.product_id -ceq $product.id -and
        $binding.tag_id -ceq $Fixture.TagId -and
        $binding.station_id -ceq $Fixture.StationId -and
        $binding.is_active -eq $true
    )
    if (-not $seedMatches) {
        throw 'Public API business fixture did not match the requested records.'
    }

    return [pscustomobject]@{
        SiteId = [string]$site.id
        SiteName = [string]$site.name
        SiteAddress = [string]$site.address
        SiteNotes = [string]$site.notes
        StationId = [string]$station.station_id
        StationAlias = [string]$station.alias
        TagId = [string]$tag.tag_id
        ProductId = [string]$product.id
        ProductCode = [string]$product.product_code
        ProductName = [string]$product.product_name
        BindingId = [string]$binding.id
    }
}

function Get-RehearsalBusinessState {
    param(
        [Parameter(Mandatory = $true)]
        [Microsoft.PowerShell.Commands.WebRequestSession]$WebSession,

        [Parameter(Mandatory = $true)]
        [pscustomobject]$Expected,

        [Parameter(Mandatory = $true)]
        [string]$BackupId
    )

    Start-RehearsalCheckpoint -Step 'business_state_inventory_reads'
    $site = Invoke-RehearsalJsonApi -Method GET -Path '/settings/site' -WebSession $WebSession
    $station = Invoke-RehearsalJsonApi -Method GET -Path (
        '/stations/{0}' -f [Uri]::EscapeDataString($Expected.StationId)
    ) -WebSession $WebSession
    $tagDetail = Invoke-RehearsalJsonApi -Method GET -Path (
        '/tags/{0}' -f [Uri]::EscapeDataString($Expected.TagId)
    ) -WebSession $WebSession
    $tag = $tagDetail.tag
    $productDetail = Invoke-RehearsalJsonApi -Method GET -Path (
        '/products/{0}' -f [Uri]::EscapeDataString($Expected.ProductId)
    ) -WebSession $WebSession
    $bindingPage = Invoke-RehearsalJsonApi -Method GET -Path (
        '/bindings?product_id={0}&tag_id={1}&is_active=true&page_size=100' -f
            [Uri]::EscapeDataString($Expected.ProductId),
            [Uri]::EscapeDataString($Expected.TagId)
    ) -WebSession $WebSession
    $bindings = @($bindingPage.items | Where-Object { $_.id -ceq $Expected.BindingId })
    if ($bindings.Count -ne 1) {
        throw 'Expected active business binding was not returned by the API.'
    }
    $binding = $bindings[0]

    $backupPage = Invoke-RehearsalJsonApi -Method GET -Path '/backups?page_size=100' -WebSession $WebSession
    $backups = @($backupPage.items | Where-Object { $_.id -ceq $BackupId })
    if ($backups.Count -ne 1) {
        throw 'Expected business backup record was not returned by the API.'
    }
    $backup = $backups[0]
    Complete-RehearsalCheckpoint -Step 'business_state_inventory_reads'

    Start-RehearsalCheckpoint -Step 'business_state_record_match'
    $stateMatches = (
        $site.id -ceq $Expected.SiteId -and
        $site.name -ceq $Expected.SiteName -and
        $site.address -ceq $Expected.SiteAddress -and
        $site.notes -ceq $Expected.SiteNotes -and
        [int]$site.low_battery_threshold -eq 30 -and
        $station.station_id -ceq $Expected.StationId -and
        $station.site_id -ceq $Expected.SiteId -and
        $station.alias -ceq $Expected.StationAlias -and
        $tag.tag_id -ceq $Expected.TagId -and
        $tag.site_id -ceq $Expected.SiteId -and
        $tag.station_id -ceq $Expected.StationId -and
        $tag.active_binding_id -ceq $Expected.BindingId -and
        $tagDetail.active_binding.id -ceq $Expected.BindingId -and
        $productDetail.product.id -ceq $Expected.ProductId -and
        $productDetail.product.product_code -ceq $Expected.ProductCode -and
        $productDetail.product.product_name -ceq $Expected.ProductName -and
        $productDetail.product.is_active -eq $true -and
        [int]$productDetail.product.active_binding_count -eq 1 -and
        $binding.id -ceq $Expected.BindingId -and
        $binding.product_id -ceq $Expected.ProductId -and
        $binding.tag_id -ceq $Expected.TagId -and
        $binding.site_id -ceq $Expected.SiteId -and
        $binding.station_id -ceq $Expected.StationId -and
        $binding.is_active -eq $true -and
        $backup.status -ceq 'SUCCEEDED' -and
        ([string]$backup.sha256).ToLowerInvariant() -match '^[0-9a-f]{64}$'
    )
    if (-not $stateMatches) {
        throw 'Public API business state no longer matches the seeded records.'
    }
    Complete-RehearsalCheckpoint -Step 'business_state_record_match'

    Start-RehearsalCheckpoint -Step 'business_state_audit_match'
    $auditSpecs = @(
        [pscustomobject]@{ EventType = 'settings.site_updated'; EntityType = 'site'; EntityId = $Expected.SiteId },
        [pscustomobject]@{ EventType = 'station.created'; EntityType = 'station'; EntityId = $Expected.StationId },
        [pscustomobject]@{ EventType = 'tag.registered'; EntityType = 'tag'; EntityId = $Expected.TagId },
        [pscustomobject]@{ EventType = 'product.created'; EntityType = 'product'; EntityId = $Expected.ProductId },
        [pscustomobject]@{ EventType = 'binding.created'; EntityType = 'product'; EntityId = $Expected.ProductId }
    )
    $audits = @(
        foreach ($spec in $auditSpecs) {
            $auditCheckpoint = 'business_state_audit_' + $spec.EventType.Replace('.', '_')
            Start-RehearsalCheckpoint -Step $auditCheckpoint
            $path = '/operation-logs?event_type={0}&entity_type={1}&entity_id={2}&page_size=100' -f
                [Uri]::EscapeDataString($spec.EventType),
                [Uri]::EscapeDataString($spec.EntityType),
                [Uri]::EscapeDataString($spec.EntityId)
            $page = Invoke-RehearsalJsonApi -Method GET -Path $path -WebSession $WebSession
            $records = @($page.items)
            if ($records.Count -lt 1) {
                throw "Expected audit record is missing: $($spec.EventType)"
            }
            Complete-RehearsalCheckpoint -Step $auditCheckpoint
            foreach ($record in $records) {
                [pscustomobject][ordered]@{
                    id = [string]$record.id
                    event_type = [string]$record.event_type
                    site_id = [string]$record.site_id
                    station_id = [string]$record.station_id
                    tag_id = [string]$record.tag_id
                    product_id = [string]$record.product_id
                    actor_type = [string]$record.actor_type
                }
            }
        }
    )
    $audits = @($audits | Sort-Object event_type, id)
    Complete-RehearsalCheckpoint -Step 'business_state_audit_match'

    Start-RehearsalCheckpoint -Step 'business_state_projection'
    $projection = [ordered]@{
        site = [ordered]@{
            id = [string]$site.id
            name = [string]$site.name
            address = [string]$site.address
            notes = [string]$site.notes
            low_battery_threshold = [int]$site.low_battery_threshold
        }
        station = [ordered]@{
            station_id = [string]$station.station_id
            site_id = [string]$station.site_id
            alias = [string]$station.alias
        }
        tag = [ordered]@{
            tag_id = [string]$tag.tag_id
            site_id = [string]$tag.site_id
            station_id = [string]$tag.station_id
            active_binding_id = [string]$tag.active_binding_id
        }
        product = [ordered]@{
            id = [string]$productDetail.product.id
            product_code = [string]$productDetail.product.product_code
            product_name = [string]$productDetail.product.product_name
            source = [string]$productDetail.product.source
            is_active = [bool]$productDetail.product.is_active
            active_binding_count = [int]$productDetail.product.active_binding_count
        }
        binding = [ordered]@{
            id = [string]$binding.id
            product_id = [string]$binding.product_id
            product_code = [string]$binding.product_code
            product_name = [string]$binding.product_name
            tag_id = [string]$binding.tag_id
            site_id = [string]$binding.site_id
            station_id = [string]$binding.station_id
            source = [string]$binding.source
            is_active = [bool]$binding.is_active
        }
        audit = @($audits)
        backup = [ordered]@{
            id = [string]$backup.id
            reason = [string]$backup.reason
            status = [string]$backup.status
            size_bytes = [long]$backup.size_bytes
            sha256 = ([string]$backup.sha256).ToLowerInvariant()
            schema_version = [string]$backup.schema_version
        }
    }
    $recordCounts = [ordered]@{
        sites = 1
        stations = 1
        tags = 1
        products = 1
        bindings = 1
        audits = [long]$audits.Count
        backups = 1
        total = [long](6 + $audits.Count)
    }
    $state = [pscustomobject]@{
        Fingerprint = Get-RehearsalCanonicalHash -Value $projection
        Counts = [pscustomobject]$recordCounts
    }
    Complete-RehearsalCheckpoint -Step 'business_state_projection'
    return $state
}

function Test-RehearsalUninstallState {
    param(
        [Parameter(Mandatory = $true)]
        [string]$InstallRoot,

        [Parameter(Mandatory = $true)]
        [string]$DataRoot,

        [Parameter(Mandatory = $true)]
        [bool]$DataMustRemain
    )

    $installRemoved = -not (Test-Path -LiteralPath $InstallRoot)
    $dataStateCorrect = if ($DataMustRemain) {
        Test-Path -LiteralPath $DataRoot -PathType Container
    }
    else {
        -not (Test-Path -LiteralPath $DataRoot)
    }
    return $installRemoved -and $dataStateCorrect
}

try {
    foreach ($path in @($BaselineInstaller, $CandidateInstaller)) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            throw "Mapped installer is missing: $path"
        }
    }
    $baselineInstallerHash = (Get-FileHash -LiteralPath $BaselineInstaller -Algorithm SHA256).Hash.ToLowerInvariant()
    $candidateInstallerHash = (Get-FileHash -LiteralPath $CandidateInstaller -Algorithm SHA256).Hash.ToLowerInvariant()
    Set-RehearsalHash -Name 'baseline_installer_sha256' -Value (
        $baselineInstallerHash
    )
    Set-RehearsalHash -Name 'candidate_installer_sha256' -Value (
        $candidateInstallerHash
    )
    Add-RehearsalResult -Step 'baseline_installer_hash_verified' -Passed (
        $baselineInstallerHash -ceq $ExpectedBaselineSha256.ToLowerInvariant()
    )
    Add-RehearsalResult -Step 'candidate_installer_hash_verified' -Passed (
        $candidateInstallerHash -ceq $ExpectedCandidateSha256.ToLowerInvariant()
    )
    Add-RehearsalResult -Step 'installer_artifacts_distinct' -Passed (
        -not [string]::Equals(
            [IO.Path]::GetFullPath($BaselineInstaller),
            [IO.Path]::GetFullPath($CandidateInstaller),
            [StringComparison]::OrdinalIgnoreCase
        ) -and $baselineInstallerHash -cne $candidateInstallerHash
    )
    Add-RehearsalResult -Step 'baseline_installer_version_1_0_0' -Passed (
        (Get-RehearsalInstallerProductVersion -Path $BaselineInstaller) -ceq '1.0.0'
    )
    Add-RehearsalResult -Step 'candidate_installer_version_2_0_0' -Passed (
        (Get-RehearsalInstallerProductVersion -Path $CandidateInstaller) -ceq '2.0.0'
    )

    $freshArguments = @(
        '/VERYSILENT',
        '/SUPPRESSMSGBOXES',
        '/NORESTART',
        '/REHEARSAL=1',
        '/SITENAME=HighTacSandbox',
        '/STATIONID=90A9F0000000',
        "/WEBPORT=$webPort"
    )
    Invoke-RehearsalExecutable -Path $BaselineInstaller -Arguments $freshArguments -Step 'fresh_install'
    $freshServicesReady = Wait-RehearsalCondition -Condition {
        (Test-RehearsalServiceRunning -Name 'HighTacMqttBroker') -and
        (Test-RehearsalServiceRunning -Name 'HighTacPlatform') -and
        (Test-RehearsalTcpPort -Port $mqttPort) -and
        (Test-RehearsalApiReady)
    } -TimeoutSeconds 90
    $checks['fresh_broker_running'] = [bool](Test-RehearsalServiceRunning -Name 'HighTacMqttBroker')
    $checks['fresh_platform_running'] = [bool](Test-RehearsalServiceRunning -Name 'HighTacPlatform')
    $checks['fresh_mqtt_listening'] = [bool](Test-RehearsalTcpPort -Port $mqttPort)
    $checks['fresh_api_ready'] = [bool](Test-RehearsalApiReady)
    $checks['fresh_install_root_present'] = [bool](Test-Path -LiteralPath $installRoot -PathType Container)
    $checks['fresh_data_root_present'] = [bool](Test-Path -LiteralPath $dataRoot -PathType Container)
    $checks['fresh_install_state_present'] = [bool](Test-Path -LiteralPath (
        Join-Path $dataRoot 'config\install-state.json'
    ) -PathType Leaf)
    $checks['fresh_broker_service_installed'] = [bool]($null -ne (
        Get-Service -Name 'HighTacMqttBroker' -ErrorAction SilentlyContinue
    ))
    $checks['fresh_platform_service_installed'] = [bool]($null -ne (
        Get-Service -Name 'HighTacPlatform' -ErrorAction SilentlyContinue
    ))
    Add-RehearsalResult -Step 'fresh_services_ready' -Passed $freshServicesReady

    $serviceModels = @(Get-CimInstance Win32_Service -Filter "Name='HighTacMqttBroker' OR Name='HighTacPlatform'")
    Add-RehearsalResult -Step 'automatic_start' -Passed (
        $serviceModels.Count -eq 2 -and @($serviceModels | Where-Object { $_.StartMode -ne 'Auto' }).Count -eq 0
    )
    $brokerDependencies = @((Get-ItemProperty -LiteralPath 'HKLM:\SYSTEM\CurrentControlSet\Services\HighTacMqttBroker').DependOnService)
    $platformDependencies = @((Get-ItemProperty -LiteralPath 'HKLM:\SYSTEM\CurrentControlSet\Services\HighTacPlatform').DependOnService)
    Add-RehearsalResult -Step 'tcp_dependencies' -Passed (
        $brokerDependencies -contains 'Tcpip' -and $platformDependencies -contains 'Tcpip'
    )
    Add-RehearsalResult -Step 'web_broker_control_compatible' -Passed (
        $platformDependencies -notcontains 'HighTacMqttBroker'
    )
    foreach ($serviceName in @('HighTacMqttBroker', 'HighTacPlatform')) {
        $failureText = (& "$env:SystemRoot\System32\sc.exe" qfailure $serviceName 2>&1) -join [Environment]::NewLine
        Add-RehearsalResult -Step "recovery_$serviceName" -Passed (
            ([regex]::Matches($failureText, 'RESTART')).Count -ge 3
        )
    }

    $operatorFile = Join-Path $dataRoot 'config\station-mqtt-credentials.txt'
    $operatorText = [IO.File]::ReadAllText($operatorFile)
    $operatorSettings = @{}
    foreach ($operatorLine in [IO.File]::ReadAllLines($operatorFile)) {
        $trimmedOperatorLine = $operatorLine.Trim()
        if (-not $trimmedOperatorLine -or $trimmedOperatorLine.StartsWith('#')) {
            continue
        }
        $separatorIndex = $trimmedOperatorLine.IndexOf('=')
        if ($separatorIndex -le 0) {
            throw 'Protected operator output contains an invalid setting line.'
        }
        $operatorName = $trimmedOperatorLine.Substring(0, $separatorIndex).Trim()
        if ($operatorSettings.ContainsKey($operatorName)) {
            throw "Protected operator output contains a duplicate setting: $operatorName"
        }
        $operatorSettings[$operatorName] = $trimmedOperatorLine.Substring($separatorIndex + 1)
    }
    $initialAdminUsername = [string]$operatorSettings['initial_admin_username']
    $initialAdminPassword = [string]$operatorSettings['initial_admin_password']
    Add-RehearsalResult -Step 'operator_output_scope' -Passed (
        $operatorText -match '(?m)^app_server_url=' -and
        $initialAdminUsername -ceq 'Adam' -and
        $initialAdminPassword.Length -eq 32 -and
        $initialAdminPassword -cmatch '[A-Z]' -and
        $initialAdminPassword -cmatch '[a-z]' -and
        $initialAdminPassword -match '[0-9]' -and
        $initialAdminPassword -match '[!#$%&*+\-=?@^_]' -and
        $initialAdminPassword -cne $initialAdminUsername -and
        $operatorText -match '(?m)^station_password=' -and
        $operatorText -notmatch 'HIGHTAC_MQTT_PASSWORD|backend_password|hightac_backend'
    )

    $passwordEntries = @([IO.File]::ReadAllLines((Join-Path $dataRoot 'mqtt\passwordfile')) | Where-Object { $_ })
    $mqttUsers = @($passwordEntries | ForEach-Object { $_.Split(':', 2)[0] })
    Add-RehearsalResult -Step 'separate_mqtt_accounts' -Passed (
        $mqttUsers.Count -eq 2 -and $mqttUsers -contains 'hightac_backend' -and
        $mqttUsers -contains 'estation_90A9F0000000'
    )
    $aclText = [IO.File]::ReadAllText((Join-Path $dataRoot 'mqtt\aclfile'))
    Add-RehearsalResult -Step 'least_privilege_acl' -Passed (
        $aclText -notmatch 'hightac/local/#' -and
        $aclText -match '/estation/90A9F0000000/task' -and
        $aclText -match '/estation/90A9F0000000/heartbeat'
    )

    $adminConnection = Connect-RehearsalAdmin `
        -Username $initialAdminUsername `
        -Password $initialAdminPassword
    Add-RehearsalResult -Step 'admin_must_change_password' -Passed (
        $adminConnection.Me.must_change_password -eq $true
    )
    $rehearsalAdminPassword = 'Sandbox-' + [Guid]::NewGuid().ToString('N') + '-A1!'
    [void](Invoke-RehearsalApi -Method POST -Path '/auth/change-password' -WebSession $adminConnection.WebSession -Headers @{
        'X-CSRF-Token' = $adminConnection.CsrfToken
    } -Body @{
        current_password = $initialAdminPassword
        new_password = $rehearsalAdminPassword
    })
    $meAfterPasswordChangeResponse = Invoke-RehearsalApi -Method GET -Path '/auth/me' -WebSession $adminConnection.WebSession
    $adminConnection.CsrfToken = [string]$meAfterPasswordChangeResponse.Headers['X-CSRF-Token']
    if ([string]::IsNullOrWhiteSpace($adminConnection.CsrfToken)) {
        throw 'Password-change verification did not receive a replacement CSRF token.'
    }
    $meAfterPasswordChange = $meAfterPasswordChangeResponse.Content | ConvertFrom-Json
    Add-RehearsalResult -Step 'admin_bootstrap' -Passed (
        $meAfterPasswordChange.must_change_password -eq $false
    )

    $fixture = New-RehearsalBusinessFixture
    $expectedBusinessData = New-RehearsalBusinessData -Connection $adminConnection -Fixture $fixture
    Add-RehearsalResult -Step 'business_data_seeded' -Passed $true

    $mutationHeaders = New-RehearsalMutationHeaders -CsrfToken $adminConnection.CsrfToken
    [void](Invoke-RehearsalApi -Method POST -Path '/broker/stop' -WebSession $adminConnection.WebSession -Headers $mutationHeaders -Body @{
        confirmation = 'STOP BROKER'
    })
    Add-RehearsalResult -Step 'web_broker_stop' -Passed (Wait-RehearsalCondition -Condition {
        -not (Test-RehearsalTcpPort -Port $mqttPort)
    } -TimeoutSeconds 30)
    $mutationHeaders = New-RehearsalMutationHeaders -CsrfToken $adminConnection.CsrfToken
    [void](Invoke-RehearsalApi -Method POST -Path '/broker/start' -WebSession $adminConnection.WebSession -Headers $mutationHeaders)
    Add-RehearsalResult -Step 'web_broker_start' -Passed (Wait-RehearsalCondition -Condition {
        (Test-RehearsalTcpPort -Port $mqttPort) -and (Test-RehearsalApiReady)
    } -TimeoutSeconds 45)

    $mutationHeaders = New-RehearsalMutationHeaders -CsrfToken $adminConnection.CsrfToken
    $businessBackup = Invoke-RehearsalJsonApi -Method POST -Path '/backups' -WebSession $adminConnection.WebSession -Headers $mutationHeaders
    $businessBackupHash = ([string]$businessBackup.sha256).ToLowerInvariant()
    $businessBackupPath = Get-RehearsalBackupPath -BackupFileName ([string]$businessBackup.filename)
    Add-RehearsalResult -Step 'business_backup_created' -Passed (
        $businessBackup.status -ceq 'SUCCEEDED' -and
        $businessBackupHash -match '^[0-9a-f]{64}$' -and
        (Test-Path -LiteralPath $businessBackupPath -PathType Leaf) -and
        (Get-FileHash -LiteralPath $businessBackupPath -Algorithm SHA256).Hash.ToLowerInvariant() -ceq $businessBackupHash
    )
    Set-RehearsalHash -Name 'baseline_business_backup_sha256' -Value $businessBackupHash

    $baselineState = Get-RehearsalBusinessState -WebSession $adminConnection.WebSession -Expected $expectedBusinessData -BackupId $businessBackup.id
    Add-RehearsalResult -Step 'business_records_verified_before_upgrade' -Passed (
        $baselineState.Counts.sites -eq 1 -and
        $baselineState.Counts.stations -eq 1 -and
        $baselineState.Counts.tags -eq 1 -and
        $baselineState.Counts.products -eq 1 -and
        $baselineState.Counts.bindings -eq 1 -and
        $baselineState.Counts.audits -ge 5 -and
        $baselineState.Counts.backups -eq 1
    )
    Set-RehearsalCount -Name 'business_sites_seeded' -Value $baselineState.Counts.sites
    Set-RehearsalCount -Name 'business_stations_seeded' -Value $baselineState.Counts.stations
    Set-RehearsalCount -Name 'business_tags_seeded' -Value $baselineState.Counts.tags
    Set-RehearsalCount -Name 'business_products_seeded' -Value $baselineState.Counts.products
    Set-RehearsalCount -Name 'business_bindings_seeded' -Value $baselineState.Counts.bindings
    Set-RehearsalCount -Name 'business_audits_seeded' -Value $baselineState.Counts.audits
    Set-RehearsalCount -Name 'business_backups_seeded' -Value $baselineState.Counts.backups
    Set-RehearsalHash -Name 'business_state_before_upgrade_sha256' -Value $baselineState.Fingerprint

    $platformProcessBeforeRestore = Get-RehearsalPlatformProcess
    Add-RehearsalResult -Step 'restore_backend_identity_scoped' -Passed (
        $null -ne $platformProcessBeforeRestore
    )
    $mutationHeaders = New-RehearsalMutationHeaders -CsrfToken $adminConnection.CsrfToken
    try {
        [void](Invoke-RehearsalApi -Method POST -Path (
            '/backups/{0}/restore' -f [Uri]::EscapeDataString([string]$businessBackup.id)
        ) -WebSession $adminConnection.WebSession -Headers $mutationHeaders -Body @{
            confirmation = 'RESTORE BACKUP'
            expected_sha256 = $businessBackup.sha256
        })
    }
    catch {
        # The service is expected to terminate immediately after returning the restore response.
    }
    Add-RehearsalResult -Step 'restore_real_service_restart' -Passed (Wait-RehearsalCondition -Condition {
        $platformProcessAfterRestore = Get-RehearsalPlatformProcess
        (Test-RehearsalPlatformProcessRestarted `
            -Before $platformProcessBeforeRestore `
            -After $platformProcessAfterRestore) -and
            (Test-RehearsalApiReady)
    } -TimeoutSeconds 90)
    $adminAfterRestore = Connect-RehearsalAdmin -Username $initialAdminUsername -Password $rehearsalAdminPassword
    $afterRestoreState = Get-RehearsalBusinessState -WebSession $adminAfterRestore.WebSession -Expected $expectedBusinessData -BackupId $businessBackup.id
    Add-RehearsalResult -Step 'restore_preserves_business_data' -Passed (
        $afterRestoreState.Fingerprint -ceq $baselineState.Fingerprint
    )
    Set-RehearsalHash -Name 'business_state_after_restore_sha256' -Value $afterRestoreState.Fingerprint

    Stop-Service -Name HighTacPlatform -Force
    Stop-Service -Name HighTacMqttBroker -Force
    Start-Service -Name HighTacPlatform
    Add-RehearsalResult -Step 'reboot_start_order_simulation' -Passed (Wait-RehearsalCondition -Condition {
        (Get-Service HighTacMqttBroker).Status -eq 'Running' -and
        (Get-Service HighTacPlatform).Status -eq 'Running' -and
        (Test-RehearsalApiReady)
    } -TimeoutSeconds 90)

    [IO.File]::WriteAllText(
        (Join-Path $dataRoot 'db\rehearsal-preserve.txt'),
        'db sentinel',
        (New-Object Text.UTF8Encoding($false))
    )
    [IO.File]::WriteAllText(
        (Join-Path $dataRoot 'backups\rehearsal-preserve.txt'),
        'backup sentinel',
        (New-Object Text.UTF8Encoding($false))
    )
    $beforeUpgrade = Get-RehearsalFileHashes -BackupFileName $businessBackup.filename
    Set-RehearsalCount -Name 'programdata_manifest_files' -Value $beforeUpgrade.Count
    Set-RehearsalHash -Name 'programdata_manifest_before_upgrade_sha256' -Value (
        Get-RehearsalCanonicalHash -Value $beforeUpgrade
    )

    Invoke-RehearsalExecutable -Path $CandidateInstaller -Arguments @(
        '/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART', '/REHEARSAL=1'
    ) -Step 'upgrade_install'
    Add-RehearsalResult -Step 'upgrade_ready' -Passed (Wait-RehearsalCondition -Condition {
        Test-RehearsalApiReady
    } -TimeoutSeconds 90)
    $afterUpgrade = Get-RehearsalFileHashes -BackupFileName $businessBackup.filename
    $changedPaths = @($beforeUpgrade.Keys | Where-Object { $beforeUpgrade[$_] -cne $afterUpgrade[$_] })
    Add-RehearsalResult -Step 'upgrade_preserves_programdata' -Passed ($changedPaths.Count -eq 0)
    Set-RehearsalHash -Name 'programdata_manifest_after_upgrade_sha256' -Value (
        Get-RehearsalCanonicalHash -Value $afterUpgrade
    )

    $adminAfterUpgrade = Connect-RehearsalAdmin -Username $initialAdminUsername -Password $rehearsalAdminPassword
    Add-RehearsalResult -Step 'database_integrity_after_upgrade' -Passed (
        Test-RehearsalSqliteIntegrity -Path $databasePath
    )
    $afterUpgradeState = Get-RehearsalBusinessState -WebSession $adminAfterUpgrade.WebSession -Expected $expectedBusinessData -BackupId $businessBackup.id
    Add-RehearsalResult -Step 'upgrade_preserves_business_data' -Passed (
        $afterUpgradeState.Fingerprint -ceq $baselineState.Fingerprint
    )
    Set-RehearsalCount -Name 'business_records_after_upgrade' -Value $afterUpgradeState.Counts.total
    Set-RehearsalHash -Name 'business_state_after_upgrade_sha256' -Value $afterUpgradeState.Fingerprint

    $mutationHeaders = New-RehearsalMutationHeaders -CsrfToken $adminAfterUpgrade.CsrfToken
    $upgradeBackup = Invoke-RehearsalJsonApi -Method POST -Path '/backups' -WebSession $adminAfterUpgrade.WebSession -Headers $mutationHeaders
    $upgradeBackupHash = ([string]$upgradeBackup.sha256).ToLowerInvariant()
    $upgradeBackupPath = Get-RehearsalBackupPath -BackupFileName ([string]$upgradeBackup.filename)
    Add-RehearsalResult -Step 'upgrade_database_backup' -Passed (
        $upgradeBackup.status -ceq 'SUCCEEDED' -and
        $upgradeBackupHash -match '^[0-9a-f]{64}$' -and
        (Test-Path -LiteralPath $upgradeBackupPath -PathType Leaf) -and
        (Get-FileHash -LiteralPath $upgradeBackupPath -Algorithm SHA256).Hash.ToLowerInvariant() -ceq $upgradeBackupHash
    )
    Set-RehearsalHash -Name 'database_snapshot_after_upgrade_sha256' -Value $upgradeBackupHash

    $uninstaller = Join-Path $installRoot 'unins000.exe'
    Invoke-RehearsalExecutable -Path $uninstaller -Arguments @(
        '/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART'
    ) -Step 'keep_data_uninstall'
    Add-RehearsalResult -Step 'keep_data_cleanup' -Passed (
        (Test-RehearsalUninstallState -InstallRoot $installRoot -DataRoot $dataRoot -DataMustRemain $true) -and
        (Test-Path -LiteralPath $databasePath -PathType Leaf) -and
        (Test-Path -LiteralPath $businessBackupPath -PathType Leaf) -and
        $null -eq (Get-Service HighTacPlatform -ErrorAction SilentlyContinue) -and
        $null -eq (Get-Service HighTacMqttBroker -ErrorAction SilentlyContinue) -and
        $null -eq (Get-NetFirewallRule -Name 'HighTac.Platform.Web.LocalSubnet' -ErrorAction SilentlyContinue) -and
        $null -eq (Get-NetFirewallRule -Name 'HighTac.Platform.Mqtt.LocalSubnet' -ErrorAction SilentlyContinue)
    )
    $afterKeepDataUninstall = Get-RehearsalFileHashes -BackupFileName $businessBackup.filename
    Add-RehearsalResult -Step 'keep_data_content_preserved' -Passed (
        (Get-RehearsalCanonicalHash -Value $afterKeepDataUninstall) -ceq
            (Get-RehearsalCanonicalHash -Value $beforeUpgrade)
    )
    Set-RehearsalHash -Name 'programdata_manifest_after_keep_uninstall_sha256' -Value (
        Get-RehearsalCanonicalHash -Value $afterKeepDataUninstall
    )

    Invoke-RehearsalExecutable -Path $CandidateInstaller -Arguments @(
        '/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART', '/REHEARSAL=1'
    ) -Step 'reinstall_with_preserved_data'
    Add-RehearsalResult -Step 'preserved_data_reused' -Passed (Wait-RehearsalCondition -Condition {
        Test-RehearsalApiReady
    } -TimeoutSeconds 90)
    $adminAfterReinstall = Connect-RehearsalAdmin -Username $initialAdminUsername -Password $rehearsalAdminPassword
    Add-RehearsalResult -Step 'database_integrity_after_reinstall' -Passed (
        Test-RehearsalSqliteIntegrity -Path $databasePath
    )
    $afterReinstallState = Get-RehearsalBusinessState -WebSession $adminAfterReinstall.WebSession -Expected $expectedBusinessData -BackupId $businessBackup.id
    Add-RehearsalResult -Step 'reinstall_restores_business_data' -Passed (
        $afterReinstallState.Fingerprint -ceq $baselineState.Fingerprint
    )
    Set-RehearsalCount -Name 'business_records_after_reinstall' -Value $afterReinstallState.Counts.total
    Set-RehearsalHash -Name 'business_state_after_reinstall_sha256' -Value $afterReinstallState.Fingerprint
    $afterReinstall = Get-RehearsalFileHashes -BackupFileName $businessBackup.filename
    Add-RehearsalResult -Step 'reinstall_preserves_credentials' -Passed (
        (Get-RehearsalCanonicalHash -Value $afterReinstall) -ceq
            (Get-RehearsalCanonicalHash -Value $beforeUpgrade)
    )
    Set-RehearsalHash -Name 'programdata_manifest_after_reinstall_sha256' -Value (
        Get-RehearsalCanonicalHash -Value $afterReinstall
    )

    $uninstaller = Join-Path $installRoot 'unins000.exe'
    Invoke-RehearsalExecutable -Path $uninstaller -Arguments @(
        '/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART', '/DELETEDATA=1'
    ) -Step 'delete_data_uninstall'
    Add-RehearsalResult -Step 'delete_data_cleanup' -Passed (
        (Test-RehearsalUninstallState -InstallRoot $installRoot -DataRoot $dataRoot -DataMustRemain $false) -and
        $null -eq (Get-Service HighTacPlatform -ErrorAction SilentlyContinue) -and
        $null -eq (Get-Service HighTacMqttBroker -ErrorAction SilentlyContinue) -and
        $null -eq (Get-NetFirewallRule -Name 'HighTac.Platform.Web.LocalSubnet' -ErrorAction SilentlyContinue) -and
        $null -eq (Get-NetFirewallRule -Name 'HighTac.Platform.Mqtt.LocalSubnet' -ErrorAction SilentlyContinue)
    )

    $legacyRuntimeRoot = Join-Path $installRoot 'server\runtime'
    $legacySourceSentinel = Join-Path $legacyRuntimeRoot 'backups\legacy-migration-sentinel.txt'
    $legacyDestinationSentinel = Join-Path $dataRoot 'backups\legacy-migration-sentinel.txt'
    New-Item -ItemType Directory -Path (Split-Path -Parent $legacySourceSentinel) -Force | Out-Null
    [IO.File]::WriteAllText(
        $legacySourceSentinel,
        'legacy migration sentinel',
        (New-Object Text.UTF8Encoding($false))
    )
    $legacySourceHash = (Get-FileHash -LiteralPath $legacySourceSentinel -Algorithm SHA256).Hash

    Invoke-RehearsalExecutable -Path $CandidateInstaller -Arguments $freshArguments -Step 'legacy_runtime_install'
    Add-RehearsalResult -Step 'legacy_runtime_ready' -Passed (Wait-RehearsalCondition -Condition {
        Test-RehearsalApiReady
    } -TimeoutSeconds 90)
    Add-RehearsalResult -Step 'legacy_runtime_migration' -Passed (
        (Test-Path -LiteralPath $legacySourceSentinel -PathType Leaf) -and
        (Test-Path -LiteralPath $legacyDestinationSentinel -PathType Leaf) -and
        (Get-FileHash -LiteralPath $legacySourceSentinel -Algorithm SHA256).Hash -eq $legacySourceHash -and
        (Get-FileHash -LiteralPath $legacyDestinationSentinel -Algorithm SHA256).Hash -eq $legacySourceHash
    )

    $uninstaller = Join-Path $installRoot 'unins000.exe'
    Invoke-RehearsalExecutable -Path $uninstaller -Arguments @(
        '/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART', '/DELETEDATA=1'
    ) -Step 'legacy_runtime_cleanup'
}
catch {
    $failure = $_.Exception.Message
    $checks['rehearsal_exception'] = $false
}
finally {
    $initialAdminPassword = $null
    $rehearsalAdminPassword = $null
    New-Item -ItemType Directory -Path $OutputRoot -Force | Out-Null
    $report = New-RehearsalReport -Checks $checks -Counts $counts -Hashes $hashes
    $reportPath = Join-Path $OutputRoot 'HighTac-Sandbox-Rehearsal.json'
    [IO.File]::WriteAllText(
        $reportPath,
        (($report | ConvertTo-Json -Depth 8) + [Environment]::NewLine),
        (New-Object Text.UTF8Encoding($false))
    )
}

if ($null -ne $failure) {
    Write-Error 'Sandbox rehearsal failed; the JSON report contains only redacted status values.'
    exit 1
}
exit 0
