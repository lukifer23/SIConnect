# Capability coverage

Updated October 20, 2026. Implemented means registered production code exists. Physical evidence is listed separately. The catalog is generated from the installed registry; availability and scope are separate. Use `device.capabilities` for current state. Tool count changes with installed builds.

| Family | Implemented | Physical evidence / remaining limits |
| --- | --- | --- |
| Device diagnostics | Status, battery, storage, memory, network, thermal, on-demand runtime resource counters | Real native values through engine/SQLite and hosted MCP |
| Native apps | List, inspect, detailed structured inspection, launch | Detailed inspection physically checked against SIConnect, Android system package and a nonexistent package; malformed names rejected. Primary user 0 only; disabled/disposable package cases remain pending. |
| Privileged shell | Execute, identity, health | Real bundled UID 2000; arbitrary commands, approvals and durable broad grant exercised; cancellation/output stress pending |
| Privileged apps | List, inspect, install, uninstall | Package inspection through hosted MCP; official Play Store installs via Hermes tool runtime and actual Dots verified; Grok install/uninstall owner-confirmed; Dots structured test-app uninstall waited for actual owner approval and removal was independently verified; isolated disposable-APK stress coverage still pending |
| Settings/system | Get/put, dumpsys, system command through common backend | Production registration; not every mutating operation physically exercised |
| Notifications | Listener list/dismiss, native post | Policy-backed reminder notification physically delivered; listener revocation and dismissal need expanded testing |
| Alarms | Next alarm | Actual Clock test alarm and matching next-alarm state verified; creation used authorized shell/Clock UI |
| Reminders | Create, list, update, cancel; exact/deferrable scheduling | Real exact delivery in instrumentation; existing reminder updated under same identity; extended idle/reboot verification pending |
| Weather | Real forecast/cache/normalization | Live provider fetch; deterministic branching/long-running weather workflows need broader validation |
| Request control | Own request lookup and cancellation | Real hosted lookup; cancellation and unknown-outcome stress pending |
| Skills | Durable JSON skill validation/storage/execution and continuation | Real native diagnostics skill; host approval/continuation tests; expanded scheduled workflows pending |
| Managed files | List, metadata, bounded UTF-8/base64 read/write, SHA-256, mkdir, copy, move/rename, delete empty directory/file | Physical engine/authenticated fixture round trip passed, including traversal rejection and postconditions. App-owned storage only. SAF is a separate owner-selected document family; MediaStore and other apps’ private storage are not implemented. |
| Calendar | Nine Provider-backed tools: calendars, bounded occurrences/event metadata, create/update/delete, reminder list/add/remove | Permission state physically checked. Dedicated-calendar write round trip awaits owner setup. No server-sync/invitation-delivery claim; recurring timing edits/exceptions unsupported. |
| Selected documents | Nine SAF tools: roots/list/metadata/read/hash/create/write/rename/delete | Real persisted grants and provider implementation; selected test-root round trip awaits owner selection. Copy/move and MediaStore are not implemented. |
| Shared screen | Four cooperative lease tools; structured launch coordination | Two disposable physical agents proved contention rejection while background status continued. Arbitrary broad shell bypasses this cooperative boundary. |
| Automations | Durable run snapshots/reconciliation, typed recurrence, explicit missed policy | Actual deferrable recurrence and crash-boundary SQLite fixtures passed. Reboot/live kill/DST device scenarios are not all verified. |

Client-specific grants are independent of capability implementation. Dots read-only native/shell tools worked, while its initial app inspection was denied by scope. The owner has since corrected the exact agent’s grant; its actual installation retry subsequently succeeded, with package/version/installer independently verified. That is a permission blocker, not proof of missing implementation or a disconnected phone. See [permissions](permissions.md) and [provider compatibility](provider-compatibility.md).

Pass 3 shell lifecycle stress passed on the actual bundled backend: text/stderr, stdin/environment, nonzero exit, 8 KiB truncation, binary bytes, timeout, cancellation before start and owned parent/child termination. This does not establish every maximum-size Binder/WebSocket or hostile-process case. Exact reminder interrupted-claim reconciliation passed with a disposable test notification; extended process-death/idle/reboot cases remain pending.

Hermes's locked-phone file check used the actual privileged shell and a unique disposable `/data/local/tmp` file; do not confuse that evidence with locked-screen validation of every native `files.*` operation. Native managed-file operations were physically exercised separately through authenticated engine calls. `apps.launch` is SCREEN; arbitrary shell is MAY_OPEN_SCREEN; native diagnostics/files/reminders/weather are BACKGROUND. These describe interaction, not a guarantee of uninterrupted idle/reboot execution.

Weather windows now accept date/timezone/local start/end hours, use Unix hourly instants, account for preceding-hour precipitation and report missing-data completeness. A real explicit four-hour provider window was fetched; the declarative commute skill now exists and is installed; real forecast/notification/reminder workflow validation is recorded separately in current status.

Pass 4's installed catalog has 69 tools. New shell reliable-pipe transport passed a physical 128 KiB stdout plus 128 KiB stderr case, including invalid UTF-8 binary mode. `requests.output` provides authenticated chunks; `requests.recent` provides only the caller's recent metadata. Legacy compatibility limits and incomplete stress cases remain documented.
