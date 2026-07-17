# Migration TODO — etzhayyim-sdk py → cljc

**Status**: 🟢 HTTP layer ported off `httpx` → `babashka.http-client` (cljc). Coexistence with
the still-python `metrics` skeleton retained for the mst-projector subscriber.

## Done — httpx elimination + cljc port (this wave)

Per the repo-wide clj/bb rule (root CLAUDE.md §"Operational code = clj/bb over the kotoba Datom
log") and the substrate-boundary direction below.

- [x] **Replace `httpx` with `babashka.http-client`** (no new dependency — bb built-in).
      The three httpx-bearing modules ported to cljc:
      - [x] `mst_projector.cljc` — XRPC client (query-by-collection / query-by-did / query-by-field
            / count-by-collection); same NSIDs, camelCase wire keys, Clojure-map return shapes.
      - [x] `mst.cljc` — MST read/query helpers (M3 stubs preserved: throw a NotImplementedError
            analogue `::errors/not-implemented`); ETZHAYYIM_PDS_URL config kept.
      - [x] `llm.cljc` — LiteLLM client (translate / chat) with exponential-backoff retry; auth/
            429/5xx/4xx/network classification faithful to the python.
- [x] **Error hierarchy → `ex-info` + keyword `isa?` graph** (`errors.cljc`): the
      `EtzhayyimSdkError` class tree becomes `:type` keywords `derive`d into the global hierarchy;
      `(errors/sdk-error? e ::mst-projector-server-error)` replaces `isinstance`.
- [x] **`metrics.cljc`** — in-process counters/gauges/histograms + Prometheus export (byte-shape
      parity); lock-free via a single atom.
- [x] **Tests ported to cljc** (`clojure.test`): `test_metrics.cljc` + `test_mst_projector.cljc`;
      the httpx MockTransport injection → `(binding [*request* stub] …)`. `run_tests.clj` (bb,
      not `.sh` — repo rule) runs them green: **17 tests / 47 assertions, 0 failures / 0 errors**.
- [x] **Removed provably-unused python** (nothing outside the package imported them): `errors.py`,
      `mst_projector.py`, `mst.py`, `llm.py`, `tests/test_mst_projector.py` — this eliminates every
      `httpx` import from the package code.

## Blocked / coexisting (kept python)

- [ ] **`subscriber.py` not ported** (`50-infra/mst-projector/py/src/mst_projector/subscriber.py`):
      it imports `etzhayyim_sdk.cursor` (AT Protocol firehose / CBOR-frame websocket subscriber,
      `subscribe_with_checkpoint`) and `etzhayyim_sdk.pds` (PDS client) — deep python-only AT-Proto
      deps that are **not** part of this package and impractical to bb-port. Per the founder
      guidance this is left in place to coexist.
- [x] **`metrics.py` + `__init__.py` kept** so the `etzhayyim_sdk` python package stays importable
      for the subscriber above (neither imports httpx, so httpx is still fully gone). `metrics.py`
      now coexists with `metrics.cljc`; `tests/test_metrics.py` is retained for the python surface.

## Substrate-boundary remediation (original gap-patch, still tracked)

- [x] No `@atproto/api` / `viem` / IPFS / Signal direct imports — SDK is the substrate boundary.
- [x] No RisingWave / Postgres / Kysely / Drizzle / Prisma — kotoba Datom log + MST + IPFS + Base L2.
- [x] No Stripe / PayPal / fiat — N/A in this SDK.
- [x] No GA4 / Meta Pixel / 3rd-party ad-tracking — N/A.
- [ ] Audit against Charter Rider (current v3.5) §2 — no violations introduced by this port.

## Reference

- ADR-2605172000 / 2605172100 (kotoba substrate) · ADR-2605215500 (mst-projector) ·
  ADR-2606072802 (`run_tests.clj` not `.sh`) · root CLAUDE.md §"Operational code = clj/bb".
