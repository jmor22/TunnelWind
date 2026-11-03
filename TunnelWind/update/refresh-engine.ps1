<#
.SYNOPSIS
  Tunnel Wind - engine refresh (regeneration) script.

.DESCRIPTION
  Pulls the freshest official client binary from Psiphon's own S3 upgrade channel,
  decodes the envelope (gzip + JSON + base64), unpacks UPX if present, locates the
  embedded psiphon-tunnel-core.exe, and swaps it into TunnelWind/engine/ if-and-only-if
  the SHA-256 differs. Rotates the local engine integrity pin so the next launch
  re-pins the new hash automatically.
#>
param(
    [string]$UpgradeUrl = 'https://s3.amazonaws.com/psiphon/web/mjr4-p23r-puwl/psiphon3.exe.upgrade'
)
$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$engineDir   = Join-Path $projectRoot 'TunnelWind\engine'
$upxDir      = Join-Path $projectRoot 'tools\upx'
$upxPath     = Get-ChildItem -Recurse $upxDir -Filter upx.exe -ErrorAction SilentlyContinue |
               Select-Object -First 1 -ExpandProperty FullName
$target      = Join-Path $engineDir 'psiphon-tunnel-core.exe'

New-Item -ItemType Directory -Force $engineDir | Out-Null

function Get-CurrentEngineSha {
    if (Test-Path $target) { (Get-FileHash $target -Algorithm SHA256).Hash }
    else { $null }
}

Write-Host "== Tunnel Wind engine refresh (regeneration) ==" -ForegroundColor Cyan
$currentSha = Get-CurrentEngineSha
if ($currentSha) {
    Write-Host ("current engine sha256: " + $currentSha)
} else {
    Write-Host "current engine missing - will fetch"
}

Write-Host "HEAD $UpgradeUrl"
# direct (no-proxy) WebClient: the tunnel's own local proxy can not tunnel S3
$wc = New-Object System.Net.WebClient
$wc.Proxy = $null
$wc.Headers.Add('User-Agent', 'tunnelwind-engine-refresh')
Write-Host "download..."
$env:TMPDIR = Join-Path $env:TEMP 'tunnelwind-engine-refresh'
New-Item -ItemType Directory -Force $env:TMPDIR | Out-Null
$wc.DownloadFile($UpgradeUrl, "$env:TMPDIR\upgrade.gz")

Add-Type -AssemblyName System.IO.Compression
$ms = New-Object System.IO.MemoryStream (,[IO.File]::ReadAllBytes("$env:TMPDIR\upgrade.gz"))
$gz = New-Object System.IO.Compression.GZipStream($ms, [System.IO.Compression.CompressionMode]::Decompress)
$outMs = New-Object System.IO.MemoryStream
$gz.CopyTo($outMs)
$envelope = [Text.Encoding]::UTF8.GetString($outMs.ToArray())
$data = ($envelope | ConvertFrom-Json).data
$pe = [Convert]::FromBase64String($data)
Write-Host ("decoded PE: {0:N0} bytes" -f $pe.Length)
[IO.File]::WriteAllBytes("$env:TMPDIR\fresh.psiphon3.exe", $pe)
$freshSha = (Get-FileHash "$env:TMPDIR\fresh.psiphon3.exe" -Algorithm SHA256).Hash
Write-Host ("fresh psiphon3.exe sha256: " + $freshSha)

$unpacked = "$env:TMPDIR\fresh.unpacked.exe"
Remove-Item $unpacked -ErrorAction SilentlyContinue
if ($upxPath -and (Test-Path $upxPath)) {
    Write-Host "upx-unpack..."
    & $upxPath -d -o $unpacked "$env:TMPDIR\fresh.psiphon3.exe" | Out-Null
}

if (Test-Path $unpacked) {
    $u = Get-Item $unpacked
    Write-Host ("unpacked: {0:N0} bytes" -f $u.Length)
} else {
    Write-Warning "upx unavailable, falling back to fresh PE (non-packed assumption)"
    Copy-Item "$env:TMPDIR\fresh.psiphon3.exe" $unpacked -Force
}

# locate the embedded tunnel-core PE by Go module-path signature
$marker = 'path' + [char]9 + 'github.com/Psiphon-Labs/psiphon-tunnel-core/ConsoleClient' + [char]10 + 'mod' + [char]9 + 'github.com/Psiphon-Labs/psiphon-tunnel-core'
$model = [Text.Encoding]::ASCII.GetBytes($marker)
$bytes = [IO.File]::ReadAllBytes($unpacked)
$foundIdx = -1
for ($i = 0; $i -lt ($bytes.Length - $model.Length); $i++) {
    if ($bytes[$i] -eq $model[0]) {
        $match = $true
        for ($j = 1; $j -lt $model.Length; $j++) {
            if ($bytes[$i + $j] -ne $model[$j]) { $match = $false; break }
        }
        if ($match) { $foundIdx = $i; break }
    }
}
if ($foundIdx -lt 0) { throw "tunnel-core marker not found in unpacked binary" }
Write-Host ("marker at offset: {0}" -f $foundIdx)

$mzStart = -1
for ($i = $foundIdx; $i -gt 0; $i--) {
    if ($bytes[$i] -eq 0x4D -and $bytes[$i + 1] -eq 0x5A -and $bytes[$i + 2] -eq 0x90 -and $bytes[$i + 3] -eq 0x00) {
        $mzStart = $i; break
    }
}
if ($mzStart -lt 0) { throw "MZ header not found near the tunnel-core marker" }
Write-Host ("nearest MZ header at offset: {0}" -f $mzStart)

# compute exact inner-PE file length by walking its section table
# (e_lfanew is relative to the MZ start, so add mzStart)
$eLfanew = $mzStart + [BitConverter]::ToInt32($bytes, $mzStart + 0x3C)
if ([BitConverter]::ToUInt16($bytes, $eLfanew) -ne 0x4550) { throw "invalid PE signature" }
$numSections = [BitConverter]::ToUInt16($bytes, $eLfanew + 6)
$optSize = [BitConverter]::ToUInt16($bytes, $eLfanew + 20)
$secStart = $eLfanew + 24 + $optSize
$peLen = 0
for ($s = 0; $s -lt $numSections; $s++) {
    $o = $secStart + 40 * $s
    $rawSize = [BitConverter]::ToUInt32($bytes, $o + 16)
    $rawPtr  = [BitConverter]::ToUInt32($bytes, $o + 20)
    if ($rawPtr + $rawSize -gt $peLen) { $peLen = [int]($rawPtr + $rawSize) }
}
Write-Host ("inner PE length: {0:N0} bytes" -f $peLen)

$carved = New-Object byte[] $peLen
[Array]::Copy($bytes, $mzStart, $carved, 0, $peLen)
[IO.File]::WriteAllBytes("$env:TMPDIR\tunnel-core.carved.exe", $carved)
Write-Host ("carved: {0:N0} bytes" -f $carved.Length)

$carvedSha = (Get-FileHash "$env:TMPDIR\tunnel-core.carved.exe" -Algorithm SHA256).Hash
Write-Host ("carved engine sha256: $carvedSha")
if ($currentSha -eq $carvedSha) {
    Write-Host "engine already up to date (identical sha256). Nothing to do." -ForegroundColor Green
    exit 0
}

Write-Host "swapping engine into $target ..." -ForegroundColor Yellow
Copy-Item "$env:TMPDIR\tunnel-core.carved.exe" $target -Force

# also refresh the build-side copy the app auto-uses (output\engine) if present
$outputEngine = Join-Path (Join-Path $projectRoot 'TunnelWind') 'output\engine\psiphon-tunnel-core.exe'
if (Test-Path (Split-Path -Parent $outputEngine)) {
    Copy-Item "$env:TMPDIR\tunnel-core.carved.exe" $outputEngine -Force
    Write-Host "also refreshed output\engine copy" -ForegroundColor Yellow
}

$pinDir = Join-Path $env:LOCALAPPDATA 'TunnelWind'
New-Item -ItemType Directory -Force $pinDir | Out-Null
Remove-Item (Join-Path $pinDir 'engine.sha256') -Force -ErrorAction SilentlyContinue
Write-Host "integrity pin reset - next app run will re-pin the fresh engine." -ForegroundColor Green

Write-Host ""
Write-Host "engine refresh complete. Launch the app to verify." -ForegroundColor Cyan
