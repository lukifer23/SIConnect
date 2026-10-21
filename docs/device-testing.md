# Device testing

Target physically exercised October 20, 2026: Galaxy Z Fold 7 SM-F966U, Android 17/API 37, One UI 9. Always select the current authorized serial from `adb devices -l`; do not copy a private serial into documentation.

```sh
./gradlew :core:test :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
./gradlew :app:assembleDebugAndroidTest
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
python3 tools/start_builtin.py --serial <serial> --restart
adb -s <serial> shell am start -W -n dev.siconnect.app/.ui.MainActivity
adb -s <serial> shell am instrument -w -e class dev.siconnect.app.RuntimeDeviceTest dev.siconnect.app.test/androidx.test.runner.AndroidJUnitRunner
```

APK metadata: `app/build/outputs/apk/debug/output-metadata.json`; application ID `dev.siconnect.app`. Android host unit task currently has no test sources; instrumentation is distinct from host tests. Core currently has 30 passing tests, including exact standing grant boundaries. Five current device runtime tests passed, including an actual precise alarm followed by a policy-governed notification. A separate real gateway setup test passed. Do not present CI compilation as physical verification.

Actual external actions: Grok Bot through hosted MCP opened apple.com in physical Chrome and created the requested Samsung Clock test alarm. Approval UI was used on the phone. Screen and Clock state were inspected after execution; shell exit status alone was not treated as verification. Native and shell calls were correlated by request IDs with durable records. Private evidence is outside Git.

Ordinary process death was tested after enabling broad permission: automatic service restart and a new unattended shell succeeded in 1.65 seconds. Force-stop was not used.

After instrumentation, launch MainActivity again to restore the owner-enabled foreground connection; the runner can leave the app cached. This differs from the separately exercised ordinary process-death recovery.

Reminder migration: SQLite v3 adds a numeric revision column. Compare-and-set uses that column rather than serialized JSON, preserving records created before new default fields existed. Existing WorkManager records are replaced from durable reminder state when restoring so they reference the current worker class. Exact timing requires Android special access and never silently switches to deferrable timing.

Still required: notification action duplication; cancellation and output stress; disposable install/uninstall; reboot (requires separate owner authorization); screen-off/idle/network changes; long-duration measurements. Force-stop is a separate Android state and must not be labeled ordinary process death.

## OAuth, client permissions and latest installed UI

Actual Dots and ChatGPT OAuth calls to native status and shell identity were correlated with phone SQLite records. The actual Dots app-install attempt reached the phone but was initially denied by agent scope. No approval was created. Two same-name saved OAuth clients had independent grants; the app-family permission had been applied to the other one. The new installed Home links directly from the denied request to its agent, distinguishes same-name cards and sorts them by recent request activity. The owner subsequently saved the correct grant, verified in SQLite; the subsequent install retry completed and was verified.

Hermes' real email was confirmed by the owner. A deterministic script using Hermes' native MCP runtime then completed an official-app Play Store installation, verifying installed package, version and installer through the phone. That is not evidence that the earlier full LLM run completed all tasks. Grok app install/uninstall behavior was separately reported working by the owner; a repeatable disposable-package uninstall through structured `apps.uninstall` is still outstanding.

Latest Home approval/permission changes built and linted, installed successfully, and displayed the correct permission card in a real UI hierarchy. No automated permission/approval taps were performed during this diagnosis. Full review-duration, rotation/fold, notification-action and process-death interaction checks remain required. An Android heads-up banner disappearing does not imply that its underlying approval/request was removed; check Home's pending card and notification shade.

The latest Activity update was separately built/linted/installed. One updated native execution/SQLite/idempotency test passed, including reopening the production database and checking the new lightweight request projection. ADB UI checks exercised Activity opening, attention filtering, sort reversal and real-bot selection options. No owner permission, approval or denial control was pressed. The whole five-test suite was not repeatedly run while the owner reviewed prompts: its isolated approval test intentionally creates short-lived test notifications.

The exact blocked-agent permission dialog remained open for a 20-second observation without any permission changes by the test. The owner then saved Apps and broad device control; the resulting grant and audit event were verified directly in the private phone database. Other bots' grants were not copied or merged.

Dots then completed a real official-app installation after the grant correction. Its initial query, official listing launch, single installation tap and final verification were matched to that OAuth principal's durable phone records. A separate user-0 package query confirmed package/version/Google Play installer. The final bounded command output was not truncated. Full package dumps reached the output limit during intermediate checks; they were not accepted as complete package metadata. No login was attempted.

Dots' subsequent structured uninstall waited for the owner, then completed after real phone approval. Its actual request retained a ten-minute approval window, and independent user-0 inspection confirmed the test package was absent. Dots then checked for an existing matching sent test, sent the separately authorized email once and inspected Sent/header/body. The three reported email phases matched successful non-truncated phone records; an independent Gmail hierarchy read confirmed the test subject and body. No private recipient or message data is included here.

Latest review regression: two targeted physical tests passed (SQLite/idempotency/projection and resolved-action review reentry). The latter opens a completed native diagnostic, closes its review and delivers the same intent again to a real singleTop activity; no approval is executed. Earlier framework text-search probes failed to find Compose virtual nodes. Actual window-child traversal corrected the selector. A failure screenshot also exposed clipped dialog controls; the installed bounded layout's Close control was independently measured at 126 pixels high and exercised. Initial failures remain documented; the final two-case run had zero failures/skips.

## Pass 3 focused physical validation

Private artifacts `pass3-package-device.txt` and `pass3-lifecycle-device.txt` record two and three passing instrumentation cases respectively (overlapping package coverage), without personal data fixtures. The latter suite exercises real PackageManager, the bundled shell supervisor and interrupted reminder reconciliation. In-place installation preserved the application ID and database. Host checks include all required Android build/lint/test-APK tasks; the JVM Android unit-test task has no sources. Pairing, final hosted deployment and longer collection evidence are recorded when executed, not inferred from these tests. Existing real contacts/emails/store installations were not repeated.

## Owner-coordinated locked-phone workflow

Hermes's configured native MCP runtime executed real requests while runtime metadata reported `screenInteractive=false`, `keyguardLocked=true`, `deviceLocked=true`. Package inspection and two disposable shell-file operations succeeded; an exact SIConnect reminder reached SUCCEEDED and Android's notification service independently listed its test notification. Final runtime still reported screen off/locked. Private evidence is in the locally retained `pass3-hermes-locked` artifacts and notification verification; no captures, personal identities, tokens or content are checked in.

The first read encountered DEVICE_REPLACED_UNKNOWN_OUTCOME. Its original request was persisted as SUCCEEDED and retrieved with `requests_get`; no mutation was resubmitted. A preceding foreground observation also performed five read-only calls without changing the foreground Clock activity. Full reboot/Doze/network-switch behavior is not established by these checks.

Managed-file round trip (real authenticated test identity, disposable directory), history keyset ties/filtering and structured package inspection passed in a final three-case physical run. The initial file listing failed due to canonical Android path aliases; the root path was canonicalized consistently and the rerun passed. Three app JVM tests use explicitly controlled forecast data for DST/window/missing-value calculations; production weather uses real HTTP.

Final changed-storage validation on 0.3.0 passed four physical instrumentation cases in 0.155 seconds: audit-insert failure rolls back EXECUTING state, native SQLite/idempotency, managed-file round trip/traversal boundary and keyset history ties/filtering. Test identities/resources are isolated. Android instrumentation terminates the target process; this is distinct from ordinary process death. The same in-place package-update recovery route restores connectivity afterward without opening an Activity.

Final host counts: 30 core tests, three app JVM forecast tests, seven local gateway protocol/OAuth tests and four Python subprocess/path tests passed with zero failures/skips in their final runs. Earlier exploratory failures/skips and corrected defects are described above; these counts do not imply the full requested 20-case device/provider matrix passed. Calendar, disposable APK install/uninstall, reboot, full UI lifecycle and extended Doze/network switching were not rerun in this pass.

A private post-upgrade database snapshot passed quick_check at schema v4. All three existing remote principals retained identical grants and credential hashes; the existing personal reminder remained unchanged. The background APK upgrade retained the foreground activity and restored an acknowledged gateway connection. Fresh official-client checks passed seven real device tools; Hermes also called live runtime/capability metadata, status, identity and structured inspection. The installed live registry advertised 43 tools and existing bearer scope did not automatically include managed files.

## Pass 4 focused checkpoint

In-place Android 0.4.0 upgrade, same package/signing/database: ten physical cases passed, zero final failures/skips, 2.964 seconds in that targeted invocation. These covered three scheduler cases (actual WorkManager recurrence plus two persisted-boundary fixtures), shared-screen contention with background execution, two Binder/chunk cases, actual calendar permission, package inspection, managed-file round trip and shell identity. An earlier invocation had a wrong test-class name; the corrected class passed. No personal messages/calendar events/files were mutated by this suite.

Android 0.4.1 was subsequently installed in place and the existing hosted official client successfully invoked seven actual phone tools. Its 69-tool catalog is actual discovery, not a claim that every new mutation is physically verified. New SAF/calendar write fixtures require explicit `documentTestRootId` / `calendarTestCalendarId`; do not run them against arbitrary user data. The generic exact alarm enqueues WorkManager, so exact alarm delivery is not proof of exact skill start time.

The strong-biometric/device-credential UI must be tested by the owner. Do not automate a successful authentication. A resolved review may still be exercised without creating/approving a real request. New local scheduler fixtures are test-only persisted boundary records, not production mocks or a claim that all live kill timings were tested.


Real Pass 4 conversational chain: Grokbot sent the bounded test instruction through the phone Telegram UI, Hermes received it under its intended profile and executed actual MCP calls. Sanitized correlation: native status `63d0153d-624a-4d6b-a780-3ec7abc31839`, shell identity `bee9cafd-9d04-469f-9f6c-8ce8121edb2d`, disposable notification `cd537435-1c91-4302-8c9b-c8528efc6656`; each persisted SUCCEEDED. Hermes used the older raw inspection path for its package check. The notification capability confirms Android observed its own posted notification, not that the owner saw it. A separate notification-list attempt was scope-denied and was not bypassed. No email, personal message or app installation was repeated.


Final 0.4.1 targeted physical invocation: **20 passed, zero failed/skipped**, 11.531 seconds. Added live Open-Meteo skill retrieval (explicit public test coordinates, notifications/reminder branches disabled), actual notification/reminder key deduplication and document-root catalog checks. Rechecked real exact reminder delivery, interrupted reminder claim, same-name pairing, engine approvals/denial/revocation, shell timeout/output/cancellation, durable native skill, package/managed files and scheduler/lease/Binder paths. Exact fixture cleanup now removes only its own reminder row and notification in finally. SAF and calendar write cases were not selected; they are still pending owner setup, not silently counted as passing.

A post-instrumentation hosted smoke initially passed six native calls but failed shell identity as PRIVILEGE_REQUIRED. The daemon had dropped its remote owner BinderProxy; Android logged automatic removal of its death recipients after garbage collection. The correction retains that proxy and recipient, ignores stale callbacks and restores attachment on the daemon looper. A subsequent three-case physical Binder/identity run passed, and actual app process termination produced a new app process with the same daemon in 1.311 seconds in one observation. Final hosted checks after restoring the owner-enabled service returned SUCCEEDED for all seven native/shell calls. Instrumentation shutdown itself can stop the connection service; this is distinct from ordinary process death of a running sticky service. An intermediate smoke overlapped an intentional process interruption and returned DEVICE_OFFLINE; that is not represented as a passing attempt.
