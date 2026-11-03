# Local update-channel test server: serves update\stage\ files over plain HTTP on
# http://127.0.0.1:8099/ so the app's update flow can be drilled without hosting.
$ErrorActionPreference = 'Stop'
$root = Join-Path $PSScriptRoot 'stage'

if (-not (Test-Path (Join-Path $root 'update.json'))) { throw "no update.json in $root" }

$sep = [string][System.IO.Path]::DirectorySeparatorChar
$listener = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Loopback, 8099)
$listener.Start()
Write-Host "stage served: $root"
Write-Host "URL: http://127.0.0.1:8099/update.json"

while ($true) {
    $client = $listener.AcceptTcpClient()
    try {
        $stream = $client.GetStream()
        $reader = New-Object System.IO.StreamReader($stream)
        $requestLine = $reader.ReadLine()
        if (-not $requestLine) { $client.Close(); continue }
        $path = ($requestLine -split ' ')[1].TrimStart('/')
        if ($path -eq '') { $path = 'update.json' }
        $file = Join-Path $root ($path -replace '/', $sep)
        if (-not (Test-Path $file) -or (Get-Item $file).PSIsContainer) {
            $resp = [System.Text.Encoding]::ASCII.GetBytes("HTTP/1.0 404 Not Found`r`nContent-Length: 0`r`nConnection: close`r`n`r`n")
            $stream.Write($resp, 0, $resp.Length)
        } else {
            $bytes = [System.IO.File]::ReadAllBytes($file)
            $header = [System.Text.Encoding]::ASCII.GetBytes(
                "HTTP/1.0 200 OK`r`nContent-Type: application/octet-stream`r`nContent-Length: $($bytes.Length)`r`nConnection: close`r`n`r`n")
            $stream.Write($header, 0, $header.Length)
            $stream.Write($bytes, 0, $bytes.Length)
        }
        $stream.Flush()
    } catch { }
    finally { $client.Close() }
}
