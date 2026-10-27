# Project status

Updated October 26, 2026. Current Android source and installed debug build: **0.6.0-dev.8 / versionCode 16**, SQLite **10**, **89** phone capabilities. The deployed gateway also exposes `siconnect_connection` (**90** MCP tools total), providing a fresh acknowledgement without dispatching phone execution. See [pass 15](engineering-pass-15.md), [the source audit](source-audit-2026-10-26.md) and [next steps](next-steps.md). Existing principals, enrollment and owner data are retained. Development signing remains in use; this is not production-release acceptance.

| Area | Current state | Evidence and limits |
| --- | --- | --- |
| Execution and policy | Durable requests, immutable-operation deduplication, current-grant/deadline checks, explicit uncertain outcomes | 70 core tests; code-14 physical native execution/SQLite/idempotency test passed |
| Workflows and recovery | Prepared startup cursors, bounded continuation workers, stable long child IDs, validated workflow conditions/output | Host regressions pass; injected Android startup/storage-failure acceptance remains open |
| Reminders and scheduling | Serialized reminder admission/update/cancel, original-request cancellation, exact and deferrable dispatch | Physical exact delivery, interrupted-claim reconciliation, scheduler and manual-run cases passed; concurrent cancellation matrix remains open |
| Android owner UI | Shared feedback, reminder detail navigation, narrower shell permission reduction, adaptive management screens | Builds/lint pass; full Fold/font/rotation and genuine authentication cancellation remain open |
| Discovery and network recovery | Shared per-refresh provider probes, five-second UI metadata reuse with invalidation; validated-network callbacks wake reconnect backoff | Six core and four Android JVM regressions; real provider and Android network-callback cases passed; disruptive transition matrix remains open |
| Native resources | Managed files, selected SAF roots, Calendar Provider, typed diagnostics/package inspection | Code-13 managed-file round trip passed; selected SAF passed on code 12; dedicated Calendar CRUD remains open |
| Privileged execution | Existing bundled Binder backend; explicit optional Shizuku | Code-13 actual shell identity case passed after authorized bootstrap; no universal unattended reboot recovery claim |
| Gateway and clients | OAuth/PKCE/private bearer, acknowledged socket generations, original-result lookup; new optional retry key in source | 15 local SDK v1/v2 protocol tests pass; deployed retry identity and transport-error clarification; fresh Grok results are in pass 14 |
| Storage | Indexed history, compact request tombstones, bounded health-alert ledger | SQLite integrity passed; general request/workflow retention has no production owner flow |
| Security and release | Existing signing identity/enrollment retained; debug fixtures excluded from minified release | Host release/isolation checks passed; public signed distribution and owner-signed Actions workflow remain open |

Host verification: **193 passing tests** (70 core, 93 Android JVM, two embedded ADB, 13 Python, 15 gateway). Android debug/release and instrumentation APKs built; lint has no errors and 71 warnings. The host checks were rerun in this pass. Live SDK acceptance is separate from the 193-test count.

Code 14 passed seven focused physical tests, including actual document-provider discovery, native operation without the disposable companion, Android validated-network callbacks, native durability/idempotency, managed files, bundled shell identity and bounded shell cancellation. A separate framework-only checkpoint passed against the minified app, verifying schema/integrity and unchanged original owner records. The installed minified APK is 4,896,897 bytes, uses the existing development signer, and excludes the debug fixture/loopback endpoint. It is not a public production-signed release.

The previous code-13 batch had 20 passes and one explicit unplugged-phone precondition failure while USB was charging. That case was not rerun or relabeled as a pass. No uninstall/data clear of the main app, credential rotation or permission broadening was used. All five original agent/grant records, three reminders, two automations and the selected document-root record were preserved.

## Current blockers and acceptance gaps

- The real tomorrow-weather read/decision path succeeded after owner-supplied coordinates; a rain reminder was correctly skipped. Commute settings, consent-aware native location and actual Maps route-reading capabilities are now implemented. Maps UI control produced a real future-morning duration range; native GPS still requires owner consent, and Grok completed future-time selection and the corrected route using existing shell permission; the installed native reader also passed its physical check. New native-family scopes are still explicit owner choices. Tool metadata now distinguishes a point forecast, caller-owned reminders and the next Android-exposed alarm.

- Real unplugged health-threshold acceptance requires an unplugged device; do not fake the battery state.
- Calendar writes require a verified dedicated writable test calendar. No personal calendar was selected or changed in this pass.
- Biometric success/cancel, special-access restoration and disruptive network/reboot scenarios need their actual owner/platform conditions. Instrumentation force-stop is separate from ordinary process death.
- Hosted retry identity is deployed and verified with actual reads/writes and a single recorded execution. Grok also returned a successful stable keyed identity; its initial negative interpretation was incorrect. Review the exact results and remaining loss/restart limits in pass 14. The initial Grok status read recovered its successful original record after socket replacement; a shell attempt failed before phone dispatch.
- General storage retention remains open. Blocking IO cancellation and ADB per-packet flow control are patched in code 15; their full physical interruption acceptance remains open. Shared discovery and network-triggered wake-up are implemented; broader performance and transition acceptance remain open.
- Full Fold/large-font UI acceptance, quiet CPU/PSS/network/database-growth measurements and compatible signed distribution remain incomplete. There is no completed battery or 24-hour availability study.

## Historical evidence

- [Pass 13: code-13 physical checkpoint and repository cleanup](engineering-pass-13.md)

Dated reports preserve their original versions, measurements and limitations; they are not fresh verification of the current source.

- [Pass 12: scheduled health scans, owner Inbox and selected SAF](engineering-pass-12.md)
- [Pass 11: UI/recovery, connection controls and background workflows](engineering-pass-11.md)
- [Pass 10: local ADB pairing, daemon recovery and authorized reboot](engineering-pass-10.md)
- [Pass 9: proposal recovery, metadata projections and expiration](engineering-pass-9.md)
- [Pass 8: semantic workflow, Action Inbox and provider checks](engineering-pass-8.md)
- [Pass 6: installed platform and owner permissions](engineering-pass-6.md)
