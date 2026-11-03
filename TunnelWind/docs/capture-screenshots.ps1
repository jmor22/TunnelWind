param(
    [Parameter(Mandatory=$true)][string]$OutDir
)
$ErrorActionPreference = 'Stop'

Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
public class Capt {
    [DllImport("user32.dll", SetLastError=true)]
    public static extern bool PrintWindow(IntPtr hwnd, IntPtr hdc, uint flags);
    [DllImport("user32.dll", SetLastError=true)]
    public static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll", SetLastError=true)]
    public static extern bool ShowWindow(IntPtr hWnd, int cmd);
    [DllImport("user32.dll")]
    public static extern bool IsIconic(IntPtr hWnd);
    [DllImport("user32.dll")]
    public static extern bool GetWindowRect(IntPtr hWnd, out RECT r);
    [StructLayout(LayoutKind.Sequential)]
    public struct RECT { public int Left; public int Top; public int Right; public int Bottom; }
}
"@ -ReferencedAssemblies @()

Add-Type -AssemblyName System.Drawing

function Screenshot-Window([IntPtr]$hWnd, [string]$filePath) {
    [Capt]::ShowWindow($hWnd, 9) | Out-Null
    [Capt]::SetForegroundWindow($hWnd) | Out-Null
    Start-Sleep -Milliseconds 700

    $rect = New-Object Capt+RECT
    [Capt]::GetWindowRect($hWnd, [ref]$rect) | Out-Null
    $w = $rect.Right - $rect.Left
    $h = $rect.Bottom - $rect.Top
    if ($w -le 0 -or $h -le 0) { throw "zero-size window" }

    $bmp = New-Object System.Drawing.Bitmap($w, $h)
    $g   = [System.Drawing.Graphics]::FromImage($bmp)
    $hdc = $g.GetHdc()
    $ok  = [Capt]::PrintWindow($hWnd, $hdc, 2)
    $g.ReleaseHdc($hdc)
    $g.Dispose()
    if (-not $ok) { $bmp.Dispose(); throw 'PrintWindow failed' }
    $bmp.Save($filePath, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    Write-Host "captured: $filePath ($w x $h)"
}

# -------- state 1: freshly launched, disconnected --------
Get-Process TunnelWind -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Milliseconds 800
Start-Process -FilePath "$PWD\TunnelWind\output\TunnelWind.exe" -WorkingDirectory "$PWD\TunnelWind\output"
Start-Sleep -Seconds 3

$proc = Get-Process TunnelWind -ErrorAction Stop | Where-Object { $_.MainWindowHandle -ne 0 } | Select-Object -First 1
$hwnd = $proc.MainWindowHandle
if ($hwnd -eq [IntPtr]::Zero) { throw 'no window handle' }

New-Item -ItemType Directory -Force $OutDir | Out-Null
Screenshot-Window $hwnd (Join-Path $OutDir 'screenshot-disconnected.png')

# -------- state 2: connected (click CONNECT via UIA) --------
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes
$root = [System.Windows.Automation.AutomationElement]::RootElement
$cond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::NameProperty, 'Tunnel Wind')
$win = $root.FindFirst([System.Windows.Automation.TreeScope]::Children, $cond)
if ($win -eq $null) { throw 'window not found via UIA' }
$btnCond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::NameProperty, 'CONNECT')
$btn = $win.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $btnCond)
if ($btn -eq $null) { throw 'CONNECT button not found' }
($btn.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern)).Invoke()
Write-Host 'CONNECT clicked'

# wait for tunnel: local port 8080 or 1080 listening
$deadline = (Get-Date).AddSeconds(75)
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 2
    $proc2 = Get-Process psiphon-tunnel-core -ErrorAction SilentlyContinue
    if ($proc2) {
        $pid2 = ($proc2 | Select-Object -First 1).Id
        $ports = @(Get-NetTCPConnection -State Listen -OwningProcess $pid2 -ErrorAction SilentlyContinue)
        $hit = $ports | Where-Object { $_.LocalPort -eq 8080 -or $_.LocalPort -eq 1080 }
        if ($hit) { break }
    }
}
Start-Sleep -Seconds 4
Screenshot-Window $hwnd (Join-Path $OutDir 'screenshot-connected.png')

# -------- state 3: settings window --------
[Capt]::SetForegroundWindow([IntPtr]::Zero) | Out-Null
$dlgCond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ClassNameProperty, '#32670')
# open via ⚙ button
$gearCond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::HelpTextProperty, 'Settings')
$gear = $win.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $gearCond)
if ($gear) {
    ($gear.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern)).Invoke()
    Start-Sleep -Seconds 2
    # find the settings dialog by title
    $dlgCond2 = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::NameProperty, 'Tunnel Wind - Settings')
    $dlg = $root.FindFirst([System.Windows.Automation.TreeScope]::Children, $dlgCond2)
    if ($dlg) {
        $hDlg = [System.Windows.Automation.AutomationElement]::NativeWindowHandleProperty
        $h = $dlg.Current.NativeWindowHandle
        Screenshot-Window ([IntPtr]$h) (Join-Path $OutDir 'screenshot-settings.png')
        # cancel it
        $cancelCond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::NameProperty, 'Cancel')
        $c = $dlg.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $cancelCond)
        if ($c) { ($c.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern)).Invoke() | Out-Null }
        Start-Sleep -Milliseconds 700
    }
}

# -------- state 4: disconnect again (restore) --------
$btn2 = $win.FindFirst([System.Windows.Automation.TreeScope]::Descendants, (New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::NameProperty, 'DISCONNECT')))
if ($btn2) { ($btn2.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern)).Invoke() }
Write-Host 'done'
