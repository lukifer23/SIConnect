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

Private bearer mode is implemented. OAuth provider onboarding and token renewal UI are unfinished. Deployment does not require Docker or a Mac-side server. After phone reboot, native functionality can recover; the bundled privileged service still needs authorized bootstrap.
