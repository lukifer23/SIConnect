# Performance and resource evidence

Measurements below are short development observations on the actual Fold. They are not a battery-life study or a guarantee of continuous Android background execution.

| Observation | Result | Limits |
| --- | --- | --- |
| Installed UI-checkpoint debug APK | 48,955,141 bytes | Debug build; subsequent source/build changes can alter size |
| Deterministic Hermes install flow | Five successful MCP calls: 681, 809, 2661, 706 and 790 ms; one bounded three-second installation check interval | About nine seconds for measured calls plus interval; excludes agent startup/reasoning; official app package/version/Play installer verified |
| Short Hermes agent trace | 35 tool results; 21 ordinary operations p50 717 ms / p95 927 ms | One mixed interactive run, not controlled idle profiling |
| Scripts containing UI hierarchy capture | 14 samples p50 7076 ms, maximum 120223 ms | These scripts also contained sleeps/other work; do not attribute the combined delay to Android hierarchy capture alone |
| Ordinary process recovery | New unattended shell succeeded 1.65 seconds after tested app process death/restart | One recovery check, not force-stop/reboot or long-duration uptime |

`tools/profile_trace.py --input /absolute/private/run.jsonl --out /absolute/private/profile.json` aggregates Hermes timings without copying prompts, arguments or results. Output must be outside this public repository, is created with mode 0600, and cannot overwrite an existing file. Categories describe observed scripts, not isolated backend stages. Agent/orchestration gaps can include reasoning and human interaction.

Current implementation uses one outbound WebSocket with a 90-second protocol ping and jittered exponential reconnect backoff, plus a one-minute in-process expiration sweep. Home refreshes from request/connection events rather than continuously polling. Exact reminders use AlarmManager; deferrable work uses WorkManager. A foreground service or absence of a permanent wakelock does not establish low battery cost. Network-switch reconnection behavior, Doze, Samsung background restrictions, memory/CPU, radio traffic and log growth still need controlled measurements.

Next measurements should record connected idle foreground/background/screen-off intervals, process identity, CPU deltas, memory, network bytes, wakeups/reconnection count and retained log growth. Preserve raw captures privately. Record other bot activity so it is not mislabeled idle. Compare equivalent intervals before optimizing; do not extrapolate one short sample into 24-hour battery drain.

After the approval-window gateway deployment, an official MCP v2 read-only device-status call succeeded on the real phone in 258 ms. Its stored request deadline was approximately fifteen minutes after creation, confirming the new live envelope. One call does not establish a latency percentile.
