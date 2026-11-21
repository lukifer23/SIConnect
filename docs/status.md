# Status

Updated November 20, 2026. This is the single current status page. Dated reports in [history](history/) keep their original versions, measurements and limits; they are not re-verification of the current source.

## Current build

| Item | Value |
| --- | --- |
| Android app | **0.6.0-dev.26 / versionCode 34**, installed in place with the existing development signer |
| Main SQLite schema | **14** (indexes per-agent request history); Termux journal **1**; Clock journal **1** |
| Phone capabilities | Build 31 had **112**. Build 32 adds one background countdown tool. |
| Gateway | Worker version `fe3d1b13-dc85-4df9-937f-76e7893e6ff4` deployed November 20 (US Central). Previous version `d540df34-ac5d-46eb-8b0c-164a58cb2c3e`. |

Build 34 answers an explicit `siconnect_connection` check over the existing phone socket. `RESPONDED` means the gateway received the matching reply. The gateway shares concurrent probes and waits up to four seconds. A timeout leaves the existing connection available for a retry. The check adds no periodic phone messages.

Build 33 shows each saved agent’s latest request on Home and in Agents. Its Activity tab reads up to 20 requests for that identity, including compacted outcomes. Home reads four global activity rows. Incoming updates can refresh the visible dashboard during sustained activity. Opening a saved OAuth invitation after leaving the app or recreating the activity requires owner authentication. The dialog hides expired codes.

Build 32 adds a native background countdown with a visible chronometer and a dedicated Android alarm notification channel. The existing `clock.timer_start` tool starts it when given a duration and no external package. Existing reminders grants also cover `reminders.start_countdown`. The separate external Clock tool waits for owner review if SIConnect is in the background. It replaces the Clock timer shell command with Android’s native SET_TIMER intent. New pairings include start, status and cancel. Existing owners can search for **timer** in the agent’s permissions. An unobserved start records an uncertain outcome and retains its original timer identity.

Build 31 adds combined backend, display use and availability filters in the phone’s tool catalog. Native calendar listing reads calendar metadata in one provider query. Attendee and reminder reads report overflow only when another row exists. See the [phone guide](phone-setup.md#start-with-native-features).

Build 30 fixes interrupted enrollment and reconciles pairing receipts missed while the phone was offline. Identical reconnects retain the gateway catalog revision. Fresh OAuth agents start with native scopes and read-only automatic approval. Existing agents keep their owner-selected permissions. See [connection recovery](troubleshooting.md#a-connection-stopped-working-after-restart).

Build 29 puts individual and bulk revocation in **Agents**. Resetting all agents revokes their phone grants in one SQLite transaction, clears stored bot credentials and the pending invitation, and retains local automations, audit records and device enrollment. The Worker checks revocation during pairing and OAuth token exchange and retains previously reported revocations when the phone's recent list changes.

The phone records gateway confirmation for each invitation. A delayed receipt identifies the exact code. An interrupted enrollment that returns “already registered” is finalized after the saved device credential and gateway handshake succeed. Home distinguishes retry, authentication failure, and a connected gateway with zero authorized agents.

The gateway issues one-hour OAuth access tokens. Each successful refresh now renews a 30-day idle window, including for an existing unexpired grant. A saved phone identity can outlive a provider login. After revoking the four original identities, the owner paired Dot and Grok Bot separately over OAuth on Workers Free. Native phone tools use the app’s Android permissions. The bundled shell backend still needs per-boot authorization.

The named Cloudflare Tunnel **SIConnect** showed **Healthy** with **No routes**. Its Mac connector starts at boot. Both providers use the existing Worker directly on the verified Free plan. This deployment has no public tunnel hostname.

## Verification

| Suite | Result |
| --- | --- |
| Core (JVM) | 91 pass |
| Android JVM unit | 152 pass |
| Embedded ADB | 2 pass |
| Python tools | 13 pass |
| Gateway (local SDK v1/v2 protocol, test-only device peer) | 22 pass |
| Lint | 0 errors |
| Hosted status, code 34 | Grok Bot called `siconnect_connection` through its existing OAuth session. The Worker reported `UNSUPPORTED` on build 33. After an in-place update over wireless debugging, the background bridge restarted and Grok reported `RESPONDED`, `phoneSessionAcknowledged: true` and `executionDispatched: false`. SIConnect’s owner UI was not opened for the test. |
| OAuth renewal, November 20 | With the phone peer offline, the SDK rotated the token and the local Worker stored a new 30-day expiry. A subsequent owner revocation blocked refresh. Hosted health, MCP challenge and OAuth discovery agreed on the existing Worker origin after deployment. |
| Hosted reboot, code 33 | After a full Fold reboot and first unlock, the phone logged a fresh gateway acknowledgement at 23:49:16 UTC without opening SIConnect. Dot and Grok Bot each called `device_battery` through their existing OAuth sessions. At 23:49:32 and 23:50:16 UTC respectively, their new request IDs matched successful phone records under their separate identities. Both reported 99%. USB supported the reboot and diagnostics. The providers used the deployed HTTPS Worker. |
| Physical, code 33 | 14 targeted instrumentation tests passed on the Fold. A schema-13 fixture retained both credential records, a revoked grant and per-agent results across upgrade and reopen. The latest lookup used both per-agent indexes and returned a quiet agent’s compacted result behind 2,000 newer requests. Backgrounding and activity recreation invalidated outstanding pairing disclosure callbacks. After the in-place install, Home showed the gateway connected and both Dot and Grok Bot with active access. |
| Hosted timer, code 32 | Grok Bot used its existing OAuth connector and `clock_timer_start` with a duration of 480 seconds. The phone recorded success under Grok’s identity and displayed the matching live SIConnect countdown while SIConnect was in the background. A separate short countdown test verified the expiry alert. |
| Physical, code 32 | The Fold ran a real disposable Samsung Clock start, observation and cancellation test with the native intent path. Duplicate request IDs dispatched once, other agents could not cancel the timer, preexisting Clock work blocked a second start and the test observed removal of its own timer notification. |
| Physical, code 31 | Installed in place on the Fold. The catalog showed 78 of 112 tools ready, including 72 native background tools. The private-display filter showed eight tools and zero ready with the current backend state. Combined filters, the empty state and clearing filters were verified on the cover screen. A synthetic Android provider test verified one calendar-list query per call and accurate overflow for calendars, attendees and reminders at 0, 1, 99, 100 and 101 rows. |
| Physical, code 30 | Both Android Keystore enrollment tests passed on the Fold, including migration, process recreation and stale completion. After an in-place update the phone acknowledged the hosted gateway without opening the app. After killing the background process, a new process and acknowledgement were observed within 6.7 seconds. All four prior remote identities remained revoked. |
| Physical, provider reboot test | Dot and Grok Bot each called `device_battery` before a full Fold reboot and again after the first Android unlock. The phone recorded a new gateway acknowledgement at 22:21:37 UTC before opening SIConnect. Grok Bot succeeded at 22:22:12 and Dot at 22:22:45. Both clients displayed new request IDs matching successful on-device records for their separate OAuth identities. The existing sessions and grants worked without a new sign-in. USB was attached for the reboot and read-only diagnostics. The provider calls used the public HTTPS Worker. |
| Physical, code 29 | Single and bulk revoke confirmed on the Fold cover screen. Four of four remote grants revoked. Verified zero encrypted bot keys, zero pending invitations, and removal of both legacy development bearer files. An old phone bearer returned HTTP 401 on the hosted MCP endpoint. The phone recorded a fresh gateway acknowledgement after an in-place update and again after background process death. The foreground service returned in 1.2 seconds in the process-death check. Both checks ran before reopening the activity. |
| Physical, code 28 | Exact automation in forced deep Doze (screen off, unplugged): started 10 ms after its slot and succeeded (`ExactDozeAcceptanceDeviceTest`); `RuntimeDeviceTest` 13, `NotificationActionDeviceTest` 1, `FullAccessPendingContinuationDeviceTest` 1, `TermuxJournalDeviceTest` 4, `SchedulerDeviceTest` 3, `ProposalDeviceTest` 4, `AgentRenameDeviceTest` 3, `ManualAutomationDeviceTest` 2, `CalendarDeviceTest` 2, `DocumentDeviceTest` 2, `ClockJournalDeviceTest` 1, `ControlCenterDeviceTest` 1 |

The gateway test suite uses a local test peer and is never phone evidence. CI runs the host suites, lint, release debug-isolation, Python and gateway checks and a secret scan; it does not run instrumentation.

## Open acceptance work

Run these on the physical phone with the existing signer. Do not reset data, broaden grants or repeat personal sends to make a check pass.

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
  - `messages.recent` limits and cleanup.
  - `codex.execute` with a real bounded request.
  - Recovery-center observations through recreation.
- **Connectivity and recovery.** Reproduce these transitions while preserving original request IDs and checking stored outcomes:
  - network loss and return, captive portal, deep Doze and a change of network across reboot;
  - socket replacement and enrollment retry.
- **Private execution while locked or off** needs legitimate companion/OEM provisioning. Normal private displays require an awake, unlocked phone; nothing wakes or bypasses keyguard.
- **Operating cost.** Collect matched quiet foreground/background/locked CPU, memory, network, wakeup and database-growth observations, then a long soak. No battery or availability improvement is established.
- **Distribution.** Validate the owner-signed workflow, compatible updates and the real package-installer journey. Signer custody and publication are owner decisions.
- **Clients.** Refresh existing clients' tool catalogs only after installed discovery is confirmed. Dedicated Codex phone-MCP pairing is separate from `codex.execute`.

Instrumentation can force-stop the app and leave the shared-screen Accessibility service disconnected. Restore it in Android Accessibility settings (SIConnect off, then on); nothing is changed automatically.

## Roadmap

1. Extend the verified two-provider reboot to network changes and longer idle periods. Exercise expiry and re-consent on disposable identities.
2. Introduce a shared capability builder, and owner-request/action UI holders, file by file with device re-verification. See the deferred list in [pass 23](engineering-pass-23.md#duplication-and-efficiency-pass).
3. Decide on one Android package-name validation rule. Current call sites accept different forms.
4. Measure representative retained-history workloads before designing quotas or archival. Retry identities and notification/slot claims are permanent correctness state.
5. Extend controlled startup and storage-failure coverage around the named startup stages and the Recovery center.

Previous status, roadmap and build-plan documents are preserved unchanged in [history](history/): `project-status.md`, `next-steps.md` and `build-plan.md`.
