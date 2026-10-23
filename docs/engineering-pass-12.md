# Engineering pass 12

Starting commit: `29325d42bc9e73732a8d2166cfc5ad6ec5e0abfd`, clean `main`.

## Implemented changes

- Health snapshot v3 adds persisted condition identities, cooldowns, recovery margins, severity escalation and bounded alert metadata through the existing notification capability and scheduler. Existing exact stock snapshots require owner-confirmed upgrade; customized definitions are preserved.
- Schema 10 adds only the health alert ledger. Existing identity, reminder, proposal and workflow tables are retained.
- The engine rechecks current policy and backend selection inside the execution lock immediately before dispatch. A host regression reproduced the previous remote-stop race before the fix.
- Automation cards distinguish required screen interaction from optional screen interaction and unavailable backend/permission dependencies. Results show actual dispatch delay and readable warning outcomes.
- Home distinguishes blocking new remote actions from disconnecting the gateway. Activity exposes active records and owner-authenticated per-request cancellation. Already executing work is not claimed to stop automatically.
- Inbox decision controls remain visible below the scrolling review, and changing proposals resets the review scroll. Exact action details remain available.
- Headless application initialization attempts restoration of the saved owner-enabled bridge using the existing foreground service. Android restrictions produce an explicit blocked state instead of a false connection indicator.

## Fresh physical observations

The owner saved a disposable 15-minute health schedule through the actual Compose interface and Android authentication. Its configured timezone was preserved. The first future slot ran with the phone screen off and locked; the workstation ADB server was stopped during the waiting interval. No manual Run now or external bootstrap command completed this run.

- First scheduled run: 26 ms after its slot, 132 ms total, six real native readings successful, warning intentionally off, recurrence advanced 15 minutes.
- Second scheduled run after an in-place APK update: 38,634 ms after its slot, 156 ms total, successful, recurrence advanced. The owner was using another application; this second run is not a screen-off result.
- Two disposable Inbox proposals were accepted with real owner authentication. Each has one distinct linked request with `SUCCEEDED` and a completed proposal. The item named cancel was approved rather than cancelled, so cancellation is not counted as verified.
- The development APK update preserved the actual installed certificate and app data. SQLite schema 10 readback passed `quick_check`. The original agent/grant baseline, reminders and automation records were retained in private observations.
- Baseline hosted runtime and shell identity calls succeeded with screen off/locked and actual Binder UID 2000. A later post-update hosted request returned `DEVICE_OFFLINE`; native scheduling continued. After the correction, an in-place update performed while another app was foregrounded was followed by seven successful hosted native/Binder checks, 177–259 ms each. Instrumentation subsequently caused `ForegroundServiceStartNotAllowedException`, which was recorded as blocked; this is not an ordinary process-death result.

Private evidence stays outside Git. Database copies used for readback are guarded observations, not atomic recovery backups.

## Verification and remaining work

Host verification: core 49, app 66, ADB module 2 tests, all passing with no skips. Debug APK, Android-test APK, lint and unsigned minified release built successfully. Release manifest/R8 checks confirmed the debug fixture and loopback endpoint are excluded. Gateway clean install, typecheck, ten integration tests, dry-run bundle build and npm audit passed with zero reported vulnerabilities. Python tooling: 13 passing.

Physical instrumentation: eleven distinct alert, migration, retention and document cases passed across the focused run and corrected document rerun, with no skips. The initial document round trip failed at `AWAITING_APPROVAL`, before a file was created: the old harness used the default owner identity while expecting unattended writes. The correction uses a disposable expiring identity with standing writes constrained to the explicitly selected test root, then removes that identity. No existing grant is expanded. The initial cleanup fixture also had a JUnit return-type validation failure; its corrected run passed and disabled only the disposable schedule, preserved other schedules and cancelled the exact abandoned fixture request. Cleanup is not counted as an additional product acceptance case.

The selected SAF provider passed actual create/write/read/hash/rename/metadata/delete/absence checks. No cloud sync is inferred. The test folder remains selected and empty. The disposable future schedule is disabled after three successful scheduled runs, preserving their results.

Pending: actual authentication cancellation, dedicated test-calendar CRUD, full owner-operated setup/network matrix, health-v3 owner upgrade, complete Fold/font UI QA, signing workflow/package-installer validation. The resulting main commit requires its own [GitHub Actions result](https://github.com/lukifer23/SIConnect/actions/workflows/ci.yml). No fresh reboot, network-transition matrix, Grokbot/Dots/Hermes session regression or quiet battery study is claimed. Existing provider credentials, gateway deployment and local ADB identity were not rotated.

This is a focused source audit of the execution boundary, health/scheduler path, owner UI, notification delivery, database migration and bridge lifecycle. It is not an exhaustive audit of every production source file.

## Resource and security scope

Debug APK: 45,297,886 bytes. Unsigned minified release: 4,814,882 bytes; it is not an installable signed production release. The existing development signer was reused for the installed update; no new key or credential was generated or uploaded. A separate 4,860,033-byte minified preview was signed locally and its certificate matched the actual installed predecessor. It has not been installed or published; the GitHub owner-signing workflow remains unexercised.

A 60.741-second development-contaminated profile recorded app PSS 69,835–74,262 KiB and RSS 177,192–180,324 KiB. A process change invalidated the whole-interval app CPU estimate; three valid subintervals consumed 0.15 CPU seconds over 43.894 seconds. The privileged process consumed 0.01 CPU seconds over 59.923 seconds (0.0167% of one core). These are development observations, not a matched idle comparison, battery estimate or completed soak.

No dependency was added or cryptographic implementation replaced. Strict Gradle checksums, restricted JitPack, pinned LibADB/SPAKE2, OkHttp 5.5.1 and Bouncy Castle 1.86 remain. Publisher sources were consulted; npm audit is not a complete native supply-chain or legal-compliance audit. LGPL relinking/distribution review and the broader advisory inventory remain release gates. See [embedded ADB source and notices](../adb-bootstrap/README.md).

Broad authorized shell still carries shell-identity authority beyond narrow native UI grants. This pass adds no remote ADB bootstrap endpoint. Neither a network reconnect nor alert suppression authorizes replaying an uncertain mutation.

## Final device preservation check

Five original agent credential hashes and grant hashes match the private baseline. Three original reminders and the original automation remain unchanged. The disposable test schedule alone is disabled. SQLite quick-check passes. The selected test folder is empty and retained for future owner-approved checks.

After the final background APK update, all seven official-SDK hosted checks passed again: six native diagnostics and Binder shell identity, 161–310 ms each. The catalog remains 85 tools. No desktop Activity launch or external daemon bootstrap was used to restore this connection. Existing Grokbot/Dots/Hermes application sessions were not freshly exercised.

Release packaging inspection found SPAKE2 in all four ABIs: arm64-v8a 28,192 bytes, armeabi-v7a 28,888, x86 42,156 and x86_64 35,128, plus the existing AndroidX graphics-path libraries. Seven license assets are packaged. Presence of notices and ABI files is not proof of complete license compliance or runtime testing on all ABIs.

## Hosted lost-result acceptance

Using the existing authorized identity, one remote shell mutation appended a byte to a uniquely named disposable temporary file, then waited before returning. Independent readback observed the byte before the official SDK client aborted and closed its transport. The original SQLite request was located without resubmitting the mutation; authenticated `requests_get` returned its original `SUCCEEDED` result under ADB_SERVICE. Final file size was exactly one byte. A separate authorized cleanup removed that exact fixture file and succeeded. No owner prompt, grant expansion or personal file was used. This proves this hosted client-abort/result-retrieval case, not a Durable Object restart or every transport-loss timing.

Linux CI passed for implementation commit `3cd1d06` ([run 39903227036](https://github.com/lukifer23/SIConnect/actions/runs/39903227036)) and documentation checkpoint `ab0015c` ([run 39903499538](https://github.com/lukifer23/SIConnect/actions/runs/39903499538)): Android/core/release isolation, gateway and public hygiene/secret scan all green. Runner annotations flag older Node-20-targeting action versions and the ubuntu-latest image transition; these are remaining CI maintenance items, not failed jobs. Dependency verification stayed enabled.

## Remaining priorities

1. Offer convenient owner-reviewed standing access for recurring native mutations, without expanding unrelated agents or prompting on every already authorized run. Default owner writes correctly still require approval; the SAF harness now makes its own bounded fixture authorization explicit.
2. Upgrade the installed stock health definition through its existing owner flow, complete actual cancellation/interrupted review and use a deliberately selected test calendar. No further prompts were sent after the owner requested a pause in approvals.
3. Complete cover/inner-screen, font/rotation/theme and setup-interruption QA, including physically inspecting the new fixed Inbox decision controls.
4. Validate network transitions and a separately authorized workstation-independent reboot. Distinguish instrumentation force-stop restrictions from ordinary service recovery.
5. Exercise signed distribution through Android's package installer, complete native dependency/relinking review and run a genuine quiet interval/longer availability observation. Fresh Grokbot/Dots/Hermes application sessions and weather delivery also remain open.
