# Installation, updates and signing

The current source and installed debug build are **0.6.0-dev.6 / versionCode 14 / SQLite 10**. See [pass 14](engineering-pass-14.md) for preservation checks and fresh live acceptance. Existing physical testing uses an in-place update of the same `dev.siconnect.app` installation and existing debug certificate. No replacement release signing key has been generated. There is not yet a validated public production-signed APK distribution channel.

## Preserve the existing installation

Android accepts an update only when its application identity and signing lineage are compatible. Obtain APKs from this repository’s verified owner-controlled build/release artifacts, check the commit, version and SHA-256, and verify the signer against the installed certificate. Do not use an arbitrary APK from an issue or message. Do not uninstall, clear data or generate a new signing key to resolve an update mismatch.

For development, build with JDK 21 and Android SDK 37 using `./gradlew :app:assembleDebug`; use `adb install -r` when a workstation is available. The same compatible signed APK can be downloaded directly on the phone and opened with Android’s package installer after authorizing that download source. A sideloaded app cannot silently install arbitrary updates. This pass does not implement an automatic updater or claim a fresh-install phone-only distribution acceptance pass.

## Owner-signed build workflow

The manual **Owner-signed APK** GitHub Actions workflow uses the `android-signing` environment. Configure only an owner-controlled existing compatible keystore through environment secrets `SICONNECT_SIGNING_KEYSTORE_B64`, `SICONNECT_STORE_PASSWORD`, `SICONNECT_KEY_ALIAS`, and `SICONNECT_KEY_PASSWORD`, plus the environment variable `SICONNECT_EXPECTED_CERT_SHA256`. Review the certificate continuity decision before configuring a different identity. Never commit the keystore, passwords or `signing.properties`.

The workflow refuses incomplete signing configuration, builds/tests/lints, verifies the signed APK’s certificate against the expected SHA-256, and creates a versioned APK artifact with commit/version/schema/signer/APK hash/size/build-run metadata. It deletes temporary signing files afterward. It does not publish a GitHub release or generate a key. **This new workflow has not been run with owner signing credentials.** Host `assembleRelease` currently produces an unsigned build when signing is unconfigured; that artifact is not an installable signed production release. Metadata records build provenance, not a demonstrated bit-for-bit reproducible-build result.

## Database continuity

Schema 9 added request tombstones; schema 10 adds a bounded health-alert ledger. Neither migration recreates the database. Physical upgrade checks confirmed SQLite integrity and preservation of the five original agent credential/grant hashes, three original reminders and original automation. The separately authorized disposable schedule was disabled after its test runs. Never downgrade an installed schema-10 database to a schema-9 APK or wipe data as recovery. Retain a compatible current APK and a private owner-controlled backup/recovery point. APK signature continuity and database integrity are separate checks.

## Pass 12 local signed-preview validation

A minified development preview was signed locally with the existing compatible development keystore. Signature verification matched the certificate from the actual previously installed APK. The signed preview is 4,860,033 bytes, version 0.6.0-dev.4 / code 12 / schema 10. Private build metadata records its APK SHA-256 and source commit. No signing material was uploaded or committed, and no replacement key was generated.

This signed minified preview has not been installed through Android's package installer or published. It is not a verified production release. The physical in-place update used the compatible signed debug APK and preserved credentials, ADB host identity preferences and durable records. Debug fixtures and the loopback endpoint were independently verified absent from the minified release manifest/code. Exercising the owner-signed GitHub workflow and the full phone download/package-installer journey remains separate work; no permission to upload the owner's signing key is inferred.

## Code-14 self-contained development installation

The optimized development APK (4,896,897 bytes) was signed with the existing development certificate and installed in place. SQLite integrity and original owner records passed actual readback after installation. Fresh Grok native/shell/runtime reads and official SDK keyed read/write acceptance passed on that installed artifact. This is still a development signer; no new key or production release channel was created.

Only `dev.siconnect.app` remains installed among SIConnect and its fixtures. The generic `dev.siconnect.notificationfixture` and temporary `dev.siconnect.app.test` packages were removed. The main APK excludes the debug semantic fixture and loopback endpoint. Its own foreground connection service and bundled shell daemon are normal runtime components from the same APK, not separate companion applications. Advanced control still requires authorized bootstrap; removing test packages does not eliminate Android's authorization/reboot requirements.
