# TunnelWind

A modern Windows VPN/circumvention client built from the reverse-engineered architecture of
Psiphon 3 Windows (see `../ANALYSIS.md`). Stokes the battle-hardened **psiphon-tunnel-core**
engine as a subprocess (same stdin/stdout JSON contract the original launcher uses) and
replaces everything else with clean, auditable code:

- .NET 8 WPF UI (dark theme, tray icon, live log + traffic counters) — no legacy IE webview
- No polipo dependency (tunnel-core exposes HTTP + SOCKS natively)
- Engine binary lives under `%LOCALAPPDATA%\TunnelWind\` (no classic `%TEMP%` drop — an AV
  heuristic trigger used by the original client)
- One-click/even system proxy (WinInet) with automatic restore, no per-connection registry surgery
- Settings persisted per user (`%APPDATA%\TunnelWind\settings.json`)
- No PsiCash/monetization; upgrade channel intentionally disabled in v1

## Bootstrap pipeline (verified working end-to-end)

1. `ServerListFetcher` fetches `server_list_compressed` over **standard SChannel TLS**
   (the engine's Go TLS dialer is fingerprint-sensitive and intermittently EOFs on the
   same endpoints) and writes the **raw compressed bytes** directly into the engine's
   own datastore: `%LOCALAPPDATA%\TunnelWind\data\ca.psiphon.PsiphonTunnel.tunnel-core\remote_server_list`.
2. The engine loads/verifies it (RSA list signature + Ed25519 per-entry signatures)
   exactly as it would with its own fetch — trust is never weakened.
3. tunnel-core then dials candidates (SSH/OSSH/QUIC/meek/inproxy-WebRTC …) and emits
   `Tunnels {count:1}` — the UI flips to Connected and the ports are ready.

Key facts proven during bring-up (see `../ANALYSIS.md`):
* The `RemoteServerListSignaturePublicKey` is the **RSA-3072 SPKI (base64 text)** — its
  SHA-256 equals the live list's `signingPublicKeyDigest`. The two trust-anchor keys
  were initially swapped; engine probes pinpointed the mismatch.
* `ClientVersion` must be an **integer string** (engine uses `strconv.Atoi`).
* Engine contract: `psiphon-tunnel-core.exe --config <path>`; all notices — including
  port notices — arrive on **stderr**; payloads are nested under `"data"`.
* Remote list wire format: zlib(78 9C) JSON `{"data": <hex-per-entry>}`.

## Layout

```
TunnelWind/
  src/TunnelWind.Core/     — engine manager, config builder, system proxy (netstandard-ish, net8.0)
  src/TunnelWind.App/      — WPF app (net8.0-windows)
  engine/                  — psiphon-tunnel-core.exe (extracted from the official build; verify SHA-256!)
  build.ps1                — portable-dotnet build script
```

## Build

```
cd TunnelWind
powershell -ExecutionPolicy Bypass -File build.ps1
```

The script uses a **portable .NET SDK** placed in `..\tools\dotnet` (no system-wide install),
restores, builds and copies the engine binary into the output directory.

## Run

`TunnelWind\output\TunnelWind.exe`  (x64)
`TunnelWind\output-arm64\TunnelWind.exe`  (Windows-on-ARM — engine runs under x86 emulation)

Fixed local proxy ports live in settings (`%APPDATA%\TunnelWind\settings.json`,
default `8080` HTTP / `1080` SOCKS — set them via ⚙; empty means auto-port).

1. Press **CONNECT** — the log shows the engine notices (bootstrap → servers → tunnel up).
2. When the ports appear and the status shows `Connected · <protocol> · <region>`,
   browsing goes through the tunnel.
3. Disconnect or exit (tray → Exit) restores your previous proxy settings.

## Security notes / next steps

- The bootstrap constants in `Bootstrap.cs` are the **public** distribution anchors
  (signing public keys, sponsor ID, S3/mirror URLs) shipped inside every Psiphon client.
- Engine hash verification before first run is TODO (add `spdhash` style pinning of
  `psiphon-tunnel-core.exe` SHA-256 to `engine.stamp`).
- For a fully independent server network, swap in your own server list format and
  transports later (`SplitTunnelRegions`, custom remote server lists, etc.).
- `AdditionalParameters` opaque blob is intentionally not forwarded (optional in v1).
