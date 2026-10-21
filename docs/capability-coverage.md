# Capability coverage

Updated October 20, 2026. Implemented means registered production code exists. Physical evidence is listed separately. The gateway advertises 31 tools at the latest connection check; availability can change with permissions/backend state.

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
| Calendar/files | Not implemented | Do not infer functionality from permission controls or planned schemas |

Client-specific grants are independent of capability implementation. Dots read-only native/shell tools worked, while its initial app inspection was denied by scope. The owner has since corrected the exact agent’s grant; its actual installation retry subsequently succeeded, with package/version/installer independently verified. That is a permission blocker, not proof of missing implementation or a disconnected phone. See [permissions](permissions.md) and [provider compatibility](provider-compatibility.md).

Pass 3 shell lifecycle stress passed on the actual bundled backend: text/stderr, stdin/environment, nonzero exit, 8 KiB truncation, binary bytes, timeout, cancellation before start and owned parent/child termination. This does not establish every maximum-size Binder/WebSocket or hostile-process case. Exact reminder interrupted-claim reconciliation passed with a disposable test notification; extended process-death/idle/reboot cases remain pending.
