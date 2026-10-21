# Provider compatibility

Updated October 20, 2026. Entries below describe actual SIConnect evidence, rather than assuming that every MCP product accepts the same authentication.

| Client | Evidence | State |
| --- | --- | --- |
| Official MCP SDK v1, 2025-11-25 | Real Worker integration initialization, tools/list, tools/call | Passed, test-only device peer |
| Official MCP SDK v2, 2026-07-28 | Real Worker integration; hosted calls to seven real Fold tools | Passed |
| Grok Bot | Secure secret form accepted bearer key; saved connector battery invocation; actual native and privileged actions correlated on Fold | Connected and exercised |
| Hermes | Native HTTP MCP discovery/status; owner confirmed phone email; deterministic native MCP tool runtime installed an official app and verified package/version/Play Store installer | Connected and exercised; full LLM run did not finish every requested task |
| Dots | OAuth connected; actual device status and shell identity matched durable phone records | Connected; owner corrected Apps scope, install retry in progress |
| ChatGPT OAuth connector | Maintained OAuth provider; real consent completed and native/privileged calls correlated against SQLite | Connected and exercised |
| Other API clients | Require their own authentication and transport validation | Unverified |

Grok Bot reported that its saved connector launches `mcp-remote` in its cloud environment and reads the token from its secure secret store. The Mac does not host that relay. Its first two direct gateway checks were distinguished from a subsequent invocation through its saved connector. Do not equate Grok Bot's Cloudflare management plugin with the SIConnect phone connector.

The endpoint supports OAuth and the existing private bearer mode. Official SDK OAuth authorization/PKCE/refresh/tool invocation passes locally; this is separate from hosted-client onboarding. The app independently hashes/authenticates the forwarded agent credential and applies the current local grant. A compromised gateway that sees that credential can impersonate it; independent agent signatures are not implemented.

Client identity matters: two existing OAuth connections had the same display name. App permission was enabled on one while the other made the denied request. The owner subsequently used the new exact-request permission screen to enable the intended connection; SQLite confirmed both Apps scope and the deliberate broad-shell choice. The installed UI distinguishes same-name agents and offers a permission-review card bound to the exact denied request's authenticated agent. An agent-scope denial is final for that request; it does not create an approval. After an owner grant change, submit a new request. Do not reconnect or duplicate the plugin to solve a local permission problem.

The Cloudflare management connector manages cloud resources. It does not grant permission to control the phone. SIConnect is its own MCP resource, and each client has to authenticate to that resource. The current Grok/Hermes private-bearer setup shares an agent; OAuth Dots connections have separate local grants. Sharing an endpoint never implies sharing permissions.
