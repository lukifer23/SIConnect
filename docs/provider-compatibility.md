# Provider compatibility

The "Documented" column comes from provider documentation reviewed on 2026-10-20. The "Verified" column records only real connections made against SIConnect. Nothing has been verified yet.

| Provider / product | Transport | Auth options (documented) | Write tools | Unattended / scheduled use | Verified with SIConnect |
| --- | --- | --- | --- | --- | --- |
| Generic MCP client (official SDK v1, `2025-11-25`) | Streamable HTTP | Bearer or OAuth 2.1 | Yes | Client-dependent | Not yet |
| Generic MCP client (official SDK v2, `2026-07-28`) | Streamable HTTP, stateless | Bearer or OAuth 2.1 | Yes | Client-dependent | Not yet |
| ChatGPT custom connectors (developer mode) | Remote MCP over HTTPS | No-auth or OAuth 2.1 with protected resource metadata. Static API keys are not supported. | Supported, with confirmation prompts. Plan availability is unverified. | Unverified | Not yet |
| OpenAI Dots | Uses ChatGPT plugins/connectors | As for ChatGPT | Unverified | Runs scheduled and unattended. Whether it can use custom developer-mode connectors is **unverified**. | Not yet |
| OpenAI Responses API (`mcp` tool) | Remote MCP | `authorization` field, which must be resent on each request | Yes. `require_approval` defaults to always. | Yes, when driven by your own scheduler | Not yet |
| Codex | MCP client | Bearer via config | Yes | Session-driven | Not yet |
| Grok connectors | Remote MCP over HTTPS | "Complete any required authentication": OAuth or API keys. Exact schemes are undocumented. | Unverified | Business/Enterprise admin provisioning | Not yet |
| Grok Bot routines | Connected tools | As for Grok connectors | Docs recommend approval for send/delete/purchase | Routines run in the cloud. Use of custom MCP connectors is **unverified**. | Not yet |
| xAI API remote MCP | Remote MCP | `authorization` plus custom `headers` | Yes (`require_approval` not supported) | Yes, when driven by your own scheduler | Not yet |

Sources:

- MCP 2026-07-28 changelog and transport specification: https://modelcontextprotocol.io/specification/2026-07-28/changelog
- ChatGPT Apps SDK authentication: https://developers.openai.com/apps-sdk/build/auth
- OpenAI remote MCP tool: https://developers.openai.com/api/docs/guides/tools-connectors-mcp
- Grok connectors and custom tunneling: https://docs.x.ai/grok/connectors
- xAI remote MCP: https://docs.x.ai/developers/tools/remote-mcp
- Grok Bot routines: https://docs.x.ai/grok-bot/skills-routines-and-automations

The OpenAI help-center articles 12584461 and 20001530 returned HTTP 403 to automated fetches. Dots behavior above comes from secondary sources and must be confirmed in the owner's account.
