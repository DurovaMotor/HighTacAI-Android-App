[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'Medium')]
param(
    [Parameter(Mandatory = $true)]
    [string]$PlatformBuildRoot,

    [string]$MosquittoRoot = (Join-Path $env:ProgramFiles 'Mosquitto'),

    [Parameter(Mandatory = $true)]
    [string]$WinSWExecutable,

    [string]$ProductLicenseFile,

    [Parameter(Mandatory = $true)]
    [string]$WinSWLicenseFile,

    [Parameter(Mandatory = $true)]
    [string]$VcRedistExecutable,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$')]
    [string]$AppVersion,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$MosquittoVersion,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$WinSWVersion,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$VcRedistVersion,

    [string]$RepositoryRoot,

    [string]$OutputRoot
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not $RepositoryRoot) {
    $RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
}
$RepositoryRoot = [IO.Path]::GetFullPath($RepositoryRoot).TrimEnd('\')
if (-not $OutputRoot) {
    $OutputRoot = Join-Path $RepositoryRoot 'installer\build\payload'
}
$OutputRoot = [IO.Path]::GetFullPath($OutputRoot).TrimEnd('\')
$allowedBuildRoot = [IO.Path]::GetFullPath((Join-Path $RepositoryRoot 'installer\build')).TrimEnd('\')
if (-not $OutputRoot.StartsWith($allowedBuildRoot + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw "OutputRoot must stay under '$allowedBuildRoot' so cleanup cannot affect source files."
}

$PlatformBuildRoot = [IO.Path]::GetFullPath($PlatformBuildRoot).TrimEnd('\')
$MosquittoRoot = [IO.Path]::GetFullPath($MosquittoRoot).TrimEnd('\')
$WinSWExecutable = [IO.Path]::GetFullPath($WinSWExecutable)
$ProductLicenseFile = if ($ProductLicenseFile) { [IO.Path]::GetFullPath($ProductLicenseFile) } else { $null }
$WinSWLicenseFile = [IO.Path]::GetFullPath($WinSWLicenseFile)
$VcRedistExecutable = [IO.Path]::GetFullPath($VcRedistExecutable)

$requiredInputs = @(
    (Join-Path $PlatformBuildRoot 'HighTacPlatform.exe'),
    (Join-Path $MosquittoRoot 'mosquitto.exe'),
    (Join-Path $MosquittoRoot 'mosquitto_passwd.exe'),
    (Join-Path $MosquittoRoot 'epl-v20'),
    (Join-Path $MosquittoRoot 'edl-v10'),
    (Join-Path $MosquittoRoot 'NOTICE.md'),
    $WinSWExecutable,
    $WinSWLicenseFile,
    $VcRedistExecutable
)
if ($ProductLicenseFile) {
    $requiredInputs += $ProductLicenseFile
}
foreach ($inputPath in $requiredInputs) {
    if (-not (Test-Path -LiteralPath $inputPath -PathType Leaf)) {
        throw "Required release input is missing: $inputPath"
    }
}

$winSWFileName = [IO.Path]::GetFileName($WinSWExecutable)
if ($winSWFileName -notmatch '(?i)winsw.*x64.*\.exe$') {
    throw "WinSWExecutable must be the reviewed x64 WinSW binary; received '$winSWFileName'."
}
$vcRedistFileName = [IO.Path]::GetFileName($VcRedistExecutable)
if ($vcRedistFileName -notmatch '(?i)^vc_redist\.x64\.exe$') {
    throw "VcRedistExecutable must be the reviewed Microsoft x64 redistributable named VC_redist.x64.exe; received '$vcRedistFileName'."
}
$vcSignature = Get-AuthenticodeSignature -LiteralPath $VcRedistExecutable
if ($vcSignature.Status -ne 'Valid' -or $null -eq $vcSignature.SignerCertificate -or $vcSignature.SignerCertificate.Subject -notmatch 'Microsoft Corporation') {
    throw 'VC_redist.x64.exe must have a valid Microsoft Corporation Authenticode signature.'
}
$actualVcVersion = (Get-Item -LiteralPath $VcRedistExecutable).VersionInfo.ProductVersion
if ([string]$actualVcVersion -ne $VcRedistVersion) {
    throw "VcRedistVersion '$VcRedistVersion' does not match the signed executable product version '$actualVcVersion'."
}
$actualWinSWVersion = (Get-Item -LiteralPath $WinSWExecutable).VersionInfo.ProductVersion
if ($actualWinSWVersion -and -not ([string]$actualWinSWVersion).StartsWith($WinSWVersion)) {
    throw "WinSWVersion '$WinSWVersion' does not match the executable product version '$actualWinSWVersion'."
}
foreach ($licensePath in @($WinSWLicenseFile) + @($ProductLicenseFile | Where-Object { $_ })) {
    if ((Get-Item -LiteralPath $licensePath).Length -lt 128) {
        throw "License/redistribution input is unexpectedly short and must be reviewed: $licensePath"
    }
}

if (-not $PSCmdlet.ShouldProcess($OutputRoot, 'Compose release payload from application and external vendor inputs')) {
    return
}

if (Test-Path -LiteralPath $OutputRoot) {
    Remove-Item -LiteralPath $OutputRoot -Recurse -Force
}
foreach ($directory in @('server', 'mosquitto', 'service', 'tools', 'templates', 'licenses', 'dependencies')) {
    New-Item -ItemType Directory -Path (Join-Path $OutputRoot $directory) -Force | Out-Null
}

Get-ChildItem -LiteralPath $PlatformBuildRoot -Force | ForEach-Object {
    Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $OutputRoot 'server') -Recurse -Force
}

foreach ($vendorExecutable in @('mosquitto.exe', 'mosquitto_passwd.exe')) {
    Copy-Item -LiteralPath (Join-Path $MosquittoRoot $vendorExecutable) -Destination (Join-Path $OutputRoot 'mosquitto') -Force
}
Get-ChildItem -LiteralPath $MosquittoRoot -Filter '*.dll' -File | ForEach-Object {
    Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $OutputRoot 'mosquitto') -Force
}

Copy-Item -LiteralPath $WinSWExecutable -Destination (Join-Path $OutputRoot 'service\HighTacPlatform.exe') -Force
Copy-Item -LiteralPath $WinSWExecutable -Destination (Join-Path $OutputRoot 'service\HighTacMqttBroker.exe') -Force
Copy-Item -LiteralPath (Join-Path $RepositoryRoot 'installer\winsw\HighTacPlatform.xml') -Destination (Join-Path $OutputRoot 'service') -Force
Copy-Item -LiteralPath (Join-Path $RepositoryRoot 'installer\winsw\HighTacMqttBroker.xml') -Destination (Join-Path $OutputRoot 'service') -Force
Copy-Item -LiteralPath $VcRedistExecutable -Destination (Join-Path $OutputRoot 'dependencies\VC_redist.x64.exe') -Force

Get-ChildItem -LiteralPath (Join-Path $RepositoryRoot 'installer\windows') -File | Where-Object {
    $_.Extension -in '.ps1', '.psm1'
} | ForEach-Object {
    Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $OutputRoot 'tools') -Force
}
Get-ChildItem -LiteralPath (Join-Path $RepositoryRoot 'installer\config') -File | ForEach-Object {
    Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $OutputRoot 'templates') -Force
}

if ($ProductLicenseFile) {
    Copy-Item -LiteralPath $ProductLicenseFile -Destination (Join-Path $OutputRoot 'licenses\HighTac-Product-License.txt') -Force
}
Copy-Item -LiteralPath $WinSWLicenseFile -Destination (Join-Path $OutputRoot 'licenses\WinSW-License.txt') -Force
Copy-Item -LiteralPath (Join-Path $MosquittoRoot 'epl-v20') -Destination (Join-Path $OutputRoot 'licenses\Mosquitto-EPL-2.0.txt') -Force
Copy-Item -LiteralPath (Join-Path $MosquittoRoot 'edl-v10') -Destination (Join-Path $OutputRoot 'licenses\Mosquitto-EDL-1.0.txt') -Force
Copy-Item -LiteralPath (Join-Path $MosquittoRoot 'NOTICE.md') -Destination (Join-Path $OutputRoot 'licenses\Mosquitto-NOTICE.md') -Force

$noticesTemplate = [IO.File]::ReadAllText((Join-Path $RepositoryRoot 'installer\licenses\THIRD-PARTY-NOTICES.template.md'))
$notices = $noticesTemplate.Replace('__APP_VERSION__', $AppVersion)
$notices = $notices.Replace('__MOSQUITTO_VERSION__', $MosquittoVersion)
$notices = $notices.Replace('__WINSW_VERSION__', $WinSWVersion)
$notices = $notices.Replace('__VC_REDIST_VERSION__', $VcRedistVersion)
if ($notices -match '__[A-Z0-9_]+__') {
    throw 'Third-party notice generation left unresolved placeholders.'
}
$utf8WithoutBom = New-Object Text.UTF8Encoding($false)
[IO.File]::WriteAllText((Join-Path $OutputRoot 'licenses\THIRD-PARTY-NOTICES.md'), $notices, $utf8WithoutBom)

$vcHash = (Get-FileHash -LiteralPath $VcRedistExecutable -Algorithm SHA256).Hash.ToLowerInvariant()
$vcNotice = @"
Microsoft Visual C++ Redistributable component record

File: VC_redist.x64.exe
Product version: $VcRedistVersion
SHA-256: $vcHash
Authenticode signer: $($vcSignature.SignerCertificate.Subject)
Authenticode status at build time: $($vcSignature.Status)
Runtime role: native dependency for packaged Windows components

This file records build provenance only. It is not a license grant and does not
invent or replace Microsoft license terms. Microsoft terms govern use and
redistribution of this component; the release owner is responsible for ensuring
that the intended distribution is permitted.

Microsoft license terms: https://visualstudio.microsoft.com/license-terms/
Latest supported redistributables: https://learn.microsoft.com/cpp/windows/latest-supported-vc-redist
"@
[IO.File]::WriteAllText(
    (Join-Path $OutputRoot 'licenses\Microsoft-VC-Runtime-Notice.txt'),
    ($vcNotice.TrimEnd() + "`n"),
    $utf8WithoutBom
)

$buildManifest = [ordered]@{
    schema_version = 1
    application_version = $AppVersion
    product_license_included = [bool]$ProductLicenseFile
    components = @(
        [ordered]@{
            name = 'Eclipse Mosquitto'
            version = $MosquittoVersion
            executable = 'mosquitto/mosquitto.exe'
            sha256 = (Get-FileHash -LiteralPath (Join-Path $MosquittoRoot 'mosquitto.exe') -Algorithm SHA256).Hash.ToLowerInvariant()
        },
        [ordered]@{
            name = 'WinSW'
            version = $WinSWVersion
            executable = 'service/HighTacPlatform.exe'
            sha256 = (Get-FileHash -LiteralPath $WinSWExecutable -Algorithm SHA256).Hash.ToLowerInvariant()
        },
        [ordered]@{
            name = 'Microsoft Visual C++ Redistributable x64'
            version = $VcRedistVersion
            executable = 'dependencies/VC_redist.x64.exe'
            sha256 = $vcHash
            authenticode_status = [string]$vcSignature.Status
            signer = [string]$vcSignature.SignerCertificate.Subject
        }
    )
}
[IO.File]::WriteAllText(
    (Join-Path $OutputRoot 'BUILD-MANIFEST.json'),
    (($buildManifest | ConvertTo-Json -Depth 6) + "`n"),
    $utf8WithoutBom
)

Copy-Item -LiteralPath (Join-Path $RepositoryRoot 'installer\README.md') -Destination (Join-Path $OutputRoot 'DEPLOYMENT-README.md') -Force -ErrorAction Stop

$forbiddenRuntimeFiles = @(Get-ChildItem -LiteralPath $OutputRoot -Recurse -File | Where-Object {
    $_.Name -in '.env', 'platform.yaml', 'passwordfile', 'aclfile', 'mosquitto.conf', 'station-mqtt-credentials.txt' -or
    $_.Extension -in '.db', '.sqlite', '.log', '.pid'
})
if ($forbiddenRuntimeFiles.Count -gt 0) {
    $paths = ($forbiddenRuntimeFiles | ForEach-Object { $_.FullName }) -join ', '
    throw "Release payload contains runtime data or credentials and will not be packaged: $paths"
}

& (Join-Path $RepositoryRoot 'installer\tests\Test-ReleaseArtifacts.ps1') -Root $OutputRoot | Out-Null

$manifestLines = foreach ($file in Get-ChildItem -LiteralPath $OutputRoot -Recurse -File | Sort-Object FullName) {
    if ($file.Name -eq 'SHA256SUMS.txt') {
        continue
    }
    $relativePath = $file.FullName.Substring($OutputRoot.Length + 1).Replace('\', '/')
    $hash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    "$hash *$relativePath"
}
[IO.File]::WriteAllText(
    (Join-Path $OutputRoot 'SHA256SUMS.txt'),
    (($manifestLines -join "`n") + "`n"),
    (New-Object Text.UTF8Encoding($false))
)

[pscustomobject]@{
    PayloadRoot = $OutputRoot
    ApplicationVersion = $AppVersion
    MosquittoVersion = $MosquittoVersion
    WinSWVersion = $WinSWVersion
    VcRedistVersion = $VcRedistVersion
    FileCount = @(Get-ChildItem -LiteralPath $OutputRoot -Recurse -File).Count
    ChecksumManifest = Join-Path $OutputRoot 'SHA256SUMS.txt'
}
