# Security model

## Trust boundaries

| Component | Trusted for | Not trusted for |
| --- | --- | --- |
| Owner (on-device UI, biometric) | Policy, grants, approvals, emergency stop | n/a |
| Android app (policy engine) | Final authorization and execution | n/a |
| Gateway | Authenticating agents, routing, queueing | Authorization decisions, approval claims, device policy |
| External agents | Choosing tools within their grant | Anything outside their grant. Their interpretation of outside content is never authorization. |
| Content (notifications, messages, web, files) | Data only | Instructions of any kind |

## Threats and mitigations

| Threat | Mitigation |
| --- | --- |
| Unauthorized MCP client | Every MCP call currently needs a valid private agent bearer token; OAuth is not implemented. The gateway stores only token hashes, and the device re-checks agent existence, revocation and expiry. |
| Stolen agent credential | Per-agent grants limit the damage. Credentials can be revoked or rotated on the phone. The emergency stop denies new remote work. Gateway bearer expiration is configured independently. |
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
| Excessive tool authority | The default agent grant is `READ_ONLY` auto-approval. Everything else needs approval until the owner widens the grant. |

## Emergency stop

The emergency stop is a switch in the app (dashboard and quick settings) that sets `DeviceControls.remoteExecutionEnabled = false`.

- It is checked at submit time and again before an approved action executes.
- A separate switch, `privilegedEnabled`, disables privileged routing.
- Neither switch needs another computer.

## Owner standing permissions

Always allow this action persists an agent-bound exact canonical argument/version hash and selected backend in SQLite. Shell requests match such grants before the ordinary shell approval gate; they cannot match a generic empty constrained grant. Critical risk, current scopes, denylist, revocation, expiry, backend availability and emergency stop remain authoritative. Always allow device control is a separate deliberate BROAD shell grant, saved only from the local owner UI. It also grants that agent access to its own request status. The requesting agent cannot change policy through gateway JSON. The owner can switch back to asking each time.

The bot key copied by the owner UI is Keystore-encrypted at rest, masked from the app and marked sensitive on Android clipboard. Debug migration reads the prior private development credential only if it still authenticates. Request and result storage may contain sensitive shell input/output; raw export must remain private.
