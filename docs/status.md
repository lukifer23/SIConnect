# Status

Updated November 11, 2026. This is the single current status page. Dated reports in [history](history/) keep their original versions, measurements and limits; they are not re-verification of the current source.

## Current build

| Item | Value |
| --- | --- |
| Android app | **0.6.0-dev.20 / versionCode 28**, compatible development signer (in-place update preserves enrollment, agents, grants and records) |
| Main SQLite schema | **12** (unchanged since code 26); Termux journal **1**; Clock journal **1** |
| Phone capabilities | **112** observed on the installed build (code 27 count; no capabilities were added in code 28) |
| Gateway | Source includes the pass-23 hardening; **the hosted Worker still runs the earlier deployment until it is redeployed** (see [gateway deployment](gateway-deployment.md)) |

Latest work: [pass 23](engineering-pass-23.md) (November 11 audit fixes: liveness and outcome honesty, trust boundaries, owner screens, duplication/efficiency, docs/CI). Previous checkpoint: [pass 22](engineering-pass-22.md) (managed Clock timers, private Messages reading, recovery center, editable agent names, native Codex execution).

## Verification

| Suite | Result |
| --- | --- |
| Core (JVM) | 91 pass |
| Android JVM unit | 151 pass |
| Embedded ADB | 2 pass |
| Python tools | 13 pass |
| Gateway (local SDK v1/v2 protocol, test-only device peer) | 18 pass |
| Lint | 0 errors |
| Physical, code 28 | `RuntimeDeviceTest` 13, `NotificationActionDeviceTest` 1, `FullAccessPendingContinuationDeviceTest` 1, `TermuxJournalDeviceTest` 4, `SchedulerDeviceTest` 3, `ProposalDeviceTest` 4, `AgentRenameDeviceTest` 3, `ManualAutomationDeviceTest` 2, `CalendarDeviceTest` 2, `DocumentDeviceTest` 2, `ClockJournalDeviceTest` 1, `ControlCenterDeviceTest` 1 |

The gateway test suite uses a local test peer and is never phone evidence. CI runs the host suites, lint, release debug-isolation, Python and gateway checks and a secret scan; it does not run instrumentation.

## Open acceptance work

Run these on the physical phone with the existing signer. Do not reset data, broaden grants or repeat personal sends to make a check pass.

- **Gateway redeploy.** Deploy the pass-23 Worker after checking the Wrangler account. Then verify against the hosted gateway:
  - revocation (`AGENT_REVOKED`);
  - the per-agent cap;
  - a mistyped pairing code that keeps the consent page usable.
- **Exact automation under Doze.** Schedule one exact automation with the screen off and the phone idle, and confirm it runs on time from the alarm.
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

1. Redeploy the gateway and complete the acceptance items above.
2. Introduce a shared capability builder, and owner-request/action UI holders, file by file with device re-verification. See the deferred list in [pass 23](engineering-pass-23.md#duplication-and-efficiency-pass).
3. Decide on one Android package-name validation rule. Current call sites accept different forms.
4. Measure representative retained-history workloads before designing quotas or archival. Retry identities and notification/slot claims are permanent correctness state.
5. Extend controlled startup and storage-failure coverage around the named startup stages and the Recovery center.

Previous status, roadmap and build-plan documents are preserved unchanged in [history](history/): `project-status.md`, `next-steps.md` and `build-plan.md`.
