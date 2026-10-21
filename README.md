# SIConnect

SIConnect is a native Android device-control and automation platform. It lets AI agents you have authorized operate your own Android device through the Model Context Protocol (MCP), with every request passing an on-device policy engine, an approval flow and an audit log.

It is not a chatbot and not a local LLM. External agents decide *what* to do. SIConnect decides *whether* it may proceed, does it deterministically, checks what actually happened and records the result.

```
External agents (ChatGPT, Codex, Grok, OpenAI/xAI APIs, any MCP client)
        |  MCP over HTTPS (OAuth or private bearer)
        v
SIConnect gateway (Cloudflare Worker + Durable Object, self-hosted)
        |  authenticated outbound WebSocket opened by the phone
        v
SIConnect Android app
   capability registry -> policy engine -> approvals -> execution backends
   (native APIs, bundled ADB Binder service, optional Shizuku)    -> verification -> audit log
```

The phone opens the connection outward, so the phone does not need a public address. The development Mac is not part of the runtime path.

> **Status: working development runtime, Android 0.3.0.** Grok Bot, Dots/ChatGPT and Hermes have operated the actual Fold through the existing hosted gateway. This pass adds explicit pairing identities, verified package metadata, shell/process recovery, paginated activity, managed files and time-window weather. Hermes also completed disposable file operations and delivered an exact reminder while the Fold remained locked with its screen off. Generic third-party app navigation still uses the shared screen. See [project status](docs/project-status.md), [device testing](docs/device-testing.md) and [capability coverage](docs/capability-coverage.md).

A real sparse 24-hour hosted observation is underway; no completed soak or battery-life conclusion is claimed. Phone-only privileged bootstrap, native calendar/SAF/MediaStore integration, broad background lifecycle validation and shared-screen coordination remain unfinished.

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

Device setup, gateway deployment and agent pairing are covered in [docs/development-setup.md](docs/development-setup.md) .

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

OAuth pairing for Dots/ChatGPT is implemented and actual phone calls were verified. See [gateway setup](docs/gateway-deployment.md), [client evidence](docs/provider-compatibility.md), [permissions](docs/permissions.md), [capability coverage](docs/capability-coverage.md) and [performance](docs/performance.md). Hermes has a reusable [phone-control skill](integrations/skills/siconnect/SKILL.md). Never include live credentials or personal device evidence in issues or commits.
