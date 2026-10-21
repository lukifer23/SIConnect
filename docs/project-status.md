# Project status

Updated October 20, 2026. This replaces the initial core-only status.

| Subsystem | Implemented | Evidence / limits |
| --- | --- | --- |
| Core authorization | Current grant/scope/expiry/revocation, backend and kill-switch revalidation after approval | Host tests plus real device revocation test |
| Idempotency | Scoped immutable-operation fingerprint; conflicts explicit; CAS execution ownership | Concurrent host submissions; actual SQLite test |
| Durable skills | Persisted cursor, stable child IDs, pending approvals, partial/unknown outcomes, recovery | Four-step approval continuation on host; real three-step native skill on phone |
| Android runtime | Compose UI, SQLiteOpenHelper v4, Keystore secrets, permissions, lifecycle, native registry | Debug APK built, installed and launched repeatedly on Fold 7 |
| Privileged runtime | Bundled shell/root-only Binder service; shared bounded subprocess executor; optional explicit Shizuku backend | Bundled backend physically returned UID 2000, exit 0; no companion needed for this route |
| Approvals | Persisted requests, notifications, exact owner review, deny and once/standing/broad permission | Instrumented deny/approve/revoke; actual UI approved browser and Clock operations |
| Persistent permission | Exact argument/version/backend grants and explicit broad shell; owner-only SQLite changes; revoke controls | Phone UI saved broad grant; process killed and automatically restarted; new unattended shell succeeded |
| Gateway | Worker, Durable Object, official MCP v2 handler, OAuth plus private bearer auth, device enrollment, acknowledged outbound socket | Deployed in owner's personal account; real Fold connected; SDK v1/v2 local protocol tests |
| Grok Bot | Saved secure bearer credential; cloud-hosted MCP relay | Actual connector battery call correlated with phone record; browser and alarm operations executed after UI approval |
| Result tracking | requests.get / requests.cancel scoped to authenticated requesting agent | Real hosted MCP lookup and interrupted-result recovery succeeded; current installed catalog has 43 tools |
| Scheduling | Versioned reminder records, revision CAS, exact AlarmManager and deferrable WorkManager, same-ID update, reboot restoration | Real exact alarm delivered through policy/engine/notification in instrumentation; longer lifecycle testing pending |
| Weather | Real Open-Meteo forecast/cache/normalization | Live forecast fetched; existing next-morning reminder updated to exact timing without creating a duplicate |
| Hermes | Global HTTP MCP connection and reusable device skill | Real phone email confirmed by owner; deterministic native MCP runtime installed official app and verified package/version/installer; full LLM run interrupted after email, not reported as full success |
| Dots/ChatGPT | OAuth, phone-created pairing, separate authenticated agent, real native and shell calls | Actual calls correlated with SQLite; app-install test denied by local scope; correct agent’s permission saved by owner and verified in SQLite; subsequent official-app install and final bounded verification succeeded |
| Owner management UI | Bot cards, durable Apps permission, revocation, exact blocked-agent review, disambiguated duplicate labels | APK built/linted/installed; physical hierarchy shows the correct Dots permission card; permission review stayed open for 20 seconds; owner saved the correct grant; longer/configuration-change checks remain pending |

## Engineering pass 3 (in progress)

Starting source: `abfefc5`, preserving the reviewed `b474f3d` work and the existing three remote principals. The annotated recovery tag is `siconnect-working-multiagent-2026-10-20`. Fresh baseline core/Android builds and gateway checks passed before modification. Live credentials and the database were preserved.

Implemented this increment:

- New OAuth bots always get fresh identities/credentials, even with duplicate labels. Resume/renew uses the persisted invitation ID. Reconnection is explicitly selected from a particular bot card; it preserves only that bot's grant. No existing identity migration or automatic credential rotation occurs.
- `apps.inspect_detailed` uses bounded PackageManager queries for primary-user installation, version, enabled state, timestamps and installer metadata. A second query verifies state; source-permission errors and concurrent changes are explicit. Raw privileged inspection/dumps and general shell remain available.
- Shell execution uses a deadline-bound ownership handshake before user code starts, an owned session supervisor, atomic capacity, cancellation-before-start handling and group cleanup. Successful commands avoid the previous unconditional pair of 100 ms sleeps. Legacy text/base64 fields remain compatible; maximum transport-envelope stress remains outstanding.
- Exact reminder receivers persist a WorkManager reconciliation watchdog before quick delivery. Interrupted claims reuse their original request ID; terminal/unknown results are reconciled rather than reposted blindly. Worker failure is no longer reported as successful delivery.
- Gateway socket generations, hello readiness, per-session in-flight ownership, stale-session rejection and same-request recovery hints are implemented and pass local Worker/SDK tests. Deployment verification is recorded separately below; source tests are not hosted evidence.
- `device.runtime` exposes on-demand process CPU/PSS, UID network counters, database/WAL sizes and connection/backend state. A real sparse hosted MCP collector is available in `gateway/test/soak-client.mjs`; completion of a 24-hour interval is not yet established.
- Corrupt encrypted secrets now fail explicitly instead of being treated as absent and silently regenerated. Keystore key creation is synchronized. An obsolete bundled Binder death callback cannot clear a newly attached service.

Validation so far: APK/lint/instrumentation builds passed and in-place upgrades succeeded. Three focused physical tests passed: structured PackageManager inspection, actual shell supervisor behavior (nonzero exit, stderr/stdin/environment, bounded output, binary bytes, timeout, early cancellation, parent/child cleanup), and interrupted-reminder reconciliation without a second post. An earlier two-test physical run also verified package metadata and actual UID 2000 identity. Seven local gateway integration tests pass, including official SDK v1/v2, OAuth/refresh/pairing and authenticated socket replacement. Real hosted diagnostics passed after the first upgrade (seven calls; 32 tools at that point). Android instrumentation ends the target process: the app must be relaunched and its socket acknowledged before hosted checks; two prematurely started probes correctly returned DEVICE_OFFLINE.

The focused live pairing test passed (one case, zero failures) for duplicate-label identity isolation, expired invitation renewal and explicitly selected reconnect; only disposable test principals were created/deleted. An initial attempt skipped while the gateway had not yet reconnected; the final test explicitly awaited ONLINE. Existing remote principals remained untouched.

Outstanding audit work includes full output-envelope/Binder stress, scheduler slot recovery and wall-clock recurrence, time-window weather calculations, calendar/files, UI coordination and older-history pagination. Existing provider-specific successes remain valid historical evidence; they are not represented as newly rerun provider tests.

## Pass 3 shipping increment

The existing personal gateway was updated in place to version `3103541b-0f0f-4acb-8e1c-c86989f19efe`. Endpoint, OAuth resources, agent credentials and enrollment identity were retained. Commit `95d7348` was pushed to main and its CI passed. Fresh official-client calls and Hermes's configured native MCP runtime subsequently succeeded through that deployment.

The next Android increment adds SQLite v4 indexes and keyset Activity pagination, indexed current-credential lookup, transactional request/audit writes, bounded app-owned file operations, explicit weather windows, interaction metadata and live capability/scope diagnostics. The APK version is 0.3.0 (code 2), same application ID. Connection recovery on package replacement and boot explicitly starts the previously owner-enabled service; Android rejection is logged, not hidden. Backend/permission changes refresh discovery. Boot execution is implemented but a physical reboot is not claimed.

Actual locked-phone Hermes workflow: original read result retrieved after a real socket replacement; live runtime confirmed noninteractive screen, keyguard locked and device locked; package inspection succeeded; one unique disposable shell file was created, read/hashed and removed; an exact SIConnect reminder was scheduled and reached SUCCEEDED. Android's notification service independently confirmed the test notification. Final runtime still reported screen off/device locked. No real message, email or personal app installation was repeated. This used Hermes's real configured MCP tool runtime; it is not represented as a fresh autonomous LLM conversation test or a new Dots/Grok test.

A live public-test-location forecast returned a COMPLETE four-hour explicit-window summary. The no-location call correctly failed LOCATION_REQUIRED; device defaults were not invented or changed. Three JVM fixture tests validate window selection, DST duration and missing-data semantics. File operations and SQLite pagination passed on the actual phone; an initial listing defect caused by Android's canonical path alias was reproduced and corrected. The first physical attempt had one failure, and the final three-case rerun passed. The Activity page-one controls were observed; a page-two UI attempt was stopped when the shared screen changed to Clock, so full folded/unfolded navigation is not claimed.

`gateway/test/soak-client.mjs` is running a genuine 24-hour sparse read-only collection with private output and successful initial samples. Development/install activity is mixed into this interval and must be annotated. It is neither a finished soak nor a battery result.

Calendar tools, SAF/MediaStore file access, phone-only privileged bootstrap, durable gateway offline execution, shared-screen leases, generic scheduler wall-clock recurrence and the full requested provider/lifecycle matrix remain unfinished. The source review fixed concrete cross-system defects; a claim of an exhaustive completed repository audit would be premature.

## Physical evidence

Five production-backed Android instrumentation tests passed, including actual precise reminder delivery: native execution/persistence/idempotency, package query and skill, bundled shell identity, and approval deny/approve/revoke. An additional real-device gateway setup test passed. Official MCP SDK v2 invoked seven real phone tools through the hosted gateway. Grok Bot then independently called device status, shell identity and battery; matching REMOTE request records were read from the phone's database-backed endpoint.

Grok Bot submitted the owner's requested browser and Clock commands. Both waited for local approval, then completed with exit 0. Physical Chrome showed apple.com; Samsung Clock showed the named test alarm at the requested 6:08 PM. AlarmManager independently returned the same trigger time. This validates alarm creation, not an unattended scheduler guarantee. Private screenshots and outputs stay outside Git.

## Remaining limits

- Two explicitly authorized conversations were exercised through Grok Bot. Exact outgoing messages and Delivered state were verified on the Fold. A date-bounded message review also ran. Private content stays outside Git.
- OAuth onboarding completed after an expired consent session was restarted. Both a ChatGPT work conversation and the actual Dots conversation executed native and privileged read-only calls. Initial browser failures remain documented as resolved onboarding failures, not a continuing connection blocker.
- Dots app management was blocked by the active agent grant. Two same-name connections caused the wrong grant to be changed. The installed update linked the denial to the exact bot; the owner then enabled Apps and broad device control for that identity. Both choices were verified in SQLite. A denied request will not execute retroactively; the new client install and bounded final verification succeeded.
- Approval/permission review previously closed too readily, and automated setup taps interfered with owner review. Automated phone approval taps were stopped. The installed update requires explicit Close/Done, preserves an open Home action review across activity recreation, and retains a pending notification until resolution/expiry. Extended owner interaction and configuration-change validation remain pending.
- Full device control requires an owner-authorized ADB bootstrap after phone reboot. The Mac is not a runtime server after bootstrap. Phone-only wireless pairing is not implemented.
- Shizuku integration is optional; its reboot/lifecycle behavior has not been exhaustively tested.
- At the initial checkpoint calendar/files and expanded lifecycle coverage were outstanding. Pass 3 implements managed files and starts real collection; calendar, SAF/MediaStore, disposable-package regression and extended reboot/network coverage remain unfinished.
- Request/result data can contain sensitive content. Do not export raw device evidence to this public repository.

Ordinary process-death check: the owner-enabled app process was killed with its own UID (not force-stop). Android restarted the service, the Binder backend reattached, and a new arbitrary shell request succeeded without approval in 1.65 seconds. This is a single recovery measurement, not long-duration lifecycle evidence.

## Current checkpoint

The UI-checkpoint build (`:app:assembleDebug :app:lintDebug`) passed, then its APK was installed with `adb install -r`, bundled authority restarted and MainActivity launched. The physical UI showed the exact denied Dots connection's permission card and CONNECTED status. No permission was granted by the diagnostic check. A separate authenticated `/connection` check reported online, protocol 2 and 31 capabilities. No pending/executing request was present immediately before installation.

Earlier UI-checkpoint host checks: core 30 tests (then up-to-date); gateway six tests passed with zero failures/skips. Android JVM unit task has no sources. Five physical instrumentation tests passed earlier; those are not a fresh physical validation of every subsequent UI change. Lint passed with warnings, including dependency/style suggestions; it was not disabled wholesale.

The longer approval envelope was subsequently deployed to the previously verified personal gateway account. An official MCP v2 device-status call succeeded in 258 ms with an approximately fifteen-minute stored deadline. The final source also checks persistence when clearing a consumed invitation; it built/linted successfully after the installed UI checkpoint, but that last guard has not been separately exercised on the phone.

## Activity and resource checkpoint

Home now queries a bounded metadata projection rather than decoding complete result JSON on every request update. Full output is loaded on selection. A compact Activity dialog exposes bot/status filters and newest/oldest sorting for the latest 100 records; blocked request details display the policy reason and link to that exact bot's permissions. This keeps the primary navigation small and preserves detailed management.

The activity APK and instrumentation APK built, lint passed, both installed and the bundled authority restarted. The updated native SQLite/idempotency instrumentation test passed on the physical phone (one test, zero failures). The earlier five-test suite was not rerun in full during owner review because it deliberately creates and resolves brief test approval notifications. Physical UI checks opened Activity, selected Needs attention, changed sort direction and listed real bot identities, then returned Home without changing any permission or approval.

A short connected foreground resource baseline measured 0.24 CPU-seconds across 60.487 seconds and app PSS 127,060–127,960 KiB. Daemon CPU counters did not increase. This is a short mixed-observation baseline, not a battery result or an optimization comparison. The private-output process profiling harness is in `tools/profile_device.py`.

The exact Dots permission screen remained open through a 20-second passive check. The owner then saved Apps and always-allow device control for the requesting OAuth principal; SQLite and its owner grant event independently confirmed the change. No diagnostic script granted permission. A new client invocation is required because prior denied records remain terminal.

## Dots install completed

After the owner corrected the exact agent grant, Dots completed the requested official-app installation through the existing OAuth MCP connection. Its initial package check, Play Store launch, single install tap and final bounded verification were correlated with persisted requests from that OAuth principal. Independent user-0 package inspection confirmed the expected package, version code and Google Play installer. The final verification returned exit 0 without output truncation. No application login was attempted. Dots’ separate self-addressed email test also completed; its send, Sent verification and header/body verification matched durable records, and an independent Gmail UI read confirmed the test subject/body. Hermes’ previously confirmed email was not resent.

One issue surfaced during verification: a full privileged package dump can exceed the bounded output limit before reaching the version/user section. Truncation is reported. Add a typed bounded package-inspection result while preserving explicit full-dump access; do not infer installed/version state from an incomplete dump or an exit code alone.

Dots also completed the owner's subsequent uninstall request. It waited for real on-phone approval, executed `apps.uninstall`, then verified absence. An independent user-0 package query confirmed removal. Its pending record had the full 600,000 ms approval window. The email test ran afterward, using the requested account and checking for an existing sent test before sending once. All personal account data and raw Gmail captures remain outside Git.

## Review lifecycle completion and Pass 3 boundary

The shared owner review now uses a bounded dialog layout with a separately measured action area; an actual screenshot had exposed a clipped Close control in the previous layout. The installed replacement has a fully visible 126-pixel Close touch target on the tested Fold configuration. Notification intent deliveries are consumed individually, so reopening the same stored action is permitted; a resolved/expired action can display its state without offering a new execution.

The earlier review-checkpoint targeted physical instrumentation passed both cases (2 passed, 0 failed, 0 skipped): native SQLite/idempotency/metadata projection, and real singleTop review open → Close → same-request reentry. Four earlier review probe runs timed out because framework text lookup did not find Compose virtual nodes despite the visible dialog. The final test traverses actual accessible window children; those earlier failures are not counted as passes. The test uses a completed native diagnostic record and creates no destructive action or approval notification. Both APKs built/installed; final lint passed; gateway status was online/protocol 2 with 31 tools. The owner-selected Dots Apps and broad-shell grant persisted across repeated in-place upgrades and process restarts.

Pass 3 begins from reviewed commit `b474f3d` plus these already verified, previously uncommitted UI/evidence updates. Preserve the live principals, credentials, database, gateway endpoint and scheduled reminders. New identity/package/shell/scheduling work must be tracked separately from the completed client action tests above.

## Final pass 3 checkpoint evidence

APK 0.3.0/code 2 was installed in place. Package-update connection recovery worked without launching MainActivity or changing the foreground activity. Fresh hosted official-client calls succeeded (seven tools); Hermes's configured runtime also verified five current tools, including live capability/scope metadata. Catalog count: 43. Native runtime reported ONLINE and the bundled privileged backend available. SQLite v4 quick_check passed; existing remote identities, grants and credential hashes matched the pre-upgrade snapshot, and the existing personal reminder was unchanged.

Final host checks passed: 30 core, three app JVM, seven gateway and four Python tests, zero final failures/skips. The final four-case physical storage/native suite passed; earlier focused physical shell, reminder reconciliation, pairing isolation and package checks also passed. Full untouched provider UX, calendar, disposable-package regression, reboot and long idle/network-switch coverage remain outstanding. The real soak remains running, not complete.

Storage cleanup removed 77.6 MiB of reproducible Android intermediates/temporary build output and Worker staging output. Current APKs/reports/dependencies/private evidence were retained. The disposable instrumentation package and one temporary device UI dump were removed; the production package, credentials, database/history and personal reminders were preserved.

Legacy Grok/Hermes private-bearer use still shares one principal. Independent per-client revocation requires deliberately pairing/migrating separate identities; no silent credential rotation or grant copying occurred. OAuth same-name connections remain distinct.
