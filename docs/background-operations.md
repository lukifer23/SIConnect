# Background work and connection controls

Current version, verification and open acceptance work are kept in one place: [status](status.md).

SIConnect runs saved deterministic workflows through its existing scheduler, policy engine and device-side request store. A remote model or connected computer is not required to execute a saved native workflow. Android access, current local-automation grants and any required approval still apply to every step.

## Private app execution

Native background APIs remain the first choice, including while locked when their platform permissions allow. Code 19 adds real app virtual displays with their own focus and no focus-stealing request. The owner can prohibit shared-screen actions for an agent, independently of private per-app permissions. The actual Samsung power grouping still requires the phone awake and unlocked for private UI. No wake, unlock or shared-screen fallback is requested. See [private app execution](private-app-execution.md) and [pass 17](history/engineering-pass-17.md) for tools, lifecycle and measured limits.

## Device health scans

Open **Automations**. Existing installations with the original device snapshot can choose **Upgrade health snapshot** and confirm with Android owner authentication. This upgrades the same workflow identity; it does not create an agent, enable a schedule or expand grants. Customized or disabled definitions are not automatically replaced. Runs already durably claimed retain their saved definition. Future slots not yet claimed use the current definition.

Choose **Device health snapshot** when creating or editing an automation. The scan reads real battery, memory, app-volume storage, network and thermal state without opening the screen. Results appear in run history with readable measurements; step details and raw output remain available separately. These are point-in-time readings, not a battery-life prediction or a daily performance study.

**Notify about health warnings** is off by default. If enabled, the run can post one native notification when any of these conditions is observed:

- Battery is at or below the configured percentage and is not charging.
- Available app-volume storage is below 2 GiB.
- Android reports low memory.
- Android reports severe or greater thermal throttling.

Missing data never satisfies a threshold. An unknown battery percentage is excluded. A skipped warning is not a claim that every reading is healthy. Notification access and the existing `notifications.post` grant are required only when warning notifications are enabled; saving a schedule does not grant access.

Health workflow version 3 persists condition identities and notification receipts across runs and process restarts. Choose a minimum cooldown (15 minutes to 24 hours) and whether ordinary unchanged conditions should produce reminders. New conditions alert when eligible; worsening conditions can alert after a one-minute guard. Critical conditions use a repeat interval no longer than one hour even when ordinary persistent reminders are off. Battery and storage recovery margins reduce threshold oscillation. Missing readings preserve the last known condition rather than clearing it.

The existing notification capability posts one aggregated warning and verifies the actual native notification. A confirmed permission denial does not consume the cooldown. An uncertain delivery retains its original request reference instead of replaying the same action. A later, independently scheduled observation can notify when its cooldown permits. Cleared conditions remove only the health policy's own notification. The ledger stores compact condition metadata, caps policy rows and prunes inactive rows after 30 days. Readings remain in ordinary durable execution history. Recent-history queries are bounded; full completed payloads expire only under the owner’s opt-in retention policy, and compact retry identities remain permanent.

Run history explains active and unavailable conditions, notification suppression, the next eligible reminder and unverified earlier delivery. A successful warning step can mean that no notification was needed; inspect the readable outcome rather than treating every successful step as a posted alert.

Use **Run now** for a single owner-confirmed run. It uses the background worker but does not enable or move the saved schedule. The schedule switch controls future dispatch. Cancelling an active run is a separate action; an uncertain side effect is never reported as safely cancelled.

## Scheduling limits

Workflow cards distinguish background-only work, work that requires the screen, work that may open a screen, permission/backend-dependent work and unknown metadata. This describes capability interaction, not a guarantee of unattended completion: permissions, backend availability and approval policy are checked at execution time.

Daily, weekday, weekly, monthly and fixed-interval schedules use the existing scheduler. Fixed intervals have a 15-minute minimum. Deferrable work may be delayed by Android; precise alarm timing does not guarantee immediate completion of the whole workflow. Review the saved timezone, next-run preview and missed-run policy. No polling service, screen scan or local model is added by health scans.

Native device readings do not require the privileged daemon. Remote calls require internet connectivity and the owner-enabled gateway connection. Screen interaction remains subject to lock state, service availability and screen coordination. Force-stop and Android background restrictions are separate from ordinary process recovery.

## Caller death and privileged background restoration

Code 20 ties volatile private sessions to the actual caller process’s Binder lifetime. Provider reconnection does not restore a private session or replay its original request. Native original-result recovery and owner-enabled phone-local backend replacement have real locked-phone evidence. Code-21 private-window interruption now passes on the actual awake/unlocked phone, including real display removal and original-result no-replay checks.

If Android refuses normal startup of an already enabled bridge, the local-owner runtime can submit fixed `system.restore_bridge` maintenance through the policy engine. With current advanced controls and the selected backend, it requests a ten-second startup allowance for SIConnect only, then requests service startup. Owner-only policy binds identity and origin; remote scopes cannot grant this role. The saved enablement preference is never changed. A service-start acknowledgement is separate from actual ONLINE state. See [pass 18](history/engineering-pass-18.md).

## Connection history and remote access

Open **Home → Gateway → Connection history** to inspect the latest ten retained gateway events. Connection, owner pause, interrupted result delivery and sanitized interruption categories are stored in the existing local SQLite event log. Categories distinguish credential rejection, DNS, TLS, timeout, authenticated session replacement and other transport closures. Raw exception messages, server close text, URLs, tokens and pairing codes are not copied into the new interruption events.

History follows the existing bounded event-retention policy; it is not permanent forensic storage. Opening history performs a bounded query and refreshes when a gateway event is committed. It does not start a polling collector.

**Disconnect remote gateway** stops the outbound bridge and saves the disabled preference. It retains enrollment, agents, grants, credentials and execution results. It does not cancel an action already dispatched or disable local schedules. **Connect saved gateway** resumes the same connection; enabling a paused connection requires owner authentication. It does not create a new OAuth identity.

**Block new remote actions** leaves the transport connected but denies new remote execution, including a fresh policy check immediately before dispatch. Pending remote approvals and accepted Inbox proposals remain subject to that check. It does not terminate work already executing. Existing saved reminders dispatch as local scheduled work and continue, including reminders originally created by an authorized agent. Local automations continue under their current local grants.

Open **Activity → Running** to inspect retained active request records. Owner-authenticated **Cancel this request** asks the existing engine to cancel that specific request. Cancellation cannot undo a committed side effect; an uncertain mutation retains `UNKNOWN_OUTCOME`. Neither control wipes data or changes grants.

An owner-enabled bridge also attempts restoration when the application initializes without an Activity, using the same foreground service. Android can reject a background service start; the app reports that restriction and asks the owner to open it or review background access. No permanent local ADB connection or polling recovery daemon is added.

After interrupted delivery, retrieve the original request outcome before deciding whether to retry. A reconnect does not automatically resubmit an unknown-outcome mutation. Native workflows can continue offline when their own steps do not need internet access.

## Verification scope

See [engineering pass 12](history/engineering-pass-12.md) for the exact installed build, owner-interactive execution, hosted checks and remaining acceptance gaps. A future scheduled scan was observed with the screen off and locked, without manual Run now. Its six real readings succeeded and the next 15-minute slot advanced. A second scheduled run survived an in-place development update. These observations do not establish universal Doze timing or a new reboot result. The development update uses the original signing identity; public signed distribution remains a separate release milestone.

Exact automations start their run directly from the exact alarm under a bounded wake lock, inside Android's idle allowlist window, instead of only enqueueing an ordinary WorkManager job that Doze can defer. A delayed WorkManager job remains as a durable watchdog if the process dies; a slot runs at most once.
