using System.Security.Cryptography;
using System.Text;

namespace TunnelWind.Core;

/// <summary>
/// Engine binary integrity check (single source of truth).
///  1. hash the app-bundled engine and the LocalAppData engine;
///  2. if they differ BUT the pin matches the bundled hash, the LocalAppData copy
///     was clobbered by an older build - re-sync from the bundle (self-heal);
///  3. pass when LocalAppData hash == pin (re-pinning on first run);
///  4. fail when the pin matches neither (external tamper/corruption).
/// </summary>
public static class EngineIntegrity
{
    public static string PinPath => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "TunnelWind", "engine.sha256");

    public static string HashFile(string path)
    {
        using var sha = SHA256.Create();
        using var stream = File.OpenRead(path);
        var sb = new StringBuilder(64);
        foreach (var b in sha.ComputeHash(stream)) sb.Append(b.ToString("x2"));
        return sb.ToString();
    }

    /// <summary>Returns (ok, message).</summary>
    public static (bool Ok, string Message) Verify(string bundledEnginePath, string localEnginePath)
    {
        if (!File.Exists(bundledEnginePath))
            return (false, "[engine] bundled engine missing: " + bundledEnginePath);

        var bundledHash = HashFile(bundledEnginePath);
        var localHash = File.Exists(localEnginePath) ? HashFile(localEnginePath) : null;

        var pinFile = PinPath;
        string? pin = File.Exists(pinFile) ? File.ReadAllText(pinFile).Trim() : null;

        // first run: pin whatever we just installed
        if (string.IsNullOrEmpty(pin))
        {
            var firstHash = localHash ?? bundledHash;
            try
            {
                Directory.CreateDirectory(Path.GetDirectoryName(pinFile)!);
                File.WriteAllText(pinFile, firstHash + "\n");
            }
            catch
            {
                return (true, "[engine] pin file could not be created (continuing)");
            }
            return (true, "[engine] integrity pinned: " + firstHash);
        }

        // self-heal: LocalAppData clobbered by an older/newer bundle copy while the
        // pin still matches the app's bundled engine
        if (localHash != null &&
            !string.Equals(localHash, bundledHash, StringComparison.OrdinalIgnoreCase) &&
            string.Equals(pin, bundledHash, StringComparison.OrdinalIgnoreCase))
        {
            try
            {
                File.Copy(bundledEnginePath, localEnginePath, overwrite: true);
                return (true, "[engine] engine re-synced from app bundle (pin matched bundle)");
            }
            catch (Exception e)
            {
                return (false, "[engine] re-sync failed: " + e.Message);
            }
        }

        var effective = localHash ?? bundledHash;
        if (string.Equals(effective, pin, StringComparison.OrdinalIgnoreCase))
            return (true, "[engine] integrity OK");

        return (false, "[engine] INTEGRITY CHECK FAILED: engine binary hash does not match the pin record.");
    }
}
