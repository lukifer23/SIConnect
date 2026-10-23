# Installation, updates and signing

The current build is **0.6.0-dev.1 / versionCode 9 / SQLite 9**. Existing physical testing uses an in-place update of the same `dev.siconnect.app` installation and existing debug certificate. No replacement release signing key has been generated. There is not yet a validated public production-signed APK distribution channel.

## Preserve the existing installation

Android accepts an update only when its application identity and signing lineage are compatible. Obtain APKs from this repository’s verified owner-controlled build/release artifacts, check the commit, version and SHA-256, and verify the signer against the installed certificate. Do not use an arbitrary APK from an issue or message. Do not uninstall, clear data or generate a new signing key to resolve an update mismatch.

For development, build with JDK 21 and Android SDK 37 using `./gradlew :app:assembleDebug`; use `adb install -r` when a workstation is available. The same compatible signed APK can be downloaded directly on the phone and opened with Android’s package installer after authorizing that download source. A sideloaded app cannot silently install arbitrary updates. This pass does not implement an automatic updater or claim a fresh-install phone-only distribution acceptance pass.

## Owner-signed build workflow

The manual **Owner-signed APK** GitHub Actions workflow uses the `android-signing` environment. Configure only an owner-controlled existing compatible keystore through environment secrets `SICONNECT_SIGNING_KEYSTORE_B64`, `SICONNECT_STORE_PASSWORD`, `SICONNECT_KEY_ALIAS`, and `SICONNECT_KEY_PASSWORD`, plus the environment variable `SICONNECT_EXPECTED_CERT_SHA256`. Review the certificate continuity decision before configuring a different identity. Never commit the keystore, passwords or `signing.properties`.

The workflow refuses incomplete signing configuration, builds/tests/lints, verifies the signed APK’s certificate against the expected SHA-256, and creates a versioned APK artifact with commit/version/schema/signer/APK hash/size/build-run metadata. It deletes temporary signing files afterward. It does not publish a GitHub release or generate a key. **This new workflow has not been run with owner signing credentials.** Host `assembleRelease` currently produces an unsigned build when signing is unconfigured; that artifact is not an installable signed production release. Metadata records build provenance, not a demonstrated bit-for-bit reproducible-build result.

## Database continuity

Schema 9 adds request tombstones and indexes without deleting existing agents, credentials, requests, reminders or automations during migration. Physical upgrade checks confirmed SQLite integrity and preservation of the existing five agents, three reminders and one automation. Never downgrade an installed schema-9 database to a schema-8 APK or wipe data as recovery. Retain a compatible current APK and a private owner-controlled backup/recovery point. APK signature continuity and database integrity are separate checks.
