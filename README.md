# 🌬 Tunnel Wind

<p align="center">
  <img src="TunnelWind/docs/img/screenshot-disconnected.png" width="240" />
  &nbsp;&nbsp;&nbsp;
  <img src="TunnelWind/docs/img/screenshot-connected.png" width="240" />
</p>

<p align="center"><em>Left: main window (disconnected). Right: live tunnel — <b>Connected · INPROXY-WEBRTC-OSSH · IT</b>, HTTP+SOCKS ports, region chips with IT selected, split-tunnel toggle.</em></p>

A modern, open Windows client built on top of the **psiphon-tunnel-core** engine.
This project was created by reverse engineering the complete architecture of the
official Psiphon 3 Windows client (see [`ANALYSIS.md`](ANALYSIS.md)), then rebuilding
every part around it in clean, auditable code.

> **What it is**
> A .NET 8 WPF app that spawns the official `psiphon-tunnel-core.exe` engine and
> manages the entire client experience: bootstrap, server selection, system proxy,
> split tunneling, update channel, integrity checks, tray, autostart.

> **What it is not**
> It does not bundle or redistribute the engine itself. Before you can build/run, you
> (or your build agent) extract `psiphon-tunnel-core.exe` **from your own, legally
> obtained copy of Psiphon** — the reverse-engineered extraction procedure is fully
> documented in [`EXTRACTION_LOG.txt`](EXTRACTION_LOG.txt) and automated in
> [`build.ps1`](TunnelWind/build.ps1) once the file is in place.

---

## Feature set

| Area | Detail |
|---|---|
| Engine | Spawns `psiphon-tunnel-core` as a subprocess with a config file; notices streamed on stderr and parsed into UI state |
| Bootstrap | Pre-fetches the remote server list over standard Windows TLS (SChannel) and writes it into the engine's own datastore — engine validates RSA list + Ed25519 per-entry signatures as it would with its own download |
| Trust anchors | All the public distribution constants recovered from the shipped client (`Bootstrap.cs`) |
| Region picker | Chip grid populated live from engine `AvailableEgressRegions` notices; instant hot-restart on chip click |
| Split tunnel | Per-country bypass via `SplitTunnelOwnRegion` / `SplitTunnelRegions`, plus an editable list |
| System proxy | WinInet user-scope proxy with crash-safe backup / restore, stale-proxy guard at startup, LAN exposure (`ListenInterface: any`) |
| Update channel | Manifest-driven `update.json` (`appVersion`, `appUrl`, `appSha256`, `engineUrl`, `engineSha256`); SHA-256-verified swap of engine (pin rotation) and app (rename dance) |
| Hardening | Engine hash pinning (`engine.sha256`), orphan sweep, kill-mode (proxy restore on engine death), single-instance mutex |
| Tray | Live tray icon with context menu (Show / Connect / Disconnect / Exit), close-to-tray, balloon notifications |
| UX | Fixed local ports (default `8080` HTTP / `1080` SOCKS) or auto, per-country egress, traffic counters, "start with Windows", autostart toggle, real settings window |

## Layout

```
TunnelWind/
  src/TunnelWind.Code/          # core library (engine, config, proxy, update, integrity)
  src/TunnelWind.App/           # WPF UI (net8.0-windows, x64 + ARM64)
  engine/                       # engine binary (not in repo; see extraction doc)
  update/                       # update.json + LocalUpdateServer.ps1 + packager
build.ps1                       # portable-dotnet build script (doubles as tool installer)
```

## Build

```
git clone https://github.com/jmor22/TunnelWind.git
cd TunnelWind
powershell -ExecutionPolicy Bypass -File tools/get-dotnet.ps1      # portable .NET 8 SDK (no admin)
powershell -ExecutionPolicy Bypass -File build.ps1                 # x64
powershell -ExecutionPolicy Bypass -File build.ps1 -arm64          # ARM64 (Windows-on-ARM)
```

Runtime prerequisites are already inside the portable bundle; no system installs.

## Run

`TunnelWind\output\TunnelWind.exe`

1. Put your `psiphon-tunnel-core.exe` inside `TunnelWind\engine\` (one-time).
2. Press **CONNECT**; the log walks you through every engine stage.
3. When disconnected (or issues), tray → Exit restores your system proxy from backup.

## Security notes

* The system proxy is flipped on only when the tunnel is up. Every Prepare → Apply
  step writes a **proxy backup file** so a future run (or a crash) can restore the
  previous user configuration.
* The engine binary is hash-pinned on first run; any modification or wrong-method
  insertion blocks CONNECT.
* The engine's own persistence (`%LOCALAPPDATA%\TunnelWind\data\…`) is used as-is,
  so you never need to trust an intermediate cache.
* **No engine binaries are ever redistributed from this repo.**

## Legal / licensing

* This repo's code: see [`LICENSE.md`](TunnelWind/LICENSE.md) (it says explicitly:
* TunnelWind wrapper code: see [`TunnelWind/LICENSE.md`](TunnelWind/LICENSE.md).
* `psiphon-tunnel-core` source: https://github.com/Psiphon-Labs/psiphon-tunnel-core ;
  use of Psiphon's network / API requires adherence to their Do-No-Harm license
  (personal use is fine, redistribution of the engine itself is not).
* You are responsible for compliance in your jurisdiction.

## Documentation

* [`ANALYSIS.md`](ANALYSIS.md) — the complete reverse-engineering report (UPX
  unpacking, embedded PEs, config-builder decompilation, engine wire contract,
  notices…)
* [`EXTRACTION_LOG.txt`](EXTRACTION_LOG.txt) — exact byte-provenance of every
  extracted constant (SHA-256s, file offsets, decompiled key addressing)
* [`TunnelWind/README.md`](TunnelWind/README.md) — engine bootstrap pipeline details
  (base64 transfer URLs, numeric `ClientVersion`, stderr notices, `"data"` payloads…)
