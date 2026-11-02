<#
.SYNOPSIS
  Builds the TunnelWind update channel package.

.DESCRIPTION
  1. Publishes the app (portable SDK in ..\tools\dotnet — no system install needed).
  2. Stages TunnelWind_<version>.exe + psiphon-tunnel-core.exe into update\stage\
     ready to upload to your HTTP(S) host.
  3. Generates update\update.json with correct version, URLs and SHA-256 hashes.

  Publish the stage folder + update.json to any static HTTPS host (S3, GitHub Pages,
  Nginx, …), then in the app press ⇅ and give it the manifest URL:
     https://YOUR_HOST/update.json

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

# 1. publish
$out = Join-Path $projectRoot 'output'
& $dotnet publish (Join-Path $projectRoot 'src\TunnelWind.App\TunnelWind.App.csproj') `
    -c Release -f net8.0-windows -r win-x64 --self-contained false -o $out | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'publish failed' }

# engine into output\engine (and keep meter updates pinned)
New-Item -ItemType Directory -Force (Join-Path $out 'engine') | Out-Null
$engineDir = Join-Path $projectRoot 'engine'
New-Item -ItemType Directory -Force $engineDir | Out-Null
$engineExe = Join-Path $engineDir 'psiphon-tunnel-core.exe'
$engineSrc = Join-Path (Split-Path -Parent $projectRoot) 'extracted\id131.bin'
if (-not (Test-Path $engineExe) -and (Test-Path $engineSrc)) { Copy-Item $engineSrc $engineExe }
Copy-Item $engineExe (Join-Path $out 'engine\psiphon-tunnel-core.exe') -Force

# 2. staging folder
$stage = Join-Path (Join-Path $projectRoot 'update') 'stage'
New-Item -ItemType Directory -Force $stage | Out-Null
$stagedApp = Join-Path $stage "TunnelWind_$ver.exe"
$stagedEngine = Join-Path $stage 'psiphon-tunnel-core.exe'
Copy-Item (Join-Path $out 'TunnelWind.exe') $stagedApp -Force
Copy-Item $engineExe $stagedEngine -Force

# 3. hashes + manifest
$appSha = (Get-FileHash $stagedApp -Algorithm SHA256).Hash
$engineSha = (Get-FileHash $stagedEngine -Algorithm SHA256).Hash

$manifest = [ordered]@{
    appVersion    = $ver
    appUrl        = "$DeploymentUrl/TunnelWind_$ver.exe"
    appSha256     = $appSha
    engineUrl     = "$DeploymentUrl/psiphon-tunnel-core.exe"
    engineSha256  = $engineSha
    notes         = "Tunnel Wind v$ver"
}
$updateJsonPath = Join-Path (Join-Path $projectRoot 'update') 'update.json'
$manifest | ConvertTo-Json -Depth 4 | Set-Content -Encoding UTF8 $updateJsonPath

Write-Host ''
Write-Host "staged:" -ForegroundColor Green
Write-Host "  $stagedApp"
Write-Host "  $stagedEngine"
Write-Host "manifest:" -ForegroundColor Green
Write-Host "  $updateJsonPath"
Write-Host ''
Write-Host "Upload the stage folder + update.json to: $DeploymentUrl" -ForegroundColor Yellow
Write-Host "Then set the channel in the app: press ⇅ -> https://.../update.json" -ForegroundColor Yellow
