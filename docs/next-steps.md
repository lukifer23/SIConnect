# Next steps

Current checkpoint: real Dots OAuth/native/shell invocation works; the active Dots agent's app-management scope is the current install-test blocker. See [project status](project-status.md) for evidence and limits.

1. On the installed Home, open the blocked Dots request's permission card, deliberately enable Apps for that exact connection and retry with a new request. Verify package/installer and avoid login. Do not create another plugin or copy another bot's grant. Complete the separate requested Dots email test once, with actual sent-state verification.
2. Validate the new explicit-close approval and permission screens with the owner, including long review, fold/rotation, duplicate actions, expiry, backend loss, revocation and ordinary process death. Stop concurrent phone automation while an owner reviews a dialog.
3. Improve the single Home flow: readable authorization reasons, directly accessible per-bot permissions, activity filtering/sorting, complete request transitions, connection history and clearly separated local-owner versus remote actions. Preserve the unified policy/engine.
4. Finish idle resource measurements and compare connected/background/screen-off observations using private reproducible evidence. Optimize measured wakeups, hierarchy capture and agent orchestration; do not promise zero battery drain or unrestricted background execution.
5. Physically verify the newly deployed longer request envelope during an actual ten-minute owner approval window. Retain bounded synchronous routing and same-request result retrieval.
6. Add phone-only authorized wireless-debugging pairing/bootstrap. Preserve Binder caller checks and the optional Shizuku backend; after reboot privileged setup is still required.
7. Exercise cancellation/output limits, lost-result handling, structured disposable install/uninstall and audit-harness pipeline safeguards. Keep personal apps and private evidence out of tests/commits.
8. Validate scheduled skills, weather branching, exact versus deferrable reminders, network switching, idle/screen-off behavior and reboot recovery. Reboot requires separate owner authorization. Calendar/files native families remain unimplemented.
9. Separate the existing shared private-bearer Grok/Hermes principal if independent per-client revocation is desired. Keep OAuth agents independently scoped; implement end-to-end signatures only if that stronger gateway threat boundary is required.
