# Source review and implementation milestone — November 6, 2026

Current source: **0.6.0-dev.13 / versionCode 21 / SQLite 11 / 100 phone capabilities plus gateway status**. The pass began without a device; the owner connected a test phone during final review and authorized physical checks. Code 21 is now installed in place with the matching development signer and focused device evidence below. No gateway deployment or production-signed publication was performed. Earlier code-20 evidence remains in [pass 18](engineering-pass-18.md).

## Scope and intended product

SIConnect is an owner-controlled Android execution platform for external agents. The phone owns identity, grants, exact-action approvals, deterministic execution, verification and durable history. Its outbound gateway connection removes the development computer from the runtime path. Native APIs handle background work; authorized shared-screen tools and real private displays cover app-only jobs. Private displays currently require an awake, unlocked phone on the tested Samsung platform.

The end goal is dependable daily use: explicit access, reviewable outcomes, recoverable interruptions without duplicate effects, understandable setup/recovery and sustainable resource use. It is not yet a generally validated unattended phone operator or a production-distributed release. See [the scope and build plan](build-plan.md).

The review began at `04d828a` on `main`, whose committed Android version was code 16. The existing working tree already contained the code-18 retention, code-19 private displays and code-20 caller-lifetime/recovery increments, tests and reports. Those changes were preserved and included in this milestone. Their original implementation predates this pass; selected physical regressions were rerun on code 21 as recorded below. Publishing is directly to `main` at the owner's request, without a side branch or force push.

The live GitHub query for open repository items returned no open issues or pull requests. That does not imply an empty engineering backlog: the concrete backlog is documented here and in [next steps](next-steps.md).

## Review coverage

The repository inventory included production sources, tests, build/CI configuration, tools, integration guidance, third-party notices and every documentation path. Cross-tree checks covered conflict markers, stub/TODO markers, exact duplicate content, version/evidence conflicts, Markdown links and generated/private-file boundaries. Detailed tracing concentrated on the following paths:

| Area | Implementation reviewed | Evidence boundary |
| --- | --- | --- |
| Core runtime | Policy, approvals, durable state/CAS, retry identity, execution cancellation, workflows and proposals | JVM regressions exercise production core; physical native/idempotency and SQLite regressions pass separately |
| Android persistence | Schema-11 migrations, stored JSON, retention, requests, workflow/automation/reminder state, notification claims | Real disposable SQLite retention, migration, large-row and replay checks pass on code 21 |
| Background work | Recovery graph, bounded continuation workers, recurrence, reminder cancellation/reconciliation and health policy | Historical device results remain separate; startup/provider/clock/interruption matrix is incomplete |
| Device execution | Native files/providers, location/weather/commute, semantic controls, leases, notifications, Binder/shutdown and private displays | Native/files, actual UID-2000 shell, Calculator/Maps private displays and their cleanup pass; broader providers remain open |
| Connection and bootstrap | Gateway enrollment/socket/retry paths, explicit backend selection, local ADB discovery/bootstrap and stream handling | Host protocol tests, hosted reads and actual process-death recovery pass; cellular/Doze/reboot and new local-pairing acceptance remain open |
| Owner experience | Home/management/agents/automations, approval boundaries, commute, storage/private controls and retained state | Source/build review and narrow real-screen checks; authentication success and the full Fold/font matrix remain open |
| Gateway | Worker/Durable Object routing, OAuth/PKCE, acknowledged generations, SDK compatibility and keyed retries | 15 local protocol tests plus fresh hosted native/shell reads; no gateway deployment |
| Delivery | Gradle/dependency configuration, CI, release signing/isolation, Python tools, current and historical docs | Local artifacts checked; owner-signed distribution and package-installer journey remain open |

This is a repository-wide structural review with deeper tracing of failure and authority boundaries, not a claim that every line received equal independent scrutiny. It is not a cryptographic audit of the bundled third-party transport or a proof that no defects remain.

## Confirmed defects fixed

### Queued requests could survive cancellation as EXECUTING

A request was durably admitted before its lazy coroutine ran. Cancelling that queued job, or using an already-cancelled runtime scope, could prevent the coroutine body and its catch clauses from running. The record then stayed EXECUTING until process recovery.

`ExecutionEngine` now tracks entry into the body and finalizes cancellation from the job completion callback when dispatch never began. Exceptions before the capability is invoked retain a no-effect outcome. Once the backend has been invoked, existing uncertain-outcome rules remain authoritative.

Two new host cases reproduced the original stuck records. Both now pass with the production engine and an actual filesystem effect that must remain absent. Original-ID retry returns the original terminal result.

### Revocation could be missed during the dispatcher wait

Policy was checked before coroutine admission, but the owner could revoke the grant while an admitted coroutine waited to run. The old code then invoked the backend without another policy check.

The coroutine now rechecks current grant/controls, version/backend and availability before dispatch. A newly required approval cannot reuse an earlier auto-approved admission. Cancellation is checked immediately before invoking the capability. The regression revoked an admitted write before draining the test dispatcher: the old implementation created a real file; the corrected implementation refuses it. A positive control still writes once and retry preserves a later owner edit.

These checks do not revoke effects already dispatched, and they do not promise an atomic transaction with arbitrary Android providers or unrelated privileged processes.

### Workflow children could outlive the caller deadline

A workflow persisted a new definition-based deadline without considering `ExecutionContext.deadlineAtMs`. A short or already-expired caller could therefore start children with a later deadline.

The durable run now captures its start time once and persists the earlier of the definition deadline and caller deadline. Resume uses the original stored run. Three host cases cover caller inheritance, an already-expired caller dispatching no children and a shorter definition deadline. The first two failed before the correction.

### Owner commute controls bypassed the common execution record

Commute settings directly called `CommuteRouter.calculate` and the current-location backend. That skipped the common engine's request lifecycle and made the Maps button inconsistent with consequential owner actions.

The Maps button freezes the form, requests Android owner authentication, submits `commute.route` under local-owner origin, and resolves the exact pending request/hash through the existing owner approval path. Activity retains the original result and request ID even if Maps backgrounds the screen. Current-location lookup also submits through the engine and still requires existing Android location consent. No grant or permission is expanded.

The real owner-screen regression passes authentication cancellation and Activity recreation while the Android prompt is open, with no saved-profile change or admitted route request. Private Maps routing separately passes. Owner-authenticated shared-screen route success and current-location consent remain distinct acceptance. Reopening the screen does not restore every unsaved result/draft automatically; retained owner-screen state is still a broader gap.

### Deferred startup failure could escape into owner UI coroutines

`ControlCenterState` awaited runtime initialization outside its error handling, and Home could start dependent effects against a failed initialization. A recovery failure could therefore throw into the owner UI instead of presenting a usable failure state.

The retained state catches initialization errors while preserving cancellation. MainActivity waits before composing Home and shows a startup error with a link to Android app settings. The change preserves data and avoids pretending that initialization succeeded. It does not add automatic in-process retry, repair corrupt data or catch every possible constructor/Application failure. Android fault-injection acceptance remains pending.

### Private app launch waited for exit before draining output

The private launch path waited for `am start -W` to exit before reading its output. A verbose child could block on a full pipe; `readText().take(...)` also bounded only the final copy, not the read allocation.

Private launch now uses the existing `ShellRunner`, with concurrent bounded collectors, a ten-second timeout and a 16 KiB budget per stream. Timeout/cancellation/error/nonzero exit/truncation fails the launch and follows existing workspace cleanup. It retains explicit package/display arguments and never falls back to the shared screen. Effects after launch dispatch remain potentially uncertain. This reuses production process supervision; actual Calculator/Maps launch and caller/backend-death cleanup now pass on code 21.

## Remaining work and build order

| Priority | Finding or gap | Concrete next increment and acceptance |
| --- | --- | --- |
| P1, host work | Owner action/loading/error state is duplicated across screens; some reads remain tied to composition and forms/results are not consistently retained | Consolidate a retained owner-operation model with durable request references and explicit cancellation. Move reads to IO projections. Then verify genuine auth cancel/recreation and long reviews on the phone |
| P1, host work | Runtime initialization is one Deferred chain; failure prevents later services from starting and recovery requires a new process | Define explicit recovery stages and a safe retry contract. Inject failure at each persisted boundary and prove no duplicate action, forgotten pending work or data reset; do not retry initialization blindly |
| P1, host work | Home reads all reminders then filters; scheduler loads all definitions; active workflow lookups hydrate full JSON records | Add bounded SQL projections/paging and indexed pending-request lookup, with real disposable SQLite ordering/limit/retry tests. A bounded dispatch channel alone does not bound query allocation |
| P1, device acceptance | Owner-authenticated Maps success and startup fault presentation remain open; private caller/backend death, queued opens and revocation now pass | Verify actual owner-authenticated route success and original record readback, injected deferred startup failure, and wider private-app interruptions. Keep the new lifecycle regressions; no physical wake or keyguard bypass |
| P1, device acceptance | Provider, network and background lifecycle matrix remains incomplete | Dedicated Calendar resource and selected disposable SAF root; blocked/cancelled provider/HTTP/Binder cases; ordinary death versus force-stop; usable/offline/captive/cellular/Doze/reboot transitions; recover original IDs without replay |
| P2, storage | Payload retention is opt-in and disabled by default. Active/uncertain evidence and compact retry/notification/slot identities intentionally remain; there is no hard database quota | Measure representative growth and maintenance cost. Design admission/backpressure and archival separately from deletion. Never discard identity records just to meet a size target |
| P2, storage/performance | Workflow snapshots and `step_results` duplicate step output; event count trimming differs from age-based startup trimming | Establish read consumers and retention invariants before changing storage shape. Measure real SQLite growth/cleanup and preserve migrations and uncertainty evidence |
| P2, UX | Full cover/unfolded/font-scale/rotation/split-window/light/dark matrix and long approval review remain open | Exercise actual owner journeys with clear busy/error/expiry states; verify no accidental grant expansion and no hidden result after interruption |
| P2, platform | Optional Shizuku, wider private-app compatibility and locked/off private UI are not established | Use a legitimate supported companion/OEM mechanism and actual owner consent for any new backend. Keep the current awake/unlocked contract explicit |
| P2, release | No validated public owner-signed distribution channel; hosted availability/battery claims are incomplete | Compatible signer and schema upgrade, actual package-installer flow, monitored release checks and retained long-duration observations |

Native MediaStore, SAF copy/move, recurring-calendar timing edits, other Android profiles and richer workflow editing remain unimplemented. Add them around a concrete daily job and real verification, not catalog placeholders. The saved commute profile alone does not make an unattended traffic-aware morning workflow complete.

No new runtime stubs, mocked phone responses or placeholder feature implementations were added. Production marker checks found intentional unsupported CRUD methods on the Binder attachment `ContentProvider`; it is a `call`-based attachment contract, not an unfinished user data provider. Exact-file duplication was confined to license copies needed in module distribution/APK notices. Similar public tool variants and shared/private Maps surfaces are compatibility/execution contracts; do not delete them merely to reduce source count.

## Connected-device acceptance

The owner connected the Fold during final review and authorized all available checks. The installed code-20 APK and local code-21 debug APK were independently verified with `apksigner`; their certificate digests matched. Private pre-update database/preferences captures passed integrity checks, and two consecutive captures agreed on protected rows before installation. Code 21 was installed with `adb install -r`; the main app was never uninstalled or cleared.

**24 distinct instrumentation cases passed:**

- Four runtime cases: real native execution/idempotency and reopened SQLite readback, managed-file round trip/traversal refusal, audit-transaction rollback and the actual shell supervisor's output/timeout/cancellation behavior.
- Five private-workspace cases: real Calculator input/capture with physical focus unchanged, expired Binder admission, queued Binder cancellation, queued opens and post-revocation rejection with original outcome retained. In the eight-open cancellation case, seven were prevented before dispatch and one actually launched; all owned displays were removed.
- Ten execution-retention cases and three earlier retention/migration cases on disposable real SQLite resources, including large records, compact retry/slot identities, protected uncertainty and legacy upgrade preservation. Production retention stayed disabled.
- One real private Maps route using public Austin landmarks: observed duration 420 seconds and distance 1,931.2128 meters, expected destination matched, workspace closed and actual display removed, physical focus and lock state unchanged. This is one route observation, not a prediction guarantee.
- One new real owner-UI case: edit an unsaved commute draft, open Android authentication, cancel it, reopen it and recreate the Activity while the prompt is open. Both cancellation paths leave the saved profile and local-owner route-request count unchanged. No biometric success was simulated.

The normal-process recovery harness passed **three separate scenarios**: native app-process death with exact original-result retry and autonomous bridge recovery; private-workspace caller-process death; and private-backend process death. Both private scenarios verified actual display removal, original-result retry creating no new display, rejection of the dead session and successful fresh-session cleanup. Observed removal was 102 ms and 61 ms respectively in this run; these are single observations. Backend replacement in the private-backend scenario used the existing host bootstrap, so this pass does not newly prove phone-only backend recovery. The physical foreground stayed unchanged during these scenarios and the harness reported no cleanup errors.

After tests, all protected table hashes exactly matched the baseline: **five agent/grant/credential rows, three reminders, two automations and one selected document root**. SQLite schema 11 integrity passed. Preference comparison found only the expected `local_adb.last_bootstrap` metadata change from authorized restart. The temporary test package, UI diagnostic captures on the phone and recovery-client resources were removed. Private evidence remains outside Git.

Fresh hosted `device.status` and `shell.identity` calls succeeded after testing, on NATIVE/UID 10031 and ADB_SERVICE/UID 2000 respectively. Actual shell exit status was zero with no timeout, cancellation or truncation. Installed package readback confirms code 21. These are fresh execution results, not merely saved enrollment or a service-start acknowledgement.

Initial failures are retained in the private evidence: the signer parser expected an older output label (`Signer #1` instead of `V2 Signer`), and the new UI harness initially timed out locating fields because Android's accessibility cache retained the pre-scroll Compose tree. The corrected harness clears that cache on API 34+ and performs fresh lookups; its real cancellation/recreation case then passed. These attempts are not counted as application acceptance passes, and the first mixed Maps/UI run is not described as a clean two-case pass.

Still open: actual biometric-success commute execution, consented native location, deferred-startup fault injection, broader provider/network/Doze/reboot/shutdown boundaries, full Fold/font/theme coverage and production signing/distribution. This pass did not change Android access, owner grants, keyguard, debugging consent or live retention policy to make a test pass.

## Verification

Seven new host regression tests and one real Android owner-UI regression were added. Before implementation, three dispatch cases and two deadline cases failed. After implementation, all suites passed:

| Check | Result |
| --- | --- |
| Core JVM | 83 passed, zero failures/errors/skips |
| Android JVM | 102 passed, zero failures/errors/skips |
| Embedded ADB JVM | 2 passed, zero failures/errors/skips |
| Python tools | 13 passed; tool modules also compile |
| Gateway | 15 local protocol tests passed; TypeScript check and Wrangler dry build passed; npm audit reported zero vulnerabilities |
| Total host tests | **215 passed** |
| Focused device instrumentation | **24 distinct cases passed**, zero skips in the passing selections |
| Normal-process recovery | **3 scenarios passed**, including actual caller/backend death and original-result no-replay |
| Android packaging | Debug APK, instrumentation APK and minified unsigned release built for code 21 |
| Release isolation | Manifest excludes debug semantic fixture, retains accessibility binding; R8 usage confirms `LocalEndpoint` removed |
| Android lint | Zero errors; 88 warnings, matching the review baseline |
| Repository | Whitespace/conflict/private-path and local Markdown-link checks; remote `main` checked before publishing |

Lint warnings are existing API/KTX/style/dependency notices plus private API and bundled ADB trust-manager warnings. They are not suppressed by this pass and are not proof of safe TLS in every environment. Gradle also reports deprecated features relevant to a future Gradle 10 upgrade. No dependency upgrade is mixed into this runtime milestone.

The new dispatch tests use the production core with the repository's isolated in-memory request-store fixture and real temporary-file side effects. They do not claim Android SQLite durability. Gateway tests use a local Worker and controlled protocol peers; they do not claim a connected phone. Focused Android instrumentation and the normal-process recovery harness were executed after the phone connected. Device fixtures remain opt-in and separate from production release output.

Repeatable host commands:

```sh
./gradlew :core:test :app:testDebugUnitTest :adb-bootstrap:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :app:assembleRelease --console=plain
python3 -m py_compile tools/*.py
python3 -m unittest discover -s tools/tests -v
cd gateway
npm run typecheck
npm test
npm run build
npm audit --audit-level=high
```

Current status, scope/queue, setup/install/recovery, architecture/security, permissions, capability coverage, device testing, performance and private/background execution docs were reconciled. Dated reports retain their original evidence. The installed APK and test-owned records changed; original agents/grants/credentials, reminders, automations, selected folder and live retention policy were preserved. The bootstrap timestamp advanced during authorized backend restart. No gateway deployment, signer replacement or production release was performed.
