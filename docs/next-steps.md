# Next steps

Updated October 26, 2026. Resume from **0.6.0-dev.5 / code 13 / SQLite 10 / 85 tools** on `main`. Read [project status](project-status.md), [pass 13 handoff](engineering-pass-13.md) and [the source audit](source-audit-2026-10-26.md) first. This is the current queue; dated engineering reports retain historical evidence.

## Engineering priorities

1. **Owner-controlled retention.** Introduce explicit policy and incremental cleanup for request payloads, workflow/step results, automation history and deduplication claims. Protect active links, unknown outcomes and immutable retry identity. The existing request prune helper has no production caller; do not enable it blindly. Test notification/reminder retries after payload compaction and large-result SQLite retrieval. Measure representative database growth.
2. **Shared readiness measurements.** Stop repeating provider-root checks across every document capability and ordinary UI request update. Share bounded discovery snapshots, invalidate on access/backend/provider changes, and keep execution-time authorization fresh. Count actual provider calls before claiming a speedup.
3. **Gateway retry rollout and network recovery.** Source now exposes `_siconnectOperationKey`, tested with both SDK generations. Review/deploy to the existing endpoint/account, refresh catalogs and validate physical original-result recovery without duplicate writes. Add a coalesced usable-network signal to interrupt the current backoff; measure offline/Wi-Fi/cellular transitions without replaying mutations.
4. **Blocking IO and ADB stream contract.** Own provider/HTTP cancellation where supported; preserve uncertainty after possible mutations. Correct per-packet ADB WRTE acknowledgement and empty writes, with deterministic delayed-ACK/disconnect tests.
5. **Owner screen state.** Consolidate action/loading/error wrappers, remove composition-time database reads and finish result/form presentation. Keep public capability names compatible; preserve unrelated grants when reducing shell authority.

## Device acceptance

- Preserve the installed signer, five original agent/grant records, three reminders, two automations and selected root. Use in-place updates; verify schema integrity and actual gateway/Binder readiness afterward.
- Complete real owner authentication cancellation/interruption, long-review/recreation and shell-reduction-with-unrelated-grant checks. Do not automate biometric success.
- Exercise cancellation during a disposable mutation, workflow timeout, pending reminder approval, update/cancel/restore ordering and injected storage/startup failures. Inspect original request outcomes and absence of following steps.
- Complete cover/unfolded/rotation/split-window/large-font/light/dark review, shared feedback, reminder details and saved forms.
- Rerun the real native health-threshold case unplugged. The current USB batch has one explicit precondition failure, not a full green suite.
- Use only a verified dedicated writable test calendar; reuse the explicitly selected test SAF root with disposable cleanup. Provider revocation/restoration and recurring/attendee behavior remain separate checks.
- Validate normal-process Accessibility/listener recovery separately from instrumentation. If Android marks a service crashed, the owner may need to restore it through Settings.
- Coordinate disruptive network/reboot tests and compatible signed installation. Preserve enrollment and original request IDs. Do not repeat personal sends or broaden grants to make a smoke test pass.
- Collect matched quiet foreground/background/locked CPU/PSS/network/wakeup/database observations; account for boot/process changes and other agent activity. Then perform a retained long soak. No battery/availability improvement is yet established.

## Resume after cleanup

Follow [development setup](development-setup.md) to regenerate removed build/dependency directories. ADB can be restarted with `adb start-server`; select the authorized device explicitly. The phone's existing gateway/Binder runtime and saved agent configuration are independent of a workstation development server. See [repository hygiene](repository-hygiene.md) for preserved files and shutdown boundaries.
