# Engineering pass 10 — evidence and open acceptance

October 22, 2026. This is an **incomplete engineering checkpoint**, not full-platform acceptance. Starting source and fetched `origin/main`: `eb0ad6b3d05f43ceeaf18c2f188ae0756cc5618f`; starting branch `main`, clean working tree. Work remains on `main`. Use the commit containing this document for the exact source identity; deployment/CI outcomes require their actual run evidence.

## Preserved working system

The existing application ID, debug signer, gateway/device enrollment, OAuth and private-bearer identities remain in place. Physical in-place schema-8 → schema-9 upgrade checks reported SQLite integrity `ok`, the same five agents/credential hashes/grants, three reminders and one automation, with no additional agent. Baseline APK/database/preferences and private evidence are kept outside the repository. No wipe, uninstall, credential/signing rotation or intentional personal calendar/document mutation was performed in this engineering checkpoint. One reboot was explicitly approved by the owner and performed. The source recovery point retains the starting commit; older schema-8 APKs are not a safe database downgrade.

The baseline physical daemon was absent. Mac ADB initially reported no route while another host TCP probe succeeded. Running the existing ADB executable/key from Terminal on a separate host-server socket restored the authorized connection; the precise macOS per-process networking cause was not established. No missing Mac approval prompt is claimed to have been approved.

The original `tools/start_builtin.py` restored the bundled daemon using that Mac connection. Seven official hosted SDK calls then passed with 85 tools: native status/battery/memory/storage/network/thermal and shell identity, which returned **UID 2000 through ADB_SERVICE**. Grokbot, operated through the existing Mac application using the local cua-driver skill, independently completed status, battery, shell identity and `shell.execute` with `id -u`, stdout `2000`, exit 0. All four original request IDs were matched to successful phone SQLite records. Private evidence is not committed. This is **HOSTED MCP VERIFIED**, and fresh **Grokbot application verification**, after **Mac-assisted** bootstrap. It is not proof of phone-only setup. Fresh Dots/Hermes application-level checks remain open; earlier evidence stays historical.

## Native setup and authorization architecture

Build: **0.6.0-dev.1 / code 9 / SQLite 9**. Existing DirectShellDaemon, Binder interface and execution authorization are preserved. The owner setup coordinator is not a capability, remote endpoint or alternate execution engine.

Implemented: contextual native onboarding; separate gateway/AI-agent/advanced-control steps; strict bounded HTTPS gateway setup-code parsing and owner confirmation; Android Settings special-access routes; an adaptive advanced-control dialog with current Binder evidence, a single pair-and-enable sequence, saved-authorization recovery, explicit identity reset/restart and collapsed diagnostics. A working daemon no longer hides the option to configure phone-only recovery. Opening Settings adjacent to SIConnect was physically observed on the cover display. Unfolded, large-font and complete cancellation/error UX acceptance remains open.

The phone-local ADB transport integrates pinned LibADB Android 3.1.1 source (`c849886ebc6d48e7b46d967e78a6bb65c90c3b74`, Apache-2.0 option), a pinned LGPL SPAKE2 JNI dependency and Bouncy Castle. Four ABIs are present. [Module notes](../adb-bootstrap/README.md) identify licenses, source pins and relinking instructions. JitPack is restricted to the SPAKE2 group; Gradle artifact checksums are recorded.

Protocol adaptations use platform TLS 1.3 and the public Android TLS exporter, require encrypted ADB, keep pairing and connection service discovery separate, bound packets/output/socket waits, close cancellation-owned resources, wipe mutable pairing-derived bytes, and avoid secret/peer-info logging. Stream-open notification, EOF/draining and builder-result defects in the integrated source were corrected. Host protocol and isolated crypto tests cover malformed framing and incompatible password rejection, but do not establish interoperability with actual adbd.

Host identity uses a nonexportable RSA-2048 Android Keystore key, reused across sessions. TLS PSS signing on this physical platform required Conscrypt’s raw RSA signing path; initial incompatible-padding tests failed and were corrected before the disposable local TLS test passed. No ADB private key is exported, stored in Cloudflare or exposed to MCP. Explicit owner reset removes only the local host identity; saved pairing with a missing key is an error, never silent key regeneration.

NSD resolves only numeric addresses actually assigned to this phone. Android 17 system-mediated discovery uses its service picker; optional manual/broad discovery has a contextual ACCESS_LOCAL_NETWORK runtime request and revocation check. Permission failures, pairing/TLS errors and connection/Binder failures are separate states. Samsung pairing and the granted local-network path passed. Permission denial/revocation and system-picker-only acceptance remain outstanding.

Authenticated bootstrap checks ADB `id -u=2000`, the actual installed package/source APK/app UID, existing daemon presence, fixed bootstrap command and authenticated Binder attachment; it closes temporary ADB and checks a fresh Binder command before success. External agents cannot supply bootstrap commands. No normal app process is described as shell. No legacy TCP/5555 fallback or permanent embedded ADB server is used.

**PHYSICAL DEVICE VERIFIED:** actual TLS/SPAKE2 pairing completed through the app after real owner fingerprint confirmation. Its saved key then authenticated actual adbd and proved shell UID 2000. Legacy `shell:` startup reproduced a failure: that path allocates a PTY, and terminal closure can kill a background daemon. The fixed path uses supported `shell,v2,raw:` framing, checks the actual exit status and bounds separate stdout/stderr. Five host tests cover framing, partial/missing status, invalid channels/lengths and output limits. With the original daemon absent, the app started a different bundled daemon using its own saved identity; all seven hosted checks succeeded with Mac ADB reporting no attached devices. No workstation bootstrap command was used. A second deliberate daemon death recovered with the same pairing, and seven hosted checks passed again.

The first setup used Mac-driven UI taps/code entry and the owner’s actual biometric; the cryptographic pairing, TLS connection and privileged launch ran on the phone. A fully unassisted fresh APK-download/install/owner-setup scenario remains to be validated. The earlier reported pairing error’s precise original cause was not established; do not erase it from the evidence history.

## Recovery and durability

Implemented phases distinguish native readiness, debugging disabled, permission required, pairing required/expired, saved pairing, ADB connecting/authenticated, bootstrapping, privileged ready/unavailable, owner action and failure. Opt-in saved authorization triggers recovery on runtime initialization, Binder death and Wi-Fi transitions with bounded attempts (0/15/60-second delays). It reuses only an existing key, respects explicit bundled/Shizuku selection and never enables debugging or changes secure settings. Ordinary tool execution remains on Binder after temporary ADB closes.

A phone reboot destroys the shell process. Android may require Wireless debugging to be re-enabled or trusted-Wi-Fi confirmation. Actual owner-approved reboot passed on the current trusted Wi-Fi: after the owner reported unlocking, seven gateway checks succeeded, including the restored Binder UID 2000. A changed private boot ID independently confirmed the reboot; original identities/grants/reminders/automation and SQLite integrity were preserved. No engineer-triggered Settings toggle, app launch or Mac bootstrap command was used after that reboot. The Mac ADB server did automatically rediscover one idle transport after startup; that idle connection did not launch the service. A repeat with every workstation ADB server stopped throughout reboot is a separate stronger acceptance test, not claimed here.

Ordinary app-process SIGTERM also passed: the app PID changed, the same daemon reattached and seven hosted checks succeeded. This is not force-stop evidence. Repeated daemon-death recovery passed separately. No universal unattended reboot guarantee or untested cellular/new-Wi-Fi result is made.

Shizuku remains optional. Source review found non-root boot behavior uses privileged secure-settings access and Android debugging-setting writes; this implementation does not copy that route. LADB’s shared embedded-server design/conflict warning was examined; SIConnect uses a direct temporary client and does not own or kill the shared localhost ADB server. Neither Shizuku autostart nor coexistence/Wi-Fi transitions were physically validated in this pass.

## Confirmed retention issue and security review

The previous prune predicate could delete a terminal root execution while an accepted proposal still referenced it. Reproduced with the old SQL in a disposable database; regression exercised on physical Android SQLite. Schema 9 adds indexed compact tombstones and parent lookup. Active accepted/executing proposal records, unknown outcomes and parent/child records are protected. Old terminal payloads compact in batches of at most 100; request/idempotency lookup still returns the original terminal outcome and `RESULT_EXPIRED`, preventing reinsertion/replay. Tombstones retain a minimum one-year outcome window and survive while referenced by proposals. Migration does not automatically activate pruning or delete owner history. Event trimming protects unresolved/unknown requests and active proposal links.

Protected unresolved records and long-lived workflow/proposal metadata can remain indefinitely; a comprehensive bounded retention/privacy policy and corruption/disk-pressure matrix are not completed. Interrupted-upgrade, broad concurrent maintenance and every workflow-reference case are not claimed covered.

Focused source review covered owner/bootstrap boundaries, manifest/exported components, Binder authority and UID validation, backend selection/death handling, gateway/OAuth/auth/pairing, SQLite/proposal retention, request output and relevant UI/runtime integration. No new remote bootstrap capability or privilege substitution was introduced. General authorized shell remains broad: it can reach surfaces beyond narrower semantic UI permissions. No cryptographic independent audit or exhaustive whole-repository audit is claimed. Remaining fuzz/cancellation/key-revocation, cross-agent and lifecycle cases need their exact validation.

## Validation

| Classification | Result | Scope/limits |
| --- | --- | --- |
| HOST TESTED | 48 core, 44 Android JVM, 2 ADB protocol, 13 Python, 10 gateway tests passed; no skips in these counts | Disposable/deterministic host fixtures do not prove phone pairing or owner authentication |
| HOST TESTED | Debug APK, instrumentation APK, lintDebug and release build passed | Release is unsigned without configured owner signing material |
| ANDROID INSTRUMENTATION TESTED | 11 targeted physical cases passed | Retention 3, proposals/migrations 4, SPAKE2 crypto 3, Keystore TLS/exporter 1; isolated TLS peer is not Android adbd |
| PHYSICAL DEVICE VERIFIED | In-place update/state preservation; actual native and restored Binder shell execution; adjacent Settings layout | Samsung Fold 7 Android 17/API 37; UI/ADB operation from Mac does not prove computer independence |
| HOSTED MCP VERIFIED | Seven SDK checks and four SQLite-correlated Grokbot calls | Existing gateway, existing identities; no Cloudflare redeployment |
| OWNER INTERACTIVE VERIFIED | Actual fingerprint-confirmed local pairing; explicitly authorized reboot and owner-reported unlock | Inbox accept/cancel/biometric lifecycle remains open |
| BLOCKED / OPEN | Fully unassisted fresh installation/setup, additional network/revocation tests, owner Inbox, selected Calendar/SAF resources, automation/weather and semantic lifecycle matrix | Tested local pairing/bootstrap/recovery are distinct from these outstanding cases |
| UNSUPPORTED GUARANTEE | Normal app self-elevation, silent arbitrary APK installation, universal unattended privileged reboot recovery | Use legitimate Android authorization only |

Gateway typecheck, ten MCP/OAuth/transport tests and Worker dry-run build passed. Test fixtures exercise lost-result reconciliation without a second write; no new real-device lost-mutation interruption scenario is claimed. Device SQLite remains execution authority. `UNKNOWN_OUTCOME` mutations must not be blindly retried. Existing clients were not re-enrolled or rotated. CI status must be checked after pushing; host checks are not CI evidence.

## Resource and release observations

Baseline debug APK: **38,555,243 bytes**; current debug APK: **45,340,619 bytes**, an increase of **6,785,376 bytes**. Current unsigned release: **5,994,847 bytes**. Native SPAKE2 binaries: arm64 28,192, armv7 28,888, x86 42,156 and x86_64 35,128 bytes. These are artifact sizes, not measured resident-memory costs. The recovery probe passed after local startup, but no controlled comparable bootstrap/CPU/battery benchmark is available.

A five-sample, 60.812-second development interval recorded 0.21 CPU-seconds / 59.959 valid seconds (0.3502% of one core), with no privileged daemon then present. This is not quiet idle or comparable baseline/after optimization. The retained collector completed after 25.13 hours, but 50 samples cover only 17.83 hours with gaps up to 150.22 minutes and a 7.31-hour tail. Of 147 calls, 128 succeeded, 6 returned DEVICE_OFFLINE and 13 shell-identity calls failed UNAVAILABLE; successful median 335 ms, maximum 1961 ms. No continuous 24-hour coverage, quiet soak or battery-drain estimate is established. Private evidence was retained.

A manual owner-signed artifact workflow now refuses absent signing material and verifies certificate continuity before uploading a versioned APK/provenance record. It has not been exercised with owner signing credentials; no new key, public release artifact, silent updater or fictitious Cloudflare provisioning flow is claimed. [Installation guidance](installation.md) preserves the current install/signing identity and explains schema-9 downgrade limits.

## Exact remaining priorities

1. Complete the fully owner-operated fresh installation/setup flow and a stronger reboot test with all workstation ADB servers stopped; preserve the successful local pairing, daemon recovery and authorized-reboot evidence.
2. Test Wi-Fi/cellular/new trusted-network transitions, revoked/expired pairing and permission denial/revocation. Keep persistent access indicators accurate after Android Settings changes.
3. Regress the existing Dots connection without duplicate agents or credential rotation; perform fresh Hermes application checks.
4. Complete real owner Inbox accept/cancel/biometric lifecycle, disposable owner-selected Calendar/SAF CRUD, actual scheduled/weather workflows and semantic contention/lifecycle tests.
5. Complete the remaining threat-model/database/gateway/device-loss matrix, controlled resource study and owner-compatible signed distribution validation. Update this checkpoint with actual results rather than claiming completed audit/acceptance.


## Primary protocol references

- [Android ADB/Wireless debugging](https://developer.android.com/tools/adb) and [Android 17 Wi-Fi 2.0 trusted-network handling](https://developer.android.com/blog/posts/introducing-fast-and-reliable-wireless-debugging-with-android-debug-bridge-adb-wi-fi-2-0).
- [AOSP Wireless ADB protocol](https://android.googlesource.com/platform/packages/modules/adb/+/HEAD/docs/dev/adb_wifi.md), [service dispatch](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/daemon/services.cpp) and [shell subprocess/PTY behavior](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/daemon/shell_service.cpp).
- [Android local-network permission](https://developer.android.com/privacy-and-security/local-network-permission) and [public TLS exporter](https://developer.android.com/reference/android/net/ssl/SSLSockets).
