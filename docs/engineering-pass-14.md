# Engineering pass 14 — October 26, 2026

Current source and installed app: **0.6.0-dev.6 / versionCode 14**, SQLite **10**, **85** tools. The phone now runs a minified development-signed APK, updated in place with the existing certificate. The existing gateway was updated in its previously verified account; endpoint, bindings, credentials and local grants were retained. This pass adds measured discovery/reconnect improvements and fresh Grok/SDK evidence.

## Goal and scope

The end goal remains an owner-controlled Android execution platform: authorized external agents can use native capabilities, deterministic skills/automations and the bundled privileged backend through the same durable policy engine. It must report actual authority and outcomes, recover original requests after interruptions, preserve revocation/approval boundaries, and operate without a workstation runtime or separate companion APK.

This pass prioritizes the real connected path, provider-query amplification, usable-network recovery, test-package cleanup and client retry behavior. General retention, blocked provider/HTTP cancellation, ADB write flow control, complete owner UI acceptance and signed public distribution remain unfinished. No new scopes were granted to make tests pass.

## Implemented and verified

| Area | Change | Evidence |
| --- | --- | --- |
| Discovery | Per-refresh shared probes, including shared failures; measured availability passed to detail rendering | Six core regressions cover shared measurement, failure reuse, cache expiry, invalidation, monitor ownership and fresh execution authorization |
| Document providers | One grouped read/write readiness measurement across nine tools; early stop once both are ready | Real selected-root physical test: one provider-access probe across the nine-tool refresh; twenty cached UI metadata reads caused no additional probe |
| Owner projection | Five-second metadata reuse for ordinary activity updates; invalidate on navigation/resume/access/backend/service changes | Host cache and permission-loss tests; explicit folder removal refreshes gateway discovery |
| Reconnection | A lifecycle-owned default-network callback wakes existing timed backoff when Android reports a validated usable network; captive/unvalidated networks do not qualify | Four coroutine tests cover prompt wake-up, pre-wait changes, coalesced/stale signals and cancellation; actual Android callback test passed |
| Offline reporting | `DEVICE_OFFLINE` now identifies the transport boundary, reports that this attempt was not forwarded and leaves backend availability unknown | All 15 gateway protocol cases pass; same existing gateway updated |
| Retry rollout | Optional `_siconnectOperationKey` deployed on the existing endpoint | Live same-principal read/write deduplication, changed-operation conflict, original record equality and exact-file cleanup passed |
| Standalone app | Removed disposable notification/semantic companion and instrumentation package; optimized APK excludes debug fixture/loopback endpoint | Final installed-package check listed only `dev.siconnect.app` among SIConnect/test fixtures |
| Installation evidence | Optional framework-only checkpoint runner avoids dependencies on the target's unminified Kotlin ABI; exports bounded request metadata only | Protected-record/integrity check passed against the minified installation; twelve live request records independently verified |
| Agent guidance | Distinguishes transport loss from privilege loss and gateway retry metadata from native arguments/catalogs | Corrects the unsupported diagnoses seen in the actual Grok conversation |

The five-second cache is discovery metadata only. Execution still checks current grants, availability, deadlines and policy directly. Invalidation during a slow provider call cannot republish that stale measurement into the cache, and provider IO does not hold the registry monitor. This is a bounded query-count improvement, not a measured whole-UI latency or battery result.

Network callbacks only wake reconnection; they do not replay requests, enable connectivity, change enrollment or reconnect an owner-paused gateway. Timed retry remains available if observer registration fails, with a diagnostic event. Wi-Fi/cellular/captive-portal/reboot transition acceptance remains open.

## Actual Grok results

The existing Grok Bot app and saved SIConnect principal were used. No replacement bot or new credential was created. Native/shell results were later matched against actual SQLite records. The shared Grok conversation also had unrelated owner work in progress, so its whole-conversation timing is not a controlled SIConnect latency measurement.

**SI14-A, original code-13 baseline:** native status and battery succeeded in SQLite. Status response delivery hit `DEVICE_REPLACED_UNKNOWN_OUTCOME`; Grok retrieved the original successful record through `requests_get`. The subsequent shell identity attempt received `DEVICE_OFFLINE` and produced no phone execution record. Recorded native lifecycles were 41 ms for status, 36 ms for battery and 25 ms for lookup. Phone history showed transport interruption/result-delivery failure and reconnection. The privileged daemon was present in the independent baseline; the transport error did not establish a stopped backend. The exact cause of the underlying interruption was not reproduced, so no universal connection fix is claimed.

**SI14-B, installed minified code 14:** all four requested reads succeeded: two unkeyed battery reads, actual shell identity as UID 2000 through `ADB_SERVICE`, and runtime reporting app version/code 14 and gateway ONLINE. Grok reported an old empty battery tool schema and omitted the new key, so those two unkeyed reads correctly have distinct request identities. SQLite verified their lifecycles at 21 and 15 ms, shell identity at 56 ms and runtime at 80 ms.

**SI14-C:** Grok reported restarting its existing MCP connector, then issued two keyed battery reads. Both returned the same `mcp:` identity and successful original result. That ID exactly matches the gateway's SHA-256 derivation for the authenticated device/principal and the supplied operation key. SQLite independently showed **one** execution transition and an 18 ms original lifecycle. This is a passing keyed retry. Grok's narrative called it a failure because native arguments were `{}` and native `device_capabilities` did not advertise the transport field. Those are expected boundaries: the gateway strips the key before capability validation, and native capabilities are not MCP tool schemas. Fresh official SDK discovery against the same principal exposed the field correctly. A reported stale client schema and an incorrect interpretation must not override actual request/transition evidence.

No personal sends, installations, permission expansion or calendar edits were part of these phone queries. The test read/write used only a unique disposable shell file. Raw app UI, credentials, IDs linking private device/agent identity and database captures remain outside the public tree.

## Independent hosted retry and latency sample

The existing private credential was checked by hash against the same Grok device principal before use. No new principal, pairing or grant was created. `gateway/test/retry-device-client.mjs` uses the official SDK against the actual hosted phone and defaults to reads; writing requires `--allow-disposable-write` and the principal's current permission.

The live run discovered 85 tools and performed nine calls: eight successful responses and one expected `IDEMPOTENCY_CONFLICT`. Identical-key battery calls returned an equal original record. A different capability under that key conflicted. Identical-key shell writes returned one stored request; a separate file read counted one byte, and cleanup verified absence. SQLite showed one execution for the keyed mutation. The read-only verifier matched twelve distinct successful Grok/SDK request records, all with a single execution transition, while preserving original owner records.

| Call | Measured host SDK round trip |
| --- | ---: |
| First keyed battery / original-result retry | 200 / 158 ms |
| Changed-capability conflict | 141 ms |
| Actual privileged identity | 200 ms |
| Runtime with explicit SQLite integrity check | 517 ms |
| First keyed shell write / original-result retry | 206 / 153 ms |
| File-count verification / cleanup | 187 / 203 ms |

These are a small live sample, including network/gateway overhead. They are not statistical percentiles, a matched before/after benchmark, a whole-agent duration or a battery/availability study. The SQLite integrity check accounted for a 292 ms recorded runtime lifecycle in the SDK run. Ordinary native work and hosted reads were already fast in this sample; connection loss, repeated discovery and client orchestration need separate measurement.

## Installation and test-package cleanup

Seven focused JUnit device tests passed on the updated debug build: DiscoveryDeviceTest (two), GatewayNetworkDeviceTest (one), native durable/idempotent execution, managed files, actual bundled shell identity and bounded shell timeout/output/cancellation. The separate minified checkpoint passed for schema 10, SQLite integrity, five original agent/grant records, three reminders, two automations and the document-root record.

The minified APK is **4,896,897 bytes**, signed with the existing development identity. It was installed with `install -r`; no uninstall/data clear of the main app or new signing key was used. Its manifest excludes SemanticUiFixtureActivity and R8 removes the debug LocalEndpoint. This is an optimized development preview, not public production distribution. APK size does not prove runtime speed or low memory use.

The first attempted AndroidX checkpoint runner crashed because the target's minification renamed Kotlin Intrinsics; this was a test-runner dependency failure. An initial custom-manifest attempt also failed to register its runner. The corrected explicit `-PinstallationCheckpoint=true` runner uses Android/Java framework APIs only, then successfully verified the actual optimized app. Both failed harness attempts were retained privately rather than counted as passes. The normal AndroidX runner remains the default for the debug test suite.

`dev.siconnect.notificationfixture` was the generic disposable notification/semantic app; it was removed. `dev.siconnect.app.test` was removed after the final metadata check. MainActivity was relaunched and the bundled backend restored from the installed APK. Final package inspection found only the main SIConnect package among these three packages. The runtime still uses its own foreground gateway service and bundled daemon; neither test package is needed.

## Verification and remaining work

Host total: **193 passed** — 70 core, 93 Android JVM, two embedded ADB, 13 Python and 15 gateway. Full debug/release/instrumentation builds and lint passed. Lint remains warning-bearing; no lint-error claim is inferred from tests alone. Gateway typecheck, deployed protocol changes and npm audit passed. The reusable live harness also passed its actual nine-call acceptance run; it is separate from local fixture tests and the host count.

Next priorities are owner-controlled retention with deduplication/unknown-outcome protection; blocked IO cancellation; per-packet ADB write acknowledgements; controlled network transitions and original-result loss/restart testing; large-font/Fold/authentication lifecycle acceptance; designated Calendar CRUD; and quiet matched resource/database-growth studies. Native/UI/listener permissions and existing scopes must be evaluated independently. Current Grok grants do not include every newly implemented family, and no automatic expansion is appropriate. See [next steps](next-steps.md) and [the source audit](source-audit-2026-10-26.md).

## Real tomorrow-weather workflow

The owner then requested a real forecast, alarm/commute assessment and a conditional clothing reminder through Grok/SIConnect. The initial native forecast failed explicitly with `LOCATION_REQUIRED`; next-alarm and scoped reminder reads succeeded. After the owner supplied a destination, the actual next-day morning forecast succeeded with complete hourly coverage. The forecast did not warrant a rain reminder, so the conditional action was skipped and Clock was unchanged. This proves the real read/decision path, not an actual rain-reminder dispatch or route-wide traffic assessment. Personal destination/address details remain outside this report.

The response exposed interpretation gaps, now corrected in source descriptors/metadata and agent guidance: no exposed next alarm does not enumerate all configured alarms; reminder listing is caller-scoped; a point forecast does not supply live traffic or establish route/road conditions; and missing weather coordinates do not diagnose device disconnection. Alarm output now states its Android observation scope and non-enumeration explicitly. Forecast output labels point scope, coordinate source and absence of live traffic. Existing fields and reminder-list array shape remain compatible.

Remaining product gaps are discoverable owner-configured weather/commute preferences, permission-aware current-location support, and a real route/traffic data source if desired. Current GPS was not obtained through a native SIConnect location capability. Missing wake/departure timing prevents a personalized earlier-alarm decision. No new permissions or source-location defaults were enabled automatically.
