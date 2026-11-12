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
