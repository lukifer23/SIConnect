# Capability coverage

Updated October 20, 2026. Implemented means registered production code exists. Physical evidence is listed separately. The gateway advertises 31 tools at the latest connection check; availability can change with permissions/backend state.

| Family | Implemented | Physical evidence / remaining limits |
| --- | --- | --- |
| Device diagnostics | Status, battery, storage, memory, network, thermal | Real native values through engine/SQLite and hosted MCP |
| Native apps | List, inspect, launch | Real package query; visibility follows Android |
| Privileged shell | Execute, identity, health | Real bundled UID 2000; arbitrary commands, approvals and durable broad grant exercised; cancellation/output stress pending |
| Privileged apps | List, inspect, install, uninstall | Package inspection through hosted MCP; official Play Store install via Hermes tool runtime verified; Grok install/uninstall owner-confirmed; structured disposable uninstall still pending |
| Settings/system | Get/put, dumpsys, system command through common backend | Production registration; not every mutating operation physically exercised |
| Notifications | Listener list/dismiss, native post | Policy-backed reminder notification physically delivered; listener revocation and dismissal need expanded testing |
| Alarms | Next alarm | Actual Clock test alarm and matching next-alarm state verified; creation used authorized shell/Clock UI |
| Reminders | Create, list, update, cancel; exact/deferrable scheduling | Real exact delivery in instrumentation; existing reminder updated under same identity; extended idle/reboot verification pending |
| Weather | Real forecast/cache/normalization | Live provider fetch; deterministic branching/long-running weather workflows need broader validation |
| Request control | Own request lookup and cancellation | Real hosted lookup; cancellation and unknown-outcome stress pending |
| Skills | Durable JSON skill validation/storage/execution and continuation | Real native diagnostics skill; host approval/continuation tests; expanded scheduled workflows pending |
| Calendar/files | Not implemented | Do not infer functionality from permission controls or planned schemas |

Client-specific grants are independent of capability implementation. Dots read-only native/shell tools worked, while its initial app inspection was denied by scope. The owner has since corrected the exact agent’s grant; its installation retry remains in progress. That is a permission blocker, not proof of missing implementation or a disconnected phone. See [permissions](permissions.md) and [provider compatibility](provider-compatibility.md).
