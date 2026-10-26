# Engineering pass 13 — October 26, 2026

Android source and installed build: **0.6.0-dev.5 / versionCode 13**, SQLite **10**, **85** tools. Android implementation is commit `239cf12`; this follow-up finishes gateway retry identity and records device verification, documentation and workspace cleanup. The existing hosted gateway has not been redeployed.

## Completed work

The [source audit](source-audit-2026-10-26.md) records the implementation already committed for execution cancellation/deadlines, workflow validation and long IDs, bounded continuation workers, reminder ordering/cancellation, permission preservation, shared UI feedback, UTF-8 reads, signing metadata and development dependency updates. Those changes add 38 host regressions. Android debug, minified unsigned release and instrumentation APKs built. Release isolation passed; lint has zero errors and 71 warnings.

The gateway now advertises optional `_siconnectOperationKey` in MCP tool arguments. It strips that field before device invocation and hashes device/agent/key into a stable request/idempotency identity. The key is independent of credential refresh and scoped to the authenticated agent/device. An identical keyed retry resolves to the original execution; changing capability or arguments conflicts. Calls without a key retain fresh identities. Execution expiry is frozen by the phone at first admission; a separate transport deadline bounds each attempt. A concurrent duplicate returns `REQUEST_IN_FLIGHT` with original-request lookup guidance. No automatic mutation retry queue was added.

Five new protocol tests cover both supported client generations, invalid keys, in-flight identity and lost-response recovery. The peer is a test fixture, not the physical phone. Hosted deployment/catalog refresh and a real phone keyed-mutation loss test remain pending. See [gateway usage](gateway-deployment.md#retry-identity-and-original-result-recovery).

## Verification

| Check | Result |
| --- | --- |
| Core JVM | 64 passed, source-audit build |
| Android JVM | 89 passed, source-audit build |
| Embedded ADB JVM | Two passed, source-audit build |
| Python tooling | 13 passed, source-audit build |
| Gateway protocol | 15 passed after retry implementation |
| Gateway typecheck / dry-run bundle | Passed after retry implementation |
| Full npm audit | Zero known vulnerabilities |
| Android physical batch | 21 ran: 20 passed, one charging-precondition failure |
| Live SQLite integrity | `quick_check` passed, schema 10 |
| Enrollment/configuration continuity | All five agents/grants, three reminders, two automations and selected document-root row matched before/after |

The host total is **183 passing tests**. The previous Android-source commit's [hosted CI run](https://github.com/lukifer23/SIConnect/actions/runs/40382643188) passed. Publication of this follow-up triggers a separate [CI run on main](https://github.com/lukifer23/SIConnect/actions/workflows/ci.yml); use its commit-specific result for this gateway/documentation checkpoint.

## Device procedure and evidence limits

USB and wireless ADB both exposed the same Galaxy Z Fold 7/API 37. The private harness explicitly selected USB. The installed debug certificate matched the local candidate. App and test APKs were installed with `install -r`; no uninstall, data clear, re-enrollment, credential rotation or permission broadening was used. The existing bundled backend was restored with the authorized startup script.

The batch selected RetentionDeviceTest; five RuntimeDeviceTest methods (native durable execution/idempotency, interrupted reminder claim, exact reminder delivery, managed file round trip and actual bundled shell); SchedulerDeviceTest; ManualAutomationDeviceTest; UiLeaseDeviceTest; and HealthAlertDeviceTest. It did not exercise every instrumented method or the full owner UI.

`actualNativeReadingsUseProductionCooldownWithExplicitTestThreshold` failed its explicit unplugged-phone assertion because the USB-connected phone was charging. Its real native readings succeeded before that assertion. No health-threshold delivery result is claimed for this case, and no charging state was spoofed. The remaining 20 tests passed. The initial private harness summary expected per-test instrumentation status lines, but this runner returned compact JUnit output; the actual final `Tests run: 21, Failures: 1` was inspected directly. The nonzero result is retained in this report rather than described as a full green suite.

Final private database comparison found no changed/missing original protected records. Database counts were 1,182 requests / 5,480 events before and 1,227 / 5,687 after; these are measured activity counts, not a controlled storage benchmark. The database file grew from 14,397,440 to 14,712,832 bytes; changing WAL/checkpoint state prevents treating file-size deltas as a clean workload comparison. Native test records/audit history were retained on the phone.

MainActivity was relaunched after instrumentation. Final inspection found the app, foreground connection service and bundled privileged daemon running; the latest recorded connection event was `gateway.connected`. This is not a fresh third-party-client send or a guarantee of uninterrupted connectivity. No new special-access restoration, ordinary process death, network transition, reboot, Calendar write, SAF write or biometric acceptance/cancellation was performed in this pass. Earlier genuine owner Inbox/SAF evidence remains in [pass 12](engineering-pass-12.md).

## Handoff and cleanup

[Project status](project-status.md) and [next steps](next-steps.md) now provide a single current checkpoint/queue. Historical evidence stays in dated engineering reports. Setup, installation, device-testing, architecture, capability coverage and retry guidance link to the corresponding current state.

See [repository hygiene](repository-hygiene.md) for the duplicate/artifact inventory, cleanup boundaries, retained recovery APK, process shutdown and rebuild instructions. Public documentation contains sanitized aggregate results only. Raw device/database captures, local credentials, serials, addresses, tokens and signing material are excluded from the Git tree.
