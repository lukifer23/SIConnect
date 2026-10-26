# Engineering pass 15 — in progress

First installed iteration: **0.6.0-dev.7 / code 15**, schema 10, 85 tools.

- ADB split writes consume one READY per packet; zero-length writes preserve readiness. Writers remain serialized and peer-close stops further output.
- Weather HTTP cancellation owns the call through response-body consumption.
- Calendar queries now use an operation-owned CancellationSignal on IO threads. Calendar mutations without a cancellation API retain the engine's uncertain-outcome handling.
- Debug build and existing Android/ADB JVM checks passed in 11 seconds. In-place device installation, bundled-service bootstrap and MainActivity startup succeeded. No data clear, credential change or new test package.
- The new in-flight cancellation and multi-packet paths have not yet been exercised on the physical phone. Build/startup success is not that evidence.

Remaining authorized work: owner commute/location setup, safe history retention, connection diagnostics, client isolation, owner UI recovery and release configuration. These are not completed by this patch.
