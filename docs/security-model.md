# Security model

## Full control authorization — code 23

The local owner can now authenticate once to enable an explicit, persistent **Full control** profile for one exact remote identity. The stored `AgentGrant.fullControl` flag is separate from ordinary `BROAD` shell permission. Canonical Full control uses wildcard scope, no denies, `CRITICAL` automatic approval, broad shell, shared-screen access and no grant expiry. All SIConnect actions available to that agent run without per-action SIConnect approval, including actions classified critical. Android platform authorization, the local-owner-only gate, backend availability, revocation and remote/privileged pause checks remain enforced. The earlier critical-always-approval rule described by historical custom-access checkpoints does not apply to explicit Full control.

Full control can read/change/delete apps or data, send content and execute arbitrary privileged shell. A stolen full-control credential therefore carries that broad authority; neither narrow family switches nor cooperative UI leases sandbox it. Owner authentication protects enabling/changing authority, rather than creating a new prompt for each authorized action. Native protected UI/password checks and Android fingerprint/PIN, runtime/special-access permissions and app sign-in are not suppressed. Broad shell retains the selected Android backend’s actual power and can reach beyond narrower structured-tool rules.

The profile and pre-activation custom `AgentGrant` snapshot are persisted together in the existing agent JSON/SQLite transaction with an audit event. No credential or identity replacement is required. Full control has no automatic grant expiry and survives app/process restarts until disabled or the agent is revoked. Switching off restores the previous custom permission/expiry profile, preserves current identity/label/revocation and any later explicit expiry, and clears full authority; it does not resurrect a revoked identity. Individual family, shell and app allowance mutations are refused until Custom access is restored. Separate gateway OAuth/token lifetimes and Android backend/reboot lifecycles remain separate from this saved choice.

The enable review also covers a bounded same-agent continuation of existing unexpired `AWAITING_APPROVAL` actions. A local-owner helper rechecks the original device/agent, state, immutable hash, version/backend, schema, both deadlines and fresh policy/availability. Only current `ALLOW` decisions proceed through the engine’s original request ID and stored arguments. It creates no new request or retry identity and never replays denied, completed, cancelled, expired or unknown-outcome work. Full control does not add a remote approval/grant endpoint, and the engine repeats its normal dispatch-time checks. See [owner controls](permissions.md#full-control-and-custom-access--code-23).

## Code-21 checkpoint — November 6, 2026

Code 21 adds actual-dispatch revalidation and caller-bounded workflow deadlines. The owner Maps button now authenticates and submits through the common durable engine; current-location lookup also uses engine policy. These changes add no remote owner authority or new standing grants. The real Android commute cancellation/recreation regression now passes without admitting a route or saving the draft; biometric-success execution remains open. See [the source audit](source-audit-2026-11-06.md) for exact evidence and remaining acceptance.

## Pass 9 checkpoint — October 21, 2026

Inbox acceptance now revalidates original-agent policy, availability and emergency stop after authentication, before allocating a request link. Preflight is guidance, never a permission grant. Transient retry requires a successful missing-record lookup and reuses the same ID; unknown outcomes are reconciled. The optional debug Inbox harness has no acceptance endpoint, uses expiring isolated credentials and is excluded from the minified release. Existing credentials/grants were unchanged. See [pass 9](engineering-pass-9.md).

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
| Stolen agent credential | Current per-agent authority applies: a Full control credential has broad automatic authority without per-action SIConnect approval. Credentials can be revoked on the phone and remote pause denies new remote work. Controlled per-agent credential rotation is not yet exposed. Gateway bearer expiration is independent of the saved phone profile. |
| Replay | Request IDs are unique, idempotency keys deduplicate retries, every request has an expiry, and expired requests are rejected even if delivered late. |
| Gateway compromise | The gateway can forge an agent's identity, but only for agents that exist on the device, and only within those agents' grants. There is no remote approval/grant API. However, an already broad-shell principal can drive unlocked on-device UI; the Pass 4 UI now invokes Android owner authentication for consequential approvals and permission expansion; this is not a complete isolation guarantee for an arbitrary shell controller. Plaintext is visible to the gateway, so we do not claim end-to-end encryption. |
| Approval substitution | When approval is required, it is bound to the SHA-256 of capability, version and canonical arguments. A different command cannot reuse that approval. Full control’s explicit waiting-request continuation also executes only the original stored operation and ID. |
| Prompt injection via content | Content cannot change the phone grant: policy changes are local-owner-only and not exposed as remote tools. Capability arguments remain schema-validated, and required exact approvals are hash-bound. Full control deliberately permits automatic actions within its broad grant; safe handling of outside content by that trusted agent remains consequential. |
| Malicious skill definitions | Skills are validated (known capabilities, no nested skills even through tool-name aliases, no forward references). Earlier step output (notification or message text, command output) may never be interpolated into a step whose capability runs an interpreted command; this is checked at install and again at run time for previously installed definitions, which fail with `UNSAFE_COMMAND_INTERPOLATION` without executing. Pass such data through typed arguments. Each step runs under the invoker's grant. Installation is currently a local management action; every executed step passes the policy engine under the invoking identity. Remote skill-install/run capabilities are not implemented. |
| Command injection in typed capabilities | Typed capabilities build argument vectors (`argv`) rather than shell strings, and validate package names and paths. |
| Dangerous shell chaining | Custom access requires shell approval by default and supports exact standing commands or explicit `BROAD` shell. Full control separately authorizes arbitrary shell automatically, including classified-critical actions. Both broad choices can reach beyond narrower rules; no keyword filtering is presented as a safeguard. |
| Sensitive output disclosure | Output is bounded and truncation is reported. Logs never store tokens or keys. Message bodies and notification text are not persisted in logs by default. Diagnostic exports are redacted and must be reviewed before sharing. |
| Lost or stolen phone | App data is excluded from backups. Secrets are wrapped with an Android Keystore key. Current agent revocation and the emergency stop cut agent authority; credential/device recovery remains an owner-controlled operation. |
| Impersonated privileged service | Only shell/root callers may attach the bundled privileged Binder, and the first live attachment wins: a second shell-uid caller (another app's Shizuku service, an `adb shell`) cannot replace a running authority to receive agent commands or forge results. Rejections are audited as `privileged.attach_rejected`. Restart replaces the authority only after the old daemon dies. A shell-uid process that attaches first, before the genuine daemon, is outside this protection: shell identity is the trust boundary. |
| Unintended privilege persistence | Bundled shell authority ends at reboot and requires owner-authorized bootstrap. Optional Shizuku has its own startup lifecycle. |
| Excessive tool authority | Default OAuth pairing grants only listed capabilities, with LOW_IMPACT auto-approval and approval-required shell. Capabilities outside that scope are denied, not automatically escalated to approval. The owner must deliberately widen a grant. |

## Emergency stop

The emergency stop is a switch in the app (Home and management UI) that sets `DeviceControls.remoteExecutionEnabled = false`.

- It is checked at submit time, execution admission and actual coroutine dispatch, including already-approved actions waiting for a dispatcher. It does not terminate already-dispatched work.
- A separate switch, `privilegedEnabled`, disables privileged routing.
- Neither switch needs another computer.

## Owner standing permissions

Always allow this action persists an agent-bound exact canonical argument/version hash and selected backend in SQLite. Shell requests match such grants before the ordinary shell approval gate; they cannot match a generic empty constrained grant. In Custom access, classified-critical actions still require review regardless of standing grants. Current scopes, denylist, revocation, configured expiry, backend availability and emergency stop remain authoritative. Explicit Full control instead supplies wildcard/automatic authority with no grant expiry; its other eligibility gates remain. Always allow device control is a separate deliberate BROAD shell grant, saved only from the local owner UI. It also grants that agent access to its own request status. The requesting agent cannot change policy through gateway JSON. The owner can switch back to asking each time.

The bot key copied by the owner UI is Keystore-encrypted at rest, masked from the app and marked sensitive on Android clipboard. Debug migration reads the prior private development credential only if it still authenticates. Request and result storage may contain sensitive shell input/output; raw export must remain private.

## OAuth phone pairing

The owner creates invitations only from the phone UI. Invoking tools remotely cannot create agents or change their grants. Each new OAuth pairing starts with approval-gated shell access, irrespective of another client's standing permission. Invitation hashes are device-scoped; consumption is a Durable Object transaction and replay tombstones prevent re-publication during the code lifetime. Pending phone data is Keystore-encrypted, and the server's invitation payload is encrypted using the one-use code. Neither codes nor credentials are audit-event fields.

The maintained OAuth provider enforces the authorization-code/PKCE and resource-token boundary; its browser-bound consent helpers provide CSRF and framing protection. Expiration errors are resolved by starting a fresh consent session, never by relaxing these checks. Token validity at the gateway does not replace the current phone grant and revocation checks. The phone also sends its revoked agent IDs in every hello and immediately after a revocation; the gateway then refuses that agent's tool listing, `/connection` probe and invocations with `AGENT_REVOKED` even while its OAuth tokens remain unexpired. A wrong pairing code is rejected before the browser's one-use consent is spent, and a grant without `phone:control` is rejected before the invitation is consumed. Access-token and refresh lifetimes do not make an owner grant temporary.

## Permission management and presentation

Owner changes to Apps-family scope and revocation are SQLite transactions with audit events. They are not remote tools. In Custom access, an app-family grant permits policy evaluation and does not itself remove high/critical approval requirements. Ordinary `BROAD` shell still follows the custom critical gate. Explicit Full control is the separate owner decision that removes SIConnect per-action approval for all available actions, including classified-critical work; it also includes broad shell. Narrower structured-tool switches do not make either broad-shell authority harmless.

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

In Custom access, UI-family scope and per-package automatic screen actions are distinct. The owner can authenticate to allow one agent's `ui.click`/`ui.set_text` in one chosen nonprotected package. This standing permission can include sending or deleting through that application's controls. Current scope, denylist, revocation, availability, screen lease and fresh-reference checks still apply. Removing the allowance does not silently change unrelated bots or broad shell. Full control already supplies family and automatic-action authority across supported apps, so its individual app/family controls are locked until the owner restores Custom access; native protected UI and fresh-reference checks remain.

The semantic backend refuses locked-device interaction, protected SIConnect/system-permission/biometric surfaces, password nodes and their descendants. Node references bind authenticated agent, package/window, event generation, fingerprint and monotonic lifetime; state changes invalidate them. Opening owner approval changes the screen and can correctly make an earlier semantic action stale. Broad privileged shell remains outside this cooperative protection, so no complete isolation from deliberately granted arbitrary shell is claimed.

Notification actions are independently revalidated against live key, source package, notification version, action reference and PendingIntent creator UID/package. SIConnect-created intents and authentication-required actions cannot be invoked through these capabilities. Routine discovery excludes bodies and generated reply choices; action labels require explicit opt-in. Published third-party receivers still control their own behavior, so recipient identity and external delivery are not independently verified. Never automatically resend following an uncertain reply.

Requested semantic snapshots and explicit outgoing reply arguments may be sensitive in the authoritative private request database. Routine accessibility/notification lifecycle events do not log captured text. Keep databases, raw results, screenshots and fixture/device receipts outside the public repository. The disposable RemoteInput fixture has no network/SMS/contact permission and exposes only test counters/digests; uninstall it after coordinated validation.


## Phone-local ADB boundary

The local bootstrap controller is reachable only from owner UI, with owner confirmation for pairing, startup/restart, identity removal and enabling saved recovery. It is not a registered capability, exported ADB server or gateway/MCP endpoint. Pairing codes remain transient on the phone; a nonexportable Android Keystore RSA key signs TLS. TLS/SPAKE2 authorizes the ADB host; actual ADB UID and a fresh Binder command are independently verified. Only the fixed installed-package bootstrap is available through this controller; existing authorized general shell remains powerful and separate. Broad shell can reach app/system surfaces beyond narrower structured-UI grants. Recovery reuses saved authorization and never toggles secure settings or changes backend. Physical local pairing/bootstrap passed; malicious/revoked-host acceptance remains open. See [pass 10](engineering-pass-10.md) and [third-party transport sources](../adb-bootstrap/README.md).

## Dispatch-time owner restrictions

The engine rechecks the current agent grant, device controls, capability version/backend and availability inside its execution lock before admission and again when the execution coroutine actually starts. The second check closes the dispatcher wait gap after the durable EXECUTING transition. Cancellation before backend dispatch records a terminal no-effect result, including a lazy coroutine that never enters its body. Owner blocking therefore covers work queued after initial evaluation. It does not revoke effects already committed or terminate an already executing command. Pending owner acceptance still dispatches under the original agent and current policy. General authorized shell retains the authority of its selected backend and is not isolated by narrow native UI permissions.

Health notification suppression uses compact per-agent policy state and the same notification capability, grants and request records. It creates no new background daemon or owner bootstrap endpoint. An unverified post retains a claim and original request reference; notification cooldown does not authorize replay of that request.
