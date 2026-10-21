# Troubleshooting

## October 21, 2026 checkpoint

If semantic access is configured but unavailable, inspect `ui.status.serviceState`: DISABLED_BY_OWNER, ENABLED_BUT_DISCONNECTED, CONNECTED_BUT_SCREEN_LOCKED, CONNECTED_WITH_NO_FOREGROUND_WINDOW or CONNECTED_READY. The ordinary app cannot inspect Android’s private crash list (`androidServiceCrashStateKnown=false`). In this pass, ADB exit history proved instrumentation force-stops and `dumpsys accessibility` showed the enabled service crashed/unbound. Restore through Android Accessibility → Installed apps → SIConnect, off/on; no secure-settings write is performed automatically. Finish instrumentation before restoration. Native background capabilities remain independent. The normal-process fixture requires an unlocked screen and rejects lock/off before session setup.

## Bot is connected but an action does nothing

Open Home and inspect the exact recent request. CONNECTED means the phone acknowledged its gateway connection; it does not mean every bot has every permission. `DENIED` with `agent_scope` means the request reached the phone and was blocked before approval. Use the blocked app-request permission card, enable the intended scope for that bot and submit a fresh request. The previous denied record will not turn into success.

Two connections can share a display name. Their permissions remain independent. Use the short suffix, last request and request-linked permission button. Do not duplicate a plugin or re-pair repeatedly to fix a missing scope. Cloudflare's management connector is separate from the SIConnect phone connector.

## A prompt disappears

Android controls how long a notification heads-up banner is visible. Open the notification shade or SIConnect Home to review the durable pending request. The updated in-app review requires explicit Close and shows expiry/resolution. Bot permission review remains open after a switch is changed. Home action review retains its request reference across activity recreation. Do not run another agent's phone UI automation while the owner reviews a dialog. Expired, revoked, denied and cancelled actions cannot execute merely because their review remains visible.

If the updated screen still vanishes without interaction, capture the approximate time and the screen type; correlate Activity lifecycle, stored request transitions and backend/connection events. Do not recreate the request merely to make the prompt reappear.

## OAuth code or browser error

A pairing invitation and the browser's consent session each expire. Restart consent from the existing client and create/renew the phone's code if needed. Use the same browser session for its consent form. Do not remove browser binding or CSRF checks. Pairing stores credentials encrypted with Android Keystore; public logs should contain neither codes nor tokens.

## Device control unavailable after reboot or update

Native tools can still work. After an authorized ADB connection, run `python3 tools/start_builtin.py --serial <serial>`; add `--restart` after an APK update. This starts the bundled privileged authority; it is not a Mac-side runtime server. The phone-only bootstrap path is not implemented. Never claim an app-sandbox process is privileged shell.

## Slow execution

Prefer typed tools and request status lookup over repeated screen capture. A command containing sleeps, UI dumping and parsing reports the combined duration. Use bounded XML capture, verify before acting, and stop when the requested state is reached. Do not resend messages or repeat installs solely because a transport response was lost. See [performance](performance.md).

## Package inspection output is truncated

`apps.inspect_privileged` currently returns a bounded raw Android package dump. For packages with large resolver tables, truncation can occur before version and per-user state. `SUCCEEDED`/exit 0 means the dump command ran; it does not prove that omitted metadata was verified. Use the implemented apps.inspect_detailed for typed bounded metadata. The real Dots install was verified using a bounded privileged package query and an independent user-0 check, rather than treating a truncated dump as complete evidence.

Reopening an owner review must consume each incoming intent rather than permanently suppressing a request ID. The installed activity now supports closing and reopening the same stored action, including a terminal result. A bounded review layout keeps its action controls outside the scrollable text region. Instrumentation that inspects Compose dialogs should traverse their actual accessibility window children; framework find-by-text can omit the virtual nodes on the tested runtime.

Pairing a new bot never selects a same-name identity. To preserve permissions, choose the exact bot card and Reconnect this bot. Resume a pending invitation rather than creating repeated identities after a network error. Expired invitation renewal keeps its intended agent ID. A stored credential decryption error is a recovery issue; SIConnect preserves the encrypted data and does not silently create a replacement device secret.

After Android instrumentation finishes, relaunch SIConnect and wait for its acknowledged connection before a hosted MCP probe. A DEVICE_OFFLINE response is accurate while the target process/socket is down. Keep this distinct from scope denial and from an unknown-outcome action that requires original-request lookup.

If an idle/locked request encounters DEVICE_REPLACED_UNKNOWN_OUTCOME, retain its request ID and use `requests_get` after reconnection. This was exercised through the actual Hermes connection: the original read had completed durably. Do not resend a message, installation or other mutation to discover its result.

New managed-file tools are confined to SIConnect storage. A Files-family scope denial is separate from Android storage permissions. Weather LOCATION_REQUIRED means configure or explicitly supply a confirmed location; it does not select an arbitrary default. The owner-enabled gateway service now restores on APK replacement/boot; if Android blocks that start, inspect `gateway.recovery.blocked` and open SIConnect. Privileged reboot bootstrap remains a separate limitation.

## Automation waiting or blocked

AWAITING_APPROVAL has no finish time and retains the original slot/run. Approve or deny the stored child request, then inspect the same run. UNKNOWN_OUTCOME pauses future dispatch; reconcile the original action before re-enabling anything. BLOCKED includes dispatch or missing-skill errors; startup reconciliation repairs dispatch from SQLite without inventing execution success. Editing an active slot changes future configuration; its frozen skill/input snapshot remains the owned run.

## Build cache was cleaned during development

If Gradle/Kotlin references a removed transforms snapshot or reports missing known dependencies immediately after cache cleanup, stop the daemon with `./gradlew --stop` and rebuild with the pinned versions. Do not remove dependencies, change toolchains or disable lint to conceal stale cache references. Avoid deleting caches/logs while active builds/measurements are using them.

## Large result or lost response

Use the original request ID with requests_get. If too large, requests_output returns bounded base64 JSON-byte chunks with offsets/hash. If HTTP vanished before an ID arrived, requests_recent lists only this authenticated agent's recent metadata. Inspect and correlate the original action; never infer a clean failure or resend a mutation merely because response delivery failed.

## Appearance and layout

Settings offers System, Light and Dark. Android font scaling remains active. Dialog explanations scroll independently of action buttons, and the main content accounts for system bars/keyboard. Folded/unfolded and large-font verification remains a separate device check. Bridge health → Check now collects one native sample; it does not start continuous polling or establish battery drain.


If the shell daemon is alive but the app loses privileged availability after process death, older builds could have lost the remote owner BinderProxy to garbage collection, silently unlinking its death callback. The corrected daemon retains both proxy and recipient and reattaches after actual owner-process death. Upgrade the app and restart the bundled daemon once to load the correction; later ordinary app recovery should not require another bootstrap. Reboot still destroys shell authority. Ending instrumentation is a separate lifecycle case: it can stop the connection service, so restore the owner-enabled service before evaluating production connection recovery.

## Accessibility or notification setup card remains

Home's cards reflect actual service availability, not merely a previously tapped setup button. Open the indicated Android special-access screen and enable SIConnect. Sideload restrictions may require Allow restricted settings in app info first. Return to SIConnect and inspect `ui.status` or current capability availability. Enabling ordinary notification permission does not enable notification-listener access. Both services initially connected and their missing-access cards disappeared on the tested Fold; no silent permission grant is performed. Later lifecycle testing left Accessibility enabled in Settings but disconnected, and Android diagnostics listed it as crashed. Enabled in Settings is not the same as bound/operational.

## A semantic action reports stale, busy or protected

`RESOURCE_BUSY` means another cooperative screen lease owns the target; unrelated background tools can continue. Use the current lease status and wait for release, or cancel your own active request. Mutations require the calling agent's existing lease and the correct target package/window.

`STALE_NODE_REFERENCE`, `WINDOW_CHANGED` or `TARGET_NOT_FOREGROUND` means inspect the actual screen again before preparing another action. Owner approval itself can change the window. For intentionally unattended operation, the owner can permit semantic click/text for one app in Agents, separately from UI scope, then the bot inspects fresh state. Never weaken reference checks to replay an old click. `PROTECTED_UI` and `SCREEN_LOCKED` require appropriate owner/manual interaction; this backend does not unlock the phone or automate biometric, permission or owner-management controls.

A running Android instrumentation session can interfere with accessibility-service binding. Its skipped fixture is not a passing semantic test. In the observed normal-process retry, Android still listed the enabled service as crashed/disconnected; no new app fatal exception was found, so the exact crash mechanism remains unconfirmed. Open Android Settings → Accessibility → installed apps/services → SIConnect, turn the service off and back on yourself, then return and check `ui.status.connected`. SIConnect does not silently toggle this authorization or claim automatic recovery. The opt-in `tools/semantic_ui_smoke.py` harness exercises an isolated debug fixture through authenticated ADB-forwarded loopback in the normal app process; it remains blocked until the owner restores service binding, and its current verification status is recorded in device testing. Do not run it while the owner is using the screen or reviewing a real action.

## Notification action or reply is refused

Discover current actions with the exact key and originating package. Notification updates/removal and listener/process restarts invalidate previous references. `NOTIFICATION_CHANGED` or `ACTION_CHANGED` requires fresh inspection, not blind replay. `OWNER_AUTHENTICATION_REQUIRED` and `LOCAL_OWNER_ACTION_PROTECTED` require the owner to use the real local interface. `ACTION_CREATOR_MISMATCH`, immutable reply intents, Activity replies and unsupported inputs are reported explicitly; there is no hidden alternate send route.

`DISPATCHED` means Android accepted the PendingIntent. Recipient identity, application send state and external delivery remain unverified. On connection loss retrieve the original request through requests.get; never submit another reply just to discover its result. The separate [notification fixture](../tools/notification_fixture/README.md) validates disposable Android broadcast receipts without sending a personal message.

## Automation editing or Run now

Saved schedule state and an active/manual run are separate. Saveable form fields survive ordinary Activity recreation but must be saved before they become a durable schedule. Run now queues one independent run without moving the saved next time. Reopen that run to inspect steps/results or cancel it. A queued cancellation remains cancelled before worker start. A cancelled side-effecting action may remain `UNKNOWN_OUTCOME`; inspect the original request instead of assuming nothing happened. Missing local-automation scope and Android special access are displayed independently.
