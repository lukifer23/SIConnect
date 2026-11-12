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
