# Security model

## Trust boundaries

| Component | Trusted for | Not trusted for |
| --- | --- | --- |
| Owner (on-device UI) | Policy, grants, approvals, emergency stop | n/a |
| Android app (policy engine) | Final authorization and execution | n/a |
| Gateway | Authenticating agents, routing, queueing | Authorization decisions, approval claims, device policy |
| External agents | Choosing tools within their grant | Anything outside their grant. Their interpretation of outside content is never authorization. |
| Content (notifications, messages, web, files) | Data only | Instructions of any kind |

## Threats and mitigations

| Threat | Mitigation |
| --- | --- |
| Unauthorized MCP client | MCP requires a validated OAuth access token or configured private bearer. The phone independently validates the forwarded credential and current grant, revocation and expiry. |
| Stolen agent credential | Per-agent grants limit the damage. Credentials can be revoked on the phone. Controlled per-agent credential rotation is not yet exposed. The emergency stop denies new remote work. Gateway bearer expiration is configured independently. |
| Replay | Request IDs are unique, idempotency keys deduplicate retries, every request has an expiry, and expired requests are rejected even if delivered late. |
| Gateway compromise | The gateway can forge an agent's identity, but only for agents that exist on the device, and only within those agents' grants. It cannot approve anything: approvals happen on the device. Plaintext is visible to the gateway, so we do not claim end-to-end encryption. |
| Approval substitution | An approval is bound to the SHA-256 of capability, version and canonical arguments. Execution uses only the stored arguments, so a different command needs a new approval. |
| Prompt injection via content | Content never reaches the policy engine. Policy changes are local-owner-only operations and are not exposed as remote tools. |
| Malicious skill definitions | Skills are validated (known capabilities, no nested skills, no forward references). Each step runs under the invoker's grant. Installing a skill is itself a policy-gated operation whose risk is the maximum risk of its steps. |
| Command injection in typed capabilities | Typed capabilities build argument vectors (`argv`) rather than shell strings, and validate package names and paths. |
| Dangerous shell chaining | Arbitrary shell needs approval by default. Standing shell grants are exact-string matches. `BROAD` mode is an explicit owner choice, documented as able to bypass narrower rules. No keyword filtering is presented as a safeguard. |
| Sensitive output disclosure | Output is bounded and truncation is reported. Logs never store tokens or keys. Message bodies and notification text are not persisted in logs by default. Diagnostic exports are redacted and must be reviewed before sharing. |
| Lost or stolen phone | App data is excluded from backups. Secrets are wrapped with an Android Keystore key. Revoking the device on the gateway cuts remote access. |
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

Owner approval uses immutable PendingIntents and non-exported components. The pending notification is ongoing until resolution/expiry, but Android controls the transient heads-up banner duration. The in-app review stays open until explicit Close, displays expiry/resolved state and still requires engine revalidation. Notification failure never means approval. Biometric authentication for these choices is not yet implemented.
