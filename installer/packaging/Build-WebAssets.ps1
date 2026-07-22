[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'Medium')]
param(
    [string]$RepositoryRoot,

    [string]$WebRoot,

    [string]$OutputRoot,

    [string]$NpmCommand = 'npm.cmd',

    [switch]$SkipNpmCi
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not $RepositoryRoot) {
    $RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
}
$RepositoryRoot = [IO.Path]::GetFullPath($RepositoryRoot).TrimEnd('\')
if (-not $WebRoot) {
    $WebRoot = Join-Path $RepositoryRoot 'web'
}
if (-not $OutputRoot) {
    $OutputRoot = Join-Path $RepositoryRoot 'installer\build\web-dist'
}
$WebRoot = [IO.Path]::GetFullPath($WebRoot).TrimEnd('\')
$OutputRoot = [IO.Path]::GetFullPath($OutputRoot).TrimEnd('\')
$allowedBuildRoot = [IO.Path]::GetFullPath((Join-Path $RepositoryRoot 'installer\build')).TrimEnd('\')

if (-not $OutputRoot.StartsWith($allowedBuildRoot + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw "OutputRoot must stay under '$allowedBuildRoot' so cleanup cannot affect source files."
}
if (-not (Test-Path -LiteralPath (Join-Path $WebRoot 'package.json') -PathType Leaf)) {
    throw "Web package.json was not found at '$WebRoot'. The M4 web project must exist before packaging."
}
if (-not $SkipNpmCi -and -not (Test-Path -LiteralPath (Join-Path $WebRoot 'package-lock.json') -PathType Leaf)) {
    throw "package-lock.json is required for reproducible npm ci builds: $WebRoot"
}
if ($null -eq (Get-Command $NpmCommand -ErrorAction SilentlyContinue)) {
    throw "npm command was not found: $NpmCommand"
}

if (-not $PSCmdlet.ShouldProcess($WebRoot, "Build Vite assets and stage them at $OutputRoot")) {
    return
}

Push-Location $WebRoot
try {
    if (-not $SkipNpmCi) {
        & $NpmCommand ci
        if ($LASTEXITCODE -ne 0) {
            throw "npm ci failed with exit code $LASTEXITCODE."
        }
    }
    & $NpmCommand run build
    if ($LASTEXITCODE -ne 0) {
        throw "npm run build failed with exit code $LASTEXITCODE."
    }
}
finally {
    Pop-Location
}

$viteOutput = Join-Path $WebRoot 'dist'
if (-not (Test-Path -LiteralPath (Join-Path $viteOutput 'index.html') -PathType Leaf)) {
    throw "Vite did not produce the required dist/index.html: $viteOutput"
}
if (Test-Path -LiteralPath $OutputRoot) {
    Remove-Item -LiteralPath $OutputRoot -Recurse -Force
}
New-Item -ItemType Directory -Path $OutputRoot -Force | Out-Null
Get-ChildItem -LiteralPath $viteOutput -Force | ForEach-Object {
    Copy-Item -LiteralPath $_.FullName -Destination $OutputRoot -Recurse -Force
}

Get-Item -LiteralPath $OutputRoot
