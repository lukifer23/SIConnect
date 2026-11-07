#!/data/data/com.termux/files/usr/bin/sh
# SIConnect Android compatibility launcher v1. Contains no account credentials.
# Invoke explicitly through codex.execute's androidCompatibilityLauncher option.
# Uses Termux's existing DNS configuration and trusted CA bundle, with TLS verification.
set -eu
termux_prefix=/data/data/com.termux/files/usr
codex_entry="$termux_prefix/lib/node_modules/@openai/codex/bin/codex.js"
test -x "$termux_prefix/bin/proot"
test -x "$termux_prefix/bin/node"
test -r "$termux_prefix/etc/resolv.conf"
test -r "$codex_entry"
: "${SSL_CERT_FILE:=$termux_prefix/etc/tls/cert.pem}"
test -r "$SSL_CERT_FILE"
export SSL_CERT_FILE
# Explicit Node also avoids the npm launcher's /usr/bin/env shebang under proot.
exec "$termux_prefix/bin/proot" -b "$termux_prefix/etc/resolv.conf:/etc/resolv.conf" \
    "$termux_prefix/bin/node" "$codex_entry" "$@"
