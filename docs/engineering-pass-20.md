# Pass 20: native Termux commands and private-screen efficiency

November 6, 2026. Installed **0.6.0-dev.17 / code 25**, main SQLite schema **11**, native Termux journal schema **1**, **104 phone capabilities / 105 MCP tools**. Updates used the existing compatible development signer and preserved enrollment.

## Real workflow evidence

The owner shared the Dot's successful ten-message Google Messages summary. Retained code-24 requests corroborate actual backward movement (delta −297) and changed visible content. All seven Messages steps succeeded: 49.530 seconds elapsed, about 1.140 seconds from phone receipt through response enqueue, with 48.423 seconds between calls. The intervals include delivery, agent/client processing and pauses; they do not isolate model/network latency. Terminal observations also corroborate Codex's successful login. Private message content and login URLs are excluded from this report.

## Native Termux bridge

Four registered capabilities use the official Termux RUN_COMMAND service: `termux.status`, `termux.execute`, `termux.result`, and `termux.recent`. Commands run as Termux's actual Android UID in background mode; no terminal UI or key injection is used for command execution. Existing shell policy, live per-agent scopes, revocation, expiry and remote pause remain enforced, including rechecks after waiting for a dispatch slot. Full control remains persistent and covers the new family without changing existing saved grants.

The app requires the one-time Android Run commands in Termux permission and Termux's external-app setting. On this authorized test device the requested permission was granted and the single allow-external-apps property was configured through the verified owner Termux shell, retaining other settings. The shipped app never rewrites Termux's private configuration; Settings supplies the owner setup route and real verification.

A separate SQLite journal commits dispatch identity and nonce hash before IPC and bounded results before waking a waiter. The result token is one-shot, narrowly mutable and explicit to a nonexported, package-bound receiver. Real Termux internal success is −1, while shell success is exit 0; source review caught and corrected the initial zero-sentinel assumption before native acceptance. Results remain available after timeout or app death, without changing the original terminal core outcome or replaying work. Recent history is caller-scoped, paginated metadata only. [Protocol and limits](termux-integration.md).

Owner-enabled retention also covers completed callback payloads. Unknown or missing original core outcomes and unresolved callbacks remain protected. A bounded keyset cursor advances past protected old rows so they cannot starve eligible completed history. Metadata and replay identities remain. Production retention stays disabled.

## Performance and UI

Private inspection accepts an AND selector for bounded text/description, exact resource/class identity and boolean states. It returns actionable references only for matches, bounded scan/time/byte metadata and honest incomplete-search status. Password subtrees and target ownership remain enforced. A real fixture returned its selected scroll container as one 468-byte node in 5 ms; a one-node scan budget returned an empty incomplete search rather than a false no-match guarantee.

Selected Activity details now refresh only for matching requests. Transport-only updates refresh bounded audit rows without decoding a selected large result again. Lifecycle restart reloads authoritative SQLite. Connection history no longer invalidates on every response enqueue.

Home distinguishes runtime, screen, notification and privileged readiness and shows actual saved agent mode/expiry. The developer catalog is searchable and grouped. A dedicated Termux page shows actual permission and callback evidence, identity-bound background verification and explicit saved Codex login checks, with durable request IDs and existing owner approval. Authentication output is suppressed at source.

## Physical acceptance

Eight focused device cases passed with no skips in their corrected runs: two real Termux IPC cases, two disposable SQLite journal cases, one core/journal retention integration, one real private scroll/filter/capture case, and two owner UI recreation/cancellation cases. The first UI batch omitted its opt-in flag and skipped two UI cases; both were rerun with the flag and passed. Earlier failures/skips remain privately retained.

Three normal-process scenarios also passed: actual Termux UID/output, timeout followed by real late completion, and actual SIConnect PID death while the Termux command was observed running. Android/the existing daemon restored the endpoint without a foreground Activity or host bootstrap; the real callback was retained, the original core state stayed UNKNOWN_OUTCOME, and same-ID retry produced exactly one EXECUTING transition and one execution-start audit. Physical focus stayed unchanged. The first PID attempt stopped before killing anything because the process listing was truncated/interpreter-dependent; the corrected observer uses wide argv and the actual UID plus unique marker, and only that case was rerun.

An additional real background check on the home screen confirmed Codex CLI 0.167.0 and a saved signed-in session without changing physical focus or printing authentication output. The initial raw npm launcher failed because /usr/bin/env was unavailable in that background environment. Invoking the JavaScript launcher through Termux Node succeeded; the owner check now selects that route for npm entrypoints. No new model inference request was issued by this pass.

Host checks pass **207 tests** (83 core, 122 Android JVM, two embedded ADB), debug and instrumentation assembly, minified unsigned release assembly, and lint with zero errors. Development signing remains in use; this is not public production distribution acceptance.

## Handoff and limits

Normal-process readback verifies code 25, gateway ONLINE, selected privilege available, native screen control and Termux execution IMPLEMENTED, and database integrity OK. The five original agent identities/credentials/grants, three reminders, two automations and folder grant are unchanged, apart from ordinary last-seen updates. The intended Dot still has Full control, no expiry and its saved previous profile. Temporary clients and fixture/test packages were removed; completed/uncertain execution evidence was preserved. Accessibility was restored after instrumentation and its actual binding verified.

The existing ChatGPT/Dot Primary OAuth account was refreshed to **105 tools (48 write / 57 read)**, including all four Termux tools, without reauthentication or account replacement. Grok showed a connector Error during disruptive device work; a fresh observation after gateway recovery shows Connected. Its final tool count remains unverified because its details panel did not open reliably. No authentication cause was established from accessible host logs. No messages were sent to bots during that connector refresh; the subsequent owner-requested timer tests are recorded below.

Broader app compatibility, quiet-resource/battery measurements, locked/off-screen acceptance, sustained load and a long availability soak remain open. Continue UI refinement against real owner workflows. Keep all raw DBs, host logs, command output, credentials, device identity and screenshots outside Git.

## Subsequent Grok timer test

At the owner's explicit request, one Grok chat message requested a 19-minute phone timer. The chat input duplicated the text within that single sent message after delayed insertion; no second message was sent. Phone audit shows one SET_TIMER request for 1,140 seconds, successful shell execution and a Grok start confirmation. The owner's separate cancel request did not establish automated cancellation; the owner reported canceling manually.

This exposed a specific permission mismatch: Grok uses the older development-client identity displayed as Personal bot. It retains broad shell access but notification-action discovery and semantic UI search were outside its custom scopes and returned POLICY_DENIED. The owner completed the one-time authentication for that identity through SIConnect. Full control is now saved without expiry, with its previous custom profile retained and zero waiting requests replayed. Fresh own-identity discovery confirms notification actions, notification invocation, semantic search and private opening are in scope and IMPLEMENTED. This is an intentional owner-authorized permission change after the preservation checkpoint above. The active Dot's Full profile is independent and remains saved. No failed/terminal timer action is replayed by enabling a profile.

The fresh owner-authorized Grok test then succeeded end to end without another approval: one 1,140-second timer started at 23:50:39 CT; native notification discovery exposed the real Cancel and Pause actions; Grok invoked Cancel at 23:50:53; Android alarm history recorded the matching timer removal with `Reason=alarm_cancelled` at 23:50:53.123. Notification readback also showed it gone. All six requests succeeded, with no approval-wait transitions. Phone processing totaled 374 ms across a 23.520-second workflow; inter-call intervals account for the rest and do not isolate model/network delay. The action dispatch result itself remains honestly unverified; the later alarm-system observation establishes cancellation. The native cancel did not require opening Clock or controlling the shared screen.
