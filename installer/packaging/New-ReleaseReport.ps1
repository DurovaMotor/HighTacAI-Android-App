[CmdletBinding()]
param(
    [ValidatePattern('^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$')]
    [string]$AppVersion = '2.0.0',

    [Parameter(Mandatory = $true)]
    [string]$InstallerPath,

    [string]$ApkPath,

    [string]$EvidencePath,

    [string]$RepositoryRoot,

    [string]$OutputRoot,

    [switch]$AllowReleaseCandidate
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-RequiredProperty {
    param(
        [Parameter(Mandatory = $true)]
        [object]$InputObject,

        [Parameter(Mandatory = $true)]
        [string]$Name,

        [Parameter(Mandatory = $true)]
        [string]$Context
    )

    $property = $InputObject.PSObject.Properties[$Name]
    if ($null -eq $property) {
        throw "$Context is missing required property '$Name'."
    }
    return $property.Value
}

function ConvertTo-MarkdownCell {
    param([AllowNull()][object]$Value)

    if ($null -eq $Value) {
        return ''
    }
    return ([string]$Value).Replace('|', '\|').Replace("`r", '').Replace("`n", '<br>')
}

function Invoke-GitText {
    param([string[]]$Arguments)

    $output = @(& git -C $RepositoryRoot @Arguments 2>$null)
    if ($LASTEXITCODE -ne 0) {
        return $null
    }
    return ($output -join "`n").Trim()
}

function Assert-FixedEvidenceCollection {
    param(
        [Parameter(Mandatory = $true)]
        [object[]]$Entries,

        [Parameter(Mandatory = $true)]
        [string[]]$ExpectedIds,

        [Parameter(Mandatory = $true)]
        [string]$Context,

        [string[]]$NonGatingIds = @(),

        [string[]]$WaivableIds = @()
    )

    $actualIds = @($Entries | ForEach-Object {
        [string](Get-RequiredProperty $_ 'id' "$Context entry")
    })
    $problems = New-Object 'System.Collections.Generic.List[string]'
    $missingIds = @($ExpectedIds | Where-Object { $actualIds -cnotcontains $_ })
    $unexpectedIds = @($actualIds | Where-Object { $ExpectedIds -cnotcontains $_ } | Sort-Object -Unique)
    $duplicateIds = @($ExpectedIds | Where-Object {
        $expectedId = $_
        @($actualIds | Where-Object { $_ -ceq $expectedId }).Count -ne 1
    } | Where-Object { $actualIds -ccontains $_ })

    if ($missingIds.Count -gt 0) {
        $problems.Add("missing: $($missingIds -join ', ')")
    }
    if ($unexpectedIds.Count -gt 0) {
        $problems.Add("unexpected: $($unexpectedIds -join ', ')")
    }
    if ($duplicateIds.Count -gt 0) {
        $problems.Add("not unique: $($duplicateIds -join ', ')")
    }
    if ($actualIds.Count -ne $ExpectedIds.Count -and $problems.Count -eq 0) {
        $problems.Add("expected $($ExpectedIds.Count) entries but found $($actualIds.Count)")
    }
    if ($problems.Count -gt 0) {
        throw "$Context must contain exactly the fixed release evidence IDs ($($ExpectedIds -join ', ')); $($problems -join '; ')."
    }

    foreach ($expectedId in $ExpectedIds) {
        $entry = @($Entries | Where-Object { [string]$_.id -ceq $expectedId })[0]
        $requiredForRelease = Get-RequiredProperty $entry 'required_for_release' "$Context entry '$expectedId'"
        $mustGateRelease = $NonGatingIds -cnotcontains $expectedId
        if ($WaivableIds -ccontains $expectedId -and
            $requiredForRelease -is [bool] -and
            -not [bool]$requiredForRelease) {
            $status = [string](Get-RequiredProperty $entry 'status' "$Context entry '$expectedId'")
            $owner = [string](Get-RequiredProperty $entry 'owner' "$Context entry '$expectedId'")
            $waiver = Get-RequiredProperty $entry 'waiver' "$Context entry '$expectedId'"
            $accepted = Get-RequiredProperty $waiver 'accepted' "$Context entry '$expectedId' waiver"
            $acceptedBy = [string](Get-RequiredProperty $waiver 'accepted_by' "$Context entry '$expectedId' waiver")
            $acceptedAt = [string](Get-RequiredProperty $waiver 'accepted_at' "$Context entry '$expectedId' waiver")
            $scope = [string](Get-RequiredProperty $waiver 'scope' "$Context entry '$expectedId' waiver")
            $risk = [string](Get-RequiredProperty $waiver 'risk' "$Context entry '$expectedId' waiver")
            $acceptedAtValid = $false
            if (-not [string]::IsNullOrWhiteSpace($acceptedAt)) {
                try {
                    [void][DateTimeOffset]::Parse(
                        $acceptedAt,
                        [Globalization.CultureInfo]::InvariantCulture,
                        [Globalization.DateTimeStyles]::RoundtripKind
                    )
                    $acceptedAtValid = $true
                }
                catch {
                    $acceptedAtValid = $false
                }
            }
            if ($status -cne 'external' -or
                $owner -cne 'user' -or
                $accepted -isnot [bool] -or
                -not [bool]$accepted -or
                $acceptedBy -cne 'user' -or
                -not $acceptedAtValid -or
                [string]::IsNullOrWhiteSpace($scope) -or
                [string]::IsNullOrWhiteSpace($risk)) {
                throw "$Context entry '$expectedId' may be non-gating only with an explicit external user waiver containing accepted, accepted_by, a valid accepted_at timestamp, scope, and risk."
            }
            continue
        }
        if ($requiredForRelease -isnot [bool] -or [bool]$requiredForRelease -ne $mustGateRelease) {
            throw "$Context entry '$expectedId' must set required_for_release to the Boolean value $($mustGateRelease.ToString().ToLowerInvariant())."
        }
    }
}

if (-not $RepositoryRoot) {
    $RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
}
$RepositoryRoot = [IO.Path]::GetFullPath($RepositoryRoot).TrimEnd('\')
$gitCommit = Invoke-GitText -Arguments @('rev-parse', 'HEAD')
if ([string]::IsNullOrWhiteSpace($gitCommit) -or $gitCommit -notmatch '^[0-9a-fA-F]{40,64}$') {
    throw 'Unable to resolve the current Git commit for release reporting.'
}
$gitCommit = $gitCommit.ToLowerInvariant()
$InstallerPath = [IO.Path]::GetFullPath($InstallerPath)
if (-not (Test-Path -LiteralPath $InstallerPath -PathType Leaf)) {
    throw "Installer does not exist: $InstallerPath"
}
if (-not $OutputRoot) {
    $OutputRoot = Split-Path -Parent $InstallerPath
}
$OutputRoot = [IO.Path]::GetFullPath($OutputRoot).TrimEnd('\')
New-Item -ItemType Directory -Path $OutputRoot -Force | Out-Null

$expectedTag = "v$AppVersion"
$installerHash = (Get-FileHash -LiteralPath $InstallerPath -Algorithm SHA256).Hash.ToLowerInvariant()
$checksumPath = "$InstallerPath.sha256"
if (-not (Test-Path -LiteralPath $checksumPath -PathType Leaf)) {
    throw "Installer checksum sidecar is missing: $checksumPath"
}
$expectedChecksumLine = "$installerHash *$([IO.Path]::GetFileName($InstallerPath))"
$actualChecksumLine = ([IO.File]::ReadAllText($checksumPath)).Trim()
if ($actualChecksumLine -cne $expectedChecksumLine) {
    throw 'Installer checksum sidecar does not match the final installer bytes.'
}

$metadataPath = "$InstallerPath.release.json"
if (-not (Test-Path -LiteralPath $metadataPath -PathType Leaf)) {
    throw "Installer release metadata is missing: $metadataPath"
}
$installerMetadata = [IO.File]::ReadAllText($metadataPath) | ConvertFrom-Json
$installerSourceCommit = [string](Get-RequiredProperty $installerMetadata 'source_commit' 'Installer release metadata')
$installerSourceWorktreeClean = Get-RequiredProperty $installerMetadata 'source_worktree_clean' 'Installer release metadata'
if ((Get-RequiredProperty $installerMetadata 'product_version' 'Installer release metadata') -cne $AppVersion -or
    (Get-RequiredProperty $installerMetadata 'expected_git_tag' 'Installer release metadata') -cne $expectedTag -or
    (Get-RequiredProperty $installerMetadata 'sha256' 'Installer release metadata') -cne $installerHash) {
    throw 'Installer release metadata does not match the requested version, Git tag, and SHA-256.'
}
if ($installerSourceCommit -notmatch '^[0-9a-fA-F]{40,64}$' -or
    $installerSourceCommit.ToLowerInvariant() -cne $gitCommit) {
    throw 'Installer release metadata source_commit does not match the current Git commit.'
}
if ($installerSourceWorktreeClean -isnot [bool]) {
    throw 'Installer release metadata source_worktree_clean must be a Boolean value.'
}
if (-not [bool]$installerSourceWorktreeClean -and -not $AllowReleaseCandidate) {
    throw 'Installer was built from a dirty source worktree and cannot be used for a formal release.'
}
$distributionTarget = [string](Get-RequiredProperty $installerMetadata 'distribution_target' 'Installer release metadata')
$distributionClass = [string](Get-RequiredProperty $installerMetadata 'distribution_class' 'Installer release metadata')
if (($distributionTarget -eq 'internal_lan' -and $distributionClass -notin @('internal_lan_unsigned', 'internal_lan_signed')) -or
    ($distributionTarget -eq 'public_distribution' -and $distributionClass -ne 'public_distribution_signed') -or
    $distributionTarget -notin @('internal_lan', 'public_distribution')) {
    throw 'Installer distribution target and classification are inconsistent.'
}
$liveSignature = Get-AuthenticodeSignature -LiteralPath $InstallerPath
if ($distributionClass -in @('public_distribution_signed', 'internal_lan_signed')) {
    if ([string]$liveSignature.Status -ne 'Valid') {
        throw "Signed release metadata is inconsistent with live Authenticode verification: $($liveSignature.Status)"
    }
}
elseif ($distributionClass -eq 'internal_lan_unsigned') {
    if ([string]$liveSignature.Status -ne 'NotSigned') {
        throw "Internal unsigned metadata is inconsistent with live Authenticode verification: $($liveSignature.Status)"
    }
}
else {
    throw "Unknown installer distribution class: $distributionClass"
}

$apkArtifact = $null
if ($ApkPath) {
    $ApkPath = [IO.Path]::GetFullPath($ApkPath)
    if (-not (Test-Path -LiteralPath $ApkPath -PathType Leaf)) {
        throw "Android APK does not exist: $ApkPath"
    }
    $apkArtifact = [ordered]@{
        file = [IO.Path]::GetFileName($ApkPath)
        sha256 = (Get-FileHash -LiteralPath $ApkPath -Algorithm SHA256).Hash.ToLowerInvariant()
    }
}

$testMatrix = @()
$manualChecks = @()
$evidenceLoaded = $false
$fixedTestMatrixIds = @(
    'android_unit_and_room',
    'android_lint_and_release_apk',
    'backend_pytest',
    'contract_validation',
    'web_unit_build_e2e',
    'installer_static',
    'release_artifact_secret_scan'
)
$fixedManualCheckIds = @(
    'real_station_tag_closed_loop',
    'v2_packaged_android_network_smoke',
    'windows_host_upgrade_rehearsal',
    'windows_sandbox_upgrade_rehearsal',
    'windows_reboot_autostart',
    'two_android_client_sync',
    'fault_injection_recovery',
    'provider_credentials_rotated',
    'scale_acceptance_10_phones_2000_tags'
)
$nonGatingManualCheckIds = @('scale_acceptance_10_phones_2000_tags')
if ($EvidencePath) {
    $EvidencePath = [IO.Path]::GetFullPath($EvidencePath)
    if (-not (Test-Path -LiteralPath $EvidencePath -PathType Leaf)) {
        throw "Release evidence file does not exist: $EvidencePath"
    }
    $evidence = [IO.File]::ReadAllText($EvidencePath) | ConvertFrom-Json
    if ([int](Get-RequiredProperty $evidence 'schema_version' 'Release evidence') -ne 1 -or
        [string](Get-RequiredProperty $evidence 'product_version' 'Release evidence') -cne $AppVersion -or
        [string](Get-RequiredProperty $evidence 'expected_git_tag' 'Release evidence') -cne $expectedTag -or
        [string](Get-RequiredProperty $evidence 'distribution_target' 'Release evidence') -cne $distributionTarget) {
        throw 'Release evidence schema, product version, expected Git tag, or distribution target does not match this build.'
    }
    $testMatrix = @(Get-RequiredProperty $evidence 'test_matrix' 'Release evidence')
    $manualChecks = @(Get-RequiredProperty $evidence 'manual_checks' 'Release evidence')
    $evidenceLoaded = $true
}

$allowedStatuses = @('passed', 'failed', 'pending', 'not_run', 'external')
foreach ($entry in @($testMatrix) + @($manualChecks)) {
    $id = [string](Get-RequiredProperty $entry 'id' 'Release evidence entry')
    $status = [string](Get-RequiredProperty $entry 'status' "Release evidence entry '$id'")
    $requiredForRelease = Get-RequiredProperty $entry 'required_for_release' "Release evidence entry '$id'"
    if ($status -notin $allowedStatuses) {
        throw "Release evidence entry '$id' has unsupported status '$status'."
    }
    if ($requiredForRelease -isnot [bool]) {
        throw "Release evidence entry '$id' must use a Boolean required_for_release value."
    }
}

if ($evidenceLoaded) {
    Assert-FixedEvidenceCollection `
        -Entries $testMatrix `
        -ExpectedIds $fixedTestMatrixIds `
        -Context 'Release evidence test_matrix'
    Assert-FixedEvidenceCollection `
        -Entries $manualChecks `
        -ExpectedIds $fixedManualCheckIds `
        -Context 'Release evidence manual_checks' `
        -NonGatingIds $nonGatingManualCheckIds `
        -WaivableIds @('provider_credentials_rotated')

    $scaleCheck = @($manualChecks | Where-Object { [string]$_.id -ceq 'scale_acceptance_10_phones_2000_tags' })[0]
    if ([string](Get-RequiredProperty $scaleCheck 'owner' "Release evidence entry 'scale_acceptance_10_phones_2000_tags'") -cne 'user') {
        throw 'The 10-phone/2000-tag scale acceptance must remain an external user-owned, non-release-gating check.'
    }
    $providerCheck = @($manualChecks | Where-Object { [string]$_.id -ceq 'provider_credentials_rotated' })[0]
    if (-not [bool]$providerCheck.required_for_release -and $distributionTarget -cne 'internal_lan') {
        throw 'Provider credential revocation verification may be waived only for an internal_lan release.'
    }
}

$gitTags = @(Invoke-GitText -Arguments @('tag', '--points-at', 'HEAD') -split "`n" | Where-Object { $_ })
$gitStatus = Invoke-GitText -Arguments @('status', '--porcelain')
$gitClean = $null -ne $gitStatus -and [string]::IsNullOrWhiteSpace($gitStatus)
$expectedTagAtHead = $gitTags -contains $expectedTag

$requiredEvidence = @(@($testMatrix) + @($manualChecks) | Where-Object { [bool]$_.required_for_release })
$requiredEvidencePassed = $requiredEvidence.Count -gt 0 -and
    @($requiredEvidence | Where-Object { [string]$_.status -ne 'passed' }).Count -eq 0
$signatureGatePassed = if ($distributionTarget -eq 'public_distribution') {
    $distributionClass -eq 'public_distribution_signed' -and [string]$liveSignature.Status -eq 'Valid'
}
else {
    $distributionTarget -eq 'internal_lan' -and
        $distributionClass -in @('internal_lan_unsigned', 'internal_lan_signed')
}
$releaseReady = $evidenceLoaded -and
    $null -ne $apkArtifact -and
    $signatureGatePassed -and
    [bool]$installerSourceWorktreeClean -and
    $gitClean -and
    $expectedTagAtHead -and
    $requiredEvidencePassed
$releaseMode = if ($AllowReleaseCandidate) { 'candidate' } else { 'release' }

$report = [ordered]@{
    schema_version = 1
    generated_at_utc = [DateTime]::UtcNow.ToString('o')
    product_version = $AppVersion
    expected_git_tag = $expectedTag
    distribution_target = $distributionTarget
    release_mode = $releaseMode
    git = [ordered]@{
        commit = $gitCommit
        tags_at_head = $gitTags
        worktree_clean = $gitClean
        expected_tag_at_head = $expectedTagAtHead
    }
    artifacts = [ordered]@{
        android_apk = $apkArtifact
        windows_installer = [ordered]@{
            file = [IO.Path]::GetFileName($InstallerPath)
            sha256 = $installerHash
            source_commit = $installerSourceCommit.ToLowerInvariant()
            source_worktree_clean = [bool]$installerSourceWorktreeClean
            distribution_class = $distributionClass
            authenticode_status = [string]$liveSignature.Status
            signer_subject = if ($liveSignature.SignerCertificate) { $liveSignature.SignerCertificate.Subject } else { $null }
            signer_thumbprint = if ($liveSignature.SignerCertificate) { $liveSignature.SignerCertificate.Thumbprint } else { $null }
            timestamped = $null -ne $liveSignature.TimeStamperCertificate
        }
    }
    test_matrix = $testMatrix
    manual_checks = $manualChecks
    release_ready = $releaseReady
    scale_acceptance = [ordered]@{
        owner = 'user'
        scope = '10 real Android phones and 2000 real light strips'
        release_gate = $false
    }
}

$jsonPath = Join-Path $OutputRoot "HighTacPlatform-$AppVersion-release-report.json"
[IO.File]::WriteAllText(
    $jsonPath,
    (($report | ConvertTo-Json -Depth 10) + "`n"),
    (New-Object Text.UTF8Encoding($false))
)

$markdown = New-Object Text.StringBuilder
[void]$markdown.AppendLine("# HighTac Platform $AppVersion Release Report")
[void]$markdown.AppendLine()
[void]$markdown.AppendLine("- Expected Git tag: ``$expectedTag``")
[void]$markdown.AppendLine("- Distribution target: ``$distributionTarget``")
[void]$markdown.AppendLine("- Release mode: ``$releaseMode``")
[void]$markdown.AppendLine("- Git commit: ``$gitCommit``")
[void]$markdown.AppendLine("- Clean tagged commit: ``$($gitClean -and $expectedTagAtHead)``")
[void]$markdown.AppendLine("- Release ready: ``$releaseReady``")
[void]$markdown.AppendLine()
[void]$markdown.AppendLine('## Artifacts')
[void]$markdown.AppendLine()
[void]$markdown.AppendLine('| Artifact | SHA-256 | Signature/classification |')
[void]$markdown.AppendLine('| --- | --- | --- |')
if ($apkArtifact) {
    [void]$markdown.AppendLine("| $($apkArtifact.file) | ``$($apkArtifact.sha256)`` | Verify Android release signing in the test matrix |")
}
else {
    [void]$markdown.AppendLine('| Android APK | MISSING | Not ready |')
}
[void]$markdown.AppendLine("| $([IO.Path]::GetFileName($InstallerPath)) | ``$installerHash`` | $distributionClass / $($liveSignature.Status) |")
[void]$markdown.AppendLine()
[void]$markdown.AppendLine('## Test Matrix')
[void]$markdown.AppendLine()
[void]$markdown.AppendLine('| ID | Status | Required | Command/evidence |')
[void]$markdown.AppendLine('| --- | --- | --- | --- |')
foreach ($entry in $testMatrix) {
    [void]$markdown.AppendLine("| $(ConvertTo-MarkdownCell $entry.id) | $(ConvertTo-MarkdownCell $entry.status) | $([bool]$entry.required_for_release) | $(ConvertTo-MarkdownCell $entry.evidence) |")
}
[void]$markdown.AppendLine()
[void]$markdown.AppendLine('## Manual Checks')
[void]$markdown.AppendLine()
[void]$markdown.AppendLine('| ID | Status | Required | Owner | Evidence |')
[void]$markdown.AppendLine('| --- | --- | --- | --- | --- |')
foreach ($entry in $manualChecks) {
    [void]$markdown.AppendLine("| $(ConvertTo-MarkdownCell $entry.id) | $(ConvertTo-MarkdownCell $entry.status) | $([bool]$entry.required_for_release) | $(ConvertTo-MarkdownCell $entry.owner) | $(ConvertTo-MarkdownCell $entry.evidence) |")
}
[void]$markdown.AppendLine()
[void]$markdown.AppendLine('The 10-phone/2000-light-strip field acceptance remains user-owned and outside the release gate. Provider-console revocation verification is separately recorded as an explicit internal-LAN user risk waiver, not as a completed check.')

$markdownPath = Join-Path $OutputRoot "HighTacPlatform-$AppVersion-release-report.md"
[IO.File]::WriteAllText(
    $markdownPath,
    $markdown.ToString(),
    (New-Object Text.UTF8Encoding($false))
)

$result = [pscustomobject]@{
    JsonReport = $jsonPath
    MarkdownReport = $markdownPath
    ReleaseReady = $releaseReady
    ReleaseMode = $releaseMode
}

if (-not $releaseReady -and -not $AllowReleaseCandidate) {
    throw "Release report is not release-ready. Review '$jsonPath'. Use -AllowReleaseCandidate only for an explicitly non-final candidate build."
}

$result
