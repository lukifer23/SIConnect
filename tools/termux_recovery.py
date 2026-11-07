#!/usr/bin/env python3
"""Finite real Termux IPC acceptance: UID, timeout/late callback, SIConnect PID death.

Requires the installed debug app/test APK and the owner's existing Termux configuration.
No Activity launch, force-stop, Termux kill, daemon restart, credential read, or host bootstrap.
Only disposable fixture grants are used. Private evidence must stay outside the repository.
--reuse-client uses the provisioner's existing private cache file; cleanup still runs unless
--keep-client is explicit. Instrumentation provisioning/cleanup can disconnect accessibility;
restore the owner's previously enabled service after the harness if Android does so.
"""
import argparse
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
APP = 'dev.siconnect.app'
CLIENT_PATH = 'cache/termux-native-recovery-client.json'
PROVISIONER = 'dev.siconnect.app.TermuxRecoveryClientDeviceTest#manageDisposableTermuxClient'
PREFIX = '/data/data/com.termux/files/usr/bin/'
TERMINAL = {'SUCCEEDED', 'FAILED', 'CANCELLED', 'DENIED', 'EXPIRED', 'UNKNOWN_OUTCOME'}


class AcceptanceFailure(RuntimeError):
    """Messages are fixed harness prose, never raw remote errors or output."""


def require(condition, message):
    if not condition:
        raise AcceptanceFailure(message)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output-dir', type=Path)
    parser.add_argument('--reuse-client', action='store_true')
    parser.add_argument('--keep-client', action='store_true', help='Retain only this disposable client after successful cases')
    parser.add_argument('--only-process-death',action='store_true',help='Run just the PID-death case after normal/timeout cases have already passed')
    args = parser.parse_args()
    output = args.output_dir or Path(tempfile.mkdtemp(prefix='siconnect-termux-recovery-'))
    require(not output.resolve().is_relative_to(ROOT), 'Private evidence must be outside the repository')
    output.mkdir(parents=True, exist_ok=True, mode=0o700)
    output.chmod(0o700)
    report = {'cases': [], 'completed': False, 'noActivityOrBootstrapUsed': True,
              'personalCommandsOrCredentialsAccessed': False, 'cleanupErrors': []}
    client = None
    port = None
    fixture_owned = False
    pool = concurrent.futures.ThreadPoolExecutor(max_workers=1)
    adb_prefix = ['adb', '-s', args.serial]

    def adb(*words, timeout=20, check=True):
        return subprocess.run(adb_prefix + list(words), capture_output=True, check=check, timeout=timeout)

    def phase(mode):
        result = adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'termuxRecoveryClient', mode,
                     '-e', 'class', PROVISIONER, APP + '.test/androidx.test.runner.AndroidJUnitRunner', timeout=60)
        raw = result.stdout + result.stderr
        path = output / ('client-' + mode + '.log')
        path.write_bytes(raw)
        path.chmod(0o600)
        require(b'OK (1 test)' in raw and b'INSTRUMENTATION_STATUS_CODE: -2' not in raw,
                'Disposable Termux principal provisioning/cleanup failed; private log retained')

    def until(probe, label, seconds=20):
        deadline = time.monotonic() + seconds
        while True:
            value = probe()
            if value:
                return value
            if time.monotonic() >= deadline:
                raise AcceptanceFailure(label)
            time.sleep(.1)

    def request(path, body=None, timeout=6):
        data = None if body is None else json.dumps(body).encode()
        req = urllib.request.Request('http://127.0.0.1:' + str(port) + path, data=data,
                                     headers={'Authorization': 'Bearer ' + client['token'],
                                              'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=timeout) as response:
            return json.load(response)

    def invoke(capability, arguments=None):
        record = request('/invoke', {'capabilityId': capability, 'arguments': arguments or {}})
        require(record.get('state') == 'SUCCEEDED', 'Required read capability did not succeed')
        return record['result']['output']

    def ready():
        try:
            return invoke('termux.status')
        except (OSError, urllib.error.URLError, ValueError, AcceptanceFailure):
            return None

    def app_pid():
        found = adb('shell', 'pidof', APP, check=False).stdout.decode().strip()
        require(re.fullmatch(r'[0-9]+', found) is not None, 'Expected one exact SIConnect process')
        return int(found)

    def foreground():
        result = adb('shell', 'dumpsys', 'window', 'displays', check=False)
        if result.returncode:
            return None
        display = None
        for line in result.stdout.decode(errors='replace').splitlines():
            match = re.search(r'Display: mDisplayId=(\d+)', line)
            if match:
                display = int(match[1])
            if display == 0 and 'mCurrentFocus=' in line:
                # Compare only in memory. Window names/component details never enter the report.
                return line.strip()
        return None

    def with_core_snapshot(reader):
        # Main/WAL copies are ephemeral and private. Concurrent checkpointing may invalidate
        # one copy; require integrity and retry boundedly rather than trust an inconsistent view.
        for attempt in range(3):
            try:
                with tempfile.TemporaryDirectory(prefix='core-snapshot-', dir=output) as folder:
                    os.chmod(folder, 0o700)
                    target = Path(folder) / 'siconnect.db'
                    for suffix in ('', '-wal'):
                        source = 'databases/siconnect.db' + suffix
                        exists = adb('shell', 'run-as', APP, 'test', '-f', source, check=False)
                        if exists.returncode:
                            require(bool(suffix), 'Core SQLite database is unavailable')
                            continue
                        dest = Path(str(target) + suffix)
                        with dest.open('wb') as stream:
                            os.chmod(dest, 0o600)
                            copied = subprocess.run(adb_prefix + ['exec-out', 'run-as', APP, 'cat', source],
                                                    stdout=stream, stderr=subprocess.PIPE, timeout=25)
                        require(copied.returncode == 0, 'Could not privately snapshot core SQLite')
                    with sqlite3.connect('file:' + str(target) + '?mode=ro', uri=True) as db:
                        require(db.execute('PRAGMA quick_check').fetchone()[0] == 'ok', 'Core snapshot integrity check failed')
                        return reader(db)
            except (sqlite3.Error, AcceptanceFailure, subprocess.SubprocessError):
                if attempt == 2:
                    raise AcceptanceFailure('Could not establish a consistent private SQLite snapshot') from None
                time.sleep(.1)

    def verify_fixture(db):
        row = db.execute('SELECT token_sha256,json FROM agents WHERE agent_id=?', (client['agentId'],)).fetchone()
        require(row is not None, 'Disposable principal is not present in actual AgentStore')
        record = json.loads(row[1]); grant = record['grant']
        require(row[0] == hashlib.sha256(client['token'].encode()).hexdigest(), 'Disposable credential does not match actual AgentStore')
        require(record.get('tokenHint') == 'Termux recovery acceptance only' and
                grant['agentId'] == client['agentId'] and grant.get('label') == 'Disposable native Termux recovery' and
                set(grant.get('scopes', [])) == {'termux.*', 'device.status'} and
                grant.get('shellMode') == 'BROAD' and grant.get('autoApproveUpTo') == 'HIGH_IMPACT' and
                not grant.get('allowSharedScreen', False) and not grant.get('fullControl', False) and
                not grant.get('revoked', False) and record.get('fullAccessPreviousGrant') is None and
                grant.get('expiresAtMs') == client['expiresAtMs'], 'Actual disposable grant does not match the narrow provisioner contract')

    def execution_evidence(request_id, state):
        def read(db):
            row = db.execute('SELECT state,agent_id,capability_id FROM requests WHERE request_id=?', (request_id,)).fetchone()
            require(row == (state, client['agentId'], 'termux.execute'), 'Original core execution metadata changed unexpectedly')
            transitions = db.execute("SELECT count(*) FROM request_transitions WHERE request_id=? AND to_state='EXECUTING'", (request_id,)).fetchone()[0]
            starts = db.execute("SELECT count(*) FROM events WHERE request_id=? AND event_type='execution.started'", (request_id,)).fetchone()[0]
            require(transitions == starts == 1, 'Original command did not retain exactly one execution transition and audit')
            return {'executingTransitions': transitions, 'executionStartedAudits': starts, 'originalCoreState': row[0]}
        return with_core_snapshot(read)

    def body_for(label, sleep_seconds, timeout_ms):
        request_id = str(uuid.uuid4())
        marker = 'siconnect-termux-' + label + '-' + uuid.uuid4().hex
        command = (PREFIX + 'sleep ' + str(sleep_seconds) + '; ' if sleep_seconds else '')
        command += "printf '%s\\n' '" + marker + "'; " + PREFIX + 'id -u'
        return {'requestId': request_id, 'idempotencyKey': 'termux-acceptance-' + request_id,
                'capabilityId': 'termux.execute', 'arguments': {'executable': PREFIX + 'sh',
                'arguments': ['-c', command], 'timeoutMs': timeout_ms, 'maxOutputBytes': 1024}}, marker

    def original(request_id):
        return request('/requests/' + request_id)

    def result(request_id):
        return invoke('termux.result', {'executionId': request_id})

    def actual_completion(request_id, marker, expected_uid):
        def probe():
            observed = result(request_id)
            return observed if observed.get('callbackReceived') else None
        observed = until(probe, 'Actual late Termux callback was not retained', seconds=20)
        require(observed.get('phase') == 'RESULT_RECEIVED' and observed.get('commandSucceeded') is True and
                observed.get('executionErrorCode') == -1 and observed.get('exitCode') == 0 and
                observed.get('packageUid') == expected_uid, 'Termux callback did not confirm actual successful execution')
        require(observed.get('stdout', '').strip().splitlines() == [marker, str(expected_uid)] and
                observed.get('stderr', '') == '', 'Actual printf/UID output did not match this disposable command')
        return observed

    def no_replay(body, observed, expected_state):
        retained = original(body['requestId'])
        require(retained.get('state') == expected_state, 'Late callback unexpectedly changed the original core outcome')
        retried = request('/invoke', body)
        require(retried.get('requestId') == body['requestId'] and retried == retained, 'Same-ID retry did not return the exact original record')
        after = result(body['requestId'])
        require(after == observed, 'Same-ID retry changed the actual callback journal')
        return execution_evidence(body['requestId'], expected_state)

    def termux_command_running(marker, expected_uid):
        rows = adb('shell', 'ps', '-A', '-w', '-o', 'PID,UID,ARGS', check=False)
        if rows.returncode:
            raise AcceptanceFailure('Cannot observe the real Termux command process before PID-death acceptance')
        uid_names = {str(expected_uid), 'u%d_a%d' % (expected_uid // 100000, expected_uid % 100000 - 10000)}
        for line in rows.stdout.decode(errors='replace').splitlines():
            fields = line.split(None, 2)
            # Termux may resolve sh to its actual interpreter basename. Match the
            # command's unpredictable marker and real UID, with untruncated argv.
            if len(fields) == 3 and fields[0].isdigit() and fields[1] in uid_names and marker in fields[2]:
                return True
        return False

    try:
        adb('shell', 'run-as', APP, 'true')
        if not args.reuse_client:
            phase('create')
            report['disposableClientProvisioned'] = True
        # Only take ownership after the file identifies this exact test fixture.
        client = json.loads(adb('exec-out', 'run-as', APP, 'cat', CLIENT_PATH).stdout)
        require(set(client) == {'agentId', 'token', 'expiresAtMs', 'gatewayEnabled', 'phoneRecoveryEnabled'}, 'Unexpected recovery client cache fields')
        require(client['agentId'].startswith('termux-recovery-'), 'Refusing a non-fixture principal')
        uuid.UUID(client['agentId'].removeprefix('termux-recovery-'))
        require(isinstance(client['token'], str) and len(client['token']) == 72, 'Invalid private disposable credential shape')
        require(client['expiresAtMs'] > time.time() * 1000 + 90000, 'Disposable credential expires too soon; reconcile it and provision anew')
        with_core_snapshot(verify_fixture)
        fixture_owned = True
        port = int(adb('forward', 'tcp:0', 'tcp:8765').stdout.decode().strip())
        status = until(ready, 'Existing debug endpoint did not recover without an Activity', seconds=25)
        expected_uid = status.get('uid')
        require(isinstance(expected_uid, int) and expected_uid >= 10000 and status.get('installed') and
                status.get('appEnabled') and status.get('serviceAvailable') and status.get('runCommandPermissionGranted') and
                status.get('resultApiSupported') is not False, 'Actual Termux package/permission preconditions are not met')
        baseline = foreground()
        report['physicalForegroundObservable'] = baseline is not None
        report['termuxUid'] = expected_uid
        report['usedExistingClient'] = args.reuse_client

        if not args.only_process_death:
            # Case 1: actual native IPC command/result, not privileged shell identity.
            body, marker = body_for('normal', 0, 10000)
            started = time.monotonic()
            record = request('/invoke', body, timeout=15)
            require(record.get('state') == 'SUCCEEDED' and record.get('result', {}).get('backend') == 'NATIVE', 'Normal native Termux execution failed')
            observed = actual_completion(body['requestId'], marker, expected_uid)
            evidence = no_replay(body, observed, 'SUCCEEDED')
            report['cases'].append({'case': 'normal-native-termux-uid', 'requestId': body['requestId'],
                                    'elapsedMs': round((time.monotonic() - started) * 1000), 'actualUidVerified': True,
                                    'sameIdRetryDidNotReplay': True, **evidence})
            print('normal-native-termux-uid: passed', flush=True)

            # Case 2: core wait expires while the real external command continues and calls back.
            body, marker = body_for('late', 3, 250)
            started = time.monotonic()
            record = request('/invoke', body, timeout=10)
            require(record.get('state') == 'UNKNOWN_OUTCOME' and record.get('errorCode') == 'TERMUX_OUTCOME_PENDING', 'Wait timeout was not retained as uncertain original outcome')
            observed = actual_completion(body['requestId'], marker, expected_uid)
            require(observed.get('waitStatus') == 'TIMED_OUT', 'Late callback lost the original timeout evidence')
            evidence = no_replay(body, observed, 'UNKNOWN_OUTCOME')
            report['cases'].append({'case': 'timeout-real-late-callback', 'requestId': body['requestId'],
                                    'elapsedMs': round((time.monotonic() - started) * 1000), 'lateCallbackSucceeded': True,
                                    'sameIdRetryDidNotReplay': True, **evidence})
            print('timeout-real-late-callback: passed', flush=True)

        # Case 3: prove Termux's actual shell is alive, kill only its SIConnect caller PID,
        # then let the existing daemon/Android callback restore the ordinary app process.
        body, marker = body_for('process-death', 5, 30000)
        started = time.monotonic()
        pending_http = pool.submit(request, '/invoke', body, 40)
        def dispatched():
            try:
                observed = result(body['requestId'])
                return observed.get('phase') == 'DISPATCHING' and not observed.get('callbackReceived')
            except (urllib.error.HTTPError, AcceptanceFailure):
                return False
        until(dispatched, 'Real Termux dispatch was not observed', seconds=3)
        until(lambda: termux_command_running(marker, expected_uid), 'Actual Termux shell was not observable before caller death', seconds=2)
        old_pid = app_pid()
        require(app_pid() == old_pid, 'SIConnect PID changed before the exact-PID kill')
        require(not pending_http.done() and termux_command_running(marker, expected_uid),
                'Actual Termux command finished before the exact-PID kill; no process was killed')
        adb('shell', 'run-as', APP, 'kill', '-9', str(old_pid))
        until(ready, 'Normal app endpoint did not restore autonomously after PID death', seconds=35)
        require(app_pid() != old_pid, 'SIConnect did not establish a replacement process')
        try:
            pending_http.result(timeout=5)
            raise AcceptanceFailure('Original HTTP unexpectedly survived confirmed caller-process death')
        except (OSError, urllib.error.URLError, TimeoutError):
            pass
        observed = actual_completion(body['requestId'], marker, expected_uid)
        require(observed.get('waitStatus') == 'PROCESS_LOST', 'Process-death callback lost durable waiter-loss evidence')
        until(lambda: original(body['requestId']).get('state') == 'UNKNOWN_OUTCOME', 'Original request was not recovered as UNKNOWN_OUTCOME', seconds=10)
        evidence = no_replay(body, observed, 'UNKNOWN_OUTCOME')
        report['cases'].append({'case': 'siconnect-pid-death-real-late-callback', 'requestId': body['requestId'],
                                'elapsedMs': round((time.monotonic() - started) * 1000), 'actualTermuxProcessObservedBeforeKill': True,
                                'exactCallerPidReplaced': True, 'lateCallbackSucceeded': True,
                                'autonomousRecoveryWithoutActivityOrBootstrap': True, 'sameIdRetryDidNotReplay': True, **evidence})
        print('siconnect-pid-death-real-late-callback: passed', flush=True)
        report['physicalForegroundUnchanged'] = foreground() == baseline if baseline is not None else None
        require(report['physicalForegroundUnchanged'] is not False, 'Physical foreground changed during background acceptance')
        report['completed'] = len(report['cases']) == (1 if args.only_process_death else 3)
    except Exception as error:
        report['failureType'] = type(error).__name__
        report['failure'] = str(error) if isinstance(error, AcceptanceFailure) else 'Unexpected failure; raw remote output deliberately omitted'
    finally:
        pool.shutdown(wait=True, cancel_futures=True)
        if fixture_owned and not (args.keep_client and report['completed']):
            try:
                phase('remove')
                report['disposableClientRemoved'] = True
            except Exception as error:
                report['disposableClientRemoved'] = False
                report['cleanupErrors'].append('remove-client:' + type(error).__name__)
                report['uncertainFixturePreservedByProvisioner'] = True
        elif fixture_owned:
            report['disposableClientIntentionallyRetained'] = True
        elif report.get('disposableClientProvisioned'):
            report['unvalidatedFixtureRetainedForOwnerReconciliation'] = True
        if port is not None:
            try:
                adb('forward', '--remove', 'tcp:' + str(port))
            except Exception as error:
                report['cleanupErrors'].append('remove-forward:' + type(error).__name__)
        report['completed'] = report['completed'] and not report['cleanupErrors']
        path = output / 'termux-recovery-summary.json'
        path.write_text(json.dumps(report, indent=2) + '\n')
        path.chmod(0o600)
    print('Private Termux recovery evidence: ' + str(path))
    return 0 if report['completed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
