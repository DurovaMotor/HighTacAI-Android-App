[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'Medium')]
param(
    [Parameter(Mandatory = $true)]
    [string]$BaselineInstaller,

    [Parameter(Mandatory = $true)]
    [string]$CandidateInstaller,

    [string]$RepositoryRoot,

    [string]$OutputRoot,

    [switch]$Launch
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-RehearsalFileSha256 {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    $stream = [IO.File]::OpenRead($Path)
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try {
        return ([BitConverter]::ToString($algorithm.ComputeHash($stream)) -replace '-', '').ToLowerInvariant()
    }
    finally {
        $algorithm.Dispose()
        $stream.Dispose()
    }
}

function Get-RunningWindowsSandboxProcess {
    [CmdletBinding()]
    param()

    $sandboxProcessNames = @(
        'WindowsSandbox',
        'WindowsSandboxRemoteSession',
        'WindowsSandboxServer',
        'vmmemWindowsSandbox'
    )
    return @(Get-Process -ErrorAction SilentlyContinue | Where-Object {
        $_.ProcessName -in $sandboxProcessNames
    })
}

function Get-RehearsalRequiredJsonValue {
    param(
        [Parameter(Mandatory = $true)]
        [object]$Json,

        [Parameter(Mandatory = $true)]
        [string]$Name,

        [Parameter(Mandatory = $true)]
        [string]$SidecarPath
    )

    $property = $Json.PSObject.Properties[$Name]
    if ($null -eq $property -or [string]::IsNullOrWhiteSpace([string]$property.Value)) {
        throw "Installer release sidecar is missing '$Name': $SidecarPath"
    }
    return [string]$property.Value
}

function Assert-RehearsalInstallerSidecars {
    param(
        [Parameter(Mandatory = $true)]
        [string]$InstallerPath,

        [Parameter(Mandatory = $true)]
        [string]$ExpectedVersion,

        [Parameter(Mandatory = $true)]
        [string]$InstallerSha256
    )

    $artifactName = [IO.Path]::GetFileName($InstallerPath)
    $checksumPath = "$InstallerPath.sha256"
    if (-not (Test-Path -LiteralPath $checksumPath -PathType Leaf)) {
        throw "Installer checksum sidecar is required: $checksumPath"
    }
    $checksumPresent = $true
    if ($checksumPresent) {
        $checksumLines = @([IO.File]::ReadAllLines($checksumPath) | Where-Object {
            -not [string]::IsNullOrWhiteSpace($_)
        })
        if ($checksumLines.Count -ne 1 -or
            $checksumLines[0] -notmatch '^(?<Hash>[0-9A-Fa-f]{64})[ \t]+\*?(?<FileName>[^\r\n]+)$') {
            throw "Installer checksum sidecar has an invalid format: $checksumPath"
        }
        $sidecarHash = $Matches['Hash'].ToLowerInvariant()
        $sidecarArtifact = $Matches['FileName'].Trim()
        if ($sidecarHash -cne $InstallerSha256 -or
            $sidecarArtifact -cne $artifactName -or
            [IO.Path]::GetFileName($sidecarArtifact) -cne $sidecarArtifact) {
            throw "Installer checksum sidecar does not match the installer artifact: $checksumPath"
        }
    }

    $releasePath = "$InstallerPath.release.json"
    if (-not (Test-Path -LiteralPath $releasePath -PathType Leaf)) {
        throw "Installer release sidecar is required: $releasePath"
    }
    $releasePresent = $true
    if ($releasePresent) {
        try {
            $release = [IO.File]::ReadAllText($releasePath) | ConvertFrom-Json
        }
        catch {
            throw "Installer release sidecar is not valid JSON: $releasePath"
        }
        $releaseVersion = Get-RehearsalRequiredJsonValue -Json $release -Name 'product_version' -SidecarPath $releasePath
        $releaseTag = Get-RehearsalRequiredJsonValue -Json $release -Name 'expected_git_tag' -SidecarPath $releasePath
        $releaseCommit = (Get-RehearsalRequiredJsonValue -Json $release -Name 'source_commit' -SidecarPath $releasePath).ToLowerInvariant()
        $cleanProperty = $release.PSObject.Properties['source_worktree_clean']
        $releaseArtifact = Get-RehearsalRequiredJsonValue -Json $release -Name 'artifact' -SidecarPath $releasePath
        $releaseHash = (Get-RehearsalRequiredJsonValue -Json $release -Name 'sha256' -SidecarPath $releasePath).ToLowerInvariant()
        if ($releaseVersion -cne $ExpectedVersion -or
            $releaseTag -cne "v$ExpectedVersion" -or
            $releaseCommit -notmatch '^[0-9a-f]{40,64}$' -or
            $null -eq $cleanProperty -or
            $cleanProperty.Value -isnot [bool] -or
            -not [bool]$cleanProperty.Value -or
            $releaseArtifact -cne $artifactName -or
            $releaseHash -cne $InstallerSha256) {
            throw "Installer release sidecar does not match the installer artifact: $releasePath"
        }
    }

    return [pscustomobject]@{
        ChecksumPresent = [bool]$checksumPresent
        ReleaseMetadataPresent = [bool]$releasePresent
        SourceCommit = $releaseCommit
    }
}

function Get-RehearsalInstallerArtifact {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,

        [Parameter(Mandatory = $true)]
        [string]$ExpectedVersion,

        [Parameter(Mandatory = $true)]
        [string]$Role
    )

    $fullPath = [IO.Path]::GetFullPath($Path)
    if (-not (Test-Path -LiteralPath $fullPath -PathType Leaf)) {
        throw "$Role installer artifact was not found: $fullPath"
    }
    $item = Get-Item -LiteralPath $fullPath -Force
    if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "$Role installer artifact cannot be a reparse point: $fullPath"
    }

    $productVersion = ([string][Diagnostics.FileVersionInfo]::GetVersionInfo($fullPath).ProductVersion).Trim()
    if ($productVersion -cne $ExpectedVersion) {
        throw "$Role installer ProductVersion must be $ExpectedVersion; found '$productVersion'."
    }
    $sha256 = Get-RehearsalFileSha256 -Path $fullPath
    $sidecars = Assert-RehearsalInstallerSidecars `
        -InstallerPath $fullPath `
        -ExpectedVersion $ExpectedVersion `
        -InstallerSha256 $sha256

    return [pscustomobject]@{
        Path = $fullPath
        ProductVersion = $productVersion
        Sha256 = $sha256
        ChecksumSidecarPresent = $sidecars.ChecksumPresent
        ReleaseSidecarPresent = $sidecars.ReleaseMetadataPresent
        SourceCommit = $sidecars.SourceCommit
    }
}

function Get-RehearsalInstallerPair {
    param(
        [Parameter(Mandatory = $true)]
        [string]$BaselinePath,

        [Parameter(Mandatory = $true)]
        [string]$CandidatePath,

        [Parameter(Mandatory = $true)]
        [string]$BaselineVersion,

        [Parameter(Mandatory = $true)]
        [string]$CandidateVersion
    )

    $baselineFullPath = [IO.Path]::GetFullPath($BaselinePath)
    $candidateFullPath = [IO.Path]::GetFullPath($CandidatePath)
    if ([string]::Equals($baselineFullPath, $candidateFullPath, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Baseline and candidate installers must be different files.'
    }

    $baseline = Get-RehearsalInstallerArtifact -Path $baselineFullPath -ExpectedVersion $BaselineVersion -Role 'Baseline'
    $candidate = Get-RehearsalInstallerArtifact -Path $candidateFullPath -ExpectedVersion $CandidateVersion -Role 'Candidate'
    if ($baseline.Sha256 -ceq $candidate.Sha256) {
        throw 'Baseline and candidate installers must have different SHA-256 hashes.'
    }
    return [pscustomobject]@{
        Baseline = $baseline
        Candidate = $candidate
    }
}

if ($env:OS -ne 'Windows_NT') {
    throw 'Windows Sandbox rehearsal can only be prepared on Windows.'
}
if (-not $RepositoryRoot) {
    $RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
}
$RepositoryRoot = [IO.Path]::GetFullPath($RepositoryRoot).TrimEnd('\')
$installerPair = Get-RehearsalInstallerPair `
    -BaselinePath $BaselineInstaller `
    -CandidatePath $CandidateInstaller `
    -BaselineVersion '1.0.0' `
    -CandidateVersion '2.0.0'
$BaselineInstaller = $installerPair.Baseline.Path
$CandidateInstaller = $installerPair.Candidate.Path

$allowedBuildRoot = [IO.Path]::GetFullPath((Join-Path $RepositoryRoot 'installer\build')).TrimEnd('\')
if (-not $OutputRoot) {
    $OutputRoot = Join-Path $allowedBuildRoot 'rehearsal-output'
}
$OutputRoot = [IO.Path]::GetFullPath($OutputRoot).TrimEnd('\')
if (-not $OutputRoot.StartsWith($allowedBuildRoot + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw "OutputRoot must stay under '$allowedBuildRoot'."
}

$sandboxExecutable = @(
    (Join-Path $env:SystemRoot 'System32\WindowsSandbox.exe'),
    (Join-Path $env:SystemRoot 'System32\WindowsSandboxClient.exe')
) | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
if (-not $sandboxExecutable) {
    throw 'Windows Sandbox is not available. Enable the Windows optional feature Containers-DisposableClientVM, then reboot.'
}

$bundleRoot = Join-Path $allowedBuildRoot 'rehearsal-bundle'
$wsbPath = Join-Path $allowedBuildRoot 'HighTac-Platform-Rehearsal.wsb'
if (-not $PSCmdlet.ShouldProcess($bundleRoot, 'Prepare isolated Windows Sandbox rehearsal bundle and configuration')) {
    return
}

if (Test-Path -LiteralPath $bundleRoot) {
    Remove-Item -LiteralPath $bundleRoot -Recurse -Force
}
New-Item -ItemType Directory -Path $bundleRoot, $OutputRoot -Force | Out-Null
$bundleBaseline = Join-Path $bundleRoot 'baseline-installer.exe'
$bundleCandidate = Join-Path $bundleRoot 'candidate-installer.exe'
Copy-Item -LiteralPath $BaselineInstaller -Destination $bundleBaseline -Force
Copy-Item -LiteralPath $CandidateInstaller -Destination $bundleCandidate -Force
if ((Get-RehearsalFileSha256 -Path $bundleBaseline) -cne
        $installerPair.Baseline.Sha256 -or
    (Get-RehearsalFileSha256 -Path $bundleCandidate) -cne
        $installerPair.Candidate.Sha256) {
    throw 'Installer bytes changed while the Sandbox rehearsal bundle was being prepared.'
}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'Run-HighTacSandboxRehearsal.ps1') `
    -Destination (Join-Path $bundleRoot 'Run-HighTacSandboxRehearsal.ps1') -Force

$bundleXml = [Security.SecurityElement]::Escape($bundleRoot)
$outputXml = [Security.SecurityElement]::Escape($OutputRoot)
$configuration = @"
<Configuration>
  <Networking>Enable</Networking>
  <ProtectedClient>Enable</ProtectedClient>
  <ClipboardRedirection>Disable</ClipboardRedirection>
  <PrinterRedirection>Disable</PrinterRedirection>
  <AudioInput>Disable</AudioInput>
  <VideoInput>Disable</VideoInput>
  <MappedFolders>
    <MappedFolder>
      <HostFolder>$bundleXml</HostFolder>
      <SandboxFolder>C:\HighTacRehearsal</SandboxFolder>
      <ReadOnly>true</ReadOnly>
    </MappedFolder>
    <MappedFolder>
      <HostFolder>$outputXml</HostFolder>
      <SandboxFolder>C:\HighTacRehearsalOutput</SandboxFolder>
      <ReadOnly>false</ReadOnly>
    </MappedFolder>
  </MappedFolders>
  <LogonCommand>
    <Command>powershell.exe -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File C:\HighTacRehearsal\Run-HighTacSandboxRehearsal.ps1 -BaselineInstaller C:\HighTacRehearsal\baseline-installer.exe -CandidateInstaller C:\HighTacRehearsal\candidate-installer.exe -ExpectedBaselineSha256 $($installerPair.Baseline.Sha256) -ExpectedCandidateSha256 $($installerPair.Candidate.Sha256) -OutputRoot C:\HighTacRehearsalOutput</Command>
  </LogonCommand>
</Configuration>
"@
[IO.File]::WriteAllText($wsbPath, $configuration, (New-Object Text.UTF8Encoding($false)))

$result = [pscustomobject]@{
    SandboxExecutable = $sandboxExecutable
    Configuration = $wsbPath
    Bundle = $bundleRoot
    ReportDirectory = $OutputRoot
    RepositoryMapped = $false
    LegacyPasswordFileMapped = $false
    BaselineInstallerSha256 = $installerPair.Baseline.Sha256
    CandidateInstallerSha256 = $installerPair.Candidate.Sha256
    BaselineSourceCommit = $installerPair.Baseline.SourceCommit
    CandidateSourceCommit = $installerPair.Candidate.SourceCommit
    Launched = [bool]$Launch
}
if ($Launch) {
    $runningSandboxProcesses = @(Get-RunningWindowsSandboxProcess)
    if ($runningSandboxProcesses.Count -gt 0) {
        $processSummary = ($runningSandboxProcesses | ForEach-Object {
            "$($_.ProcessName):$($_.Id)"
        }) -join ', '
        throw "A Windows Sandbox instance is already running; refusing to launch another one: $processSummary"
    }
    Start-Process -FilePath $sandboxExecutable -ArgumentList @($wsbPath) | Out-Null
}
$result
