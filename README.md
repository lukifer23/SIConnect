# SIConnect

SIConnect is a native Android device-control and automation platform. It lets AI agents you have authorized operate your own Android device through the Model Context Protocol (MCP), with every request passing an on-device policy engine, an approval flow and an audit log.

It is not a chatbot and not a local LLM. External agents decide *what* to do. SIConnect decides *whether* it may proceed, does it deterministically, checks what actually happened and records the result.

```
External agents (ChatGPT, Codex, Grok, OpenAI/xAI APIs, any MCP client)
        |  MCP over HTTPS (private bearer mode)
        v
SIConnect gateway (Cloudflare Worker + Durable Object, self-hosted)
        |  authenticated outbound WebSocket opened by the phone
        v
SIConnect Android app
   capability registry -> policy engine -> approvals -> execution backends
   (native APIs, bundled ADB Binder service, optional Shizuku)    -> verification -> audit log
```

The phone opens the connection outward, so the phone does not need a public address. The development Mac is not part of the runtime path.

> **Status: working development runtime.** The APK is installed on the Galaxy Z Fold 7. Real Grok Bot calls through the deployed personal Cloudflare gateway have executed native diagnostics, opened apple.com and created a verified Clock alarm. SQLite requests, approvals and owner-selected standing permissions are integrated. OAuth, phone-only privileged bootstrap and extended lifecycle validation remain unfinished. See [project status](docs/project-status.md) and [device testing](docs/device-testing.md).

## Design principles

- **One policy engine.** Native API calls, shell commands, skills and automations all go through the same authorization path (`core/.../Policy.kt`).
- **Shell is first-class.** An owner-authorized shell executor runs commands no predefined capability covers, so a new diagnostic needs no app rebuild. By default it requires approval. Granting broad shell access to an agent is an explicit, documented decision.
- **Approvals are bound to exact actions.** An approval covers the SHA-256 of the capability, its version and the canonical arguments. Any change means a new approval.
- **Honest outcomes.** Interrupted side-effecting work is reported as `UNKNOWN_OUTCOME`, never as success. Unavailable capabilities say why. Privileged work never silently falls back to a weaker identity.
- **Durable requests.** Every request has an id, an optional idempotency key, an expiry and a persisted state machine. Duplicates return the existing record instead of re-executing.
- **Deterministic automation.** Skills are declarative JSON workflows over existing capabilities. Routine checks (for example the weather/commute reminder) run on the device without calling a model.

## Repository layout

| Path | Contents |
| --- | --- |
| `core/` | Pure Kotlin/JVM: models, capability registry, JSON-schema subset validator, policy engine, execution engine, skill runtime. Host-testable. |
| `app/` | Android application (Kotlin, Jetpack Compose): backends, capabilities, persistence, approvals, transport, UI. |
| `gateway/` | MCP gateway for Cloudflare Workers. |
| `tools/` | Mac-side tooling: ADB audit harness, diagnostics analyzer. |
| `docs/` | Architecture, decisions, device audit, security model, status and handoff. |

## Requirements

- macOS or Linux with JDK 21 (the Gradle daemon is pinned to 21 via `gradle/gradle-daemon-jvm.properties`).
- Android SDK with platform `android-37.0` and build-tools 37.
- An Android 12+ device (developed on a Galaxy Z Fold 7, Android 17 / One UI 9).
- Optional: [Shizuku](https://shizuku.rikka.app/) for shell-uid privileged execution. No root required.
- Optional: a Cloudflare account for the gateway.

## Quick start (host)

```sh
git clone https://github.com/lukifer23/SIConnect.git
cd SIConnect
./gradlew :core:test
```

Device setup, gateway deployment and agent pairing are covered in [docs/development-setup.md](docs/development-setup.md) and will grow as those components land.

## Documentation

- [Architecture](docs/architecture.md)
- [Architecture decisions](docs/architecture-decisions.md)
- [Device audit (Galaxy Z Fold 7)](docs/adb-audit.md)
- [Provider compatibility](docs/provider-compatibility.md)
- [Security model](docs/security-model.md)
- [Project status](docs/project-status.md) and [next steps](docs/next-steps.md)

## Disclaimer

SIConnect is designed to give software agents powerful control over a device, up to ADB-shell-level privileges when you enable them. Read [DISCLAIMER.md](DISCLAIMER.md) before you install it. Use it only on hardware you own.

## License

[MIT](LICENSE)
