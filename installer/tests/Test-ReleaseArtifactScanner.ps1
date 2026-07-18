[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

Add-Type -AssemblyName System.IO.Compression

$scanner = Join-Path $PSScriptRoot 'Test-ReleaseArtifacts.ps1'
$fixtureRoot = Join-Path $PSScriptRoot '..\config'
$tempBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([char[]]@('\', '/'))
$testRoot = [IO.Path]::GetFullPath((Join-Path $tempBase ('hightac-artifact-scanner-' + [guid]::NewGuid().ToString('N'))))
if (-not $testRoot.StartsWith($tempBase + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Artifact scanner test root escaped the temporary directory.'
}

$script:passed = 0
$utf8 = New-Object Text.UTF8Encoding($false)

function Assert-ScannerCondition {
    param(
        [Parameter(Mandatory = $true)][bool]$Condition,
        [Parameter(Mandatory = $true)][string]$Message
    )

    if (-not $Condition) {
        throw $Message
    }
    $script:passed++
}

function New-ScannerCaseRoot {
    param([Parameter(Mandatory = $true)][string]$Name)

    $path = Join-Path $testRoot $Name
    New-Item -ItemType Directory -Path $path -Force | Out-Null
    return $path
}

function Write-Utf8Fixture {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Content
    )

    $parent = Split-Path -Parent $Path
    if (-not (Test-Path -LiteralPath $parent -PathType Container)) {
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
    }
    [IO.File]::WriteAllText($Path, $Content, $utf8)
}

function Write-Utf16BinaryFixture {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Content
    )

    $encoded = [Text.Encoding]::Unicode.GetBytes($Content)
    $bytes = New-Object byte[] ($encoded.Length + 2)
    $bytes[0] = 0x4D
    $bytes[1] = 0x5A
    [Buffer]::BlockCopy($encoded, 0, $bytes, 2, $encoded.Length)
    try {
        [IO.File]::WriteAllBytes($Path, $bytes)
    }
    finally {
        [Array]::Clear($encoded, 0, $encoded.Length)
        [Array]::Clear($bytes, 0, $bytes.Length)
    }
}

function New-ZipFixture {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][hashtable]$Entries
    )

    $stream = [IO.File]::Open($Path, [IO.FileMode]::CreateNew, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    $archive = $null
    try {
        $archive = [IO.Compression.ZipArchive]::new($stream, [IO.Compression.ZipArchiveMode]::Create, $false)
        foreach ($entryName in $Entries.Keys) {
            $entry = $archive.CreateEntry($entryName, [IO.Compression.CompressionLevel]::Optimal)
            $entryStream = $entry.Open()
            $entryBytes = $utf8.GetBytes([string]$Entries[$entryName])
            try {
                $entryStream.Write($entryBytes, 0, $entryBytes.Length)
            }
            finally {
                $entryStream.Dispose()
                [Array]::Clear($entryBytes, 0, $entryBytes.Length)
            }
        }
    }
    finally {
        if ($null -ne $archive) {
            $archive.Dispose()
        }
        else {
            $stream.Dispose()
        }
    }
}

function New-SelfExtractingZipFixture {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][hashtable]$Entries
    )

    $zipPath = $Path + '.zip-source'
    New-ZipFixture -Path $zipPath -Entries $Entries
    $zipBytes = [IO.File]::ReadAllBytes($zipPath)
    $fixtureBytes = New-Object byte[] ($zipBytes.Length + 32)
    $fixtureBytes[0] = 0x4D
    $fixtureBytes[1] = 0x5A
    [Buffer]::BlockCopy($zipBytes, 0, $fixtureBytes, 32, $zipBytes.Length)
    try {
        [IO.File]::WriteAllBytes($Path, $fixtureBytes)
    }
    finally {
        [Array]::Clear($zipBytes, 0, $zipBytes.Length)
        [Array]::Clear($fixtureBytes, 0, $fixtureBytes.Length)
        [IO.File]::Delete($zipPath)
    }
}

function Assert-ScannerFailure {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$ExpectedMessage,
        [hashtable]$AdditionalParameters = @{},
        [string]$MustNotEcho
    )

    $parameters = @{ Root = $Root }
    foreach ($key in $AdditionalParameters.Keys) {
        $parameters[$key] = $AdditionalParameters[$key]
    }

    $failed = $false
    $message = ''
    try {
        [void](& $scanner @parameters)
    }
    catch {
        $failed = $true
        $message = [string]$_.Exception.Message
    }
    Assert-ScannerCondition $failed "Scanner unexpectedly accepted fixture for rule: $ExpectedMessage"
    Assert-ScannerCondition ($message -match $ExpectedMessage) "Scanner failure did not identify expected rule: $ExpectedMessage"
    if (-not [string]::IsNullOrEmpty($MustNotEcho)) {
        Assert-ScannerCondition ($message.IndexOf($MustNotEcho, [StringComparison]::Ordinal) -lt 0) 'Scanner error echoed a configured secret value.'
    }
}

New-Item -ItemType Directory -Path $testRoot -Force | Out-Null
try {
    $repositoryFixtures = & $scanner -Root $fixtureRoot
    Assert-ScannerCondition ($repositoryFixtures.SecretsFound -eq 0) 'Repository placeholder config fixtures must remain accepted.'

    $placeholderRoot = New-ScannerCaseRoot -Name 'placeholders'
    $placeholderText = @'
station_password = __MQTT_STATION_PASSWORD__
HIGHTAC_MQTT_PASSWORD="__MQTT_PLATFORM_PASSWORD__"
HIGHTAC_BOOTSTRAP_ADMIN_PASSWORD="__BOOTSTRAP_ADMIN_PASSWORD__"
storePassword=${ANDROID_STORE_PASSWORD}
keyPassword=__ANDROID_KEY_PASSWORD__
'@
    Write-Utf8Fixture -Path (Join-Path $placeholderRoot 'platform.env.template') -Content $placeholderText
    Write-Utf8Fixture `
        -Path (Join-Path $placeholderRoot 'public-ca.pem') `
        -Content "-----BEGIN CERTIFICATE-----`r`ntest-public-certificate-fixture`r`n-----END CERTIFICATE-----"
    Write-Utf16BinaryFixture -Path (Join-Path $placeholderRoot 'placeholder-installer.exe') -Content $placeholderText
    New-ZipFixture -Path (Join-Path $placeholderRoot 'placeholder.apk') -Entries @{
        'assets/platform.env.template' = $placeholderText
        'classes.dex' = 'binary-fixture-without-credentials'
    }
    $placeholderResult = & $scanner -Root $placeholderRoot
    Assert-ScannerCondition ($placeholderResult.FilesScanned -eq 4) 'Placeholder fixture scan must inventory all outer artifacts.'
    Assert-ScannerCondition ($placeholderResult.ArchivesScanned -eq 1) 'Placeholder APK must be inspected as a ZIP-compatible archive.'
    Assert-ScannerCondition ($placeholderResult.ArchiveEntriesScanned -eq 2) 'Placeholder APK entries must be content-scanned.'

    $signingRoot = New-ScannerCaseRoot -Name 'signing-material'
    Write-Utf8Fixture -Path (Join-Path $signingRoot 'release-signing.jks') -Content 'test-fixture'
    Write-Utf8Fixture -Path (Join-Path $signingRoot 'release.keystore') -Content 'test-fixture'
    Write-Utf8Fixture -Path (Join-Path $signingRoot 'release.p12') -Content 'test-fixture'
    Write-Utf8Fixture -Path (Join-Path $signingRoot '.jks') -Content 'test-fixture'
    Assert-ScannerFailure -Root $signingRoot -ExpectedMessage 'Signing key or private credential material is forbidden'

    $archiveSigningRoot = New-ScannerCaseRoot -Name 'archive-signing-material'
    New-ZipFixture -Path (Join-Path $archiveSigningRoot 'release.apk') -Entries @{
        'assets/release.keystore' = 'test-fixture'
    }
    Assert-ScannerFailure -Root $archiveSigningRoot -ExpectedMessage 'Signing key or private credential material is forbidden'

    $privateKeyRoot = New-ScannerCaseRoot -Name 'private-key-pem'
    Write-Utf8Fixture `
        -Path (Join-Path $privateKeyRoot 'renamed-public-looking.pem') `
        -Content "-----BEGIN PRIVATE KEY-----`r`ntest-private-key-fixture`r`n-----END PRIVATE KEY-----"
    Assert-ScannerFailure -Root $privateKeyRoot -ExpectedMessage 'content contains a private key'

    $apkSecretRoot = New-ScannerCaseRoot -Name 'apk-rendered-secret'
    $apkSecret = 'generated-' + [guid]::NewGuid().ToString('N')
    New-ZipFixture -Path (Join-Path $apkSecretRoot 'release.apk') -Entries @{
        'assets/platform.env.template' = ('HIGHTAC_MQTT_PASSWORD="{0}"' -f $apkSecret)
    }
    Assert-ScannerFailure `
        -Root $apkSecretRoot `
        -ExpectedMessage 'rendered platform MQTT password' `
        -MustNotEcho $apkSecret
    $apkSecret = $null

    $exeSecretRoot = New-ScannerCaseRoot -Name 'exe-rendered-secret'
    $exeSecret = 'generated-' + [guid]::NewGuid().ToString('N')
    Write-Utf16BinaryFixture `
        -Path (Join-Path $exeSecretRoot 'HighTacPlatform-setup.exe') `
        -Content ('HIGHTAC_BOOTSTRAP_ADMIN_PASSWORD="{0}"' -f $exeSecret)
    Assert-ScannerFailure `
        -Root $exeSecretRoot `
        -ExpectedMessage 'rendered bootstrap admin password' `
        -MustNotEcho $exeSecret
    $exeSecret = $null

    $sfxPlaceholderRoot = New-ScannerCaseRoot -Name 'sfx-placeholder'
    New-SelfExtractingZipFixture -Path (Join-Path $sfxPlaceholderRoot 'placeholder-setup.exe') -Entries @{
        'config/platform.env.template' = $placeholderText
    }
    $sfxPlaceholderResult = & $scanner -Root $sfxPlaceholderRoot
    Assert-ScannerCondition ($sfxPlaceholderResult.ArchivesScanned -eq 1) 'Self-extracting ZIP installer must be opened through its embedded archive offset.'
    Assert-ScannerCondition ($sfxPlaceholderResult.ArchiveEntriesScanned -eq 1) 'Self-extracting ZIP installer entries must be content-scanned.'

    $sfxSecretRoot = New-ScannerCaseRoot -Name 'sfx-rendered-secret'
    $sfxSecret = 'generated-' + [guid]::NewGuid().ToString('N')
    New-SelfExtractingZipFixture -Path (Join-Path $sfxSecretRoot 'release-setup.exe') -Entries @{
        'config/station.conf' = ('station_password={0}' -f $sfxSecret)
    }
    Assert-ScannerFailure `
        -Root $sfxSecretRoot `
        -ExpectedMessage 'rendered station password' `
        -MustNotEcho $sfxSecret
    $sfxSecret = $null

    $environmentRoot = New-ScannerCaseRoot -Name 'known-environment-secret'
    $environmentName = 'HIGHTAC_SCANNER_TEST_' + [guid]::NewGuid().ToString('N').ToUpperInvariant()
    $environmentSecret = 'generated-' + [guid]::NewGuid().ToString('N')
    $previousEnvironmentValue = [Environment]::GetEnvironmentVariable($environmentName, [EnvironmentVariableTarget]::Process)
    try {
        [Environment]::SetEnvironmentVariable($environmentName, $environmentSecret, [EnvironmentVariableTarget]::Process)
        $secretBytes = $utf8.GetBytes($environmentSecret)
        $boundaryBytes = New-Object byte[] (1MB + $secretBytes.Length + 32)
        $boundaryBytes[0] = 0x4D
        $boundaryBytes[1] = 0x5A
        $secretOffset = 1MB - [Math]::Floor($secretBytes.Length / 2)
        [Buffer]::BlockCopy($secretBytes, 0, $boundaryBytes, $secretOffset, $secretBytes.Length)
        try {
            [IO.File]::WriteAllBytes((Join-Path $environmentRoot 'boundary-installer.exe'), $boundaryBytes)
        }
        finally {
            [Array]::Clear($secretBytes, 0, $secretBytes.Length)
            [Array]::Clear($boundaryBytes, 0, $boundaryBytes.Length)
        }
        Assert-ScannerFailure `
            -Root $environmentRoot `
            -ExpectedMessage 'configured known historical secret' `
            -AdditionalParameters @{ KnownSecretEnvironmentVariable = $environmentName } `
            -MustNotEcho $environmentSecret
    }
    finally {
        [Environment]::SetEnvironmentVariable($environmentName, $previousEnvironmentValue, [EnvironmentVariableTarget]::Process)
        $environmentSecret = $null
    }

    $knownFileRoot = New-ScannerCaseRoot -Name 'known-file-secret'
    $knownFileSecret = 'generated-' + [guid]::NewGuid().ToString('N')
    $knownSecretFile = Join-Path $testRoot 'known-secret-source.txt'
    Write-Utf8Fixture -Path $knownSecretFile -Content ($knownFileSecret + "`r`n")
    [IO.File]::SetAttributes($knownSecretFile, [IO.FileAttributes]::ReadOnly)
    New-ZipFixture -Path (Join-Path $knownFileRoot 'release.apk') -Entries @{
        'assets/opaque.bin' = ('prefix:{0}:suffix' -f $knownFileSecret)
    }
    Assert-ScannerFailure `
        -Root $knownFileRoot `
        -ExpectedMessage 'configured known historical secret' `
        -AdditionalParameters @{ KnownSecretFile = $knownSecretFile } `
        -MustNotEcho $knownFileSecret
    $knownFileSecret = $null

    $legacyRoot = New-ScannerCaseRoot -Name 'legacy-file-copy'
    $legacySource = Join-Path $testRoot 'legacy-password-source.txt'
    $legacyContent = 'generated-' + [guid]::NewGuid().ToString('N')
    Write-Utf8Fixture -Path $legacySource -Content $legacyContent
    [IO.File]::Copy($legacySource, (Join-Path $legacyRoot 'renamed.data'))
    Assert-ScannerFailure `
        -Root $legacyRoot `
        -ExpectedMessage 'byte-for-byte copy of a configured known-secret source' `
        -AdditionalParameters @{ LegacyPasswordFile = $legacySource } `
        -MustNotEcho $legacyContent
    $legacyContent = $null

    $malformedRoot = New-ScannerCaseRoot -Name 'malformed-apk'
    Write-Utf8Fixture -Path (Join-Path $malformedRoot 'broken.apk') -Content 'not-a-zip-fixture'
    Assert-ScannerFailure -Root $malformedRoot -ExpectedMessage 'could not be inspected as an archive'
}
finally {
    if (Test-Path -LiteralPath $testRoot) {
        $resolvedTestRoot = [IO.Path]::GetFullPath($testRoot)
        if (-not $resolvedTestRoot.StartsWith($tempBase + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Refusing to clean an artifact scanner test path outside the temporary directory.'
        }
        Get-ChildItem -LiteralPath $resolvedTestRoot -Recurse -Force -ErrorAction SilentlyContinue | ForEach-Object {
            if (-not $_.PSIsContainer -and ($_.Attributes -band [IO.FileAttributes]::ReadOnly) -ne 0) {
                $_.Attributes = $_.Attributes -band (-bnot [IO.FileAttributes]::ReadOnly)
            }
        }
        Remove-Item -LiteralPath $resolvedTestRoot -Recurse -Force
    }
}

Write-Output "Release artifact scanner checks passed: $script:passed assertions."
