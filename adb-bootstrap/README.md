# Embedded owner-only ADB transport

Selected source: [LibADB Android](https://github.com/MuntashirAkon/libadb-android), version 3.1.1,
commit `c849886ebc6d48e7b46d967e78a6bb65c90c3b74` (Apache-2.0 alternative, with
per-file BSD/MIT notices retained). This is a reviewed source integration, not a new ADB protocol.
Upstream's connection manager, legacy discovery, PRNG workaround and example application are excluded.
The Kotlin owner coordinator is in `app/.../bootstrap/`; no capability registers this library.

SIConnect modifications:

- Minimum API 31; use public `android.net.ssl.SSLSockets.exportKeyingMaterial` and platform TLS 1.3.
  No Conscrypt binary, hidden-API exemption, embedded ADB server or TCP/5555 fallback.
- Separate TLS context per host identity, not an upstream static identity cache.
- Reject AUTH/unencrypted CNXN; cap negotiated packet sizes and buffered output.
- Bounded socket connect/read, cancellation-owned socket closure, null-safe pairing teardown,
  wipe pairing/password-derived bytes, and omit peer identity from logs.
- Validate AOSP device-GUID peer type (1); preserve SPAKE2/TLS exporter channel binding.
- Fix stream-open lost-notification race, EOF/buffer draining, unsigned InputStream bytes and
  upstream Builder success-test inversion. Disconnect never destroys the persistent host key.

SPAKE2 JNI is pinned to `com.github.MuntashirAkon.spake2-java:spake2-android:2.2.1`,
[source commit `7615ddd680b990e14513ebb66eac4cb0dbf82464`](https://github.com/MuntashirAkon/spake2-java/tree/7615ddd680b990e14513ebb66eac4cb0dbf82464).
It supplies arm64-v8a, armeabi-v7a, x86 and x86_64 shared libraries. Its LGPL-3.0 license and
native-source notices accompany the APK in assets/licenses. The application and build scripts are
public and permit rebuilding/relinking with a modified library: build the pinned upstream Android
module (or compatible fork), publish it to a local Maven repository, explicitly substitute that
coordinate in this module, then rebuild SIConnect with your own signing identity for your own
installation. Changing signing identity cannot update an existing differently signed install.
Do not erase owner data to test relinking; use a disposable device/profile.

Bouncy Castle 1.86 provides upstream's AES-GCM/HKDF implementation. JitPack is restricted to the
one SPAKE2 group; other artifacts resolve through Google/Maven Central. Gradle verification
metadata pins downloaded artifacts. Neither upstream library claims an independent security audit;
SIConnect's focused review/tests do not constitute a complete cryptographic audit.

LADB was examined but not copied: it embeds an ADB server with its own lifecycle, publishes a
Shizuku conflict warning, and has additional distribution restrictions. SIConnect instead opens
one direct client socket and does not own/kill a shared localhost:5037 server. That removes that
specific shared-server design hazard; physical Shizuku coexistence remains a separate test.
