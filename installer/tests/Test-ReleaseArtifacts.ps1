[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Root,

    [string]$LegacyPasswordFile,

    [Alias('HistoricalSecretEnvironmentVariable')]
    [string[]]$KnownSecretEnvironmentVariable = @(),

    [Alias('HistoricalSecretFile')]
    [string[]]$KnownSecretFile = @()
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$Root = [IO.Path]::GetFullPath($Root).TrimEnd([char[]]@('\', '/'))
if ($Root -match '^[A-Za-z]:$') {
    $Root += '\'
}
if (-not (Test-Path -LiteralPath $Root -PathType Container)) {
    throw "Artifact root does not exist: $Root"
}

Add-Type -AssemblyName System.IO.Compression

$scanBufferBytes = 1MB
$minimumKnownSecretBytes = 8
$maximumKnownSecretSourceBytes = 1MB
$maximumEmbeddedSignatureBytes = 256KB
$maximumKnownSecretPatterns = 256
$maximumArchiveEntries = 10000
$maximumArchiveEntryBytes = 256MB
$maximumArchiveExpandedBytes = 1GB
$maximumArchiveCompressionRatio = 1000.0
$maximumEmbeddedZipBytes = 512MB

$textExtensions = @(
    '.cfg', '.conf', '.css', '.csv', '.env', '.html', '.ini', '.iss', '.js',
    '.json', '.md', '.pem', '.properties', '.ps1', '.psm1', '.py', '.template',
    '.toml', '.ts', '.tsx', '.txt', '.xml', '.yaml', '.yml'
)
$containerExtensions = @(
    '.7z', '.aab', '.apk', '.apks', '.appx', '.appxbundle', '.bin', '.cab',
    '.dat', '.ear', '.exe', '.jar', '.msi', '.msix', '.msixbundle', '.msp',
    '.nupkg', '.pak', '.rar', '.war', '.whl', '.zip'
)
$zipExtensions = @('.aab', '.apk', '.apks', '.appx', '.appxbundle', '.ear', '.jar', '.msix', '.msixbundle', '.nupkg', '.war', '.whl', '.zip')
$forbiddenNames = @(
    '.env',
    'aclfile',
    'mosquitto.conf',
    'password_file',
    'passwordfile',
    'platform.yaml',
    'station-mqtt-credentials.txt'
)
$forbiddenRuntimeExtensions = @('.db', '.sqlite', '.sqlite3', '.log', '.pid')
$forbiddenSigningNames = @(
    '.keystore',
    'key.properties',
    'keystore.properties',
    'signing.properties'
)
$forbiddenSigningExtensions = @(
    '.bcfks', '.jceks', '.jks', '.key', '.keystore', '.ks', '.p12', '.pfx',
    '.pk8', '.pkcs12'
)

$failures = New-Object 'System.Collections.Generic.List[string]'
$knownSecretPatterns = New-Object 'System.Collections.Generic.List[object]'
$knownSecretPatternHashes = @{}
$knownSourceFileHashes = @{}
$latin1Encoding = [Text.Encoding]::GetEncoding(28591)
$utf8Encoding = New-Object Text.UTF8Encoding($false)
$strictUtf8Encoding = New-Object Text.UTF8Encoding($false, $true)
$unicodeEncoding = [Text.Encoding]::Unicode
$bigEndianUnicodeEncoding = [Text.Encoding]::BigEndianUnicode
$sha256 = [Security.Cryptography.SHA256]::Create()

$script:bytesInspected = [long]0
$script:contentStreamsScanned = 0
$script:textFilesScanned = 0
$script:binaryContainersScanned = 0
$script:archivesScanned = 0
$script:archiveEntriesScanned = 0
$script:knownSecretSourcesLoaded = 0

function Add-ScanFailure {
    param([Parameter(Mandatory = $true)][string]$Message)

    if (-not $failures.Contains($Message)) {
        $failures.Add($Message)
    }
}

function Test-PathWithinArtifactRoot {
    param([Parameter(Mandatory = $true)][string]$Path)

    $fullPath = [IO.Path]::GetFullPath($Path).TrimEnd([char[]]@('\', '/'))
    $rootPath = $Root.TrimEnd([char[]]@('\', '/'))
    if ($fullPath.Equals($rootPath, [StringComparison]::OrdinalIgnoreCase)) {
        return $true
    }
    return $fullPath.StartsWith($rootPath + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)
}

function Test-ForbiddenArtifactName {
    param(
        [Parameter(Mandatory = $true)][string]$LeafName,
        [Parameter(Mandatory = $true)][string]$Location
    )

    $normalizedName = $LeafName.ToLowerInvariant()
    $extension = [IO.Path]::GetExtension($normalizedName).ToLowerInvariant()
    if ($normalizedName -in $forbiddenSigningNames -or $normalizedName -in $forbiddenSigningExtensions -or $extension -in $forbiddenSigningExtensions) {
        Add-ScanFailure "Signing key or private credential material is forbidden in release artifacts: $Location"
    }
    elseif ($normalizedName -in $forbiddenNames -or $normalizedName -in $forbiddenRuntimeExtensions -or $extension -in $forbiddenRuntimeExtensions) {
        Add-ScanFailure "Runtime data or credential filename is forbidden in release artifacts: $Location"
    }
}

function Add-KnownSecretBytePattern {
    param([Parameter(Mandatory = $true)][byte[]]$Bytes)

    if ($Bytes.Length -lt $minimumKnownSecretBytes -or $Bytes.Length -gt $maximumEmbeddedSignatureBytes) {
        return
    }
    $patternHashBytes = $sha256.ComputeHash($Bytes)
    $patternHash = [Convert]::ToBase64String($patternHashBytes)
    [Array]::Clear($patternHashBytes, 0, $patternHashBytes.Length)
    if ($knownSecretPatternHashes.ContainsKey($patternHash)) {
        return
    }
    if ($knownSecretPatterns.Count -ge $maximumKnownSecretPatterns) {
        throw "Known-secret sources exceed the $maximumKnownSecretPatterns-pattern safety limit."
    }
    $knownSecretPatternHashes[$patternHash] = $true
    $knownSecretPatterns.Add([pscustomobject]@{
        Pattern = $latin1Encoding.GetString($Bytes)
        ByteLength = $Bytes.Length
    })
}

function Add-KnownSecretTextPattern {
    param([Parameter(Mandatory = $true)][string]$Text)

    if ([string]::IsNullOrEmpty($Text)) {
        return
    }
    if ($utf8Encoding.GetByteCount($Text) -lt $minimumKnownSecretBytes) {
        return
    }
    foreach ($encoding in @($utf8Encoding, $unicodeEncoding, $bigEndianUnicodeEncoding)) {
        $bytes = $encoding.GetBytes($Text)
        try {
            Add-KnownSecretBytePattern -Bytes $bytes
        }
        finally {
            [Array]::Clear($bytes, 0, $bytes.Length)
        }
    }
}

function Add-KnownSecretSourceText {
    param([Parameter(Mandatory = $true)][string]$Text)

    Add-KnownSecretTextPattern -Text $Text
    foreach ($rawLine in @($Text -split "`r?`n")) {
        $line = $rawLine.Trim()
        if ([string]::IsNullOrWhiteSpace($line) -or $line.StartsWith('#')) {
            continue
        }
        Add-KnownSecretTextPattern -Text $line

        $separatorIndex = $line.IndexOf('=')
        if ($separatorIndex -gt 0) {
            $key = $line.Substring(0, $separatorIndex).Trim()
            if ($key -match '^[A-Za-z_][A-Za-z0-9_.-]*$') {
                $value = $line.Substring($separatorIndex + 1).Trim()
                if ($value.Length -ge 2 -and (($value[0] -eq '"' -and $value[$value.Length - 1] -eq '"') -or ($value[0] -eq "'" -and $value[$value.Length - 1] -eq "'"))) {
                    $value = $value.Substring(1, $value.Length - 2)
                }
                Add-KnownSecretTextPattern -Text $value
            }
        }
    }
}

function Get-SafeKnownSecretFile {
    param([Parameter(Mandatory = $true)][string]$Path)

    $fullPath = [IO.Path]::GetFullPath($Path)
    if (-not (Test-Path -LiteralPath $fullPath -PathType Leaf)) {
        throw "Known-secret source file does not exist: $fullPath"
    }
    if (Test-PathWithinArtifactRoot -Path $fullPath) {
        throw 'Known-secret source files must be outside the artifact root.'
    }
    $item = Get-Item -LiteralPath $fullPath -Force
    if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw 'Known-secret source files must not be symbolic links or reparse points.'
    }
    if ($item.Length -gt $maximumKnownSecretSourceBytes) {
        throw "Known-secret source files must not exceed $maximumKnownSecretSourceBytes bytes."
    }
    return $item
}

foreach ($variableName in @($KnownSecretEnvironmentVariable)) {
    if ([string]::IsNullOrWhiteSpace($variableName) -or $variableName -notmatch '^[A-Za-z_][A-Za-z0-9_]*$') {
        throw 'Known-secret environment variable names must use letters, digits, and underscores and must not contain secret values.'
    }
    $secretValue = [Environment]::GetEnvironmentVariable($variableName, [EnvironmentVariableTarget]::Process)
    if ([string]::IsNullOrEmpty($secretValue)) {
        throw "Known-secret environment variable is unset or empty: $variableName"
    }
    $patternsBefore = $knownSecretPatterns.Count
    Add-KnownSecretSourceText -Text $secretValue
    if ($knownSecretPatterns.Count -eq $patternsBefore) {
        throw 'Known-secret environment values must encode to at least 8 bytes so the scanner can avoid unsafe short-string matches.'
    }
    $script:knownSecretSourcesLoaded++
    $secretValue = $null
}

$sourceFileArguments = New-Object 'System.Collections.Generic.List[string]'
foreach ($sourceFile in @($KnownSecretFile)) {
    if (-not [string]::IsNullOrWhiteSpace($sourceFile)) {
        $sourceFileArguments.Add($sourceFile)
    }
}
if (-not [string]::IsNullOrWhiteSpace($LegacyPasswordFile)) {
    $sourceFileArguments.Add($LegacyPasswordFile)
}

$loadedSourcePaths = @{}
foreach ($sourceFile in $sourceFileArguments) {
    $sourceItem = Get-SafeKnownSecretFile -Path $sourceFile
    if ($loadedSourcePaths.ContainsKey($sourceItem.FullName)) {
        continue
    }
    $loadedSourcePaths[$sourceItem.FullName] = $true

    $sourceBytes = [IO.File]::ReadAllBytes($sourceItem.FullName)
    try {
        $sourceHash = (Get-FileHash -LiteralPath $sourceItem.FullName -Algorithm SHA256).Hash
        $knownSourceFileHashes[$sourceHash] = $true
        $patternsBefore = $knownSecretPatterns.Count
        Add-KnownSecretBytePattern -Bytes $sourceBytes
        try {
            $sourceText = $strictUtf8Encoding.GetString($sourceBytes)
            Add-KnownSecretSourceText -Text $sourceText
            $sourceText = $null
        }
        catch [Text.DecoderFallbackException] {
            # Binary secret sources are compared by hash and, when small enough, by exact bytes.
        }
        if ($knownSecretPatterns.Count -eq $patternsBefore -and $sourceBytes.Length -ne 0) {
            throw 'Known-secret source did not contain an embeddable value of at least 8 bytes.'
        }
        $script:knownSecretSourcesLoaded++
    }
    finally {
        [Array]::Clear($sourceBytes, 0, $sourceBytes.Length)
    }
}

$regexTimeout = [TimeSpan]::FromSeconds(2)
$assignmentRegex = [regex]::new(
    '(?i)(?<![A-Za-z0-9_])(?<name>station_password|HIGHTAC_MQTT_PASSWORD|HIGHTAC_BOOTSTRAP_ADMIN_PASSWORD)[ \t]*=[ \t]*(?<value>"[^"\r\n\x00]{0,1024}"|''[^''\r\n\x00]{0,1024}''|[^\s#;\x00]{1,1024})',
    ([Text.RegularExpressions.RegexOptions]::CultureInvariant),
    $regexTimeout
)
$signingPasswordRegex = [regex]::new(
    '(?i)(?<![A-Za-z0-9_])(?<name>storePassword|keyPassword|store_password|key_password|SIGNING_PFX_PASSWORD|SigningPfxPassword)[ \t]*[=:][ \t]*(?<value>"[^"\r\n\x00]{0,1024}"|''[^''\r\n\x00]{0,1024}''|[^\s#;\x00]{1,1024})',
    ([Text.RegularExpressions.RegexOptions]::CultureInvariant),
    $regexTimeout
)
$mosquittoHashRegex = [regex]::new(
    '(?m)(?:^|[\r\n])[A-Za-z0-9_-]{1,128}:\$7\$[0-9]{1,6}\$[A-Za-z0-9+/=]{40,128}\$[A-Za-z0-9+/=]{40,128}[ \t]*(?:\r?$)',
    ([Text.RegularExpressions.RegexOptions]::CultureInvariant),
    $regexTimeout
)
$privateKeyRegex = [regex]::new(
    '-----BEGIN (?:RSA |EC |DSA |OPENSSH |ENCRYPTED )?PRIVATE KEY-----',
    ([Text.RegularExpressions.RegexOptions]::CultureInvariant),
    $regexTimeout
)
$commonTokenRegex = [regex]::new(
    '(?<![A-Za-z0-9_])(?:AKIA[0-9A-Z]{16}|gh[pousr]_[A-Za-z0-9]{36,255}|sk-(?:proj-|svcacct-)?[A-Za-z0-9_-]{24,})(?![A-Za-z0-9_])',
    ([Text.RegularExpressions.RegexOptions]::CultureInvariant),
    $regexTimeout
)
$expectedPlaceholders = @{
    'station_password' = '__MQTT_STATION_PASSWORD__'
    'HIGHTAC_MQTT_PASSWORD' = '__MQTT_PLATFORM_PASSWORD__'
    'HIGHTAC_BOOTSTRAP_ADMIN_PASSWORD' = '__BOOTSTRAP_ADMIN_PASSWORD__'
}
$heuristicMarkers = @(
    'station_password',
    'HIGHTAC_MQTT_PASSWORD',
    'HIGHTAC_BOOTSTRAP_ADMIN_PASSWORD',
    'storePassword',
    'keyPassword',
    'store_password',
    'key_password',
    'SIGNING_PFX_PASSWORD',
    'SigningPfxPassword',
    '$7$',
    'PRIVATE KEY-----',
    'AKIA',
    'ghp_',
    'gho_',
    'ghu_',
    'ghs_',
    'ghr_',
    'sk-'
)
$littleEndianHeuristicMarkers = New-Object 'System.Collections.Generic.List[string]'
$bigEndianHeuristicMarkers = New-Object 'System.Collections.Generic.List[string]'
foreach ($marker in $heuristicMarkers) {
    $markerBytes = $unicodeEncoding.GetBytes($marker)
    try {
        $littleEndianHeuristicMarkers.Add($latin1Encoding.GetString($markerBytes))
    }
    finally {
        [Array]::Clear($markerBytes, 0, $markerBytes.Length)
    }
    $markerBytes = $bigEndianUnicodeEncoding.GetBytes($marker)
    try {
        $bigEndianHeuristicMarkers.Add($latin1Encoding.GetString($markerBytes))
    }
    finally {
        [Array]::Clear($markerBytes, 0, $markerBytes.Length)
    }
}

function Get-UnquotedAssignmentValue {
    param([Parameter(Mandatory = $true)][string]$Value)

    if ($Value.Length -ge 2 -and (($Value[0] -eq '"' -and $Value[$Value.Length - 1] -eq '"') -or ($Value[0] -eq "'" -and $Value[$Value.Length - 1] -eq "'"))) {
        return $Value.Substring(1, $Value.Length - 2)
    }
    return $Value
}

function Test-SafeSigningPasswordReference {
    param([Parameter(Mandatory = $true)][string]$Value)

    return $Value -match '^(?:__[A-Z0-9_]+__|\$\{[A-Za-z_][A-Za-z0-9_]*\}|\$env:[A-Za-z_][A-Za-z0-9_]*|%[A-Za-z_][A-Za-z0-9_]*%|\{\{[A-Za-z0-9_.-]+\}\})$'
}

function Invoke-HeuristicTextInspection {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Location,
        [Parameter(Mandatory = $true)][hashtable]$Detected
    )

    try {
        if (-not $Detected.ContainsKey('mosquitto') -and $Text.IndexOf('$7$', [StringComparison]::Ordinal) -ge 0 -and $mosquittoHashRegex.IsMatch($Text)) {
            Add-ScanFailure "Artifact content contains a Mosquitto password hash entry: $Location"
            $Detected['mosquitto'] = $true
        }
        if (-not $Detected.ContainsKey('private-key') -and $Text.IndexOf('PRIVATE KEY-----', [StringComparison]::Ordinal) -ge 0 -and $privateKeyRegex.IsMatch($Text)) {
            Add-ScanFailure "Artifact content contains a private key: $Location"
            $Detected['private-key'] = $true
        }
        $hasCommonTokenMarker = $Text.IndexOf('AKIA', [StringComparison]::Ordinal) -ge 0 -or
            $Text.IndexOf('ghp_', [StringComparison]::Ordinal) -ge 0 -or
            $Text.IndexOf('gho_', [StringComparison]::Ordinal) -ge 0 -or
            $Text.IndexOf('ghu_', [StringComparison]::Ordinal) -ge 0 -or
            $Text.IndexOf('ghs_', [StringComparison]::Ordinal) -ge 0 -or
            $Text.IndexOf('ghr_', [StringComparison]::Ordinal) -ge 0 -or
            $Text.IndexOf('sk-', [StringComparison]::Ordinal) -ge 0
        if (-not $Detected.ContainsKey('common-token') -and $hasCommonTokenMarker -and $commonTokenRegex.IsMatch($Text)) {
            Add-ScanFailure "Artifact content contains a recognizable access token: $Location"
            $Detected['common-token'] = $true
        }

        $hasApplicationAssignmentMarker = $Text.IndexOf('station_password', [StringComparison]::OrdinalIgnoreCase) -ge 0 -or
            $Text.IndexOf('HIGHTAC_MQTT_PASSWORD', [StringComparison]::OrdinalIgnoreCase) -ge 0 -or
            $Text.IndexOf('HIGHTAC_BOOTSTRAP_ADMIN_PASSWORD', [StringComparison]::OrdinalIgnoreCase) -ge 0
        if ($hasApplicationAssignmentMarker) {
            foreach ($match in $assignmentRegex.Matches($Text)) {
                $name = $match.Groups['name'].Value
                $value = Get-UnquotedAssignmentValue -Value $match.Groups['value'].Value
                if ([string]::IsNullOrEmpty($value) -or $value -ceq $expectedPlaceholders[$name]) {
                    continue
                }
                $ruleKey = 'assignment-' + $name.ToLowerInvariant()
                if (-not $Detected.ContainsKey($ruleKey)) {
                    switch ($name.ToLowerInvariant()) {
                        'station_password' {
                            Add-ScanFailure "Artifact content contains a rendered station password: $Location"
                        }
                        'hightac_mqtt_password' {
                            Add-ScanFailure "Artifact content contains a rendered platform MQTT password: $Location"
                        }
                        'hightac_bootstrap_admin_password' {
                            Add-ScanFailure "Artifact content contains a rendered bootstrap admin password: $Location"
                        }
                    }
                    $Detected[$ruleKey] = $true
                }
            }
        }

        $hasSigningAssignmentMarker = $Text.IndexOf('storePassword', [StringComparison]::OrdinalIgnoreCase) -ge 0 -or
            $Text.IndexOf('keyPassword', [StringComparison]::OrdinalIgnoreCase) -ge 0 -or
            $Text.IndexOf('store_password', [StringComparison]::OrdinalIgnoreCase) -ge 0 -or
            $Text.IndexOf('key_password', [StringComparison]::OrdinalIgnoreCase) -ge 0 -or
            $Text.IndexOf('SIGNING_PFX_PASSWORD', [StringComparison]::OrdinalIgnoreCase) -ge 0 -or
            $Text.IndexOf('SigningPfxPassword', [StringComparison]::OrdinalIgnoreCase) -ge 0
        if ($hasSigningAssignmentMarker) {
            foreach ($match in $signingPasswordRegex.Matches($Text)) {
                $value = Get-UnquotedAssignmentValue -Value $match.Groups['value'].Value
                if ([string]::IsNullOrEmpty($value) -or (Test-SafeSigningPasswordReference -Value $value)) {
                    continue
                }
                if (-not $Detected.ContainsKey('signing-password')) {
                    Add-ScanFailure "Artifact content contains a rendered signing credential: $Location"
                    $Detected['signing-password'] = $true
                }
            }
        }
    }
    catch [Text.RegularExpressions.RegexMatchTimeoutException] {
        if (-not $Detected.ContainsKey('regex-timeout')) {
            Add-ScanFailure "Artifact content could not be fully inspected within the regex safety limit: $Location"
            $Detected['regex-timeout'] = $true
        }
    }
}

function Invoke-ContentInspection {
    param(
        [Parameter(Mandatory = $true)][IO.Stream]$Stream,
        [Parameter(Mandatory = $true)][string]$Location,
        [Parameter(Mandatory = $true)][bool]$InspectHeuristics
    )

    if (-not $Stream.CanRead) {
        Add-ScanFailure "Artifact content stream is not readable: $Location"
        return
    }

    $maximumPatternBytes = 0
    foreach ($signature in $knownSecretPatterns) {
        if ($signature.ByteLength -gt $maximumPatternBytes) {
            $maximumPatternBytes = $signature.ByteLength
        }
    }
    $overlapBytes = [Math]::Max(8192, $maximumPatternBytes - 1)
    if (($overlapBytes % 2) -ne 0) {
        $overlapBytes++
    }

    $buffer = New-Object byte[] $scanBufferBytes
    $carry = New-Object byte[] 0
    $knownSecretMatched = $false
    $detected = @{}
    $script:contentStreamsScanned++

    try {
        while (($read = $Stream.Read($buffer, 0, $buffer.Length)) -gt 0) {
            $script:bytesInspected += $read
            $window = New-Object byte[] ($carry.Length + $read)
            if ($carry.Length -gt 0) {
                [Buffer]::BlockCopy($carry, 0, $window, 0, $carry.Length)
            }
            [Buffer]::BlockCopy($buffer, 0, $window, $carry.Length, $read)
            $latinText = $latin1Encoding.GetString($window, 0, $window.Length)

            if (-not $knownSecretMatched) {
                foreach ($signature in $knownSecretPatterns) {
                    if ($latinText.IndexOf($signature.Pattern, [StringComparison]::Ordinal) -ge 0) {
                        Add-ScanFailure "Artifact content matches a configured known historical secret: $Location"
                        $knownSecretMatched = $true
                        break
                    }
                }
            }

            if ($InspectHeuristics) {
                Invoke-HeuristicTextInspection -Text $latinText -Location $Location -Detected $detected

                $inspectLittleEndianUnicode = $false
                foreach ($marker in $littleEndianHeuristicMarkers) {
                    if ($latinText.IndexOf($marker, [StringComparison]::Ordinal) -ge 0) {
                        $inspectLittleEndianUnicode = $true
                        break
                    }
                }
                if ($inspectLittleEndianUnicode -and $window.Length -ge 2) {
                    $evenLength = $window.Length - ($window.Length % 2)
                    $unicodeText = $unicodeEncoding.GetString($window, 0, $evenLength)
                    Invoke-HeuristicTextInspection -Text $unicodeText -Location $Location -Detected $detected
                    if ($window.Length -ge 3) {
                        $offsetLength = ($window.Length - 1) - (($window.Length - 1) % 2)
                        $offsetUnicodeText = $unicodeEncoding.GetString($window, 1, $offsetLength)
                        Invoke-HeuristicTextInspection -Text $offsetUnicodeText -Location $Location -Detected $detected
                    }
                }

                $inspectBigEndianUnicode = $false
                foreach ($marker in $bigEndianHeuristicMarkers) {
                    if ($latinText.IndexOf($marker, [StringComparison]::Ordinal) -ge 0) {
                        $inspectBigEndianUnicode = $true
                        break
                    }
                }
                if ($inspectBigEndianUnicode -and $window.Length -ge 2) {
                    $evenLength = $window.Length - ($window.Length % 2)
                    $unicodeText = $bigEndianUnicodeEncoding.GetString($window, 0, $evenLength)
                    Invoke-HeuristicTextInspection -Text $unicodeText -Location $Location -Detected $detected
                    if ($window.Length -ge 3) {
                        $offsetLength = ($window.Length - 1) - (($window.Length - 1) % 2)
                        $offsetUnicodeText = $bigEndianUnicodeEncoding.GetString($window, 1, $offsetLength)
                        Invoke-HeuristicTextInspection -Text $offsetUnicodeText -Location $Location -Detected $detected
                    }
                }
            }

            $carryLength = [Math]::Min($overlapBytes, $window.Length)
            $carry = New-Object byte[] $carryLength
            if ($carryLength -gt 0) {
                [Buffer]::BlockCopy($window, $window.Length - $carryLength, $carry, 0, $carryLength)
            }
        }
    }
    catch {
        Add-ScanFailure "Artifact content could not be read completely: $Location"
    }
    finally {
        [Array]::Clear($buffer, 0, $buffer.Length)
        if ($carry.Length -gt 0) {
            [Array]::Clear($carry, 0, $carry.Length)
        }
    }
}

function Get-ZipEndRecordInfo {
    param([Parameter(Mandatory = $true)][IO.FileInfo]$File)

    if ($File.Length -lt 22) {
        return $null
    }
    $tailLength = [int][Math]::Min([long]65557, $File.Length)
    $tail = New-Object byte[] $tailLength
    $stream = [IO.File]::Open($File.FullName, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
    try {
        [void]$stream.Seek(-$tailLength, [IO.SeekOrigin]::End)
        $offset = 0
        while ($offset -lt $tail.Length) {
            $read = $stream.Read($tail, $offset, $tail.Length - $offset)
            if ($read -eq 0) {
                break
            }
            $offset += $read
        }
        for ($index = $offset - 22; $index -ge 0; $index--) {
            if ($tail[$index] -eq 0x50 -and $tail[$index + 1] -eq 0x4B -and $tail[$index + 2] -eq 0x05 -and $tail[$index + 3] -eq 0x06) {
                $endRecordOffset = ($File.Length - $tailLength) + $index
                $centralDirectorySize = [BitConverter]::ToUInt32($tail, $index + 12)
                $centralDirectoryOffset = [BitConverter]::ToUInt32($tail, $index + 16)
                $archiveStartOffset = $endRecordOffset - [long]$centralDirectorySize - [long]$centralDirectoryOffset
                if ($archiveStartOffset -lt 0 -or $archiveStartOffset -ge $File.Length) {
                    $archiveStartOffset = 0
                }
                return [pscustomobject]@{
                    ArchiveStartOffset = $archiveStartOffset
                    EndRecordOffset = $endRecordOffset
                }
            }
        }
        return $null
    }
    finally {
        $stream.Dispose()
        [Array]::Clear($tail, 0, $tail.Length)
    }
}

function Invoke-OpenZipArchiveInspection {
    param(
        [Parameter(Mandatory = $true)][IO.Compression.ZipArchive]$Archive,
        [Parameter(Mandatory = $true)][IO.FileInfo]$File
    )

    $entryCount = $Archive.Entries.Count
    $script:archivesScanned++
    if ($entryCount -gt $maximumArchiveEntries) {
        Add-ScanFailure "ZIP-compatible artifact exceeds the archive entry safety limit: $($File.FullName)"
        return
    }

    $expandedBytes = [long]0
    $entryIndex = 0
    foreach ($entry in $Archive.Entries) {
        $entryIndex++
        if ([string]::IsNullOrEmpty($entry.Name)) {
            continue
        }
        $entryLocation = "$($File.FullName) [archive entry #$entryIndex]"
        Test-ForbiddenArtifactName -LeafName $entry.Name -Location $entryLocation

        if ($entry.Length -lt 0 -or $entry.CompressedLength -lt 0) {
            Add-ScanFailure "ZIP-compatible artifact has an entry with invalid size metadata: $entryLocation"
            continue
        }
        if ($entry.Length -gt $maximumArchiveEntryBytes) {
            Add-ScanFailure "ZIP-compatible artifact entry exceeds the per-entry inspection limit: $entryLocation"
            continue
        }
        if ($entry.Length -gt 1MB) {
            $compressedLength = [Math]::Max([long]1, $entry.CompressedLength)
            $compressionRatio = [double]$entry.Length / [double]$compressedLength
            if ($compressionRatio -gt $maximumArchiveCompressionRatio) {
                Add-ScanFailure "ZIP-compatible artifact entry exceeds the compression-ratio safety limit: $entryLocation"
                continue
            }
        }
        if ($expandedBytes + $entry.Length -gt $maximumArchiveExpandedBytes) {
            Add-ScanFailure "ZIP-compatible artifact exceeds the total expanded-size inspection limit: $($File.FullName)"
            break
        }
        $expandedBytes += $entry.Length

        $entryStream = $null
        try {
            $entryStream = $entry.Open()
            Invoke-ContentInspection -Stream $entryStream -Location $entryLocation -InspectHeuristics $true
            $script:archiveEntriesScanned++
        }
        catch {
            Add-ScanFailure "ZIP-compatible artifact entry could not be inspected: $entryLocation"
        }
        finally {
            if ($null -ne $entryStream) {
                $entryStream.Dispose()
            }
        }
    }
}

function Invoke-ZipArchiveInspection {
    param(
        [Parameter(Mandatory = $true)][IO.FileInfo]$File,
        [long]$EmbeddedArchiveOffset = 0
    )

    $opened = $false
    $fileStream = $null
    $archive = $null
    try {
        $fileStream = [IO.File]::Open($File.FullName, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
        $archive = [IO.Compression.ZipArchive]::new($fileStream, [IO.Compression.ZipArchiveMode]::Read, $false)
        Invoke-OpenZipArchiveInspection -Archive $archive -File $File
        $opened = $true
    }
    catch {
        $opened = $false
    }
    finally {
        if ($null -ne $archive) {
            $archive.Dispose()
        }
        elseif ($null -ne $fileStream) {
            $fileStream.Dispose()
        }
    }
    if ($opened) {
        return
    }

    if ($EmbeddedArchiveOffset -gt 0) {
        $embeddedLength = $File.Length - $EmbeddedArchiveOffset
        if ($embeddedLength -gt $maximumEmbeddedZipBytes) {
            Add-ScanFailure "Embedded ZIP-compatible artifact exceeds the in-memory inspection limit: $($File.FullName)"
            return
        }

        $sourceStream = $null
        $memoryStream = $null
        $embeddedArchive = $null
        try {
            $sourceStream = [IO.File]::Open($File.FullName, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
            [void]$sourceStream.Seek($EmbeddedArchiveOffset, [IO.SeekOrigin]::Begin)
            $memoryStream = New-Object IO.MemoryStream
            $sourceStream.CopyTo($memoryStream)
            [void]$memoryStream.Seek(0, [IO.SeekOrigin]::Begin)
            $embeddedArchive = [IO.Compression.ZipArchive]::new($memoryStream, [IO.Compression.ZipArchiveMode]::Read, $false)
            Invoke-OpenZipArchiveInspection -Archive $embeddedArchive -File $File
            $opened = $true
        }
        catch {
            $opened = $false
        }
        finally {
            if ($null -ne $embeddedArchive) {
                $embeddedArchive.Dispose()
            }
            elseif ($null -ne $memoryStream) {
                $memoryStream.Dispose()
            }
            if ($null -ne $sourceStream) {
                $sourceStream.Dispose()
            }
        }
    }

    if (-not $opened) {
        Add-ScanFailure "ZIP-compatible artifact could not be inspected as an archive: $($File.FullName)"
    }
}

$items = @(Get-ChildItem -LiteralPath $Root -Recurse -Force)
foreach ($item in $items | Where-Object { ($_.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 }) {
    Add-ScanFailure "Artifact tree contains a symbolic link or junction: $($item.FullName)"
}
$files = @($items | Where-Object { -not $_.PSIsContainer })

foreach ($file in $files) {
    Test-ForbiddenArtifactName -LeafName $file.Name -Location $file.FullName

    if ($knownSourceFileHashes.Count -gt 0) {
        try {
            $artifactHash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
            if ($knownSourceFileHashes.ContainsKey($artifactHash)) {
                Add-ScanFailure "Artifact is a byte-for-byte copy of a configured known-secret source: $($file.FullName)"
            }
        }
        catch {
            Add-ScanFailure "Artifact could not be hashed for known-secret comparison: $($file.FullName)"
        }
    }

    $extension = $file.Extension.ToLowerInvariant()
    $isText = $extension -in $textExtensions
    $isContainer = $extension -in $containerExtensions -or $file.Name -match '(?i)(?:setup|installer)'
    if ($isText -or $isContainer -or $knownSecretPatterns.Count -gt 0) {
        $stream = $null
        try {
            $stream = [IO.File]::Open($file.FullName, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
            Invoke-ContentInspection -Stream $stream -Location $file.FullName -InspectHeuristics ($isText -or $isContainer)
            if ($isText) {
                $script:textFilesScanned++
            }
            if ($isContainer) {
                $script:binaryContainersScanned++
            }
        }
        catch {
            Add-ScanFailure "Artifact could not be opened for content inspection: $($file.FullName)"
        }
        finally {
            if ($null -ne $stream) {
                $stream.Dispose()
            }
        }
    }

    $isExpectedZip = $extension -in $zipExtensions
    $zipEndRecord = $null
    if (-not $isExpectedZip -and $isContainer) {
        try {
            $zipEndRecord = Get-ZipEndRecordInfo -File $file
        }
        catch {
            Add-ScanFailure "Artifact could not be checked for embedded ZIP content: $($file.FullName)"
        }
    }
    if ($isExpectedZip -or $null -ne $zipEndRecord) {
        $embeddedArchiveOffset = 0
        if ($null -ne $zipEndRecord) {
            $embeddedArchiveOffset = $zipEndRecord.ArchiveStartOffset
        }
        Invoke-ZipArchiveInspection -File $file -EmbeddedArchiveOffset $embeddedArchiveOffset
    }
}

$sha256.Dispose()

if ($failures.Count -gt 0) {
    throw "Artifact secret scan failed:`n - $($failures -join "`n - ")"
}

[pscustomobject]@{
    Root = $Root
    FilesScanned = $files.Count
    TextFilesScanned = $script:textFilesScanned
    BinaryContainersScanned = $script:binaryContainersScanned
    ArchivesScanned = $script:archivesScanned
    ArchiveEntriesScanned = $script:archiveEntriesScanned
    ContentStreamsScanned = $script:contentStreamsScanned
    BytesInspected = $script:bytesInspected
    KnownSecretSourcesLoaded = $script:knownSecretSourcesLoaded
    LegacySourceCompared = [bool]$LegacyPasswordFile
    SecretsFound = 0
}
