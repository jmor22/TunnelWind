using System.IO.Compression;
using System.Text;
using System.Text.Json;

namespace TunnelWind.Core;

/// <summary>
/// Fetches the Psiphon remote server list over standard Windows TLS and writes it
/// where the engine's own datastore expects it (DataRootDirectory/ca.psiphon.PsiphonTunnel.tunnel-core/remote_server_list,
/// raw compressed bytes). Why: the engine's tuned Go TLS dialer intermittently fails
/// (EOF/DNS) while standard SChannel TLS reaches the same endpoints. The engine
/// verifies the downloaded payload itself (RSA + per-entry Ed25519 signatures).
/// Wire format verified: zlib(78 9C) of {"data": &lt;hex-list&gt;, signature..., signingPublicKeyDigest...}.
/// </summary>
public static class ServerListFetcher
{
    private static readonly string[] FetchUrls =
    [
        "https://s3.amazonaws.com/psiphon/web/mjr4-p23r-puwl/server_list_compressed",
        "https://www.blogsfmcancercitizen.com/web/mjr4-p23r-puwl/server_list_compressed",
    ];

    /// <summary>Engine datastore file the engine itself loads server entries from.</summary>
    public static string EngineStorePath => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "TunnelWind", "data", "ca.psiphon.PsiphonTunnel.tunnel-core", "remote_server_list");

    /// <summary>
    /// Writes the compressed remote server list directly into the engine datastore.
    /// Returns (ok, lastErrorForResult).
    /// </summary>
    public static async Task<(bool Ok, string? LastError)> FetchAndStoreAsync(CancellationToken ct = default)
    {
        string? lastError = null;
        using var http = new HttpClient(new HttpClientHandler
        {
            AutomaticDecompression = System.Net.DecompressionMethods.None,
            UseProxy = false,
        });
        http.Timeout = TimeSpan.FromSeconds(20);

        foreach (var url in FetchUrls)
        {
        try
        {
            var compressed = await http.GetByteArrayAsync(url, ct).ConfigureAwait(false);
            if (compressed.Length < 4096 || compressed[0] != 0x78) { lastError = url + ": bad payload"; continue; }

            // sanity: must decompress and contain entry JSON (verified whole shape)
            var text = DecompressZlib(compressed);
            if (text == null || !text.Contains("ipAddress")) { lastError = url + ": unexpected content"; continue; }

            var path = EngineStorePath;
            Directory.CreateDirectory(Path.GetDirectoryName(path)!);
            await File.WriteAllBytesAsync(path, compressed, ct).ConfigureAwait(false);
            // .part must not exist to avoid confusing resumable-downloader state
            try { File.Delete(path + ".part"); } catch { }
            return (true, null);
        }
        catch (OperationCanceledException) { throw; }
        catch (Exception ex)
        {
            lastError = url + ": " + ex.Message;
        }
        }
        return (false, lastError);
    }

    private static string? DecompressZlib(byte[] data)
    {
        if (data.Length < 3 || data[0] != 0x78) return null;
        try
        {
            using var src = new MemoryStream(data, 2, data.Length - 2); // skip zlib header
            using var df = new DeflateStream(src, CompressionMode.Decompress);
            using var dst = new MemoryStream();
            df.CopyTo(dst);
            return Encoding.UTF8.GetString(dst.ToArray());
        }
        catch
        {
            return null;
        }
    }
}

