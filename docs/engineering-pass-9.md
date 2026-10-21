# Engineering pass 9

October 21, 2026. Starting commit `947a1c1662222000f10f5f2a3e9ad6b05168d342`, branch `main`. Android **0.5.0-dev.3 / versionCode 8**, SQLite **v8**, **85** hosted-discovered capabilities. This is a development checkpoint. The audit focused on proposal persistence, dispatch, policy, owner review, runtime maintenance and release isolation; it was not an exhaustive fresh audit of every subsystem.

## Implemented changes and confirmed defects

- Expired, unopened proposals previously continued occupying the 100-active-proposals-per-agent quota. Bounded expiration reconciliation and indexed counts now exclude expired pending proposals without requiring individual review. Repeated submission of the original idempotency key returns its expired proposal without extending its lifetime.
- A transient exception before execution-record insertion previously left an accepted proposal waiting for startup recovery. The existing 60-second runtime maintenance tick now reconciles bounded candidate batches. A retry is permitted only after a successful authoritative lookup finds no request, and uses the original immutable request ID, arguments and deadline. A recorded request, including an unknown outcome, is reconciled rather than resubmitted. Database lookup failure is not proof that execution never started.
- Inbox lists previously decoded full stored proposal JSON, despite omitting arguments from remote responses. SQLite v8 adds bounded list metadata and indexes; list queries no longer select private arguments, reasons, sources or shell assessments. The additive migration streams existing rows, preserving original JSON, identities, revisions and request links. It never recreates owner data. Startup recovery uses bounded keyset pages rather than loading every active proposal.
- Home now counts all unexpired pending proposals and previews three; older pending work is no longer hidden behind the most recent 50 resolved proposals. Inbox adds status and stable-agent filters, both sort orders, saveable keyset cursors, risk/time summaries and on-demand exact arguments.
- Owner review now evaluates actual capability availability and the original agent's current policy before authentication. Acceptance evaluates them again afterward. Denial, revocation, expiration, backend loss and emergency stop cannot become a new permission grant. The UI distinguishes additional execution approval, waiting for a request record and an unknown outcome, and links to recorded execution results. It clears old detail data while loading another item and prevents duplicate work-button dispatch.
- A debug-only normal-process Inbox harness prepares two safe native-status proposals for real owner accept/cancel testing. It uses isolated expiring credentials, authenticated loopback and the production remote execution path. It exposes no acceptance/approval endpoint, does not automate Android authentication and refuses a locked/off screen before provisioning. Cleanup removes precisely its session identity and credential file; accepted request history remains available. This harness is prepared and syntax-checked; its owner workflow has not yet run.

## Fresh verification

| Classification | Result |
| --- | --- |
| Core JVM | **48 passed**, no failures/skips; includes expired-quota/deduplication, post-review policy changes, transient pre-insert recovery and accepted-action expiration |
| Android JVM | **36 passed**, no failures/skips |
| Python unit | **13 passed**, no failures/skips; scripts compile |
| Gateway host integration | Typecheck, **10 tests**, build/dry-run passed; no production deployment |
| Android builds | Debug, instrumentation APK, lint, minified unsigned release passed |
| Release isolation | Actual release manifest excludes the debug fixture, preserves accessibility declaration; R8 usage confirms debug loopback removal |
| Physical upgrade | Same package/signing identity; in-place install and actual schema **7→8** upgrade; SQLite integrity `ok` |
| Physical instrumentation | **4 passed**, 0.118 seconds: authenticated proposal/native linked execution exactly once, schema-6 upgrade, populated schema-7 migration/list payload isolation, filtered bidirectional equal-timestamp pagination |
| Data preservation | All **five** original agent credential/grant hashes matched; **three** reminders and **one** saved disabled automation retained |
| Hosted official SDK | **7 successful** real calls, 85 tools; six native diagnostics plus actual bundled shell UID **2000** |
| Hermes provider | Fresh existing-configuration native/shell pair succeeded; IDs independently matched phone SQLite with `REMOTE` origin and expected backends |
| Grokbot provider | Attempted existing window in background; submission could not be confirmed before the window became inaccessible. **No fresh success claimed** |
| Dots provider | Existing identities preserved; existing conversation remains unavailable for a safe fresh application-level check. **Blocked**, no new OAuth connection |
| Owner Inbox accept/cancel | **Blocked**: phone remained locked. Harness setup correctly refused before creating a disposable session |
| Semantic UI | Enabled service was bound at baseline. Instrumentation restart subsequently left it in Android's crashed-service set. Owner off/on restoration remains required. No fresh semantic mutation pass claimed |
| Calendar / SAF | **Blocked** pending explicit disposable calendar and Android-picker folder selection; no personal data used |
| Automation / commute | Existing disabled automation preserved. No fresh form/run-now or configured live commute delivery completed; owner configuration/authentication remains pending |
| Notification / messaging | No fresh reply fixture or personal send; previous evidence remains historical |

Host gateway tests use an isolated device peer; they are not physical result-loss verification. Trusted local proposal resolution in instrumentation is not owner biometric verification. The earlier pass's full semantic fixture success remains historical and is not counted again.

Sanitized fresh Hermes correlation: native `b424b4c6-aa34-4e9d-b6de-fc1cb9c7f1e3`; privileged identity `cc908ed1-61f5-45c1-bbf3-d7ebed98db3f`. Both are durable `SUCCEEDED` records. The shared Grok/Hermes bearer principal was preserved; independent revocation has not been implemented by silently splitting it.

## Physical lifecycle and resource observations

The bundled authority was restored through the existing authorized ADB bootstrap after upgrade. Native and privileged hosted operations worked with the Fold locked/screen off. Accessibility screen actions still require a functioning owner-enabled service and unlocked screen. Android's instrumentation start/finish lifecycle again left configured accessibility in the crashed/disconnected set; no secure-setting writes were used to restore it. Repeated instrumentation should be batched before the owner's final restoration.

A 61.011-second agent-use annotated interval collected five real samples. App CPU was **0.42 seconds / 59.983 valid seconds (0.7002% of one core)**; daemon CPU **0.01 / 59.965 seconds (0.0167%)**. App PSS was **82,630–88,557 KiB**; RSS **234,948–238,364 KiB**. Lock state was separately confirmed; some OEM power-dump fields were unavailable. This interval overlapped remote work and is not a quiet-idle comparison or battery estimate. No measured speedup or token saving is claimed for the metadata-query change.

The retained sparse collection was not restarted or overwritten. Interim evidence at **08:37 UTC** contained **24 samples**, spanning **02:49–08:37 UTC**, with **66 successful tool results** and **six DEVICE_OFFLINE results** from earlier development interruptions. Successful tool latency median was **340.5 ms**, maximum **1,961 ms**. The 24-hour collection remained unfinished; no daily-driver availability percentage is inferred. Private profiles, device records and credentials remain outside Git.

## Initial failures preserved

One new Android test compilation failed on Kotlin's mixed-value `arrayOf` inference; an explicit `Array<Any?>` fixed it and all four physical tests then passed. An initial hosted command was run from the repository root, which has no `package.json`; the actual gateway-directory rerun passed all seven calls. Neither failure is counted as a successful test. The locked-screen owner harness refused without changing phone state. Grokbot's background send was unverifiable, followed by an exact-window accessibility failure; it is not represented as provider execution.

## Security, limitations and next owner steps

There is still one registry, policy engine, execution store and approval system. No remote owner-origin or proposal-acceptance tool was added. Proposal text remains untrusted display data. Fresh current-policy checks supplement execution-time authorization; they do not replace it. Broad authorized shell can still bypass cooperative semantic leases, and the trusted gateway can still see forwarded credentials/plaintext results. Public-source checks found no live credential/device-identity values in changed files. The unsigned release was not installed over the working debug signing identity.

1. Unlock the Fold, restore SIConnect accessibility off/on in Android Settings if still disconnected, and leave the app available. Run the normal-process Inbox setup described in [device testing](device-testing.md). Complete one real accept authentication and cancel the other, verifying no linked request is allocated by cancellation; then dismiss and clean up only those fixtures.
2. Complete semantic TTL/cross-agent/window and service-loss cases after that restoration, using the normal-process route. The full edge-case matrix remains open.
3. Explicitly designate a disposable writable calendar and select a disposable SAF folder in Android's picker. Then run the existing real Provider/document CRUD tests.
4. Complete the saved diagnostics automation's owner-confirmed Run now and configure location/timezone/wake/departure for the live commute workflow. Do not infer missing owner settings.
5. Expose the existing Dots/Grokbot sessions safely, verify fresh read-only IDs against SQLite and finish quiet soak analysis after the actual interval ends. No new credentials or broader grants are required merely to diagnose connectivity.

Final implementation SHA, push and CI result are recorded in the delivery message and Git history. No Cloudflare source, OAuth identity, endpoint or deployment was changed in this pass.
