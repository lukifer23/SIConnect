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
