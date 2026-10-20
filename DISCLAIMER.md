# Disclaimer

SIConnect is experimental personal software, provided under the MIT License **without warranty of any kind**. By installing or running it you accept the following.

## You are granting powerful access

- SIConnect can be configured to let remote software agents read data from your device and change it: notifications, messages, calendars, files, installed applications and settings.
- With a privileged backend (Shizuku) enabled, agents you authorize can run arbitrary commands as the Android `shell` user, which has ADB-level privileges. A broad shell grant can perform actions that narrower, feature-level restrictions would otherwise gate. No keyword filter or static analysis can reliably predict what an arbitrary command will do.
- AI agents can misinterpret instructions and can be manipulated by content they read (prompt injection in email, web pages, notifications or messages). SIConnect treats agent requests as untrusted and enforces your policy on the device, but a grant you give is a grant the agent can use.

## Your responsibilities

- Use SIConnect only on devices you own or are explicitly authorized to administer.
- Review approval requests before you accept them. Keep broad shell grants to agents you trust, and revoke credentials you no longer use.
- Keep your gateway credentials secret. Anyone holding a valid agent credential can make requests within that agent's grant.
- Back up important data. Uninstalling apps, deleting files and changing settings can be irreversible.

## What SIConnect does not do

- It does not root your device, unlock the bootloader, exploit vulnerabilities or bypass Android security features (secure lock screen, biometrics, FLAG_SECURE, restricted settings).
- It does not bypass authentication in third-party apps, payment systems or banking applications.
- It does not guarantee uninterrupted background operation. Android, One UI battery management and reboots can stop it. On non-root devices, Shizuku must be restarted after each reboot unless its supported auto-start applies.
- The gateway terminates TLS and can see request and result content in plaintext while routing it. SIConnect does not claim end-to-end encryption.

## Third parties

SIConnect is an independent project. It is not affiliated with, endorsed by or sponsored by Google, Samsung, OpenAI, xAI, Anthropic, Cloudflare or the Shizuku project. Product names are used only to describe compatibility. Provider capabilities change, so check the current provider documentation; [docs/provider-compatibility.md](docs/provider-compatibility.md) records what was verified and when.

## Security reports

Please report vulnerabilities privately via GitHub security advisories on this repository rather than public issues.
