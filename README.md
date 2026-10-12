# Agentic SDLC: URL shortener + governed orchestration layer

A runnable prototype that turns a free-text requirement into a reviewable engineering outcome (code, tests, docs,
release report) using agents under explicit governance, plus the URL shortener those agents work on.

* **Java 21, zero external dependencies** (JDK only). The build environment could not reach Maven Central, so there is
  no Spring/JUnit; `src/test/.../testkit` is a ~100-line JUnit stand-in. Everything below was compiled and run for real.

## Setup and run

```bash
./build.sh test                                   # compile everything + run all 38 tests (about 40 s)
java -cp out com.acme.shortener.ShortenerApp 8080 # run the URL shortener service
java -cp out com.acme.sdlc.Scenarios all runs     # run the 3 scenarios end to end (about 35 s)
java -cp out com.acme.sdlc.Scenarios brownfield   # or one of: greenfield | brownfield | ambiguous
```

Each scenario writes `runs/<scenario>/summary.md` (final engineering summary), `audit.jsonl` (hash-chained audit log),
`change.diff` (the code change). Pre-generated outputs are included.

### Shortener API

| Method | Path | Result |
|---|---|---|
| POST | `/api/v1/links` `{url, alias?, ttlSeconds?}` | 201 link; 400 invalid; 409 alias taken; 429 rate limited |
| GET | `/{code}` | 302 redirect (404 unknown, 410 expired) |
| GET | `/api/v1/links/{code}` , `/stats` | link metadata, click analytics |
| DELETE | `/api/v1/links/{code}` | 204 |
| GET | `/health` | 200 |

Security: http(s) only, no credentials in URLs, loopback/private/link-local hosts rejected (SSRF guard), bounded body
and URL sizes, per-client token-bucket limits, no stack traces in errors. Analytics are aggregate only: counts per day and
per referrer *host*; no IP, user agent or full referrer is stored.

## Testing approach

38 tests, deterministic (injected clocks and sleepers, no real waiting except one concurrency test):

* **Shortener (13)**: validation, expiry via a controllable clock, rate limiting, concurrency (no lost clicks), and real
  HTTP end-to-end tests on an ephemeral port.
* **Orchestrator (17)**: graph validation (cycles, two producers, racy cross-branch reads), parallel branches that must run
  concurrently (barrier test), gates, retries/backoff/MTTR, fallback, rollback, circuit breaker, attempt budget, deadline,
  kill switch, approvals (reject, fail-closed, human input, reuse), policies, autonomy boundaries, re-planning, audit tamper detection.
* **SDLC layer (8)**: analyzers, workspace confinement and exact restore, all-or-nothing patching, and all three scenarios
  end to end (including the failure path in the brownfield run).

See `docs/ARCHITECTURE.md` for design, orchestration model, decisions, trade-offs and limitations.
