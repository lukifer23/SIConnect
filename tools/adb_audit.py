#!/usr/bin/env python3
"""SIConnect ADB capability audit harness.

Runs a catalog of classified ADB probes against one explicitly selected device and writes a
structured, redacted JSON report. Read-only probes run by default; anything else requires
--allow-risk and per-probe confirmation.

Raw (unredacted) output is written only with --raw-dir, which must point outside the repository.

Usage:
  tools/adb_audit.py --list-devices
  tools/adb_audit.py --serial <serial> --out audit.json [--only pkg,props] [--raw-dir ~/siconnect-raw]
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path

MAX_OUTPUT = 64 * 1024
REPO_ROOT = Path(__file__).resolve().parent.parent

# Risk classes mirror the on-device policy engine.
READ_ONLY, LOW, MODERATE, HIGH = "READ_ONLY", "LOW_IMPACT", "MODERATE_IMPACT", "HIGH_IMPACT"


@dataclass
class Probe:
    id: str
    group: str
    command: str
    risk: str = READ_ONLY
    summarize: str = "lines"  # lines | first_line | count | none
    note: str = ""


PROBES: list[Probe] = [
    # --- props / identity (build-level, not personal) ---
    Probe("props.model", "props", "getprop ro.product.model", summarize="first_line"),
    Probe("props.release", "props", "getprop ro.build.version.release", summarize="first_line"),
    Probe("props.sdk", "props", "getprop ro.build.version.sdk_full", summarize="first_line"),
    Probe("props.oneui", "props", "getprop ro.build.version.oneui", summarize="first_line"),
    Probe("props.patch", "props", "getprop ro.build.version.security_patch", summarize="first_line"),
    Probe("props.abi", "props", "getprop ro.product.cpu.abi", summarize="first_line"),
    Probe("props.build_type", "props", "getprop ro.build.type", summarize="first_line"),
    Probe("props.verified_boot", "props", "getprop ro.boot.verifiedbootstate", summarize="first_line"),
    Probe("props.flash_locked", "props", "getprop ro.boot.flash.locked", summarize="first_line"),
    # --- privilege ---
    Probe("priv.shell_id", "privilege", "id", summarize="first_line"),
    Probe("priv.selinux", "privilege", "getenforce", summarize="first_line"),
    Probe("priv.su", "privilege", "command -v su || echo absent", summarize="first_line"),
    Probe("priv.shizuku_pkg", "privilege", "pm list packages --user 0 moe.shizuku.privileged.api", summarize="lines"),
    Probe("priv.shizuku_proc", "privilege", "ps -A -o USER,NAME | grep -i shizuku || echo not-running", summarize="lines"),
    Probe("priv.users", "privilege", "pm list users | grep -c UserInfo", summarize="first_line",
          note="count only; user names are personal"),
    # --- device state ---
    Probe("state.battery", "state", "dumpsys battery", summarize="lines"),
    Probe("state.meminfo", "state", "head -4 /proc/meminfo", summarize="lines"),
    Probe("state.storage", "state", "df -h /data | tail -1", summarize="lines"),
    Probe("state.thermal", "state", "dumpsys thermalservice | grep -A6 'Current temperatures' | head -8", summarize="lines"),
    Probe("state.uptime", "state", "uptime", summarize="first_line"),
    Probe("state.deviceidle", "state", "dumpsys deviceidle | grep -E 'mState|mLightState' | head -2", summarize="lines"),
    # --- package manager ---
    Probe("pkg.count_user0", "packages", "pm list packages --user 0 | wc -l", summarize="first_line"),
    Probe("pkg.count_third_party", "packages", "pm list packages --user 0 -3 | wc -l", summarize="first_line"),
    Probe("pkg.roles", "packages",
          "for r in SMS HOME DIALER BROWSER ASSISTANT; do echo $r=$(cmd role get-role-holders android.app.role.$r); done",
          summarize="lines"),
    Probe("pkg.dump_sample", "packages", "dumpsys package com.android.settings | grep -m3 -E 'versionName|firstInstallTime'",
          summarize="lines"),
    # --- framework services ---
    Probe("svc.list_count", "framework", "service list | wc -l", summarize="first_line"),
    Probe("svc.key_services", "framework",
          "for s in activity package notification alarm jobscheduler accessibility appwidget statusbar input settings; "
          "do service check $s; done", summarize="lines"),
    Probe("svc.cmd_help_alarm", "framework", "cmd alarm help 2>&1 | head -20", summarize="lines"),
    Probe("svc.next_alarm", "framework", "dumpsys alarm | grep -m2 -iE 'nextAlarmClock|next wake'", summarize="lines"),
    Probe("svc.notification_listeners", "framework", "cmd notification allowed_listeners 2>&1 | wc -l", summarize="first_line"),
    Probe("svc.accessibility_enabled", "framework", "settings get secure accessibility_enabled", summarize="first_line"),
    Probe("svc.appops_help", "framework", "appops help 2>&1 | head -5", summarize="lines"),
    # --- settings namespaces (counts only) ---
    Probe("settings.system_count", "settings", "settings list system | wc -l", summarize="first_line"),
    Probe("settings.secure_count", "settings", "settings list secure | wc -l", summarize="first_line"),
    Probe("settings.global_count", "settings", "settings list global | wc -l", summarize="first_line"),
    # --- data access boundaries ---
    Probe("data.sms_provider", "data", "content query --uri content://sms/inbox --projection _id --where \"_id<0\" 2>&1 | head -2",
          summarize="lines", note="shell has no READ_SMS; expected to fail"),
    Probe("data.calendar_provider", "data",
          "content query --uri content://com.android.calendar/calendars --projection _id 2>&1 | wc -l", summarize="first_line"),
    Probe("data.sdcard", "data", "ls /sdcard | wc -l", summarize="first_line"),
    Probe("data.app_private", "data", "ls /data/data 2>&1 | head -1", summarize="first_line", note="expected permission denied"),
    # --- interaction ---
    Probe("ui.wm_size", "interaction", "wm size", summarize="lines"),
    Probe("ui.wm_density", "interaction", "wm density", summarize="lines"),
    Probe("ui.uiautomator", "interaction", "command -v uiautomator || echo absent", summarize="first_line"),
    Probe("ui.input_cmd", "interaction", "input 2>&1 | head -3", summarize="lines"),
    # --- non-read-only examples (never run without --allow-risk + confirmation) ---
    Probe("act.screencap_tmp", "interaction", "screencap -p /data/local/tmp/siconnect_audit.png && ls -l /data/local/tmp/siconnect_audit.png"
          " && rm /data/local/tmp/siconnect_audit.png", risk=LOW, summarize="lines", note="writes+removes temp file"),
]

REDACTIONS = [
    (re.compile(r"\b(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}\b"), "<mac>"),
    (re.compile(r"\b\d{1,3}(?:\.\d{1,3}){3}\b"), "<ipv4>"),
    (re.compile(r"\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b"), "<uuid>"),
    (re.compile(r"[\w.+-]+@[\w-]+\.[\w.]+"), "<email>"),
    (re.compile(r"\b\d{14,16}\b"), "<long-number>"),  # IMEI-like
    (re.compile(r"(?i)(serial[^=:\s]*[=:\s]+)\S+"), r"\1<serial>"),
    (re.compile(r"UserInfo\{(\d+):[^:}]+"), r"UserInfo{\1:<name>"),
]


def redact(text: str, serial: str | None) -> str:
    if serial:
        text = text.replace(serial, "<serial>")
        bare = serial.split("-")[1] if serial.startswith("adb-") and "-" in serial else None
        if bare:
            text = text.replace(bare, "<serial>")
    for pattern, repl in REDACTIONS:
        text = pattern.sub(repl, text)
    return text


def adb(args: list[str], timeout: float = 30) -> tuple[int, str, str, float]:
    t0 = time.monotonic()
    try:
        p = subprocess.run(["adb", *args], capture_output=True, timeout=timeout)
        return p.returncode, p.stdout.decode(errors="replace"), p.stderr.decode(errors="replace"), time.monotonic() - t0
    except subprocess.TimeoutExpired:
        return -1, "", "timeout", time.monotonic() - t0


def list_devices() -> list[dict]:
    code, out, _, _ = adb(["devices", "-l"])
    devices = []
    for line in out.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2:
            devices.append({"serial": parts[0], "state": parts[1],
                            **dict(p.split(":", 1) for p in parts[2:] if ":" in p)})
    return devices


def summarize(kind: str, text: str):
    lines = [l for l in text.splitlines() if l.strip()]
    if kind == "first_line":
        return lines[0].strip() if lines else ""
    if kind == "count":
        return len(lines)
    if kind == "none":
        return None
    return lines[:40]


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--list-devices", action="store_true")
    ap.add_argument("--serial", help="explicit adb serial (required; never guessed)")
    ap.add_argument("--out", type=Path, help="redacted JSON report path")
    ap.add_argument("--only", help="comma-separated groups or probe ids")
    ap.add_argument("--allow-risk", choices=[LOW, MODERATE], help="permit non-read-only probes up to this class")
    ap.add_argument("--yes", action="store_true", help="skip per-probe confirmation for allowed risky probes")
    ap.add_argument("--raw-dir", type=Path, help="write unredacted output here (must be outside the repo)")
    a = ap.parse_args()

    if a.list_devices:
        for d in list_devices():
            print(f"{d['state']:12} model={d.get('model', '?'):14} serial=<use --serial; not printed in reports>  {d['serial']}")
        return 0
    if not a.serial:
        ap.error("--serial is required; run --list-devices first")
    if a.serial not in {d["serial"] for d in list_devices() if d["state"] == "device"}:
        print("error: serial not connected/authorized", file=sys.stderr)
        return 2
    if a.raw_dir and REPO_ROOT in a.raw_dir.resolve().parents:
        print("error: --raw-dir must be outside the repository", file=sys.stderr)
        return 2

    allowed = {READ_ONLY} | ({LOW} if a.allow_risk in (LOW, MODERATE) else set()) | ({MODERATE} if a.allow_risk == MODERATE else set())
    selected = [p for p in PROBES if not a.only or p.group in a.only.split(",") or p.id in a.only.split(",")]
    results = []
    for p in selected:
        if p.risk not in allowed:
            results.append({"id": p.id, "group": p.group, "risk": p.risk, "skipped": "risk not allowed"})
            continue
        if p.risk != READ_ONLY and not a.yes:
            if input(f"Run {p.risk} probe {p.id!r}: {p.command!r}? [y/N] ").strip().lower() != "y":
                results.append({"id": p.id, "group": p.group, "risk": p.risk, "skipped": "declined"})
                continue
        code, out, err, dt = adb(["-s", a.serial, "shell", p.command])
        truncated = len(out) > MAX_OUTPUT
        out = out[:MAX_OUTPUT]
        if a.raw_dir:
            a.raw_dir.mkdir(parents=True, exist_ok=True)
            (a.raw_dir / f"{p.id}.txt").write_text(f"$ {p.command}\nexit={code}\n--- stdout\n{out}\n--- stderr\n{err}")
        red_out, red_err = redact(out, a.serial), redact(err, a.serial)
        denied = bool(re.search(r"(?i)permission denial|securityexception|permission denied|not allowed", out + err))
        results.append({
            "id": p.id, "group": p.group, "risk": p.risk, "command": p.command, "exit_code": code,
            "duration_ms": round(dt * 1000), "permission_failure": denied, "truncated": truncated,
            "summary": summarize(p.summarize, red_out), "stderr": red_err.strip()[:2000] or None, "note": p.note or None,
        })
        print(f"{'DENIED' if denied else ('ok' if code == 0 else 'exit ' + str(code)):>8}  {p.id}  ({round(dt*1000)} ms)")

    report = {
        "schema": "siconnect.adb_audit/1",
        "generated_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "device_fingerprint": hashlib.sha256(a.serial.encode()).hexdigest()[:12],
        "probes": results,
    }
    if a.out:
        a.out.write_text(json.dumps(report, indent=2))
        print(f"wrote {a.out}")
    else:
        print(json.dumps(report, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
