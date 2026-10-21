# Architecture

> Intelligence decides what to do. Deterministic software decides how. The policy engine decides whether it may proceed. The execution engine performs it. Verification determines what actually happened. Observability records it.

## Layers

1. **External intelligence.** MCP clients such as ChatGPT connectors, the OpenAI Responses API, Codex, Grok and the xAI API, or self-hosted agents. They plan and choose tools. They never hold device authority beyond what the owner granted to their agent identity.
2. **Control plane (gateway).** A Cloudflare Worker that terminates MCP over HTTPS, authenticates agents and routes calls to the device bound to the authenticated principal. Offline requests fail explicitly; there is no durable offline execution queue. It returns structured results. It is deliberately thin: it makes no policy decisions beyond authentication and rate limits.
3. **Execution plane (Android app).** Owns the capability registry, policy engine, approvals, execution backends, scheduler, skills, persistence and audit log. It enforces policy locally regardless of what the gateway claims.

## Request lifecycle

```
gateway invoke --> ExecutionEngine.submit
  1. idempotency / duplicate-id check (same fingerprint returns existing; changed operation conflicts)
  2. persist RECEIVED
  3. resolve capability, expiry check, JSON-schema validation
  4. capability.assess(args)    -> RiskAssessment (risk, target, exact shell command, side effects)
  5. PolicyEngine.evaluate      -> ALLOW | REQUIRE_APPROVAL | DENY | UNAVAILABLE
  6a. ALLOW            -> EXECUTING -> (VERIFYING) -> SUCCEEDED | FAILED | UNKNOWN_OUTCOME
  6b. REQUIRE_APPROVAL -> AWAITING_APPROVAL -> notification -> owner approves (bound to args hash)
                          -> re-check current agent/grant/scope/revocation/expiry, controls,
                             version/backend, exact hash and deadlines -> execute stored args
  6c. DENY / UNAVAILABLE -> DENIED / FAILED with reason
```

Each transition is a compare-and-set on the persisted record and emits an `Event` (trace id, request id, agent, capability, backend, policy decision, status, duration, error code, verification). On startup, `recover()` turns `EXECUTING`/`VERIFYING` into `UNKNOWN_OUTCOME` and never re-runs them.

States: `RECEIVED, VALIDATED, AWAITING_APPROVAL, APPROVED, DENIED, EXPIRED, EXECUTING, VERIFYING, SUCCEEDED, FAILED, CANCELLED, UNKNOWN_OUTCOME`. Legal transitions are encoded in `RequestState.canTransitionTo`.

## Policy evaluation order

The first matching rule wins:

1. Emergency stop for remote origin: **DENY**.
2. Unknown, revoked or expired agent: **DENY**.
3. Agent denylist, then scope globs: **DENY**.
4. Capability availability not executable: **UNAVAILABLE**.
5. Privileged backend while privileged execution is disabled: **UNAVAILABLE**.
6. Missing shell assessment for `shell.execute`: **DENY**.
7. Effective risk (maximum of descriptor and argument assessment) is `CRITICAL`: **REQUIRE_APPROVAL**.
8. Matching standing action grant (canonical capability/version/arguments hash, backend, risk cap, expiry): **ALLOW**. Shell cannot use a generic unconstrained grant here.
9. Arbitrary shell assessment: explicit `BROAD` grants **ALLOW**; an exact configured standing command **ALLOWS**; otherwise **REQUIRE_APPROVAL**.
10. Risk at or below `autoApproveUpTo`: **ALLOW**; otherwise **REQUIRE_APPROVAL**.

Acceptance of an exact owner approval revalidates eligibility/deny checks without asking for the same risk approval again. Approval cannot revive a revoked or expired grant.

## Execution backends

| Backend | Identity | Used for |
| --- | --- | --- |
| `NATIVE` | app uid | Implemented framework diagnostics, packages, notifications and next-alarm access; calendar/SMS provider tools are not implemented |
| `ADB_SERVICE` | actual bundled daemon identity, verified UID 2000 | Authorized `app_process` Binder service, no companion; common bounded shell runner |
| `APP_SHELL` | app uid | Model value; not a silent substitute for privileged execution |
| `SHIZUKU` | actual UserService identity when authorized | Optional explicitly selected backend using the common shell executor; current physical shell evidence uses the bundled backend |
| `ACCESSIBILITY` | app accessibility service | UI tree inspection and semantic interaction *(planned)* |
| `NETWORK` | app uid | Outbound HTTP data sources, e.g. the Open-Meteo forecast |
| `ROOT` | uid 0 | Reserved; not used on stock devices |

Results always report the backend and identity that actually executed. A request whose selected privileged backend is unavailable fails with `PRIVILEGE_REQUIRED`. It is never retried as `APP_SHELL`.

## Skills and automations

A skill (`core/.../Skills.kt`) is a versioned JSON workflow made of capability steps. It supports `${input.*}`/`${steps.<id>.output.*}` templates, `when` conditions (`and/or/not/eq/ne/gt/gte/lt/lte/in/exists/truthy`) and per-step `onError`. Every step is submitted through `ExecutionEngine.submit` under the invoking agent's identity, so a skill cannot exceed the agent's own grant. Nested skills are rejected. Missing data never satisfies a numeric threshold.

Durable skills persist their execution cursor, step records and pending approval reference. Resume uses stable child request IDs and does not repeat a completed mutation. Runs distinguish partial success, cancellation and unknown outcome. Runtime completion events can continue a waiting workflow.

Implemented automations bind one-time/interval timing to skills under the local automation grant, with WorkManager/AlarmManager scheduling, durable slot uniqueness and reboot restoration. Generic event-driven triggers and comprehensive missed-run/timezone behavior remain unfinished; do not infer them from the data model. Exact and deferrable reminders use separate appropriate APIs.

## Device and client routing

Device IDs are randomly generated app identities, not hardware identifiers. Each Durable Object routes an authenticated principal to its bound phone and stores that phone's capability catalog. OAuth pairing binds the same device and a local agent credential. The current MCP surface does not offer arbitrary `device_id` selection or a tested multi-device chooser. Each phone independently enforces its current grant. Existing same-name OAuth clients remain distinct principals; the owner UI and request-linked permission review distinguish them.

SQLiteOpenHelper v3 stores requests, transitions, events, agents, skills, skill runs, step results, automations, scheduler runs, reminders and notification metadata. Keystore-wrapped private preferences hold credentials and pending pairing data; ordinary settings use preferences. Startup checks database integrity, recovers uncertain in-flight work without replay, restores pending approvals/schedules and starts the owner-enabled outbound connection. ONLINE requires protocol-2 acknowledgement. Pairing publication also requires acknowledgement.

## Module boundaries

- `core` has no Android dependency, so the policy, engine, schema, skill and canonicalization code is unit-tested on the JVM and in CI.
- `app` provides Android implementations of `RequestStore`, `EventSink`, `ApprovalGate` and `AgentDirectory`, plus the capabilities, backends, transport and UI.
- `gateway` shares only the wire contract with the device. It never imports device code.

Home activity uses a bounded SQLite column projection. Large argument/output JSON is decoded only for an explicitly opened result; pending approval and recent app-denial records remain separately loaded for their owner review. The view subscribes to runtime changes rather than polling full history. Existing detailed management remains available.

Pass 3 device sockets carry persisted generation/readiness attachments. In-flight gateway requests are owned by their accepting socket generation; replacement/disconnect returns an explicit uncertain outcome and original request lookup guidance. This does not provide a durable gateway result cache or offline mutation queue. Socket state survives hibernation through attachments/storage; pending promises do not. Interrupted reminders have durable reconciliation work and consult the original device execution record before any delivery attempt.
