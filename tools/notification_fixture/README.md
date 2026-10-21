# Disposable notification action fixture

This separate test APK publishes real Android notification actions and receives real `RemoteInput` broadcasts. It has no Internet, SMS, contacts, calendar or account access. The receiver stores only a counter, action kind and SHA-256 digest of disposable input. Its exported read-only test provider exposes those receipts; it is not intended to remain installed.

This fixture exists separately because SIConnect deliberately refuses notification actions created under its own UID. That prevents remote invocation of SIConnect owner approval, pairing or biometric-management PendingIntents. There is no production test bypass.

Build with JDK 21 and the installed Android SDK 37/build-tools 37.0.0. Use a new private output directory outside the repository:

```sh
python3 tools/notification_fixture/build.py --output-dir /absolute/private/path/notification-fixture
```

The script generates a disposable signing key and signature-verifies the APK. Keep the generated key and APK outside Git. The package is `dev.siconnect.notificationfixture`.

Coordinate the test with the owner. Select the authorized device explicitly, install this disposable package, grant its notification permission, and launch its fixture Activity. The Activity posts one quiet test notification and finishes. It never sends a real message.

```sh
adb -s "$SICONNECT_TEST_SERIAL" install /absolute/private/path/notification-fixture/notification-fixture.apk
adb -s "$SICONNECT_TEST_SERIAL" shell pm grant dev.siconnect.notificationfixture android.permission.POST_NOTIFICATIONS
adb -s "$SICONNECT_TEST_SERIAL" shell am start -n dev.siconnect.notificationfixture/.FixtureActivity
```

The owner must enable SIConnect notification-listener access first. Build and install SIConnect's actual debug/test APKs using the existing project procedures. Opt into only this physical test:

```sh
adb -s "$SICONNECT_TEST_SERIAL" shell am instrument -w \
  -e class dev.siconnect.app.NotificationActionDeviceTest \
  -e allowNotificationFixture true \
  dev.siconnect.app.test/androidx.test.runner.AndroidJUnitRunner
```

The test discovers the live action through the actual registry/engine, rejects its authentication-required action, dispatches disposable RemoteInput text once, checks the independent receiver digest/count, verifies duplicate-request suppression and stale-reference denial, and cleans its own test identity/notification. It does not touch real contacts, messages or unrelated notifications. Without the opt-in, live fixture, or owner-granted listener access, it skips with the missing dependency stated.

After reviewing the private evidence, remove only the disposable fixture package:

```sh
adb -s "$SICONNECT_TEST_SERIAL" uninstall dev.siconnect.notificationfixture
```

A fixture receipt proves Android delivered that disposable broadcast. It does not prove another messaging application sent or delivered an actual message. Production notification-reply results therefore report `DISPATCHED` with `deliveryUnverified=true`; never blindly retry after an uncertain outcome.

Official API references: [NotificationListenerService](https://developer.android.com/reference/android/service/notification/NotificationListenerService), [Notification.Action](https://developer.android.com/reference/android/app/Notification.Action), [RemoteInput](https://developer.android.com/reference/android/app/RemoteInput), and [PendingIntent](https://developer.android.com/reference/android/app/PendingIntent).
