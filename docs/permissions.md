# Permissions and owner control

## Pass 9 checkpoint — October 21, 2026

Accepting an Inbox suggestion submits the exact action under its original agent; it never adds a scope or standing grant. Review shows current denial/backend unavailability before authentication and explains when an additional execution approval is required. Current policy is rechecked after authentication and by the engine. Existing Dots/Grok/Hermes grants were not expanded. See [pass 9](engineering-pass-9.md).

SIConnect has two permission layers. Android grants access to platform features; the owner grants each authenticated bot access to SIConnect capabilities. Connecting to the gateway or authorizing Cloudflare's management connector does not grant phone-control permissions.

## Daily workflow

Home shows live connection/backend state, pending reviews, missing-access setup cards and recent activity. Agents manages exact identities; Automations manages saved workflows and runs; Activity provides status/bot filters, sorting and stored results; Settings holds Android special access and connection configuration. Opening a blocked action displays its authorization reason and can open that same agent’s permission screen. Same-name connections have a short distinguishing suffix and independent grants. A blocked-request permission button targets the exact requesting identity; use that instead of guessing between names.

Apps enables the registered `apps.*` family. The choice is saved in SQLite and audited. Sensitive operations still follow risk policy. Always allow device control is a separate explicit broad-shell choice; its grant survives app process restarts until revoked or changed. Ask before device control clears standing shell authorization. Revoking a bot blocks subsequent requests and accepted approvals. New pairings do not inherit another bot's permissions.

A denied request stays denied. After changing permission, ask the bot to submit a new request; do not retry an uncertain mutation blindly. A pending approval is different: open the exact stored action, read its arguments/risk/expiry, then Allow once, Always allow this action, Deny or Close. Closing the review does not approve it. Critical policy still applies.

## Android access

| Access | How it is granted | Current behavior |
| --- | --- | --- |
| Notifications | Native runtime permission on supported versions; Settings afterward | Approval/reminder notifications; pending requests remain in Home even without permission |
| Notification listener | Android special-access settings | Live metadata/actions and supported RemoteInput replies; owner enabled this access on the Fold. Samsung may require Allow restricted settings in app info. Native notification permission is separate. |
| Accessibility | Android special-access settings; Home offers a missing-access card | Semantic foreground tools; owner initially enabled access on the Fold. Android later marked the service crashed/disconnected after lifecycle testing; owner off/on restoration is pending. Locked, protected and password surfaces are excluded; scope and standing approval remain separate. |
| Exact alarms | Android special-access screen | Required for exact reminders; an exact request does not silently become deferrable |
| Package visibility | Manifest visibility declarations | Native package results follow Android visibility limits; privileged queries require the selected backend |
| Privileged device control | Owner-authorized ADB bootstrap of bundled Binder service | No companion app required; actual UID 2000 verified; bootstrap required again after phone reboot |
| Optional Shizuku | Separate installed service and its permission flow | Explicit alternative backend; never automatically substituted |
| Calendar | Native runtime read/write permission in Settings | Nine real tools; bot Calendar scope is independently selected; never automatically chooses the first calendar |
| Selected folders | Android document-tree picker and persisted URI grant | Owner chooses each root; bots need documents scope; disconnect revokes the stored grant |
| Battery/background settings | Android/Samsung settings | Owner guidance exists; uninterrupted idle execution and battery cost are not yet established |

The app is sideloaded. Do not claim special access is an ordinary runtime dialog. Strong-biometric/device-credential confirmation is implemented for approval and permission expansion; owner-interactive validation is pending. Phone-only privileged pairing remains unfinished.

## New bot, resume and reconnect

Pair another bot creates a fresh independent identity. Identical names do not share credentials or grants. A pending invitation survives closing the screen and ordinary process death; reopening resumes that exact invitation. Renew and copy code replaces an expired invitation while preserving its intended agent. Dismissing an invitation leaves its identity intact and a previously published code expires normally. Select an existing bot card's Reconnect this bot action only when you intentionally want another connection to that same identity and grant. Revoked or expired identities cannot reconnect. The legacy separately configured bearer connection retains its existing setup.

Managed files is a separate owner-selected bot scope for the new `files.*` family. It covers SIConnect's own managed directory; it does not grant unrestricted phone storage or another app's private files. File mutations still follow risk/approval policy. New and existing agents do not gain this family automatically.

Capabilities now distinguish background execution from actions that use the shared screen. Live runtime reports screen/keyguard/device-lock state. Hermes package inspection, disposable approved-shell file operations and exact reminder delivery were verified while locked/screen off. This does not make visible-app navigation a background action or guarantee Doze connectivity indefinitely.

Pass 4 owner authentication uses Android's actual strong biometric or screen-lock credential prompt, with no cached approval. Cancelling leaves the request/grant unchanged. The callback binds the selected request/hash or agent action and engine authorization is rechecked afterward. Emergency stop, denial, revocation and permission reduction remain immediately available. Ordinary background calls under existing standing grants do not prompt again.

Settings → Choose a folder opens Android's actual picker after owner authentication. A root handle grants no extra bot scope; permit Selected folders separately. Access revocation/provider disappearance is reported explicitly. Calendar and document tests require a deliberately designated disposable resource; do not select personal data just to prove writes.

## Automatic screen actions in one app

In Agents, select the exact identity, allow the UI interaction family, then review Automatic screen actions. Enter the intended Android package and choose Allow clicks and text in this app. Android owner authentication confirms this expansion. It persists an agent/package/backend-bound standing grant for semantic clicks and text entry; it does not grant arbitrary shell or another app. These actions can include sends/deletes inside the chosen app, so enable them deliberately. Remove the allowance to restore ordinary approval behavior for that package.

An exact approval for an earlier `ui.click` can become stale when owner review changes the foreground window. This is expected protection. For a permitted unattended workflow, have the bot acquire its lease and inspect fresh nodes after review; do not relax stale checks or repeatedly replay an old reference. Background native capabilities do not need semantic screen permission.

## Automation owner workflow

Automations offers typed inputs and native date/time pickers, recurrence and timezone, next-run previews, exact/deferrable timing, missed-run policy and a durable run history. Save commits the configuration after owner authentication; it does not silently grant the local-automation identity new capabilities. Commute weather exposes an explicit Allow local weather automation action when its required scopes are missing. Wake fallback and departure time must be configured; no Clock alarm is changed.

Run now queues a separate one-shot execution and leaves the saved schedule untouched. Inspect its existing run for approval waits, results or uncertainty; Cancel this run targets that run. Disabling a schedule affects future dispatch, while active runs remain separately visible. Draft fields are saveable across ordinary Activity recreation; save the automation to make its configuration durable.

## Published notification actions

Grant the intended bot's Notifications family separately from Android notification-listener access. `notifications.actions` returns bounded live action references without bodies or suggested replies; optional labels may contain app-supplied sensitive text. High-impact invocation/reply still requires the configured approval or standing policy. Authentication-required actions and SIConnect owner-management actions are refused. RemoteInput replies work only for supported published mutable text-input intents; `DISPATCHED` is not proof of recipient delivery. Do not resend an uncertain reply blindly.

## Action Inbox and app picker

Allow the proposals capability family for the exact agent through Agents if you want it to submit follow-ups. `proposals.create` requires a stable per-agent idempotency key, registered target capability, exact schema-valid arguments and an expiration within seven days. Arguments are limited to 16 KiB. Source text is untrusted display data; provide minimal references rather than entire conversations. Remote list/get/withdraw are scoped to that same identity. There is no remote acceptance API.

On Home, open **Review Action Inbox**, select a proposal, review the exact action/risk/expiry and choose Accept proposal or Dismiss. Acceptance requires fresh Android owner authentication. It creates one persistent linked request under the proposing agent’s current permissions; a high-risk action may then require **Review execution approval** through the existing exact-action approval screen. Acceptance does not enable missing scopes, override revocation or save standing permission. Inspect the linked execution for outcomes, verification and uncertainty; do not resubmit an unknown mutation. Unstarted accepted actions expire no later than fifteen minutes after acceptance or their earlier proposal expiry.

In Agents → Automatic screen actions, **Choose installed app** searches current-user PackageManager metadata and displays exact package/version and any standing allowance. Selecting a row does not grant access. **Allow clicks and text in this app** still requires owner authentication and the UI family scope. Protected owner/permission/biometric surfaces remain excluded. Manual package entry is an advanced fallback. A saved standing allowance does not prove accessibility is connected or that Android exposes every app control.
