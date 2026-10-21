#!/usr/bin/env python3
"""Measure SIConnect CPU/memory and lifecycle context; keep real evidence outside Git."""
import argparse
import collections
import hashlib
import json
from pathlib import Path
import re
import secrets
import signal
import threading
import time

from adb_audit import REPO_ROOT as REPOSITORY, outside_repository, private_new_file, run_bounded

PACKAGE = 'dev.siconnect.app'
IMPORTANCE = {100: 'FOREGROUND', 125: 'FOREGROUND_SERVICE', 200: 'VISIBLE', 230: 'PERCEPTIBLE',
              300: 'SERVICE', 325: 'TOP_SLEEPING', 350: 'CANT_SAVE_STATE', 400: 'CACHED', 500: 'EMPTY', 1000: 'GONE'}


def parse_process(text, pid):
    """/proc/stat fields are counted after the closing comm parenthesis, not split on its spaces."""
    first = text.splitlines()[0] if text else ''
    if not first.startswith(f'{pid} (') or ')' not in first:
        return {'available': False, 'reason': 'PROCESS_COUNTERS_UNAVAILABLE'}
    fields = first[first.rfind(')') + 2:].split()
    try:
        cpu, start = int(fields[11]) + int(fields[12]), int(fields[19])
        if min(cpu, start) < 0:
            raise ValueError('negative process counter')
    except (IndexError, ValueError):
        return {'available': False, 'reason': 'MALFORMED_PROCESS_COUNTERS'}
    result = {'available': True, 'pid': pid, 'start_ticks': start, 'cpu_ticks': cpu}
    for key, pattern in [('rss_kib', r'^VmRSS:\s*(\d+)\s+kB$'), ('virtual_kib', r'^VmSize:\s*(\d+)\s+kB$')]:
        match = re.search(pattern, text, re.MULTILINE)
        result[key] = int(match[1]) if match else None
    return result


def parse_conditions(power, policy, importance):
    """Copy only recognized framework flags. Do not infer KeyguardManager.isDeviceLocked()."""
    def flag(text, name):
        match = re.search(r'\b' + re.escape(name) + r'=(true|false)\b', text)
        return match[1] == 'true' if match else None
    wake = re.search(r'\bmWakefulness=(Awake|Asleep|Dozing|Dreaming)\b', power)
    display = re.search(r'Display Power:\s*state=(ON|OFF|DOZE|DOZE_SUSPEND|UNKNOWN)\b', power)
    delegate = policy.partition('KeyguardServiceDelegate')[2]
    number = int(importance.strip()) if importance.strip().isdigit() else None
    return {'screen_interactive': flag(power, 'mInteractive'), 'screen_on_fully': flag(policy, 'mScreenOnFully'),
            'power_wakefulness': wake[1] if wake else None, 'display_state': display[1] if display else None,
            'keyguard_showing': flag(delegate, 'showing'), 'keyguard_occluded': flag(delegate, 'occluded'),
            'device_locked': None, 'powered': flag(power, 'mIsPowered'), 'charging': None,
            'app_importance': number, 'app_importance_name': IMPORTANCE.get(number, 'UNKNOWN'),
            'sources': ['dumpsys power', 'dumpsys window policy', 'am package-importance'],
            'limits': 'OEM dump fields may be absent. Keyguard showing is not proof of credential/device lock. A foreground service does not mean the app UI is foreground.'}


def parse_version(text):
    match = re.search(r'^package:' + re.escape(PACKAGE) + r'\s+versionCode:(\d+)\s*$', text, re.MULTILINE)
    return {'version_code': int(match[1]) if match else None, 'version_name': None,
            'source': 'pm list packages --user 0 --show-versioncode (exact package match)'}


def cpu_summary(samples, name, ticks):
    """Only adjacent, positively identified process/boot/version intervals may contribute CPU."""
    intervals, skipped = [], collections.Counter()
    for first, last in zip(samples, samples[1:]):
        a, b = first.get(name, {}), last.get(name, {})
        reason = None
        if not a.get('available') or not b.get('available'):
            reason = 'COUNTERS_UNAVAILABLE'
        elif not first.get('boot_session') or first.get('boot_session') != last.get('boot_session'):
            reason = 'BOOT_CHANGED_OR_UNKNOWN'
        elif (a.get('pid'), a.get('start_ticks')) != (b.get('pid'), b.get('start_ticks')):
            reason = 'PROCESS_CHANGED'
        elif first.get('app_version', {}).get('version_code') is None or last.get('app_version', {}).get('version_code') is None:
            reason = 'APP_VERSION_UNKNOWN'
        elif first.get('app_version', {}).get('version_code') != last.get('app_version', {}).get('version_code'):
            reason = 'APP_VERSION_CHANGED'
        elif not ticks or ticks <= 0:
            reason = 'CLOCK_TICKS_UNAVAILABLE'
        else:
            elapsed = b.get('observed_elapsed_seconds', last['elapsed_seconds']) - a.get('observed_elapsed_seconds', first['elapsed_seconds'])
            delta = b['cpu_ticks'] - a['cpu_ticks']
            wall_a, wall_b = a.get('observed_at_ms'), b.get('observed_at_ms')
            wall = (wall_b - wall_a) / 1000 if wall_a is not None and wall_b is not None else elapsed
            if elapsed <= 0 or delta < 0:
                reason = 'CLOCK_OR_COUNTER_REGRESSION'
            elif abs(wall - elapsed) > max(2, elapsed * .1):
                reason = 'HOST_CLOCK_OR_SLEEP_GAP'
            else:
                intervals.append({'seconds': elapsed, 'cpu_seconds': delta / ticks})
        if reason:
            skipped[reason] += 1
    result = {'valid_intervals': len(intervals), 'excluded_intervals': dict(skipped)}
    if intervals and not skipped:
        elapsed = sum(row['seconds'] for row in intervals)
        cpu = sum(row['cpu_seconds'] for row in intervals)
        result.update(cpu_seconds=round(cpu, 4), one_core_percent=round(cpu / elapsed * 100, 4), counter_observed_seconds=round(elapsed, 3))
    else:
        result['unavailable'] = 'No whole-interval CPU percentage: process/boot/version/counter continuity was not established'
        result['valid_interval_cpu_seconds'] = round(sum(row['cpu_seconds'] for row in intervals), 4)
        result['valid_interval_observed_seconds'] = round(sum(row['seconds'] for row in intervals), 3)
    return result


class Sampler:
    def __init__(self, serial, started, deadline):
        self.serial, self.started, self.deadline = serial, started, deadline
        self.salt = secrets.token_bytes(16)
        self.probes = []

    def shell(self, probe, *argv, limit=65536):
        remaining = self.deadline - time.monotonic()
        if remaining <= 0:
            self.probes.append({'probe': probe, 'status': 'DEADLINE_REACHED'})
            return ''
        try:
            result = run_bounded(['adb', '-s', self.serial, 'shell', *argv], timeout=min(10, remaining), limit=limit)
        except OSError:
            self.probes.append({'probe': probe, 'status': 'ADB_UNAVAILABLE'})
            return ''
        status = 'TIMEOUT' if result.timed_out else 'TRUNCATED' if result.stdout_truncated else 'FAILED' if result.code else 'OK'
        self.probes.append({'probe': probe, 'status': status, 'exit_code': result.code,
                            'seconds': round(result.seconds, 4), 'stderr_truncated': result.stderr_truncated})
        return result.stdout if status == 'OK' else ''

    def process(self, name, app=False):
        pid = self.shell(name + '.pid', 'pidof', name, limit=256).strip()
        if not pid.isdigit():
            return {'available': False, 'reason': 'NO_SINGLE_MATCHING_PROCESS'}
        prefix = ['run-as', PACKAGE] if app else []
        # Both sources are bounded and read under the selected process authority; no raw dump retained.
        text = self.shell(name + '.counters', *prefix, 'cat', f'/proc/{pid}/stat', f'/proc/{pid}/status', limit=16384)
        result = parse_process(text, int(pid))
        result.update(observed_elapsed_seconds=round(time.monotonic() - self.started, 4), observed_at_ms=round(time.time() * 1000))
        return result

    def sample(self):
        self.probes = []
        begin = time.monotonic()
        raw_boot = self.shell('boot', 'cat', '/proc/sys/kernel/random/boot_id', limit=128).strip()
        boot = hashlib.sha256(self.salt + raw_boot.encode()).hexdigest()[:24] if re.fullmatch(r'[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}', raw_boot) else None
        sample = {'elapsed_seconds': round(begin - self.started, 3), 'observed_at_ms': round(time.time() * 1000),
                  'boot_session': boot, 'app': self.process(PACKAGE, True), 'privileged': self.process('siconnect_privileged')}
        memory = self.shell('app.memory', 'dumpsys', 'meminfo', PACKAGE)
        for key, pattern in [('app_pss_kib', r'TOTAL PSS:\s*(\d+)'), ('app_rss_kib', r'TOTAL RSS:\s*(\d+)')]:
            match = re.search(pattern, memory)
            sample[key] = int(match[1]) if match else None
        sample['app_version'] = parse_version(self.shell('app.version', 'pm', 'list', 'packages', '--user', '0', '--show-versioncode', PACKAGE, limit=4096))
        sample['conditions'] = parse_conditions(self.shell('device.power', 'dumpsys', 'power'),
            self.shell('device.keyguard', 'dumpsys', 'window', 'policy'),
            self.shell('app.importance', 'am', 'package-importance', PACKAGE, limit=256))
        raw_boot_end = self.shell('boot.end', 'cat', '/proc/sys/kernel/random/boot_id', limit=128).strip()
        sample['observation_crossed_boot'] = bool(raw_boot and raw_boot_end and raw_boot != raw_boot_end)
        if not raw_boot_end or raw_boot_end != raw_boot:
            sample['boot_session'] = None
        sample['sampling_seconds'] = round(time.monotonic() - begin, 4)
        sample['probes'] = self.probes
        return sample


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--duration-seconds', type=int, default=60)
    parser.add_argument('--interval-seconds', type=int, default=10)
    parser.add_argument('--activity-mode', choices=['unspecified', 'idle', 'owner-use', 'agent-use', 'development', 'mixed'], default='unspecified', help='Operator annotation, not an inferred idle classification')
    args = parser.parse_args()
    if not 10 <= args.duration_seconds <= 86400 or not 5 <= args.interval_seconds <= 300:
        parser.error('Use duration 10..86400 seconds and interval 5..300 seconds')
    try:
        target = outside_repository(args.out)
    except ValueError as error:
        parser.error(str(error))
    checkpoint = target.with_name(target.name + '.samples.jsonl')
    stop = threading.Event()
    interrupted = []
    def stopping(signum, _frame):
        interrupted.append(signum); stop.set()
    for signum in [signal.SIGINT, signal.SIGTERM]:
        signal.signal(signum, stopping)
    report = {'format': 'siconnect.device-profile.v1', 'methodology_revision': 2, 'samples': [],
              'activity_mode_annotation': args.activity_mode, 'checkpoint_format': 'siconnect.device-profile-samples.v1',
              'limits': 'Observed interval, not battery-life evidence. Sequential sampling has overhead and is not atomic. Record other user/bot activity. Raw dumps, serial, credentials, notification content and hardware identity are never retained. Boot session hashes are salted per collection; do not compare them across files. Unknown/OEM-specific state fields stay null.'}
    with private_new_file(target) as output, private_new_file(checkpoint) as journal:
        started = time.monotonic()
        sampler = Sampler(args.serial, started, started + args.duration_seconds + 10)
        clock = sampler.shell('clock', 'getconf', 'CLK_TCK', limit=128).strip()
        report['clock_ticks_per_second'] = int(clock) if clock.isdigit() else None
        try:
            while not stop.is_set():
                sample = sampler.sample()
                report['samples'].append(sample)
                journal.write(json.dumps({'format': report['checkpoint_format'], 'clock_ticks_per_second': report['clock_ticks_per_second'], 'activity_mode_annotation': args.activity_mode, 'sample': sample}) + '\n')
                journal.flush()
                elapsed = time.monotonic() - started
                if elapsed >= args.duration_seconds:
                    break
                stop.wait(min(args.interval_seconds, max(0, args.duration_seconds - elapsed)))
        except Exception as error:
            report['collection_error'] = type(error).__name__
            raise
        finally:
            report['observed_seconds'] = round(time.monotonic() - started, 3)
            report['collection_state'] = 'FAILED' if 'collection_error' in report else 'INTERRUPTED' if interrupted else 'COMPLETED'
            report['cpu'] = {name: cpu_summary(report['samples'], name, report['clock_ticks_per_second']) for name in ['app', 'privileged']}
            json.dump(report, output, indent=2); output.write('\n'); output.flush()
        pss = [x['app_pss_kib'] for x in report['samples'] if x['app_pss_kib'] is not None]
        print(json.dumps({'observed_seconds': report['observed_seconds'], 'samples': len(report['samples']),
                          'collection_state': report['collection_state'], 'cpu': report['cpu'],
                          'app_pss_kib': {'min': min(pss), 'max': max(pss)} if pss else {'unavailable': 'No memory samples'}}))


if __name__ == '__main__':
    main()
