# Pass 19: owner control, private scrolling and observed latency

November 6, 2026. The installed checkpoint at the start of this pass is 0.6.0-dev.15 / code 23 / SQLite 11. Source work for the next build is ongoing; implementation is not physical acceptance.

## Real owner and client acceptance

The owner enabled indefinite Full control for the existing Dots identity. Subsequent real private Clock operations did not request new SIConnect approval. Readback showed a running 19-minute timer. A later native notification action stopped it, and the owner independently confirmed the stop. Google Messages opened and exposed six actual messages; no conversation content is retained in this report.

The profile's real SQLite activation/restore/idempotency/rollback and private-app atomic-grant fixtures passed. The new UI recreation and retention-cancellation fixtures passed. The latest ten-case batch had nine passes and one skip: the pending-continuation fixture checked backend availability before daemon reattachment. That fixture now waits for actual readiness, but the corrected run remains outstanding. Never count the skip as a pass.

## Latency evidence

Sanitized analysis of actual retained requests and terminal audit timestamps:

| Sequence | Calls | Elapsed sequence | Summed on-phone spans |
| --- | ---: | ---: | ---: |
| Private Clock start | 13 | 86.776 s | 1.418 s |
| Notification Clock stop | 7 | 21.377 s | 0.184 s |
| Messages navigation/read/scroll | 15 | 121.900 s | 1.347 s |

On-phone spans run from saved request creation through terminal audit emission. Gaps include result delivery, client/agent processing, routing and possible user pauses; they do not isolate model or network latency. Initial admission and transport serialization are not fully measured by those old records. New source records monotonic receipt-to-response-enqueue duration and individual dispatch/invocation/serialization/enqueue durations, with no request arguments, secrets or result content. WebSocket enqueue does not confirm remote receipt. Activity shows phone lifecycle/execution/enqueue timings with this limitation.

## Reproduced limitations

Both Messages semantic scroll dispatches were accepted. The following 104-node snapshots were equivalent in normalized visible content, bounds and actions. The selected container advertised backward/up but not forward. A resized split layout returned 200 nodes with incomplete traversal; the active message pane was omitted. A later Termux capture failed with WORKSPACE_IMAGE_TOO_LARGE. Termux itself is installed and the Dot opened it. The owner subsequently shared a Dot report of working Node/npm, official Codex CLI version 0.167.0 and an interactive sign-in chooser. This is client-reported installation/startup evidence; an authenticated model request is not yet established here.

Production private UiAutomation already requests coexistence with enabled Accessibility services. The old RuntimeDeviceTest used default automation, which suppresses them; it now requests coexistence and clears cached reads. Prior CLI uiautomator dumps also use suppressing automation. Process replacement can independently leave Android services disconnected. These are concrete tooling risks, not proof of the exact Samsung disconnect cause.

## Acceptance still required

The scoped source now clears UiAutomation cache before fresh roots, accepts advertised directional scroll actions, supports explicit private-display swipes, and reports scroll events/visible changes separately from dispatch. Inspect accepts up to 500 nodes within the existing 192 KiB budget and exposes directional container hints. Capture adapts JPEG quality and owned-image size to its existing 128 KiB limit, returning actual dimensions. Seven JVM contract cases pass. The integrated build passes 198 host cases (83 core, 113 Android JVM, two embedded ADB), debug/instrumentation APK assembly, minified unsigned release assembly, and lint with zero errors. The debug APK retains the installed certificate. Physical acceptance remains outstanding; the installed checkpoint stays code 23 while the Dot signs in. Test semantic and display-bound touch scrolling against the actual separate disposable ScrollView app, asserting visible changes and unchanged physical focus. Install only after the active Dot session is idle. Recheck saved Full control and normal-process gateway/backend/Accessibility readiness, then retest the affected real client workflow. Do not replay private-message sends or uncertain mutations, overwrite enrollment, or reset owner data. Keep raw DBs, identities, screenshots and tokens outside Git.
