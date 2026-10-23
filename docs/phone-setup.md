# Phone setup and advanced control

Status: development build **0.6.0-dev.1 / code 9**, SQLite **9**. Actual app TLS/SPAKE2 pairing with owner fingerprint confirmation, phone-local ADB/Binder startup, repeated daemon recovery, app-process recovery and an owner-approved reboot have passed on the physical Fold. No Mac bootstrap command was used for local recovery or post-reboot restoration. The first pairing UI was driven from the Mac; fully unassisted fresh-install setup remains a distinct pending acceptance scenario. See [pass 10 evidence and precise limits](engineering-pass-10.md).

## Start with native features

Install an APK signed with the certificate already trusted by the existing installation. Open Home → Setup and readiness. The setup screen separates welcome, Android access, native readiness, gateway enrollment, AI-agent connection, optional advanced control, background operation and verification. Request only the access needed for the intended workflow. Notification listener, Accessibility, exact alarms and selected document folders use their Android special-access/picker screens; a runtime permission does not grant those services.

Calendar access requires the appropriate runtime permissions. SAF access requires an owner-selected folder. Weather requires an explicitly configured location. Native diagnostics and permitted native operations remain usable while advanced control is unavailable.

## Connect AI agent

Use the existing gateway or an owner-provided secure setup code. Gateway setup accepts bounded JSON (or its base64 representation) containing `url` and `enroll`; review the HTTPS origin before owner authentication. This does not provision Cloudflare infrastructure. Obtain enrollment material through the existing authenticated gateway administration path; never post it in an issue. Then connect the external AI client using its existing OAuth or private bearer flow. Do not recreate a working agent to fix an unrelated Android ADB problem.

## Enable advanced device control

Android Wireless debugging authorizes a separate **phone-local ADB host identity**. This is unrelated to connecting an AI agent to MCP. The app never grants itself shell identity merely by opening Developer options.

1. Open Settings → Advanced device control. If control is already working but no local pairing is saved, choose **Set up phone-only recovery**.
2. Enable Developer options and Wireless debugging on a trusted Wi-Fi network. Use **Open Settings beside SIConnect** for Android’s supported adjacent-window flow. The system/OEM decides the final layout.
3. In Wireless debugging, open **Pair device with pairing code**. Keep that temporary dialog open beside SIConnect or in a supported pop-up. Closing/reopening it can invalidate the old endpoint/code.
4. In SIConnect choose **Find this phone**, select this phone’s pairing service if Android asks, and enter the current six-digit code. Do not send the code to an AI agent, chat or gateway. Android 17’s system service picker avoids broad local-network access; Advanced options also offer a contextual local-discovery permission request and manual pairing port when needed.
5. Tap **Pair and enable** and complete the local owner confirmation. SIConnect performs TLS/SPAKE2 pairing, resolves the separate connection service, verifies ADB `id -u`, checks its installed package/UID/APK path, launches the existing bundled daemon only when absent, closes temporary ADB, and verifies a fresh command through Binder. **Only the final verified state is advanced-control success.**

Pairing and connection ports are different and dynamic. Never use a remembered port or default insecure TCP/5555. A Settings dialog saying paired is insufficient evidence of authenticated ADB and Binder startup. Actual pairing and Binder startup now passed on Samsung. The earlier reported pairing failure’s original cause was not established; the subsequent daemon-start failure was traced to legacy PTY shell transport and corrected using raw shell v2.

## Recovery and durability

The private RSA host key is nonexportable in Android Keystore. A successful pairing timestamp and owner recovery preference persist locally. Reuse the identity rather than repeatedly pairing; **Forget local pairing** is an explicit owner-authenticated reset and does not reset gateway/agent identities.

After successful pairing, **Restore control** finds the current connection endpoint. The opt-in **Reconnect automatically** setting attempts bounded recovery on runtime initialization, Binder loss and Wi-Fi transition, using only the saved identity. It never enables debugging, pairs with a new code, modifies secure settings or switches backend silently. Retries use delays of 0, 15 and 60 seconds; exhausted attempts require owner action. There is no continuously running ADB client/server for ordinary tool execution.

An app-process restart, APK replacement, privileged-daemon death, Android force-stop and phone reboot are different events. Reboot destroys the shell daemon. Android may disable Wireless debugging or require trusted-network confirmation; enable it through Android Settings and restore from the phone. No universal unattended reboot recovery is promised. The tested trusted-Wi-Fi reboot restored the gateway and shell after owner unlock. Ordinary app-process death and repeated daemon loss also recovered. Force-stop, new-network/cellular transition and additional revocation cases remain open.

## If pairing fails

Keep the current Android pairing dialog visible. Confirm that **Find this phone** resolved the pairing service and that the code belongs to that same open dialog. After reopening Android’s dialog, find the phone again: the old pairing endpoint may be stale. Clear/re-enter the current code locally. Check local-network permission only when using manual/broad discovery, and distinguish permission failure, service-discovery timeout, TLS rejection, SPAKE/code rejection and Binder-attachment failure. An error is not evidence that the previous agent connection was deleted.

If bundled control is already available, Check status reports that actual Binder identity. If control is missing, native tools remain available. The existing authorized-workstation recovery script remains available for development diagnostics; see [development setup](development-setup.md). Do not rotate keys, uninstall the app or clear app data as routine troubleshooting.

## Install and update

See [installation and signing](installation.md). Never install a differently signed APK over the current installation by uninstalling first: that would remove durable app state. The package installer requires Android’s own owner authorization. Updating the APK alone does not prove gateway or privileged readiness; check actual health and shell identity afterward.
