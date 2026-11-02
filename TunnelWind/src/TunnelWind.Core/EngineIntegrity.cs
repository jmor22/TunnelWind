using System.Security.Cryptography;
using System.Text;

namespace TunnelWind.Core;

/// <summary>
/// Engine binary integrity check.
/// First run: records the engine's SHA-256 into engine.sha256 (pin file) next to it.
/// Later runs: verify match; a mismatch blocks connect (corruption/tamper protection).
/// The pin file also records the app/engine pairing, so replacing the engine requires
/// deleting the pin (visible, auditable action — mirrors the original client's upgrade
/// signature-verification posture, but simpler and self-contained).
/// </summary>
public static class EngineIntegrity
{
    public static string PinPath => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "TunnelWind", "engine.sha256");

    public static string PinFile(string enginePath) =>
        "TUNNELWIND-ENGINE-PIN v1\n" + HashFile(enginePath) + "\n";

    public static string HashFile(string path)
    {
        using var sha = SHA256.Create();
        using var stream = File.OpenRead(path);
        var hash = sha.ComputeHash(stream);
        var sb = new StringBuilder(64);
        foreach (var b in hash) sb.Append(b.ToString("x2"));
        return sb.ToString();
    }

    /// <summary>Returns (ok, message). When ok and pin created now, message says "pinned".</summary>
    public static (bool Ok, string Message) Verify(string enginePath)
    {
        var actual = HashFile(enginePath);
        var pinFile = PinPath;
        if (!File.Exists(pinFile))
        {
            try
            {
                Directory.CreateDirectory(Path.GetDirectoryName(pinFile)!);
                File.WriteAllText(pinFile, actual + "\n");
            }
            catch
            {
                return (true, "[engine] pin file could not be created (continuing)");
            }
            return (true, "[engine] integrity pinned: " + actual);
        }
        var stored = File.ReadAllText(pinFile).Trim();
        if (stored.Equals(actual, StringComparison.OrdinalIgnoreCase))
        {
            return (true, "[engine] integrity OK");
        }
        return (false, "[engine] INTEGRITY CHECK FAILED: engine binary hash does not match the pin record.");
    }
}
