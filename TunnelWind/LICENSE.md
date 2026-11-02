# Licenses & Attribution

## This project (TunnelWind)
- The wrapper code in `src/` written for this project: MIT (you may reuse it freely — a
  compatible TODO: file is intentional placeholder, apply your own terms for your fork).
- Disclaimer: this is personal-use software. YOU are responsible for ensuring legal
  compliance in your jurisdiction before you use any circumvention tool.

## Engine: psiphon-tunnel-core
- The `psiphon-tunnel-core.exe` in `engine/` is the official Psiphon Labs binary,
  extracted unchanged from the publicly distributed psiphon3.exe (see `../ANALYSIS.md`
  for SHA-256 provenance).
- tunnel-core source: https://github.com/Psiphon-Labs/psiphon-tunnel-core
- Psiphon's client licensing has Do-No-Harm and attribution terms; consult
  `psiphon-tunnel-core/LICENSE` before distributing your build. Public or commercial
  distribution of a Psiphon-network-compatible client requires a Psiphon API agreement.
- Sponsor ID / propagation channel / signing keys are Psiphon's product constants —
  they identify *their* network. They are used solely so your personal build can reach
  the same network.

## Other third-party pieces
- polipo: deliberately **not** included (deprecated upstream; tunnel-core covers HTTP
  proxying natively).
