[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'Medium')]
param(
    [string]$RepositoryRoot,

    [string]$ServerRoot,

    [string]$EntryPoint,

    [string]$WebAssetsRoot,

    [string]$OutputRoot,

    [string]$PythonCommand = 'python',

    [switch]$BuildWeb,

    [switch]$SkipNpmCi
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not $RepositoryRoot) {
    $RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
}
$RepositoryRoot = [IO.Path]::GetFullPath($RepositoryRoot).TrimEnd('\')
if (-not $ServerRoot) {
    $ServerRoot = Join-Path $RepositoryRoot 'server'
}
if (-not $WebAssetsRoot) {
    $WebAssetsRoot = Join-Path $RepositoryRoot 'installer\build\web-dist'
}
if (-not $OutputRoot) {
    $OutputRoot = Join-Path $RepositoryRoot 'installer\build\pyinstaller'
}

$ServerRoot = [IO.Path]::GetFullPath($ServerRoot).TrimEnd('\')
$WebAssetsRoot = [IO.Path]::GetFullPath($WebAssetsRoot).TrimEnd('\')
$OutputRoot = [IO.Path]::GetFullPath($OutputRoot).TrimEnd('\')
$allowedBuildRoot = [IO.Path]::GetFullPath((Join-Path $RepositoryRoot 'installer\build')).TrimEnd('\')
if (-not $OutputRoot.StartsWith($allowedBuildRoot + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw "OutputRoot must stay under '$allowedBuildRoot' so cleanup cannot affect source files."
}

if (-not (Test-Path -LiteralPath (Join-Path $ServerRoot 'pyproject.toml') -PathType Leaf)) {
    throw "Server pyproject.toml was not found at '$ServerRoot'. The backend project must exist before packaging."
}
if ($EntryPoint) {
    $EntryPoint = [IO.Path]::GetFullPath($EntryPoint)
    if (-not (Test-Path -LiteralPath $EntryPoint -PathType Leaf)) {
        throw "PyInstaller entry point was not found: $EntryPoint"
    }
}
else {
    $cliModule = Join-Path $ServerRoot 'src\hightac_platform\cli.py'
    if (-not (Test-Path -LiteralPath $cliModule -PathType Leaf)) {
        throw "The backend pyproject declares hightac_platform.cli:main, but its CLI module is missing: $cliModule"
    }
}
if ($BuildWeb) {
    & (Join-Path $PSScriptRoot 'Build-WebAssets.ps1') `
        -RepositoryRoot $RepositoryRoot `
        -OutputRoot $WebAssetsRoot `
        -SkipNpmCi:$SkipNpmCi `
        -Confirm:$false | Out-Null
}
if (-not (Test-Path -LiteralPath (Join-Path $WebAssetsRoot 'index.html') -PathType Leaf)) {
    throw "Embedded web build is missing index.html: $WebAssetsRoot. Run Build-WebAssets.ps1 first or use -BuildWeb."
}
if ($null -eq (Get-Command $PythonCommand -ErrorAction SilentlyContinue)) {
    throw "Python command was not found: $PythonCommand"
}

$pythonVersion = & $PythonCommand -c 'import sys;print(sys.version_info.major,sys.version_info.minor,sys.version_info.micro,sep=chr(46))'
if ($LASTEXITCODE -ne 0 -or -not ([string]$pythonVersion).StartsWith('3.12.')) {
    throw "Python 3.12 is required for the M6 package. Detected: $pythonVersion"
}
& $PythonCommand -c 'import PyInstaller; print(PyInstaller.__version__)' | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw 'PyInstaller is not installed in the selected Python 3.12 environment.'
}

if (-not $PSCmdlet.ShouldProcess($ServerRoot, "Build PyInstaller one-folder package at $OutputRoot")) {
    return
}

if (Test-Path -LiteralPath $OutputRoot) {
    Remove-Item -LiteralPath $OutputRoot -Recurse -Force
}
$distRoot = Join-Path $OutputRoot 'dist'
$workRoot = Join-Path $OutputRoot 'work'
$specRoot = Join-Path $OutputRoot 'spec'
New-Item -ItemType Directory -Path $distRoot, $workRoot, $specRoot -Force | Out-Null

if (-not $EntryPoint) {
    $EntryPoint = Join-Path $specRoot 'hightac_service_entry.py'
    $entrySource = @'
import sys
from pathlib import Path

from fastapi import HTTPException
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles

from hightac_platform.cli import main


def attach_embedded_web() -> None:
    from hightac_platform.main import app

    bundle_root = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent))
    web_root = (bundle_root / "hightac_platform" / "static").resolve()
    index_file = web_root / "index.html"
    if not index_file.is_file():
        raise RuntimeError(f"Embedded web build is missing: {index_file}")

    assets_root = web_root / "assets"
    if assets_root.is_dir():
        app.mount("/assets", StaticFiles(directory=assets_root), name="embedded-web-assets")

    @app.get("/", include_in_schema=False)
    async def embedded_web_index() -> FileResponse:
        return FileResponse(index_file)

    @app.get("/{full_path:path}", include_in_schema=False)
    async def embedded_web_fallback(full_path: str) -> FileResponse:
        if full_path == "api" or full_path.startswith("api/"):
            raise HTTPException(status_code=404)
        candidate = (web_root / full_path).resolve()
        try:
            candidate.relative_to(web_root)
        except ValueError as exc:
            raise HTTPException(status_code=404) from exc
        if candidate.is_file():
            return FileResponse(candidate)
        return FileResponse(index_file)


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "serve":
        attach_embedded_web()
    raise SystemExit(main())
'@
    [IO.File]::WriteAllText($EntryPoint, $entrySource, (New-Object Text.UTF8Encoding($false)))
}

$arguments = @(
    '-m', 'PyInstaller',
    '--noconfirm',
    '--clean',
    '--onedir',
    '--name', 'HighTacPlatform',
    '--distpath', $distRoot,
    '--workpath', $workRoot,
    '--specpath', $specRoot,
    '--paths', (Join-Path $ServerRoot 'src'),
    '--collect-submodules', 'hightac_platform',
    '--add-data', "$WebAssetsRoot;hightac_platform/static"
)

$migrationsRoot = Join-Path $ServerRoot 'migrations'
if (Test-Path -LiteralPath $migrationsRoot -PathType Container) {
    $arguments += @('--add-data', "$migrationsRoot;hightac_platform/_migrations")
}
$alembicConfig = Join-Path $ServerRoot 'alembic.ini'
if (Test-Path -LiteralPath $alembicConfig -PathType Leaf) {
    $arguments += @('--add-data', "$alembicConfig;hightac_platform")
}
$arguments += $EntryPoint

& $PythonCommand @arguments
if ($LASTEXITCODE -ne 0) {
    throw "PyInstaller failed with exit code $LASTEXITCODE."
}

$applicationRoot = Join-Path $distRoot 'HighTacPlatform'
$executable = Join-Path $applicationRoot 'HighTacPlatform.exe'
if (-not (Test-Path -LiteralPath $executable -PathType Leaf)) {
    throw "PyInstaller completed without producing the expected executable: $executable"
}
if (-not (Get-ChildItem -LiteralPath $applicationRoot -Recurse -Filter index.html -File -ErrorAction SilentlyContinue)) {
    throw 'PyInstaller output does not contain the embedded web index.html.'
}

Get-Item -LiteralPath $applicationRoot
