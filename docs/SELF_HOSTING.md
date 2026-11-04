# Adding egress countries

## What is and isn't possible

`AvailableEgressRegions` is computed by the engine from **candidate server
entries**: entries whose protocol parameters are complete and which match the
active `LimitTunnelProtocols`.

The list is **not static**. On a fresh install the first notice is computed from
the embedded list we bootstrap with and reported 21 regions. The engine then
refreshes its server entries at runtime (remote list + DSL), and the same notice
later reported **27 regions**:

```
AT AU BE BR CA CH CZ DE DK ES FR GB ID IE IN IT JP KR LT NL NO PL RO RS SE SG US
```

That run also connected successfully to one of the newly appeared regions
(`EgressRegion = RO` → `ConnectedServerRegion: RO`), so the app simply keeps
listening to the notice and the Servers screen grows automatically. The first
connection attempt right after a fresh install can hang if it targets a region
the engine has not discovered yet — connect once with Automatic, then pick the
new region.

Servers belonging to other VPN providers cannot be used at all:

* they speak WireGuard/OpenVPN/IPSec, not Psiphon's tunnel protocols;
* the client needs that server's `webServerSecret`, `webServerCertificate`,
  SSH host key and obfuscation keys — they don't exist for a non-Psiphon server;
* using someone else's infrastructure without authorisation is not acceptable.

So: more servers *within* the existing countries is already maximised (all 429
entries are embedded). More **countries** either appear when Psiphon adds them
(which the client picks up automatically), or require your own Psiphon server.

## Running your own Psiphon server

The server component is part of the open-source engine repository
(`Server/`). You need a VPS with a public IPv4 address, root access, and a
domain if you want fronted-meek protocols.

### 1. Build

```bash
git clone https://github.com/Psiphon-Labs/psiphon-tunnel-core
cd psiphon-tunnel-core/Server
go build -o psiphond main.go
```

### 2. Generate the configuration

```bash
./psiphond \
  -ipaddress <SERVER_PUBLIC_IP> \
  -protocol SSH:22 \
  -protocol OSSH:53 \
  -protocol FRONTED-MEEK:443 \
  -webserverport 443 \
  -serverentry server-entry.json \
  -logfilename psiphond.log \
  generate
```

This writes `psiphond.config`, `server-entry.json`, key material, and the
traffic-rules / OSL / tactics files. Run `./psiphond -help` for the full flag
list; add `-traffic-rules`, `-osl`, `-tactics` if you want custom files.

### 3. Run

```bash
./psiphond -config psiphond.config run
```

Open the ports you passed in your firewall (22, 53, 443 by default) and make
sure `sshd` is not conflicting with the SSH protocol port.

### 4. Put the server into the app

The client's `embeddedServerEntryList` (parameter `p1` of `Psi.start`) is a
newline-separated list of hex strings. The engine's decoder (`decodeServerEntry`)
**ignores the four prefix fields** and parses the JSON, so the simplest encoding
is:

```
hex( "0 0 0 0 " + <contents of server-entry.json> )
```

Add one line per self-hosted server. In this app that means appending to the
string returned by `prefetchServerList()` before `Psi.start(...)` — or adding a
"custom server entry" setting that is appended the same way.

Notes:

* Embedded entries are **trusted by the client, not signed** by Psiphon's list
  key. That is what makes self-hosting work — and also why an embedded list
  should only ever come from your own build.
* Set the JSON's `region` field to the country code you want (e.g. `"MX"`). The
  engine only lists regions that appear in candidate entries, so this is what
  makes the country show up in the Servers screen. Without a region the server
  is still dialable in Automatic mode.
* To force a specific server use the engine's `TargetServerEntry` /
  server-affinity features; otherwise the engine races all candidates.

### 5. Costs and caveats

* One server = one country and one point of failure; latency and uptime are
  yours to monitor.
* Bandwidth is billed by your VPS provider — a VPN with real users is not free.
* Keep Psiphon's Do-No-Harm licence and acceptable-use policy in mind. Running
  your own server for yourself/your users is fine; the public Psiphon servers
  are theirs and must not be abused.

## If you need a country Psiphon doesn't cover

Self-hosting is the clean path for a Psiphon-compatible egress. If you'd rather
have a plain VPN in many countries, adding a WireGuard engine (with your own VPS
or a commercial provider's API) is a different product decision: simpler to
deploy, but it does not provide Psiphon's circumvention properties.
