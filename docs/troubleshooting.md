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

`apps.inspect_privileged` currently returns a bounded raw Android package dump. For packages with large resolver tables, truncation can occur before version and per-user state. `SUCCEEDED`/exit 0 means the dump command ran; it does not prove that omitted metadata was verified. A typed bounded result remains required. The real Dots install was verified using a bounded privileged package query and an independent user-0 check, rather than treating a truncated dump as complete evidence.

Reopening an owner review must consume each incoming intent rather than permanently suppressing a request ID. The installed activity now supports closing and reopening the same stored action, including a terminal result. A bounded review layout keeps its action controls outside the scrollable text region. Instrumentation that inspects Compose dialogs should traverse their actual accessibility window children; framework find-by-text can omit the virtual nodes on the tested runtime.

Pairing a new bot never selects a same-name identity. To preserve permissions, choose the exact bot card and Reconnect this bot. Resume a pending invitation rather than creating repeated identities after a network error. Expired invitation renewal keeps its intended agent ID. A stored credential decryption error is a recovery issue; SIConnect preserves the encrypted data and does not silently create a replacement device secret.

After Android instrumentation finishes, relaunch SIConnect and wait for its acknowledged connection before a hosted MCP probe. A DEVICE_OFFLINE response is accurate while the target process/socket is down. Keep this distinct from scope denial and from an unknown-outcome action that requires original-request lookup.
