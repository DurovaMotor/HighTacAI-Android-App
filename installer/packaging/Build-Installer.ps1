[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'Medium')]
param(
    [Parameter(Mandatory = $true)]
    [string]$PayloadRoot,

    [ValidatePattern('^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$')]
    [string]$AppVersion = '2.0.0',

    [string]$RepositoryRoot,

    [string]$OutputRoot,

    [string]$IsccPath,

    [string]$SigningPfxPath,

    [Security.SecureString]$SigningPfxPassword,

    [string]$SigningCertificateThumbprint,

    [ValidateSet('CurrentUser', 'LocalMachine')]
    [string]$SigningCertificateStore = 'CurrentUser',

    [string]$TimestampServer = 'http://timestamp.digicert.com',

    [ValidateSet('internal_lan', 'public_distribution')]
    [string]$DistributionTarget = 'internal_lan',

    [switch]$RequireSignedInstaller
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-HighTacRepositorySourceState {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Root
    )

    $gitCommand = Get-Command git.exe -ErrorAction SilentlyContinue
    if ($null -eq $gitCommand) {
        $gitCommand = Get-Command git -ErrorAction SilentlyContinue
    }
    if ($null -eq $gitCommand) {
        throw 'Git is required to bind installer metadata to its source commit.'
    }

    $commitOutput = @(& $gitCommand.Source -C $Root rev-parse --verify HEAD 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to resolve the installer source commit: $($commitOutput -join ' ')"
    }
    $commit = ([string]($commitOutput | Select-Object -First 1)).Trim().ToLowerInvariant()
    if ($commit -notmatch '^[0-9a-f]{40,64}$') {
        throw 'Git returned an invalid installer source commit.'
    }

    $statusOutput = @(& $gitCommand.Source -C $Root status --porcelain=v1 --untracked-files=all 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to inspect the installer source worktree: $($statusOutput -join ' ')"
    }

    return [pscustomobject]@{
        Commit = $commit
        WorktreeClean = @($statusOutput | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }).Count -eq 0
    }
}

$signingInputConfigured = [bool]$SigningPfxPath -or [bool]$SigningCertificateThumbprint
if (($RequireSignedInstaller -or $DistributionTarget -eq 'public_distribution') -and -not $signingInputConfigured) {
    throw 'A code-signing PFX or installed certificate thumbprint is required for this distribution target.'
}
if ($DistributionTarget -eq 'public_distribution' -and [string]::IsNullOrWhiteSpace($TimestampServer)) {
    throw 'Public distribution requires a trusted Authenticode timestamp server.'
}

function Resolve-CodeSigningCertificate {
    param(
        [string]$PfxPath,
        [Security.SecureString]$PfxPassword,
        [string]$Thumbprint,
        [string]$StoreLocation
    )

    if ($PfxPath -and $Thumbprint) {
        throw 'Choose either SigningPfxPath or SigningCertificateThumbprint, not both.'
    }
    if ($PfxPassword -and -not $PfxPath) {
        throw 'SigningPfxPassword is valid only with SigningPfxPath.'
    }
    if (-not $PfxPath -and -not $Thumbprint) {
        return $null
    }

    if ($PfxPath) {
        $resolvedPfxPath = [IO.Path]::GetFullPath($PfxPath)
        if (-not (Test-Path -LiteralPath $resolvedPfxPath -PathType Leaf)) {
            throw "Signing PFX does not exist: $resolvedPfxPath"
        }
        if (-not $PfxPassword) {
            throw 'SigningPfxPassword must be supplied as a SecureString when SigningPfxPath is used.'
        }
        try {
            $certificate = [Security.Cryptography.X509Certificates.X509Certificate2]::new(
                $resolvedPfxPath,
                $PfxPassword,
                [Security.Cryptography.X509Certificates.X509KeyStorageFlags]::EphemeralKeySet
            )
        }
        catch {
            throw "Unable to open the signing PFX: $($_.Exception.Message)"
        }
    }
    else {
        $normalizedThumbprint = ($Thumbprint -replace '\s', '').ToUpperInvariant()
        if ($normalizedThumbprint -notmatch '^[A-F0-9]{40,64}$') {
            throw 'SigningCertificateThumbprint must contain 40 to 64 hexadecimal characters.'
        }
        $certificatePath = "Cert:\$StoreLocation\My\$normalizedThumbprint"
        if (-not (Test-Path -LiteralPath $certificatePath -PathType Leaf)) {
            throw "Code-signing certificate was not found: $certificatePath"
        }
        $certificate = Get-Item -LiteralPath $certificatePath
    }

    if (-not $certificate.HasPrivateKey) {
        throw 'The selected signing certificate does not expose a private key.'
    }
    $now = Get-Date
    if ($now -lt $certificate.NotBefore -or $now -gt $certificate.NotAfter) {
        throw 'The selected signing certificate is outside its validity period.'
    }
    $codeSigningEku = '1.3.6.1.5.5.7.3.3'
    $hasCodeSigningEku = $false
    foreach ($extension in @($certificate.Extensions)) {
        if ($extension -isnot [Security.Cryptography.X509Certificates.X509EnhancedKeyUsageExtension]) {
            continue
        }
        if (@($extension.EnhancedKeyUsages | Where-Object { $_.Value -eq $codeSigningEku }).Count -gt 0) {
            $hasCodeSigningEku = $true
            break
        }
    }
    if (-not $hasCodeSigningEku) {
        throw 'The selected certificate is not explicitly valid for Authenticode code signing.'
    }

    return $certificate
}

if (-not $RepositoryRoot) {
    $RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
}
$RepositoryRoot = [IO.Path]::GetFullPath($RepositoryRoot).TrimEnd('\')
$sourceState = Get-HighTacRepositorySourceState -Root $RepositoryRoot
$PayloadRoot = [IO.Path]::GetFullPath($PayloadRoot).TrimEnd('\')
if (-not $OutputRoot) {
    $OutputRoot = Join-Path $RepositoryRoot 'installer\build\installer'
}
$OutputRoot = [IO.Path]::GetFullPath($OutputRoot).TrimEnd('\')
$allowedBuildRoot = [IO.Path]::GetFullPath((Join-Path $RepositoryRoot 'installer\build')).TrimEnd('\')
if (-not $OutputRoot.StartsWith($allowedBuildRoot + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw "OutputRoot must stay under '$allowedBuildRoot' so cleanup cannot affect source files."
}

if (-not $IsccPath) {
    $candidates = foreach ($programFilesRoot in @(${env:ProgramFiles(x86)}, $env:ProgramFiles)) {
        if ($programFilesRoot) {
            Join-Path $programFilesRoot 'Inno Setup 6\ISCC.exe'
        }
    }
    if ($env:LOCALAPPDATA) {
        $candidates += Join-Path $env:LOCALAPPDATA 'Programs\Inno Setup 6\ISCC.exe'
    }
    $IsccPath = $candidates | Where-Object { $_ -and (Test-Path -LiteralPath $_ -PathType Leaf) } | Select-Object -First 1
}
if (-not $IsccPath -or -not (Test-Path -LiteralPath $IsccPath -PathType Leaf)) {
    throw 'Inno Setup 6 compiler (ISCC.exe) was not found. Install it separately or pass -IsccPath.'
}

$requiredPayloadFiles = @(
    'server\HighTacPlatform.exe',
    'mosquitto\mosquitto.exe',
    'mosquitto\mosquitto_passwd.exe',
    'mosquitto\MSVCP140.dll',
    'mosquitto\VCRUNTIME140.dll',
    'mosquitto\VCRUNTIME140_1.dll',
    'service\HighTacPlatform.exe',
    'service\HighTacMqttBroker.exe',
    'dependencies\VC_redist.x64.exe',
    'licenses\THIRD-PARTY-NOTICES.md',
    'licenses\Microsoft-VC-Runtime-Notice.txt',
    'BUILD-MANIFEST.json',
    'SHA256SUMS.txt'
)
foreach ($relativePath in $requiredPayloadFiles) {
    $path = Join-Path $PayloadRoot $relativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Installer payload is incomplete: $path"
    }
}

$manifestPath = Join-Path $PayloadRoot 'SHA256SUMS.txt'
$manifestEntries = @{}
foreach ($line in [IO.File]::ReadAllLines($manifestPath)) {
    if ([string]::IsNullOrWhiteSpace($line) -or $line.StartsWith('#')) {
        continue
    }
    if ($line -notmatch '^([A-Fa-f0-9]{64})\s+\*(.+)$') {
        throw "Invalid payload checksum line: $line"
    }
    $relativePath = $Matches[2].Replace('/', '\')
    if ([IO.Path]::IsPathRooted($relativePath) -or $relativePath -match '(^|\\)\.\.(\\|$)') {
        throw "Unsafe relative path in payload checksum manifest: $relativePath"
    }
    $manifestKey = $relativePath.ToLowerInvariant()
    if ($manifestEntries.ContainsKey($manifestKey)) {
        throw "Duplicate payload checksum path: $relativePath"
    }
    $manifestEntries[$manifestKey] = $Matches[1].ToUpperInvariant()
    $filePath = Join-Path $PayloadRoot $relativePath
    if (-not (Test-Path -LiteralPath $filePath -PathType Leaf)) {
        throw "Payload checksum references a missing file: $relativePath"
    }
    if ((Get-FileHash -LiteralPath $filePath -Algorithm SHA256).Hash -ne $manifestEntries[$manifestKey]) {
        throw "Payload checksum mismatch: $relativePath"
    }
}
$payloadFiles = @(Get-ChildItem -LiteralPath $PayloadRoot -Recurse -File | Where-Object { $_.FullName -ne $manifestPath })
foreach ($file in $payloadFiles) {
    $relativePath = $file.FullName.Substring($PayloadRoot.Length + 1).Replace('/', '\').ToLowerInvariant()
    if (-not $manifestEntries.ContainsKey($relativePath)) {
        throw "Payload file is not covered by SHA256SUMS.txt: $relativePath"
    }
}
if ($manifestEntries.Count -eq 0 -or $manifestEntries.Count -ne $payloadFiles.Count) {
    throw 'Payload checksum manifest coverage is incomplete.'
}

& (Join-Path $RepositoryRoot 'installer\tests\Test-ReleaseArtifacts.ps1') -Root $PayloadRoot | Out-Null

$issPath = Join-Path $PSScriptRoot 'HighTacPlatform.iss'
if (-not $PSCmdlet.ShouldProcess($issPath, "Compile HighTac installer $AppVersion at $OutputRoot")) {
    return
}

New-Item -ItemType Directory -Path $OutputRoot -Force | Out-Null
$isccOutput = @(& $IsccPath `
    "/DPayloadRoot=$PayloadRoot" `
    "/DAppVersion=$AppVersion" `
    "/DOutputDir=$OutputRoot" `
    $issPath)
$isccExitCode = $LASTEXITCODE
$isccOutput | Out-Host
if ($isccExitCode -ne 0) {
    throw "Inno Setup compilation failed with exit code $isccExitCode."
}

$installerPath = Join-Path $OutputRoot "HighTacPlatform-$AppVersion-x64.exe"
if (-not (Test-Path -LiteralPath $installerPath -PathType Leaf)) {
    throw "Inno Setup did not produce the expected installer: $installerPath"
}

$signingCertificate = Resolve-CodeSigningCertificate `
    -PfxPath $SigningPfxPath `
    -PfxPassword $SigningPfxPassword `
    -Thumbprint $SigningCertificateThumbprint `
    -StoreLocation $SigningCertificateStore
$signingRequested = $null -ne $signingCertificate
try {
    if ($signingRequested) {
        $signingParameters = @{
            FilePath = $installerPath
            Certificate = $signingCertificate
            HashAlgorithm = 'SHA256'
        }
        if ($TimestampServer) {
            $signingParameters.TimestampServer = $TimestampServer
        }
        $signingResult = Set-AuthenticodeSignature @signingParameters
        if ([string]$signingResult.Status -ne 'Valid') {
            throw "Authenticode signing did not produce a valid signature: $($signingResult.Status) $($signingResult.StatusMessage)"
        }
    }

    $signature = Get-AuthenticodeSignature -LiteralPath $installerPath
    if ($signingRequested) {
        if ([string]$signature.Status -ne 'Valid') {
            throw "Post-signing Authenticode verification failed: $($signature.Status) $($signature.StatusMessage)"
        }
        if ($signature.SignerCertificate.Thumbprint -ne $signingCertificate.Thumbprint) {
            throw 'Post-signing verification returned a different signer certificate.'
        }
        if ($TimestampServer -and $null -eq $signature.TimeStamperCertificate) {
            throw 'The signed installer does not contain the required trusted timestamp.'
        }
    }
    elseif ([string]$signature.Status -ne 'NotSigned') {
        throw "Unsigned installer has an unexpected Authenticode state: $($signature.Status) $($signature.StatusMessage)"
    }
    $signatureRequired = $RequireSignedInstaller -or $DistributionTarget -eq 'public_distribution'
    if ($signatureRequired -and [string]$signature.Status -ne 'Valid') {
        throw 'A valid Authenticode signature is required for this distribution target, but none is present.'
    }
}
finally {
    if ($SigningPfxPath -and $signingCertificate -is [IDisposable]) {
        $signingCertificate.Dispose()
    }
}

$distributionClass = if ($DistributionTarget -eq 'public_distribution') {
    'public_distribution_signed'
}
elseif ([string]$signature.Status -eq 'Valid') {
    'internal_lan_signed'
}
else {
    'internal_lan_unsigned'
}
$installerHash = (Get-FileHash -LiteralPath $installerPath -Algorithm SHA256).Hash.ToLowerInvariant()
$hashPath = "$installerPath.sha256"
[IO.File]::WriteAllText(
    $hashPath,
    "$installerHash *$([IO.Path]::GetFileName($installerPath))`n",
    (New-Object Text.UTF8Encoding($false))
)

$metadataPath = "$installerPath.release.json"
$metadata = [ordered]@{
    schema_version = 1
    product_version = $AppVersion
    expected_git_tag = "v$AppVersion"
    source_commit = $sourceState.Commit
    source_worktree_clean = [bool]$sourceState.WorktreeClean
    artifact = [IO.Path]::GetFileName($installerPath)
    sha256 = $installerHash
    distribution_target = $DistributionTarget
    distribution_class = $distributionClass
    authenticode_required = $signatureRequired
    authenticode = [ordered]@{
        status = [string]$signature.Status
        signer_subject = if ($signature.SignerCertificate) { $signature.SignerCertificate.Subject } else { $null }
        signer_thumbprint = if ($signature.SignerCertificate) { $signature.SignerCertificate.Thumbprint } else { $null }
        timestamped = $null -ne $signature.TimeStamperCertificate
        timestamp_signer_subject = if ($signature.TimeStamperCertificate) { $signature.TimeStamperCertificate.Subject } else { $null }
    }
    notice = if ($distributionClass -eq 'internal_lan_unsigned') {
        'INTERNAL LAN UNSIGNED PACKAGE - permitted only for private LAN deployment; never public distribution.'
    }
    elseif ($distributionClass -eq 'internal_lan_signed') {
        'INTERNAL LAN SIGNED PACKAGE - complete all internal release gates before deployment.'
    }
    else {
        'PUBLIC DISTRIBUTION SIGNED PACKAGE - complete all public release gates before distribution.'
    }
}
[IO.File]::WriteAllText(
    $metadataPath,
    (($metadata | ConvertTo-Json -Depth 6) + "`n"),
    (New-Object Text.UTF8Encoding($false))
)

[pscustomobject]@{
    Installer = $installerPath
    Sha256 = $installerHash
    ChecksumFile = $hashPath
    ReleaseMetadataFile = $metadataPath
    DistributionTarget = $DistributionTarget
    DistributionClass = $distributionClass
    AuthenticodeStatus = [string]$signature.Status
    SignerThumbprint = if ($signature.SignerCertificate) { $signature.SignerCertificate.Thumbprint } else { $null }
}
