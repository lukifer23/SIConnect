# Background work and connection controls

SIConnect runs saved deterministic workflows through its existing scheduler, policy engine and device-side request store. A remote model or connected computer is not required to execute a saved native workflow. Android access, current local-automation grants and any required approval still apply to every step.

## Device health scans

Open **Automations**. Existing installations with the original device snapshot can choose **Upgrade health snapshot** and confirm with Android owner authentication. This upgrades the same workflow identity; it does not create an agent, enable a schedule or expand grants. Customized or disabled definitions are not automatically replaced. Runs already queued retain their saved definition.

Choose **Device health snapshot** when creating or editing an automation. The scan reads real battery, memory, app-volume storage, network and thermal state without opening the screen. Results appear in run history with readable measurements; step details and raw output remain available separately. These are point-in-time readings, not a battery-life prediction or a daily performance study.

**Notify about health warnings** is off by default. If enabled, the run can post one native notification when any of these conditions is observed:

- Battery is at or below the configured percentage and is not charging.
- Available app-volume storage is below 2 GiB.
- Android reports low memory.
- Android reports severe or greater thermal throttling.

Missing data never satisfies a threshold. An unknown battery percentage is excluded. A skipped warning is not a claim that every reading is healthy. Notification access and the existing `notifications.post` grant are required only when warning notifications are enabled; saving a schedule does not grant access.

The existing durable child-request identities prevent replay of the same step within a run. Different scheduled runs are separate observations and can each warn; this is not a cross-run notification deduplication promise.

Use **Run now** for a single owner-confirmed run. It uses the background worker but does not enable or move the saved schedule. The schedule switch controls future dispatch. Cancelling an active run is a separate action; an uncertain side effect is never reported as safely cancelled.

## Scheduling limits

Workflow cards say whether their registered capabilities run without opening the screen or use the phone's screen. This describes capability interaction, not a guarantee of unattended completion: permissions, backend availability and approval policy are checked at execution time.

Daily, weekday, weekly, monthly and fixed-interval schedules use the existing scheduler. Fixed intervals have a 15-minute minimum. Deferrable work may be delayed by Android; precise alarm timing does not guarantee immediate completion of the whole workflow. Review the saved timezone, next-run preview and missed-run policy. No polling service, screen scan or local model is added by health scans.

Native device readings do not require the privileged daemon. Remote calls require internet connectivity and the owner-enabled gateway connection. Screen interaction remains subject to lock state, service availability and screen coordination. Force-stop and Android background restrictions are separate from ordinary process recovery.

## Connection history and remote access

Open **Home → Gateway → Connection history** to inspect the latest ten retained gateway events. Connection, owner pause, interrupted result delivery and sanitized interruption categories are stored in the existing local SQLite event log. Categories distinguish credential rejection, DNS, TLS, timeout, authenticated session replacement and other transport closures. Raw exception messages, server close text, URLs, tokens and pairing codes are not copied into the new interruption events.

History follows the existing bounded event-retention policy; it is not permanent forensic storage. Opening history performs a bounded query and refreshes when a gateway event is committed. It does not start a polling collector.

**Pause remote access** stops the outbound bridge and saves the disabled preference. It retains enrollment, agents, grants, credentials and execution results. It does not cancel an action already dispatched or disable local schedules. **Reconnect existing gateway** resumes the same connection; enabling a paused connection requires owner authentication. It does not create a new OAuth identity.

After interrupted delivery, retrieve the original request outcome before deciding whether to retry. A reconnect does not automatically resubmit an unknown-outcome mutation. Native workflows can continue offline when their own steps do not need internet access.

## Verification scope

See [engineering pass 11](engineering-pass-11.md) for the exact installed build, owner-interactive execution, hosted checks and remaining acceptance gaps. Manual worker execution does not establish a new screen-off scheduled-run or reboot result. The development update uses the original signing identity; public signed distribution remains a separate release milestone.
