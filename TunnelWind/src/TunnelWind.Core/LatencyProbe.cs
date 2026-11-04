using System.Collections.Concurrent;
using System.Diagnostics;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;

namespace TunnelWind.Core;

/// <summary>
/// Rough per-region latency: a TCP handshake to a few of that region's listed
/// servers. It measures reachability/time-to-server, not the tunnel round-trip,
/// but it is a useful relative indicator for the server picker.
/// </summary>
public static class LatencyProbe
{
    public sealed record Endpoint(string Region, string Host, int Port);

    /// <summary>
    /// Parse the signed server list (newline-separated hex entries). Modern
    /// entries use a "0 0 0 0 {json}" prefix with ipAddress/webServerPort in the
    /// JSON; older ones put "ip port" in the prefix.
    /// </summary>
    public static List<Endpoint> Parse(string? hexList, int maxPerRegion = 3)
    {
        var result = new List<Endpoint>();
        if (string.IsNullOrWhiteSpace(hexList)) return result;

        var seen = new Dictionary<string, int>(StringComparer.OrdinalIgnoreCase);
        foreach (var raw in hexList.Split('\n'))
        {
            var line = raw.Trim();
            if (line.Length < 8 || line.Length % 2 != 0) continue;
            try
            {
                var text = Encoding.UTF8.GetString(Convert.FromHexString(line));
                var parts = text.Split(' ', 5);
                if (parts.Length < 5) continue;
                using var doc = JsonDocument.Parse(parts[4]);
                var root = doc.RootElement;

                var region = root.TryGetProperty("region", out var r) ? (r.GetString() ?? "") : "";
                var host = root.TryGetProperty("ipAddress", out var h) ? (h.GetString() ?? "") : "";
                if (host.Length == 0) host = parts[0];
                if (region.Length != 2 || host.Length == 0 || host == "0") continue;

                var port = 0;
                if (root.TryGetProperty("webServerPort", out var wp))
                {
                    if (wp.ValueKind == JsonValueKind.String) int.TryParse(wp.GetString(), out port);
                    else if (wp.ValueKind == JsonValueKind.Number) port = wp.GetInt32();
                }
                if (port <= 0 && root.TryGetProperty("tlsOSSHPort", out var tls) && tls.ValueKind == JsonValueKind.Number)
                    port = tls.GetInt32();
                if (port <= 0 && root.TryGetProperty("sshPort", out var ssh) && ssh.ValueKind == JsonValueKind.Number)
                    port = ssh.GetInt32();
                if (port <= 0) int.TryParse(parts[1], out port);
                if (port <= 0) continue;

                seen.TryGetValue(region, out var count);
                if (count >= maxPerRegion) continue;
                seen[region] = count + 1;
                result.Add(new Endpoint(region.ToUpperInvariant(), host, port));
            }
            catch { /* skip malformed entry */ }
        }
        return result;
    }

    public static async Task<Dictionary<string, int>> MeasureAsync(
        string? hexList,
        IReadOnlyCollection<string>? regions = null,
        int timeoutMs = 1200,
        CancellationToken ct = default)
    {
        var endpoints = Parse(hexList);
        var wanted = regions is { Count: > 0 }
            ? new HashSet<string>(regions, StringComparer.OrdinalIgnoreCase)
            : null;

        var byRegion = endpoints
            .Where(e => wanted == null || wanted.Contains(e.Region))
            .GroupBy(e => e.Region, StringComparer.OrdinalIgnoreCase)
            .ToDictionary(g => g.Key, g => g.ToList(), StringComparer.OrdinalIgnoreCase);

        var results = new ConcurrentDictionary<string, int>(StringComparer.OrdinalIgnoreCase);
        using var gate = new SemaphoreSlim(8);
        var tasks = byRegion.Select(async kv =>
        {
            await gate.WaitAsync(ct).ConfigureAwait(false);
            try
            {
                var best = int.MaxValue;
                foreach (var ep in kv.Value)
                {
                    var ms = await ProbeAsync(ep.Host, ep.Port, timeoutMs, ct).ConfigureAwait(false);
                    if (ms.HasValue) best = Math.Min(best, ms.Value);
                }
                if (best != int.MaxValue) results[kv.Key] = best;
            }
            finally { gate.Release(); }
        });
        await Task.WhenAll(tasks).ConfigureAwait(false);
        return new Dictionary<string, int>(results, StringComparer.OrdinalIgnoreCase);
    }

    private static async Task<int?> ProbeAsync(string host, int port, int timeoutMs, CancellationToken ct)
    {
        using var client = new TcpClient();
        using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        cts.CancelAfter(timeoutMs);
        var sw = Stopwatch.StartNew();
        try
        {
            await client.ConnectAsync(host, port, cts.Token).ConfigureAwait(false);
            sw.Stop();
            return (int)sw.ElapsedMilliseconds;
        }
        catch
        {
            return null;
        }
    }
}
