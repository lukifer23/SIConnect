# Project status

Updated October 20, 2026. This replaces the initial core-only status.

| Subsystem | Implemented | Evidence / limits |
| --- | --- | --- |
| Core authorization | Current grant/scope/expiry/revocation, backend and kill-switch revalidation after approval | Host tests plus real device revocation test |
| Idempotency | Scoped immutable-operation fingerprint; conflicts explicit; CAS execution ownership | Concurrent host submissions; actual SQLite test |
| Durable skills | Persisted cursor, stable child IDs, pending approvals, partial/unknown outcomes, recovery | Four-step approval continuation on host; real three-step native skill on phone |
| Android runtime | Compose UI, SQLiteOpenHelper v3, Keystore secrets, permissions, lifecycle, native registry | Debug APK built, installed and launched repeatedly on Fold 7 |
| Privileged runtime | Bundled shell/root-only Binder service; shared bounded subprocess executor; optional explicit Shizuku backend | Bundled backend physically returned UID 2000, exit 0; no companion needed for this route |
| Approvals | Persisted requests, notifications, exact owner review, deny and once/standing/broad permission | Instrumented deny/approve/revoke; actual UI approved browser and Clock operations |
| Persistent permission | Exact argument/version/backend grants and explicit broad shell; owner-only SQLite changes; revoke controls | Phone UI saved broad grant; process killed and automatically restarted; new unattended shell succeeded |
| Gateway | Worker, Durable Object, official MCP v2 handler, OAuth plus private bearer auth, device enrollment, acknowledged outbound socket | Deployed in owner's personal account; real Fold connected; SDK v1/v2 local protocol tests |
| Grok Bot | Saved secure bearer credential; cloud-hosted MCP relay | Actual connector battery call correlated with phone record; browser and alarm operations executed after UI approval |
| Result tracking | requests.get / requests.cancel scoped to authenticated requesting agent | Real hosted MCP lookup succeeded; catalog now advertises 31 tools |
| Scheduling | Versioned reminder records, revision CAS, exact AlarmManager and deferrable WorkManager, same-ID update, reboot restoration | Real exact alarm delivered through policy/engine/notification in instrumentation; longer lifecycle testing pending |
| Weather | Real Open-Meteo forecast/cache/normalization | Live forecast fetched; existing next-morning reminder updated to exact timing without creating a duplicate |
| Hermes | Global HTTP MCP connection and reusable device skill | Real phone email confirmed by owner; deterministic native MCP runtime installed official app and verified package/version/installer; full LLM run interrupted after email, not reported as full success |
| Dots/ChatGPT | OAuth, phone-created pairing, separate authenticated agent, real native and shell calls | Actual calls correlated with SQLite; app-install test denied by local scope; correct agent’s permission saved by owner and verified in SQLite; subsequent official-app install and final bounded verification succeeded |
| Owner management UI | Bot cards, durable Apps permission, revocation, exact blocked-agent review, disambiguated duplicate labels | APK built/linted/installed; physical hierarchy shows the correct Dots permission card; permission review stayed open for 20 seconds; owner saved the correct grant; longer/configuration-change checks remain pending |

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
- Calendar/files families, disposable uninstall, extended background/reboot/network tests and long-duration resource profiling remain outstanding.
- Request/result data can contain sensitive content. Do not export raw device evidence to this public repository.

Ordinary process-death check: the owner-enabled app process was killed with its own UID (not force-stop). Android restarted the service, the Binder backend reattached, and a new arbitrary shell request succeeded without approval in 1.65 seconds. This is a single recovery measurement, not long-duration lifecycle evidence.

## Current checkpoint

The UI-checkpoint build (`:app:assembleDebug :app:lintDebug`) passed, then its APK was installed with `adb install -r`, bundled authority restarted and MainActivity launched. The physical UI showed the exact denied Dots connection's permission card and CONNECTED status. No permission was granted by the diagnostic check. A separate authenticated `/connection` check reported online, protocol 2 and 31 capabilities. No pending/executing request was present immediately before installation.

Current host checks: core 30 tests (up-to-date on the latest invocation); gateway six tests passed with zero failures/skips. Android JVM unit task has no sources. Five physical instrumentation tests passed earlier; those are not a fresh physical validation of every subsequent UI change. Lint passed with warnings, including dependency/style suggestions; it was not disabled wholesale.

The longer approval envelope was subsequently deployed to the previously verified personal gateway account. An official MCP v2 device-status call succeeded in 258 ms with an approximately fifteen-minute stored deadline. The final source also checks persistence when clearing a consumed invitation; it built/linted successfully after the installed UI checkpoint, but that last guard has not been separately exercised on the phone.

## Activity and resource checkpoint

Home now queries a bounded metadata projection rather than decoding complete result JSON on every request update. Full output is loaded on selection. A compact Activity dialog exposes bot/status filters and newest/oldest sorting for the latest 100 records; blocked request details display the policy reason and link to that exact bot's permissions. This keeps the primary navigation small and preserves detailed management.

The activity APK and instrumentation APK built, lint passed, both installed and the bundled authority restarted. The updated native SQLite/idempotency instrumentation test passed on the physical phone (one test, zero failures). The earlier five-test suite was not rerun in full during owner review because it deliberately creates and resolves brief test approval notifications. Physical UI checks opened Activity, selected Needs attention, changed sort direction and listed real bot identities, then returned Home without changing any permission or approval.

A short connected foreground resource baseline measured 0.24 CPU-seconds across 60.487 seconds and app PSS 127,060–127,960 KiB. Daemon CPU counters did not increase. This is a short mixed-observation baseline, not a battery result or an optimization comparison. The private-output process profiling harness is in `tools/profile_device.py`.

The exact Dots permission screen remained open through a 20-second passive check. The owner then saved Apps and always-allow device control for the requesting OAuth principal; SQLite and its owner grant event independently confirmed the change. No diagnostic script granted permission. A new client invocation is required because prior denied records remain terminal.

## Dots install completed

After the owner corrected the exact agent grant, Dots completed the requested official-app installation through the existing OAuth MCP connection. Its initial package check, Play Store launch, single install tap and final bounded verification were correlated with persisted requests from that OAuth principal. Independent user-0 package inspection confirmed the expected package, version code and Google Play installer. The final verification returned exit 0 without output truncation. No application login was attempted. Dots' separate email test remains outstanding; Hermes' previously confirmed email is not being resent.

One issue surfaced during verification: a full privileged package dump can exceed the bounded output limit before reaching the version/user section. Truncation is reported. Add a typed bounded package-inspection result while preserving explicit full-dump access; do not infer installed/version state from an incomplete dump or an exit code alone.
