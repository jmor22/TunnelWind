using System.Diagnostics;
using System.Reflection;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace TunnelWind.Core;

// ----------------------------------------------------------------------------
// Update channel contract (TunnelWind OWN channel — independent from S3 upgrade):
//
// The operator hosts a JSON manifest:
// {
//   "appVersion"    : "1.1.0",
//   "appUrl"        : "https://example.com/TunnelWind_v1.1.0.exe",
//   "appSha256"     : "<sha256 hex of that file>",          (required when appUrl set)
//   "engineSha256"  : "<sha256 hex of tunnel-core>",        (required when engineUrl set)
//   "engineUrl"     : "https://example.com/psiphon-tunnel-core.exe",
//   "notes"         : "short release note"
// }
//
// Security model, kept explicit and simple:
//  - The manifest URL is fetched over HTTPS and identifies TODAY's hosting.
//  - Every package is verified against the sha256 in the manifest before install.
//  - The engine's integrity pin (engine.sha256) is rotated to the verified hash.
//  - The app swap uses the Windows name-rename dance (running exe may be renamed).
// ----------------------------------------------------------------------------

public sealed class UpdateManifest
{
    [JsonPropertyName("appVersion")] public string AppVersion { get; set; } = "";
    [JsonPropertyName("appUrl")] public string? AppUrl { get; set; }
    [JsonPropertyName("appSha256")] public string? AppSha256 { get; set; }
    [JsonPropertyName("engineUrl")] public string? EngineUrl { get; set; }
    [JsonPropertyName("engineSha256")] public string? EngineSha256 { get; set; }
    [JsonPropertyName("notes")] public string? Notes { get; set; }
}

public static class UpdateService
{
    public static string AppDirectory => AppDomain.CurrentDomain.BaseDirectory;
    public static string AppExePath => Path.Combine(AppDirectory, "TunnelWind.exe");
    public static string NewAppPath => Path.Combine(AppDirectory, "TunnelWind.new");
    public static string OldAppPath => Path.Combine(AppDirectory, "TunnelWind.exe.old");

    public static string CurrentAppVersion =>
        Assembly.GetExecutingAssembly().GetName().Version?.ToString(3) ?? "0.0.0";

    // ---------------- manifest ----------------

    public static async Task<UpdateManifest?> FetchManifestAsync(string url, CancellationToken ct = default)
    {
        using var http = new HttpClient();
        http.Timeout = TimeSpan.FromSeconds(15);
        var json = await http.GetStringAsync(url, ct).ConfigureAwait(false);
        return JsonSerializer.Deserialize<UpdateManifest>(json);
    }

    /// <summary>MAJOR.MINOR.PATCH numeric comparison; negative when a < b.</summary>
    public static int CompareVersions(string a, string b)
    {
        Version.TryParse(a.TrimStart('v', 'V'), out var va);
        Version.TryParse(b.TrimStart('v', 'V'), out var vb);
        if (va == null || vb == null) return string.CompareOrdinal(a, b);
        return va.CompareTo(vb);
    }

    // ---------------- downloads ----------------

    private static async Task DownloadToAsync(string url, string dest, CancellationToken ct)
    {
        using var http = new HttpClient();
        http.Timeout = TimeSpan.FromSeconds(120);
        var bytes = await http.GetByteArrayAsync(url, ct).ConfigureAwait(false);
        await File.WriteAllBytesAsync(dest, bytes, ct).ConfigureAwait(false);
    }

    public static string Sha256File(string path)
    {
        using var sha = SHA256.Create();
        using var stream = File.OpenRead(path);
        var sb = new System.Text.StringBuilder();
        foreach (var b in sha.ComputeHash(stream)) sb.Append(b.ToString("x2"));
        return sb.ToString();
    }

    /// <summary>Downloads the engine, verifies sha, installs into the engine dir and rotates the pin.</summary>
    public static async Task InstallEngineUpdateAsync(UpdateManifest manifest, string engineDest, CancellationToken ct)
    {
        if (string.IsNullOrWhiteSpace(manifest.EngineUrl) ||
            string.IsNullOrWhiteSpace(manifest.EngineSha256))
        {
            throw new InvalidOperationException("manifest has no engine package");
        }
        var temp = engineDest + ".update";
        try
        {
            await DownloadToAsync(manifest.EngineUrl, temp, ct).ConfigureAwait(false);
            var hash = Sha256File(temp);
            if (!hash.Equals(manifest.EngineSha256.Trim(), StringComparison.OrdinalIgnoreCase))
            {
                throw new InvalidOperationException($"engine hash mismatch: expected {manifest.EngineSha256}, got {hash}");
            }
            File.Move(temp, engineDest, overwrite: true);
            // rotate the integrity pin to the verified actual bytes
            var pin = EngineIntegrity.PinPath;
            Directory.CreateDirectory(Path.GetDirectoryName(pin)!);
            File.WriteAllText(pin, hash + "\n");
        }
        finally
        {
            try { File.Delete(temp); } catch { }
        }
    }

    /// <summary>Downloads the new app into AppDirectory as TunnelWind.new (verified).</summary>
    public static async Task PrepareAppUpdateAsync(UpdateManifest manifest, CancellationToken ct)
    {
        if (string.IsNullOrWhiteSpace(manifest.AppUrl) ||
            string.IsNullOrWhiteSpace(manifest.AppSha256))
        {
            throw new InvalidOperationException("manifest has no app package");
        }
        try { File.Delete(NewAppPath); } catch { }
        await DownloadToAsync(manifest.AppUrl, NewAppPath, ct).ConfigureAwait(false);
        var hash = Sha256File(NewAppPath);
        if (!hash.Equals(manifest.AppSha256.Trim(), StringComparison.OrdinalIgnoreCase))
        {
            try { File.Delete(NewAppPath); } catch { }
            throw new InvalidOperationException($"app hash mismatch: expected {manifest.AppSha256}, got {hash}");
        }
    }

    /// <summary>
    /// Atomically swaps TunnelWind.new in place of the running exe and returns
    /// the path to launch (the same TunnelWind.exe path, now the new binary).
    /// Call right before Application exit.
    /// </summary>
    public static string ApplyAppSwap()
    {
        if (!File.Exists(NewAppPath)) throw new InvalidOperationException("no prepared update package");

        try { File.Delete(OldAppPath); } catch { }

        // running exe may be renamed on Windows
        File.Move(AppExePath, OldAppPath, overwrite: true);
        File.Move(NewAppPath, AppExePath, overwrite: false);

        // best effort: launch the new version
        Process.Start(new ProcessStartInfo
        {
            FileName = AppExePath,
            UseShellExecute = true,
            WorkingDirectory = AppDirectory,
        });
        return AppExePath;
    }

    /// <summary>Cleanup pass for the swap leftovers (called at startup).</summary>
    public static void CleanupSwapLeftovers()
    {
        try { if (File.Exists(OldAppPath)) { File.Delete(OldAppPath); } } catch { }
        try { if (File.Exists(NewAppPath)) { File.Delete(NewAppPath); } } catch { }
    }
}
