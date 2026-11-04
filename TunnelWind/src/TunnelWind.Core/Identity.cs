namespace TunnelWind.Core;

/// <summary>
/// TunnelWind identity constants. These are version-locked per TunnelWind build
/// (ORIGINAL Psiphon values are in bootstrap_legacy.cs, kept for reference only).
/// New identity on every major TunnelWind version.
/// </summary>
public static class Identity
{
    public static string ClientPlatform = "windows";
    public static string ClientVersion = "100";     // engine-side client version (integer, per engine contract)
    public static string ClientName = "TunnelWind";
    public static string AppVersion = "1.2.1";      // UI/update-channel version
}
