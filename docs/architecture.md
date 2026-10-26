# Architecture

## Pass 9 checkpoint — October 21, 2026

SQLite v8 adds indexed proposal metadata without replacing immutable detail JSON or device request authority. Inbox reads use keyset projections. The existing sparse runtime tick reconciles bounded accepted/request-state candidates and expired proposals; startup recovery pages active rows. Retries require an authoritative missing request and keep the same request ID/deadline. Recorded unknown outcomes are never resubmitted. See [pass 9](engineering-pass-9.md).

## October 21, 2026 checkpoint

Action Inbox is a device-SQLite proposal layer over the existing engine. An authenticated agent can store/read/withdraw only its own proposals. Local owner acceptance atomically records a stable execution link, then submits the immutable versioned action as the original agent with REMOTE origin. Current policy and approval still apply. Recovery consults the existing request before any dispatch; it does not repeat unknown mutations. Lists use bounded keyset pagination and omit action arguments from remote summaries. Schema 7 adds indexed proposal rows without modifying existing tables.

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
8. Matching standing grant (exact action hash or explicit package constraints, backend, risk cap, expiry): **ALLOW**. Shell cannot use a generic unconstrained grant here.
9. Arbitrary shell assessment: explicit `BROAD` grants **ALLOW**; an exact configured standing command **ALLOWS**; otherwise **REQUIRE_APPROVAL**.
10. Risk at or below `autoApproveUpTo`: **ALLOW**; otherwise **REQUIRE_APPROVAL**.

Acceptance of an exact owner approval revalidates eligibility/deny checks without asking for the same risk approval again. Approval cannot revive a revoked or expired grant.

## Execution backends

| Backend | Identity | Used for |
| --- | --- | --- |
| `NATIVE` | app uid | Implemented framework diagnostics, packages, notifications and next-alarm access; managed app-owned files, Calendar Provider and owner-selected SAF documents; universal SMS/RCS database access is not implemented |
| `ADB_SERVICE` | actual bundled daemon identity, verified UID 2000 | Authorized `app_process` Binder service, no companion; common bounded shell runner |
| `APP_SHELL` | app uid | Model value; not a silent substitute for privileged execution |
| `SHIZUKU` | actual UserService identity when authorized | Optional explicitly selected backend using the common shell executor; current physical shell evidence uses the bundled backend |
| `ACCESSIBILITY` | app accessibility service | Bounded foreground semantic inspection, selectors, event-driven waits, and leased interaction; owner/system/password controls excluded |
| `NETWORK` | app uid | Outbound HTTP data sources, e.g. the Open-Meteo forecast |
| `ROOT` | uid 0 | Reserved; not used on stock devices |

Results always report the backend and identity that actually executed. A request whose selected privileged backend is unavailable fails with `PRIVILEGE_REQUIRED`. It is never retried as `APP_SHELL`.

## Skills and automations

A skill (`core/.../Skills.kt`) is a versioned JSON workflow made of capability steps. It supports `${input.*}`/`${steps.<id>.output.*}` templates, `when` conditions (`and/or/not/eq/ne/gt/gte/lt/lte/in/exists/truthy`) and per-step `onError`. Every step is submitted through `ExecutionEngine.submit` under the invoking agent's identity, so a skill cannot exceed the agent's own grant. Nested skills are rejected. Missing data never satisfies a numeric threshold.

Durable skills persist their execution cursor, step records and pending approval reference. Resume uses stable child request IDs and does not repeat a completed mutation. Runs distinguish partial success, cancellation and unknown outcome. Runtime completion events can continue a waiting workflow.

Implemented automations bind typed one-time, interval, daily, weekday, weekly or monthly timing to skills under the local automation grant. WorkManager/AlarmManager dispatch, immutable run snapshots and unique slot ownership are reconciled with SQLite. Missed-run policy and a pinned timezone are explicit; wall-clock rules account for DST. Reboot restoration exists in code but comprehensive physical reboot/idle validation remains pending. Exact and deferrable reminders use their appropriate APIs.

Owner-requested Run now creates a separate durable one-shot run without advancing or rewriting the saved recurring schedule. Repeating the same manual identity returns that run; cancellation can claim a queued slot before its skill starts. The owner UI exposes run/step states and bounded results. Saveable form state survives ordinary Activity recreation; it is not a replacement for saving the durable automation definition.

## Device and client routing

Device IDs are randomly generated app identities, not hardware identifiers. Each Durable Object routes an authenticated principal to its bound phone and stores that phone's capability catalog. OAuth pairing binds the same device and a local agent credential. The current MCP surface does not offer arbitrary `device_id` selection or a tested multi-device chooser. Each phone independently enforces its current grant. Existing same-name OAuth clients remain distinct principals; the owner UI and request-linked permission review distinguish them.

SQLiteOpenHelper v6 stores requests, transitions, events, agents, skills, skill runs, step results, automations, scheduler runs, reminders, document-root grants, cooperative screen ownership and notification deduplication metadata. Keystore-wrapped private preferences hold credentials and pending pairing data; ordinary settings use preferences. Startup checks database integrity, recovers uncertain in-flight work without replay, restores pending approvals/schedules and starts the owner-enabled outbound connection. ONLINE requires protocol-2 acknowledgement. Pairing publication also requires acknowledgement.

## Module boundaries

- `core` has no Android dependency, so the policy, engine, schema, skill and canonicalization code is unit-tested on the JVM and in CI.
- `app` provides Android implementations of `RequestStore`, `EventSink`, `ApprovalGate` and `AgentDirectory`, plus the capabilities, backends, transport and UI.
- `gateway` shares only the wire contract with the device. It never imports device code.

The adaptive five-section owner interface shares one lifecycle-aware runtime state holder; navigation and form state are saveable. Home and Activity use bounded SQLite column projections. Large argument/output JSON is decoded only for an explicitly opened result; pending approval and recent app-denial records remain separately loaded for their owner review. The view subscribes to runtime changes rather than polling full history. Existing detailed management remains available.

Pass 3 device sockets carry persisted generation/readiness attachments. In-flight gateway requests are owned by their accepting socket generation; replacement/disconnect returns an explicit uncertain outcome and original request lookup guidance. This does not provide a durable gateway result cache or offline mutation queue. Socket state survives hibernation through attachments/storage; pending promises do not. Interrupted reminders have durable reconciliation work and consult the original device execution record before any delivery attempt.

SQLite v4 adds history-order and credential-hash indexes without modifying existing records. Production request creation/state transition and their audit insert now share a database transaction; record update notifications are emitted after commit. A failed audit insert cannot leave an EXECUTING record before backend registration. Persistent storage failure after external side effects can still prevent recording a definitive outcome; do not infer safe failure from missing output.

InteractionMode is registry metadata independent of privilege: BACKGROUND, SCREEN, MAY_OPEN_SCREEN, UNSPECIFIED. Native app launch is explicitly SCREEN; arbitrary approved shell may use the screen. Device runtime reports actual interactive/keyguard/device-lock state. Owner-enabled package-update recovery starts the connection service without opening MainActivity. Managed-file paths are canonical and confined to the app-owned managed directory; no universal storage permission is implied.

## Pass 4 runtime additions

Automation configuration is independent from a slot's frozen skill/input snapshot. A unique `(automation_id, slot_at)` record owns each run; its skill cursor and child requests remain authoritative after interruption. Skill-state events trigger scheduler reconciliation, with startup and bounded periodic reconciliation for missed updates. Nonterminal approval waits have no finish timestamp. Slot completion and next-cursor advancement share SQLite; Android dispatch is acknowledged/reconciled outside that transaction.

A single SQLite screen lease covers cooperative structured screen actions. It is not a global registry lock and cannot isolate arbitrary privileged shell. Binder result data uses reliable PFD streams rather than large reply transactions. Device SQLite keeps the original result; authenticated chunks and recent metadata provide bounded recovery through the same engine.

Calendar Provider, managed files and SAF documents are distinct backend surfaces under the same registry/policy/engine. SAF roots are locally selected persisted URI grants, never caller-created raw URI authority. The commute workflow is an ordinary SkillDefinition and generic automation, not a second weather scheduler.

## Semantic UI and published notification actions

The native AccessibilityService exposes bounded semantic snapshots and explicit selectors through the same registry. Snapshots bind agent, service generation, foreground package/window, event epoch and a short monotonic lifetime. Mutations require a matching current node and an existing agent-owned screen lease. Accessibility events invalidate references without retaining their text. Password nodes and descendants, SIConnect owner controls, Android permission/lock/biometric surfaces are excluded. A narrow debug fixture is isolated from normal management; it is not an owner-control bypass.

Capability-family scope permits policy evaluation. A separate owner-authenticated package allowance can authorize `ui.click` and `ui.set_text` within a chosen nonprotected app under the existing standing-grant model. It does not authorize another app or arbitrary shell. Generic click acceptance is not a verified send, install or other business outcome.

NotificationListenerService exposes exact current-user notification/action references, bounded to 16 actions and eight text inputs. Updates, removal, process/listener restart invalidate references. Actions revalidate package/creator UID, version and PendingIntent before dispatch. Own-app and authentication-required actions are refused. Supported RemoteInput replies use a mutable broadcast/service intent, run under the same high-impact policy and cooperative lease, and report dispatch with recipient/delivery unverified. No notification bodies or suggested replies enter routine discovery; opt-in action labels may contain app-supplied sensitive text.

Cancellation preserves uncertainty: cancelling executing side-effecting work requests backend cancellation and waits for its actual recorded outcome rather than immediately asserting a clean `CANCELLED`. Startup marks interrupted execution unknown and pre-execution records failed without replay. These are recovery classifications, not proof that an interrupted external mutation did nothing.


## Pass 10 local bootstrap

The owner UI calls LocalAdbBootstrap, which resolves only addresses assigned to this phone, pairs using TLS/SPAKE2, authenticates the dynamic connection service and launches the existing DirectShellDaemon with the installed APK and app UID. Temporary ADB closes before the final Binder command verification; ordinary execution still uses the original policy/engine/Binder transport. No new MCP tool or Cloudflare execution authority is introduced. Existing explicit Shizuku selection remains available. SQLite 9 adds compact result/idempotency tombstones and reference-aware retention. Phone-local pairing/bootstrap, daemon recovery and the tested owner-authorized reboot passed; broader network/revocation acceptance remains open.

## Bounded runtime continuation

Startup reconciles engine outcomes and prepares workflow cursors before readiness; it does not execute recovered workflow steps in that phase. After readiness, bounded keyed workers resume workflows and reconcile schedule state independently of event consumers. Signals arriving during an active lookup coalesce into a fair follow-up at the queue tail. A full queue leaves the persisted backlog for the existing maintenance tick. Proposal maintenance has its own bounded worker. This separates execution waits from readiness and observer delivery while retaining stable run/child identities.

ReminderLifecycle serializes each reminder's state and platform dispatch through a bounded set of mutexes. Delivery uses the engine's nonwaiting admission option while holding that lock, then waits outside it. Cancellation therefore sees either a stopped reminder or its authoritative delivery request. In-flight cancellation persists CANCELLING and reconciles the actual terminal/unknown result; completed history is retained. The Android adapter still owns AlarmManager/WorkManager dispatch. These paths have host tests; the versionCode 13 physical lifecycle matrix remains pending.
