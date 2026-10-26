# Project status

Updated October 26, 2026. Current Android source and installed debug build: **0.6.0-dev.5 / versionCode 13**, SQLite **10**, **85** tools. The gateway source adds optional caller retry identity; that gateway change has **not** been deployed. See [pass 13](engineering-pass-13.md) for this checkpoint, [the source audit](source-audit-2026-10-26.md) for code findings, and [next steps](next-steps.md) for the current queue.

| Area | Current state | Evidence and limits |
| --- | --- | --- |
| Execution and policy | Durable requests, immutable-operation deduplication, current-grant/deadline checks, explicit uncertain outcomes | 64 core tests; code-13 physical native execution/SQLite/idempotency test passed |
| Workflows and recovery | Prepared startup cursors, bounded continuation workers, stable long child IDs, validated workflow conditions/output | Host regressions pass; injected Android startup/storage-failure acceptance remains open |
| Reminders and scheduling | Serialized reminder admission/update/cancel, original-request cancellation, exact and deferrable dispatch | Physical exact delivery, interrupted-claim reconciliation, scheduler and manual-run cases passed; concurrent cancellation matrix remains open |
| Android owner UI | Shared feedback, reminder detail navigation, narrower shell permission reduction, adaptive management screens | Builds/lint pass; full Fold/font/rotation and genuine authentication cancellation remain open |
| Native resources | Managed files, selected SAF roots, Calendar Provider, typed diagnostics/package inspection | Code-13 managed-file round trip passed; selected SAF passed on code 12; dedicated Calendar CRUD remains open |
| Privileged execution | Existing bundled Binder backend; explicit optional Shizuku | Code-13 actual shell identity case passed after authorized bootstrap; no universal unattended reboot recovery claim |
| Gateway and clients | OAuth/PKCE/private bearer, acknowledged socket generations, original-result lookup; new optional retry key in source | 15 local SDK v1/v2 protocol tests pass; new keyed behavior has not been tested against the hosted phone path or deployed |
| Storage | Indexed history, compact request tombstones, bounded health-alert ledger | SQLite integrity passed; general request/workflow retention has no production owner flow |
| Security and release | Existing signing identity/enrollment retained; debug fixtures excluded from minified release | Host release/isolation checks passed; public signed distribution and owner-signed Actions workflow remain open |

Host verification: **183 passing tests** (64 core, 89 Android JVM, two embedded ADB, 13 Python, 15 gateway). Android debug/release and instrumentation APKs built; lint has no errors and 71 warnings. The Android/Python results are from the source-audit build; the gateway was rerun after its retry change.

Physical batch on code 13: **21 tests ran, 20 passed, one failed its unplugged-phone precondition because USB was charging the phone**. It is not a clean full-suite pass. No charging state was simulated. Private pre/post comparison matched all five original agents/grants, three reminders, two automations and the document-root record. No uninstall, data clear, credential rotation or new agent was used.

## Current blockers and acceptance gaps

- Real unplugged health-threshold acceptance requires an unplugged device; do not fake the battery state.
- Calendar writes require a verified dedicated writable test calendar. No personal calendar was selected or changed in this pass.
- Biometric success/cancel, special-access restoration and disruptive network/reboot scenarios need their actual owner/platform conditions. Instrumentation force-stop is separate from ordinary process death.
- Gateway retry identity is ready for review in source; hosted rollout, catalog refresh and physical result-loss acceptance remain separate work.
- General storage retention, readiness-query amplification, network-triggered reconnect, blocking IO cancellation and ADB stream write flow control remain source-confirmed work.
- Full Fold/large-font UI acceptance, quiet CPU/PSS/network/database-growth measurements and compatible signed distribution remain incomplete. There is no completed battery or 24-hour availability study.

## Historical evidence

Dated reports preserve their original versions, measurements and limitations; they are not fresh verification of the current source.

- [Pass 12: scheduled health scans, owner Inbox and selected SAF](engineering-pass-12.md)
- [Pass 11: UI/recovery, connection controls and background workflows](engineering-pass-11.md)
- [Pass 10: local ADB pairing, daemon recovery and authorized reboot](engineering-pass-10.md)
- [Pass 9: proposal recovery, metadata projections and expiration](engineering-pass-9.md)
- [Pass 8: semantic workflow, Action Inbox and provider checks](engineering-pass-8.md)
- [Pass 6: installed platform and owner permissions](engineering-pass-6.md)
