[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$reportScript = Join-Path $repositoryRoot 'installer\packaging\New-ReleaseReport.ps1'
$buildScript = Join-Path $repositoryRoot 'installer\packaging\Build-Release.ps1'
$sourceEvidencePath = Join-Path $repositoryRoot 'docs\releases\v2.0.0-evidence.json'
$tempBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
$testRoot = [IO.Path]::GetFullPath((Join-Path $tempBase ("hightac-release-report-" + [guid]::NewGuid().ToString('N'))))
if (-not $testRoot.StartsWith($tempBase + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Release report test root escaped the Windows temporary directory.'
}

$passed = 0
function Assert-ReleaseReportCondition {
    param(
        [Parameter(Mandatory = $true)]
        [bool]$Condition,

        [Parameter(Mandatory = $true)]
        [string]$Message
    )

    if (-not $Condition) {
        throw $Message
    }
    $script:passed++
}

function Write-InstallerSidecars {
    param(
        [Parameter(Mandatory = $true)]
        [string]$InstallerPath,

        [Parameter(Mandatory = $true)]
        [string]$DistributionTarget,

        [Parameter(Mandatory = $true)]
        [string]$DistributionClass,

        [string]$SourceCommit,

        [bool]$SourceWorktreeClean = $true
    )

    $hash = (Get-FileHash -LiteralPath $InstallerPath -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText(
        "$InstallerPath.sha256",
        "$hash *$([IO.Path]::GetFileName($InstallerPath))`n",
        (New-Object Text.UTF8Encoding($false))
    )
    if (-not $SourceCommit) {
        $SourceCommit = ([string](& git -C $repositoryRoot rev-parse HEAD)).Trim().ToLowerInvariant()
    }
    $metadata = [ordered]@{
        schema_version = 1
        product_version = '2.0.0'
        expected_git_tag = 'v2.0.0'
        source_commit = $SourceCommit
        source_worktree_clean = $SourceWorktreeClean
        artifact = [IO.Path]::GetFileName($InstallerPath)
        sha256 = $hash
        distribution_target = $DistributionTarget
        distribution_class = $DistributionClass
        authenticode_required = $DistributionTarget -eq 'public_distribution'
    }
    [IO.File]::WriteAllText(
        "$InstallerPath.release.json",
        (($metadata | ConvertTo-Json -Depth 4) + "`n"),
        (New-Object Text.UTF8Encoding($false))
    )
}

function New-TestEvidenceFile {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name,

        [scriptblock]$Mutator
    )

    $evidence = [IO.File]::ReadAllText($sourceEvidencePath) | ConvertFrom-Json
    if ($Mutator) {
        & $Mutator $evidence | Out-Null
    }
    $path = Join-Path $testRoot $Name
    [IO.File]::WriteAllText(
        $path,
        (($evidence | ConvertTo-Json -Depth 10) + "`n"),
        (New-Object Text.UTF8Encoding($false))
    )
    return $path
}

function Assert-ReleaseEvidenceRejected {
    param(
        [Parameter(Mandatory = $true)]
        [string]$InstallerPath,

        [Parameter(Mandatory = $true)]
        [string]$ApkPath,

        [Parameter(Mandatory = $true)]
        [string]$EvidencePath,

        [Parameter(Mandatory = $true)]
        [string]$ErrorPattern,

        [Parameter(Mandatory = $true)]
        [string]$Message
    )

    $rejected = $false
    try {
        [void](& $reportScript `
            -AppVersion '2.0.0' `
            -InstallerPath $InstallerPath `
            -ApkPath $ApkPath `
            -EvidencePath $EvidencePath `
            -RepositoryRoot $repositoryRoot `
            -OutputRoot $testRoot `
            -AllowReleaseCandidate)
    }
    catch {
        $rejected = $_.Exception.Message -match $ErrorPattern
    }
    Assert-ReleaseReportCondition $rejected $Message
}

New-Item -ItemType Directory -Path $testRoot -Force | Out-Null
try {
    $installerPath = Join-Path $testRoot 'HighTacPlatform-2.0.0-x64.exe'
    $namespace = 'HighTacReleaseFixture' + [guid]::NewGuid().ToString('N')
    $source = @"
namespace $namespace {
    public static class Program {
        public static void Main() { }
    }
}
"@
    Add-Type -TypeDefinition $source -Language CSharp -OutputAssembly $installerPath -OutputType ConsoleApplication
    $apkPath = Join-Path $testRoot 'app-release.apk'
    [IO.File]::WriteAllBytes($apkPath, [byte[]](0x50, 0x4b, 0x03, 0x04))
    $fixtureSignature = Get-AuthenticodeSignature -LiteralPath $installerPath
    Assert-ReleaseReportCondition ([string]$fixtureSignature.Status -eq 'NotSigned') 'Fixture PE must be unsigned for the internal-LAN report test.'

    Write-InstallerSidecars `
        -InstallerPath $installerPath `
        -DistributionTarget 'internal_lan' `
        -DistributionClass 'internal_lan_unsigned'
    $result = & $reportScript `
        -AppVersion '2.0.0' `
        -InstallerPath $installerPath `
        -RepositoryRoot $repositoryRoot `
        -OutputRoot $testRoot `
        -AllowReleaseCandidate
    Assert-ReleaseReportCondition (Test-Path -LiteralPath $result.JsonReport -PathType Leaf) 'Internal-LAN JSON release report was not generated.'
    Assert-ReleaseReportCondition (Test-Path -LiteralPath $result.MarkdownReport -PathType Leaf) 'Internal-LAN Markdown release report was not generated.'
    Assert-ReleaseReportCondition (-not $result.ReleaseReady) 'A fixture without APK, evidence, clean tag, and tests must not be release-ready.'
    $jsonReport = [IO.File]::ReadAllText($result.JsonReport) | ConvertFrom-Json
    Assert-ReleaseReportCondition ($jsonReport.distribution_target -ceq 'internal_lan') 'Report must preserve the internal-LAN target.'
    Assert-ReleaseReportCondition ($jsonReport.artifacts.windows_installer.distribution_class -ceq 'internal_lan_unsigned') 'Report must preserve the unsigned internal-LAN classification.'
    Assert-ReleaseReportCondition ($jsonReport.artifacts.windows_installer.authenticode_status -ceq 'NotSigned') 'Report must state that the internal-LAN fixture is not Authenticode signed.'
    Assert-ReleaseReportCondition ($jsonReport.artifacts.windows_installer.source_commit -ceq ([string](& git -C $repositoryRoot rev-parse HEAD)).Trim().ToLowerInvariant()) 'Report must preserve the installer source commit.'
    Assert-ReleaseReportCondition ([bool]$jsonReport.artifacts.windows_installer.source_worktree_clean) 'Report must preserve the clean installer source-worktree claim.'
    Assert-ReleaseReportCondition ($jsonReport.release_mode -ceq 'candidate') 'An explicitly allowed incomplete report must identify itself as a candidate.'

    Write-InstallerSidecars `
        -InstallerPath $installerPath `
        -DistributionTarget 'internal_lan' `
        -DistributionClass 'internal_lan_unsigned' `
        -SourceCommit ('0' * 40)
    $wrongSourceRejected = $false
    try {
        [void](& $reportScript `
            -AppVersion '2.0.0' `
            -InstallerPath $installerPath `
            -RepositoryRoot $repositoryRoot `
            -OutputRoot $testRoot `
            -AllowReleaseCandidate)
    }
    catch {
        $wrongSourceRejected = $_.Exception.Message -match 'source_commit does not match'
    }
    Assert-ReleaseReportCondition $wrongSourceRejected 'Release reporting must reject an installer built from another Git commit.'

    Write-InstallerSidecars `
        -InstallerPath $installerPath `
        -DistributionTarget 'internal_lan' `
        -DistributionClass 'internal_lan_unsigned' `
        -SourceWorktreeClean:$false
    $dirtySourceRejected = $false
    try {
        [void](& $reportScript `
            -AppVersion '2.0.0' `
            -InstallerPath $installerPath `
            -RepositoryRoot $repositoryRoot `
            -OutputRoot $testRoot)
    }
    catch {
        $dirtySourceRejected = $_.Exception.Message -match 'dirty source worktree'
    }
    Assert-ReleaseReportCondition $dirtySourceRejected 'Formal release reporting must reject an installer built from a dirty source worktree.'

    Write-InstallerSidecars `
        -InstallerPath $installerPath `
        -DistributionTarget 'internal_lan' `
        -DistributionClass 'internal_lan_unsigned'

    $powerShellExecutable = (Get-Process -Id $PID).Path
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $formalOutput = @(& $powerShellExecutable `
            -NoLogo `
            -NoProfile `
            -NonInteractive `
            -ExecutionPolicy Bypass `
            -File $reportScript `
            -AppVersion '2.0.0' `
            -InstallerPath $installerPath `
            -RepositoryRoot $repositoryRoot `
            -OutputRoot $testRoot 2>&1)
        $formalExitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    Assert-ReleaseReportCondition ($formalExitCode -ne 0) 'Formal release reporting must return a non-zero exit code when ReleaseReady is false.'
    Assert-ReleaseReportCondition (($formalOutput -join "`n") -match 'not release-ready') 'Formal release reporting must explain the failed release gate.'

    $validEvidencePath = New-TestEvidenceFile -Name 'valid-evidence.json'
    $validEvidenceResult = & $reportScript `
        -AppVersion '2.0.0' `
        -InstallerPath $installerPath `
        -ApkPath $apkPath `
        -EvidencePath $validEvidencePath `
        -RepositoryRoot $repositoryRoot `
        -OutputRoot $testRoot `
        -AllowReleaseCandidate
    Assert-ReleaseReportCondition (-not $validEvidenceResult.ReleaseReady) 'Pending fixed evidence must remain a non-final candidate.'
    Assert-ReleaseReportCondition ($validEvidenceResult.ReleaseMode -ceq 'candidate') 'Candidate opt-in must preserve the existing incomplete-report workflow.'

    $missingAutomaticPath = New-TestEvidenceFile -Name 'missing-automatic.json' -Mutator {
        param($evidence)
        $evidence.test_matrix = @($evidence.test_matrix | Where-Object { [string]$_.id -cne 'backend_pytest' })
    }
    Assert-ReleaseEvidenceRejected `
        -InstallerPath $installerPath `
        -ApkPath $apkPath `
        -EvidencePath $missingAutomaticPath `
        -ErrorPattern 'test_matrix.*missing: backend_pytest' `
        -Message 'The fixed seven-entry automatic test matrix must reject a missing ID.'

    $duplicateAutomaticPath = New-TestEvidenceFile -Name 'duplicate-automatic.json' -Mutator {
        param($evidence)
        $evidence.test_matrix = @($evidence.test_matrix) + @($evidence.test_matrix[0])
    }
    Assert-ReleaseEvidenceRejected `
        -InstallerPath $installerPath `
        -ApkPath $apkPath `
        -EvidencePath $duplicateAutomaticPath `
        -ErrorPattern 'test_matrix.*not unique: android_unit_and_room' `
        -Message 'The fixed automatic test matrix must reject duplicate IDs.'

    $nonGatingAutomaticPath = New-TestEvidenceFile -Name 'non-gating-automatic.json' -Mutator {
        param($evidence)
        @($evidence.test_matrix | Where-Object { [string]$_.id -ceq 'contract_validation' })[0].required_for_release = $false
    }
    Assert-ReleaseEvidenceRejected `
        -InstallerPath $installerPath `
        -ApkPath $apkPath `
        -EvidencePath $nonGatingAutomaticPath `
        -ErrorPattern "test_matrix entry 'contract_validation'.*Boolean value true" `
        -Message 'A fixed automatic gate must not be bypassable by setting required_for_release to false.'

    $missingManualPath = New-TestEvidenceFile -Name 'missing-manual.json' -Mutator {
        param($evidence)
        $evidence.manual_checks = @($evidence.manual_checks | Where-Object { [string]$_.id -cne 'two_android_client_sync' })
    }
    Assert-ReleaseEvidenceRejected `
        -InstallerPath $installerPath `
        -ApkPath $apkPath `
        -EvidencePath $missingManualPath `
        -ErrorPattern 'manual_checks.*missing: two_android_client_sync' `
        -Message 'The fixed manual release checklist must reject a missing ID.'

    $nonGatingManualPath = New-TestEvidenceFile -Name 'non-gating-manual.json' -Mutator {
        param($evidence)
        @($evidence.manual_checks | Where-Object { [string]$_.id -ceq 'windows_reboot_autostart' })[0].required_for_release = $false
    }
    Assert-ReleaseEvidenceRejected `
        -InstallerPath $installerPath `
        -ApkPath $apkPath `
        -EvidencePath $nonGatingManualPath `
        -ErrorPattern "manual_checks entry 'windows_reboot_autostart'.*Boolean value true" `
        -Message 'A fixed manual gate must not be bypassable by setting required_for_release to false.'

    $invalidProviderWaiverPath = New-TestEvidenceFile -Name 'invalid-provider-waiver.json' -Mutator {
        param($evidence)
        $provider = @($evidence.manual_checks | Where-Object { [string]$_.id -ceq 'provider_credentials_rotated' })[0]
        $provider.required_for_release = $false
        $provider.status = 'external'
        $provider.owner = 'user'
        $provider.PSObject.Properties.Remove('waiver')
    }
    Assert-ReleaseEvidenceRejected `
        -InstallerPath $installerPath `
        -ApkPath $apkPath `
        -EvidencePath $invalidProviderWaiverPath `
        -ErrorPattern "provider_credentials_rotated.*waiver" `
        -Message 'Provider revocation verification must not become non-gating without a complete explicit user waiver.'

    foreach ($invalidWaiver in @(
        [pscustomobject]@{ Name = 'waiver-not-accepted.json'; Property = 'accepted'; Value = $false; Pattern = 'provider_credentials_rotated.*waiver' },
        [pscustomobject]@{ Name = 'waiver-wrong-owner.json'; Property = 'owner'; Value = 'release-team'; Pattern = 'provider_credentials_rotated.*waiver' },
        [pscustomobject]@{ Name = 'waiver-invalid-time.json'; Property = 'accepted_at'; Value = 'not-a-timestamp'; Pattern = 'provider_credentials_rotated.*waiver' },
        [pscustomobject]@{ Name = 'waiver-empty-scope.json'; Property = 'scope'; Value = ''; Pattern = 'provider_credentials_rotated.*waiver' },
        [pscustomobject]@{ Name = 'waiver-empty-risk.json'; Property = 'risk'; Value = ''; Pattern = 'provider_credentials_rotated.*waiver' }
    )) {
        $invalidWaiverPath = New-TestEvidenceFile -Name $invalidWaiver.Name -Mutator {
            param($evidence)
            $provider = @($evidence.manual_checks | Where-Object { [string]$_.id -ceq 'provider_credentials_rotated' })[0]
            if ($invalidWaiver.Property -ceq 'owner') {
                $provider.owner = $invalidWaiver.Value
            }
            else {
                $provider.waiver.($invalidWaiver.Property) = $invalidWaiver.Value
            }
        }
        Assert-ReleaseEvidenceRejected `
            -InstallerPath $installerPath `
            -ApkPath $apkPath `
            -EvidencePath $invalidWaiverPath `
            -ErrorPattern $invalidWaiver.Pattern `
            -Message "Provider waiver must reject invalid field: $($invalidWaiver.Property)."
    }

    $gatingScalePath = New-TestEvidenceFile -Name 'gating-scale.json' -Mutator {
        param($evidence)
        @($evidence.manual_checks | Where-Object { [string]$_.id -ceq 'scale_acceptance_10_phones_2000_tags' })[0].required_for_release = $true
    }
    Assert-ReleaseEvidenceRejected `
        -InstallerPath $installerPath `
        -ApkPath $apkPath `
        -EvidencePath $gatingScalePath `
        -ErrorPattern "manual_checks entry 'scale_acceptance_10_phones_2000_tags'.*Boolean value false" `
        -Message 'The user-owned scale acceptance must remain the unconditional non-gating manual check.'

    $buildScriptText = [IO.File]::ReadAllText($buildScript)
    Assert-ReleaseReportCondition ($buildScriptText -match '\[switch\]\$AllowReleaseCandidate') 'Build-Release.ps1 must expose an explicit release-candidate opt-in switch.'
    Assert-ReleaseReportCondition ($buildScriptText -match 'AllowReleaseCandidate\s*=\s*\[bool\]\$AllowReleaseCandidate') 'Build-Release.ps1 must forward candidate mode to release reporting.'
    Assert-ReleaseReportCondition ($buildScriptText -match '-not \$releaseReport\.ReleaseReady -and -not \$AllowReleaseCandidate') 'Build-Release.ps1 must retain its own formal release-ready gate.'
    $reportScriptText = [IO.File]::ReadAllText($reportScript)
    Assert-ReleaseReportCondition ($reportScriptText -match "providerCheck\.required_for_release[\s\S]+distributionTarget -cne 'internal_lan'") 'Provider credential waiver must remain restricted to internal-LAN releases.'

    Write-InstallerSidecars `
        -InstallerPath $installerPath `
        -DistributionTarget 'public_distribution' `
        -DistributionClass 'public_distribution_signed'
    $publicForgeryRejected = $false
    try {
        [void](& $reportScript `
            -AppVersion '2.0.0' `
            -InstallerPath $installerPath `
            -RepositoryRoot $repositoryRoot `
            -OutputRoot $testRoot `
            -AllowReleaseCandidate)
    }
    catch {
        $publicForgeryRejected = $_.Exception.Message -match 'inconsistent with live Authenticode verification'
    }
    Assert-ReleaseReportCondition $publicForgeryRejected 'An unsigned artifact must not be reportable as a signed public distribution.'
}
finally {
    if (Test-Path -LiteralPath $testRoot) {
        $resolvedTestRoot = [IO.Path]::GetFullPath($testRoot)
        if (-not $resolvedTestRoot.StartsWith($tempBase + '\', [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Refusing to clean a release report test path outside the Windows temporary directory.'
        }
        Remove-Item -LiteralPath $resolvedTestRoot -Recurse -Force
    }
}

Write-Output "Release reporting checks passed: $passed assertions."
