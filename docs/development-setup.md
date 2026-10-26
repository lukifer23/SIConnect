# Development setup

Current checkpoint: Android **0.6.0-dev.5 / code 13**, SQLite **10**, **85** tools. See [project status](project-status.md) and [pass 13](engineering-pass-13.md) for measured results and remaining gaps.

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
./gradlew :core:test :app:assembleDebug :app:testDebugUnitTest :adb-bootstrap:testDebugUnitTest :app:lintDebug
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

## Hermes

Install the generic [SIConnect skill](../integrations/skills/siconnect/SKILL.md) in the active Hermes profile's skills directory. Configure an HTTP MCP server named `siconnect` with the deployed HTTPS `/mcp` address, `Authorization: Bearer ${SICONNECT_TOKEN}`, a 30-second connection timeout and a 130-second tool timeout. Store the variable in the client's private environment/secret store, never in this repository. Use `hermes mcp test siconnect` for real discovery; invoke `device_status` to verify phone execution and correlate its request ID in Bot activity. Start a fresh agent session if its existing tool catalog is cached.

The verified local setup currently shares the owner's existing paired agent with another client. OAuth Dots pairing is now implemented and verified with separate local identities; do not describe the shared private-bearer identity as independent grants.

## Gateway and tooling checks

```sh
python3 -m unittest discover -s tools/tests -v
cd gateway
npm ci
npm run typecheck
npm test
npm run build
npm audit --audit-level=high
```

`npm test` starts and tears down a local test-only Worker/device peer. It does not prove physical phone behavior. `npm run build` is a dry run; it does not deploy. The new optional MCP retry field requires the updated hosted gateway before a client can use it; see [gateway deployment](gateway-deployment.md).

## In-place update and recovery

The application ID remains `dev.siconnect.app`. Verify certificate compatibility, then use `adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk`. Do not uninstall or clear data to resolve a signer mismatch. Do not downgrade an installed schema-10 database to an older schema. After an update, reattach the existing bundled backend with `python3 tools/start_builtin.py --serial <serial> --restart` when needed, launch MainActivity if instrumentation left the connection stopped, and verify a fresh native call and actual shell identity. Enrollment preservation alone is not proof of online readiness.

## Shutdown and generated files

Use `./gradlew --stop` after all checks finish. Stop local Worker/test/profiler processes by their verified PID. `adb kill-server` ends workstation debugging transports without deleting the phone's pairing/enrollment or stopping its outbound gateway. Restart it when needed. Do not force-stop the phone or terminate the Binder daemon to clean host processes while agents should remain connected.

Build outputs and `gateway/node_modules` are regenerable. Keep `local.properties`, `gateway/.dev.vars`, signing/Keystore material, enrolled credentials and owner data. Do not remove shared Gradle caches while a daemon is active. See [repository hygiene](repository-hygiene.md) for the exact checkpoint cleanup and rebuild costs.
