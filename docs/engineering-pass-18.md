# Engineering pass 18 — caller lifetime and background recovery

October 27, 2026. Current source and installed development build: **0.6.0-dev.12 / code 20**, SQLite **11**, **100 phone capabilities / 101 MCP tools** including gateway status. Existing Maps, retention and private-display work remains uncommitted. [Pass 17](engineering-pass-17.md) records code-19 Calculator/Maps acceptance; this pass distinguishes later implementation from completed device checks.

## Changes

Workspace calls now carry a Binder lifetime token owned by the actual calling app process. The backend records its kernel caller PID, links token death, invalidates queued work when that caller ends and releases only that caller's displays. Sessions also bind a server-generated caller generation; a new app process cannot resume old session references. A running launch that finishes after caller death is cleaned before the next queued operation. Provider attachment lifetime remains responsible for reconnecting the authority, rather than defining private-workspace ownership. The Shizuku service protocol version advances to 6; actual workspace validation in this pass remains on ADB_SERVICE.

This follows an intermittent failed process-death run against the earlier provider-based cleanup. Earlier runs sometimes passed, but the repeated failure was retained as evidence rather than treated as acceptance. Code 20's new lifetime protocol passes the locked-safe Binder/engine checks. Its real private-window process-death rerun still requires an awake, unlocked phone; the phone has relocked. No lock credential or physical power policy was changed to bypass that precondition.

Android also sometimes refuses background foreground-service startup. The existing owner-enabled bridge first uses the normal Android start path. If that specific restriction occurs and advanced control plus the selected backend are available, a serialized, rate-limited local-owner request goes through the original execution engine. New `system.restore_bridge` requests an actual ten-second Android startup allowance for SIConnect's own package, then requests the saved service start. Package, duration and command are fixed, with no caller arguments. It neither enables a paused gateway nor changes persistent battery exemptions. Backend selection and current owner controls are checked again before service startup. Acceptance of service startup remains separate from actual gateway acknowledgement.

`ownerOnly` is now an explicit capability/policy property: both the local-owner principal and trusted LOCAL_OWNER origin are required before standing grants or broad permissions can authorize the operation. It applies to private emergency cleanup and bridge maintenance. Owner-only operations are excluded from remote-agent permission-family lists; live metadata identifies them. Existing scopes, credentials and grants are not expanded. Capability implementations retain their own role checks as a second boundary.

The existing phone-local recovery observer already responds to lost Binder authority when the owner enabled saved recovery. This pass adds real acceptance with host bootstrap disabled rather than duplicating that mechanism.

## Device evidence

Before the final central owner-role refinement, a **33-case locked-safe code-20 batch passed without skips**: 24 foundation cases, seven private/bridge policy cases, real managed-file round trip and the previously unmet unplugged health check. The health case uses actual native readings and a deliberately high test threshold; its clearly labeled disposable notification is observed once, suppressed on repeat and removed. The actual battery was unplugged at 71%; no battery state was faked. The two owner-maintenance cases also pass separately: real selected UID-2000 execution without wake/control/grant changes, and rejection of a remote principal/caller-supplied package/duration. The central owner-role change is followed by a fresh verification run; consult current status for its final counts.

The reproducible [recovery harness](../tools/private_workspace_recovery.py) uses a real expiring principal, authenticated existing debug endpoint, actual process death and production SQLite/Binder services. It restores only its disposable resources and the saved owner-enabled bridge. It does not launch a physical Activity, dismiss keyguard or force-stop/uninstall the main application.

Completed code-20 scenarios while the phone was locked and noninteractive:

- Kill the normal app process after an actual native battery read. The app returns under a new PID, the privileged process remains the same, database integrity is OK, and retrying the immutable key returns the exact original request/result. The gateway returns ONLINE without an Activity.
- Stop the actual bridge service while leaving its owner preference enabled, idle for 35 seconds, then reattach an authorized backend. The same app process returns ONLINE without an Activity or physical wake. An earlier run observed one actual startup-assistance event; a later normal-start run needed no assistance. The owner-maintenance test separately executes the policy-routed allowance under actual UID 2000.
- Kill the actual privileged daemon while the ordinary app and bridge remain active. Existing owner-enabled phone-local recovery replaces the daemon using saved authorization and embedded ADB. **No host bootstrap is called in this scenario.** The app PID stays the same, native readings succeed, replacement Binder availability and SQLite integrity are verified, and physical foreground/lock/interactive state remain unchanged. The Mac injects the failure and observes; it does not launch the replacement.

Private-window owner/backend death and queued-open/revocation cases are implemented as real-device tests but remain pending while the phone is locked. They are not reported as code-20 passes. Code-19 Calculator `2 + 3 = 5`, JPEG capture, current/future Maps, ownership, stale-reference and expiry acceptance remains historical pass-17 evidence.

Initial harness failures included an older same-version test APK with narrower fixture scopes, transient process identity counts, service-start restrictions, and attempting to query a cached app's debug socket after stopping its foreground service. The harness now installs its current helper, waits for settled identities, observes the actual AMS service record before/after stopping it, and records cleanup failures rather than fabricating a completed run. Partial reports remain private evidence.

## Resource observation

A retained **301.074-second / 11-sample** background development observation had ten valid continuous PID/start/boot/version intervals. App CPU increased **0.41 seconds over 299.955 observed seconds (0.1367% of one core)**; daemon counters did not advance at the available 100-Hz resolution. App PSS was **56,166–60,373 KiB**. App importance was foreground-service (125) and keyguard showing was true in every sample. No app test jobs were issued during this interval; ADB observation and other device activity still limit interpretation.

The original 64-KiB power probe truncated Samsung's 143,809-byte dump, so its unknown power fields remain unknown in that saved interval. The collector now bounds that probe at 256 KiB, records probe bounds and advances methodology revision to 3. A complete actual probe identifies Dozing/unpowered. This is a short resource observation, not a matched optimization comparison, battery study or long availability soak.

## Platform limit

Read-only inspection of the actual Samsung services artifact strengthens the locked/off UI boundary. DisplayManagerFlags binds separate_timeouts to a synthetic supplier that directly returns Boolean.TRUE, and FlagState caches the value. No DeviceConfig/aconfig/property read supplies this consumer. Restarting the same artifact therefore does not establish a different value. The shell's aflags listing requires root; no override or root attempt was made.

Combined with the observed empty-name group-0 mapping, this is a compiled platform policy rather than a missing app-level permission. Legitimate companion virtual-device or OEM ComputerControl provisioning, or an appropriately authorized framework change, needs its own implementation and actual acceptance. No flag, keyguard, framework or kernel change was made. Native background work and the awake/unlocked private-display path remain the implemented surfaces.

## Remaining work

Finish the new caller-lifetime private-window and queued-open/revocation acceptance when the phone is actually awake/unlocked. Continue controlled network/captive/Doze/reboot and in-flight mutation boundaries, dedicated Calendar/SAF consent/resource journeys, owner-authentication/Fold/font/recreation review, longer matched resource measurements and production signing/distribution. Phone-local daemon-crash recovery is now observed under existing authorization; this does not establish universal reboot recovery or recovery after Android revokes pairing/network permission.
