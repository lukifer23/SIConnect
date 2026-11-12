# Pass 19: owner control, private scrolling and observed latency

November 6, 2026. The installed build is now **0.6.0-dev.16 / code 24 / SQLite 11**, updated in place with the existing signer. Code 23 was the starting checkpoint. Real fixture acceptance and normal-process readiness are recorded below.

## Real owner and client acceptance

The owner enabled indefinite Full control for the existing Dots identity. Subsequent real private Clock operations did not request new SIConnect approval. Readback showed a running 19-minute timer. A later native notification action stopped it, and the owner independently confirmed the stop. Google Messages opened and exposed six actual messages; no conversation content is retained in this report.

The profile's real SQLite activation/restore/idempotency/rollback and private-app atomic-grant fixtures passed. The new UI recreation and retention-cancellation fixtures passed. The latest ten-case batch had nine passes and one skip: the pending-continuation fixture checked backend availability before daemon reattachment. That fixture now waits for actual readiness, and the corrected run now passes on code 24 using actual shell UID 2000 and original request IDs. The earlier skip remains historical.

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

## Code-24 physical acceptance and handoff

The first private-scroll run performed a real semantic scroll, then refused the next input with STALE_NODE_REFERENCE while the Android fixture window was still animating. The fixture now waits for three stable fresh samples and retries only explicit failed stale refusals where no input was sent. Production identity/fingerprint checks remain unchanged; accepted or uncertain actions are never replayed by the fixture.

The corrected physical case passes without skips: semantic scrolling returned a real delta of 758 pixels and moved visible rows; PRIVATE_TOUCH returned a real delta of 68 pixels and exposed later rows. Both changed visible content and preserved physical screen focus. Actual owned-surface JPEG capture decoded at 720×1280, 37,911 bytes, quality 75, matching returned metadata. This proves the actual codec/capture path but does not specifically force the size-reduction branch. The owned display disappeared after close; its disposable identity was removed. The explicit full-profile pending-continuation case also passes with no skips.

Android still marked the native Accessibility service crashed after instrumentation. Local maintenance cycled only the already-enabled SIConnect service, with the precondition that it was the sole configured component, preserving the original enabled state and leaving other permissions alone. Actual binding and the normal-process UI capability were verified afterward. No general remote repair capability or insecure settings-list replacement was added. Disposable fixture and instrumentation packages were removed.

Final normal-process checks show code 24, gateway ONLINE, privileged backend available, database integrity OK, native screen control IMPLEMENTED, private SEMANTIC/PRIVATE_TOUCH scroll modes, and inspect limit 500. All five original agent records/credentials/grants (apart from ordinary last-seen updates), three reminders, two automations, and the selected folder are unchanged from the pre-update capture. The intended Dots identity retains Full control, no expiry, its previous-profile snapshot and nonrevoked state. No reauthentication was required for existing SIConnect connectors.

## Codex login evidence

The available retained observation shows Chrome displaying “Token exchange failed: error sending request for url” during the OAuth callback. All ten shell commands in the examined sign-in interval exited successfully without timeout; two earlier private-window inspections failed. The callback observation alone does not identify DNS, TLS or HTTP cause and does not establish SIConnect causation.

The owner subsequently shared the Dot's more specific report of Codex DNS lookup failure, followed by a passing network check after configuring Termux DNS and trusted certificates with TLS verification retained. These are client-reported follow-up findings; no authenticated model request has been verified here. No network/DNS/certificate changes were made by this engineering pass. The official authentication documentation describes dedicated codex-login.log diagnostics, custom CA configuration and device-code login for headless environments: https://learn.chatgpt.com/docs/auth.

## Next acceptance

The owner will ask the existing Dot to retry the real Google Messages task. Confirm retrieval beyond the original six messages, actual content movement, bounded result sizes and transport timings. Keep native/background APIs ahead of screen automation in subsequent feature work, use isolated displays where app-only controls are necessary, and verify concrete outcomes. Full Fold/UI refinement, broad app compatibility, locked/off display operation and a long reliability soak remain separate acceptance work. Keep raw DBs, identities, screenshots, tokens and login URLs outside Git.
