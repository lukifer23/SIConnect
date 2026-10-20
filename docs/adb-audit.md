# Device audit: Samsung Galaxy Z Fold 7

The audit uses the read-only harness `tools/adb_audit.py`, connected over wireless debugging. Raw output is never committed. This page holds only build-level facts and capability conclusions; serials, user names, network addresses and package inventories are omitted.

Audit date: 2026-10-20.

## Platform

| Property | Value |
| --- | --- |
| Model | SM-F966U (Galaxy Z Fold 7, US) |
| Android | 17 (SDK 37.0) |
| One UI | 9.0 (`ro.build.version.oneui=90000`) |
| Security patch | 2026-09-05 |
| ABI | arm64-v8a |
| Build | `user`, verified boot `green`, bootloader locked, `ro.debuggable=0` |
| SELinux | Enforcing |
| Root | None (`su` absent) |
| RAM | ~10.8 GiB total |
| Users | 2 (owner plus Secure Folder, user 150) |
| Display | 1080x2520 at 420 dpi (cover screen while folded) |

## Privilege findings

- The ADB shell runs as `uid=2000(shell)` with groups `input`, `log`, `adb`, `sdcard_rw`, `readproc`, `net_bw_stats` and `uhid`, in SELinux context `u:r:shell:s0`. This is the authority a Shizuku-hosted service gets.
- Shizuku 13.5.4 is installed but was not running at audit time. Upgrading to 13.6+ enables non-root auto-start on trusted Wi-Fi (Android 13+).
- Commands that enumerate packages without `--user 0` fail: the shell cannot access user 150 (Secure Folder). SIConnect passes `--user 0` explicitly.
- `/data/data` is not readable by shell: app-private data stays inaccessible, as expected.

## Framework surface (via shell)

| Area | Finding |
| --- | --- |
| Services | 525 binder services. `activity`, `package`, `notification`, `alarm`, `jobscheduler`, `accessibility`, `appwidget`, `statusbar`, `input` and `settings` are all present. |
| Packages | 606 packages for user 0, of which 106 are third-party. `dumpsys package` exposes version and install times. |
| Roles | SMS: Google Messages. Home: One UI Home. Dialer: Samsung. Assistant: Google. |
| Alarms | `dumpsys alarm` shows the Samsung clock's next wake alarm. `cmd alarm` does not offer `get-next-alarm`. |
| Settings | Shell can list the `system` (454), `secure` (399) and `global` (544) namespaces. |
| Calendar provider | `content query` on `content://com.android.calendar/calendars` succeeds from shell. |
| SMS provider | A query with an impossible predicate returned "No result found" instead of a security exception, so shell appears to have read access. Real rows were deliberately not read. |
| UI | `uiautomator`, `input` and `wm` are available to shell. |
| Accessibility | Accessibility is disabled. One notification listener is enabled (a Samsung accessory app). |
| Thermal | HAL temperatures are readable: AP about 29 C, battery about 28 C. |

## Implications for SIConnect

1. Shizuku is the privileged backend (ADR-001). Its capabilities include `pm install/uninstall --user 0`, `settings get/put`, `dumpsys`, `cmd` and `input`.
2. The calendar, notification, SMS and next-alarm features use native APIs first. Shell equivalents are a fallback only when the owner explicitly selects them.
3. Google Messages holds the SMS role and RCS is private to it. Sending uses `SmsManager` (SMS only) or notification reply actions. RCS content is not claimed.
4. The sideloaded APK needs **Allow restricted settings** in App info before notification-listener or accessibility access can be enabled.
5. Commands must always target user 0 explicitly to avoid Secure Folder `SecurityException`s.

Re-run with:

```sh
tools/adb_audit.py --list-devices
tools/adb_audit.py --serial <serial> --out /path/outside/repo/audit.json
```
