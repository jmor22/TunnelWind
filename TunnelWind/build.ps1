# TunnelWind build script - uses the portable .NET SDK in ..\tools\dotnet (no system install)
# usage: powershell -ExecutionPolicy Bypass -File build.ps1 [-arm64]
$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot                        # TunnelWind\
$toolsDotnet = Join-Path (Split-Path -Parent $projectRoot) 'tools\dotnet'
$env:DOTNET_ROOT = $toolsDotnet
$env:DOTNET_CLI_HOME = Join-Path $env:TEMP 'tunnelwind-cli-home'
$env:DOTNET_SKIP_FIRST_TIME_EXPERIENCE = '1'
$dotnet = Join-Path $toolsDotnet 'dotnet.exe'

if (-not (Test-Path $dotnet)) {
    throw "dotnet.exe not found at $dotnet - run tools\get-dotnet.ps1 first"
}

Write-Host "== TunnelWind build (portable SDK) ==" -ForegroundColor Cyan
$out = Join-Path $projectRoot 'output'

# engine staged once from the analysis extraction
$engineDir = Join-Path $projectRoot 'engine'
New-Item -ItemType Directory -Force $engineDir | Out-Null
$engineExe = Join-Path $engineDir 'psiphon-tunnel-core.exe'
$engineSrc = Join-Path (Split-Path -Parent $projectRoot) 'extracted\id131.bin'
if (-not (Test-Path $engineExe) -and (Test-Path $engineSrc)) {
    Copy-Item $engineSrc $engineExe
    Write-Host "engine: copied from $engineSrc" -ForegroundColor Yellow
}

function Build-Arch([string]$rid, [string]$outDir) {
    New-Item -ItemType Directory -Force (Join-Path $outDir 'engine') | Out-Null
    & $dotnet publish (Join-Path $projectRoot 'src\TunnelWind.App\TunnelWind.App.csproj') `
        -c Release -f net8.0-windows -r $rid --self-contained false -o $outDir
    if ($LASTEXITCODE -ne 0) { throw "publish $rid failed" }
    if (Test-Path $engineExe) {
        Copy-Item $engineExe (Join-Path $outDir 'engine\psiphon-tunnel-core.exe') -Force
    }
    Write-Host "done -> $outDir\TunnelWind.exe" -ForegroundColor Green
}

Build-Arch 'win-x64' $out

if ($args -contains '-arm64') {
    Build-Arch 'win-arm64' (Join-Path $projectRoot 'output-arm64')
}
