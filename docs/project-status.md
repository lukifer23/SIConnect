# Project status

Last updated: 2026-10-20 (initial commit).

## Implemented

| Component | State | Evidence |
| --- | --- | --- |
| Gradle build (Gradle 9.8.2, AGP 9.4.1, Kotlin 2.4.21, JDK 21 daemon) | Done | `./gradlew :core:test` |
| `core`: models, risk classes, availability, request state machine | Done | `EngineTest` |
| `core`: capability registry and schema-subset validator | Done | `EngineTest` |
| `core`: policy engine (scopes, denylist, emergency stop, shell modes, standing grants, risk thresholds) | Done | `EngineTest` |
| `core`: execution engine (idempotency, CAS transitions, approvals bound to args hash, expiry, cancellation, timeout to `UNKNOWN_OUTCOME`, recovery) | Done | `EngineTest` (17 tests) |
| `core`: skill runtime (templates, conditions, validation, no escalation) | Done | `SkillsTest` (7 tests) |
| ADB audit harness (read-only, explicit target, redaction) | Done | Ran against the Fold 7; see `docs/adb-audit.md` |
| CI (host-only) | Done | `.github/workflows/ci.yml` |

## In progress

- `app`: Android app with SQLite persistence, native and Shizuku backends, capabilities, approval notifications, foreground-service transport and Compose UI.
- `gateway`: Cloudflare Worker and Durable Object, serving dual-era MCP with OAuth 2.1 and bearer authentication.

## Physical-device verification

None of the app capabilities have been verified on the device yet, because the app is not yet installed. The only device-verified item so far is the read-only audit.

## Device state

- Galaxy Z Fold 7, Android 17 / One UI 9, connected over wireless debugging.
- Shizuku 13.5.4 is installed but not running.

## Implementation pass 2 checkpoint — October 20, 2026

Core changes: approvals re-read current grants, scope, revocation, expiry, backend availability and kill switches. Risk approval is not re-requested. Request/key reuse is bound to agent, device, capability/version, canonical arguments, trusted origin, parent and explicit expiry; mismatches raise `IDEMPOTENCY_CONFLICT`. Trace IDs and generated default expiry are excluded from that fingerprint. Execution now requires winning the EXECUTING CAS and registers a lazy job before cancellation can race it. Exceptions during possibly mutating work report UNKNOWN_OUTCOME. Output schemas and bounded schema/payload definitions are validated.

DurableSkillRunner persists a definition snapshot, deadline, cursor, pending child ID and outcomes through SkillRunStore. Stable child IDs prevent replay; startup recovery must run engine recovery first. Android SQLite wiring and physical tests remain in progress. The untracked partial app present at session start has been preserved.

### Android and gateway checkpoint

The app now compiles with the pinned AGP/Gradle/Kotlin toolchain and SDK 37. `:app:assembleDebug`, `:app:assembleDebugAndroidTest`, `:app:lintDebug` pass. The debug APK has been installed repeatedly on the Fold 7 and launched. Native execution, actual package inspection, a three-step native skill and persistence through a reopened SQLite connection passed device instrumentation. Startup exposed an Android ICU incompatibility in the original template regex; escaping the closing brace fixed it.

The production runtime uses SQLiteOpenHelper, CAS request transitions with transactional transition history, Keystore-encrypted credentials, native approval notifications, a non-exported owner review UI, and an authenticated debug-only loopback endpoint. The UI is being simplified following owner feedback.

A companion-free privileged Binder service is bundled in the APK and started by the authorized ADB launcher. Its attachment/identity tests are still being debugged; shell functionality is not yet claimed verified. Shizuku remains explicitly selectable with no automatic backend fallback.

Gateway: actual Cloudflare Worker/Durable Object implementation, official MCP SDK 2.3.1 `createMcpHandler`, private bearer authentication, device enrollment, outbound WebSocket routing, bounded concurrency and explicit offline/unknown outcome errors. Five local Worker protocol tests pass using official SDK v1/v2 clients and an explicitly test-only WebSocket peer. No cloud deployment or real-phone MCP result is claimed at this checkpoint. Cloudflare CLI is not authenticated.
