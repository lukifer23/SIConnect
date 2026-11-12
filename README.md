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

> **Current source: Android 0.6.0-dev.20 (versionCode 28), main SQLite schema 12, 112 observed phone capabilities.** Pass 23 fixes the November 11 audit findings: Termux admission lockout, honest outcomes for rejected writes, stranded requests, enrollment retry, exact automations under Doze, privileged-attach takeover, workflow command injection, gateway catalog robustness and revocation, and owner-screen issues. Existing enrollment and grants are preserved. The hosted gateway needs redeploying to pick up its part. See [status](docs/status.md) and [pass 23](docs/engineering-pass-23.md).

Agent-proposed follow-ups appear in the **Action Inbox** and stay inert until the owner accepts them; accepted actions still use the proposing agent's current permissions. Native semantic screen tools, Calendar Provider, SAF documents, managed Clock timers, private Google Messages reading and native Termux/Codex execution are implemented; open physical acceptance is listed in [status](docs/status.md). Android instrumentation can leave the shared-screen Accessibility service disconnected; the owner restores it in Android settings. No battery study or universal unattended privileged reboot recovery is claimed.

## Design principles

- **One policy engine.** Native API calls, shell commands, skills and automations all go through the same authorization path (`core/.../Policy.kt`).
- **Shell is first-class.** An owner-authorized shell executor runs commands no predefined capability covers, so a new diagnostic needs no app rebuild. By default it requires approval. Granting broad shell access to an agent is an explicit, documented decision.
- **Approvals are bound to exact actions.** An approval covers the SHA-256 of the capability, its version and the canonical arguments. Any change means a new approval.
- **Honest outcomes.** Interrupted side-effecting work is reported as `UNKNOWN_OUTCOME`, never as success. Unavailable capabilities say why. Privileged work never silently falls back to a weaker identity.
- **Durable requests.** Every request has an id, an optional idempotency key, an expiry and a persisted state machine. Duplicates return the existing record instead of re-executing.
- **Deterministic automation.** Skills are declarative JSON workflows over existing capabilities. Routine checks (for example the weather/commute reminder) run on the device without calling a model.

## Using the phone

1. Open **Home** for native and device-control readiness. **Review** buttons open the relevant settings; **Connection details** manages the existing gateway connection without creating another agent. Enable only the access your workflow needs.
2. Open **Agents** to select the exact bot identity and allow its capability families. Shared-screen access, per-app private access, arbitrary shell and Full control are separate, deliberate permissions; expanding any of them requires owner authentication.
3. Open the **Action Inbox** from Home to review proposed follow-ups and their history. Accepting is separate from granting an agent new permission.
4. Open **Automations** to choose a reminder, device health scan, commute check or installed workflow, review timing and permissions, and save. A custom workflow can request the typed capabilities it needs (never shell) with owner authentication. Automations can be run now, disabled or deleted; deleting keeps run history.
5. **Connection details → Connection history** shows retained connection events. **Disconnect remote gateway** preserves agents and credentials. **Pause agent actions** on Home blocks new remote actions without stopping work already executing. See [background work and connection controls](docs/background-operations.md).

Remote agents use the same authenticated MCP endpoint; existing OAuth connections do not need to be replaced. See [permissions and owner controls](docs/permissions.md).

## Repository layout

| Path | Contents |
| --- | --- |
| `core/` | Pure Kotlin/JVM: models, capability registry, JSON-schema subset validator, policy engine, execution engine, skill runtime. Host-testable. |
| `app/` | Android application (Kotlin, Jetpack Compose): backends, capabilities, persistence, approvals, transport, UI. |
| `adb-bootstrap/` | Pinned, adapted LibADB TLS/SPAKE2 transport; third-party license notices are in the module and APK. |
| `gateway/` | MCP gateway for Cloudflare Workers. |
| `tools/` | ADB audit/profiling tools and opt-in disposable physical-test fixtures. |
| `docs/` | Architecture, decisions, device audit, security model, status and handoff. |

## Requirements

- macOS or Linux with JDK 21 (the Gradle daemon is pinned to 21 via `gradle/gradle-daemon-jvm.properties`; all modules emit Java 17 bytecode, so no separate JDK 17 is needed).
- Android SDK with platform `android-37.0` and build-tools 37.
- An Android 12+ device (developed on a Galaxy Z Fold 7, Android 17 / One UI 9).
- Optional advanced control: the **bundled privileged service** (started once per boot through phone-local Wireless debugging pairing or `tools/start_builtin.py`) or [Shizuku](https://shizuku.rikka.app/). Both run as the Android shell identity; no root required.
- Optional: a Cloudflare account for the gateway.

## Quick start (host)

```sh
git clone https://github.com/lukifer23/SIConnect.git
cd SIConnect
./gradlew :core:test
```

Device setup, gateway deployment and agent pairing are covered in [docs/development-setup.md](docs/development-setup.md) .

## Documentation

Start here:
- [Status: current build, verification and open work](docs/status.md)
- [Development setup](docs/development-setup.md), [installation and signing](docs/installation.md), [phone setup](docs/phone-setup.md) and [gateway deployment](docs/gateway-deployment.md)
- [Permissions and owner controls](docs/permissions.md), [security model](docs/security-model.md) and [troubleshooting](docs/troubleshooting.md)

Reference:
- [Architecture](docs/architecture.md) and [architecture decisions](docs/architecture-decisions.md)
- [Capability coverage](docs/capability-coverage.md), [private app execution](docs/private-app-execution.md), [background operations](docs/background-operations.md) and [Termux/Codex integration](docs/termux-integration.md)
- [Provider compatibility](docs/provider-compatibility.md), [performance](docs/performance.md), [device testing](docs/device-testing.md), [device audit (Galaxy Z Fold 7)](docs/adb-audit.md) and [repository hygiene](docs/repository-hygiene.md)
- [Reusable phone-control skill for agents](integrations/skills/siconnect/SKILL.md)

Engineering record:
- [Pass 23: November 11 audit fixes](docs/engineering-pass-23.md), [pass 22](docs/engineering-pass-22.md) and the [November 6 source review](docs/source-audit-2026-11-06.md)
- [Earlier passes and superseded status documents](docs/history/README.md)

Never include live credentials or personal device evidence in issues or commits.

## Disclaimer

SIConnect is designed to give software agents powerful control over a device, up to ADB-shell-level privileges when you enable them. Read [DISCLAIMER.md](DISCLAIMER.md) before you install it. Use it only on hardware you own.

## License

SIConnect source: [MIT](LICENSE). Bundled third-party ADB/cryptographic components have separate [licenses and source/relinking information](adb-bootstrap/README.md).
