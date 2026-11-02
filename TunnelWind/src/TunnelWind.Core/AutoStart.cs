using Microsoft.Win32;
using System.Reflection;

namespace TunnelWind.Core;

/// <summary>
/// "Start with Windows" per-user (HKCU\...\Run). No admin required.
/// </summary>
public static class AutoStart
{
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string ValueName = "TunnelWind";

    public static void Set(bool enabled)
    {
        using var key = Registry.CurrentUser.OpenSubKey(RunKey, writable: true)
            ?? Registry.CurrentUser.CreateSubKey(RunKey);
        if (enabled)
        {
            var exe = Assembly.GetExecutingAssembly().Location;
            if (string.IsNullOrEmpty(exe)) return;
            key.SetValue(ValueName, $"\"{exe}\"", RegistryValueKind.String);
        }
        else
        {
            try { key.DeleteValue(ValueName, throwOnMissingValue: false); } catch { }
        }
    }
}
