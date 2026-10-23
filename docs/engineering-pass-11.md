# Engineering pass 11 — active verification

Starting source: `651a068d66ef394a0b1db958f0dcb5b2cf67f4e3`, matching fetched `origin/main`; clean working tree, branch `main`. This is an active engineering record, not completed platform acceptance. Existing pass-10 physical results remain historical unless explicitly repeated here.

## CI dependency verification

Run `39884437726` passed gateway and hygiene jobs but failed Android configuration on `kotlinx-coroutines-bom:1.8.1` POM. The preceding correction had covered core/debug compilation but omitted the instrumentation/lint dependency paths. Resolving the entire CI task set with `--no-configuration-cache --refresh-dependencies --continue` also reproduced a missing `groovy-bom:4.0.29` POM (its Gradle module was already catalogued).

Only those two required Maven Central metadata artifacts were added. Their exact HTTPS artifacts were cross-checked with published SHA-1 and pinned using SHA-256. Verification remains strict. The complete host task set, including instrumentation APK, lint and release, then resolved successfully in 40 seconds; most compilation tasks were up to date. This is a dependency-resolution check, not a fresh execution of every test. Linux CI run `39886068956` on `f496b03` subsequently passed all Android/core, gateway and hygiene jobs. Later application changes require their own run.

## Runtime observations and unresolved gaps

Read-only hosted checks found one application process and one shell daemon. Recent application exit history contained development updates/instrumentation and no new crash attributable to this interval. A socket replacement interrupted an immediate read response; lookup of its original request returned the original successful execution. No mutation was resubmitted. The cause of that reconnect remains under investigation.

A development memory snapshot reported app PSS 145,852 KiB, RSS 274,872 KiB and swap PSS 5,359 KiB; Android reported 4,858,425,344 available bytes and no low-memory condition. These are single debug-build observations, not an idle/battery benchmark. The combined shell probe exited 1 because its daemon-name memory filter did not match; no daemon memory measurement is claimed from that probe.

Current source findings to correct and validate include overlapping recovery triggers silently discarded by `tryLock`, discovery occurring before a busy indicator is set, stale recovery-toggle state after identity reset, broad local-network permission bundled with unrelated native permissions, and notification-listener access being confused with a disconnected service. A saved pairing timestamp is historical authorization evidence, not proof Android still trusts the key.

Still open: fully owner-operated installation/pairing, signed artifact workflow with compatible credentials, stronger reboot with every workstation ADB server stopped, network transitions, permission/key revocation, owner Inbox lifecycle, designated Calendar/SAF resources, automation/weather/semantic edge cases and a controlled quiet soak. No new reboot or signing-key upload has been performed in this pass.


## Implemented UI and recovery changes

Development build: **0.6.0-dev.2 / code 10**, schema 9. Installed in place with the original signing certificate; no auto-grant flag. Post-update integrity was `ok`; original credentials/grants, device/gateway enrollment, three reminders and one automation matched the private baseline. Five agent records remained, with no new agent.

Home separates native readiness from optional advanced control and removes repeated setup/problem notices and runtime backend identifiers. Android access routes to one Settings screen. Settings uses consistent authorization/connection rows, hides raw credentials/backend selection in advanced controls and leaves automation editing in the existing primary Automations screen. Result permission links open the existing Agents screen instead of a second permission dialog. Settings queries only data for its visible section rather than reloading all subsystem lists for every execution update.

Gateway connection and AI-agent identity management have separate labels. Reconnect existing gateway reuses enrollment; it does not rotate credentials or create an agent. Raw connection edits explicitly stop the old socket before connecting with the new configuration. Invalid setup codes are rejected before requesting owner authentication. Long connection/invitation dialogs scroll within the available height.

Native bulk access no longer includes local-network permission. Runtime requests exclude already granted access, distinguish an empty/cancelled response, and route previously permanently denied access to Android app permissions. Returning from Settings clears stale request messages and refreshes actual grants. Notification availability distinguishes access denied from access granted/service disconnected, including dispatch-time checks. Reconnect reports a request, not a successful binding.

Setup operations mark discovery busy immediately, serialize resource ownership, coalesce overlapping recovery signals and discard pending recovery on cancellation. Four host regression cases cover duplicate taps, coalescing, cancellation/cleanup and a cancelled scope. Recovery toggle state is observed instead of remembered independently. Interrupted identity reset persists a fail-closed marker and cannot silently generate another key. IPv6 matching uses numeric address bytes and link-local scope, with two host regressions; discovery canonicalizes to the actual assigned local address. NSD denial stays a permission error. Reauthorization can reuse the existing host key instead of requiring identity reset. Check connection now verifies a fresh fixed Binder command; full identity is confined to advanced diagnostics.

Fresh physical screen review verified the cover-display Home, Settings access statuses and advanced-control entry after the update. Notifications, Accessibility and listener remained connected; calendar access correctly remained ungranted. Large-font/unfolded/error-state and fully owner-operated pairing acceptance remain separate pending cases. No owner biometric, calendar write or document write is claimed from navigation.

## Dependency security update

A fresh full npm audit (including development dependencies) reported zero known advisories. CI now audits development dependencies too. This is an advisory-database result, not proof of complete security.

OkHttp is updated from 5.5.0 to 5.5.1. The [publisher's October 22 release](https://lysine.dev/okhttp/changelogs/changelog/) blocks eight compromised eMudhra intermediates; the publisher notes Google Play Services devices also receive a trust-store update. Current release provenance was checked against the publisher changelog/tag and Maven coordinates. Bouncy Castle is updated from 1.81 to 1.86 using the same crypto implementation, not a protocol replacement. Its [official release information](https://www.bouncycastle.org/download/bouncy-castle-java/) describes security hardening. SIConnect's pairing path uses AES-GCM/HKDF/SHA-256, not FrodoKEM; [CVE-2026-5598](https://github.com/bcgit/bc-java/wiki/CVE%E2%80%902026%E2%80%905598) is not represented as a reproduced SIConnect exploit.

Exact required root/Android artifacts and Bouncy Castle JAR/POM are pinned with SHA-256 after checking canonical HTTPS published checksums. Dependency verification remains strict. Full Bouncy Castle and LibADB MIT notices accompany the APK. This focused inventory is not a complete LGPL/distribution legal review or exhaustive vulnerability audit of every native/transitive component.

## Next product work

Prioritize actual screen QA, current connection/recovery evidence and sanitized durable connection-failure history. Extend useful background automation through the existing scheduler/skills/policy engine; distinguish capabilities that run without a screen from those that require foreground interaction. Do not enable schedules or expand grants silently. Smart scans need deterministic inputs, bounded work, durable results and explicit owner configuration. No new background scan feature or headless provider workflow is claimed implemented by these notes.


## Verified checkpoint

The updated OkHttp root/Android metadata and Android AAR, plus Bouncy Castle JAR/POM, passed detached OpenPGP signature verification in a disposable keyring using publisher-linked public keys. Strict SHA-256 pins remain the build gate. CI/signing workflows now ask setup-java to check the latest available JDK 21 instead of preferring an older cached patch. The local Gradle daemon uses Homebrew OpenJDK 21.0.13.0.2. The upstream Temurin release observed was 21.0.13+9; this observation alone does not establish that its certificate distrust patch matches the local build. Do not represent every JDK 21.0.13 distribution as patched for the out-of-band CA incident.

Fresh checks: 48 core + 51 Android JVM + 2 embedded-ADB JVM tests passed; 13 Python and 10 gateway tests passed, with zero skipped in these counts. Debug/instrumentation APK, lint and unsigned release builds passed with strict dependency verification. Five physical Android crypto/Keystore cases passed: matching password/tamper rejection, mismatched password rejection, malformed headers, nonexportable-key TLS/exporter/reload and interrupted-reset fail-closed behavior. These isolated peers are not a new adbd pairing. Seven hosted native/Binder checks passed with the security-updated application and the existing 85-tool catalog. No fresh Dots/Hermes application session is claimed.

Physical large-text review at font scale 1.6 exposed navigation-word splitting. Navigation now uses shorter visual labels at large scales, retaining full spoken descriptions; original text size was restored. No unfolded or complete TalkBack acceptance claim is made. Current screenshots and raw results remain private.


An optional `device.runtime(checkDatabase=true)` check and Settings → Advanced tools → Diagnostics → Check database health now inspect the live SQLite connection. Ordinary resource samples omit this work. A hosted call on installed code 10 returned database integrity `OK`, gateway `ONLINE` and privileged availability true. One non-atomic host DB/WAL capture separately failed an index check; that capture is retained privately and is not accepted as a recovery backup. The live success does not turn the inconsistent copy into valid backup evidence. No database repair, recreation or owner-data mutation was performed.
