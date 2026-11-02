using System.Runtime.InteropServices;
using System.Text.Json;
using System.Text.Json.Serialization;
using Microsoft.Win32;

namespace TunnelWind.Core;

/// <summary>
/// Windows system proxy control (user scope, HKCU), with crash-safe behavior:
///   - whenever we Enable a proxy, the previous user proxy configuration is
///     persisted to %LOCALAPPDATA%\TunnelWind\proxy_backup.json, so a restore can
///     happen from a later process even after force-kill;
///   - EnsureStaleClear(): at app startup, if the system proxy points at our own
///     local port while the tunnel is stopped (leftover from a crash/hard kill),
///     clear it so the user never ends up without internet.
/// </summary>
public static class SystemProxy
{
    private const string InternetSettingsKey =
        @"Software\Microsoft\Windows\CurrentVersion\Internet Settings";

    private const int InternetOptionSettingsChanged = 39;
    private const int InternetOptionRefresh = 37;

    private static bool _wasSet;
    private static bool _oldProxyEnable;
    private static string _oldProxyServer = "";
    private static string _oldProxyOverride = "";

    private sealed class ProxyBackup
    {
        [JsonPropertyName("enabled")] public bool Enabled { get; set; }
        [JsonPropertyName("server")] public string Server { get; set; } = "";
        [JsonPropertyName("overrideList")] public string OverrideList { get; set; } = "";
    }

    private static string BackupPath => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "TunnelWind", "proxy_backup.json");

    public static void Apply(int httpPort, string proxyServerOverride = "127.0.0.1")
    {
        var key = Registry.CurrentUser.OpenSubKey(InternetSettingsKey, writable: true)
            ?? throw new InvalidOperationException("failed to open Internet Settings");

        using (key)
        {
            if (!_wasSet)
            {
                _oldProxyEnable = (int?)key.GetValue("ProxyEnable", 0) == 1;
                _oldProxyServer = (string?)key.GetValue("ProxyServer", "") ?? "";
                _oldProxyOverride = (string?)key.GetValue("ProxyOverride", "") ?? "";
                PersistBackup(new ProxyBackup
                {
                    Enabled = _oldProxyEnable,
                    Server = _oldProxyServer,
                    OverrideList = _oldProxyOverride,
                });
            }

            key.SetValue("ProxyEnable", 1, RegistryValueKind.DWord);
            key.SetValue("ProxyServer", $"{proxyServerOverride}:{httpPort}", RegistryValueKind.String);
            key.SetValue("ProxyOverride", "localhost;127.*;10.*;172.16.*;172.17.*;172.18.*;172.19.*;172.20.*;172.21.*;172.22.*;172.23.*;172.24.*;172.25.*;172.26.*;172.27.*;172.28.*;172.29.*;172.30.*;172.31.*;192.168.*;<local>", RegistryValueKind.String);
        }
        _wasSet = true;
        BroadcastChange();
    }

    public static void Restore()
    {
        var backup = _wasSet
            ? new ProxyBackup { Enabled = _oldProxyEnable, Server = _oldProxyServer, OverrideList = _oldProxyOverride }
            : LoadBackup();

        var key = Registry.CurrentUser.OpenSubKey(InternetSettingsKey, writable: true);
        if (key != null && backup != null)
        {
            using (key)
            {
                key.SetValue("ProxyEnable", backup.Enabled ? 1 : 0, RegistryValueKind.DWord);
                if (!string.IsNullOrEmpty(backup.Server)) key.SetValue("ProxyServer", backup.Server, RegistryValueKind.String);
                else key.DeleteValue("ProxyServer", false);
                if (!string.IsNullOrEmpty(backup.OverrideList)) key.SetValue("ProxyOverride", backup.OverrideList, RegistryValueKind.String);
                else key.DeleteValue("ProxyOverride", false);
            }
        }
        _wasSet = false;
        try { if (File.Exists(BackupPath)) File.Delete(BackupPath); } catch { }
        BroadcastChange();
    }

    /// <summary>
    /// Startup guard: if a proxy pointing at 127.0.0.1:&lt;our port&gt; is active but we
    /// are not the ones managing it (tunnel stopped), clear it — the leftover can
    /// only come from a previous crash between Apply and Restore.
    /// </summary>
    public static void EnsureStaleClear(int expectedHttpPort)
    {
        try
        {
            var key = Registry.CurrentUser.OpenSubKey(InternetSettingsKey);
            if (key == null) return;
            using (key)
            {
                var enabled = (int?)key.GetValue("ProxyEnable", 0) == 1;
                if (!enabled) return;
                var server = (string?)key.GetValue("ProxyServer", "") ?? "";

                var ours = server.Contains("127.0.0.1", StringComparison.OrdinalIgnoreCase) &&
                           (server.Contains($":{expectedHttpPort}", StringComparison.OrdinalIgnoreCase) ||
                            server.Trim() == "127.0.0.1:8080");
                if (!ours) return;
            }

            // leftover from a dead session — clear it (Restore honors the saved backup)
            Restore();
        }
        catch
        {
            // never break startup on proxy logic failure
        }
    }

    public static bool IsCurrentlySet => _wasSet;

    private static void PersistBackup(ProxyBackup backup)
    {
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(BackupPath)!);
            File.WriteAllText(BackupPath, JsonSerializer.Serialize(backup));
        }
        catch { }
    }

    private static ProxyBackup? LoadBackup()
    {
        try
        {
            if (File.Exists(BackupPath))
            {
                return JsonSerializer.Deserialize<ProxyBackup>(File.ReadAllText(BackupPath));
            }
        }
        catch { }
        return null;
    }

    private static void BroadcastChange()
    {
        InternetSetOption(IntPtr.Zero, InternetOptionSettingsChanged, IntPtr.Zero, 0);
        InternetSetOption(IntPtr.Zero, InternetOptionRefresh, IntPtr.Zero, 0);
    }

    [DllImport("wininet.dll", SetLastError = true)]
    private static extern bool InternetSetOption(IntPtr hInternet, int dwOption, IntPtr lpBuffer, int dwBufferLength);
}
