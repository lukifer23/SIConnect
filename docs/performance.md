# Performance and resource evidence

Measurements below are short development observations on the actual Fold. They are not a battery-life study or a guarantee of continuous Android background execution.

| Observation | Result | Limits |
| --- | --- | --- |
| Installed Activity-checkpoint debug APK | 48,955,141 bytes | Debug build; subsequent source/build changes can alter size |
| Deterministic Hermes install flow | Five successful MCP calls: 681, 809, 2661, 706 and 790 ms; one bounded three-second installation check interval | About nine seconds for measured calls plus interval; excludes agent startup/reasoning; official app package/version/Play installer verified |
| Short Hermes agent trace | 35 tool results; 21 ordinary operations p50 717 ms / p95 927 ms | One mixed interactive run, not controlled idle profiling |
| Scripts containing UI hierarchy capture | 14 samples p50 7076 ms, maximum 120223 ms | These scripts also contained sleeps/other work; do not attribute the combined delay to Android hierarchy capture alone |
| Ordinary process recovery | New unattended shell succeeded 1.65 seconds after tested app process death/restart | One recovery check, not force-stop/reboot or long-duration uptime |

`tools/profile_trace.py --input /absolute/private/run.jsonl --out /absolute/private/profile.json` aggregates Hermes timings without copying prompts, arguments or results. Output must be outside this public repository, is created with mode 0600, and cannot overwrite an existing file. Categories describe observed scripts, not isolated backend stages. Agent/orchestration gaps can include reasoning and human interaction.

Current implementation uses one outbound WebSocket with a 90-second protocol ping and jittered exponential reconnect backoff, plus a one-minute in-process expiration sweep. Home refreshes from request/connection events rather than continuously polling. Exact reminders use AlarmManager; deferrable work uses WorkManager. A foreground service or absence of a permanent wakelock does not establish low battery cost. Network-switch reconnection behavior, Doze, Samsung background restrictions, memory/CPU, radio traffic and log growth still need controlled measurements.

Next measurements should record connected idle foreground/background/screen-off intervals, process identity, CPU deltas, memory, network bytes, wakeups/reconnection count and retained log growth. Preserve raw captures privately. Record other bot activity so it is not mislabeled idle. Compare equivalent intervals before optimizing; do not extrapolate one short sample into 24-hour battery drain.

After the approval-window gateway deployment, an official MCP v2 read-only device-status call succeeded on the real phone in 258 ms. Its stored request deadline was approximately fifteen minutes after creation, confirming the new live envelope. One call does not establish a latency percentile.

A separate 60.487-second connected foreground observation collected seven process samples through `tools/profile_device.py`. The app used 0.24 CPU-seconds (0.4% of one CPU core across that interval); the bundled daemon's counters did not increase. App PSS ranged from 127,060 to 127,960 KiB. A passive UI hierarchy read occurred during this interval. This establishes a short observed CPU/memory baseline only; it does not establish screen-off behavior, network/radio cost, idle battery drain or a before/after optimization comparison.

To repeat on an explicitly selected authorized device:

```sh
python3 tools/profile_device.py --serial <serial> --out /absolute/private/connected-profile.json --duration-seconds 60 --interval-seconds 10
```

The harness reads actual process CPU counters and memory, records process changes instead of inferring deltas across them, and writes outside the repository with mode 0600. It does not change phone settings, acquire a device wakelock or claim to measure battery drain. Sampling itself has overhead. Annotate foreground/background/screen state and other activity separately.

The subsequent Activity update avoids deserializing full request results during Home refresh. It reads indexed recent metadata, defers full output to an explicit selection, and reuses one agent-list query per refresh. The physical UI and SQLite projection were exercised. A controlled equivalent before/after memory or power comparison has not been completed, so no measured savings are claimed.

## Sparse hosted collection

`device.runtime` returns actual on-demand process CPU milliseconds, process start time, PSS/private-dirty KiB, app UID receive/transmit counters, database/WAL bytes, charging/screen state and gateway/backend health. Unsupported Android network counters can be -1. Counter differences are valid only within the same process/boot/counter lifetime. Connection-event counts follow retained event history, not lifetime totals.

Run `node gateway/test/soak-client.mjs /absolute/private/gateway-config.json /absolute/private/new-soak.jsonl 24 900` for real hosted read-only samples every 15 minutes. Config and output must be outside the public repository; output is exclusive-create mode 0600. The collector uses the official MCP client, invokes actual tools and records failures rather than substituting fixtures. It neither wakes the screen nor posts notifications. Normal owner use, APK updates and other agents must be considered when interpreting counters. SIGTERM/SIGINT records a stopped interval; do not describe it as completed. A Mac running this measurement client is not a gateway/runtime server.

Pass 3 actual hosted calls after the first gateway update succeeded in 167–346 ms for six native tools and 235 ms for shell identity in one seven-call run. Separate fresh Hermes calls took 370/437/262 ms for device status, shell identity and package inspection. During the locked-phone workflow, the successful state/package/disposable shell/reminder calls were recorded privately with their request IDs and timings. These are individual development observations, not controlled percentile comparisons or proof of low battery cost. The subprocess implementation removes two unconditional 100 ms waits on successful execution; no battery saving is inferred from that code change.

The collector has started and initial samples succeeded, including actual runtime CPU/PSS/network/database measurements. It remains in progress and includes normal development/owner activity. Version/process/boot changes must be separated before calculating deltas.

The 0.3.0 debug APK measures 36,703,377 bytes in this build. One real structured package result was 323 JSON bytes (output only), rather than a full package dump. Two locked-phone shell fixture commands reported backend durations of 111 and 76 ms; their Hermes round trips were 426 and 398 ms. These are two observations, not a percentile or controlled optimization comparison.


Pass 4 adds an on-demand Home resource card using the same authorized `device.runtime` capability. It reports process PSS, cumulative CPU time and app code/data/cache storage through Android APIs; opening Home does not start continuous resource polling. One actual Hermes observation on 0.4.1 reported 87,141 KiB PSS, 2,505 ms cumulative process CPU, 37,235,200 code bytes, 7,401,472 data bytes and 204,800 cache bytes. This was mixed owner/development activity, not a controlled idle or battery measurement. Native runtime and detailed package calls took 493 and 409 ms in that sample.

The earlier sparse collector lost its output during host cleanup. Its observed samples are not a completed retained soak result. A new, separate 24-hour interval was started with 15-minute read-only sampling; it must be analyzed from its own timestamps and marked interrupted if stopped. Do not delete active collector output or Gradle caches as disposable cleanup. The new brand uses vector resources and the platform font, adding no image/font runtime dependency.


A fresh 60.552-second connected interval after app-process recovery sampled five times: app CPU increased by 0.17 seconds (0.2833% of one core), daemon CPU by 0.00 seconds at the available counter resolution; app PSS ranged 48,393–49,161 KiB. This interval is not an equivalent before/after comparison. Foreground/idle classification was not independently captured per sample, so the file name does not establish controlled foreground idle. No battery/radio/24-hour inference follows. The observation harness should add per-sample process importance and screen state before comparing lifecycle modes.


The installed 0.4.2 checkpoint debug APK measured 36,529,327 bytes before the subsequent small catalog/layout-only increment. Debug artifact size is build-specific. The real Hermes chain highlighted a raw package result of approximately 71k characters; registry descriptions now direct normal package checks to the existing compact typed operation. No end-to-end latency/token saving is claimed until that workflow is repeated with the compact operation.


The compact native package check against the installed 0.4.2 app returned a 323-byte output object and a 3,100-byte complete MCP result in 211 ms in one hosted observation. Updated raw-tool selection guidance was confirmed through live discovery. The earlier Hermes raw-result character count is a different measurement boundary, so no percentage/token reduction is calculated from those two numbers.


## Pass 6 resource observation and methodology

One 61.161-second collection was annotated `owner-use`; five actual samples reported foreground-service importance (125). Across four continuous process/boot/version intervals, app CPU increased by 0.09 seconds over 60.023 counter-observed seconds (0.1499% of one core). App PSS was 43,613–46,570 KiB. Daemon counters increased by 0.0 seconds over 60.066 seconds. This is a short background-service observation during normal owner use, not controlled idle, an optimization comparison or battery consumption. Missing OEM screen/power fields remain unknown; keyguard-showing false is not proof of `isDeviceLocked=false`.

`profile_device.py` retains report-v1 compatibility with methodology revision 2. It now records bounded probe diagnostics, exact version code, process importance/RSS, available power/keyguard flags and salted boot-session brackets. CPU summaries validate every adjacent interval across PID/start/boot/version/counter and host clock/sleep continuity. Flushed JSONL checkpoints survive interruption; interrupted/failed/complete collection states are explicit. Six added parser/counter tests and four existing Python tests pass.

The existing 24-hour sparse collector remains running. At the current checkpoint seven 15-minute samples contained fifteen successful read-only calls and six `DEVICE_OFFLINE` results during active upgrade/instrumentation work. This approximately 90-minute, development-contaminated span is not a completed 24-hour soak or a daily-driver availability estimate. Outputs were preserved and not restarted.

Overview metadata refresh is event-driven and unsubscribes while the owner screen is not collecting it. Semantic waits use accessibility events rather than persistent tree polling. No before/after resource improvement or agent-token saving is claimed without matched measurements.
