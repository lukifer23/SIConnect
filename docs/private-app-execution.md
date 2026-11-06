# Background and private app execution

Updated November 6, 2026. Current source and installed debug build: code 21 / 0.6.0-dev.13 / SQLite 11. Private launch now uses bounded process supervision; actual Calculator/Maps, queued cancellation/revocation and normal caller/backend process-death acceptance pass. See [the current audit](source-audit-2026-11-06.md). Historical device evidence remains in [pass 17](engineering-pass-17.md) and [pass 18](engineering-pass-18.md).

## Execution surfaces

| Surface | Actual mechanism | Physical screen requirement |
| --- | --- | --- |
| Native background | Existing Android/provider/network APIs through the request engine | No screen interaction; individual permissions and Android background restrictions apply |
| Private app workspace | Real shell-owned virtual display, ImageReader frames, display-scoped UiAutomation semantic nodes/actions | Phone already awake and unlocked; no wake request, unlock or shared-screen fallback |
| Shared screen | Existing accessibility/screen leases, app launches or explicitly approved shell | Explicit owner permission; screen readiness and existing policies apply |

Native operations remain preferable when an API covers the job. A private display is useful for a supported installed app that only exposes the required behavior through UI. It runs the actual app using its existing account, storage and network. It is not a separate Android profile or a sandbox against arbitrary app behavior, notifications, audio or external services.

## Owner controls

In Agents, choose the exact identity. **Allow shared-screen actions** can be disabled without authentication because it reduces authority. Re-enabling it requires actual Android owner authentication. Disabled means only capabilities declared BACKGROUND or ISOLATED_SCREEN may execute, even with broad scopes, standing approval or broad shell. Arbitrary shell and unspecified interaction modes are refused. Existing agents retain their earlier shared-screen permission by default; installation does not silently change grants.

Allow the **Private app workspaces** capability family separately. Under **Automatic private app actions**, choose an installed app and explicitly allow its private actions. Owner authentication is required for expansion. The saved allowance binds that agent, exact package and selected ADB_SERVICE or SHIZUKU backend. It covers open/click/text/scroll/close, potentially including sends or deletes in that app. Removing it preserves unrelated grants. A private allowance neither grants the family scope nor authorizes another app/backend.

Settings provides **Close all private workspaces** through the existing local-owner engine. It remains usable after advanced execution is disabled when the selected Binder is still alive. Remote agents cannot invoke this cleanup operation. New tool scopes are never added to existing clients automatically.

## Real tool sequence

The catalog adds nine phone capabilities: `headless.open`, `headless.status`, `headless.inspect`, `headless.capture`, `headless.click`, `headless.set_text`, `headless.scroll`, `headless.close`, and local-owner-only `headless.stop_all`. MCP names replace dots with underscores. `commute.route_private` is a separate fixed Maps recipe using the same private workspace implementation and existing commute policy.

1. Read `headless.status` for owned sessions and actual physical readiness.
2. Open an exact installed package, optionally with a bounded http/https/geo deep link. An app already visible on another display is refused to avoid moving the owner's visible app.
3. Inspect the returned session and exact package. Android's actual application window must be on that private display and match the package before nodes or actions are returned.
4. Use the fresh snapshot/node references for semantic click, text entry or scroll. Explicit `headless.click(inputMethod=PRIVATE_TOUCH)` injects a real touch at the freshly verified node center with the owned display ID set and read back on each event; caller coordinates and default-display input are excluded. Maps uses this for number-picker widgets that will not enter edit mode through semantic click. Inspect again after each mutation. Password subtrees, foreign packages and protected system/owner management surfaces are excluded. The owned display’s software-keyboard policy is set to HIDE and read back before app launch, so private text entry cannot fall back to a physical keyboard surface; semantic text entry remains available. The platform requires INTERNAL_SYSTEM_WINDOW for this display policy ([AOSP implementation](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r3/services/core/java/com/android/server/wm/WindowManagerService.java)).
5. Capture a bounded JPEG from the session's own rendered surface when needed. No physical screenshot API is called. Close the session when finished.

`actionAccepted` means Android accepted semantic dispatch. It does not prove a message arrived, a payment completed or another external business outcome. Text entry separately reports actual readback. Optional `commit=true` sends a display-bound Tab only after verifying the field/window and reports focus release; Maps additionally verifies retained picker values and selected route time. Lost/interrupted mutations retain the engine's uncertain-outcome semantics; never replay them blindly.

The Maps recipe launches real driving directions on its display, reads observed duration/distance and closes in a non-cancellable finally block. It does not use guessed speed, a routing API stub or a fabricated forecast. Current-route and future departure/arrival acceptance passed as separate real-device cases in pass 17. It does not start navigation.

## Bounds and lifecycle

The backend admits at most two workspaces, one per agent. Defaults are 720×1280 at 240 dpi, two minutes. Dimensions are bounded to 320–1080 by 480–1920, density 160–480, at most 2,073,600 pixels. Lifetime is 15 seconds to ten minutes. Expiry is checked on calls and by a fifteen-second maintenance task; physical release may occur up to one maintenance interval after expiry if no call arrives.

Inspect scans at most 1,000 nodes and returns at most 200 with a 192 KiB row budget. Depth is bounded. A snapshot expires after thirty seconds and is replaced by the next inspect. Actions re-read window identity, path, node fingerprint, enabled/visible state and password protection. JPEG capture is limited to 128 KiB. Requests and replies use the existing authenticated Binder and reliable bounded pipes, with shared stream admission limits; no extra network listener or execution engine is added.

App launch uses the common `ShellRunner` with a ten-second timeout and a 16 KiB output budget per stream. Both pipes are drained during execution. Timeout, cancellation, nonzero exit, transport error or output truncation fails the launch; cleanup releases the display and no default-display fallback is requested. A launch may already have produced app effects, so failure after dispatch is not reported as proof of no side effects. Actual code-21 Calculator/Maps launch and cleanup pass; the shared supervisor’s real timeout/output/cancellation test also passes. A deliberately stalled private app launch remains a distinct fault boundary.

Each workspace dispatch gets a unique cancellation identity and a bounded deadline. Queued operations atomically enter RUNNING or CANCELLED; canceled/expired work cannot start later. Cancellation after dispatch cannot undo an already committed action. Cleanup uses its own dispatch identity/deadline. The selected backend is carried through dispatch rather than reselected from mutable settings.

The direct daemon uses a kernel file lock to prevent competing bootstrap paths from creating independent authorities. Workspace calls carry the actual app process’s Binder lifetime token. Its death invalidates queued work and closes only that caller generation’s displays; sessions cannot be resumed by a new process. Provider attachment lifetime is used for reconnecting authority. Code-21 normal-process acceptance verifies caller death and backend death, actual display removal, original-result retry without reopening, and fresh-session recovery on an awake/unlocked phone. Backend process death releases platform-owned surfaces/tasks. The daemon's live lock inode must not be deleted during cleanup. Sessions are intentionally not restored after restart.

## Samsung locked/off limitation

The previously tested Android 17 Samsung build preserves OWN_DISPLAY_GROUP as a display flag but assigns these ordinary virtual displays to physical power group 0. Passive dumps and inspection of the shipped framework explain why: separate-timeout grouping finds an existing empty-name group before own-group allocation; Samsung's virtual-secondary-desktop feature is disabled. Its VirtualDisplayConfig builder exposes no client group-name override.

A direct per-display power request returned accepted yet the display returned to OFF under group policy. A diagnostic display-bound screen wake lock also woke the physical panel; that path was removed and the original sleeping/locked state restored. Production code never requests screen power or global wakefulness. A CPU-only partial wake lock is bounded by session lifetime and does not keep the physical screen awake.

The real alternative platform routes have additional conditions:

- VirtualDeviceManager requires a real supported companion association belonging to its caller. A permission declaration alone does not create one.
- Samsung's association-free virtual-device shell command explicitly rejects a secure keyguard.
- ComputerControl is present in shipped framework code but disabled by the OEM feature property on this phone. Even enabled, it requires applicable permissions, agent allowlisting/role/signature conditions and actual owner consent.

These are future backend candidates requiring legitimate provisioning and real acceptance. The shipped separate_timeouts consumer also uses a compiled-true supplier, so no supported runtime override is established on this artifact. No association, consent, permission or locked UI result is fabricated. No lock credential was changed, debug feature enabled or system-server patch installed. Custom platform/kernel work would need an actual supported deployment path and validation; it is not implied by app-level shell access.

## Background bridge recovery

The bridge uses the owner’s saved enabled preference. Code 20 can request `system.restore_bridge` through the engine after Android rejects normal startup: local-owner identity and origin, advanced controls and the selected backend are required. Its fixed ten-second startup allowance applies only to SIConnect and does not persist a battery exemption. A paused gateway stays paused. Native process-death, cold-service restoration and owner-enabled phone-local daemon recovery have real locked-phone evidence in pass 18; they do not guarantee every Doze/network/reboot condition.
