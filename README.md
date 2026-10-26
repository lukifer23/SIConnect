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

> **Source checkpoint: Android 0.6.0-dev.6 (versionCode 14), SQLite schema 10, 85 tools.** Recovery, shared readiness, network wake-up and retry improvements are detailed in the [current pass](docs/engineering-pass-14.md) and [source audit](docs/source-audit-2026-10-26.md). The optimized single-app development build is installed; disposable test packages are removed. All 193 host tests and seven focused code-14 device tests passed, followed by protected-record readback and live Grok/SDK retry verification. The earlier USB-charging precondition failure remains historical; broad acceptance is incomplete.  Real phone-local Wireless ADB pairing, authenticated local startup of the existing Binder daemon, repeated daemon-loss recovery, ordinary app-process recovery and an owner-approved reboot have passed on the Fold, including hosted shell UID 2000. Existing identities, grants, reminders and automation are preserved. Reboot evidence is specific to the tested trusted Wi-Fi/unlock sequence; new-network/cellular transitions and fully unassisted fresh installation remain open. See [pass 10 evidence](docs/engineering-pass-10.md), [phone setup/recovery](docs/phone-setup.md) and [installation/signing](docs/installation.md).

The adaptive owner control center now includes an **Action Inbox** for agent-proposed follow-ups. Proposals stay inert until local acceptance; accepted actions still use the proposing agent's current permissions and the existing execution/approval engine. Agents can choose an installed app by name, package and version when configuring semantic standing access, with owner authentication required before expansion.

Native semantic screen tools, Calendar Provider and SAF operations are implemented. Earlier physical checks passed the normal-process semantic workflow, owner-authenticated diagnostics save, two owner Inbox acceptances and a selected SAF round trip. Calendar CRUD, authentication cancellation and the remaining lifecycle/UI matrix stay open. Android instrumentation can leave an enabled Accessibility service disconnected or crashed; verify normal-process readiness and let the owner restore special access when required. No completed battery study or universal unattended privileged reboot recovery is claimed.

## Design principles

- **One policy engine.** Native API calls, shell commands, skills and automations all go through the same authorization path (`core/.../Policy.kt`).
- **Shell is first-class.** An owner-authorized shell executor runs commands no predefined capability covers, so a new diagnostic needs no app rebuild. By default it requires approval. Granting broad shell access to an agent is an explicit, documented decision.
- **Approvals are bound to exact actions.** An approval covers the SHA-256 of the capability, its version and the canonical arguments. Any change means a new approval.
- **Honest outcomes.** Interrupted side-effecting work is reported as `UNKNOWN_OUTCOME`, never as success. Unavailable capabilities say why. Privileged work never silently falls back to a weaker identity.
- **Durable requests.** Every request has an id, an optional idempotency key, an expiry and a persisted state machine. Duplicates return the existing record instead of re-executing.
- **Deterministic automation.** Skills are declarative JSON workflows over existing capabilities. Routine checks (for example the weather/commute reminder) run on the device without calling a model.

## Using the phone

1. Open Home for separate native and device-control readiness. **Access** opens Settings, where Android grants and connected services are shown separately. **Gateway** manages the existing connection without creating another agent. Enable only the access needed for your workflow.
2. Open Agents to select the exact bot identity and allow its capability families. Standing screen access for a selected app and broad privileged shell are separate deliberate permissions.
3. Open Home’s Action Inbox to review proposed follow-ups. Accepting is separate from granting an agent new permission.
4. Open Automations to choose a reminder, device health scan or configured commute check, review timing/permissions and save. Workflow cards identify whether the screen is needed. A future health scan ran with the screen off and locked and advanced its saved recurrence. Health workflow v3 adds persisted warning cooldowns, recovery margins and readable notification outcomes through the existing scheduler. Existing snapshot workflows offer an owner-confirmed upgrade; schedules are never enabled automatically.
5. Open Gateway → Connection history for locally retained connection/interruption events. Disconnect remote gateway preserves agents and credentials; connecting again uses the saved enrollment. Block new remote actions is a separate control and does not terminate already executing work. See [background work and connection controls](docs/background-operations.md). Activity retains request results; pending approvals remain reviewable until resolved or expired.

Remote agents still use the same authenticated MCP endpoint. Existing OAuth connections do not need to be replaced for this update. See [permissions and owner controls](docs/permissions.md) for the exact setup and approval behavior.

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

- [Pass 14: optimized single-app installation, live Grok results and performance fixes](docs/engineering-pass-14.md)
- [Pass 13 device checkpoint, blockers and cleanup handoff](docs/engineering-pass-13.md)
- [Repository hygiene, shutdown and rebuild](docs/repository-hygiene.md)
- [October 26 source audit, implemented fixes and prioritized build plan](docs/source-audit-2026-10-26.md)
- [Phone setup and recovery](docs/phone-setup.md) and [installation/signing](docs/installation.md)
- [Pass 12 background scans, alert policy and owner verification](docs/engineering-pass-12.md)
- [Pass 11 UI/recovery hardening and CI verification](docs/engineering-pass-11.md)
- [Pass 10 evidence and open acceptance work](docs/engineering-pass-10.md)
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

SIConnect source: [MIT](LICENSE). Bundled third-party ADB/cryptographic components have separate [licenses and source/relinking information](adb-bootstrap/README.md).
