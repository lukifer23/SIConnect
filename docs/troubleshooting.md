# Troubleshooting

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
