[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$installerRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$repositoryRoot = Split-Path -Parent $installerRoot
$credentialScriptPath = Join-Path $installerRoot 'windows\Set-HighTacIntegrationCredentials.ps1'
$credentialScriptText = [IO.File]::ReadAllText($credentialScriptPath)
$failures = New-Object 'System.Collections.Generic.List[string]'
$passed = 0

function Assert-IntegrationCredentialCondition {
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

$tokens = $null
$parseErrors = $null
$scriptAst = [Management.Automation.Language.Parser]::ParseFile(
    $credentialScriptPath,
    [ref]$tokens,
    [ref]$parseErrors
)
$parseErrorMessage = ($parseErrors | ForEach-Object { $_.Message }) -join '; '
Assert-IntegrationCredentialCondition `
    ($parseErrors.Count -eq 0) `
    "Integration credential script has PowerShell parse errors: $parseErrorMessage"

$unsafeOutputCommands = @($scriptAst.FindAll({
    param($node)
    $node -is [Management.Automation.Language.CommandAst] -and
        $node.GetCommandName() -in @(
            'Write-Host',
            'Write-Output',
            'Write-Information',
            'Write-Verbose',
            'Write-Debug',
            'Write-Warning'
        )
}, $true))
Assert-IntegrationCredentialCondition `
    ($unsafeOutputCommands.Count -eq 0) `
    'Credential maintenance must not use host, output, information, verbose, debug, or warning commands that could expose values.'
Assert-IntegrationCredentialCondition `
    ($credentialScriptText -match 'CmdletBinding\(SupportsShouldProcess\s*=\s*\$true' -and
        $credentialScriptText -match 'Assert-HighTacAdministrator' -and
        $credentialScriptText -match '\[switch\]\$Force' -and
        $credentialScriptText -match '\$ConfirmPreference\s*=\s*''None''') `
    'Credential maintenance must require elevation and preserve ShouldProcess plus explicit noninteractive Force handling.'
Assert-IntegrationCredentialCondition `
    ($credentialScriptText -match '\[Security\.SecureString\]\$OpenAiApiKey' -and
        $credentialScriptText -match '\[Security\.SecureString\]\$JianDaoYunApiKey') `
    'Both provider API keys must enter the script as SecureString values.'

$optionalIntegrationNames = @(
    'OpenAiBaseUrl',
    'OpenAiModel',
    'JianDaoYunAppId',
    'JianDaoYunEntryId',
    'JianDaoYunBaseUrl'
)
$optionalIntegrationParameters = @($scriptAst.ParamBlock.Parameters | Where-Object {
    $_.Name.VariablePath.UserPath -in $optionalIntegrationNames
})
$optionalParametersAllowEmptyBinding = $optionalIntegrationParameters.Count -eq $optionalIntegrationNames.Count
foreach ($parameter in $optionalIntegrationParameters) {
    $attributeNames = @($parameter.Attributes | ForEach-Object { $_.TypeName.FullName })
    if ('AllowNull' -notin $attributeNames -or 'AllowEmptyString' -notin $attributeNames) {
        $optionalParametersAllowEmptyBinding = $false
    }
}
Assert-IntegrationCredentialCondition `
    $optionalParametersAllowEmptyBinding `
    'Optional integration strings must accept an omitted or empty binding so partial updates and process-environment fallback work.'
Assert-IntegrationCredentialCondition `
    ([regex]::Matches($credentialScriptText, '\[ValidateLength\(0,\s*128\)\]').Count -eq 3 -and
        [regex]::Matches(
            $credentialScriptText,
            "ValidateScript\(\{ \[string\]::IsNullOrWhiteSpace\(\`$_\) -or \`$_ -match '\^https\?://"
        ).Count -eq 2) `
    'Optional models, identifiers, and URLs must validate non-empty values without rejecting omitted values in Windows PowerShell 5.1.'
Assert-IntegrationCredentialCondition `
    ($credentialScriptText -match 'DataRoot must be the managed HighTac path' -and
        $credentialScriptText -match 'Assert-HighTacProtectedPath' -and
        $credentialScriptText -match 'ReparsePoint') `
    'Credential maintenance must enforce the fixed ProgramData root, protected ACLs, and reparse-point rejection.'
Assert-IntegrationCredentialCondition `
    ($credentialScriptText -match 'Assert-HighTacDotEnvContent' -and
        $credentialScriptText -match 'duplicate variable' -and
        $credentialScriptText -match 'outside the HIGHTAC_ namespace') `
    'Credential maintenance must validate the complete dotenv structure before replacing it.'

$temporaryProtectionIndex = $credentialScriptText.IndexOf(
    'Protect-HighTacPath -Path $temporaryPath',
    [StringComparison]::Ordinal
)
$atomicSearchStart = [Math]::Max(0, $temporaryProtectionIndex)
$atomicReplaceIndex = $credentialScriptText.IndexOf(
    'Move-HighTacCredentialFileAtomically',
    $atomicSearchStart,
    [StringComparison]::Ordinal
)
Assert-IntegrationCredentialCondition `
    ($temporaryProtectionIndex -ge 0 -and
        $atomicReplaceIndex -gt $temporaryProtectionIndex -and
        $credentialScriptText -notmatch 'Write-HighTacTextFile') `
    'The complete temporary credential file must be protected before an atomic replace; in-place shared writes are forbidden.'
Assert-IntegrationCredentialCondition `
    ($credentialScriptText -match '\[Array\]::Clear\(\$bytes' -and
        $credentialScriptText -match '\$updates\.Clear\(\)') `
    'Mutable secret buffers and the update map must be cleared during cleanup.'
Assert-IntegrationCredentialCondition `
    ($credentialScriptText -match 'UpdatedNames\s*=\s*\$updatedNames' -and
        $credentialScriptText -notmatch '(?m)^\s*(?:OpenAiApiKey|JianDaoYunApiKey|UpdatedValues)\s*=') `
    'The result contract may return setting names and status only, never credential values.'

$restartBlocks = @($scriptAst.FindAll({
    param($node)
    $node -is [Management.Automation.Language.IfStatementAst] -and
        $node.Extent.Text -match '^\s*if\s*\(\$RestartService\)' -and
        $node.Extent.Text -match 'Stop-HighTacService' -and
        $node.Extent.Text -match 'Start-HighTacService'
}, $true))
$serviceOperationCount = [regex]::Matches(
    $credentialScriptText,
    '(?:Stop|Start)-HighTacService'
).Count
Assert-IntegrationCredentialCondition `
    ($restartBlocks.Count -eq 1 -and $serviceOperationCount -eq 2) `
    'Stop and start operations must occur exactly once and only inside the explicit RestartService branch.'

$gitIgnoreText = [IO.File]::ReadAllText((Join-Path $repositoryRoot '.gitignore'))
Assert-IntegrationCredentialCondition `
    ($gitIgnoreText -match '(?m)^\.env\s*$' -and $gitIgnoreText -match '(?m)^\.env\.\*\s*$') `
    'Repository ignore rules must reject runtime .env files and their variants.'
$operationsText = [IO.File]::ReadAllText((Join-Path $installerRoot 'README.md'))
Assert-IntegrationCredentialCondition `
    ($operationsText -match 'Set-HighTacIntegrationCredentials\.ps1' -and
        $operationsText -match 'Read-Host.*-AsSecureString' -and
        $operationsText -match 'Without `-RestartService`.*service state' -and
        $operationsText -match 'never copy, stage, commit, attach, or paste') `
    'Installer operations documentation must retain secure input, optional restart, and no-commit guidance.'

$requiredFunctions = @(
    'Assert-HighTacIntegrationValue',
    'Assert-HighTacIntegrationUrl',
    'Assert-HighTacDotEnvContent',
    'Set-DotEnvValue',
    'Move-HighTacCredentialFileAtomically',
    'Write-HighTacCredentialEnvironment'
)
$functionDefinitions = @($scriptAst.FindAll({
    param($node)
    $node -is [Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -in $requiredFunctions
}, $true))
Assert-IntegrationCredentialCondition `
    ($functionDefinitions.Count -eq $requiredFunctions.Count) `
    'Credential regression tests must locate every validation and atomic-write helper.'

if ($functionDefinitions.Count -eq $requiredFunctions.Count) {
    Import-Module (Join-Path $installerRoot 'windows\HighTacInstaller.Common.psm1') -Force
    $functionDefinitions | ForEach-Object { Invoke-Expression $_.Extent.Text }

    $validContent = @(
        '# managed runtime fixture',
        'HIGHTAC_OPENAI_API_KEY="old-value"',
        'HIGHTAC_PORT="8088"'
    ) -join "`r`n"
    $validContent += "`r`n"
    try {
        Assert-HighTacDotEnvContent -Content $validContent
        Assert-IntegrationCredentialCondition $true 'A structurally valid HIGHTAC_ dotenv fixture must be accepted.'
    }
    catch {
        Assert-IntegrationCredentialCondition $false 'A structurally valid HIGHTAC_ dotenv fixture was rejected.'
    }

    $replacementValue = 'value-with-"quote"-and-\-slash'
    $encodedReplacement = ConvertTo-HighTacYamlString -Value $replacementValue
    $updatedContent = Set-DotEnvValue `
        -Content $validContent `
        -Name 'HIGHTAC_OPENAI_API_KEY' `
        -Value $replacementValue
    Assert-IntegrationCredentialCondition `
        ($updatedContent.Contains('HIGHTAC_OPENAI_API_KEY="' + $encodedReplacement + '"') -and
            [regex]::Matches($updatedContent, '(?m)^HIGHTAC_OPENAI_API_KEY=').Count -eq 1) `
        'Updating a provider key must quote and escape it without creating a duplicate entry.'
    try {
        Assert-HighTacDotEnvContent -Content $updatedContent
        Assert-IntegrationCredentialCondition $true 'Escaped credential output must remain compatible with the service dotenv parser.'
    }
    catch {
        Assert-IntegrationCredentialCondition $false 'Escaped credential output was not accepted by the strict dotenv validator.'
    }

    $appendedContent = Set-DotEnvValue `
        -Content $updatedContent `
        -Name 'HIGHTAC_OPENAI_MODEL' `
        -Value 'model-fixture'
    Assert-IntegrationCredentialCondition `
        ($appendedContent -match '(?m)^HIGHTAC_OPENAI_MODEL="model-fixture"\r?$') `
        'A missing integration setting must be appended as one quoted HIGHTAC_ entry.'
    $clearedContent = Set-DotEnvValue `
        -Content $appendedContent `
        -Name 'HIGHTAC_OPENAI_API_KEY' `
        -Value ''
    Assert-IntegrationCredentialCondition `
        ($clearedContent -match '(?m)^HIGHTAC_OPENAI_API_KEY=""\r?$') `
        'Explicit credential clearing must write an empty quoted value.'

    $duplicateRejected = $false
    try {
        Assert-HighTacDotEnvContent -Content (
            'HIGHTAC_OPENAI_API_KEY="first"' + "`r`n" +
            'HIGHTAC_OPENAI_API_KEY="second"' + "`r`n"
        )
    }
    catch {
        $duplicateRejected = $_.Exception.Message -match 'duplicate variable'
    }
    Assert-IntegrationCredentialCondition `
        $duplicateRejected `
        'Duplicate dotenv variables must be rejected before any write.'

    $sensitiveFixture = 'sensitive-fixture-must-not-appear-in-errors'
    $malformedRejectedWithoutValue = $false
    try {
        Assert-HighTacDotEnvContent -Content ('HIGHTAC_OPENAI_API_KEY=' + $sensitiveFixture)
    }
    catch {
        $malformedRejectedWithoutValue =
            $_.Exception.Message -match 'invalid entry at line 1' -and
            -not $_.Exception.Message.Contains($sensitiveFixture)
    }
    Assert-IntegrationCredentialCondition `
        $malformedRejectedWithoutValue `
        'Malformed dotenv errors must identify location without echoing the offending value.'

    $controlCharacterRejectedWithoutValue = $false
    try {
        Assert-HighTacIntegrationValue `
            -Name 'OpenAiApiKey' `
            -Value ("prefix`n" + $sensitiveFixture)
    }
    catch {
        $controlCharacterRejectedWithoutValue =
            $_.Exception.Message -match 'control characters' -and
            -not $_.Exception.Message.Contains($sensitiveFixture)
    }
    Assert-IntegrationCredentialCondition `
        $controlCharacterRejectedWithoutValue `
        'Credential validation must reject control characters without echoing the value.'

    foreach ($invalidUrl in @(
        'https://user:password@example.invalid/v1',
        'https://example.invalid/v1?token=value',
        'https://example.invalid/v1#fragment'
    )) {
        $urlRejected = $false
        try {
            Assert-HighTacIntegrationUrl -Name 'OpenAiBaseUrl' -Value $invalidUrl
        }
        catch {
            $urlRejected = $_.Exception.Message -match 'without credentials, whitespace, query, or fragment'
        }
        Assert-IntegrationCredentialCondition `
            $urlRejected `
            'Integration URLs containing credentials, query data, or fragments must be rejected.'
    }

    $script:protectedPaths = New-Object 'System.Collections.Generic.List[string]'
    $script:failTemporaryProtection = $false
    function Protect-HighTacPath {
        [CmdletBinding(SupportsShouldProcess = $true)]
        param(
            [Parameter(Mandatory = $true)]
            [string]$Path
        )

        $fullPath = [IO.Path]::GetFullPath($Path)
        if ($script:failTemporaryProtection -and $fullPath -match '\.tmp$') {
            throw 'Simulated temporary ACL protection failure.'
        }
        $script:protectedPaths.Add($fullPath)
    }

    $atomicTestRoot = Join-Path (
        [IO.Path]::GetTempPath()
    ) ('hightac-integration-credentials-' + [Guid]::NewGuid().ToString('N'))
    $atomicTestRoot = [IO.Path]::GetFullPath($atomicTestRoot)
    New-Item -ItemType Directory -Path $atomicTestRoot -Force | Out-Null
    try {
        $destinationPath = Join-Path $atomicTestRoot '.env'
        $utf8WithoutBom = New-Object Text.UTF8Encoding($false)
        [IO.File]::WriteAllText($destinationPath, 'original-content', $utf8WithoutBom)
        $script:protectedPaths.Clear()
        Write-HighTacCredentialEnvironment `
            -Path $destinationPath `
            -Content 'replacement-content'

        $remainingTemporaryFiles = @(Get-ChildItem -LiteralPath $atomicTestRoot -Filter '.env.*.tmp' -Force)
        Assert-IntegrationCredentialCondition `
            ([IO.File]::ReadAllText($destinationPath) -ceq 'replacement-content') `
            'Atomic credential writing must replace the destination with complete new content.'
        Assert-IntegrationCredentialCondition `
            ($remainingTemporaryFiles.Count -eq 0) `
            'Atomic credential writing must not leave a temporary credential copy behind.'
        Assert-IntegrationCredentialCondition `
            ($script:protectedPaths.Count -eq 2 -and
                $script:protectedPaths[0] -match '\.tmp$' -and
                $script:protectedPaths[1] -ceq [IO.Path]::GetFullPath($destinationPath)) `
            'Atomic credential writing must protect the temporary file before protecting the replaced destination.'
        $replacementBytes = [IO.File]::ReadAllBytes($destinationPath)
        $hasUtf8Bom = $replacementBytes.Length -ge 3 -and
            $replacementBytes[0] -eq 0xEF -and
            $replacementBytes[1] -eq 0xBB -and
            $replacementBytes[2] -eq 0xBF
        Assert-IntegrationCredentialCondition `
            (-not $hasUtf8Bom) `
            'Atomic credential writing must preserve the UTF-8-without-BOM runtime format.'

        [IO.File]::WriteAllText($destinationPath, 'preserve-on-failure', $utf8WithoutBom)
        $script:failTemporaryProtection = $true
        $protectionFailureRaised = $false
        try {
            Write-HighTacCredentialEnvironment `
                -Path $destinationPath `
                -Content 'must-not-replace-original'
        }
        catch {
            $protectionFailureRaised = $_.Exception.Message -match 'Simulated temporary ACL protection failure'
        }
        finally {
            $script:failTemporaryProtection = $false
        }
        $remainingTemporaryFiles = @(Get-ChildItem -LiteralPath $atomicTestRoot -Filter '.env.*.tmp' -Force)
        Assert-IntegrationCredentialCondition `
            ($protectionFailureRaised -and
                [IO.File]::ReadAllText($destinationPath) -ceq 'preserve-on-failure' -and
                $remainingTemporaryFiles.Count -eq 0) `
            'A pre-replace protection failure must preserve the original file and remove the temporary copy.'
    }
    finally {
        $systemTempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
        if (-not $atomicTestRoot.StartsWith($systemTempRoot, [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Refusing to remove an integration test directory outside the system temporary root.'
        }
        Remove-Item -LiteralPath $atomicTestRoot -Recurse -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath Function:\Protect-HighTacPath -Force -ErrorAction SilentlyContinue
    }
}

if ($failures.Count -gt 0) {
    Write-Output "Integration credential checks failed ($($failures.Count)):"
    $failures | ForEach-Object { Write-Output " - $_" }
    exit 1
}

Write-Output "Integration credential checks passed: $passed assertions."
