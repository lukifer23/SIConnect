# Status

Updated November 20, 2026. This is the single current status page. Dated reports in [history](history/) keep their original versions, measurements and limits; they are not re-verification of the current source.

## Current build

| Item | Value |
| --- | --- |
| Android app | **0.6.0-dev.22 / versionCode 30**, installed in place with the existing development signer |
| Main SQLite schema | **12** (unchanged since code 26); Termux journal **1**; Clock journal **1** |
| Phone capabilities | **112** last counted on build 27. Builds 28 through 30 add no capabilities. |
| Gateway | Worker version `644d4851-eb75-4748-a4f1-3d8cc219b46e` deployed November 20. Previous version `036ae97d-a0d6-4dd5-89b7-bdf8bbc27e49`. |

Build 30 fixes interrupted enrollment and reconciles pairing receipts missed while the phone was offline. Identical reconnects retain the gateway catalog revision. Fresh OAuth agents start with native scopes and read-only automatic approval. Existing agents keep their owner-selected permissions. See [connection recovery](troubleshooting.md#a-connection-stopped-working-after-restart).

Build 29 puts individual and bulk revocation in **Agents**. Resetting all agents revokes their phone grants in one SQLite transaction, clears stored bot credentials and the pending invitation, and retains local automations, audit records and device enrollment. The Worker checks revocation during pairing and OAuth token exchange and retains previously reported revocations when the phone's recent list changes.

The phone records gateway confirmation for each invitation. A delayed receipt identifies the exact code. An interrupted enrollment that returns “already registered” is finalized after the saved device credential and gateway handshake succeed. Home distinguishes retry, authentication failure, and a connected gateway with zero authorized agents.

The gateway still issues one-hour OAuth access tokens and sets a fixed 30-day refresh-token lifetime. A saved phone grant can outlive a provider login. The reset removed the old identities before any fresh provider sign-in was attempted. Native phone tools use the app’s Android permissions. The bundled shell backend still needs per-boot authorization.

## Verification

| Suite | Result |
| --- | --- |
| Core (JVM) | 91 pass |
| Android JVM unit | 152 pass |
| Embedded ADB | 2 pass |
| Python tools | 13 pass |
| Gateway (local SDK v1/v2 protocol, test-only device peer) | 21 pass |
| Lint | 0 errors |
| Physical, code 30 | Both Android Keystore enrollment tests passed on the Fold, including migration, process recreation and stale completion. After an in-place update the phone acknowledged the hosted gateway without opening the app. After killing the background process, a new process and acknowledgement were observed within 6.7 seconds. All four prior remote identities remained revoked. |
| Physical, code 29 | Single and bulk revoke confirmed on the Fold cover screen. Four of four remote grants revoked. Verified zero encrypted bot keys, zero pending invitations, and removal of both legacy development bearer files. An old phone bearer returned HTTP 401 on the hosted MCP endpoint. The phone recorded a fresh gateway acknowledgement after an in-place update and again after background process death. The foreground service returned in 1.2 seconds in the process-death check. Both checks ran before reopening the activity. |
| Physical, code 28 | Exact automation in forced deep Doze (screen off, unplugged): started 10 ms after its slot and succeeded (`ExactDozeAcceptanceDeviceTest`); `RuntimeDeviceTest` 13, `NotificationActionDeviceTest` 1, `FullAccessPendingContinuationDeviceTest` 1, `TermuxJournalDeviceTest` 4, `SchedulerDeviceTest` 3, `ProposalDeviceTest` 4, `AgentRenameDeviceTest` 3, `ManualAutomationDeviceTest` 2, `CalendarDeviceTest` 2, `DocumentDeviceTest` 2, `ClockJournalDeviceTest` 1, `ControlCenterDeviceTest` 1 |

The gateway test suite uses a local test peer and is never phone evidence. CI runs the host suites, lint, release debug-isolation, Python and gateway checks and a secret scan; it does not run instrumentation.

## Open acceptance work

Run these on the physical phone with the existing signer. Do not reset data, broaden grants or repeat personal sends to make a check pass.

- **Tunnel cutover.** The November 20 hosted checks used the existing Worker address. The Cloudflare dashboard subsequently showed a named tunnel, **SIConnect**, at **Waiting for your Tunnel to connect**. The CLI identity could list resources but could not read that tunnel by ID. No published tunnel hostname or connected origin host has been verified. Validate that hostname and OAuth discovery before saving fresh Dot and Grok Bot connections.
- **Hosted gateway behavior with a real client.** Use a real client credential to check, against the deployed Worker:
  - revocation (`AGENT_REVOKED`);
  - the per-agent cap;
  - a mistyped pairing code that keeps the consent page usable.

  Unauthenticated health, MCP 401 and OAuth metadata were checked after the deploy.
- **Owner screens.** Check these by hand across folded/unfolded, rotation, split window, large font and light/dark:
  - the dialog error placement and approval labels;
  - Action Inbox stability;
  - automation delete;
  - the custom-workflow grant;
  - genuine biometric cancellation.
- **Native journeys.** Test Calendar writes against a dedicated writable test calendar only. Reuse the selected disposable SAF root. Verify one configured weather/reminder journey and Maps departure/arrival end to end.
- **Build-27 items still pending:**
  - Clock timer start/observe/cancel on a disposable timer.
  - `messages.recent` limits and cleanup.
  - `codex.execute` with a real bounded request.
  - Recovery-center observations through recreation.
- **Connectivity and recovery.** Reproduce these transitions while preserving original request IDs and checking stored outcomes:
  - usable network, offline, captive portal, Doze and reboot;
  - socket replacement and enrollment retry.
- **Private execution while locked or off** needs legitimate companion/OEM provisioning. Normal private displays require an awake, unlocked phone; nothing wakes or bypasses keyguard.
- **Operating cost.** Collect matched quiet foreground/background/locked CPU, memory, network, wakeup and database-growth observations, then a long soak. No battery or availability improvement is established.
- **Distribution.** Validate the owner-signed workflow, compatible updates and the real package-installer journey. Signer custody and publication are owner decisions.
- **Clients.** Refresh existing clients' tool catalogs only after installed discovery is confirmed. Dedicated Codex phone-MCP pairing is separate from `codex.execute`.

Instrumentation can force-stop the app and leave the shared-screen Accessibility service disconnected. Restore it in Android Accessibility settings (SIConnect off, then on); nothing is changed automatically.

## Roadmap

1. Complete provider sign-in, reboot and network-transition acceptance with separately authorized test identities.
2. Introduce a shared capability builder, and owner-request/action UI holders, file by file with device re-verification. See the deferred list in [pass 23](engineering-pass-23.md#duplication-and-efficiency-pass).
3. Decide on one Android package-name validation rule. Current call sites accept different forms.
4. Measure representative retained-history workloads before designing quotas or archival. Retry identities and notification/slot claims are permanent correctness state.
5. Extend controlled startup and storage-failure coverage around the named startup stages and the Recovery center.

Previous status, roadmap and build-plan documents are preserved unchanged in [history](history/): `project-status.md`, `next-steps.md` and `build-plan.md`.
