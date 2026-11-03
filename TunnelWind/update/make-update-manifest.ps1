<#
.SYNOPSIS
  Builds the TunnelWind update channel package (whole-folder ZIP swap flow).

.DESCRIPTION
  1. Publishes the app (portable SDK in ..\tools\dotnet - no system install needed).
  2. Stages it as update/stage/TunnelWind_<version>.zip (app folder contents only;
     the engine lives in LOCALAPPDATA and is packaged separately).
  3. Stages the engine as update/stage/psiphon-tunnel-core.exe.
  4. Generates update/stage/update.json with version, URLs and SHA-256 hashes.

  Upload stage\* to any static HTTPS host; in the app press (check-for-updates) and
  give it https://YOUR_HOST/update.json

.PARAMETER DeploymentUrl
  Base URL where staged files will be hosted (no trailing slash).

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File make-update-manifest.ps1 -DeploymentUrl https://cdn.example.com/tunnelwind
#>
param(
    [Parameter(Mandatory = $true)]
    [string]$DeploymentUrl
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot      # TunnelWind\
$toolsDotnet = Join-Path (Split-Path -Parent $projectRoot) 'tools\dotnet'
$dotnet = Join-Path $toolsDotnet 'dotnet.exe'
$env:DOTNET_ROOT = $toolsDotnet
$env:DOTNET_CLI_HOME = Join-Path $env:TEMP 'tunnelwind-cli-home'

if (-not (Test-Path $dotnet)) { throw "portable dotnet not found at $dotnet" }

$ver = (Select-String -Path (Join-Path $projectRoot 'src\TunnelWind.Core\Identity.cs') -Pattern 'AppVersion\s*=\s*"([^"]+)"').Matches[0].Groups[1].Value
Write-Host "== TunnelWind $ver release package ==" -ForegroundColor Cyan

# 1. engine staged once from the analysis extraction
$engineDir = Join-Path $projectRoot 'engine'
New-Item -ItemType Directory -Force $engineDir | Out-Null
$engineExe = Join-Path $engineDir 'psiphon-tunnel-core.exe'
$engineSrc = Join-Path (Split-Path -Parent $projectRoot) 'extracted\id131.bin'
if (-not (Test-Path $engineExe) -and (Test-Path $engineSrc)) {
    Copy-Item $engineSrc $engineExe
    Write-Host "engine: copied from $engineSrc" -ForegroundColor Yellow
}

# 2. stage: clean publish straight into the staging folder
$stage = Join-Path $PSScriptRoot 'stage'
if (Test-Path $stage) { Remove-Item -Recurse -Force $stage }
New-Item -ItemType Directory -Force $stage | Out-Null
& $dotnet publish (Join-Path $projectRoot 'src\TunnelWind.App\TunnelWind.App.csproj') `
    -c Release -f net8.0-windows -r win-x64 --self-contained false -o $stage | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'publish failed' }
if (Test-Path (Join-Path $stage 'engine')) { Remove-Item -Recurse -Force (Join-Path $stage 'engine') }

# 3. zip the whole app folder (UpdateService swaps folders, not single files)
$zipPath = Join-Path $stage "TunnelWind_$ver.zip"
if (Test-Path $zipPath) { Remove-Item $zipPath -Force }
Compress-Archive -Path (Join-Path $stage '*') -DestinationPath $zipPath -CompressionLevel Optimal
$appSha = (Get-FileHash $zipPath -Algorithm SHA256).Hash
$engineSha = (Get-FileHash $engineExe -Algorithm SHA256).Hash

# 4. manifest
$manifest = [ordered]@{
    appVersion    = $ver
    appUrl        = "$DeploymentUrl/TunnelWind_$ver.zip"
    appSha256     = $appSha
    engineUrl     = "$DeploymentUrl/psiphon-tunnel-core.exe"
    engineSha256  = $engineSha
    notes         = "Tunnel Wind v$ver"
}
$updateJsonPath = Join-Path $stage 'update.json'
$manifest | ConvertTo-Json -Depth 4 | Set-Content -Encoding UTF8 $updateJsonPath

Write-Host ''
Write-Host "staged:" -ForegroundColor Green
Write-Host "  $zipPath"
Write-Host "  $engineExe  (upload a copy of this too)"
Write-Host "manifest: $updateJsonPath"
Write-Host ''
Write-Host "Upload stage contents to: $DeploymentUrl" -ForegroundColor Yellow
Write-Host "Then set the channel in the app: manifest URL -> https://.../update.json" -ForegroundColor Yellow
