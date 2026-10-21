# Engineering pass 8

Development checkpoint: October 21, 2026. Starting SHA `b8a8304aec13b5f463539ba585b9b010fccbf2df`; branch `main`. Android `0.5.0-dev.2` / versionCode 7, database schema 7, 85 runtime-discovered capabilities. This is a development build, not a stable-release declaration. This pass inspected the affected execution, policy, persistence, semantic, transport and owner-UI paths; it does not claim an exhaustive repository audit.

## Implemented

- Persistent Action Inbox, four authenticated registry tools (`proposals.create`, `.list`, `.get`, `.withdraw`), Home entry and local review/accept/dismiss/linked-execution controls. Lists omit proposed arguments and source content; detail retrieval is scoped to the authenticated principal. Text and argument budgets, expiration, per-agent deduplication, revision checks and target-version binding are enforced.
- Acceptance records a stable linked request before dispatch. Dispatch uses the proposing agent with `REMOTE` origin and the existing execution engine. Revocation, scopes, emergency stop, backend availability and exact execution approval still apply. Acceptance never changes grants. A high-risk action may require a second, existing execution approval. Closing the UI does not cancel dispatch; startup recovery uses the same request identity and never resubmits an unknown outcome. Linked-record conflicts fail closed.
- Installed-app picker in agent semantic permission management: current-user PackageManager metadata, exact package identity, app name, version, bounded icon loading and search. Selecting a row changes no permission; expansion still requires owner authentication. Manual package entry remains advanced. Protected owner/settings/biometric packages remain excluded.
- Semantic status distinguishes disabled, enabled/disconnected, connected/locked, connected/no-window and connected/ready. The app explicitly cannot observe Android's private accessibility crash list. Content-free event counters and stale-reference counts support diagnosis without logging UI text. Existing reference fingerprints, agent/package/window/TTL checks and global invalidation remain conservative and unchanged.
- Normal-process semantic harness rejects a locked/off/unknown screen before provisioning or changing tasks. It now includes lease renewal and back/home postcondition checks. It does not unlock, enable special access or fake action results.
- Binder attachment now refreshes real execution identity; a late reply from an obsolete Binder cannot overwrite current status.
- CI builds a minified unsigned release and checks that the debug fixture is absent from its manifest and R8 removes the debug loopback endpoint.

## Confirmed findings

Android exit history recorded `FORCE STOP` with descriptions for instrumentation start and finish immediately before the enabled accessibility service appeared in Android's crashed-service list. This is concrete evidence of a test-induced lifecycle interruption, not evidence that the owner disabled access. No new application exception established a separate semantic crash cause. After owner restoration, Android showed the service bound with an empty crashed-service set, and the normal-process action workflow passed without another main-APK install or instrumentation run. The normal-process harness is the preferred semantic validation route. Owner restoration remains necessary in this observed state; secure settings are never rewritten automatically. Android's actual service lifecycle is described in the [official guide](https://developer.android.com/guide/topics/ui/accessibility/views/service).

Initial new-code lint failed because the app picker used API-33-only PackageManager flag overloads despite minimum API 31. Compatible guarded paths were implemented; the final lint run passed. A local `apkanalyzer` SDK-discovery failure was replaced by successful inspection using the installed `aapt2` and R8 usage output; it was not hidden as a passing check.

## Fresh verification

| Classification | Result |
| --- | --- |
| Core JVM | 44 tests passed, zero failures/skips; includes nine new proposal safety/recovery cases |
| Android JVM | 36 tests passed, zero failures/skips; includes semantic service-state classification |
| Python unit | 13 tests passed, zero failures/skips |
| Gateway host | Typecheck, ten tests and build/dry-run passed; no gateway source/deployment change |
| Android artifacts | Debug APK, instrumentation APK, lint and minified unsigned release built successfully |
| Physical upgrade | Same application ID/signing certificate, in-place install; real schema 6 to 7 migration; integrity `ok` |
| Physical instrumentation | Two new Inbox cases passed in 0.175 seconds: disposable schema migration and authenticated proposal persistence/local-resolution/native-execution/deduplication |
| Hosted MCP | Seven official SDK calls passed; native diagnostics and actual bundled shell UID 2000; 85 discovered tools |
| Hermes provider | Existing real agent session completed native status and shell identity; both actual native/shell request IDs independently matched device SQLite |
| Grokbot provider | Fresh existing-connection status/identity pair SUCCEEDED; both request IDs independently matched device SQLite |
| Dots provider | Existing OAuth identities preserved; fresh application invocation blocked on identifying/exposing the actual Dots conversation safely. Hidden Classic window refused background input; no foreground takeover or new OAuth connection |
| Semantic mutations | Normal-process physical run passed in 3.393 seconds: status/inspect/find/text-readback/click/checkbox/scroll/wait/cancel/stale/protected/password/bounds/back/home/lease renew/release; cleanup verified |
| Owner authentication/UI | Real diagnostics form-save completed after Android owner authentication; exact SQLite readback matched. Authentication method and cancellation matrix not independently classified; Inbox acceptance remains pending |
| Calendar / SAF | No disposable calendar/folder selected yet; write tests not executed |
| Messaging | No fresh personal sends. Prior RemoteInput fixture evidence remains historical |

Physical Inbox acceptance used the trusted local API in an instrumentation test. It is **not** a claim that a human completed the biometric UI flow. The native request ran under the disposable agent and `REMOTE` origin, was independently read back and had one execution-start event. Test principals/proposals were removed. Original five grant/credential hashes still matched, three reminders remained, and there were no existing automations to migrate. A subsequent real owner-UI test saved one disposable diagnostics automation with its schedule disabled. SQLite readback matched `diagnostics.snapshot`, DAILY recurrence, configured timezone, label and revision zero; it did not enable recurring work. The separate Run now owner-authentication step remains pending. This disposable disabled row is retained for that test, not silently removed. No live credentials or owner grants were changed.

## Semantic failure history and final evidence

The first unlocked run verified text editing/readback and consumed-reference refusal, then failed because Android exposed the fixture button as `COMPLETE FIXTURE` rather than the mixed-case test selector. The harness now selects the actual bounded observed button label. Cleanup also falsely failed: on this device `adb exec-out` merged a remote `ls` error into stdout and returned status zero. Cleanup now checks the exact session-specific absence response, with a regression test.

Back-navigation checks initially assumed leaving a task meant leaving the package. Multiple disposable fixture tasks made that false: Back legitimately revealed an older fixture window of the same package. The final test starts a fresh fixture task for navigation and verifies an actual window change for Back and leaving the package for Home. This does not reinterpret `performAction(true)` as business completion. Original failed runs remain in transient diagnostics; the final successful run verifies actual text/status/checked/visible-content/window postconditions and cleanup.

Sanitized final references: text edit `d9afe2b1-0bd4-45c4-be90-8708c188a650`; scroll `0584834b-ff53-49d2-a4d0-1eda933ef47d`; Back `0a8bc0d6-610b-47c1-863e-c07c024a1a27`; Home `c418ca91-7a00-4f88-aea2-366957a8d7b6`. Expected refusals/cancellation are assertions, not hidden failures. This run does not cover every requested TTL, cross-agent, lock-mid-action or unexpected service-death scenario.

Hermes also discovered the new proposal tool. Its first proposal attempt exhausted its agent tool budget before dispatch. The next exact-payload attempt reached the phone and was correctly `DENIED / POLICY_DENIED / agent_scope` (`481056e7-628c-4afd-84cb-606ad659c3bf`). No proposal was created. Existing bearer grants were preserved rather than silently expanded. This is a successful connectivity/policy-boundary observation, not a successful Inbox submission.

Provider correlation: Grokbot native `18f65b79-fc9b-4c98-91a0-9df9f5e07196` and shell `0ed300d3-9bea-4e3b-b7e7-75d6ee45f928`; Hermes native `2670c5ac-5808-4d5a-9ce3-1a756c6ccaf6` and shell `6de52f06-3211-43f4-806f-b105c6df0086`. All four matched durable `SUCCEEDED` records with the expected NATIVE / ADB_SERVICE backends. These bearer environments still share the existing device principal; no independent-identity migration is claimed.

## Resources and unattended collection

A fresh development-annotated 61.423-second sample collected five observations. App CPU was 0.45 seconds over 60.347 valid counter seconds (0.7457% of one core); daemon CPU was 0.02 seconds over 60.382 seconds (0.0331%). App PSS ranged 65,864–88,913 KiB and RSS 200,528–222,124 KiB. Hosted calls/active development overlapped this interval. It is not a controlled idle comparison or battery estimate. Some OEM power-dump fields were truncated/unavailable and remain unknown.

The existing sparse 24-hour collector was preserved and remained running. At an interim checkpoint it contained 15 samples spanning approximately 3.5 hours, 39 successful tool results and six earlier offline results during development interruptions. Collection is incomplete; no daily-driver uptime percentage or completed 24-hour result is claimed. Private measurement files remain outside Git.

## Remaining work

1. Extend the now-passing normal-process semantic fixture to TTL expiry, cross-agent stale references, lock-mid-action and service-death recovery. Preserve identity checks and avoid needless instrumentation interruptions.
2. Complete the pending Run now confirmation, Inbox acceptance/cancel and authentication-method/lifecycle matrix, then unfolded/light/dark/font/inset QA. The real folded dark Home, native form and saved automation detail were inspected; this does not constitute the full layout matrix.
3. Finish fresh Dots/Grokbot provider checks; preserve historical success separately from fresh evidence.
4. Select a disposable writable calendar and SAF folder, then run existing real CRUD tests.
5. Verify the configured live commute forecast/reminder workflow and broader interruption/multi-agent scenarios using disposable resources.
6. Analyze the unfinished soak after it actually completes. Phone-only privileged reboot recovery remains unsupported; reboot was not performed.

Gateway endpoint, OAuth state and device enrollment remain unchanged. The gateway remains a trusted plaintext boundary. Broad shell can bypass cooperative screen leases and app-level file isolation; the new Inbox does not change that limitation. Full proposal arguments can contain private action data in device SQLite, so submit minimal source references and apply the same device-diagnostic privacy precautions as request results.

## Delivery checkpoint

Implementation commit `1992bee68aef7b7736924353d029009351c712ac` was pushed to `origin/main`. All three [GitHub CI jobs](https://github.com/lukifer23/SIConnect/actions/runs/39557617888) passed, including the new minified-release isolation checks. The working tree was clean at that checkpoint. The installed debug APK includes all production-code changes in that implementation; later documentation updates do not change the APK. The source change covers 36 files, 885 insertions and 33 deletions. No Cloudflare deployment was required; the existing gateway discovered the updated device registry. No release signing key was supplied or fabricated; the release artifact is unsigned and was not installed over the working debug identity.

Raw host-window and phone screenshot captures were cleaned after inspection. Sanitized test summaries and the unfinished active collector were preserved outside Git. No personal communication, calendar/file mutation, reboot or credential rotation was used for these checks.
