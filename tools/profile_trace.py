#!/usr/bin/env python3
"""Summarize Hermes JSONL timing without copying prompts, arguments or tool results."""
import argparse
import collections
import json
import math
import os
from pathlib import Path

REPOSITORY = Path(__file__).resolve().parents[1]


def distribution(values):
    ordered = sorted(values)
    if not ordered:
        return {"samples": 0}
    def percentile(fraction):
        return round(ordered[max(0, math.ceil(len(ordered) * fraction) - 1)], 2)
    return {"samples": len(ordered), "p50_ms": percentile(.5), "p95_ms": percentile(.95),
            "min_ms": round(ordered[0], 2), "max_ms": round(ordered[-1], 2)}


def summarize(path):
    durations = collections.defaultdict(list)
    categories = collections.defaultdict(list)
    gaps = []
    last_result = None
    pending = {}
    errors = 0
    ignored = 0
    with path.open() as source:
        while line := source.readline(4_194_305):
            # Bound allocation even for a malformed, unterminated input line.
            if len(line) > 4_194_304:
                while line and not line.endswith("\n"):
                    line = source.readline(4_194_305)
                ignored += 1
                continue
            try:
                event = json.loads(line)
            except (ValueError, TypeError):
                ignored += 1
                continue
            if not isinstance(event, dict):
                ignored += 1
                continue
            kind, name = event.get("type"), event.get("name", "")
            if not isinstance(name, str) or not name.replace("_", "").isalnum():
                continue
            if kind == "tool_use":
                timestamp = event.get("timestamp")
                if last_result is not None and isinstance(timestamp, (int, float)):
                    gaps.append(max(0, timestamp - last_result))
                arguments = event.get("input", {})
                command = arguments.get("command", "") if isinstance(arguments, dict) else ""
                pending[name] = "scripts_containing_ui_dump" if isinstance(command, str) and "uiautomator" in command else "other"
            elif kind == "tool_result":
                duration = event.get("duration_ms")
                if isinstance(duration, (int, float)) and math.isfinite(duration) and duration >= 0:
                    durations[name].append(duration)
                    categories[pending.get(name, "other")].append(duration)
                errors += int(event.get("is_error") is True)
                timestamp = event.get("timestamp")
                if isinstance(timestamp, (int, float)):
                    last_result = timestamp
    return {"format": "siconnect.trace-profile.v1", "tool_latency": {k: distribution(v) for k, v in durations.items()},
            "operation_latency": {k: distribution(v) for k, v in categories.items()},
            "agent_and_orchestration_gap": distribution(gaps), "tool_errors": errors, "ignored_lines": ignored,
            "limits": "Short observed run; gaps include agent reasoning, orchestration and any human delay. No battery-life inference."}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    target = args.out.expanduser().resolve()
    if target.is_relative_to(REPOSITORY):
        parser.error("Profile evidence must be outside the public repository")
    target.parent.mkdir(parents=True, exist_ok=True)
    report = summarize(args.input.expanduser())
    with os.fdopen(os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as output:
        json.dump(report, output, indent=2)
        output.write("\n")
    print(json.dumps({"tool_results": sum(v["samples"] for v in report["tool_latency"].values()),
                      "operation_latency": report["operation_latency"], "tool_errors": report["tool_errors"]}))


if __name__ == "__main__":
    main()
