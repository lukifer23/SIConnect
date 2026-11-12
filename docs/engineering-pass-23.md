# Pass 23: audit fixes for liveness, outcome honesty and recovery

November 11, 2026. Source **0.6.0-dev.20 / code 28**. Main SQLite schema **12** and Termux journal schema **1** are unchanged, so existing enrollment, agents and grants are preserved. Changes follow the November 11 full-repository audit.

## Batch 1: liveness and outcome honesty

- **Termux admission no longer locks up.** A dispatch more than 15 minutes past its deadline stops counting toward the 64-dispatch limit. The row stays `DISPATCHING`, so a late callback is still recorded and `mayStillBeRunning` stays true. Previously 64 commands that never called back blocked every `termux.execute` and `codex.execute` until app data was cleared.
- **Validation failures are no longer `UNKNOWN_OUTCOME`.** The engine marked a request dispatched before calling `execute`, so any rejection thrown inside a write capability was recorded as "effects may have occurred". Inside an automation that permanently disabled the schedule. Capabilities now opt in with `declaresMutationBoundary` and call `ExecutionContext.beginMutation()` immediately before their first write. Calendar, Documents (SAF) and managed Files are converted, and reminder validation throws side-effect-free `CapabilityException`s. A failure after the boundary remains `UNKNOWN_OUTCOME`.
- **Requests cannot be stranded.**
  - `recordUpdates` drops the oldest buffered update instead of `tryEmit` silently dropping the newest one for every subscriber.
  - `await` re-reads the durable record at least every second.
  - The 60-second sweep fails requests left in `RECEIVED`/`VALIDATED`/`APPROVED` for five minutes (`ADMISSION_STALLED`, not executed) and marks `EXECUTING` records with no live worker `UNKNOWN_OUTCOME`.
- **Gateway enrollment retries transient failures.** 408, 429 and 5xx now back off and retry instead of permanently stopping remote access. Rejections (400/401/403/404/405) stop, and the foreground notification says the bridge stopped and why.
- **Exact automations run inside the alarm window.** `AlarmReceiver` starts the run directly with a bounded wake lock (`goAsync`), the same pattern as reminders. The WorkManager job remains as a delayed watchdog; `fire` is idempotent per slot.

Host verification: `:core:test` (86), `:app:testDebugUnitTest` (151), `:app:lintDebug`, `:app:assembleRelease`. The new instrumented Termux admission test compiles; physical-device runs are still required.

## Batch 2: trust boundaries

- **Privileged attachment cannot be hijacked.** `AuthorityProvider` keeps the first live attachment and refuses (and audits, `privileged.attach_rejected`) a different Binder from another shell/root caller while it is alive. The genuine daemon re-attaches its same Binder after app-process death; a restart replaces it only after the old daemon dies. A Shizuku death notice from a replaced Binder no longer clears a newer connection.
- **Workflow command injection is blocked.** A step whose capability runs an interpreted command cannot reference `${steps.*}` (whole or partial). Validation rejects it at install; the runner re-checks before submitting, so previously installed definitions fail with `UNSAFE_COMMAND_INTERPOLATION` and execute nothing. Resolved arguments over the payload limit fail the step immediately (`STEP_ARGUMENTS_TOO_LARGE`) instead of retrying until the workflow deadline. The nested-workflow guard compares the resolved capability ID, so tool-name aliases cannot bypass it.
- **Gateway hardening** (requires redeploying the Worker):
  - Each tool is registered independently. A schema `z.fromJSONSchema` rejects, a reserved-key collision, or a duplicate tool name after `.`→`_` (including `siconnect_connection`) skips only that tool.
  - The phone's hello carries `revokedAgents`; catalog, `/connection` and `/invoke` return `AGENT_REVOKED` for those IDs. Older phones that omit the field are unaffected.
  - The per-agent in-flight cap is 8 (`AGENT_BUSY`) and the device cap is 32. Local tests showed that client aborts are not observed by the Durable Object even with `enable_request_signal`, which is now enabled and wired; the cap contains the damage regardless.
  - OAuth: the pairing code is checked without being consumed before consent is spent, scope is checked before the invitation is consumed, and the consent page sends `frame-ancestors 'none'`.
  - Server-initiated device-socket closes reject that session's pending calls immediately. Unexpected Worker errors log their class name.

Verification:
- Host: core 89, app unit 151, gateway 18 (3 new: catalog robustness, revocation, per-agent cap), typecheck and dry-run build.
- Physical, same signer, data preserved:
  - The genuine daemon attached as uid 2000 after `--restart`, and re-attached after the app process was killed. One transient `Required value was null` attach failure was retried successfully a second later; it comes from the pre-existing service-binder check, not the new rule.
  - Instrumented runs passed: `RuntimeDeviceTest` 13/13, `TermuxJournalDeviceTest` 4/4, `SchedulerDeviceTest` 3/3, `CalendarDeviceTest` 2/2, `DocumentDeviceTest` 2/2, `ManualAutomationDeviceTest` 2/2.
  - `SchedulerDeviceTest` previously failed because its fixture generated an invalid skill ID (UUID hyphens); the fixture is fixed.

## Batch 3: owner screens

- **Light mode:** the app `Surface` paints behind the transparent system bars and only its content is inset. A new `Theme.SIConnect` window theme (light, with a `values-night` dark variant) replaces the always-dark `Theme.Material.NoActionBar`, which removes dark strips behind bar icons and the dark launch flash.
- **Errors appear where the action was taken:** gateway, pairing, reminder, approval and shell dialogs show their own errors. Previously these went to a snackbar hidden under the modal dialog, and success text was shown in red as an approval "error".
- **Readable approvals:** both approval dialogs (Home and Settings) show the bot's display name and the capability title, with the raw ID in small text. The biometric prompt names the action. The Settings approval dialog now shows its busy state.
- **Action Inbox:** reloads keep the current proposal and list on screen instead of blanking on every revision. Home always offers "Inbox history", so completed and problem proposals stay reachable.
- **Fold/rotation:** these survive Activity recreation:
  - shell-permission and reminder dialogs (saved by ID);
  - the agent revoke confirmation;
  - the ADB pairing endpoint and code, held in a retained ViewModel and never written to the saved-instance Bundle.
- **Automations:**
  - Delete, with confirmation and owner authentication. It is refused while a run is active, and run history is kept.
  - Custom workflows can request the typed capabilities they need through `allowLocalAutomationFromOwner`. That grant is owner-authenticated, limited to 32 entries, never includes shell, and each run still passes policy.
  - Times follow the phone's 12/24-hour setting.
- **Accessibility:**
  - A shared `SwitchRow` makes each labelled switch row a single TalkBack switch with a full-row touch target. It is used at 8 sites.
  - The Settings rail exposes its selected item.
  - Decorative chevrons are hidden from TalkBack.
- **Cleanup:**
  - The unreachable `ActivityDialog` and unused state are removed.
  - The pairing name no longer defaults to "Dots", and copying a still-valid pairing code works offline.
  - Settings' "Disconnect gateway" uses the same audited owner pause as Home.
  - The mock Appearance preview panel is removed.
  - Durations and dates use readable, locale-aware formats.

Physical, same signer: `ControlCenterDeviceTest` 1/1, `ProposalDeviceTest` 4/4, `AgentRenameDeviceTest` 3/3 and `ManualAutomationDeviceTest` 2/2 pass. A cover-screen capture in light mode shows dark status icons on the app background. Instrumentation disconnected the shared-screen Accessibility service, which is documented Android behavior; the owner restores it in Android settings.

## Duplication and efficiency pass

Applied, behavior-preserving:
- **Gateway:** compiled tool schemas are cached per device and catalog revision. A new revision is written on every hello. Stateless MCP previously re-fetched and recompiled up to 128 JSON schemas on every request. `AGENTS_JSON` is parsed once per isolate.
- **Phone hello:** bursts of `refreshCatalog()` calls (permission, backend, accessibility and Termux callbacks) are debounced to one send per 250 ms. An unchanged hello is not resent on the same socket.
- **Descriptors:** the Headless, Commute route, Clock, Messages and bridge-maintenance descriptors are memoized per backend (`backendDescriptor`) instead of rebuilding their schemas on every engine read.
- **SQLite:** a shared `writeTransaction {}` helper replaces 14 hand-written begin/commit/end blocks in owner grant mutators and the Termux/Clock journals. Sites with early commit-and-return keep their explicit form.
- **Small fixes:**
  - `ClockJournal` page and operation listing use one query instead of one per row.
  - The Maps node-bounds regex is compiled once.
  - The engine computes each action hash once.
  - One `authIfEnabling` helper replaces five copies of the authenticate-only-when-expanding pattern.

Deferred, with reasons:
- **Shared capability builder.** About 10 local `op`/`register` builders and 20 standalone objects remain. The saving is about 4%, and every converted site would need re-verification on the device. The mutation-boundary opt-in is already explicit per builder. Convert opportunistically when touching each file.
- **Shared UI action and request-follow holders.** The owner-request follow pattern repeats in Recovery, Private workspaces, Termux and Diagnostics. A `rememberOwnerRequest` holder is worth doing alongside a dedicated UI test.
- **Android package-name validation.** Its variants differ: single-segment names and digit-leading segments are accepted at some sites and not others. Unifying them changes what is accepted, so it needs a deliberate decision.
- **A shared Termux/Clock journal base.** The two have different orphan and phase semantics; only the transaction helper is shared.

## Batch 4: documentation and CI

- **Docs consolidated:**
  - [status](status.md) is the single current status page.
  - Passes 6–21, the October 26 audit and the superseded `project-status.md`, `next-steps.md` and `build-plan.md` moved unchanged to [history](history/README.md).
  - All 252 internal links were rewritten and checked.
  - Stale "current version" preambles (code 21–26) and the paragraph copied into seven docs were replaced by a pointer to status.
  - The README index is regrouped (start here, reference, engineering record).
  - README and DISCLAIMER describe both privileged backends and use the actual UI labels.
  - SKILL.md lists the typed tools (`messages_recent`, Clock, Termux, Codex, headless, requests, recovery) and the new stop-and-report errors.
  - Gateway deployment explains replacing the committed KV ID and origin before the first deploy.
- **Build:** `core` no longer requests a JDK 17 toolchain with no resolver. It compiles on the Gradle JDK (21) and emits Java 17 bytecode, so a machine with only JDK 21 builds it.
- **CI:**
  - Actions are pinned to release commit SHAs.
  - Workflows get concurrency groups and job timeouts.
  - `py_compile` covers every tracked Python file, and shellcheck lints the Termux launcher shipped in the APK.
  - The hygiene check also rejects `.p12`, `.pem`, `.key`, `signing.properties`, `local.properties` and `.dev.vars`.
- **Tools:**
  - `start_builtin.py` selects `base.apk` among split APKs, reports a missing UID clearly, and waits for the old daemon to exit before starting a new one.
  - Device-testing docs keep the Inbox harness token in a private directory and document `termux_recovery.py`.

## Remaining lower-priority audit fixes

- **Commute alert text:** the evening text now follows the owner's `notifyWinter` and `notifyCold` choices, and both texts are combined when both apply.
- **Approval notifications:** these are tagged by the full request ID instead of `requestId.hashCode()`, so an agent-chosen ID cannot collide with and replace (or cancel) another agent's pending approval. The title shows the agent's name. Notifications posted before this update are still cancelled.
- **Schema bounds:** `minimum`/`maximum` are compared exactly as decimals, so 2^53+1 no longer passes a 2^53 maximum.
- **`notifications.dismiss`:** checks the key exists before acting, and refuses SIConnect's own notifications, other users or work profiles, and non-clearable notifications, with no side effects. It now reports whether removal was observed.
- **Maps unsupported-control errors** no longer copy on-screen text (addresses, place names) into stored request errors.

Not changed: the risk class of `ui.scroll`/`ui.back`/`ui.home` (LOW_IMPACT, while `headless.scroll` is HIGH_IMPACT). Raising it would add approval prompts for existing agents, so it is an owner policy decision.

Verification: core 91, app unit 151, lint 0 errors. Physical: `RuntimeDeviceTest` 13, `NotificationActionDeviceTest` 1, `FullAccessPendingContinuationDeviceTest` 1.
