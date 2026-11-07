# Next steps

Updated November 6, 2026. Resume from source **0.6.0-dev.16 / code 24 / SQLite 11 / 100 phone capabilities + gateway status**. Read [the current audit](source-audit-2026-11-06.md), [the scope/gap review](build-plan.md) and [project status](project-status.md). The installed checkpoint remains code 23 during the live Dot test. Code 23 contains the control-center/adaptive-layout refresh and explicit indefinite Full control profile, and retains verified existing Grok/Dots connections. The owner enabled Full control for the intended Dots identity; real Clock create/stop calls worked without repeated SIConnect approvals. Next: finish private-scroll/cache fixes, verify normal-process Accessibility coexistence, and rerun the explicit pending-continuation fixture with bounded backend readiness when the live Dot session is idle. Code-21 evidence covers private caller/backend death, queued opens and revocation. Remaining acceptance is listed separately. Publish coherent verified milestones directly to `main`, including their docs. Preserve unrelated work and inspect current Git state before editing.

## Work without a connected phone

1. Consolidate retained owner-action state and move remaining storage reads out of composition; preserve drafts, errors and durable request IDs through recreation. Exercise genuine authentication later on the device.
2. Page/filter reminder and automation projections in SQLite instead of hydrating entire histories. Use disposable real SQLite fixtures and verify limits, ordering, retention protection and late retry behavior.
3. Extend controlled startup/storage-failure coverage with explicit recovery stages and failure reporting. Keep requests with uncertain effects nonreplayable; never reset data to make recovery pass.
4. Measure representative retained-history workloads before designing quotas or archive deletion. Permanent identity records are correctness state.

Seven new host regressions cover queued cancellation/revocation, authorized original-result replay and workflow deadline inheritance. The November 6 pass also fixed owner commute dispatch, startup error presentation and bounded private launch.

## Engineering priorities with a device

Private app execution is now implemented. Use [pass 18](engineering-pass-18.md) for current caller-lifetime/background evidence and [pass 17](engineering-pass-17.md) for code-19 Calculator/Maps acceptance and [the execution contract](private-app-execution.md) for ownership, permission and power limits. Validate a concrete daily workflow on the chosen apps before expanding compatibility claims. The locked/off route remains platform-dependent: real companion association or OEM ComputerControl support plus actual consent/acceptance is required on this Samsung build. Do not substitute a physical wake request or silently open the shared screen.

1. **Caller lifetime and controlled execution/recovery acceptance.** Code-21 private-window caller/backend death, queued-open cancellation and revocation cases now pass on the actual awake/unlocked phone; keep these regression checks and do not wake or bypass keyguard. Native app death/original-result retry, cold bridge restoration and phone-local backend replacement now have locked-phone evidence. Reproduce cancellation during a disposable mutation, workflow timeout, pending reminder approval and update/cancel/restore ordering. Inject startup/storage and blocked provider/HTTP/Binder failures independently. Verify original outcome, no following step, no duplicate action and bounded resource cleanup. The code-15 IO/ADB fixes still need focused physical interruption acceptance.
2. **Owner screen state.** Consolidate action/loading/error wrappers, remove composition-time database reads and finish result/form presentation. Verify saved forms and long reviews on Fold/rotation/large-font layouts and genuine owner authentication cancellation. Preserve unrelated grants when reducing shell authority.
3. **Controlled connectivity and retry recovery.** Caller retry identity is deployed; validated-network callbacks wake backoff. Reproduce usable-network/offline/captive/Doze transitions and socket replacement with original IDs. Use actual stored executions and fresh discovery to distinguish client/schema errors from phone failures.
4. **Complete native journeys.** Use a dedicated writable test calendar and selected SAF root. Verify one configured weather/reminder journey and the inherited Maps departure/arrival control work. Keep location consent, screen readiness and new client scopes explicit.
5. **Measure operating cost and retention.** Shared discovery/metadata reuse and opt-in execution retention are implemented. Measure missing providers, matched UI/client workloads, gateway catalog construction and real database growth/cleanup. The synthetic retention fixture shrank from 6.31 to 0.31 MiB; it is not a production soak. Compact retry/notification/slot identities deliberately remain. Consider hard quotas, Inbox archival and evidence-based unknown-outcome resolution separately.
6. **Distribution acceptance.** Validate the owner-signed workflow/channel, compatible updates and actual package-installer journey. Production signer custody and publication remain owner decisions.

Completed in code 18: owner-reviewed 7/30/90/365-day payload expiry, disabled by default; incremental workflow/request/automation cleanup; permanent retry identity; compact history; reminder/notification retry handling; large SQLite record reads. Do not enable production retention merely to run a test.

## Product gaps from real weather use

The live forecast succeeded after the owner supplied a destination; no rain reminder was warranted. Tightened alarm/reminder/weather scope descriptions address the misleading conclusions. Commute preferences, permission-aware native location and Maps screen route readers are implemented in code 16. No routing API credential is needed. Future departure selection uses actual Maps controls; confirm the selected date/time and duration range, and keep the phone unlocked. Native GPS consent, desired arrival/wake/preparation preferences, and per-client scope choices remain owner inputs. Do not infer wake/departure timing from a null next-alarm observation. See [the real workflow findings](engineering-pass-14.md#real-tomorrow-weather-workflow).

## Device acceptance

- Code 21 is installed with verified signer/schema continuity. Cancellation/recreation, private launch and caller/backend death now pass. Next verify genuine authenticated commute success and its original Activity record, engine-routed current location consent, startup failure presentation and deliberately stalled private launch cleanup.
- Preserve the installed signer, five original agent/grant records, three reminders, two automations and selected root. Use in-place updates; verify schema integrity and actual gateway/Binder readiness afterward.
- Complete real owner authentication cancellation/interruption, long-review/recreation and shell-reduction-with-unrelated-grant checks. Do not automate biometric success.
- Exercise cancellation during a disposable mutation, workflow timeout, pending reminder approval, update/cancel/restore ordering and injected storage/startup failures. Inspect original request outcomes and absence of following steps.
- Complete cover/unfolded/rotation/split-window/large-font/light/dark review, shared feedback, reminder details and saved forms.
- The code-20 real native health/cooldown case now passes unplugged, using actual readings and a labeled disposable notification. Keep the earlier USB precondition failure historical. Continue wider warning/recovery checks separately.
- Use only a verified dedicated writable test calendar; reuse the explicitly selected test SAF root with disposable cleanup. Provider revocation/restoration and recurring/attendee behavior remain separate checks.
- Validate normal-process Accessibility/listener recovery separately from instrumentation. If Android marks a service crashed, the owner may need to restore it through Settings.
- Coordinate disruptive network/reboot tests and compatible signed installation. Preserve enrollment and original request IDs. Do not repeat personal sends or broaden grants to make a smoke test pass.
- Collect matched quiet foreground/background/locked CPU/PSS/network/wakeup/database observations; account for boot/process changes and other agent activity. Then perform a retained long soak. No battery/availability improvement is yet established.

## Resume after cleanup

The current installation is the compatible code-23 debug build; code-14 minified installation notes are historical. Follow [development setup](development-setup.md) for incremental builds and the optional minified installation checkpoint. Recheck normal-process gateway/Binder readiness after instrumentation; saved enrollment alone is insufficient. Select the authorized device explicitly. Retain build caches for iteration, preserve private configuration and keep device evidence outside the repository. See [repository hygiene](repository-hygiene.md) for shutdown boundaries.
