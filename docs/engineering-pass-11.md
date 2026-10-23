# Engineering pass 11 — active verification

Starting source: `651a068d66ef394a0b1db958f0dcb5b2cf67f4e3`, matching fetched `origin/main`; clean working tree, branch `main`. This is an active engineering record, not completed platform acceptance. Existing pass-10 physical results remain historical unless explicitly repeated here.

## CI dependency verification

Run `39884437726` passed gateway and hygiene jobs but failed Android configuration on `kotlinx-coroutines-bom:1.8.1` POM. The preceding correction had covered core/debug compilation but omitted the instrumentation/lint dependency paths. Resolving the entire CI task set with `--no-configuration-cache --refresh-dependencies --continue` also reproduced a missing `groovy-bom:4.0.29` POM (its Gradle module was already catalogued).

Only those two required Maven Central metadata artifacts were added. Their exact HTTPS artifacts were cross-checked with published SHA-1 and pinned using SHA-256. Verification remains strict. The complete host task set, including instrumentation APK, lint and release, then resolved successfully in 40 seconds; most compilation tasks were up to date. This is a dependency-resolution check, not a fresh execution of every test. Actual Linux CI must still pass.

## Runtime observations and unresolved gaps

Read-only hosted checks found one application process and one shell daemon. Recent application exit history contained development updates/instrumentation and no new crash attributable to this interval. A socket replacement interrupted an immediate read response; lookup of its original request returned the original successful execution. No mutation was resubmitted. The cause of that reconnect remains under investigation.

A development memory snapshot reported app PSS 145,852 KiB, RSS 274,872 KiB and swap PSS 5,359 KiB; Android reported 4,858,425,344 available bytes and no low-memory condition. These are single debug-build observations, not an idle/battery benchmark. The combined shell probe exited 1 because its daemon-name memory filter did not match; no daemon memory measurement is claimed from that probe.

Current source findings to correct and validate include overlapping recovery triggers silently discarded by `tryLock`, discovery occurring before a busy indicator is set, stale recovery-toggle state after identity reset, broad local-network permission bundled with unrelated native permissions, and notification-listener access being confused with a disconnected service. A saved pairing timestamp is historical authorization evidence, not proof Android still trusts the key.

Still open: fully owner-operated installation/pairing, signed artifact workflow with compatible credentials, stronger reboot with every workstation ADB server stopped, network transitions, permission/key revocation, owner Inbox lifecycle, designated Calendar/SAF resources, automation/weather/semantic edge cases and a controlled quiet soak. No new reboot or signing-key upload has been performed in this pass.
