#!/usr/bin/env python3
"""Measure SIConnect process CPU/memory on one authorized device; write evidence outside Git."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import time

REPOSITORY = Path(__file__).resolve().parents[1]
PACKAGE = 'dev.siconnect.app'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--duration-seconds', type=int, default=60)
    parser.add_argument('--interval-seconds', type=int, default=10)
    args = parser.parse_args()
    if not 10 <= args.duration_seconds <= 86400 or not 5 <= args.interval_seconds <= 300:
        parser.error('Use duration 10..86400 seconds and interval 5..300 seconds')
    target = args.out.expanduser().resolve()
    if target.is_relative_to(REPOSITORY):
        parser.error('Device evidence must be outside the public repository')
    target.parent.mkdir(parents=True, exist_ok=True)
    # Reserve before doing work: no clobbering or following an existing symlink.
    output = os.fdopen(os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'w')

    def shell(*argv):
        r = subprocess.run(['adb', '-s', args.serial, 'shell', *argv], capture_output=True, text=True, timeout=20)
        return r.stdout if r.returncode == 0 else None

    def process(name, app=False):
        pid = (shell('pidof', name) or '').strip()
        if not pid.isdigit():
            return {'available': False, 'reason': 'No single matching process'}
        stat = shell(*(['run-as', PACKAGE] if app else []), 'cat', '/proc/' + pid + '/stat')
        if not stat or ')' not in stat:
            return {'available': False, 'reason': 'Process CPU counters unavailable'}
        fields = stat[stat.rfind(')') + 2:].split()
        return {'available': True, 'pid': int(pid), 'start_ticks': int(fields[19]),
                'cpu_ticks': int(fields[11]) + int(fields[12])}

    report = {'format': 'siconnect.device-profile.v1', 'samples': [], 'limits':
              'Observed interval, not battery-life evidence. Sampling has overhead; record other bot/user activity separately. No serial, credentials, request bodies or hardware identity recorded.'}
    try:
        clock = (shell('getconf', 'CLK_TCK') or '').strip()
        report['clock_ticks_per_second'] = int(clock) if clock.isdigit() else None
        started = time.monotonic()
        while True:
            sample = {'elapsed_seconds': round(time.monotonic() - started, 3),
                      'app': process(PACKAGE, True), 'privileged': process('siconnect_privileged')}
            memory = shell('dumpsys', 'meminfo', PACKAGE) or ''
            for key, pattern in [('app_pss_kib', r'TOTAL PSS:\s*(\d+)'), ('app_rss_kib', r'TOTAL RSS:\s*(\d+)')]:
                match = re.search(pattern, memory)
                sample[key] = int(match[1]) if match else None
            report['samples'].append(sample)
            if sample['elapsed_seconds'] >= args.duration_seconds:
                break
            time.sleep(min(args.interval_seconds, max(0, args.duration_seconds - (time.monotonic() - started))))
        report['observed_seconds'] = round(time.monotonic() - started, 3)
        report['cpu'] = {}
        first, last = report['samples'][0], report['samples'][-1]
        for name in ['app', 'privileged']:
            a, b = first[name], last[name]
            same = a.get('available') and b.get('available') and (a['pid'], a['start_ticks']) == (b['pid'], b['start_ticks'])
            ticks = report['clock_ticks_per_second']
            elapsed = last['elapsed_seconds'] - first['elapsed_seconds']
            if same and ticks and elapsed > 0:
                cpu_seconds = (b['cpu_ticks'] - a['cpu_ticks']) / ticks
                report['cpu'][name] = {'cpu_seconds': round(cpu_seconds, 4), 'one_core_percent': round(cpu_seconds / elapsed * 100, 4)}
            else:
                report['cpu'][name] = {'unavailable': 'Process changed or counters/clock unavailable; no inferred CPU percentage'}
        output.write(json.dumps(report, indent=2) + '\n')
        pss = [x['app_pss_kib'] for x in report['samples'] if x['app_pss_kib'] is not None]
        print(json.dumps({'observed_seconds': report['observed_seconds'], 'samples': len(report['samples']), 'cpu': report['cpu'],
                          'app_pss_kib': {'min': min(pss), 'max': max(pss)} if pss else {'unavailable': 'No memory samples'}}))
    finally:
        output.close()


if __name__ == '__main__':
    main()
