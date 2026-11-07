# Permissions and owner control

## Full control and Custom access — code 23

In **Agents**, select the exact identity and enable **Full control**. Review its authority and waiting-request summary, then authenticate once with Android on this phone. This explicitly authorizes all SIConnect actions available to that agent without per-action SIConnect approval, including reading/changing/deleting apps or data, sending messages, shared-screen interaction, private app workspaces and arbitrary privileged shell commands. Operations classified `CRITICAL` also run automatically under this explicit Full control choice; the critical approval gate remains part of **Custom access**.

The choice is stored with the agent in SQLite and survives app/process restarts. Full control sets wildcard capability scope, clears explicit denies, enables shared-screen interaction and broad shell, sets automatic approval through `CRITICAL`, and removes the grant expiry. It remains configured until the owner disables it or revokes the agent. It does not rotate credentials, merge same-name agents, widen other identities or change gateway OAuth/token lifetimes. Remote pause, privileged-routing controls, actual backend availability and current revocation remain enforced.

Full control is SIConnect authorization, not Android authorization. Android fingerprint/PIN, platform permission and app sign-in prompts remain under their existing controls. Owner-only capabilities still require the local-owner principal and origin; protected native UI checks, fresh references and cooperative leases remain. Private app interaction still requires an awake, unlocked phone. Broad shell can reach beyond narrower structured-tool restrictions; Full control is deliberate broad authority, not a sandbox or a promise of unrestricted Android privileges.

Turning Full control off restores the custom permission profile saved before activation, including scopes, denies, automatic approval level, shell/standing permissions, shared-screen choice and prior expiry. Current identity, label, revocation and any later explicit expiry are preserved; disabling Full control does not reactivate a revoked agent. A restored custom expiry can already be in the past. Individual permission controls are locked while the full profile is active; switch to **Custom access** before editing them.

Enabling Full control also explicitly authorizes a bounded continuation of that same agent’s eligible requests already in `AWAITING_APPROVAL`. The owner review shows their current count and summaries. Continuation uses the original request IDs and stored arguments, rechecks device/agent identity, immutable action hash, capability version/backend, request and approval deadlines, availability and current policy, and proceeds only on a fresh `ALLOW`. It creates no replacement requests. Expired, changed or unavailable actions remain separate; denied, completed, cancelled and uncertain outcomes are never restarted or replayed by this permission change.

Full control does not keep the privileged daemon alive across reboot or establish unattended reboot recovery. The saved grant and Android/backend readiness are separate. See [phone setup](phone-setup.md) and [the private-execution contract](private-app-execution.md).

## Code-21 owner-action checkpoint — November 6, 2026

The Maps calculation in Commute settings now freezes the reviewed destination/timezone, requires Android owner authentication, and submits `commute.route` through the common engine. Activity retains its request ID and outcome even if Maps backgrounds the screen. Current-location lookup also uses engine policy and the existing Android consent. Cancelled authentication dispatches no route action. New grants are not added. A real code-21 Android UI test verifies cancellation and Activity recreation while authentication is open, with no saved-profile change or admitted route request. Biometric-success route execution remains pending. See [the audit](source-audit-2026-11-06.md).

## Pass 11 access controls

Settings shows Android authorization separately from service connection. A granted but disconnected notification listener offers Reconnect; it does not ask for the same permission again. Returning from Android Settings refreshes access and clears stale request messages. Permanently denied runtime access opens Android app permissions. The optional native permission action covers calendar/notifications; broad local-network access is requested only in advanced local discovery.

Agent permissions have one management screen in Agents. The result-detail permission link opens that same screen. Automations has one form-based editor in its primary navigation section. Raw backend and gateway credential configuration stays in collapsed advanced settings; saved enrollment is reused for normal reconnection.

## Pass 9 checkpoint — October 21, 2026

Accepting an Inbox suggestion submits the exact action under its original agent; it never adds a scope or standing grant. Review shows current denial/backend unavailability before authentication and explains when an additional execution approval is required. Current policy is rechecked after authentication and by the engine. Existing Dots/Grok/Hermes grants were not expanded. See [pass 9](engineering-pass-9.md).

SIConnect has two permission layers. Android grants access to platform features; the owner grants each authenticated bot access to SIConnect capabilities. Connecting to the gateway or authorizing Cloudflare's management connector does not grant phone-control permissions.

## Daily workflow

Home shows live connection/backend state, pending reviews, missing-access setup cards and recent activity. Agents manages exact identities; Automations manages saved workflows and runs; Activity provides status/bot filters, sorting and stored results; Settings holds Android special access and connection configuration. Opening a blocked action displays its authorization reason and can open that same agent’s permission screen. Same-name connections have a short distinguishing suffix and independent grants. A blocked-request permission button targets the exact requesting identity; use that instead of guessing between names.

In **Custom access**, Apps enables the registered `apps.*` family. The choice is saved in SQLite and audited; sensitive operations follow the custom risk/standing policy. Always allow device control is a separate broad-shell choice within Custom access, rather than Full control of every tool family. Its grant survives app/process restarts until revoked or changed; Ask before device control clears standing shell authorization. Revoking a bot blocks subsequent requests and accepted approvals. New pairings do not inherit another bot’s permissions or Full control.

A denied request stays denied after any permission change. For a deliberately new action, have the bot submit a fresh request; do not retry an uncertain mutation blindly. In Custom access, a pending approval can be reviewed through the exact stored action: read its arguments/risk/expiry, then Allow once, Always allow this action, Deny or Close. Closing the review does not approve it. Enabling Full control can instead continue eligible waiting requests under the same-ID process described above. Neither path replays terminal outcomes.

## Android access

| Access | How it is granted | Current behavior |
| --- | --- | --- |
| Notifications | Native runtime permission on supported versions; Settings afterward | Approval/reminder notifications; pending requests remain in Home even without permission |
| Notification listener | Android special-access settings | Live metadata/actions and supported RemoteInput replies; owner enabled this access on the Fold. Samsung may require Allow restricted settings in app info. Native notification permission is separate. |
| Accessibility | Android special-access settings; Home offers a missing-access card | Semantic foreground tools; enabled access and actual service connection are reported separately. Android can mark an enabled service crashed/disconnected after lifecycle testing; its real Accessibility off/on flow restores service attachment. Locked, protected and password surfaces stay excluded. Custom scopes/standing approval or explicit Full control supply SIConnect authorization. |
| Exact alarms | Android special-access screen | Required for exact reminders; an exact request does not silently become deferrable |
| Package visibility | Manifest visibility declarations | Native package results follow Android visibility limits; privileged queries require the selected backend |
| Privileged device control | Owner-authorized ADB bootstrap of bundled Binder service | No companion app required; actual UID 2000 verified; bootstrap required again after phone reboot |
| Optional Shizuku | Separate installed service and its permission flow | Explicit alternative backend; never automatically substituted |
| Calendar | Native runtime read/write permission in Settings | Nine real tools; bot Calendar scope is independently selected; never automatically chooses the first calendar |
| Selected folders | Android document-tree picker and persisted URI grant | Owner chooses each root; bots need documents scope; disconnect revokes the stored grant |
| Battery/background settings | Android/Samsung settings | Owner guidance exists; uninterrupted idle execution and battery cost are not yet established |

The app is sideloaded. Do not claim special access is an ordinary runtime dialog. Strong-biometric/device-credential confirmation is implemented for approval and permission expansion; owner confirmation passed for local ADB pairing; Inbox authentication lifecycle verification remains open. Phone-local privileged pairing/startup and saved-authorization recovery passed in pass 10. Fully owner-operated fresh setup and further revocation tests remain open.

## New bot, resume and reconnect

Pair another bot creates a fresh independent identity. Identical names do not share credentials or grants. A pending invitation survives closing the screen and ordinary process death; reopening resumes that exact invitation. Renew and copy code replaces an expired invitation while preserving its intended agent. Dismissing an invitation leaves its identity intact and a previously published code expires normally. Select an existing agent’s **Connection → Reconnect this agent** action only when you intentionally want another connection to that same identity and grant. Revoked or expired identities cannot reconnect. The legacy separately configured bearer connection retains its existing setup.

In Custom access, Managed files is a separate owner-selected bot scope for the `files.*` family. It covers SIConnect's own managed directory; it does not grant unrestricted phone storage or another app's private files. Custom-access file mutations follow the configured risk/standing policy; explicit Full control authorizes this family automatically. Installation and ordinary pairing do not widen existing agents.

Capabilities now distinguish background execution from actions that use the shared screen. Live runtime reports screen/keyguard/device-lock state. Hermes package inspection, disposable approved-shell file operations and exact reminder delivery were verified while locked/screen off. This does not make visible-app navigation a background action or guarantee Doze connectivity indefinitely.

Pass 4 owner authentication uses Android's actual strong biometric or screen-lock credential prompt, with no cached approval. Cancelling leaves the request/grant unchanged. The callback binds the selected request/hash or agent action and engine authorization is rechecked afterward. Emergency stop, denial, revocation and permission reduction remain immediately available. Ordinary background calls under existing standing grants do not prompt again.

Settings → Connected folders → Choose folder opens Android’s actual picker after owner authentication. A root handle grants no extra bot scope; permit Selected folders separately. Access revocation/provider disappearance is reported explicitly. Calendar and document tests require a deliberately designated disposable resource; do not select personal data just to prove writes.

## Custom access: automatic screen actions in one app

In Agents, select the exact identity, restore Custom access if needed, allow the UI interaction family under **Permissions**, then open **App access**. Choose the intended installed app or enter its exact Android package and select **Allow screen actions**. Android owner authentication confirms this expansion. It persists an agent/package/backend-bound standing grant for semantic clicks and text entry; it does not grant arbitrary shell or another app. These actions can include sends/deletes inside the chosen app, so enable them deliberately. Remove the allowance to restore ordinary approval behavior for that package.

An exact approval for an earlier `ui.click` can become stale when owner review changes the foreground window. This is expected protection. For a permitted unattended workflow, have the bot acquire its lease and inspect fresh nodes after review; do not relax stale checks or repeatedly replay an old reference. Background native capabilities do not need semantic screen permission.

## Automation owner workflow

Automations offers typed inputs and native date/time pickers, recurrence and timezone, next-run previews, exact/deferrable timing, missed-run policy and a durable run history. Save commits the configuration after owner authentication; it does not silently grant the local-automation identity new capabilities. Commute weather exposes an explicit Allow local weather automation action when its required scopes are missing. Wake fallback and departure time must be configured; no Clock alarm is changed.

Run now queues a separate one-shot execution and leaves the saved schedule untouched. Inspect its existing run for approval waits, results or uncertainty; Cancel this run targets that run. Disabling a schedule affects future dispatch, while active runs remain separately visible. Draft fields are saveable across ordinary Activity recreation; save the automation to make its configuration durable.

## Published notification actions

Android notification-listener access is separate from the bot’s SIConnect authorization. In Custom access, grant the Notifications family and configure its approval/standing policy; Full control already includes this family and runs its available actions without SIConnect approval. `notifications.actions` returns bounded live action references without bodies or suggested replies; optional labels may contain app-supplied sensitive text. Authentication-required actions and SIConnect owner-management actions are refused. RemoteInput replies work only for supported published mutable text-input intents; `DISPATCHED` is not proof of recipient delivery. Do not resend an uncertain reply blindly.

## Action Inbox and app picker

Allow the proposals capability family for the exact agent through Agents if you want it to submit follow-ups. `proposals.create` requires a stable per-agent idempotency key, registered target capability, exact schema-valid arguments and an expiration within seven days. Arguments are limited to 16 KiB. Source text is untrusted display data; provide minimal references rather than entire conversations. Remote list/get/withdraw are scoped to that same identity. There is no remote acceptance API.

On Home, open the agent-suggestions card when proposals are waiting, select a proposal, review the exact action/risk/expiry and choose Accept proposal or Dismiss. Acceptance requires fresh Android owner authentication. It creates one persistent linked request under the proposing agent’s current permissions; under Custom access a high-risk action may then require **Review execution approval** through the exact-action approval screen. An agent already in Full control runs available linked actions under that full profile without a second SIConnect action approval. Acceptance does not enable missing scopes, override revocation or save standing permission. Inspect the linked execution for outcomes, verification and uncertainty; do not resubmit an unknown mutation. Unstarted accepted actions expire no later than fifteen minutes after acceptance or their earlier proposal expiry.

In Agents → Custom access → App access, **Choose installed app** searches current-user PackageManager metadata and displays exact package/version and any standing allowance. Selecting a row does not grant access. **Allow screen actions** still requires owner authentication and the UI family scope. Protected owner/permission/biometric surfaces remain excluded. Manual package entry is an advanced fallback. A saved standing allowance does not prove accessibility is connected or that Android exposes every app control.


## Owner-only maintenance

Code 20 explicitly marks owner-only capabilities in the common policy engine. Both the local-owner principal and trusted local-owner origin are required; wildcard scopes, standing grants or a scheduled/remote origin do not substitute. This covers private emergency cleanup and saved-bridge startup assistance. Agent permission lists omit owner-only operations. Bridge assistance uses existing advanced control and the selected backend for a fixed ten-second own-package allowance, without enabling a paused gateway or persisting a battery exemption. No original remote agent receives new grants.

## Private workspaces and shared-screen restriction

Code 19 introduced the Private app workspaces family and owner-authenticated per-app allowances bound to the selected privileged backend. Those controls now belong to Custom access. Explicit Full control includes private and shared-screen authority without separate app allowances; installation and ordinary pairing do not add it automatically. Disabling Allow shared-screen actions limits the agent to declared native background and private-display operations; it also blocks arbitrary shell despite broad shell permission. Re-enabling shared-screen access requires owner authentication. Permission reduction and emergency cleanup stay available immediately. Actual app UI requires an awake, unlocked phone; private display isolation does not create a separate app account or storage profile. See [the complete contract](private-app-execution.md).

## Android 17 local discovery

Advanced control offers Android 17’s NSD system service picker as a permission-minimizing route. Manual/broad local discovery requires a contextual ACCESS_LOCAL_NETWORK runtime request and rechecks revocation before use. Manifest declaration alone is insufficient. Runtime networking authorization is separate from Android Wireless debugging pairing, local owner authentication, remote-agent grants and special-access services. Actual local pairing passed with runtime local-network access granted. Physical denial/revocation and system-picker-only acceptance remain open. See [phone setup](phone-setup.md).

## Background scans and remote pause

Health scan notifications are opt-in. A read-only scan does not request notification or broad local-network access. Upgrading the original snapshot requires owner authentication and preserves the workflow ID, existing schedules and grants. Remote pause restricts the bridge without revoking agents; resuming a saved paused connection requires owner authentication. See [background operations](background-operations.md).


Termux commands additionally require Android’s `com.termux.permission.RUN_COMMAND` and Termux’s owner-controlled external-app setting. The per-agent `termux.*` scope and existing arbitrary-shell policy apply. Full control covers that scope automatically but does not manufacture the platform permission or another app’s setting. Execution uses Termux’s own UID and file/network access; it is independent of privileged shell. Result/history queries remain agent-bound. [Details](termux-integration.md).
