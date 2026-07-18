[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$installerRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$launcherPath = Join-Path $installerRoot 'rehearsal\Invoke-HighTacWindowsSandbox.ps1'
$runnerPath = Join-Path $installerRoot 'rehearsal\Run-HighTacSandboxRehearsal.ps1'
$failures = New-Object 'System.Collections.Generic.List[string]'
$passed = 0

function Assert-SandboxCondition {
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

$launcherTokens = $null
$launcherParseErrors = $null
$launcherAst = [Management.Automation.Language.Parser]::ParseFile(
    $launcherPath,
    [ref]$launcherTokens,
    [ref]$launcherParseErrors
)
Assert-SandboxCondition ($launcherParseErrors.Count -eq 0) 'Sandbox launcher must parse in Windows PowerShell 5.1.'

$requiredLauncherFunctionNames = @(
    'Get-RehearsalFileSha256',
    'Get-RehearsalRequiredJsonValue',
    'Assert-RehearsalInstallerSidecars',
    'Get-RehearsalInstallerArtifact',
    'Get-RehearsalInstallerPair'
)
foreach ($functionName in $requiredLauncherFunctionNames) {
    $definitions = @($launcherAst.FindAll({
        param($node)
        $node -is [Management.Automation.Language.FunctionDefinitionAst] -and
            $node.Name -ceq $functionName
    }, $true))
    Assert-SandboxCondition ($definitions.Count -eq 1) "Sandbox behavior test must locate launcher function $functionName."
    if ($definitions.Count -eq 1) {
        Invoke-Expression $definitions[0].Extent.Text
    }
}

$runnerTokens = $null
$runnerParseErrors = $null
$runnerAst = [Management.Automation.Language.Parser]::ParseFile(
    $runnerPath,
    [ref]$runnerTokens,
    [ref]$runnerParseErrors
)
Assert-SandboxCondition ($runnerParseErrors.Count -eq 0) 'Sandbox runner must parse in Windows PowerShell 5.1.'

$requiredFunctionNames = @(
    'New-RehearsalReport',
    'Get-RehearsalCanonicalHash',
    'Test-RehearsalProcessDescendant',
    'Select-RehearsalPlatformProcess',
    'Test-RehearsalPlatformProcessRestarted',
    'Get-RehearsalBackupPath',
    'Initialize-RehearsalSqliteReader',
    'Test-RehearsalSqliteIntegrity',
    'Test-RehearsalUninstallState'
)
foreach ($functionName in $requiredFunctionNames) {
    $definitions = @($runnerAst.FindAll({
        param($node)
        $node -is [Management.Automation.Language.FunctionDefinitionAst] -and
            $node.Name -ceq $functionName
    }, $true))
    Assert-SandboxCondition ($definitions.Count -eq 1) "Sandbox behavior test must locate $functionName."
    if ($definitions.Count -eq 1) {
        Invoke-Expression $definitions[0].Extent.Text
    }
}

$tempBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
$testRoot = [IO.Path]::GetFullPath((Join-Path $tempBase (
    'hightac-sandbox-test-' + [Guid]::NewGuid().ToString('N')
)))
if (-not $testRoot.StartsWith($tempBase + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Sandbox rehearsal test root escaped the Windows temporary directory.'
}
New-Item -ItemType Directory -Path $testRoot -Force | Out-Null

try {
    if ($env:USERNAME -cne 'WDAGUtilityAccount') {
        $probeOutputRoot = Join-Path $testRoot 'forbidden-output'
        $windowsPowerShell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
        $probeArguments = @(
            '-NoLogo',
            '-NoProfile',
            '-NonInteractive',
            '-ExecutionPolicy',
            'Bypass',
            '-File',
            $runnerPath,
            '-BaselineInstaller',
            'C:\missing-baseline.exe',
            '-CandidateInstaller',
            'C:\missing-candidate.exe',
            '-ExpectedBaselineSha256',
            ('0' * 64),
            '-ExpectedCandidateSha256',
            ('1' * 64),
            '-OutputRoot',
            $probeOutputRoot
        )
        $previousErrorActionPreference = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            $probeOutput = & $windowsPowerShell @probeArguments 2>&1 | Out-String
            $probeExitCode = $LASTEXITCODE
        }
        finally {
            $ErrorActionPreference = $previousErrorActionPreference
        }
        Assert-SandboxCondition ($probeExitCode -ne 0) 'Sandbox runner must fail on a non-WDAG host.'
        Assert-SandboxCondition ($probeOutput -match 'Refusing to run service-changing rehearsal outside Windows Sandbox') 'Host refusal must identify the Windows Sandbox boundary.'
        Assert-SandboxCondition (-not (Test-Path -LiteralPath $probeOutputRoot)) 'Host refusal must happen before the runner creates output or reaches service-changing work.'
    }

    $systemExecutable = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
    $fixtureInstaller = Join-Path $testRoot 'HighTacPlatform-fixture-x64.exe'
    Copy-Item -LiteralPath $systemExecutable -Destination $fixtureInstaller
    $fixtureVersion = ([string][Diagnostics.FileVersionInfo]::GetVersionInfo($fixtureInstaller).ProductVersion).Trim()
    $fixtureHash = (Get-FileHash -LiteralPath $fixtureInstaller -Algorithm SHA256).Hash.ToLowerInvariant()
    $previousWhatIfPreference = $WhatIfPreference
    $WhatIfPreference = $true
    try {
        $whatIfSafeHash = Get-RehearsalFileSha256 -Path $fixtureInstaller
    }
    finally {
        $WhatIfPreference = $previousWhatIfPreference
    }
    Assert-SandboxCondition ($whatIfSafeHash -ceq $fixtureHash) 'Read-only installer hashing must remain functional under -WhatIf.'
    $missingSidecarsRejected = $false
    try {
        [void](Get-RehearsalInstallerArtifact `
            -Path $fixtureInstaller `
            -ExpectedVersion $fixtureVersion `
            -Role 'Fixture')
    }
    catch {
        $missingSidecarsRejected = $_.Exception.Message -match 'sidecar is required'
    }
    Assert-SandboxCondition $missingSidecarsRejected 'Installer preflight must reject artifacts without provenance sidecars.'

    $checksumSidecarPath = "$fixtureInstaller.sha256"
    $releaseSidecarPath = "$fixtureInstaller.release.json"
    [IO.File]::WriteAllText(
        $checksumSidecarPath,
        "$fixtureHash *$([IO.Path]::GetFileName($fixtureInstaller))`n",
        (New-Object Text.UTF8Encoding($false))
    )
    $validReleaseSidecar = [ordered]@{
        schema_version = 1
        product_version = $fixtureVersion
        expected_git_tag = "v$fixtureVersion"
        source_commit = 'a' * 40
        source_worktree_clean = $true
        artifact = [IO.Path]::GetFileName($fixtureInstaller)
        sha256 = $fixtureHash
    }
    [IO.File]::WriteAllText(
        $releaseSidecarPath,
        (($validReleaseSidecar | ConvertTo-Json -Depth 4) + [Environment]::NewLine),
        (New-Object Text.UTF8Encoding($false))
    )
    $artifactWithSidecars = Get-RehearsalInstallerArtifact `
        -Path $fixtureInstaller `
        -ExpectedVersion $fixtureVersion `
        -Role 'Fixture'
    Assert-SandboxCondition (
        $artifactWithSidecars.ChecksumSidecarPresent -and
        $artifactWithSidecars.ReleaseSidecarPresent -and
        $artifactWithSidecars.SourceCommit -ceq ('a' * 40)
    ) 'Installer preflight must require and validate both provenance sidecars.'

    $dirtyReleaseSidecar = [ordered]@{} + $validReleaseSidecar
    $dirtyReleaseSidecar.source_worktree_clean = $false
    [IO.File]::WriteAllText(
        $releaseSidecarPath,
        (($dirtyReleaseSidecar | ConvertTo-Json -Depth 4) + [Environment]::NewLine),
        (New-Object Text.UTF8Encoding($false))
    )
    $dirtySourceRejected = $false
    try {
        [void](Get-RehearsalInstallerArtifact -Path $fixtureInstaller -ExpectedVersion $fixtureVersion -Role 'Fixture')
    }
    catch {
        $dirtySourceRejected = $_.Exception.Message -match 'release sidecar does not match'
    }
    Assert-SandboxCondition $dirtySourceRejected 'Installer preflight must reject artifacts built from a dirty source worktree.'
    [IO.File]::WriteAllText(
        $releaseSidecarPath,
        (($validReleaseSidecar | ConvertTo-Json -Depth 4) + [Environment]::NewLine),
        (New-Object Text.UTF8Encoding($false))
    )

    [IO.File]::WriteAllText(
        $checksumSidecarPath,
        "$('0' * 64) *$([IO.Path]::GetFileName($fixtureInstaller))`n",
        (New-Object Text.UTF8Encoding($false))
    )
    $checksumMismatchRejected = $false
    try {
        [void](Get-RehearsalInstallerArtifact -Path $fixtureInstaller -ExpectedVersion $fixtureVersion -Role 'Fixture')
    }
    catch {
        $checksumMismatchRejected = $_.Exception.Message -match 'checksum sidecar does not match'
    }
    Assert-SandboxCondition $checksumMismatchRejected 'Installer preflight must reject a present checksum sidecar whose hash does not match.'
    [IO.File]::WriteAllText(
        $checksumSidecarPath,
        "$fixtureHash *$([IO.Path]::GetFileName($fixtureInstaller))`n",
        (New-Object Text.UTF8Encoding($false))
    )

    $invalidReleaseSidecar = [ordered]@{} + $validReleaseSidecar
    $invalidReleaseSidecar.sha256 = 'f' * 64
    [IO.File]::WriteAllText(
        $releaseSidecarPath,
        (($invalidReleaseSidecar | ConvertTo-Json -Depth 4) + [Environment]::NewLine),
        (New-Object Text.UTF8Encoding($false))
    )
    $releaseMismatchRejected = $false
    try {
        [void](Get-RehearsalInstallerArtifact -Path $fixtureInstaller -ExpectedVersion $fixtureVersion -Role 'Fixture')
    }
    catch {
        $releaseMismatchRejected = $_.Exception.Message -match 'release sidecar does not match'
    }
    Assert-SandboxCondition $releaseMismatchRejected 'Installer preflight must reject a present release sidecar whose metadata does not match.'
    [IO.File]::WriteAllText(
        $releaseSidecarPath,
        (($validReleaseSidecar | ConvertTo-Json -Depth 4) + [Environment]::NewLine),
        (New-Object Text.UTF8Encoding($false))
    )

    $wrongProductVersionRejected = $false
    try {
        [void](Get-RehearsalInstallerArtifact `
            -Path $fixtureInstaller `
            -ExpectedVersion "$fixtureVersion.invalid" `
            -Role 'Fixture')
    }
    catch {
        $wrongProductVersionRejected = $_.Exception.Message -match 'ProductVersion must be'
    }
    Assert-SandboxCondition $wrongProductVersionRejected 'Installer preflight must reject a PE ProductVersion that differs from the required version.'

    $duplicateInstaller = Join-Path $testRoot 'HighTacPlatform-duplicate-x64.exe'
    Copy-Item -LiteralPath $fixtureInstaller -Destination $duplicateInstaller
    [IO.File]::WriteAllText(
        "$duplicateInstaller.sha256",
        "$fixtureHash *$([IO.Path]::GetFileName($duplicateInstaller))`n",
        (New-Object Text.UTF8Encoding($false))
    )
    $duplicateReleaseSidecar = [ordered]@{} + $validReleaseSidecar
    $duplicateReleaseSidecar.artifact = [IO.Path]::GetFileName($duplicateInstaller)
    [IO.File]::WriteAllText(
        "$duplicateInstaller.release.json",
        (($duplicateReleaseSidecar | ConvertTo-Json -Depth 4) + [Environment]::NewLine),
        (New-Object Text.UTF8Encoding($false))
    )
    $duplicateBytesRejected = $false
    try {
        [void](Get-RehearsalInstallerPair `
            -BaselinePath $fixtureInstaller `
            -CandidatePath $duplicateInstaller `
            -BaselineVersion $fixtureVersion `
            -CandidateVersion $fixtureVersion)
    }
    catch {
        $duplicateBytesRejected = $_.Exception.Message -match 'different SHA-256 hashes'
    }
    Assert-SandboxCondition $duplicateBytesRejected 'Baseline and candidate paths with identical bytes must not count as different installers.'

    $samePathRejected = $false
    try {
        [void](Get-RehearsalInstallerPair `
            -BaselinePath $fixtureInstaller `
            -CandidatePath $fixtureInstaller `
            -BaselineVersion $fixtureVersion `
            -CandidateVersion $fixtureVersion)
    }
    catch {
        $samePathRejected = $_.Exception.Message -match 'different files'
    }
    Assert-SandboxCondition $samePathRejected 'Baseline and candidate must not resolve to the same full path.'

    $expectedBackendPath = 'C:\Program Files\HighTac\Platform\server\HighTacPlatform.exe'
    $processFixture = @(
        [pscustomobject]@{ ProcessId = 410; ParentProcessId = 4; ExecutablePath = 'C:\Program Files\HighTac\Platform\services\HighTacPlatform.exe'; CreationDate = 'service' },
        [pscustomobject]@{ ProcessId = 420; ParentProcessId = 410; ExecutablePath = 'C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe'; CreationDate = 'launcher' },
        [pscustomobject]@{ ProcessId = 430; ParentProcessId = 420; ExecutablePath = $expectedBackendPath; CreationDate = 'backend-before' },
        [pscustomobject]@{ ProcessId = 440; ParentProcessId = 420; ExecutablePath = 'C:\Temp\HighTacPlatform.exe'; CreationDate = 'wrong-path' },
        [pscustomobject]@{ ProcessId = 450; ParentProcessId = 4; ExecutablePath = $expectedBackendPath; CreationDate = 'unowned' }
    )
    $selectedBackend = Select-RehearsalPlatformProcess `
        -Processes $processFixture `
        -ServiceProcessId 410 `
        -ExpectedExecutablePath $expectedBackendPath
    Assert-SandboxCondition (
        $null -ne $selectedBackend -and
        $selectedBackend.ProcessId -eq 430 -and
        $selectedBackend.ParentProcessId -eq 420 -and
        $selectedBackend.ServiceProcessId -eq 410 -and
        $selectedBackend.ExecutablePath -ceq $expectedBackendPath
    ) 'Backend selection must require both the packaged full path and ancestry under the HighTac service PID.'
    Assert-SandboxCondition (
        -not (Test-RehearsalPlatformProcessRestarted -Before $selectedBackend -After $selectedBackend)
    ) 'An unchanged service-owned backend identity must not be reported as a restore restart.'
    $reusedPidBackend = [pscustomobject]@{
        ProcessId = 430
        CreationDate = 'backend-after'
    }
    Assert-SandboxCondition (
        Test-RehearsalPlatformProcessRestarted -Before $selectedBackend -After $reusedPidBackend
    ) 'A reused backend PID with a different creation time must count as a real process replacement.'
    $replacementBackend = [pscustomobject]@{
        ProcessId = 460
        CreationDate = 'backend-after'
    }
    Assert-SandboxCondition (
        Test-RehearsalPlatformProcessRestarted -Before $selectedBackend -After $replacementBackend
    ) 'A new service-owned backend PID must count as a real process replacement.'

    $ambiguousProcessFixture = @($processFixture) + @(
        [pscustomobject]@{ ProcessId = 431; ParentProcessId = 420; ExecutablePath = $expectedBackendPath; CreationDate = 'second-backend' }
    )
    $ambiguousBackendRejected = $false
    try {
        [void](Select-RehearsalPlatformProcess `
            -Processes $ambiguousProcessFixture `
            -ServiceProcessId 410 `
            -ExpectedExecutablePath $expectedBackendPath)
    }
    catch {
        $ambiguousBackendRejected = $_.Exception.Message -match 'Multiple service-owned processes'
    }
    Assert-SandboxCondition $ambiguousBackendRejected 'Backend selection must fail closed instead of taking the first ambiguous path match.'

    $projectionA = [ordered]@{
        site = [ordered]@{ id = 'site-a'; count = 1 }
        records = @('station-a', 'tag-a', 'product-a', 'binding-a')
    }
    $projectionB = [ordered]@{
        site = [ordered]@{ id = 'site-a'; count = 1 }
        records = @('station-a', 'tag-a', 'product-a', 'binding-a')
    }
    $projectionChanged = [ordered]@{
        site = [ordered]@{ id = 'site-a'; count = 1 }
        records = @('station-a', 'tag-a', 'product-a', 'binding-b')
    }
    $hashA = Get-RehearsalCanonicalHash -Value $projectionA
    $hashB = Get-RehearsalCanonicalHash -Value $projectionB
    $hashChanged = Get-RehearsalCanonicalHash -Value $projectionChanged
    Assert-SandboxCondition ($hashA -match '^[0-9a-f]{64}$') 'Business projection fingerprint must be lowercase SHA-256.'
    Assert-SandboxCondition ($hashA -ceq $hashB) 'Equivalent ordered business projections must have the same fingerprint.'
    Assert-SandboxCondition ($hashA -cne $hashChanged) 'A changed business binding must change the projection fingerprint.'

    $script:dataRoot = Join-Path $testRoot 'managed-data'
    $safeBackupPath = Get-RehearsalBackupPath -BackupFileName 'hightac-manual-test.db'
    Assert-SandboxCondition (
        $safeBackupPath -ceq (Join-Path $script:dataRoot 'backups\hightac-manual-test.db')
    ) 'Backup filenames must resolve inside the managed backups directory.'
    $unsafeBackupRejected = $false
    try {
        [void](Get-RehearsalBackupPath -BackupFileName '..\outside.db')
    }
    catch {
        $unsafeBackupRejected = $true
    }
    Assert-SandboxCondition $unsafeBackupRejected 'Backup path traversal must be rejected before any file read.'

    $report = New-RehearsalReport -Checks ([ordered]@{
        upgrade_preserves_business_data = $true
        delete_data_cleanup = $true
    }) -Counts ([ordered]@{
        business_records = [long]7
    }) -Hashes ([ordered]@{
        business_state_sha256 = $hashA
    })
    $reportJson = $report | ConvertTo-Json -Depth 8
    $parsedReport = $reportJson | ConvertFrom-Json
    $topLevelNames = @($parsedReport.PSObject.Properties.Name | Sort-Object)
    $expectedTopLevelNames = @(
        'checks',
        'counts',
        'hashes',
        'passed',
        'sandbox_account_verified',
        'schema_version',
        'secrets_included'
    ) | Sort-Object
    Assert-SandboxCondition (@(Compare-Object $topLevelNames $expectedTopLevelNames).Count -eq 0) 'Sandbox JSON must expose only schema metadata, Booleans, counts, and hash groups.'
    Assert-SandboxCondition (
        $parsedReport.passed -is [bool] -and
        $parsedReport.secrets_included -is [bool] -and
        $parsedReport.sandbox_account_verified -is [bool] -and
        @($parsedReport.checks.PSObject.Properties | Where-Object { -not ($_.Value -is [bool]) }).Count -eq 0
    ) 'Sandbox JSON check values must all be Boolean.'
    Assert-SandboxCondition (
        @($parsedReport.counts.PSObject.Properties | Where-Object {
            -not ($_.Value -is [int16] -or $_.Value -is [int32] -or $_.Value -is [int64])
        }).Count -eq 0
    ) 'Sandbox JSON count values must all be integers.'
    Assert-SandboxCondition (
        @($parsedReport.hashes.PSObject.Properties | Where-Object {
            [string]$_.Value -notmatch '^[0-9a-f]{64}$'
        }).Count -eq 0
    ) 'Sandbox JSON hash values must all be lowercase SHA-256.'
    Assert-SandboxCondition ($reportJson -notmatch 'detail|checked_at|started_at|completed_at|password') 'Sandbox JSON must not contain details, timestamps, or password fields.'

    $badHashRejected = $false
    try {
        [void](New-RehearsalReport -Checks ([ordered]@{ ok = $true }) -Counts ([ordered]@{}) -Hashes ([ordered]@{ bad = 'not-a-hash' }))
    }
    catch {
        $badHashRejected = $true
    }
    Assert-SandboxCondition $badHashRejected 'Report construction must reject non-SHA-256 string values.'

    $badCountRejected = $false
    try {
        [void](New-RehearsalReport -Checks ([ordered]@{ ok = $true }) -Counts ([ordered]@{ bad = 'seven' }) -Hashes ([ordered]@{}))
    }
    catch {
        $badCountRejected = $true
    }
    Assert-SandboxCondition $badCountRejected 'Report construction must reject non-integer count values.'

    $installProbe = Join-Path $testRoot 'ProgramFiles\HighTac\Platform'
    $dataProbe = Join-Path $testRoot 'ProgramData\HighTac\Platform'
    New-Item -ItemType Directory -Path $dataProbe -Force | Out-Null
    Assert-SandboxCondition (
        Test-RehearsalUninstallState -InstallRoot $installProbe -DataRoot $dataProbe -DataMustRemain $true
    ) 'Keep-data uninstall state must require missing Program Files and retained ProgramData.'
    New-Item -ItemType Directory -Path $installProbe -Force | Out-Null
    Assert-SandboxCondition (
        -not (Test-RehearsalUninstallState -InstallRoot $installProbe -DataRoot $dataProbe -DataMustRemain $true)
    ) 'Keep-data uninstall state must fail while Program Files remains.'
    Remove-Item -LiteralPath (Join-Path $testRoot 'ProgramFiles') -Recurse -Force
    Remove-Item -LiteralPath (Join-Path $testRoot 'ProgramData') -Recurse -Force
    Assert-SandboxCondition (
        Test-RehearsalUninstallState -InstallRoot $installProbe -DataRoot $dataProbe -DataMustRemain $false
    ) 'Delete-data uninstall state must require both Program Files and ProgramData to be absent.'
    New-Item -ItemType Directory -Path $dataProbe -Force | Out-Null
    Assert-SandboxCondition (
        -not (Test-RehearsalUninstallState -InstallRoot $installProbe -DataRoot $dataProbe -DataMustRemain $false)
    ) 'Delete-data uninstall state must fail while ProgramData remains.'

    $missingDatabase = Join-Path $testRoot 'missing.db'
    Assert-SandboxCondition (
        -not (Test-RehearsalSqliteIntegrity -Path $missingDatabase)
    ) 'SQLite integrity check must reject a missing database.'
    $corruptDatabase = Join-Path $testRoot 'corrupt.db'
    [IO.File]::WriteAllText($corruptDatabase, 'not sqlite', (New-Object Text.UTF8Encoding($false)))
    Assert-SandboxCondition (
        -not (Test-RehearsalSqliteIntegrity -Path $corruptDatabase)
    ) 'SQLite integrity check must reject a malformed database.'

    $python = Get-Command python -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -ne $python) {
        $validDatabase = Join-Path $testRoot 'valid.db'
        $invalidForeignKeyDatabase = Join-Path $testRoot 'invalid-foreign-key.db'
        $pythonScript = @'
import sqlite3
import sys

def create(path, invalid):
    connection = sqlite3.connect(path)
    connection.executescript(
        "PRAGMA foreign_keys=OFF;"
        "CREATE TABLE parent(id INTEGER PRIMARY KEY);"
        "CREATE TABLE child(id INTEGER PRIMARY KEY, parent_id INTEGER REFERENCES parent(id));"
        "INSERT INTO parent(id) VALUES (1);"
    )
    connection.execute(
        "INSERT INTO child(id, parent_id) VALUES (1, ?)",
        (999 if invalid else 1,),
    )
    connection.commit()
    connection.close()

create(sys.argv[1], False)
create(sys.argv[2], True)
'@
        $pythonFixtureScript = Join-Path $testRoot 'create_sqlite_fixtures.py'
        [IO.File]::WriteAllText(
            $pythonFixtureScript,
            $pythonScript,
            (New-Object Text.UTF8Encoding($false))
        )
        $previousErrorActionPreference = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            $pythonOutput = & $python.Source $pythonFixtureScript $validDatabase $invalidForeignKeyDatabase 2>&1
            $pythonExitCode = $LASTEXITCODE
        }
        finally {
            $ErrorActionPreference = $previousErrorActionPreference
        }
        Assert-SandboxCondition ($pythonExitCode -eq 0) "SQLite test fixture creation must succeed: $pythonOutput"
        if ($pythonExitCode -eq 0) {
            $validHashBefore = (Get-FileHash -LiteralPath $validDatabase -Algorithm SHA256).Hash
            $validIntegrity = Test-RehearsalSqliteIntegrity -Path $validDatabase
            $validHashAfter = (Get-FileHash -LiteralPath $validDatabase -Algorithm SHA256).Hash
            Assert-SandboxCondition $validIntegrity 'Read-only SQLite checks must accept an intact database without foreign-key violations.'
            Assert-SandboxCondition ($validHashBefore -ceq $validHashAfter) 'SQLite integrity checks must not modify the database file.'
            Assert-SandboxCondition (
                -not (Test-RehearsalSqliteIntegrity -Path $invalidForeignKeyDatabase)
            ) 'SQLite integrity checks must reject a foreign-key violation even when integrity_check is otherwise OK.'
        }
    }
}
finally {
    if ($testRoot.StartsWith($tempBase + '\', [StringComparison]::OrdinalIgnoreCase) -and
        (Test-Path -LiteralPath $testRoot)) {
        Remove-Item -LiteralPath $testRoot -Recurse -Force
    }
}

if ($failures.Count -gt 0) {
    Write-Output "Sandbox rehearsal checks failed ($($failures.Count)):"
    $failures | ForEach-Object { Write-Output " - $_" }
    exit 1
}

Write-Output "Sandbox rehearsal checks passed: $passed assertions."
