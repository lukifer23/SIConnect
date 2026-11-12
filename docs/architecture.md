# Architecture

Current source: **code 27 / 0.6.0-dev.19 / main SQLite 12**. Host, physical and authenticated Codex-command checks pass; see the installed [code-27 checkpoint](engineering-pass-22.md). Separate Codex phone-MCP pairing and fresh client catalog refresh remain open. The owner interface has dedicated expanded Fold panes and a persistent per-agent Full control profile. Full control is an explicit authenticated policy choice, with same-ID continuation of eligible waiting requests; it does not replace the execution engine or Android authorization. See [permissions](permissions.md) and [the security model](security-model.md).

The earlier [November 6 code-21 review](source-audit-2026-11-06.md) verified actual-dispatch policy revalidation, pre-dispatch cancellation, caller workflow deadlines, durable owner commute execution and bounded private launch. Its storage/native/private-display and caller/backend-death evidence remains historical. Wider lifecycle and reboot acceptance is not implied by the saved Full control grant. Historical [pass 18](engineering-pass-18.md) covers caller-process lifetime, owner-only policy and locked native recovery; [pass 17](engineering-pass-17.md) covers private displays and [pass 16](engineering-pass-16.md) retention. See [the execution contract](private-app-execution.md) and [build plan](build-plan.md).

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

Each transition is a compare-and-set on the persisted record and emits an `Event` (trace id, request id, agent, capability, backend, policy decision, status, duration, error code, verification). On startup, `recover()` turns `EXECUTING`/`VERIFYING` into `UNKNOWN_OUTCOME` and never re-runs them. The 60-second maintenance sweep (`expireStale`) also closes requests orphaned without a restart: anything still `RECEIVED`/`VALIDATED`/`APPROVED` after five minutes becomes `FAILED` (`ADMISSION_STALLED`, not executed), and an `EXECUTING` record with no live worker becomes `UNKNOWN_OUTCOME`. Waiters re-read the durable record at least every second, so a dropped in-memory update cannot stall them.

A failure is `UNKNOWN_OUTCOME` only when side effects may have started. Capabilities that set `declaresMutationBoundary` call `ExecutionContext.beginMutation()` immediately before their first write; any failure before that point (validation, lookups, permission checks) is recorded as `FAILED` with nothing written. Calendar, Documents, managed Files and reminders use this; other capabilities keep the conservative default.

States: `RECEIVED, VALIDATED, AWAITING_APPROVAL, APPROVED, DENIED, EXPIRED, EXECUTING, VERIFYING, SUCCEEDED, FAILED, CANCELLED, UNKNOWN_OUTCOME`. Legal transitions are encoded in `RequestState.canTransitionTo`.

## Policy evaluation order

The first matching rule wins:

1. Emergency stop for remote origin: **DENY**.
2. Unknown, revoked or expired agent: **DENY**. Canonical Full control removes its agent-grant expiry when the owner enables it; request and transport deadlines are independent.
3. Agent denylist, then scope globs: **DENY**. Canonical Full control uses an empty denylist and wildcard scope.
4. Local-owner-only capability without the local-owner principal and origin: **DENY**.
5. Interaction outside the agent’s shared-screen choice: **DENY**. Canonical Full control enables shared-screen actions.
6. Capability availability not executable: **UNAVAILABLE**.
7. Privileged backend while privileged execution is disabled: **UNAVAILABLE**, except the existing local-owner private-workspace cleanup path.
8. Missing shell assessment for `shell.execute`: **DENY**.
9. Effective risk (maximum of descriptor and argument assessment) is `CRITICAL` **and the grant is not explicit Full control**: **REQUIRE_APPROVAL**.
10. Matching standing grant (exact action hash or explicit package constraints, backend, risk cap, expiry): **ALLOW**. Shell cannot use a generic unconstrained grant here.
11. Arbitrary shell assessment: explicit `BROAD` grants **ALLOW**; an exact configured standing command **ALLOWS**; otherwise **REQUIRE_APPROVAL**.
12. Risk at or below `autoApproveUpTo`: **ALLOW**; otherwise **REQUIRE_APPROVAL**. Canonical Full control has `CRITICAL` automatic approval, so all available SIConnect actions pass without per-action approval once the earlier eligibility checks pass.

Acceptance of an exact owner approval revalidates eligibility/deny checks without asking for the same risk approval again. Approval cannot revive a revoked or expired grant.

### Persistent Full control and explicit pending continuation

`AgentGrant.fullControl` defaults to false. `AgentRecord.fullAccessPreviousGrant` stores the previous custom grant alongside the active full grant in the existing serialized agent row; no database schema, credential or device identity replacement is required. One authenticated local-owner decision sets canonical wildcard/no-deny scope, `CRITICAL` automatic approval, broad shell, shared-screen access and no expiry. The mode persists across app/process restarts until the owner disables it or revokes the identity. OFF restores the saved custom permissions and prior expiry while preserving current identity, label, revocation and any later explicit expiry. Individual family/shell/app setters reject edits while the full profile is active. Android privileges and daemon reboot recovery are separate concerns.

The enable UI discloses waiting actions, then invokes a local-owner-only continuation helper after the full-profile commit. It examines a bounded batch of that agent’s existing `AWAITING_APPROVAL` records and requires the same device/agent, live request and approval deadlines, stored action hash, capability version/backend, schema and current `ALLOW` policy/availability. It passes the same request ID and hash to the existing engine approval-resolution path, which repeats execution admission and dispatch checks. No new invocation, origin substitution, retry identity or terminal-state reset is created. Denied/completed/cancelled/expired/unknown outcomes are not candidates; skipped or additional waiting work stays in the existing request store for explicit review.

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

SQLiteOpenHelper v12 stores requests, transitions, events, agents, skills, skill runs, step results, automations, scheduler runs, reminders, document-root grants, cooperative screen ownership and notification deduplication metadata. Its schedule/history metadata projections support bounded owner lists without hydrating complete histories. Keystore-wrapped private preferences hold credentials and pending pairing data; ordinary settings use preferences. Startup checks database integrity, recovers uncertain in-flight work without replay, restores pending approvals/schedules and starts the owner-enabled outbound connection. ONLINE requires protocol-2 acknowledgement. Pairing publication also requires acknowledgement.

## Module boundaries

- `core` has no Android dependency, so the policy, engine, schema, skill and canonicalization code is unit-tested on the JVM and in CI.
- `app` provides Android implementations of `RequestStore`, `EventSink`, `ApprovalGate` and `AgentDirectory`, plus the capabilities, backends, transport and UI.
- `gateway` shares only the wire contract with the device. It never imports device code.

The adaptive five-section owner interface shares one lifecycle-aware runtime state holder; navigation and form state are saveable. Home and Activity use bounded SQLite column projections. Large argument/output JSON is decoded only for an explicitly opened result; pending approval and recent app-denial records remain separately loaded for their owner review. The view subscribes to runtime changes rather than polling full history. Existing detailed management remains available.

Pass 3 device sockets carry persisted generation/readiness attachments. In-flight gateway requests are owned by their accepting socket generation; replacement/disconnect returns an explicit uncertain outcome and original request lookup guidance. This does not provide a durable gateway result cache or offline mutation queue. Socket state survives hibernation through attachments/storage; pending promises do not. Interrupted reminders have durable reconciliation work and consult the original device execution record before any delivery attempt.

SQLite v4 adds history-order and credential-hash indexes without modifying existing records. Production request creation/state transition and their audit insert now share a database transaction; record update notifications are emitted after commit. A failed audit insert cannot leave an EXECUTING record before backend registration. Persistent storage failure after external side effects can still prevent recording a definitive outcome; do not infer safe failure from missing output.

InteractionMode is registry metadata independent of privilege: BACKGROUND, ISOLATED_SCREEN, SCREEN, MAY_OPEN_SCREEN, UNSPECIFIED. ISOLATED_SCREEN uses owned virtual-display rendering/semantic input, with no default-display input fallback. Disabling an agent’s shared-screen permission allows only BACKGROUND and ISOLATED_SCREEN, before standing grants or broad-shell evaluation. Native app launch is explicitly SCREEN; arbitrary approved shell may use the screen. Device runtime reports actual interactive/keyguard/device-lock state. Owner-enabled package-update recovery starts the connection service without opening MainActivity. Managed-file paths are canonical and confined to the app-owned managed directory; no universal storage permission is implied.

## Pass 4 runtime additions

Automation configuration is independent from a slot's frozen skill/input snapshot. A unique `(automation_id, slot_at)` record owns each run; its skill cursor and child requests remain authoritative after interruption. Skill-state events trigger scheduler reconciliation, with startup and bounded periodic reconciliation for missed updates. Nonterminal approval waits have no finish timestamp. Slot completion and next-cursor advancement share SQLite; Android dispatch is acknowledged/reconciled outside that transaction.

A single SQLite screen lease covers cooperative structured screen actions. It is not a global registry lock and cannot isolate arbitrary privileged shell. Binder result data uses reliable PFD streams rather than large reply transactions. Device SQLite keeps the original result; authenticated chunks and recent metadata provide bounded recovery through the same engine.

Calendar Provider, managed files and SAF documents are distinct backend surfaces under the same registry/policy/engine. SAF roots are locally selected persisted URI grants, never caller-created raw URI authority. The commute workflow is an ordinary SkillDefinition and generic automation, not a second weather scheduler.

## Semantic UI and published notification actions

The native AccessibilityService exposes bounded semantic snapshots and explicit selectors through the same registry. Snapshots bind agent, service generation, foreground package/window, event epoch and a short monotonic lifetime. Mutations require a matching current node and an existing agent-owned screen lease. Accessibility events invalidate references without retaining their text. Password nodes and descendants, SIConnect owner controls, Android permission/lock/biometric surfaces are excluded. A narrow debug fixture is isolated from normal management; it is not an owner-control bypass.

In Custom access, capability-family scope permits policy evaluation and a separate owner-authenticated package allowance can authorize `ui.click` and `ui.set_text` within one nonprotected app under the standing-grant model. That allowance does not authorize another app or arbitrary shell. Explicit Full control instead authorizes available UI/private operations across supported apps automatically while retaining protected native UI, fresh-node and lease checks; its broad shell authority remains separate from those cooperative checks. Generic click acceptance is not a verified send, install or other business outcome.

NotificationListenerService exposes exact current-user notification/action references, bounded to 16 actions and eight text inputs. Updates, removal, process/listener restart invalidate references. Actions revalidate package/creator UID, version and PendingIntent before dispatch. Own-app and authentication-required actions are refused. Supported RemoteInput replies use a mutable broadcast/service intent, run under the same high-impact policy and cooperative lease, and report dispatch with recipient/delivery unverified. No notification bodies or suggested replies enter routine discovery; opt-in action labels may contain app-supplied sensitive text.

Cancellation preserves uncertainty: cancelling executing side-effecting work requests backend cancellation and waits for its actual recorded outcome rather than immediately asserting a clean `CANCELLED`. Startup marks interrupted execution unknown and pre-execution records failed without replay. These are recovery classifications, not proof that an interrupted external mutation did nothing.


## Pass 10 local bootstrap

The owner UI calls LocalAdbBootstrap, which resolves only addresses assigned to this phone, pairs using TLS/SPAKE2, authenticates the dynamic connection service and launches the existing DirectShellDaemon with the installed APK and app UID. Temporary ADB closes before the final Binder command verification; ordinary execution still uses the original policy/engine/Binder transport. No new MCP tool or Cloudflare execution authority is introduced. Existing explicit Shizuku selection remains available. SQLite 9 adds compact result/idempotency tombstones and reference-aware retention. Phone-local pairing/bootstrap, daemon recovery and the tested owner-authorized reboot passed; broader network/revocation acceptance remains open.

## Bounded runtime continuation

Startup reconciles engine outcomes and prepares workflow cursors before readiness; it does not execute recovered workflow steps in that phase. After readiness, bounded keyed workers resume workflows and reconcile schedule state independently of event consumers. Signals arriving during an active lookup coalesce into a fair follow-up at the queue tail. A full queue leaves the persisted backlog for the existing maintenance tick. Proposal maintenance has its own bounded worker. This separates execution waits from readiness and observer delivery while retaining stable run/child identities.

ReminderLifecycle serializes each reminder's state and platform dispatch through a bounded set of mutexes. Delivery uses the engine's nonwaiting admission option while holding that lock, then waits outside it. Cancellation therefore sees either a stopped reminder or its authoritative delivery request. In-flight cancellation persists CANCELLING and reconciles the actual terminal/unknown result; completed history is retained. The Android adapter still owns AlarmManager/WorkManager dispatch. These paths have host tests. Code-13 physical exact delivery and interrupted-claim reconciliation passed; concurrent cancellation/update/restore and injected startup-failure acceptance remain pending. See [pass 13](engineering-pass-13.md).

## MCP retry identity

Gateway source decorates each tool schema with optional `_siconnectOperationKey`, strips it from capability arguments and derives a stable request/idempotency identity from authenticated device, agent and key. A keyed request consistently omits explicit execution expiry: the phone freezes its default at first admission, and subsequent retries retain the same immutable-operation fingerprint. A separate per-attempt transport deadline bounds gateway waiting without extending device authority. Existing unkeyed calls keep a fresh UUID and explicit expiry.

A simultaneous duplicate reports `REQUEST_IN_FLIGHT` with original-request lookup; changed capability/arguments under the same key conflict at the device engine. The gateway does not cache action results or replay offline mutations. Device SQLite remains authoritative. This change passed local SDK v1/v2 and actual same-principal hosted read/write acceptance. The controlled keyed-loss/restart matrix remains pending. See [retry guidance](gateway-deployment.md#retry-identity-and-original-result-recovery).

## Shared discovery and network wake-up

Code 14 measures related document readiness once per catalog refresh and passes measured availability into detail generation. The owner projection may reuse metadata for five seconds, with invalidation on relevant owner/backend/service changes; ordinary engine policy/availability checks stay fresh. Registry provider IO runs outside its monitor, and an invalidated in-flight measurement cannot republish its stale cache.

The gateway loop owns a validated-default-network callback and releases it when stopped. A coalesced generation signal wakes timed reconnect backoff without replaying invocations or overriding an owner pause. Observer registration failure records a diagnostic and preserves timed retry. See [pass 14](engineering-pass-14.md) for measured results and limits.


## Native Termux command channel

Code 25 adds an independent Termux app-UID RUN_COMMAND channel with explicit Android permission and owner external-app setup. It uses canonical shell approval, rechecks live policy before IPC, and persists nonce-authenticated one-shot callbacks separately from core request outcomes. Late results never replay work or rewrite a terminal UNKNOWN_OUTCOME. See [the integration contract](termux-integration.md) and [physical recovery evidence](engineering-pass-20.md).

Code 27's `codex.execute` constructs a fresh noninteractive CLI invocation using the existing owner environment and native Termux Node. It admits a real same-agent `termux.execute` child through current policy with the original deadline and deterministic child ID. The parent exposes actual child/native observations and never claims independent task-effect verification. It changes no CLI credentials/configuration and creates no MCP pairing. The inspected phone Codex MCP configuration had zero servers; live model execution and a separate Codex phone connection remain pending. See [the Codex contract](termux-integration.md#noninteractive-codex-execution--build-27).

## Owner recovery and startup stages

Code 27 names each startup stage and retains its state, attempts, timestamps and exception type in the process. Required stages check the database, restore local principals, recover screen ownership and reconcile interrupted execution. They run once per process; a failure is surfaced rather than hidden by a data reset or repeated authority recovery. Selected-backend observation, built-in workflow checks, audit maintenance and saved automation/reminder restoration are deferred stages. One deferred failure does not pretend the stage succeeded or erase essential runtime readiness.

The owner's Recovery center reads saved gateway/access settings separately from actual gateway acknowledgement, selected-backend availability and Accessibility/notification service binding. `system.recovery_status` reports the same bounded observations, startup stages and caller-scoped Termux callback evidence without exposing credentials or message/notification bodies. Refreshing observations performs no repair or grant change.

Repairs are explicit, local-owner engine requests with saved original IDs and observable outcomes: `system.retry_startup` retries only failed/interrupted deferred stages after essential recovery succeeded; `system.rebind_notifications` asks Android to bind SIConnect's own already-granted listener; the existing `system.restore_bridge` restores only the saved authorized bridge route. Full control does not authorize these owner-only tools remotely. No repair silently replays uncertain commands. Accessibility has no equivalent supported notification-style rebind API: its recovery action opens Android's supported settings panel for the owner to review/toggle existing access, without rewriting the global enabled-service list. Code-27 physical repair/recreation acceptance is pending.

## Owned Clock timers and bounded Messages recipes

`clock.timer_start/status/cancel` use a separate SQLite `clock.db` schema-1 journal for agent-owned timer identity and operation dispatch phases. Ownership is recorded before starting, and uncertain starts/cancellations are not replayed. The selected authorized backend dispatches the typed Clock start; a current creator-bound notification action supplies supported cancellation. Exact labels, package install identity and unrelated Clock work are revalidated. Shared-screen coordination applies because Clock may open UI. Notification observations remain distinct from independent timer-store/duration verification.

`messages.recent` performs bounded real Google Messages navigation/readback within an owned private display. It checks the exact visible conversation label/header and revalidates the original parent plus every same-agent `headless.*` child. Opening a conversation may mark it read or trigger app-managed read receipts. Results expose count fulfillment, coverage, stop reason and cleanup state rather than inventing a complete history or verified Contacts identity. Generic sending/deletion is not part of this recipe. New Clock/Messages device acceptance is pending; earlier bot workflows through general controls are historical evidence only.

## Agent display metadata

The owner may rename an existing nonlocal identity through an atomic label-only SQLite patch with an expected-current-label check. This preserves active grants, credentials, expiry, Full control, its previous custom snapshot, unrelated metadata and unknown serialized fields. Authentication touches patch the latest row instead of restoring an older whole-record label/grant. The UI presents the stored name, credential provenance, identity suffix and current grant separately; a legacy shared credential does not establish which bot used it or that a client is currently connected. No automatic rename, token rotation or new connection occurs.
