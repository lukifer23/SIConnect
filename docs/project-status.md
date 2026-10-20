# Project status

Updated October 20, 2026. This replaces the initial core-only status.

| Subsystem | Implemented | Evidence / limits |
| --- | --- | --- |
| Core authorization | Current grant/scope/expiry/revocation, backend and kill-switch revalidation after approval | Host tests plus real device revocation test |
| Idempotency | Scoped immutable-operation fingerprint; conflicts explicit; CAS execution ownership | Concurrent host submissions; actual SQLite test |
| Durable skills | Persisted cursor, stable child IDs, pending approvals, partial/unknown outcomes, recovery | Four-step approval continuation on host; real three-step native skill on phone |
| Android runtime | Compose UI, SQLiteOpenHelper v3, Keystore secrets, permissions, lifecycle, native registry | Debug APK built, installed and launched repeatedly on Fold 7 |
| Privileged runtime | Bundled shell/root-only Binder service; shared bounded subprocess executor; optional explicit Shizuku backend | Bundled backend physically returned UID 2000, exit 0; no companion needed for this route |
| Approvals | Persisted requests, notifications, exact owner review, deny and once/standing/broad permission | Instrumented deny/approve/revoke; actual UI approved browser and Clock operations |
| Persistent permission | Exact argument/version/backend grants and explicit broad shell; owner-only SQLite changes; revoke controls | Phone UI saved broad grant; process killed and automatically restarted; new unattended shell succeeded |
| Gateway | Worker, Durable Object, official MCP v2 handler, OAuth plus private bearer auth, device enrollment, acknowledged outbound socket | Deployed in owner's personal account; real Fold connected; SDK v1/v2 local protocol tests |
| Grok Bot | Saved secure bearer credential; cloud-hosted MCP relay | Actual connector battery call correlated with phone record; browser and alarm operations executed after UI approval |
| Result tracking | requests.get / requests.cancel scoped to authenticated requesting agent | Real hosted MCP lookup succeeded; catalog now advertises 31 tools |
| Scheduling | Versioned reminder records, revision CAS, exact AlarmManager and deferrable WorkManager, same-ID update, reboot restoration | Real exact alarm delivered through policy/engine/notification in instrumentation; longer lifecycle testing pending |
| Weather | Real Open-Meteo forecast/cache/normalization | Live forecast fetched; existing next-morning reminder updated to exact timing without creating a duplicate |
| Hermes | Global HTTP MCP connection and reusable device skill | Native Hermes client discovered tools and invoked real device status; owner confirms a real self-addressed phone email was sent; credential remains outside Git |

## Physical evidence

Five production-backed Android instrumentation tests passed, including actual precise reminder delivery: native execution/persistence/idempotency, package query and skill, bundled shell identity, and approval deny/approve/revoke. An additional real-device gateway setup test passed. Official MCP SDK v2 invoked seven real phone tools through the hosted gateway. Grok Bot then independently called device status, shell identity and battery; matching REMOTE request records were read from the phone's database-backed endpoint.

Grok Bot submitted the owner's requested browser and Clock commands. Both waited for local approval, then completed with exit 0. Physical Chrome showed apple.com; Samsung Clock showed the named test alarm at the requested 6:08 PM. AlarmManager independently returned the same trigger time. This validates alarm creation, not an unattended scheduler guarantee. Private screenshots and outputs stay outside Git.

## Remaining limits

- Two explicitly authorized conversations were exercised through Grok Bot. Exact outgoing messages and Delivered state were verified on the Fold. A date-bounded message review also ran. Private content stays outside Git.
- Private bearer authentication works with this Grok Bot setup. OAuth with PKCE, discovery, CIMD/DCR, refresh and phone-created pairing is implemented/deployed. Dots reached real consent; expired consent/code blocked completion. Final Dots invocation remains unverified.
- Full device control requires an owner-authorized ADB bootstrap after phone reboot. The Mac is not a runtime server after bootstrap. Phone-only wireless pairing is not implemented.
- Shizuku integration is optional; its reboot/lifecycle behavior has not been exhaustively tested.
- Calendar/files families, disposable uninstall, extended background/reboot/network tests and long-duration resource profiling remain outstanding.
- Request/result data can contain sensitive content. Do not export raw device evidence to this public repository.

Ordinary process-death check: the owner-enabled app process was killed with its own UID (not force-stop). Android restarted the service, the Binder backend reattached, and a new arbitrary shell request succeeded without approval in 1.65 seconds. This is a single recovery measurement, not long-duration lifecycle evidence.
