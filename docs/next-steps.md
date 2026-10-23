# Next steps

## Background operations milestone — October 22, 2026

Android **0.6.0-dev.3 / code 11**, SQLite **9**, **85** tools. Background health scans extend the existing snapshot workflow with storage/network/thermal readings, optional threshold notifications and readable stored results. The original workflow upgrades only through owner authentication; no schedule or permission is enabled automatically. Two real owner-flow manual worker runs succeeded on the Fold with six native readings each and warnings disabled. The existing schedule remains disabled. New connection history and pause/resume controls reuse enrollment and retained event storage. See [background operations](background-operations.md) and [pass 11 evidence](engineering-pass-11.md).

The preceding UI/security checkpoint `0577d54` passed Linux CI run `39890573408`. The new milestone requires its own CI result. A manual worker run is not a new screen-off scheduled-run or reboot acceptance result. Remaining priorities: owner-confirmed future scheduled dispatch, controlled network-transition evidence, compatible signed distribution, Calendar/SAF resources and Action Inbox lifecycle.

## Earlier pass 11 checkpoint — October 22, 2026

Android **0.6.0-dev.2 / code 10**, schema **9**, installed in place. CI metadata regression fixed; Linux run `39886068956` passed. UI and recovery hardening removes duplicate editors, distinguishes granted access from service connection, reuses existing gateway enrollment and serializes setup/recovery. Original identities/grants/reminders/automation were preserved. Security updates target OkHttp 5.5.1 and Bouncy Castle 1.86 with strict checksums. See [pass 11 evidence and current limitations](engineering-pass-11.md); later app/security changes require their own physical/CI results. Headless workflow expansion, connection history and owner-interactive acceptance remain active priorities.

Earlier checkpoints below are historical; their versions and pending statements apply to those dates.

## Pass 10 checkpoint — October 22, 2026

Preserve the now-verified local pairing, raw-v2 Binder bootstrap, repeated daemon recovery, app-process recovery and owner-approved reboot. Next test Wi-Fi/cellular/new-network transitions, revocation and fully unassisted fresh installation; document exact remaining interactions. Finish owner Inbox/biometric acceptance, designated Calendar/SAF CRUD, scheduled automation/weather and semantic lifecycle tests. Configure owner-compatible signing and validate the signed artifact workflow. No complete full-platform acceptance is claimed; see [pass 10](engineering-pass-10.md).

## Pass 9 checkpoint — October 21, 2026

Pass 9 closed Inbox quota-expiration, transient pre-insert dispatch recovery and full-payload list-query gaps. Next: real owner Inbox acceptance/cancel; accessibility owner restoration and normal-process edge cases; explicitly selected Calendar/SAF CRUD; diagnostics Run now and configured commute delivery; existing Dots/Grokbot checks; complete retained soak. Do not repeat completed personal sends or broaden grants to pass smoke tests. See [pass 9](engineering-pass-9.md) and the owner harness instructions in [device testing](device-testing.md).

## October 21, 2026 checkpoint

Fresh update: the normal-process semantic fixture passed in 3.393 seconds after owner restoration, including text/readback, click, checkbox, scroll, waits/cancellation, stale/protected/password exclusion, back/home and lease checks. The service remained bound. Grokbot and Hermes completed fresh native/shell read-only pairs; Dots remains pending safe client access. See [pass 8](engineering-pass-8.md) for limitations and preserved initial failures.

Current checkpoint: 0.5.0-dev.2/code 7, SQLite 7, 85 tools. Action Inbox and installed-app picker are implemented. Prioritize remaining semantic interruption/identity cases, real owner acceptance/form-save and designated Calendar/SAF resources. See [pass 8](engineering-pass-8.md) for fresh versus historical evidence; the older roadmap below is retained for continuity.

Current development checkpoint: Android 0.5.0-dev/code 6, SQLite 6 and 81 registry capabilities. Existing credentials, grants, reminder records and gateway identity are retained. See [project status](project-status.md) for measured evidence and historical checkpoints.

1. Finish stable semantic interaction validation in the normal app process, including cancellation, stale nodes, password subtrees, package switches and shared-screen contention. Keep UI observation bounded. Global accessibility-event invalidation is conservative and may cause extra re-observation; do not weaken stale-action checks without measuring the cause.
2. Validate native automation creation/save and owner biometric success/cancel on the actual phone. The real worker/manual-run and queued-cancellation paths are physically tested; form-only screenshots do not prove owner authentication or scheduled delivery.
3. Complete unfolded, rotation, split-window, large-font, light/dark and long-review QA. Instrumented navigation requires explicit opt-in and an idle owner-approved screen. Avoid repeated instrumentation/package replacement while the owner is using Android special-access services.
4. Exercise accessibility loss/restoration and ordinary lifecycle recovery independently of instrumentation. Distinguish configured access, bound service and availability. Android may require owner off/on recovery; do not write secure settings or claim unattended re-enablement.
5. Finish owner-selected writable test-calendar and SAF-root round trips. Existing native tools remain implemented; provider synchronization, attendee delivery, MediaStore and recurring exceptions remain outside verified coverage.
6. Verify the configured commute workflow end to end with a real forecast and actual reminder delivery. The form exposes explicit location/timezone/wake/departure/threshold settings; do not change Clock alarms or infer road conditions.
7. Refresh existing provider catalogs and run brief read-only checks after the installed bridge is online. Preserve Dots OAuth and current Grok/Hermes principals; test failures during app replacement are device-offline observations, not evidence of broken authentication. Independently revocable bearer migration remains owner-coordinated future work.
8. Continue the sparse soak and matched background/locked resource observations. The revised profiler rejects CPU intervals across restart/reboot/version/counter changes. No completed 24-hour availability or battery claim exists.
9. Expand measured transport/gateway loss recovery with disposable writes and original-request retrieval. Preserve device SQLite authority and never blindly retry unknown mutations.
10. Improve app selection in semantic permission management and compact package workflows. Messaging via RemoteInput is app-specific; universal RCS access and reliable generic send verification are not implemented.
11. Investigate legitimate phone-only privileged bootstrap. Reboot still requires separately authorized testing and an authorized shell startup; retain native degraded mode and the working bundled backend.
