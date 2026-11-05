using System.Diagnostics;
using System.IO.Compression;
using System.Reflection;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Serialization;
using Thread = System.Threading.Thread;

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
    public static string NewPackagePath => Path.Combine(AppDirectory, "TunnelWind.update.zip");
    public static string OldAppPath => Path.Combine(AppDirectory, "TunnelWind.exe.old");

    public static string CurrentAppVersion =>
        Assembly.GetExecutingAssembly().GetName().Version?.ToString(3) ?? "0.0.0";

    // ---------------- manifest ----------------

    public static async Task<UpdateManifest?> FetchManifestAsync(string url, CancellationToken ct = default)
    {
        using var http = new HttpClient();
        http.Timeout = TimeSpan.FromSeconds(15);
        var json = await http.GetStringAsync(url, ct).ConfigureAwait(false);
        // tolerate a UTF-8 BOM (some editors/scripts emit one) and surrounding whitespace
        json = json.TrimStart('\uFEFF', ' ', '\t', '\r', '\n');
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

    /// <summary>Downloads the app package (whole-folder ZIP) into AppDirectory as TunnelWind.update.zip (verified).</summary>
    public static async Task PrepareAppUpdateAsync(UpdateManifest manifest, CancellationToken ct)
    {
        if (string.IsNullOrWhiteSpace(manifest.AppUrl) ||
            string.IsNullOrWhiteSpace(manifest.AppSha256))
        {
            throw new InvalidOperationException("manifest has no app package");
        }
        try { File.Delete(NewPackagePath); } catch { }
        await DownloadToAsync(manifest.AppUrl, NewPackagePath, ct).ConfigureAwait(false);
        var hash = Sha256File(NewPackagePath);
        if (!hash.Equals(manifest.AppSha256.Trim(), StringComparison.OrdinalIgnoreCase))
        {
            try { File.Delete(NewPackagePath); } catch { }
            throw new InvalidOperationException($"app hash mismatch: expected {manifest.AppSha256}, got {hash}");
        }
    }

    /// <summary>
    /// Prepares the swap: extracts the verified package to a temp folder and starts
    /// a detached helper that waits for this process to exit, copies the files over
    /// the app folder and relaunches. Windows keeps loaded assemblies locked, so an
    /// in-place overwrite from the running process can never work.
    /// Call right before Application exit.
    /// </summary>
    public static string ApplyAppSwap()
    {
        if (!File.Exists(NewPackagePath))
        {
            throw new InvalidOperationException("no prepared update package");
        }

        var stagingDir = Path.Combine(Path.GetTempPath(), "TunnelWindUpdate_" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(stagingDir);
        try
        {
            System.IO.Compression.ZipFile.ExtractToDirectory(NewPackagePath, stagingDir, overwriteFiles: true);
        }
        catch
        {
            try { Directory.Delete(stagingDir, recursive: true); } catch { }
            throw;
        }

        var logPath = Path.Combine(Path.GetTempPath(), "TunnelWindUpdate.log");
        var scriptPath = Path.Combine(Path.GetTempPath(), "TunnelWindUpdate_" + Guid.NewGuid().ToString("N") + ".ps1");
        var pid = Environment.ProcessId;

        static string Q(string value) => "'" + value.Replace("'", "''") + "'";

        var script = string.Join(Environment.NewLine, new[]
        {
            "$ErrorActionPreference = 'Stop'",
            "$log = " + Q(logPath),
            "try {",
            "  Wait-Process -Id " + pid + " -Timeout 60 -ErrorAction SilentlyContinue",
            "  Start-Sleep -Milliseconds 600",
            "  Copy-Item -Path " + Q(Path.Combine(stagingDir, "*")) + " -Destination " + Q(AppDirectory) + " -Recurse -Force",
            "  Remove-Item -Path " + Q(stagingDir) + " -Recurse -Force -ErrorAction SilentlyContinue",
            "  Remove-Item -Path " + Q(NewPackagePath) + " -Force -ErrorAction SilentlyContinue",
            "  Start-Process -FilePath " + Q(AppExePath) + " -WorkingDirectory " + Q(AppDirectory) + " -WindowStyle Normal",
            "  'ok' | Out-File -FilePath $log -Encoding utf8",
            "} catch {",
            "  ($_ | Out-String) | Out-File -FilePath $log -Encoding utf8",
            "}",
            "Remove-Item -Path $MyInvocation.MyCommand.Path -Force -ErrorAction SilentlyContinue",
        });

        File.WriteAllText(scriptPath, script, new System.Text.UTF8Encoding(false));

        var psi = new ProcessStartInfo
        {
            FileName = "powershell.exe",
            Arguments = "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File " + "\"" + scriptPath + "\"",
            UseShellExecute = false,
            CreateNoWindow = true,
        };
        Process.Start(psi);

        return AppExePath;
    }

    /// <summary>Cleanup pass for the swap leftovers (called at startup).</summary>
    public static void CleanupSwapLeftovers()
    {
        try { if (File.Exists(OldAppPath)) { File.Delete(OldAppPath); } } catch { }
        try { if (File.Exists(NewPackagePath)) { File.Delete(NewPackagePath); } } catch { }
    }
}
