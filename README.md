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

> **Status: working development runtime, Android 0.5.0-dev (version code 6), SQLite schema 6.** The installed registry advertises 81 tools through the existing hosted gateway. Grokbot and official MCP SDK calls were freshly exercised this pass, including actual bundled shell UID 2000. Dots/ChatGPT and Hermes have prior real-device success; fresh provider-specific evidence is tracked separately in [device testing](docs/device-testing.md) and [provider compatibility](docs/provider-compatibility.md).

The owner UI now has Home, Agents, Automations, Activity and Settings with adaptive navigation. Home shows actual missing-access setup cards; enabling Android Accessibility and notification-listener access removed the corresponding cards on the physical Fold. Automations can be created/edited with native date/time inputs, inspected, run once without changing their schedule, and cancelled. These changes preserve existing identities, grants, requests and reminders. Comprehensive fold/posture/font-scale verification remains pending.

Native notification-action discovery and supported RemoteInput replies now use exact live action references. A separate disposable test APK received one real reply broadcast, with its receipt digest/count checked independently. Production results report `DISPATCHED`, not recipient delivery. Semantic screen tools are implemented with cooperative leases, strict stale-reference checks and protected-owner/password exclusions; their normal-process physical harness is currently blocked because Android marks the owner-enabled Accessibility service crashed/disconnected after lifecycle testing. Owner off/on restoration is required before that validation can continue. Calendar/SAF write tests still need owner-selected disposable resources. No completed battery study or unattended privileged reboot recovery is claimed.

## Design principles

- **One policy engine.** Native API calls, shell commands, skills and automations all go through the same authorization path (`core/.../Policy.kt`).
- **Shell is first-class.** An owner-authorized shell executor runs commands no predefined capability covers, so a new diagnostic needs no app rebuild. By default it requires approval. Granting broad shell access to an agent is an explicit, documented decision.
- **Approvals are bound to exact actions.** An approval covers the SHA-256 of the capability, its version and the canonical arguments. Any change means a new approval.
- **Honest outcomes.** Interrupted side-effecting work is reported as `UNKNOWN_OUTCOME`, never as success. Unavailable capabilities say why. Privileged work never silently falls back to a weaker identity.
- **Durable requests.** Every request has an id, an optional idempotency key, an expiry and a persisted state machine. Duplicates return the existing record instead of re-executing.
- **Deterministic automation.** Skills are declarative JSON workflows over existing capabilities. Routine checks (for example the weather/commute reminder) run on the device without calling a model.

## Using the phone

1. Open Home to see connection/backend state and any missing Android access. Enable only the access needed for your workflow.
2. Open Agents to select the exact bot identity and allow its capability families. Standing screen access for a selected app and broad privileged shell are separate deliberate permissions.
3. Open Automations to choose a reminder, device snapshot or configured commute check, review timing/permissions and save. Activity retains request results; pending approvals remain reviewable until resolved or expired.

Remote agents still use the same authenticated MCP endpoint. Existing OAuth connections do not need to be replaced for this update. See [permissions and owner controls](docs/permissions.md) for the exact setup and approval behavior.

## Repository layout

| Path | Contents |
| --- | --- |
| `core/` | Pure Kotlin/JVM: models, capability registry, JSON-schema subset validator, policy engine, execution engine, skill runtime. Host-testable. |
| `app/` | Android application (Kotlin, Jetpack Compose): backends, capabilities, persistence, approvals, transport, UI. |
| `gateway/` | MCP gateway for Cloudflare Workers. |
| `tools/` | ADB audit/profiling tools and opt-in disposable physical-test fixtures. |
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
- [Gateway setup](docs/gateway-deployment.md), [owner permissions](docs/permissions.md) and [performance](docs/performance.md)
- [Reusable Hermes phone-control skill](integrations/skills/siconnect/SKILL.md)

Never include live credentials or personal device evidence in issues or commits.

## Disclaimer

SIConnect is designed to give software agents powerful control over a device, up to ADB-shell-level privileges when you enable them. Read [DISCLAIMER.md](DISCLAIMER.md) before you install it. Use it only on hardware you own.

## License

[MIT](LICENSE)
