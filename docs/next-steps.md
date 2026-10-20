# Next steps

Ordered by dependency:

1. **App foundation.** Add the SQLite `RequestStore`, `EventSink`, agents and settings stores, `Runtime` wiring, and `recover()` on start. Install the app on the Fold 7.
2. **Native capabilities.**
   - Device status and diagnostics.
   - Apps: list, inspect and launch.
   - Notifications through the listener: list, post and reply.
   - Calendar CRUD on a dedicated test calendar.
   - Reminders through AlarmManager.
   - Next alarm.
   - Weather from Open-Meteo.
   - App-scoped files.
3. **Shizuku backend.** Add the UserService (`IPrivilegedShell`), health and identity reporting, `shell.execute`, `apps.install`/`apps.uninstall`, and `settings.get`/`settings.put`. Verify each with a disposable test APK.
4. **Approvals.** Add notification actions, the detail activity, biometric confirmation for HIGH and above, and an expiry sweep.
5. **Gateway.** Build the Worker and Durable Object: device WebSocket, MCP dual-era handler, bearer auth, then OAuth 2.1. Run it locally with `wrangler dev` and verify with the official MCP SDK clients.
6. **Transport.** Add the foreground-service WebSocket client with backoff, an offline queue, and reconciliation of results.
7. **Automations.** Add the scheduler (exact versus deferrable), boot recovery, and the weather/commute skill as the end-to-end scenario.
8. **Test matrix.** Run the 20 physical-device tests and record the results in `docs/testing.md`.
9. **Performance.** Measure idle CPU, memory and battery with `dumpsys batterystats` and `meminfo` over a real unattended window.
