using System.Text.Json;
using System.Text.Json.Serialization;

namespace TunnelWind.Core;

/// <summary>
/// JSON tunnel-core configuration contract.
/// Field names are the exact wire keys observed in psiphon-tunnel-core (Go json tags)
/// and in the original client's config builder (see ANALYSIS.md).
/// </summary>
public sealed class TunnelConfig
{
    // Identity
    [JsonPropertyName("ClientPlatform")] public string ClientPlatform { get; set; } = "windows";
    [JsonPropertyName("ClientVersion")] public string ClientVersion { get; set; } = "100"; // integer per engine contract (strconv.Atoi)
    [JsonPropertyName("PropagationChannelId")] public string PropagationChannelId { get; set; } = Bootstrap.PropagationChannelId;
    [JsonPropertyName("SponsorId")] public string SponsorId { get; set; } = Bootstrap.SponsorId;

    // Network info that helps server selection (optional)
    [JsonPropertyName("DeviceNetworkInfo")] public DeviceNetworkInfo? DeviceNetworkInfo { get; set; }

    // Bootstrap
    [JsonPropertyName("RemoteServerListURLs")] public List<UrlItem> RemoteServerListURLs { get; set; } = UrlFactory.Build(Bootstrap.RemoteServerListUrls);
    [JsonPropertyName("ObfuscatedServerListRootURLs")] public List<UrlItem> ObfuscatedServerListRootURLs { get; set; } = UrlFactory.Build(Bootstrap.ObfuscatedServerListRootUrls);
    [JsonPropertyName("ServerEntrySignaturePublicKey")] public string ServerEntrySignaturePublicKey { get; set; } = Bootstrap.ServerEntrySignaturePublicKey;
    [JsonPropertyName("RemoteServerListSignaturePublicKey")] public string RemoteServerListSignaturePublicKey { get; set; } = Bootstrap.RemoteServerListSignaturePublicKey;
    [JsonPropertyName("FeedbackUploadURLs")] public List<UrlItem> FeedbackUploadURLs { get; set; } = UrlFactory.Build(Bootstrap.FeedbackUploadUrls);

    // Local endpoints
    [JsonPropertyName("LocalHttpProxyPort")] public int LocalHttpProxyPort { get; set; } = 0;
    [JsonPropertyName("LocalSocksProxyPort")] public int LocalSocksProxyPort { get; set; } = 0;
    [JsonPropertyName("ListenInterface")] public string? ListenInterface { get; set; }

    // Region selection
    [JsonPropertyName("EgressRegion")] public string EgressRegion { get; set; } = "";
    [JsonPropertyName("DeviceRegion")] public string? DeviceRegion { get; set; }

    // Transport lock (empty/null = let the engine race every protocol)
    [JsonPropertyName("LimitTunnelProtocols")] public string[]? LimitTunnelProtocols { get; set; }

    // Split tunneling
    [JsonPropertyName("SplitTunnelOwnRegion")] public bool? SplitTunnelOwnRegion { get; set; }
    [JsonPropertyName("SplitTunnelRegions")] public string[]? SplitTunnelRegions { get; set; }

    // Diagnostics / behaviors
    [JsonPropertyName("EmitDiagnosticNotices")] public bool EmitDiagnosticNotices { get; set; } = true;
    [JsonPropertyName("EmitDiagnosticNetworkParameters")] public bool EmitDiagnosticNetworkParameters { get; set; } = true;
    [JsonPropertyName("EmitServerAlerts")] public bool EmitServerAlerts { get; set; } = true;
    [JsonPropertyName("EmitBytesTransferred")] public bool EmitBytesTransferred { get; set; } = true;
    [JsonPropertyName("EnableFeedbackUpload")] public bool EnableFeedbackUpload { get; set; } = false;
    [JsonPropertyName("EnableUpgradeDownload")] public bool EnableUpgradeDownload { get; set; } = false;
    [JsonPropertyName("UseIndistinguishableTLS")] public bool UseIndistinguishableTLS { get; set; } = true;
    [JsonPropertyName("DisableLocalHTTPProxy")] public bool? DisableLocalHTTPProxy { get; set; }
    [JsonPropertyName("DisableLocalSocksProxy")] public bool? DisableLocalSocksProxy { get; set; }

    // Data directory for tunnel-core state (server list cache, transport DB, etc.)
    [JsonPropertyName("DataRootDirectory")] public string? DataRootDirectory { get; set; }

    // Upstream proxy connectivity (corporate proxy etc.)
    [JsonPropertyName("UpstreamProxyUrl")] public string? UpstreamProxyUrl { get; set; }

    /// <summary>Serialize without null fields so tunnel-core uses its own defaults.</summary>
    public string ToJson()
    {
        var options = new JsonSerializerOptions
        {
            DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
            WriteIndented = false,
        };
        return JsonSerializer.Serialize(this, options);
    }
}

/// <summary>Network hint sent at connect time (kind of link the device is on).</summary>
public sealed class DeviceNetworkInfo
{
    [JsonPropertyName("NetworkID")] public string NetworkID { get; set; } = "";
}

/// <summary>JSON wire shape of RemoteServerListURLs / ObfuscatedServerListRootURLs.</summary>
public sealed class UrlItem
{
    [JsonPropertyName("URL")] public string URL { get; set; } = "";
    [JsonPropertyName("OnlyAfterAttempts")] public int OnlyAfterAttempts { get; set; } = 0;
    [JsonPropertyName("SkipVerify")] public bool SkipVerify { get; set; } = false;
}

public static class UrlFactory
{
    /// <summary>
    /// tunnel-core decodes URL values from base64 (parameters.TransferURLs.DecodeAndValidate),
    /// exactly what the original launcher sends. Verified by engine probe.
    /// </summary>
    public static List<UrlItem> Build(string[] urls) => urls.Select(u => new UrlItem
    {
        URL = Convert.ToBase64String(System.Text.Encoding.UTF8.GetBytes(u)),
    }).ToList();
}
