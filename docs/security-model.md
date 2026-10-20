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
| Unauthorized MCP client | Every MCP call needs a valid OAuth access token or agent bearer token. The gateway stores only token hashes, and the device re-checks agent existence, revocation and expiry. |
| Stolen agent credential | Per-agent grants limit the damage. Credentials can be revoked or rotated on the phone. OAuth access tokens are short-lived, and the emergency stop denies all remote work. |
| Replay | Request IDs are unique, idempotency keys deduplicate retries, every request has an expiry, and expired requests are rejected even if delivered late. |
| Gateway compromise | The gateway can forge an agent's identity, but only for agents that exist on the device, and only within those agents' grants. It cannot approve anything: approvals happen on the device. Plaintext is visible to the gateway, so we do not claim end-to-end encryption. |
| Approval substitution | An approval is bound to the SHA-256 of capability, version and canonical arguments. Execution uses only the stored arguments, so a different command needs a new approval. |
| Prompt injection via content | Content never reaches the policy engine. Policy changes are local-owner-only operations and are not exposed as remote tools. |
| Malicious skill definitions | Skills are validated (known capabilities, no nested skills, no forward references). Each step runs under the invoker's grant. Installing a skill is itself a policy-gated operation whose risk is the maximum risk of its steps. |
| Command injection in typed capabilities | Typed capabilities build argument vectors (`argv`) rather than shell strings, and validate package names and paths. |
| Dangerous shell chaining | Arbitrary shell needs approval by default. Standing shell grants are exact-string matches. `BROAD` mode is an explicit owner choice, documented as able to bypass narrower rules. No keyword filtering is presented as a safeguard. |
| Sensitive output disclosure | Output is bounded and truncation is reported. Logs never store tokens or keys. Message bodies and notification text are not persisted in logs by default. Diagnostic exports are redacted and must be reviewed before sharing. |
| Lost or stolen phone | App data is excluded from backups. Secrets are wrapped with an Android Keystore key. Revoking the device on the gateway cuts remote access. |
| Unintended privilege persistence | Shizuku privilege ends at reboot unless the owner enables Shizuku's own auto-start. SIConnect never attempts its own persistence. |
| Excessive tool authority | The default agent grant is `READ_ONLY` auto-approval. Everything else needs approval until the owner widens the grant. |

## Emergency stop

The emergency stop is a switch in the app (dashboard and quick settings) that sets `DeviceControls.remoteExecutionEnabled = false`.

- It is checked at submit time and again before an approved action executes.
- A separate switch, `privilegedEnabled`, disables Shizuku routing.
- Neither switch needs another computer.
