# Next steps

Updated October 26, 2026. Resume from **0.6.0-dev.8 / code 16 / SQLite 10 / 89 phone capabilities + gateway status** on `main`. Read [project status](project-status.md), [pass 15 findings](engineering-pass-15.md) and [the source audit](source-audit-2026-10-26.md) first. This is the current queue; dated engineering reports retain historical evidence.

## Engineering priorities

1. **Owner-controlled retention.** Introduce explicit policy and incremental cleanup for request payloads, workflow/step results, automation history and deduplication claims. Protect active links, unknown outcomes and immutable retry identity. The existing request prune helper has no production caller; do not enable it blindly. Test notification/reminder retries after payload compaction and large-result SQLite retrieval. Measure representative database growth.
2. **Profile discovery and client orchestration.** Shared per-refresh document probes and bounded UI metadata reuse are implemented. Keep execution checks fresh. The live nine-tool provider case uses one access probe; twenty metadata reads reuse it. Measure missing providers and equivalent UI workloads before claiming general speedup. Profile the gateway's stateless catalog/schema setup separately from model/client time.
3. **Controlled connectivity and retry recovery.** The retry field is deployed and actual read/write deduplication, conflict rejection and cleanup passed. Validated-network callbacks now wake timed backoff. Next reproduce usable-network/offline/captive/Doze transitions and socket replacement, retaining original request identity. Grok's native-argument/schema interpretation was incorrect; use fresh MCP discovery and actual SQLite executions. No universal interruption root cause or fix is established by one passing rerun.
4. **Blocking IO and ADB stream contract.** Own provider/HTTP cancellation where supported; preserve uncertainty after possible mutations. The code-15 patch corrects per-packet ADB WRTE acknowledgement and empty writes; focused real in-flight cancellation/stream acceptance remains open.
5. **Owner screen state.** Consolidate action/loading/error wrappers, remove composition-time database reads and finish result/form presentation. Keep public capability names compatible; preserve unrelated grants when reducing shell authority.

## Product gaps from real weather use

The live forecast succeeded after the owner supplied a destination; no rain reminder was warranted. Tightened alarm/reminder/weather scope descriptions address the misleading conclusions. Commute preferences, permission-aware native location and Maps screen route readers are implemented in code 16. No routing API credential is needed. Future departure selection uses actual Maps controls; confirm the selected date/time and duration range, and keep the phone unlocked. Native GPS consent, desired arrival/wake/preparation preferences, and per-client scope choices remain owner inputs. Do not infer wake/departure timing from a null next-alarm observation. See [the real workflow findings](engineering-pass-14.md#real-tomorrow-weather-workflow).

## Device acceptance

- Preserve the installed signer, five original agent/grant records, three reminders, two automations and selected root. Use in-place updates; verify schema integrity and actual gateway/Binder readiness afterward.
- Complete real owner authentication cancellation/interruption, long-review/recreation and shell-reduction-with-unrelated-grant checks. Do not automate biometric success.
- Exercise cancellation during a disposable mutation, workflow timeout, pending reminder approval, update/cancel/restore ordering and injected storage/startup failures. Inspect original request outcomes and absence of following steps.
- Complete cover/unfolded/rotation/split-window/large-font/light/dark review, shared feedback, reminder details and saved forms.
- Rerun the real native health-threshold case unplugged. The earlier USB batch has one explicit precondition failure, not a full green suite.
- Use only a verified dedicated writable test calendar; reuse the explicitly selected test SAF root with disposable cleanup. Provider revocation/restoration and recurring/attendee behavior remain separate checks.
- Validate normal-process Accessibility/listener recovery separately from instrumentation. If Android marks a service crashed, the owner may need to restore it through Settings.
- Coordinate disruptive network/reboot tests and compatible signed installation. Preserve enrollment and original request IDs. Do not repeat personal sends or broaden grants to make a smoke test pass.
- Collect matched quiet foreground/background/locked CPU/PSS/network/wakeup/database observations; account for boot/process changes and other agent activity. Then perform a retained long soak. No battery/availability improvement is yet established.

## Resume after cleanup

The minified single-app code-14 build is installed and both test packages are absent. Follow [development setup](development-setup.md) for incremental builds and the opt-in installation checkpoint. Avoid rebuilding or expanding device tests unless a specific change or failure requires it. ADB can be restarted with `adb start-server`; select the authorized device explicitly. The phone's existing gateway/Binder runtime and saved agent configuration are independent of a workstation development server. See [repository hygiene](repository-hygiene.md) for preserved files and shutdown boundaries.
