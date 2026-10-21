# Gateway deployment

The implemented `gateway/` is a Cloudflare Worker and SQLite-backed Durable Object. It uses official `@modelcontextprotocol/server` 2.3.1 `createMcpHandler`, not a hand-written protocol compatibility implementation.

```sh
cd gateway
npm ci
npm run typecheck
npm test
npm run build
npx wrangler whoami
# Confirm the selected PERSONAL account before deployment.
npx wrangler secret bulk /absolute/private/gateway-secrets.json
npm run deploy
```

Secrets: `DEVICE_ENROLL_TOKEN` for owner enrollment and `AGENTS_JSON` containing token hashes, authenticated agent IDs, target device IDs, expiration/revocation fields. See source types for exact structure. Never put values in Git, chat or shell history. Wrangler account identity must be checked before mutation; the development deployment used the owner's personal/free account.

The phone stores its device credential encrypted with Android Keystore and initiates an outbound authenticated WebSocket. Registration is `/register/<deviceId>`, device socket `/device/<deviceId>`, agent MCP endpoint `/mcp`, health `/health`. Health is not proof of phone connectivity. Device hello advertises actual registered capabilities. Offline requests fail explicitly; this implementation does not promise an offline durable execution queue. Concurrency and rate limits are enforced by the Durable Object.

For real-phone checks, supply a PRIVATE file with `url` and `agentToken`, then run:

```sh
npm run device-test -- /absolute/private/gateway-config.json
```

The harness uses the official MCP v2 client and writes evidence alongside that private file. `npm test` uses a test-only device peer and must never be reported as phone evidence.

Grok Bot setup: ask it to add your HTTPS `/mcp` endpoint with private bearer authentication and request the key through its secure secret form. In SIConnect, Connection details provides Copy address and Copy bot key. Paste the key into the secure token field without the word Bearer. Verify with a real device tool and match its request ID in the phone's Bot activity. Grok Bot may need a new message after its tool catalog refreshes.

OAuth is implemented with pinned `@cloudflare/workers-oauth-provider` 1.2.3 in this same Worker. Create an `OAUTH_KV` namespace, bind it in Wrangler, and set `PUBLIC_ORIGIN` to the deployment's canonical HTTPS origin. CIMD requires `global_fetch_strictly_public`. The protected resource is `/mcp`; discovery is `/.well-known/oauth-protected-resource/mcp` and `/.well-known/oauth-authorization-server`. Authorization `/authorize`, token `/token`, and dynamic registration `/oauth/register` are library-managed. Do not replace the phone endpoint or choose unauthenticated mode.

For Dots/ChatGPT: Plugins → Add → Add custom MCP server, choose the existing HTTPS `/mcp` address and OAuth. In the phone app, Connection details → Pair another bot creates a separately revocable agent and a one-use invitation. Renewing a code for the same stored pairing preserves that agent and its owner-selected permissions. Enter that code only on your gateway's consent page. The displayed deadline is the actual invitation expiry; codes are valid for at most ten minutes. Each invitation is encrypted on the phone using a key derived from the code; the Durable Object stores only its hash and sealed payload. Consuming it is atomic, with a replay tombstone. The phone stores its pending invitation using Android Keystore encryption.

Browser consent is separately bound to its initiating browser for ten minutes. If it expires, return to the existing plugin and choose Continue connecting; do not create duplicate plugins or disable CSRF protection. A fresh pairing code may also be needed. Official SDK integration exercises S256 PKCE, consent binding, token refresh and actual MCP invocation against a test-only device peer. Actual hosted Dots/ChatGPT authorization subsequently completed; native status and privileged identity calls matched the phone database. Initial expired browser/code attempts were resolved with fresh consent, without changing security checks.

Access tokens last one hour; refresh grants last thirty days. Local owner grants remain durable independently of these token lifetimes. Revoking the paired agent on the phone immediately prevents further device execution, including with otherwise valid OAuth tokens. The gateway decrypts the forwarding credential when serving authorized calls: it remains a trusted intermediary, not an end-to-end signature boundary.

The phone reports ONLINE only after an acknowledged protocol-2 hello. Pairing waits for a saved-invitation acknowledgement and shows progress/errors; a transport socket alone is insufficient proof of readiness. An authenticated private-bearer `GET /connection` returns actual socket/catalog status without exposing credentials. Deployment does not require Docker or a Mac-side server. After phone reboot, native functionality can recover; the bundled privileged service still needs authorized bootstrap.

Do not confuse online transport with tool authorization. In this checkpoint the phone was online while Dots app inspection was denied by its local grant. Resolve that on the phone using the exact blocked request's permission link, then request a fresh invocation. Multiple connections may have the same display name and independent grants.

The deployed request-expiration default was raised to fifteen minutes so the device's ten-minute approval window is not prematurely capped by the previous two-minute envelope. This does not make a synchronous tool call wait fifteen minutes: in-flight routing remains bounded to 130 seconds and an approval-required call returns its pending record. The change was deployed after rechecking the previously verified personal account; actual client retries still require the phone grant to permit their capability.

Pass 3 deployed the same Worker endpoint to version `3103541b-0f0f-4acb-8e1c-c86989f19efe` after checking the existing personal account and successful SDK/Worker tests. OAuth/KV/device identities and credentials were retained. Socket attachments now carry generation/readiness; older attached sessions reconnect through authentication/hello during rollout. Original request lookup is required after an uncertain disconnect result. The previous deployment identity is retained privately for deliberate rollback; do not create a replacement endpoint.


Pass 4 deployment: version `5cf1038d-015a-42bf-bb54-ca74e2d8ecdd`, same Worker endpoint and durable bindings, after rechecking the expected personal account. No secret upload, OAuth resource change, credential rotation or replacement endpoint was required. Previous version `3103541b-0f0f-4acb-8e1c-c86989f19efe` remains the rollback reference. This deployment adds bounded UTF-8 envelopes, explicit HTTP-disconnect unknown-outcome handling and same-agent recent/output retrieval; the phone remains the authoritative result store. Post-deployment phone smoke results are recorded in device testing rather than inferred from upload success.
