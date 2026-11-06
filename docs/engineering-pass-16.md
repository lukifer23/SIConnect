# Engineering pass 16 — execution retention and current-state review

October 26, 2026. Android **0.6.0-dev.10 / code 18**, SQLite **11**, **89** phone capabilities plus the gateway status tool. The phone began this pass at code 17/schema 10. Existing uncommitted Maps control and connection-restoration work was preserved. This pass adds no tool names or agent grants.

## Storage contract

- Settings → Execution history offers full-payload retention or 7/30/90/365-day expiry. The default is disabled, including after migration. Enabling/changing expiry opens an explicit review and uses existing owner authentication. Disabling stops future passes and cannot restore expired payloads.
- A separate runtime coroutine performs a pass every fifteen minutes while the process is running. The owner can request one batch. Each pass considers at most 25 workflow records, 25 request records and 25 automation records; individual payloads are read one at a time. This bounds row counts, not absolute execution time or the size of one existing record.
- Finished, sufficiently old workflows retain their outcome, step identities, error and immutable operation fingerprint. Definition/input/output payloads are removed. Step-result duplicate rows are removed. Matching workflow retries return the compact original; changed definition/input/agent/device/origin conflicts.
- Request compaction retains original state, original failure details, action hash, operation fingerprint, principal, backend selection, timing and idempotency identity. `payloadExpired` marks the missing payload. Active and unknown requests, parent/child references still needed by full workflows, unresolved Inbox dispatch and unreconciled reminder deliveries are protected.
- Completed automation snapshots lose their large JSON bodies, retaining run state, timing and unique scheduled-slot identity. A late alarm or reconciliation cannot restore the expired snapshot or rerun the slot.
- Compact request identities and one-time notification claims have no automatic expiry. Deleting them by age would make sufficiently late retries eligible for replay. Notification retries with expired results require original-outcome inspection; they never repost. Reminder reconciliation checks the retained action hash.
- Activity and `requests.recent` include compact outcomes using metadata projections and stable keyset pagination. `requests.get` and `requests.output` identify expired payloads. Unknown outcomes retain their full evidence.

This policy covers execution payloads. It does not delete saved agents, reminders, automations, skills, selected roots, Inbox proposals or the existing independently managed event/health ledgers. It is not a hard cap on metadata or uncertain-outcome growth. SQLite may retain freed pages or automatically shrink its file; the UI reports allocation and reusable space separately.

## Large-record retrieval and migrations

Request and workflow persistence previously selected one full JSON value into a cursor row. An outbound chunking capability cannot help if that row is already too large for Android's CursorWindow. The shared reader now retrieves 64 KiB byte segments in one SQL statement/snapshot and reassembles UTF-8 before decoding. This preserves supplementary Unicode across segment boundaries. History selects only small metadata. Scheduler result comparisons execute inside SQLite without fetching a potentially large prior result.

Schema 11 adds retention projections/indexes and compact-history metadata. Legacy workflows get a fresh age interval at migration; the upgrade itself does not clean them. Existing compact request records gain history projections and the explicit expiry marker. Tests reconstruct real earlier table shapes rather than just labeling a current table as an older schema.

## Verification

Host checks passed: **201 tests** — 71 core, 100 Android JVM, two embedded ADB, 13 Python and 15 gateway protocol cases. Debug APK, instrumentation APK and minified unsigned release build pass. Gateway typecheck and dry-run bundle pass. Debug lint has **zero errors and 75 warnings**. The five new host regressions cover compact workflow identity, notification claims and reminder reconciliation; the inherited Maps work also accounts for differences from older documented totals.

The final focused physical batch passed **20 tests** with no skips/failures. Coverage includes disabled/default and bounded cleanup, protected active/unknown/link evidence, compact-history pagination/filtering, incomplete legacy links, large Unicode/nested workflow reads, engine retry/conflict behavior, 799-day-old retry identity, migration, late alarm delivery, storage reclamation, previous schema 6/7/8/9 regressions, durable native execution and authenticated output chunks. The real owner UI opened the expiry review, preserved it across Activity recreation and cancelled without changing the policy.

The synthetic growth fixture inserted 48 requests and 12 workflows with 64 KiB results and duplicate automation snapshots. Allocation fell from **6,619,136 to 323,584 bytes** (6.31 to 0.31 MiB) while retaining compact identities/outcomes. `auto_vacuum=1` explains why the file shrank instead of accumulating freelist pages. This is a disposable-fixture measurement, not a production workload or battery study.

The initial growth assertion incorrectly required a larger freelist; the corrected test verifies fewer live pages whether SQLite shrinks the file or reuses pages. Initial UI harness runs missed Settings until interactive-window retrieval was explicitly enabled, then exposed a fast/ambiguous scroll action. The final harness uses all-display discovery, the largest scroll container, a vertical action and a settling wait. Focused UI and the corrected combined batch passed; initial failures remain private evidence, not passing acceptance or a full Fold/lifecycle claim.

## Installed runtime and preservation

The update APK's certificate matched the actual installed code-17 APK before installation. Code 18 was installed in place, with no main-app uninstall/data clear, credential rotation, permission broadening or gateway deployment. The final private SQLite comparison verified schema 11/integrity and the original **five agents/grants/credential hashes, three reminders, two automations and one selected root**. Agent last-seen timestamps are excluded from the protected-row digest; the credential and grant fields are included. Production request tombstones and compact workflow counts remain zero, and the retention preference remains disabled.

After removing the temporary instrumentation package, normal MainActivity/service startup and the authorized bundled-backend restart restored the existing connection. Seven fresh hosted MCP reads passed (status, battery, memory, storage, network, thermal and actual shell identity), advertising **90** tools. Shell execution used `ADB_SERVICE` and actual **UID 2000**. An eighth hosted runtime read verified **code 18, database integrity OK, gateway ONLINE and privileged availability**. Only the main SIConnect package remains in the primary Android user's installation. No client re-pairing or grant changes were needed. Live provider behavior beyond these reads remains separate acceptance work.

## Remaining limits

- Real owner authentication success/cancellation/interruption is separate from opening and cancelling the local expiry review. Tests never simulate biometric success or enable production retention.
- Controlled network/Doze/reboot and in-flight provider/Binder interruption acceptance, Fold/large-font coverage, Calendar consent/dedicated-calendar writes and long resource/availability studies remain open.
- The inherited Maps future-time work is carried forward and host-built; this pass does not claim a new physical departure/arrival acceptance run.
- Release signing/distribution remain open. The installed debug update uses the existing compatible development signer; the minified host release is unsigned.

See [the scope/gap review](build-plan.md), [project status](project-status.md) and [next steps](next-steps.md).
