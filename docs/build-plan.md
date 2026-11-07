# Current scope, gaps and build plan

Updated November 6, 2026. Current implementation checkpoint: **0.6.0-dev.16 / code 24 / SQLite 11 / 100 phone tools plus gateway status**. The current control-center refresh adds dedicated unfolded layouts, persistent focused settings and result inspection, and verified existing Grok/Dots catalogs. The preceding source/host/backend review installed code 21 and ran focused physical checks. Read [the current audit and evidence](source-audit-2026-11-06.md), [project status](project-status.md) and historical [pass 18](engineering-pass-18.md).

Code 24 source addresses observed private scrolling/capture and adds phone transport timing; code 24 is installed and normal-process readiness plus private scroll/capture acceptance passed. See [pass 19](engineering-pass-19.md) for evidence and pending acceptance.
## What we are building

SIConnect lets an authorized external agent do useful work on the owner's Android phone. The phone owns permissions, approvals, execution, verification and durable history. Its outbound gateway connection lets it work without the development computer. Native APIs handle background jobs. Private app displays let supported installed apps operate while the owner uses the shared screen; this device still requires the phone awake and unlocked. Existing semantic controls/leases cover explicitly allowed shared-screen work. The selected privileged backend supplies real shell/display authority without switching identities automatically.

The finished product needs to make five promises understandable and dependable:

1. An agent has a distinct identity and only the access the owner chose.
2. Approval covers the exact reviewed operation; cancellation and revocation have defined limits.
3. A lost response, process restart or duplicated alarm never authorizes repeating an old action.
4. The owner can tell what ran, what was verified, what is waiting and what remains uncertain.
5. Setup, recovery, resource use and compatible updates remain practical on a daily-use phone.

## What is already implemented

| Area | Existing foundation | Remaining work |
| --- | --- | --- |
| Authorization and execution | Shared policy engine, exact-action approvals, current grants/deadlines, persistent state transitions, idempotency and uncertain outcomes | Real authentication cancellation/interruption and more injected failure/concurrency cases |
| Remote connectivity | OAuth/PKCE and private bearer; outbound phone socket; acknowledged generations; original-result lookup; caller retry keys | Controlled offline/captive/Doze/socket-replacement matrix and long availability measurements |
| Native operations | Diagnostics, package inspection, managed files, selected SAF roots, Calendar Provider, reminders, weather and consent-aware location | Dedicated-calendar acceptance; location consent; provider revocation/interruption coverage |
| Device and screen control | Bundled Binder shell, optional Shizuku, phone-local bootstrap, semantic references/leases, notification actions; code 19 adds real private app displays, private Maps, keyboard isolation and owner shared-screen restriction | Private interaction/lifecycle acceptance, broader app compatibility; legitimate locked/off virtual-device backend; reboot/network and accessibility recovery |
| Automation | Declarative workflows, durable cursors, recurrence/timezones, frozen scheduled slots, manual runs, cancellation and health cooldowns | Physical mutation/timeout/cancellation races, startup/storage failures and wider time/lifecycle acceptance |
| Owner experience | Adaptive Home/Agents/Automations/Activity/Settings, Action Inbox, setup/recovery cards, commute preferences | Shared retained action state, remaining composition-time reads, Fold/rotation/font-scale and long-review checks |
| Storage | Schema migrations, indexed history, event trimming, health ledger; code 18 adds opt-in execution retention and large-row reads | Real workload growth/cleanup timing; retained identity growth; future Inbox/archive and uncertainty-resolution policy |
| Distribution | Compatible in-place development updates, minified release build and owner-signing workflow source | Owner-controlled production signer/channel and actual phone download/package-installer acceptance |

Implementation and acceptance are different. A missing Android permission or client scope does not mean a capability is absent. A passing host test does not establish reboot, battery or every provider's behavior.

## November 6 execution and owner-flow increment

Code 21 closes the dispatcher wait gap: current authority is rechecked when the execution coroutine actually runs, and cancellation before it starts records a terminal result without dispatch. Workflow deadlines are bounded by both the definition and caller. Commute settings use owner authentication and the durable engine for Maps, and the engine for location. Failed deferred runtime initialization is presented without composing dependent screens. Private app launch now uses the existing bounded process supervisor. Seven new host regressions pass. Code-21 native/storage/private-display and process-death acceptance now passes; owner-authenticated route success and wider Android fault coverage remain open.

## Background recovery and caller-lifetime increment

Code 20 binds private resources to the actual calling process’s Binder token, with caller-generation checks and cleanup after interrupted launches. Owner-only capabilities require both the local-owner principal and trusted origin in the common policy engine. Fixed, policy-routed bridge maintenance can use a ten-second own-package startup allowance after Android rejects normal startup; it preserves saved enablement and advanced/backend controls. Existing phone-local recovery is now verified after actual daemon death without host bootstrap. Native controller recovery and cold bridge restoration also pass while locked. The private-window interruption rerun was still pending in [pass 18](engineering-pass-18.md); the awake/unlocked caller/backend-death cases now pass on code 21, as recorded in [the current audit](source-audit-2026-11-06.md#connected-device-acceptance).

A retained five-minute background observation and the real unplugged native health/cooldown check close specific measurement/acceptance gaps, with no battery or broad reliability claim. Original owner data and permissions remain protected.

## Private app execution increment

Code 19 adds nine real `headless.*` operations and `commute.route_private` through the existing engine and authenticated Binder. Each session binds an agent/package/display, verifies own focus/no top-focus stealing, hides its software keyboard, captures only its own surface and expires within ten minutes. Fresh semantic references reject stale/foreign/protected controls. A selected-backend binding, queued cancellation/deadlines, owner generation invalidation and kernel-owned singleton lock cover concrete authority/lifecycle gaps found during review.

Agents can now be restricted to native background and private-display tools. Arbitrary shell and all unspecified/shared-screen interaction modes are blocked before standing grants. Owner-authenticated private app allowances remain separate from scopes and existing shared-screen permissions. Existing grants are unchanged by the update.

The actual Samsung platform keeps ordinary virtual displays in physical power group 0 even with the own-group flag. Broad locked/off UI needs a legitimate companion virtual device or OEM ComputerControl provisioning and acceptance. No extra manifest permission alone fixes it. The implementation refuses locked/sleeping private UI and never requests physical wake or a shared-screen fallback. See [the complete execution contract](private-app-execution.md) and [pass 17](engineering-pass-17.md) for actual acceptance rather than inferring it from source.

## Previous retention increment

Execution history can now retain full payloads indefinitely or expire completed payloads after 7, 30, 90 or 365 days. Enabling expiry requires local review and owner authentication. Existing installations keep retention disabled.

Incremental maintenance compacts request arguments/results, finished workflow snapshots/step outputs and completed automation snapshots. Active work, uncertain outcomes and unresolved links remain protected. History keeps original outcomes and IDs; permanent compact identities, alarm-slot claims and notification deduplication claims prevent late replay. Request and workflow reads now tolerate JSON larger than Android's cursor row limit. Reminder and notification retries use their retained action binding after compaction.

This closes the first implementation item in the previous roadmap. It does not impose a hard database quota: compact identities and unresolved evidence intentionally remain. See [the retention contract](engineering-pass-16.md).

## Build order from here

Without a device, prioritize retained owner-operation state, paged storage projections and restart/recovery fault coverage. These have concrete exit criteria in [the November 6 audit](source-audit-2026-11-06.md#remaining-work-and-build-order). The following device-dependent queue resumes when a phone is available; it is not a reason to stop host engineering.

| Priority | Concrete next increment | Exit evidence |
| --- | --- | --- |
| 1 | Broaden a concrete daily workflow and finish owner-facing acceptance | Code-21 Calculator/Maps, caller/backend death, canceled opens and revocation now pass. Next verify genuine authenticated commute success, startup fault presentation and the full owner-screen lifecycle |
| 2 | Controlled execution/recovery acceptance | Disposable mutations interrupted at named boundaries; original IDs recover known/unknown outcomes; no following step or duplicate execution; bounded cleanup of blocked provider/HTTP/Binder work |
| 3 | Consolidate owner operation state | One loading/error/cancellation pattern across Settings, Inbox and Automations; storage reads outside composition; saved drafts and long reviews survive recreation; real owner authentication cancellation behaves correctly |
| 4 | Connection and background reliability | Separate usable/offline/captive/Doze transitions, reconnect generations and ordinary process death; fresh status plus original-request recovery; no credential replacement or implicit replay |
| 5 | Complete native resource journeys | Dedicated test-calendar CRUD; selected-folder cancellation/revocation/restore; one owner-configured weather/reminder journey; verified Maps departure/arrival preview on the unlocked screen |
| 6 | Measure routine operating cost | Matched foreground/background/locked CPU, PSS, network, wakeups, reconnection and database observations; representative retention workloads; then a documented long soak |
| 7 | Distribution and release acceptance | Compatible owner-signed artifact, upgrade preservation and actual package-installer journey; declared recovery limits and a repeatable release checklist |

The weather workflow currently evaluates forecast thresholds and can create reminders. Maps route preview has shared-screen and private-display variants; the saved commute profile does not itself make the whole journey an unattended traffic-aware automation. Any next integration must carry freshness, destination binding and screen-readiness requirements through the existing engine.

## Scope that needs a concrete job before expansion

SAF copy/move, MediaStore integration, recurring-calendar timing edits, other Android profiles and richer workflow editing are not implemented. Broad shell access may cover some owner-authorized jobs, but it does not substitute for typed native behavior or verification. Prioritize these additions when a specific daily workflow needs them.

The gateway remains a trusted forwarding component, with the phone as durable execution authority. End-to-end agent signatures, a durable offline gateway execution queue, a local model/chat interface and universal unattended privileged reboot recovery are outside the current implemented scope.

## Evidence and operating boundaries

The connected phone began this review at code 17/schema 10, ahead of the code-16 current docs. Existing Maps/connection edits were preserved and carried forward. Codes 18–20 update the existing application with its compatible signer. Private checkpoints cover the five original agents/grants/credentials, three reminders, two automations and one selected root. No location/calendar consent, new agent scopes, production cleanup, credential rotation, main-app uninstall or data clear is required for this pass.

Use [next steps](next-steps.md) as the current execution queue. Dated engineering reports describe the builds actually tested at that time; they do not update themselves when source changes.
