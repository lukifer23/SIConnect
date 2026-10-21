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
