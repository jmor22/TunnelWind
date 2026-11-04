using System.Text.Json;
using System.Text.Json.Serialization;
using System.Diagnostics;

namespace TunnelWind.Core;

public enum TunnelState
{
    Stopped,
    Starting,
    Connected,
    Stopping
}

/// <summary>
/// Settings persisted to %APPDATA%\TunnelWind\settings.json
/// </summary>
public sealed class Settings
{
    public bool AutoConnect { get; set; } = false;
    public bool UseSystemProxy { get; set; } = true;
    public string EgressRegion { get; set; } = "";
    public int LocalHttpProxyPort { get; set; } = 0;   // 0 = auto
    public int LocalSocksProxyPort { get; set; } = 0;  // 0 = auto
    public bool SplitTunnelOwnRegion { get; set; } = false;
    public string SplitTunnelRegionsCsv { get; set; } = "";
    public bool ExposeProxiesToLan { get; set; } = false;
    public bool StartWithWindows { get; set; } = false;
    public string? UpstreamProxyUrl { get; set; }
    public string UpdateManifestUrl { get; set; } = "";
    public string ServerListHex { get; set; } = "";

    // Transport lock: auto (default) or a specific engine protocol family
    public string Protocol { get; set; } = "auto";

    // Server browser state (CSV of ISO codes)
    public string FavoriteRegionsCsv { get; set; } = "";
    public string RecentRegionsCsv { get; set; } = "";
    public string CachedRegionsCsv { get; set; } = "";

    [JsonIgnore]
    public string[] FavoriteRegions => SplitCodes(FavoriteRegionsCsv);

    [JsonIgnore]
    public string[] RecentRegions => SplitCodes(RecentRegionsCsv);

    [JsonIgnore]
    public string[] CachedRegions => SplitCodes(CachedRegionsCsv);

    private static string[] SplitCodes(string csv) =>
        (csv ?? "")
            .Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
            .Select(s => s.ToUpperInvariant())
            .Where(s => s.Length == 2 && s.All(char.IsLetter))
            .Distinct()
            .ToArray();

    /// <summary>Engine LimitTunnelProtocols value; empty array = automatic.</summary>
    public string[] ProtocolList() => Protocol switch
    {
        "ssh" => ["SSH"],
        "ossh" => ["OSSH"],
        "tls" => ["TLS-OSSH"],
        "quic" => ["QUIC-OSSH"],
        "meek" => ["FRONTED-MEEK-OSSH", "FRONTED-MEEK-HTTP-OSSH", "FRONTED-MEEK-QUIC-OSSH", "UNFRONTED-MEEK-OSSH"],
        _ => [],
    };

    public string ProtocolLabel() => Protocol switch
    {
        "ssh" => "SSH only",
        "ossh" => "OSSH only",
        "tls" => "TLS-OSSH only",
        "quic" => "QUIC-OSSH only",
        "meek" => "Meek (fronted)",
        _ => "Automatic",
    };

    /// <summary>Parsed ID list of countries to bypass (empty when unset).</summary>
    [JsonIgnore]
    public string[] SplitTunnelRegions =>
        (SplitTunnelRegionsCsv ?? "")
            .Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
            .Select(s => s.ToUpperInvariant())
            .Where(s => s.Length >= 2 && s.Length <= 3 && s.All(char.IsLetter))
            .ToArray();

    public static string DefaultPath
    {
        get
        {
            var dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "TunnelWind");
            Directory.CreateDirectory(dir);
            return Path.Combine(dir, "settings.json");
        }
    }

    public static Settings Load()
    {
        try
        {
            var path = DefaultPath;
            if (File.Exists(path))
            {
                var json = File.ReadAllText(path);
                var s = JsonSerializer.Deserialize<Settings>(json);
                if (s != null) return s;
            }
        }
        catch { /* fresh settings */ }
        return new Settings();
    }

    public void Save()
    {
        var options = new JsonSerializerOptions { WriteIndented = true };
        File.WriteAllText(DefaultPath, JsonSerializer.Serialize(this, options));
    }
}

/// <summary>
/// Manages the psiphon-tunnel-core engine subprocess:
///   stdin  <- JSON config (one object, then stdin stays open as command channel)
///   stdout -> one-notice-per-line JSON (noticeType field)
/// Data directory + engine + serverlist file live under %LOCALAPPDATA%\TunnelWind to
/// avoid the temp-dir AV heuristics of the original client.
/// </summary>
public sealed class TunnelManager : IDisposable
{
    private Process? _process;
    private StreamWriter? _stdin;
    private readonly CancellationTokenSource _cts = new();
    private string _enginePath = "";
    private string _dataRoot = "";

    public TunnelState State { get; private set; } = TunnelState.Stopped;
    public int HttpProxyPort { get; private set; }
    public int SocksProxyPort { get; private set; }
    public ulong BytesSent { get; private set; }
    public ulong BytesReceived { get; private set; }
    public string ActiveProtocol { get; private set; } = "";
    public string ConnectedRegion { get; private set; } = "";
    public HashSet<string> AvailableEgressRegions { get; } = new(StringComparer.OrdinalIgnoreCase);

    public event Action<TunnelState>? StateChanged;
    public event Action<ulong, ulong>? StatsChanged;
    public event Action<string>? LogLine;        // raw engine output line
    public event Action<string>? Notice;         // parsed noticeType
    public event Action? RegionsChanged;         // AvailableEgressRegions updated
    public event Action? SignalChanged;          // ActiveTunnel/ConnectedServerRegion updated
    public event Action<string>? EngineDied;     // unexpected engine death (not user Stop)

    // ---------------- lifecycle ----------------

    public string EngineDirectory
    {
        get
        {
            var dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "TunnelWind");
            Directory.CreateDirectory(dir);
            return dir;
        }
    }

    public string DataRoot => Path.Combine(EngineDirectory, "data");

    /// <summary>Install the engine binary once (idempotent). Returns the engine path.</summary>
    public string EnsureEngine(string sourcePath)
    {
        var dir = EngineDirectory;
        _dataRoot = DataRoot;
        Directory.CreateDirectory(_dataRoot);
        var target = Path.Combine(dir, "psiphon-tunnel-core.exe");
        if (!File.Exists(target) || new FileInfo(target).Length != new FileInfo(sourcePath).Length)
        {
            File.Copy(sourcePath, target, overwrite: true);
        }
        _enginePath = target;
        return target;
    }

    /// <summary>
    /// Kills stale instances of OUR engine binary (e.g. orphaned after a hard app kill)
    /// before starting; the Bolt datastore file-lock would otherwise block startup
    /// (tryDatastoreOpenDB timeout). Never touches another app's engine.
    /// </summary>
    private void SweepOrphans()
    {
        try
        {
            var ours = Path.GetFullPath(_enginePath);
            foreach (var proc in Process.GetProcessesByName("psiphon-tunnel-core"))
            {
                try
                {
                    var path = proc.MainModule?.FileName;
                    if (path != null && Path.GetFullPath(path).Equals(ours, StringComparison.OrdinalIgnoreCase))
                    {
                        proc.Kill(entireProcessTree: true);
                        Log("[engine] killed orphaned engine pid " + proc.Id);
                    }
                }
                catch { /* access denied / already gone */ }
                finally
                {
                    try { proc.Dispose(); } catch { }
                }
            }
        }
        catch { }
    }

    public void Start(Settings settings)
    {
        if (State != TunnelState.Stopped) return;
        SetState(TunnelState.Starting);

        HttpProxyPort = 0;
        SocksProxyPort = 0;
        BytesSent = BytesReceived = 0;
        ActiveProtocol = "";
        ConnectedRegion = "";
        AvailableEgressRegions.Clear();

        // sweep stale engines first (they hold the Bolt datastore lock)
        SweepOrphans();

        // Pre-fetch the remote server list over standard TLS straight into the
        // engine datastore (engine verifies signatures, as it does for its own fetch).
        try
        {
            var (ok, fetchError) = ServerListFetcher.FetchAndStoreAsync().GetAwaiter().GetResult();
            Log(ok
                ? "[bootstrap] remote server list stored for engine (standard TLS)"
                : "[bootstrap] remote server list pre-fetch failed; engine fetch will be used"
                    + (fetchError == null ? "" : " (" + fetchError + ")"));
        }
        catch (Exception ex)
        {
            Log("[bootstrap] server list pre-fetch error: " + ex.Message);
        }

        var config = new TunnelConfig
        {
            DataRootDirectory = DataRoot,
            LocalHttpProxyPort = settings.LocalHttpProxyPort,
            LocalSocksProxyPort = settings.LocalSocksProxyPort,
            ListenInterface = settings.ExposeProxiesToLan ? "any" : null,
            EgressRegion = settings.EgressRegion ?? "",
            SplitTunnelOwnRegion = settings.SplitTunnelOwnRegion ? true : null,
            SplitTunnelRegions = (settings.SplitTunnelRegions is { Length: > 0 } list) ? list : null,
            UpstreamProxyUrl = string.IsNullOrWhiteSpace(settings.UpstreamProxyUrl) ? null : settings.UpstreamProxyUrl,
            LimitTunnelProtocols = settings.ProtocolList() is { Length: > 0 } protos ? protos : null,
        };

        // tunnel-core contract (verified by probe): config file via --config,
        // optional legacy server list file via --serverList.
        var configPath = Path.Combine(EngineDirectory, "config.json");
        File.WriteAllText(configPath, config.ToJson());

        var psi = new ProcessStartInfo
        {
            FileName = _enginePath,
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardInput = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            StandardOutputEncoding = System.Text.Encoding.UTF8,
            StandardErrorEncoding = System.Text.Encoding.UTF8,
            WorkingDirectory = Path.GetDirectoryName(_enginePath)!,
        };
        psi.ArgumentList.Add("--config");
        psi.ArgumentList.Add(configPath);

        // (No --serverList handoff: the engine loads from its own datastore, which we
        // seed in-place when our standard-TLS fetch succeeds.)

        _process = Process.Start(psi) ?? throw new InvalidOperationException("failed to start tunnel-core");
        _stdin = _process!.StandardInput;
        _stdin.AutoFlush = true;

        _ = Task.Run(() => ReadLoop(_process.StandardOutput, _cts.Token));
        _ = Task.Run(() => ErrLoop(_process.StandardError, _cts.Token));
        _process.Exited += (_, _) => OnProcessExit("exited");
        _process.EnableRaisingEvents = true;
        if (!_process.HasExited) return;
        OnProcessExit($"exit code {_process.ExitCode}");
    }

    private bool _expectingStop;

    public void Stop()
    {
        if (_process == null) { SetState(TunnelState.Stopped); return; }
        if (State != TunnelState.Stopped) SetState(TunnelState.Stopping);
        _expectingStop = true;
        try
        {
            try { _stdin?.Close(); } catch { }
            // hard-kill first: this engine build does not exit from stdin EOF
            try { _process.Kill(entireProcessTree: true); } catch { }
            if (!_process.WaitForExit(3000))
            {
                // extremely defensive
                try { _process.Kill(entireProcessTree: true); } catch { }
            }
        }
        finally
        {
            SetState(TunnelState.Stopped);
        }
    }

    private void OnProcessExit(string why)
    {
        Log($"[tunnel-core] {why}");
        if (State != TunnelState.Stopped && State != TunnelState.Stopping) SetState(TunnelState.Stopped);
        if (!_expectingStop)
        {
            // kill-mode: the engine died on its own — the UI restores the system proxy
            EngineDied?.Invoke(why);
        }
    }

    private void SetState(TunnelState s)
    {
        if (State == s) return;
        State = s;
        StateChanged?.Invoke(s);
    }

    public void Dispose()
    {
        if (State != TunnelState.Stopped) Stop();
        _cts.Cancel();
        _stdin?.Dispose();
        _process?.Dispose();
    }

    // ---------------- output parsing ----------------

    private async Task ReadLoop(StreamReader stdout, CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            var line = await stdout.ReadLineAsync(ct).ConfigureAwait(false);
            if (line == null) { Log("[tunnel-core] stdout EOF"); OnProcessExit("stdout EOF"); break; }
            HandleLine(line);
        }
    }

    private async Task ErrLoop(StreamReader stderr, CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            var line = await stderr.ReadLineAsync(ct).ConfigureAwait(false);
            if (line == null) { Log("[tunnel-core] stderr EOF"); break; }
            HandleLine(line, isErr: true);
        }
    }

    private void HandleLine(string line, bool isErr = false)
    {
        LogLine?.Invoke(line);
        if (string.IsNullOrWhiteSpace(line)) return;

        JsonDocument? doc = null;
        try { doc = JsonDocument.Parse(line); }
        catch { Notice?.Invoke("(raw) " + line); return; }
        using (doc)
        {
            var root = doc.RootElement;
            // payload fields live under "data" when present ({"data":{...},"noticeType":...})
            var payload = root.TryGetProperty("data", out var d) && d.ValueKind == JsonValueKind.Object ? d : root;

            string noticeType = root.TryGetProperty("noticeType", out var nt) && nt.ValueKind == JsonValueKind.String
                ? nt.GetString() ?? "" : "";

            switch (noticeType)
            {
                case "ListeningHttpProxyPort":
                    if (TryGetPort(payload, out var http)) { HttpProxyPort = http; Log($"[ports] HTTP = {http}"); }
                    break;

                case "ListeningSocksProxyPort":
                    if (TryGetPort(payload, out var socks)) { SocksProxyPort = socks; Log($"[ports] SOCKS = {socks}"); }
                    break;

                case "Tunnels":
                    if (payload.TryGetProperty("count", out var count) && count.ValueKind == JsonValueKind.Number)
                    {
                        var c = count.GetInt32();
                        if (c > 0) { Log("[state] tunnel up"); SetState(TunnelState.Connected); }
                        else if (State == TunnelState.Connected) { Log("[state] tunnel down"); SetState(TunnelState.Starting); }
                    }
                    break;

                case "BytesTransferred":
                {
                    var sent = JsonNum(payload, "bytesSent");
                    var recv = JsonNum(payload, "bytesReceived");
                    if (sent >= 0 && recv >= 0 && (sent != (long)BytesSent || recv != (long)BytesReceived))
                    {
                        BytesSent = (ulong)sent;
                        BytesReceived = (ulong)recv;
                        StatsChanged?.Invoke(BytesSent, BytesReceived);
                    }
                    break;
                }

                case "AvailableEgressRegions":
                    if (payload.TryGetProperty("regions", out var regions) && regions.ValueKind == JsonValueKind.Array)
                    {
                        foreach (var r in regions.EnumerateArray())
                        {
                            if (r.ValueKind == JsonValueKind.String) { var v = r.GetString(); if (v != null) AvailableEgressRegions.Add(v); }
                        }
                        RegionsChanged?.Invoke();
                    }
                    break;

                case "ActiveTunnel":
                    if (payload.TryGetProperty("protocol", out var proto) && proto.ValueKind == JsonValueKind.String)
                    {
                        ActiveProtocol = proto.GetString() ?? "";
                        SignalChanged?.Invoke();
                    }
                    break;

                case "ConnectedServerRegion":
                    if (payload.TryGetProperty("serverRegion", out var region) && region.ValueKind == JsonValueKind.String)
                    {
                        ConnectedRegion = region.GetString() ?? "";
                        SignalChanged?.Invoke();
                    }
                    break;

                default:
                    if (isErr && noticeType is "Warning" or "Error")
                    {
                        var msg = payload.TryGetProperty("message", out var m) && m.ValueKind == JsonValueKind.String ? m.GetString() : null;
                        if (!string.IsNullOrEmpty(msg)) Notice?.Invoke(msg);
                    }
                    break;
            }
        }
    }

    private void MaybeConnected()
    {
        // tunnel-core emits both port notices at once when startup is ready;
        // wait for the Tunnels notice to flip to Connected.
    }

    private static bool TryGetPort(JsonElement root, out int port)
    {
        port = 0;
        if (root.TryGetProperty("port", out var p) && p.ValueKind == JsonValueKind.Number) { port = p.GetInt32(); return true; }
        return false;
    }

    private static long JsonNum(JsonElement root, string field)
    {
        if (root.TryGetProperty(field, out var v) && v.ValueKind == JsonValueKind.Number) return v.TryGetInt64(out var n) ? n : 0;
        return -1;
    }

    private void Log(string message) => LogLine?.Invoke(message);
}
