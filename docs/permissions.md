# Permissions and owner control

SIConnect has two permission layers. Android grants access to platform features; the owner grants each authenticated bot access to SIConnect capabilities. Connecting to the gateway or authorizing Cloudflare's management connector does not grant phone-control permissions.

## Daily workflow

Home shows connection state, pending action reviews, blocked app-permission requests, bot cards and recent activity. Tap a bot to manage it. Activity → View all provides status/bot filters and sorting; opening a blocked action displays its authorization reason and can open the same bot’s permission screen. Same-name connections have a short distinguishing suffix and independent grants. A blocked-request permission button targets the exact requesting identity; use that instead of guessing between names.

Apps enables the registered `apps.*` family. The choice is saved in SQLite and audited. Sensitive operations still follow risk policy. Always allow device control is a separate explicit broad-shell choice; its grant survives app process restarts until revoked or changed. Ask before device control clears standing shell authorization. Revoking a bot blocks subsequent requests and accepted approvals. New pairings do not inherit another bot's permissions.

A denied request stays denied. After changing permission, ask the bot to submit a new request; do not retry an uncertain mutation blindly. A pending approval is different: open the exact stored action, read its arguments/risk/expiry, then Allow once, Always allow this action, Deny or Close. Closing the review does not approve it. Critical policy still applies.

## Android access

| Access | How it is granted | Current behavior |
| --- | --- | --- |
| Notifications | Native runtime permission on supported versions; Settings afterward | Approval/reminder notifications; pending requests remain in Home even without permission |
| Notification listener | Android special-access settings | Actual listener-backed capability availability; Samsung may require Allow restricted settings in app info |
| Exact alarms | Android special-access screen | Required for exact reminders; an exact request does not silently become deferrable |
| Package visibility | Manifest visibility declarations | Native package results follow Android visibility limits; privileged queries require the selected backend |
| Privileged device control | Owner-authorized ADB bootstrap of bundled Binder service | No companion app required; actual UID 2000 verified; bootstrap required again after phone reboot |
| Optional Shizuku | Separate installed service and its permission flow | Explicit alternative backend; never automatically substituted |
| Calendar | Runtime access is exposed in advanced settings | Calendar capability family is not implemented; granting permission does not add tools |
| Battery/background settings | Android/Samsung settings | Owner guidance exists; uninterrupted idle execution and battery cost are not yet established |

The app is sideloaded. Do not claim special access is an ordinary runtime dialog. Biometric approval and phone-only privileged pairing remain unfinished.
