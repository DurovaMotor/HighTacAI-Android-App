[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'Medium')]
param(
    [ValidatePattern('^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$')]
    [string]$AppVersion = '2.0.0',

    [string]$MosquittoVersion = '2.1.2',

    [string]$WinSWVersion = '2.12.0',

    [string]$VcRedistVersion = '14.44.35211.0',

    [string]$RepositoryRoot,

    [string]$PythonExecutable,

    [string]$MosquittoRoot,

    [string]$WinSWExecutable,

    [string]$WinSWLicenseFile,

    [string]$VcRedistExecutable,

    [string]$ProductLicenseFile,

    [string]$IsccPath,

    [string]$AndroidApkPath,

    [string]$ReleaseEvidencePath,

    [string]$SigningPfxPath,

    [Security.SecureString]$SigningPfxPassword,

    [string]$SigningCertificateThumbprint,

    [ValidateSet('CurrentUser', 'LocalMachine')]
    [string]$SigningCertificateStore = 'CurrentUser',

    [string]$TimestampServer = 'http://timestamp.digicert.com',

    [ValidateSet('internal_lan', 'public_distribution')]
    [string]$DistributionTarget = 'internal_lan',

    [switch]$RequireSignedInstaller,

    [string]$LegacyPasswordFileForScan,

    [switch]$SkipNpmCi,

    [switch]$AllowReleaseCandidate,

    [switch]$ProbeOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not $RepositoryRoot) {
    $RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
}
$RepositoryRoot = [IO.Path]::GetFullPath($RepositoryRoot).TrimEnd('\')
$buildRoot = Join-Path $RepositoryRoot 'installer\build'

function Select-ExistingFile {
    param([string[]]$Candidates)

    return $Candidates | Where-Object {
        $_ -and (Test-Path -LiteralPath $_ -PathType Leaf)
    } | Select-Object -First 1
}

if (-not $PythonExecutable) {
    $pythonCandidates = @(
        (Join-Path $RepositoryRoot 'server\.venv\Scripts\python.exe'),
        (Join-Path $env:LOCALAPPDATA 'Programs\Python\Python312\python.exe')
    )
    $PythonExecutable = Select-ExistingFile -Candidates $pythonCandidates
}
if (-not $MosquittoRoot) {
    $MosquittoRoot = Join-Path $env:ProgramFiles 'Mosquitto'
}
if (-not $WinSWExecutable) {
    $WinSWExecutable = Get-ChildItem -LiteralPath (Join-Path $buildRoot 'vendor') `
        -Recurse -File -Filter 'WinSW-x64.exe' -ErrorAction SilentlyContinue |
        Sort-Object FullName |
        Select-Object -ExpandProperty FullName -First 1
}
if (-not $WinSWLicenseFile -and $WinSWExecutable) {
    $WinSWLicenseFile = Select-ExistingFile -Candidates @(
        (Join-Path (Split-Path -Parent $WinSWExecutable) 'LICENSE.txt'),
        (Join-Path (Split-Path -Parent $WinSWExecutable) 'LICENSE')
    )
}
if (-not $VcRedistExecutable) {
    $vcCandidates = @(Get-ChildItem -LiteralPath 'C:\ProgramData\Package Cache' `
        -Recurse -File -Filter 'VC_redist.x64.exe' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTimeUtc -Descending |
        Select-Object -ExpandProperty FullName)
    $VcRedistExecutable = Select-ExistingFile -Candidates $vcCandidates
}
if (-not $IsccPath) {
    $isccCandidates = @(
        (Join-Path $env:LOCALAPPDATA 'Programs\Inno Setup 6\ISCC.exe'),
        (Join-Path ${env:ProgramFiles(x86)} 'Inno Setup 6\ISCC.exe'),
        (Join-Path $env:ProgramFiles 'Inno Setup 6\ISCC.exe')
    )
    $IsccPath = Select-ExistingFile -Candidates $isccCandidates
}
if (-not $LegacyPasswordFileForScan) {
    $legacyCandidate = Join-Path $RepositoryRoot 'tools\mqtt\runtime\config\passwordfile'
    if (Test-Path -LiteralPath $legacyCandidate -PathType Leaf) {
        $LegacyPasswordFileForScan = $legacyCandidate
    }
}
if (-not $AndroidApkPath) {
    $releaseApkCandidate = Join-Path $RepositoryRoot 'app\build\outputs\apk\release\app-release.apk'
    if (Test-Path -LiteralPath $releaseApkCandidate -PathType Leaf) {
        $AndroidApkPath = $releaseApkCandidate
    }
}
if (-not $ReleaseEvidencePath) {
    $evidenceCandidates = @(
        (Join-Path $RepositoryRoot "docs\releases\v$AppVersion-$DistributionTarget-evidence.json")
    )
    if ($DistributionTarget -eq 'internal_lan') {
        $evidenceCandidates += Join-Path $RepositoryRoot "docs\releases\v$AppVersion-evidence.json"
    }
    foreach ($evidenceCandidate in $evidenceCandidates) {
        if (Test-Path -LiteralPath $evidenceCandidate -PathType Leaf) {
            $ReleaseEvidencePath = $evidenceCandidate
            break
        }
    }
}

$missing = New-Object 'System.Collections.Generic.List[string]'
foreach ($input in @(
    [pscustomobject]@{ Name = 'Python 3.12 with PyInstaller'; Path = $PythonExecutable; Type = 'File' },
    [pscustomobject]@{ Name = 'Mosquitto root'; Path = $MosquittoRoot; Type = 'Directory' },
    [pscustomobject]@{ Name = 'WinSW x64'; Path = $WinSWExecutable; Type = 'File' },
    [pscustomobject]@{ Name = 'WinSW license'; Path = $WinSWLicenseFile; Type = 'File' },
    [pscustomobject]@{ Name = 'VC++ x64 redistributable'; Path = $VcRedistExecutable; Type = 'File' },
    [pscustomobject]@{ Name = 'Inno Setup compiler'; Path = $IsccPath; Type = 'File' }
)) {
    $exists = if ($input.Type -eq 'Directory') {
        $input.Path -and (Test-Path -LiteralPath $input.Path -PathType Container)
    }
    else {
        $input.Path -and (Test-Path -LiteralPath $input.Path -PathType Leaf)
    }
    if (-not $exists) {
        $missing.Add($input.Name)
    }
}
if ($ProductLicenseFile -and -not (Test-Path -LiteralPath $ProductLicenseFile -PathType Leaf)) {
    $missing.Add('Optional product license path was supplied but does not exist')
}
if ($AndroidApkPath -and -not (Test-Path -LiteralPath $AndroidApkPath -PathType Leaf)) {
    $missing.Add('Optional Android APK path was supplied but does not exist')
}
if ($ReleaseEvidencePath -and -not (Test-Path -LiteralPath $ReleaseEvidencePath -PathType Leaf)) {
    $missing.Add('Optional release evidence path was supplied but does not exist')
}
if ($SigningPfxPath -and $SigningCertificateThumbprint) {
    $missing.Add('Choose either a signing PFX or an installed certificate thumbprint, not both')
}
if ($SigningPfxPath -and -not (Test-Path -LiteralPath $SigningPfxPath -PathType Leaf)) {
    $missing.Add('Signing PFX path was supplied but does not exist')
}
if ($SigningPfxPath -and -not $SigningPfxPassword) {
    $missing.Add('Signing PFX password SecureString')
}
$signingConfigured = [bool]$SigningPfxPath -or [bool]$SigningCertificateThumbprint
$signatureRequired = $RequireSignedInstaller -or $DistributionTarget -eq 'public_distribution'
if ($signatureRequired -and -not $signingConfigured) {
    $missing.Add('Authenticode signing input required by the selected distribution target or RequireSignedInstaller')
}

$pythonVersion = $null
$pyInstallerVersion = $null
if ($PythonExecutable -and (Test-Path -LiteralPath $PythonExecutable -PathType Leaf)) {
    # Keep this -c payload quote-free: Windows PowerShell 5.1 rewrites embedded
    # quotes in native arguments differently from PowerShell 7.
    $pythonProbe = & $PythonExecutable -c 'import sys;import PyInstaller;print(sys.version_info.major,sys.version_info.minor,sys.version_info.micro,sep=chr(46));print(PyInstaller.__version__)' 2>$null
    if ($LASTEXITCODE -eq 0 -and @($pythonProbe).Count -ge 2) {
        $pythonVersion = [string]$pythonProbe[0]
        $pyInstallerVersion = [string]$pythonProbe[1]
        if (-not $pythonVersion.StartsWith('3.12.')) {
            $missing.Add("Python 3.12 (detected $pythonVersion)")
        }
    }
    else {
        $missing.Add('PyInstaller in selected Python environment')
    }
}

$inventory = [pscustomobject]@{
    PythonExecutable = $PythonExecutable
    PythonVersion = $pythonVersion
    PyInstallerVersion = $pyInstallerVersion
    MosquittoRoot = $MosquittoRoot
    MosquittoVersion = $MosquittoVersion
    WinSWExecutable = $WinSWExecutable
    WinSWVersion = $WinSWVersion
    WinSWLicenseFile = $WinSWLicenseFile
    VcRedistExecutable = $VcRedistExecutable
    VcRedistVersion = $VcRedistVersion
    IsccPath = $IsccPath
    ProductLicenseIncluded = [bool]$ProductLicenseFile
    LegacyPasswordComparedDuringScan = [bool]$LegacyPasswordFileForScan
    AndroidApkIncludedInReport = [bool]$AndroidApkPath
    ReleaseEvidenceIncluded = [bool]$ReleaseEvidencePath
    ReleaseCandidateAllowed = [bool]$AllowReleaseCandidate
    DistributionTarget = $DistributionTarget
    InstallerSigningMode = if ($SigningPfxPath) { 'pfx' } elseif ($SigningCertificateThumbprint) { 'certificate_store' } else { 'none_internal_lan_unsigned' }
    SignedInstallerRequired = $signatureRequired
    Missing = @($missing)
}
if ($ProbeOnly) {
    return $inventory
}
if ($missing.Count -gt 0) {
    throw "Release build prerequisites are missing: $($missing -join ', ')"
}
if (-not $PSCmdlet.ShouldProcess($buildRoot, "Build complete HighTac Platform installer $AppVersion")) {
    return $inventory
}

$webRoot = Join-Path $buildRoot 'web-dist'
$platformRoot = Join-Path $buildRoot 'pyinstaller\dist\HighTacPlatform'
$payloadRoot = Join-Path $buildRoot 'payload'
$installerRoot = Join-Path $buildRoot 'installer'

& (Join-Path $PSScriptRoot 'Build-WebAssets.ps1') `
    -RepositoryRoot $RepositoryRoot `
    -OutputRoot $webRoot `
    -SkipNpmCi:$SkipNpmCi `
    -Confirm:$false | Out-Null

& (Join-Path $PSScriptRoot 'Build-Platform.ps1') `
    -RepositoryRoot $RepositoryRoot `
    -WebAssetsRoot $webRoot `
    -PythonCommand $PythonExecutable `
    -Confirm:$false | Out-Null

$payloadParameters = @{
    PlatformBuildRoot = $platformRoot
    MosquittoRoot = $MosquittoRoot
    WinSWExecutable = $WinSWExecutable
    WinSWLicenseFile = $WinSWLicenseFile
    VcRedistExecutable = $VcRedistExecutable
    AppVersion = $AppVersion
    MosquittoVersion = $MosquittoVersion
    WinSWVersion = $WinSWVersion
    VcRedistVersion = $VcRedistVersion
    RepositoryRoot = $RepositoryRoot
    OutputRoot = $payloadRoot
    Confirm = $false
}
if ($ProductLicenseFile) {
    $payloadParameters.ProductLicenseFile = $ProductLicenseFile
}
& (Join-Path $PSScriptRoot 'New-ReleasePayload.ps1') @payloadParameters | Out-Null

$scanParameters = @{ Root = $payloadRoot }
if ($LegacyPasswordFileForScan) {
    $scanParameters.LegacyPasswordFile = $LegacyPasswordFileForScan
}
$scan = & (Join-Path $RepositoryRoot 'installer\tests\Test-ReleaseArtifacts.ps1') @scanParameters

$installerParameters = @{
    PayloadRoot = $payloadRoot
    AppVersion = $AppVersion
    RepositoryRoot = $RepositoryRoot
    OutputRoot = $installerRoot
    IsccPath = $IsccPath
    SigningCertificateStore = $SigningCertificateStore
    TimestampServer = $TimestampServer
    DistributionTarget = $DistributionTarget
    RequireSignedInstaller = $RequireSignedInstaller
    Confirm = $false
}
if ($SigningPfxPath) {
    $installerParameters.SigningPfxPath = $SigningPfxPath
    $installerParameters.SigningPfxPassword = $SigningPfxPassword
}
if ($SigningCertificateThumbprint) {
    $installerParameters.SigningCertificateThumbprint = $SigningCertificateThumbprint
}
$installer = & (Join-Path $PSScriptRoot 'Build-Installer.ps1') @installerParameters

if ($null -eq $installer -or
    $null -eq $installer.PSObject.Properties['Installer'] -or
    $null -eq $installer.PSObject.Properties['Sha256'] -or
    $null -eq $installer.PSObject.Properties['ChecksumFile'] -or
    $null -eq $installer.PSObject.Properties['ReleaseMetadataFile'] -or
    $null -eq $installer.PSObject.Properties['DistributionTarget'] -or
    $null -eq $installer.PSObject.Properties['DistributionClass'] -or
    $null -eq $installer.PSObject.Properties['AuthenticodeStatus']) {
    throw 'Build-Installer.ps1 did not return the expected installer artifact contract.'
}

$reportParameters = @{
    AppVersion = $AppVersion
    InstallerPath = $installer.Installer
    RepositoryRoot = $RepositoryRoot
    OutputRoot = $installerRoot
    AllowReleaseCandidate = [bool]$AllowReleaseCandidate
}
if ($AndroidApkPath) {
    $reportParameters.ApkPath = $AndroidApkPath
}
if ($ReleaseEvidencePath) {
    $reportParameters.EvidencePath = $ReleaseEvidencePath
}
$releaseReport = & (Join-Path $PSScriptRoot 'New-ReleaseReport.ps1') @reportParameters
if (-not $releaseReport.ReleaseReady -and -not $AllowReleaseCandidate) {
    throw "Release build $AppVersion is not release-ready. Use -AllowReleaseCandidate only for an explicitly non-final candidate build."
}

[pscustomobject]@{
    Inventory = $inventory
    PayloadRoot = $payloadRoot
    ArtifactScan = $scan
    Installer = $installer.Installer
    InstallerSha256 = $installer.Sha256
    ChecksumFile = $installer.ChecksumFile
    ReleaseMetadataFile = $installer.ReleaseMetadataFile
    DistributionTarget = $installer.DistributionTarget
    DistributionClass = $installer.DistributionClass
    AuthenticodeStatus = $installer.AuthenticodeStatus
    ReleaseReportJson = $releaseReport.JsonReport
    ReleaseReportMarkdown = $releaseReport.MarkdownReport
    ReleaseReady = $releaseReport.ReleaseReady
    ReleaseMode = $releaseReport.ReleaseMode
}
