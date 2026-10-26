# Repository hygiene and shutdown

Pass 14 resumed development with incremental build caches retained for rapid iteration. The current installed app is an optimized code-14 development build, and both disposable test packages are absent. The private recovery artifact is updated to that compatible code-14 APK. The inventory/cleanup below records the earlier pass-13 cleanup; it is not an instruction to repeatedly discard active build caches. See [pass 14](engineering-pass-14.md).

October 26, 2026. This checkpoint covers SIConnect source, its generated local artifacts and its development processes. Other projects, owner applications and shared system caches are outside the cleanup scope.

## Inventory

The initial tracked tree contained 259 files totaling about 1.9 MiB; none exceeded 1 MiB. Exact-content hashing found four duplicate groups, all required license copies: module notices mirrored into APK assets, plus the SPAKE2 C license notice/full-license files. They remain intentional; removing them would weaken distribution notices. No accidental source duplicate was found. `.git` occupied about 7.1 MiB.

Generated clutter included roughly 289 MiB of Android app build output, 6.3 MiB of core output, 3.3 MiB of ADB output, 27 MiB of root build reports, 61 MiB of project Gradle state, 285 MiB of installed gateway dependencies and 3.3 MiB of gateway bundles. Finder metadata, Python bytecode and project-specific temporary logs/captures/scanner copies were also present. These are approximate allocated sizes at inventory time, not a claimed final reclaimed-byte measurement.

The shared user Gradle cache was about 2 GiB. It is used across projects and is retained. Source, the Git object database and ignored local SDK/gateway/signing configuration are retained. No phone databases, agent enrollment or saved owner records are deleted for host hygiene.

## Cleanup boundaries

After verification and daemon shutdown, these generated workspace paths can be regenerated and are removed:

- Root `build`, `.gradle`, `.kotlin` and `.DS_Store`.
- `app/build`, `core/build` and `adb-bootstrap/build`.
- `gateway/node_modules`, `gateway/dist`, `gateway/.wrangler` and `gateway/.DS_Store`.
- Python `__pycache__` directories under `tools`.

Current project-specific `/tmp` validation databases/APK copies, expired screenshots/logs/research downloads and duplicate scanner copies are disposable after sanitized results and preservation checks are recorded. They must never be moved into the public tree. Temporary cleanup does not include arbitrary `/tmp` contents or other projects.

One compatible **code-13 debug recovery APK** and private metadata (version, schema, APK SHA-256, Android source commit and installed-signer comparison) are retained at `/tmp/siconnect-recovery-20261026`. This directory is private and outside Git. `/tmp` is ephemeral: move that artifact to an owner-controlled private destination if long-term retention is needed. It is an APK recovery convenience, not a backup of device data or a production-signed release.

Preserve `local.properties`, `gateway/.dev.vars`, Android Keystore/debug signing identity and any existing owner signing material. Build reports are summarized in [pass 13](engineering-pass-13.md); CI can regenerate them. Never clean active caches while builds are running.

## Cleanup result

The first cleanup removed **107 generated paths / 13,038 files**, totaling **828,771,019 logical bytes** and **866,070,528 allocated bytes** before deletion (about 826 MiB allocated). This measures the removed files; APFS sharing/snapshots can make actual free-space changes differ. SDK/gateway configuration and the compatible recovery APK were verified present afterward. The shared Gradle cache and phone data were retained. Final temporary secret-scan copies are removed after publication checks.

## Processes and connections

At this checkpoint `./gradlew --stop` stopped one daemon, and `adb kill-server` completed after final device checks. No local Worker/test/profiler/bootstrap process remained in the pre-cleanup process inspection.

For future sessions, stop Gradle with `./gradlew --stop` after all builds finish. Local protocol tests tear down their Worker/device peer; verify no Wrangler/workerd, test, profiling or bootstrap command remains. Stop any remaining project-specific process by a verified PID, avoiding broad process-name termination that could affect another project. End the workstation ADB server with `adb kill-server` after final device checks.

Preserve the phone app, its foreground gateway service and bundled privileged daemon so existing agent connections can continue. Host ADB shutdown disconnects workstation USB/wireless debugging transports; it does not delete Android pairing records, reset agent enrollment or stop the phone's outbound runtime. Do not terminate owner client apps, other agents, the operating system or shared connector processes as part of repository cleanup.

## Rebuild and reconnect

```sh
./gradlew :core:test :app:testDebugUnitTest :adb-bootstrap:testDebugUnitTest :app:assembleDebug :app:lintDebug
./gradlew :app:assembleDebugAndroidTest :app:assembleRelease
python3 -m unittest discover -s tools/tests -v
cd gateway
npm ci
npm run typecheck
npm test
npm run build
```

From the repository root, restart workstation debugging with `adb start-server` and select the current authorized serial explicitly. Reuse saved credentials/enrollment; do not re-pair agents to replace deleted build output. Read [development setup](development-setup.md), [phone recovery](phone-setup.md) and [installation/signing](installation.md) before updating the installed app.

Before publishing, inspect `git status`, whitespace errors, local Markdown links and tracked file types; scan the complete candidate/staged tree and the final commit for secrets. Hash/configuration continuity checks and secret scanners do not prove absence of every possible personal detail; manually review new public prose and exclude raw evidence. Push only `main`, then verify its commit and CI result.
