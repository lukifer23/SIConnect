# Development setup

## Host toolchain (macOS, Apple silicon)

```sh
brew install openjdk@21 android-platform-tools
# Android SDK (command-line tools) at ~/Library/Android/sdk
sdkmanager "platforms;android-37.0" "build-tools;37.0.0"
```

- **JDK:** Gradle runs its daemon on JDK 21, selected via `gradle/gradle-daemon-jvm.properties`. Newer JDKs such as 27 may be installed alongside but are not used for the build.
- **Gradle:** use the wrapper, `./gradlew`. Gradle 9.8.2, AGP 9.4.1 and Kotlin 2.4.21 are pinned in `gradle/libs.versions.toml`.
- **SDK path:** set `ANDROID_HOME` or write `sdk.dir=...` to `local.properties`. `local.properties` is gitignored.

## Build and test

```sh
./gradlew :core:test :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
./gradlew :app:assembleDebugAndroidTest
```

## Device connection

### USB

1. Enable Developer options, then USB debugging.
2. Run `adb devices -l` and accept the RSA prompt on the phone.

### Wireless (Android 11+)

1. On the phone, open Developer options, then Wireless debugging, then **Pair device with pairing code**.
2. On the Mac, run `adb pair <ip>:<pairing-port>` and enter the code.
3. Connect, either automatically via mDNS (`adb mdns services`) or with `adb connect <ip>:<port>`.

Always target a device explicitly: `adb -s <serial> ...`. Never commit serials, IP addresses or audit output.

## Device audit

```sh
tools/adb_audit.py --list-devices
tools/adb_audit.py --serial <serial> --out ~/siconnect-local/audit.json
```

The harness runs read-only probes only, unless you pass `--allow-risk LOW_IMPACT` and confirm each probe.

## Release signing

Create a keystore outside the repo, then create a gitignored `signing.properties` in the repo root:

```
storeFile=/absolute/path/to/siconnect-release.jks
storePassword=...
keyAlias=siconnect
keyPassword=...
```

Debug builds use the standard Android debug key.

## Installed runtime

See [device testing](device-testing.md) for exact install/start/instrumentation commands and [gateway deployment](gateway-deployment.md) for authenticated hosted MCP setup. Double-click `Start SIConnect.command` or run `tools/start_builtin.py --serial <serial>` to start the bundled privileged service after authorized ADB setup. Use `--restart` after an APK update. This is a bootstrap, not a Mac runtime server. Native tools do not require it.
