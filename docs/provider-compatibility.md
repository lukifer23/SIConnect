# Provider compatibility

Updated October 20, 2026. Entries below describe actual SIConnect evidence, rather than assuming that every MCP product accepts the same authentication.

| Client | Evidence | State |
| --- | --- | --- |
| Official MCP SDK v1, 2025-11-25 | Real Worker integration initialization, tools/list, tools/call | Passed, test-only device peer |
| Official MCP SDK v2, 2026-07-28 | Real Worker integration; hosted calls to seven real Fold tools | Passed |
| Grok Bot | Secure secret form accepted bearer key; saved connector battery invocation; actual native and privileged actions correlated on Fold | Connected and exercised |
| Hermes | Global native HTTP MCP client discovered 30 tools and invoked real device status | Connected and exercised |
| Dots | Actual client reports that hosted onboarding requires OAuth rather than a fixed private bearer | OAuth integration in progress |
| ChatGPT OAuth connector | Maintained OAuth provider deployed; actual CIMD consent reached in Chrome; browser session/code expired before final pairing | Onboarding incomplete; do not claim connected |
| Other API clients | Require their own authentication and transport validation | Unverified |

Grok Bot reported that its saved connector launches `mcp-remote` in its cloud environment and reads the token from its secure secret store. The Mac does not host that relay. Its first two direct gateway checks were distinguished from a subsequent invocation through its saved connector. Do not equate Grok Bot's Cloudflare management plugin with the SIConnect phone connector.

The endpoint supports OAuth and the existing private bearer mode. Official SDK OAuth authorization/PKCE/refresh/tool invocation passes locally; this is separate from hosted-client onboarding. The app independently hashes/authenticates the forwarded agent credential and applies the current local grant. A compromised gateway that sees that credential can impersonate it; independent agent signatures are not implemented.
