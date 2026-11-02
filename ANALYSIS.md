# Psiphon 3 Windows — Reverse Engineering Findings

Analysis performed with REA + Ghidra 12.1.4 (ephemeral headless sessions). Evidence IDs referenced
below are REA ledger records from those sessions (see `EXTRACTION_LOG.txt` for digests).

## 1. Package identity

| Property | Value |
|---|---|
| Shipped file | `psiphon3.exe` (11,310,160 bytes), `sha256 87dcd5d8787d719426c585020f06bee864abccb991f0bf79913c726fabc8ed2d` |
| Packing | UPX (sections `UPX0`, `UPX1`, `tdb`), unpacked with UPX 5.0.2 → `psiphon3_unpacked.exe` (33,726,032 B, `sha256 a35a77b2a6070778941c1a77c81ccb705e2932fa84ff0eb8beafbbcd82071de2`), x86 (32-bit) PE |
| Launcher provenance | Built at `C:\work\psiphon-windows\src` (PDB path in strings); C++ (MFC + GDI+ + nlohmann/json + Crypto++ + custom `mCtrl` lib) |
| UI | Embedded HTML UI (`MAIN.HTML`, 3.11 MB RCDATA) rendered inside an `Internet Explorer_Server` native control; 100+ locales; Bootstrap-era CSS |

## 2. Architecture (observed)

The launcher is a thin shell; the real engine is **embedded as PE resources** in `.rsrc`:

| Resource | Content | Evidence |
|---|---|---|
| RCDATA name `131` | `psiphon-tunnel-core.exe` — Go 1.26.5 (2026-07-23, rev `24b8381cc3`, CGO i686-w64-mingw32). Module: `github.com/Psiphon-Labs/psiphon-tunnel-core/ConsoleClient`; tags `PSIPHON_INIT_COMMON_VALUES`, `PSIPHON_ENABLE_INPROXY`, `PSIPHON_ENABLE_REFRACTION_NETWORKING` | REA `inspect_go_binary` (`ev_88f34871…`) |
| RCDATA name `132` | `polipo.exe` (288 KB, C/WinInet, `C:\Users\Adam\Code\psiphon\polipo\Release\polipo.pdb`) — legacy HTTP proxy shim on top of the SOCKS parent proxy | string/PE inspection |
| RCDATA `MAIN.HTML`, PNGs, `COUNTRY_DIALING_CODES.JSON` | HTML UI + branding (logo, flags, PsiCash coin, Speed Boost buttons) | `inspect_pe_resources` (`ev_793dcb3d…`) |

### Runtime flow (decompiled `CoreTransport::SpawnCoreProcess`, `ev_74d0351e…`)
1. `GetSysTempPath` → `GetUniqueTempFilename` (5 retries).
2. `ExtractExecutable` writes `psiphon-tunnel-core.exe` (or `psiphon-url-proxy.exe`) from RCDATA to temp.
3. Spawns it with **stdin/stdout pipes**: JSON config in, line protocol out.
4. Parses notices: `PSIPHON-DEBUG:>>`, `CoreNotice` JSON, `ListeningHttpProxyPort`, `ListeningSocksProxyPort`, stats (`PSIPHON-BYTES-TRANSFERRED:>>`, page views).
5. C++ layer then: sets Windows system proxy (WinInet, per-connection, `SetConnectionProxy`), manages server lists (embedded/system/remote, signature checks), runs upgrade/download verifier (`psiphon3.exe.upgrade`, S3 URLs, client-version header `x-amz-meta-psiphon-client-version`), feedback uploader (`psiphon-feedback.exe`), PsiCash monetization (native `psicashlib` from `psicash-lib-android`), egress-region/split-tunnel settings, legacy L2TP/IPSec VPN transport (`VPNTransport`, `ProhibitIpSec` registry fix).

### tunnel-core contract (from Go binary strings + launcher)
- **Input**: one JSON config object (stdin). Key fields the launcher sends (recovered from config-builder `FUN_00444076`):
  - Identity: `ClientPlatform`, `ClientVersion`, `PropagationChannelId`, `SponsorId`, `NetworkID`
  - Bootstrap: `RemoteServerListURLs`, `ObfuscatedServerListRootURLs` (JSON URL lists, URLs are base64'd), `EmbeddedServerEntryList` (`
    ` "<ver counters> " + hex(JSON entry)` format — classic `encoded_server_list`)
  - Trust anchors: `ServerEntrySignaturePublicKey` (RSA 3072, PEM-body), `RemoteServerListSignaturePublicKey` (Ed25519-style base64), `FeedbackEncryptionPublicKey`, `FeedbackUploadURLs`
  - Behavior switches: `EmitDiagnosticNotices`, `EmitDiagnosticNetworkParameters`, `EmitServerAlerts`, `UseIndistinguishableTLS`, `EnableFeedbackUpload`, `EnableUpgradeDownload`, `EgressRegion`, `LocalHttpProxyPort`, `LocalSocksProxyPort`, `UpstreamProxyUrl`, `SplitTunnelOwnRegion`, `SplitTunnelRegions`, `DeviceRegion`, `DataRootDirectory` (+ `Migrate*` legacy paths), `AdditionalParameters`
- **Output**: notice lines (JSON `{noticeType: …}`), including `ListeningHttpProxyPort` / `ListeningSocksProxyPort` on ready; `Authorizations`; upgrade/feedback events.
- **Transports compiled in** (string evidence in tunnel-core): SSH/OSSH/obfs4, UNFRONTED-MEEK, QUIC, Conjure (REFRACTION_NETWORKING), inproxy (WebRTC/STUN/TURN, same-checksum V1 protocol), shadowsocks, wireguard, terror? — plus ` knit tunnels (` knit framed tunnelled payload, etc.).

### Recovered build-time constants (shipped product data)
Kept in `EXTRACTION_LOG.txt` (public product constants — signing anchors, sponsor/channel IDs,
the embedded bootstrap server entry). The full config format and values enable any
drop-in client to boot the same network.

**Key-mapping correction (verified by engine probes + SHA-256 of the live list):**
- `RemoteServerListSignaturePublicKey` = **RSA-3072 SPKI base64 text** (`MIICIDANBgkqhkiG…`)
  — SHA-256 of this base64 text equals the live `server_list_compressed` payload's
  `signingPublicKeyDigest` (`V92HkhTZqeRRlgmIPuyWpod5nJm7xLz14BXYw6BjaPc=`).
- `ServerEntrySignaturePublicKey` = **Ed25519** key (`sHuUVTWaRyh5pZwy4UguSgkwmBe0EHtJJkoF5WrxmvA=`)
  — verifies each per-entry `signature` field.
The initial mapping (swapped) was corrected in `TunnelWind/src/TunnelWind.Core/Bootstrap.cs`.

## 3. Weak points observed (opportunities for "the better version")

1. **Extremely dated UI stack**: IE-webview-in-MFC (32-bit only, deprecated IE engine — no WebAuthn/modern CSS), Bootstrap 2-era CSS.
2. **32-bit only** (`GOARCH=386`, x86 C++); memory-limited, no ARM64.
3. **Polipo legacy HTTP shim** (abandoned upstream, known CVEs in old versions) used only to make an HTTP proxy from SOCKS.
4. **Plain-text pipe IPC** to a temp-dir-spawned child (no verification of the temp `exe` it extracts before spawning).
5. **Config/keys duplicated** between launcher and engine; system proxy config via direct registry writes that antivirus flags (their own error strings admit this).
6. **No real tun-based VPN mode on Windows** (legacy L2TP/IPSec only; slow, service-hooking to RasMan).
7. Update UX: silent S3-downloaded upgrade package + signature verifier (good), but user sees raw messages like "Upgrade package verification failed!".

## 4. What `TunnelWind` (our rebuild) does differently

- .NET 8 (x64 + ARM64) UI layer, no IE embedded web view; Mica/WinUI-style WPF.
- Engine: official `psiphon-tunnel-core.exe` as a standard subprocess (same stdin/stdout JSON
  contract), managed by our own Go/C# orchestrator with:
  - signed upgrade-plugin style replacement of the launcher, verifiable engine binary hash;
  - first-class HTTP **and** SOCKS local proxies (no polipo);
  - in-process stats streaming to the UI over a local loopback control channel;
  - settings: egress region, split tunneling, upstream proxy, local proxy ports, language;
  - modern system-proxy integration with automatic restore.
- Bootstrap config built from the recovered product constants (same network compatibility).

Run instructions and file map: see `TunnelWind/README.md`.
