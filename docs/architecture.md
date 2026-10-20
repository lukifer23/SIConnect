# Architecture

> Intelligence decides what to do. Deterministic software decides how. The policy engine decides whether it may proceed. The execution engine performs it. Verification determines what actually happened. Observability records it.

## Layers

1. **External intelligence.** MCP clients such as ChatGPT connectors, the OpenAI Responses API, Codex, Grok and the xAI API, or self-hosted agents. They plan and choose tools. They never hold device authority beyond what the owner granted to their agent identity.
2. **Control plane (gateway).** A Cloudflare Worker that terminates MCP over HTTPS, authenticates agents and routes calls to a target device by `device_id`. It queues requests for offline devices and returns structured results. It is deliberately thin: it makes no policy decisions beyond authentication and rate limits.
3. **Execution plane (Android app).** Owns the capability registry, policy engine, approvals, execution backends, scheduler, skills, persistence and audit log. It enforces policy locally regardless of what the gateway claims.

## Request lifecycle

```
gateway invoke --> ExecutionEngine.submit
  1. idempotency / duplicate-id check  (returns existing record, never re-executes)
  2. persist RECEIVED
  3. resolve capability, expiry check, JSON-schema validation
  4. capability.assess(args)    -> RiskAssessment (risk, target, exact shell command, side effects)
  5. PolicyEngine.evaluate      -> ALLOW | REQUIRE_APPROVAL | DENY | UNAVAILABLE
  6a. ALLOW            -> EXECUTING -> (VERIFYING) -> SUCCEEDED | FAILED | UNKNOWN_OUTCOME
  6b. REQUIRE_APPROVAL -> AWAITING_APPROVAL -> notification -> owner approves (bound to args hash)
                          -> re-check kill switches and availability -> execute stored args
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
6. Effective risk (the maximum of the descriptor baseline and the per-argument assessment) is `CRITICAL`: **REQUIRE_APPROVAL**.
7. Operation runs an arbitrary shell command:
   - `BROAD` grant: **ALLOW**.
   - `STANDING_COMMANDS` with an exact string match: **ALLOW**.
   - Otherwise: **REQUIRE_APPROVAL**.
8. Matching standing grant (exact-match argument constraints, risk cap, expiry): **ALLOW**.
9. Risk at or below the agent's `autoApproveUpTo`: **ALLOW**. Otherwise: **REQUIRE_APPROVAL**.

## Execution backends

| Backend | Identity | Used for |
| --- | --- | --- |
| `NATIVE` | app uid | Android framework APIs: calendar, alarms, notifications, packages, battery, SMS |
| `APP_SHELL` | app uid | `sh -c` inside the app sandbox (explicitly selected; low privilege) |
| `SHIZUKU` | `uid=2000(shell)` via a Shizuku UserService | `pm`, `settings`, `dumpsys`, `cmd`, `input`, package install/uninstall, general shell |
| `ACCESSIBILITY` | app accessibility service | UI tree inspection and semantic interaction *(planned)* |
| `NETWORK` | app uid | Outbound HTTP data sources, e.g. the Open-Meteo forecast |
| `ROOT` | uid 0 | Reserved; not used on stock devices |

Results always report the backend and identity that actually executed. A request that requires `SHIZUKU` fails with `PRIVILEGE_REQUIRED` when Shizuku is down. It is never retried as `APP_SHELL`.

## Skills and automations

A skill (`core/.../Skills.kt`) is a versioned JSON workflow made of capability steps. It supports `${input.*}`/`${steps.<id>.output.*}` templates, `when` conditions (`and/or/not/eq/ne/gt/gte/lt/lte/in/exists/truthy`) and per-step `onError`. Every step is submitted through `ExecutionEngine.submit` under the invoking agent's identity, so a skill cannot exceed the agent's own grant. Nested skills are rejected. Missing data never satisfies a numeric threshold.

Automations bind a trigger (time, interval or device event) to a skill invocation. They run under a local automation agent identity with its own grant.

## Multi-device model

The gateway routes by stable, randomly generated `device_id` values. These are not hardware identifiers. Each device registers its own capability catalog and agent list. MCP tools take an optional `device_id` argument, which is required when more than one device is online. Desktop nodes can implement the same device-transport contract (`hello` / `invoke` / `result`) without changes to the MCP surface.

## Module boundaries

- `core` has no Android dependency, so the policy, engine, schema, skill and canonicalization code is unit-tested on the JVM and in CI.
- `app` provides Android implementations of `RequestStore`, `EventSink`, `ApprovalGate` and `AgentDirectory`, plus the capabilities, backends, transport and UI.
- `gateway` shares only the wire contract with the device. It never imports device code.
