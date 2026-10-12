# Architecture

## Components

```
ShortenerApp / ShortenerServer / LinkService / UrlValidator / RateLimiter   <- the product (src/main/.../shortener)
common: Json, Hashing
orchestrator/   Engine, Plan, TaskSpec, Gate, Approval, Policy(+2 policies), Blackboard, Lineage, AuditLog, Metrics
sdlc/           SdlcPlan (the workflow graph), SdlcAgents, RequirementsAnalyzer, CodebaseAnalyzer, PatchApplier,
                Workspace, BuildRunner, Scenarios, SummaryWriter
catalog/        reviewed change recipes (patch.json + templates) and index.json
```

The orchestrator knows nothing about URL shorteners: it executes any `Plan` of `TaskSpec`s with `Agent`s. `sdlc/` is one
instantiation of it. Keeping them apart is what lets the governance be tested without the domain.

## Orchestration model

**Explicit dependency graph.** `analyze -> clarify -> design -> implement -> {test || docs} -> release`. A `Plan` is
validated before anything runs: unique ids, known dependencies, no cycles, one producer per artifact, and every read of a
produced artifact must be reachable through a dependency path (a racy cross-branch read is a plan error). Plans that fail
validation or a PLAN-phase policy never execute.

**Stateful, non-linear execution.** One coordinator thread owns all state transitions and commits; worker threads run one
task each and return an immutable outcome. Independent tasks run in parallel; joins are plain dependencies (`release`
waits for `test` and `docs`). Tasks sharing a lock (`workspace`) never overlap. Failure is contained: dependents of a
failed task are SKIPPED, independent branches continue.

**Entry/exit gates.** Engine-side, side-effect-free checks, not agent code. Examples: `requirements-testable` (every
concept has acceptance criteria), `compiles-and-targeted-tests` (the implement task is not done until the workspace
compiles and impacted tests pass), `docs-traceable` (every requirement id appears in the change note), `release-checklist`.

**Context and lineage.** Agents communicate only through a versioned, append-only, JSON-frozen `Blackboard`. An agent can
read only artifacts its task declares (snapshot-pinned versions) and publish only declared artifacts; violations are
autonomy violations (permanent, not retried, audited). `Lineage` records, per committed attempt, which artifact versions
were consumed and produced and the decisions the agent recorded, so `release-report@2` traces back to the request.

**Human approval checkpoints.** `BEFORE_EXECUTION` authorises a high-impact action and can collect human input (answers
become artifact `human.<task>`); `AFTER_EXECUTION` reviews output before commit. The approver sees a reason, evidence
(artifact refs + content hashes) and questions. A broken approval channel fails closed. If a re-plan leaves the evidence
byte-identical (fingerprint by name + hash, ignoring version numbers) the earlier approval is reused instead of
re-asking. Release tasks must carry an approval (a policy enforces it at plan time).

**Bounded retries, fallback, rollback, safe-stop.**
* Retries: per task attempts are capped (max 5), exponential backoff, permanent errors skip remaining retries.
* Fallback: after the primary agent is exhausted, a fallback agent gets its own bounded attempts.
* Rollback: any attempt of a mutating task that fails (or is rejected, or hits a critical violation) is rolled back
  immediately. If a validation task (`test`) fails permanently, mutating ancestors are rolled back in reverse commit order
  and their other descendants are invalidated. Policy forces every mutating agent (and its fallback) to support rollback.
* Safe-stop: kill switch, deadline, global attempt budget, re-plan budget, a circuit breaker on repeated task failures,
  and any CRITICAL policy violation. No new work starts; state, audit and lineage are preserved.

**Policy guardrails.** `Policy` is evaluated by the engine at PLAN, PRE_TASK and POST_TASK; agents cannot skip it and a
policy that throws counts as BLOCK (fail closed). Shipped: `ChangeControlPolicy` (mutating => rollback-capable; release =>
human approval) and `SecretScanPolicy` (credential patterns in any output are CRITICAL: nothing is committed and the run
safe-stops). Compliance/security/change-control/autonomy violations are counted separately.

**Audit and observability.** Every decision is an event in a SHA-256 hash chain (edit, delete or reorder is detected by
`verify`), mirrored to JSONL, re-verifiable from disk. Metrics: success rate, retry rate, rollback rate/count, MTTR
(first failed attempt to eventual success), re-plans, approvals (requested/granted/rejected/reused), policy violations by
category, end-to-end latency, parallelism factor.

**Dynamic re-planning.** When an input artifact is republished (a stakeholder changes the request or an answer), or a
task republishes an artifact, the engine finds every committed or running task whose pinned input versions are now stale,
rolls back mutating ones in reverse order, bumps generations (so in-flight results are discarded on arrival) and re-queues
the affected sub-graph. Only input and `human.*` artifacts can be changed from outside; re-plans are budgeted.

## The three scenarios (run for real against a copy of the shortener source)

| | Request | What it demonstrates |
|---|---|---|
| Greenfield | CSV export of click statistics with formula-injection escaping | New files + route edit via recipe `stats-csv`; no ambiguity so no human is bothered; parallel test/docs; 16 tests |
| Brownfield | Rate limit per real client IP behind a load balancer | Impact analysis (RateLimiter -> ShortenerServer, ShortenerApp, tests). The *draft* agent's change trusts any `X-Forwarded-For`; its own spoofing test fails, the exit gate rejects it, the attempt is rolled back, the *reviewed* fallback applies the safe version. 17 tests |
| Ambiguous | "smarter and safer analytics for our teams, retain data as long as needed ... etc." | 6 ambiguities with questions and proposed defaults; human answers retention; after green tests the owner revises 30 -> 14 days, triggering a re-plan: rollback, redo, approval reuse. 15 tests |

## Key decisions and trade-offs

* **Deterministic agents behind an LLM-shaped contract.** Reliable, offline, testable, and the governance is the point.
  Cost: the implementer cannot invent code outside the recipe catalog (it fails closed rather than guessing).
* **Draft vs reviewed recipe.** The `-draft` variant is a deliberately flawed fixture standing in for an unreviewed AI
  draft, to prove detection, rollback and fallback work on real compile/test output, not mocks.
* **Single coordinator thread.** Removes races in commit/lineage ordering; throughput is bounded by coordination, fine at
  this scale, would shard per run in production.
* **Exact-match patching.** Edits must match exactly once; drift is a hard failure. Safer than fuzzy apply, less flexible.
* **Conservative impact analysis.** Name-based references over-approximate (e.g. `security` pulls in `UrlValidator`);
  over-testing is the safe error.
* **In-memory state.** Simple and fast; a crashed run is not resumable (a persisted event-sourced store is the next step).

## Known limitations

* Requirement analysis is lexicon-based; it can miss ambiguity outside its vocabulary.
* Approvers are scripted in demos; production needs identity, authorisation of approvers and timeouts.
* Child-JVM builds are process isolation, not a sandbox. The audit chain proves consistency, not authenticity.
* The service is single node and in-memory: links and analytics are lost on restart; no authentication on the API.
* Host names are not resolved, so DNS-rebinding bypass of the SSRF guard is out of scope.
