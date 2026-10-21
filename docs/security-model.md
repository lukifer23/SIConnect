# Security model

## October 21, 2026 checkpoint

Action Inbox trust boundary: proposal text/source references are untrusted display data. There is no remote accept/approve tool. Local acceptance requires fresh owner authentication and submits under the proposing identity, without changing scopes/standing grants or selecting LOCAL_OWNER origin. Existing revocation, emergency stop and exact-action approval remain authoritative. Proposal IDs/results are agent-scoped. Immutable action/version hashes, argument/text budgets, expiration, CAS resolution and stable execution IDs prevent silent replacement or duplicate acceptance. Broad-shell and trusted-gateway limitations are unchanged.

## Trust boundaries

| Component | Trusted for | Not trusted for |
| --- | --- | --- |
| Owner (on-device UI) | Policy, grants, approvals, emergency stop | n/a |
| Android app (policy engine) | Final authorization and execution | n/a |
| Gateway | Authenticating agents and routing; no offline mutation queue | Authorization decisions, approval claims, device policy |
| External agents | Choosing tools within their grant | Anything outside their grant. Their interpretation of outside content is never authorization. |
| Content (notifications, messages, web, files) | Data only | Instructions of any kind |

## Threats and mitigations

| Threat | Mitigation |
| --- | --- |
| Unauthorized MCP client | MCP requires a validated OAuth access token or configured private bearer. The phone independently validates the forwarded credential and current grant, revocation and expiry. |
| Stolen agent credential | Per-agent grants limit the damage. Credentials can be revoked on the phone. Controlled per-agent credential rotation is not yet exposed. The emergency stop denies new remote work. Gateway bearer expiration is configured independently. |
| Replay | Request IDs are unique, idempotency keys deduplicate retries, every request has an expiry, and expired requests are rejected even if delivered late. |
| Gateway compromise | The gateway can forge an agent's identity, but only for agents that exist on the device, and only within those agents' grants. There is no remote approval/grant API. However, an already broad-shell principal can drive unlocked on-device UI; the Pass 4 UI now invokes Android owner authentication for consequential approvals and permission expansion; this is not a complete isolation guarantee for an arbitrary shell controller. Plaintext is visible to the gateway, so we do not claim end-to-end encryption. |
| Approval substitution | An approval is bound to the SHA-256 of capability, version and canonical arguments. Execution uses only the stored arguments, so a different command needs a new approval. |
| Prompt injection via content | Content is data, never authority to change policy. Capability arguments remain schema-validated and exact approvals are hash-bound; policy changes are local-owner-only and not exposed as remote tools. |
| Malicious skill definitions | Skills are validated (known capabilities, no nested skills, no forward references). Each step runs under the invoker's grant. Installation is currently a local management action; every executed step passes the policy engine under the invoking identity. Remote skill-install/run capabilities are not implemented. |
| Command injection in typed capabilities | Typed capabilities build argument vectors (`argv`) rather than shell strings, and validate package names and paths. |
| Dangerous shell chaining | Arbitrary shell needs approval by default. Standing shell grants are exact-string matches. `BROAD` mode is an explicit owner choice, documented as able to bypass narrower rules. No keyword filtering is presented as a safeguard. |
| Sensitive output disclosure | Output is bounded and truncation is reported. Logs never store tokens or keys. Message bodies and notification text are not persisted in logs by default. Diagnostic exports are redacted and must be reviewed before sharing. |
| Lost or stolen phone | App data is excluded from backups. Secrets are wrapped with an Android Keystore key. Current agent revocation and the emergency stop cut agent authority; credential/device recovery remains an owner-controlled operation. |
| Unintended privilege persistence | Bundled shell authority ends at reboot and requires owner-authorized bootstrap. Optional Shizuku has its own startup lifecycle. |
| Excessive tool authority | Default OAuth pairing grants only listed capabilities, with LOW_IMPACT auto-approval and approval-required shell. Capabilities outside that scope are denied, not automatically escalated to approval. The owner must deliberately widen a grant. |

## Emergency stop

The emergency stop is a switch in the app (Home and management UI) that sets `DeviceControls.remoteExecutionEnabled = false`.

- It is checked at submit time and again before an approved action executes.
- A separate switch, `privilegedEnabled`, disables privileged routing.
- Neither switch needs another computer.

## Owner standing permissions

Always allow this action persists an agent-bound exact canonical argument/version hash and selected backend in SQLite. Shell requests match such grants before the ordinary shell approval gate; they cannot match a generic empty constrained grant. Critical risk, current scopes, denylist, revocation, expiry, backend availability and emergency stop remain authoritative. Always allow device control is a separate deliberate BROAD shell grant, saved only from the local owner UI. It also grants that agent access to its own request status. The requesting agent cannot change policy through gateway JSON. The owner can switch back to asking each time.

The bot key copied by the owner UI is Keystore-encrypted at rest, masked from the app and marked sensitive on Android clipboard. Debug migration reads the prior private development credential only if it still authenticates. Request and result storage may contain sensitive shell input/output; raw export must remain private.

## OAuth phone pairing

The owner creates invitations only from the phone UI. Invoking tools remotely cannot create agents or change their grants. Each new OAuth pairing starts with approval-gated shell access, irrespective of another client's standing permission. Invitation hashes are device-scoped; consumption is a Durable Object transaction and replay tombstones prevent re-publication during the code lifetime. Pending phone data is Keystore-encrypted, and the server's invitation payload is encrypted using the one-use code. Neither codes nor credentials are audit-event fields.

The maintained OAuth provider enforces the authorization-code/PKCE and resource-token boundary; its browser-bound consent helpers provide CSRF and framing protection. Expiration errors are resolved by starting a fresh consent session, never by relaxing these checks. Token validity at the gateway does not replace the current phone grant and revocation checks. Access-token and refresh lifetimes do not make an owner grant temporary.

## Permission management and presentation

Owner changes to Apps-family scope and revocation are SQLite transactions with audit events. They are not remote tools. An app-scope grant permits policy evaluation of app operations; it does not remove high/critical risk approval requirements. A broad shell grant deliberately permits arbitrary shell within the chosen critical policy, so narrower structured-tool scopes do not make broad shell harmless.

A blocked-request permission link uses the stored request's authenticated agent ID. Same-name clients are displayed separately; no name-based grant migration or automatic grant copying occurs. New pairings never search by label. Reconnection reuses a credential only after explicit selection of its stable agent ID; renewal targets the persisted invitation ID. Existing distinct clients remain distinct. Keystore-wrapped credential persistence is committed before the invitation is published.

Owner approval uses immutable PendingIntents and non-exported components. The pending notification is ongoing until resolution/expiry, but Android controls the transient heads-up banner duration. The in-app review stays open until explicit Close, displays expiry/resolved state and still requires engine revalidation. Notification failure never means approval. Pass 4 invokes Android strong-biometric/device-credential confirmation for these approvals and grant expansion. Cancellation/recreation discards the action; owner-interactive verification remains pending.

Pass 3 indexes credential lookup while retaining current grant/revocation/expiry checks and constant-time hash comparison. Ambiguous duplicate credential rows are rejected. Request transitions and audit inserts share one production SQLite transaction. Managed-file tools reject absolute/dot-segment paths and canonical escapes, serialize their file operations, bound reads/copies/hashes and verify local mutations. They are governed by the existing registry/policy/engine and explicit Files-family scope. File data and original approval arguments can be sensitive in private request storage; never publish device databases or raw exports.

Screen-free execution is not an authorization exception. Locked-phone operations still require the current grant/approval and actual backend. Native background tools do not unlock the phone. Reboot bootstrap and first-unlock platform restrictions remain. Universal background Messages/RCS/Gmail sending is not implemented.

## Owner presence and broad UI control

The local-owner entry point is protected from forged remote JSON, but a UI event is not proof that a human touched the device. An agent with deliberately granted arbitrary privileged shell can use Android input automation on an unlocked phone, including SIConnect management/review UI. Pass 4 adds actual Android strong-biometric/device-credential confirmation to consequential approvals, capability expansion, broad-shell enablement, credential copying and selected management actions. There is no authentication cache. This still requires physical owner validation and further management-surface review; do not claim complete protection against every broad-shell UI controller. Ordinary background operations continue under existing standing grants. Broad shell remains a powerful intentional authority, not a sandbox constrained by narrower tool-family switches.

Cooperative UI leases bind stable agent identity, target package and request/trace references in SQLite. They prevent cooperative structured-action contention but are not a sandbox for arbitrary `input` commands. File mutexes likewise serialize SIConnect calls, not unrelated privileged processes; managed-path canonical checks do not constitute protection against all privileged symlink replacement races.

SAF roots are locally selected content-tree grants with opaque handles; relative document resolution never accepts a caller-created file URI. Arbitrary document providers cannot promise atomic writes or remote cloud synchronization. Native Calendar Provider mutations verify local readback only.

Notification deduplication persists an agent-scoped key-to-original-request claim before posting. An interrupted original is reconciled, never blindly reposted. Changed arguments conflict. Reminder keys are likewise per-agent and never re-arm delivered/unknown/cancelled records merely through create.

## Semantic standing access and notification dispatch

UI-family scope and per-package automatic screen actions are distinct. The owner can authenticate to allow one agent's `ui.click`/`ui.set_text` in one chosen nonprotected package. This standing permission can include sending or deleting through that application's controls. Current scope, denylist, revocation, availability, screen lease and fresh-reference checks still apply. Removing the allowance does not silently change unrelated bots or broad shell.

The semantic backend refuses locked-device interaction, protected SIConnect/system-permission/biometric surfaces, password nodes and their descendants. Node references bind authenticated agent, package/window, event generation, fingerprint and monotonic lifetime; state changes invalidate them. Opening owner approval changes the screen and can correctly make an earlier semantic action stale. Broad privileged shell remains outside this cooperative protection, so no complete isolation from deliberately granted arbitrary shell is claimed.

Notification actions are independently revalidated against live key, source package, notification version, action reference and PendingIntent creator UID/package. SIConnect-created intents and authentication-required actions cannot be invoked through these capabilities. Routine discovery excludes bodies and generated reply choices; action labels require explicit opt-in. Published third-party receivers still control their own behavior, so recipient identity and external delivery are not independently verified. Never automatically resend following an uncertain reply.

Requested semantic snapshots and explicit outgoing reply arguments may be sensitive in the authoritative private request database. Routine accessibility/notification lifecycle events do not log captured text. Keep databases, raw results, screenshots and fixture/device receipts outside the public repository. The disposable RemoteInput fixture has no network/SMS/contact permission and exposes only test counters/digests; uninstall it after coordinated validation.
