#!/usr/bin/env python3
"""Real process-death acceptance. Private UI needs an awake unlocked debug phone; native-only does not.

Uses the installed Calculator, existing authenticated debug endpoint, real SQLite principals,
Binder/display services and actual process death. It never changes original grants, dismisses
keyguard, launches a physical Activity, force-stops/uninstalls the main app or creates a server.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid

from semantic_ui_smoke import screen_precondition

ROOT = Path(__file__).resolve().parents[1]
CLIENT_PATH = 'cache/private-workspace-recovery-client.json'
PROVISIONER = 'dev.siconnect.app.PrivateWorkspaceRecoveryDeviceTest#manageDisposableRecoveryClient'
CALCULATOR = 'com.sec.android.app.popupcalculator'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output-dir', type=Path)
    parser.add_argument('--phone-backend-recovery', action='store_true', help='Also kill the real backend and require saved phone-local recovery without host bootstrap')
    parser.add_argument('--cold-bridge-seconds', type=int, default=0, help='Also stop the real saved bridge, idle 1–120 seconds, and verify background restoration after Binder reattachment')
    parser.add_argument('--native-only', action='store_true', help='Run only normal-process background recovery; no unlocked screen required')
    args = parser.parse_args()
    if args.cold_bridge_seconds not in range(0, 121):
        parser.error('--cold-bridge-seconds must be between 0 and 120')
    output = args.output_dir or Path(tempfile.mkdtemp(prefix='siconnect-workspace-recovery-'))
    if output.resolve().is_relative_to(ROOT):
        raise RuntimeError('Keep private device evidence outside the repository')
    output.mkdir(parents=True, exist_ok=True, mode=0o700)
    output.chmod(0o700)
    adb_command = ['adb', '-s', args.serial]

    def adb(*words, timeout=60):
        return subprocess.run(adb_command + list(words), capture_output=True, check=True, timeout=timeout).stdout

    def phase(mode):
        raw = adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'privateRecoveryClient', mode,
                  '-e', 'class', PROVISIONER, 'dev.siconnect.app.test/androidx.test.runner.AndroidJUnitRunner')
        path = output / ('client-' + mode + '.log')
        path.write_bytes(raw)
        path.chmod(0o600)
        if b'OK (1 test)' not in raw or b'INSTRUMENTATION_STATUS_CODE: -2' in raw:
            raise RuntimeError('Disposable client ' + mode + ' failed; inspect its private log')

    def display_ids():
        text = adb('shell', 'cmd', 'display', 'get-displays').decode()
        ids = {int(value) for value in re.findall(r'Display id (\d+):', text)}
        if 0 not in ids:
            raise RuntimeError('Cannot establish actual default display identity')
        return ids

    def foreground(ids):
        text = adb('shell', 'dumpsys', 'window', 'displays').decode()
        result = {}
        current = None
        for line in text.splitlines():
            match = re.search(r'Display: mDisplayId=(\d+)', line)
            if match:
                current = int(match[1])
            if 'mCurrentFocus=' in line and current in ids:
                result[current] = line.strip()
        if 0 not in result:
            raise RuntimeError('Cannot observe physical foreground window')
        return result

    def pid(name):
        value = adb('shell', 'pidof', name).decode().strip()
        if not re.fullmatch(r'[0-9]+', value):
            raise RuntimeError('Expected exactly one ' + name + ' process')
        return int(value)

    def await_condition(predicate, label, timeout=15):
        deadline = time.monotonic() + timeout
        while True:
            if predicate():
                return
            if time.monotonic() >= deadline:
                raise RuntimeError(label)
            time.sleep(.1)

    if not args.native_only:
        screen_precondition(adb('shell', 'dumpsys', 'window', 'policy').decode())
    adb('shell', 'run-as', 'dev.siconnect.app', 'true')
    baseline_displays = display_ids()
    baseline_foreground = foreground(baseline_displays)
    packages = adb('shell', 'pm', 'list', 'packages', '--user', '0', 'dev.siconnect.app.test').decode()
    installed_test = 'package:dev.siconnect.app.test' in packages.splitlines()
    port = None
    client = None
    client_phase_attempted = False
    report = {'cases': [], 'physicalForegroundUnchanged': None}
    try:
        # Use the built helper even when an older same-version test package is present.
        adb('install', '-r', str(ROOT / 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'))
        client_phase_attempted = True
        phase('create')
        client = json.loads(adb('exec-out', 'run-as', 'dev.siconnect.app', 'cat', CLIENT_PATH))
        port = int(adb('forward', 'tcp:0', 'tcp:8765').decode().strip())

        def request(path, body=None):
            data = None if body is None else json.dumps(body).encode()
            req = urllib.request.Request('http://127.0.0.1:' + str(port) + path, data=data,
                                         headers={'Authorization': 'Bearer ' + client['token'], 'Content-Type': 'application/json'})
            with urllib.request.urlopen(req, timeout=40) as response:
                return json.load(response)

        def ready():
            try:
                catalog = request('/capabilities')
                status = next(row for row in catalog if row['descriptor']['id'] == 'headless.open')
                if status['descriptor']['backend'] != 'ADB_SERVICE':
                    raise RuntimeError('This harness requires the selected bundled backend; it never changes backend selection')
                if status['availability'] != 'IMPLEMENTED':
                    return False
                probe = request('/invoke', {'capabilityId': 'headless.status', 'arguments': {}})
                if probe.get('state') == 'DENIED':
                    raise RuntimeError('Existing remote policy blocks the test: ' + str(probe.get('policy', {}).get('rule')))
                return probe.get('state') == 'SUCCEEDED'
            except (OSError, urllib.error.URLError):
                return False

        def invoke(capability, arguments=None, key=None):
            body = {'capabilityId': capability, 'arguments': arguments or {}}
            if key:
                body['idempotencyKey'] = key
            result = request('/invoke', body)
            if result.get('state') != 'SUCCEEDED':
                raise RuntimeError(capability + ': ' + str(result.get('state')) + ' / ' + str(result.get('errorCode')))
            return result

        def observe_window(binding):
            deadline = time.monotonic() + 12
            while True:
                result = request('/invoke', {'capabilityId': 'headless.inspect', 'arguments': binding})
                if result.get('state') == 'SUCCEEDED':
                    assert result['result']['output']['targetWindowVerified'] is True
                    return
                if result.get('errorCode') not in ('WORKSPACE_WINDOW_UNAVAILABLE', 'WORKSPACE_TARGET_CHANGED'):
                    raise RuntimeError('Private window observation failed: ' + str(result.get('errorCode')))
                if time.monotonic() >= deadline:
                    raise RuntimeError('Actual Calculator private window did not appear')
                time.sleep(.15)

        await_condition(ready, 'Normal app/debug endpoint did not become ready')
        # A real native read in the ordinary app process, including on a locked phone.
        native_key = 'native-recovery-' + str(uuid.uuid4())
        native = invoke('device.battery', key=native_key)
        assert native['result']['backend'] == 'NATIVE'
        assert 0 <= native['result']['output']['percent'] <= 100
        runtime_before = invoke('device.runtime')['result']['output']
        caller = invoke('headless.status')['result']['output']['workspaceCallerPid']
        app_before = pid('dev.siconnect.app')
        backend_before = pid('siconnect_privileged')
        assert caller == app_before, 'Actual Binder caller differs from the normal app process'
        adb('shell', 'run-as', 'dev.siconnect.app', 'kill', '-9', str(caller))
        await_condition(ready, 'Native normal-process recovery did not become ready', timeout=25)
        def native_processes_changed():
            try:
                return pid('dev.siconnect.app') != app_before and pid('siconnect_privileged') == backend_before
            except (subprocess.CalledProcessError, RuntimeError):
                return False
        await_condition(native_processes_changed, 'Native app process ownership did not settle')
        retained = request('/requests/' + native['requestId'])
        retried = invoke('device.battery', key=native_key)
        assert retained['requestId'] == retried['requestId'] == native['requestId']
        assert retained['result'] == retried['result'] == native['result']
        if client['gatewayEnabled']:
            await_condition(lambda: invoke('device.runtime')['result']['output']['gatewayState'] == 'ONLINE',
                            'Owner-enabled hosted bridge did not recover autonomously without opening an Activity', timeout=25)
        runtime_after = invoke('device.runtime', {'checkDatabase': True})['result']['output']
        assert runtime_after['databaseIntegrity'] == 'OK'
        assert runtime_after['screenInteractive'] == runtime_before['screenInteractive']
        assert runtime_after['deviceLocked'] == runtime_before['deviceLocked']
        assert foreground(baseline_displays) == baseline_foreground
        report['physicalForegroundUnchanged'] = True
        report['cases'].append({'case': 'native-owner-process-death', 'nativeBatteryReadVerified': True,
                                'originalOutcomePreserved': True, 'retryReturnedOriginalRequest': True,
                                'exactOriginalResultReturned': True, 'databaseIntegrity': 'OK',
                                'screenInteractive': runtime_after['screenInteractive'], 'deviceLocked': runtime_after['deviceLocked'],
                                'physicalForegroundUnchanged': True, 'expectedProcessIdentityChange': True,
                                'gatewayRecoveredWithoutActivity': runtime_after['gatewayState'] == 'ONLINE',
                                'observedStartupAssistanceDelta': runtime_after['gatewayBackgroundStartAssistEvents'] - runtime_before['gatewayBackgroundStartAssistEvents']})
        print('native-owner-process-death: actual native read, process recovery and exact original-result retry passed')
        if args.phone_backend_recovery:
            if not client['phoneRecoveryEnabled']:
                raise RuntimeError('Phone-local recovery is not owner-enabled; the harness never changes that setting')
            before_backend = invoke('device.runtime')['result']['output']
            old_app = pid('dev.siconnect.app')
            old_backend = pid('siconnect_privileged')
            adb('shell', 'kill', '-9', str(old_backend))
            # No workstation bootstrap here. The existing phone-side status observer,
            # saved authorization and embedded ADB transport must perform the restart.
            native_during = invoke('device.battery')
            assert native_during['result']['backend'] == 'NATIVE'
            await_condition(ready, 'Owner-enabled phone-local backend recovery did not finish', timeout=100)
            def phone_backend_changed():
                try:
                    return pid('dev.siconnect.app') == old_app and pid('siconnect_privileged') != old_backend
                except (subprocess.CalledProcessError, RuntimeError):
                    return False
            await_condition(phone_backend_changed, 'Phone-local recovery did not establish one replacement backend')
            after_backend = invoke('device.runtime', {'checkDatabase': True})['result']['output']
            assert after_backend['privilegedAvailable'] is True and after_backend['databaseIntegrity'] == 'OK'
            assert after_backend['screenInteractive'] == before_backend['screenInteractive']
            assert after_backend['deviceLocked'] == before_backend['deviceLocked']
            assert foreground(baseline_displays) == baseline_foreground
            report['cases'].append({'case': 'phone-local-backend-recovery', 'hostBootstrapUsed': False,
                                    'ownerRecoveryAlreadyEnabled': True, 'appProcessPreserved': True,
                                    'replacementBackendVerified': True, 'nativeReadSucceeded': True,
                                    'screenInteractive': after_backend['screenInteractive'], 'deviceLocked': after_backend['deviceLocked'],
                                    'physicalForegroundUnchanged': True, 'databaseIntegrity': 'OK'})
            print('phone-local-backend-recovery: real daemon replaced using saved phone authorization; no host bootstrap')
        if args.cold_bridge_seconds:
            if not client['gatewayEnabled']:
                raise RuntimeError('Cold bridge acceptance requires the existing owner-enabled gateway; it never enables a paused bridge')
            before_cold = invoke('device.runtime')['result']['output']
            old_app = pid('dev.siconnect.app')
            def bridge_record_present():
                services = adb('shell', 'dumpsys', 'activity', 'services', 'dev.siconnect.app').decode()
                return re.search(r'ServiceRecord\{[^\n]*dev\.siconnect\.app/(?:dev\.siconnect\.app)?\.transport\.ConnectionService', services) is not None
            if not bridge_record_present():
                raise RuntimeError('Cannot establish the real running bridge service record before stopping it')
            def stopped_bridge():
                stopped = subprocess.run(adb_command + ['shell', 'run-as', 'dev.siconnect.app', 'am', 'stopservice', '--user', '0', '-n',
                                                       'dev.siconnect.app/.transport.ConnectionService'], capture_output=True, timeout=20)
                if stopped.returncode:
                    path = output / 'stop-service-attempt.log'
                    path.write_bytes(stopped.stdout + stopped.stderr)
                    path.chmod(0o600)
                # A stopped foreground service allows Android to freeze this cached app.
                # Observe the real AMS service record, not its now-unavailable debug socket.
                return not bridge_record_present()
            await_condition(stopped_bridge, 'The actual bridge did not stop for cold restoration acceptance')
            print('Cold bridge: real service stopped; allowing normal background startup allowances to expire', flush=True)
            time.sleep(args.cold_bridge_seconds)
            old_backend = pid('siconnect_privileged')
            adb('shell', 'kill', '-9', str(old_backend))
            def cold_backend_gone():
                found = subprocess.run(adb_command + ['shell', 'pidof', 'siconnect_privileged'], capture_output=True, timeout=15)
                return str(old_backend) not in found.stdout.decode().split()
            await_condition(cold_backend_gone, 'Cold bridge backend did not exit')
            subprocess.run(['python3', str(ROOT / 'tools/start_builtin.py'), '--serial', args.serial],
                           check=True, capture_output=True, timeout=40)
            await_condition(ready, 'Cold bridge Binder reattachment did not finish', timeout=25)
            await_condition(lambda: invoke('device.runtime')['result']['output']['gatewayState'] == 'ONLINE',
                            'Cold saved bridge did not restore without an Activity', timeout=25)
            after_cold = invoke('device.runtime')['result']['output']
            assert pid('dev.siconnect.app') == old_app
            assert after_cold['screenInteractive'] == before_cold['screenInteractive']
            assert after_cold['deviceLocked'] == before_cold['deviceLocked']
            assert foreground(baseline_displays) == baseline_foreground
            report['cases'].append({'case': 'cold-owner-enabled-bridge', 'idleSeconds': args.cold_bridge_seconds,
                                    'appProcessPreserved': True, 'gatewayRecoveredWithoutActivity': True,
                                    'observedStartupAssistanceDelta': after_cold['gatewayBackgroundStartAssistEvents'] - before_cold['gatewayBackgroundStartAssistEvents'],
                                    'screenInteractive': after_cold['screenInteractive'], 'deviceLocked': after_cold['deviceLocked'],
                                    'physicalForegroundUnchanged': True})
            print('cold-owner-enabled-bridge: real stopped service recovered ONLINE without an Activity')
        for mode in (() if args.native_only else ('owner-process-death', 'backend-process-death')):
            screen_precondition(adb('shell', 'dumpsys', 'window', 'policy').decode())
            key = 'workspace-recovery-' + str(uuid.uuid4())
            arguments = {'packageName': CALCULATOR, 'ttlMs': 120000}
            opened = invoke('headless.open', arguments, key)
            workspace = opened['result']['output']
            old_display = workspace['displayId']
            binding = {'sessionId': workspace['sessionId'], 'packageName': CALCULATOR}
            observe_window(binding)
            assert old_display not in baseline_displays and old_display in display_ids()
            assert foreground(baseline_displays) == baseline_foreground
            app_before = pid('dev.siconnect.app')
            backend_before = pid('siconnect_privileged')
            assert workspace['workspaceCallerPid'] == app_before, 'Workspace caller differs from the normal app process'
            killed_at = time.monotonic()
            if mode == 'owner-process-death':
                # Same-UID SIGKILL; no Android force-stop flag or package data changes.
                adb('shell', 'run-as', 'dev.siconnect.app', 'kill', '-9', str(app_before))
            else:
                adb('shell', 'kill', '-9', str(backend_before))
            await_condition(lambda: old_display not in display_ids(), 'Owned display survived actual process death')
            cleanup_ms = round((time.monotonic() - killed_at) * 1000)
            if mode == 'backend-process-death':
                def old_backend_gone():
                    found = subprocess.run(adb_command + ['shell', 'pidof', 'siconnect_privileged'], capture_output=True, timeout=15)
                    return str(backend_before) not in found.stdout.decode().split()
                await_condition(old_backend_gone, 'Killed backend process did not exit')
                subprocess.run(['python3', str(ROOT / 'tools/start_builtin.py'), '--serial', args.serial],
                               check=True, capture_output=True, timeout=40)
            await_condition(ready, 'Existing app/Binder ownership did not recover', timeout=25)
            def expected_processes():
                try:
                    current_app = pid('dev.siconnect.app')
                    current_backend = pid('siconnect_privileged')
                except (subprocess.CalledProcessError, RuntimeError):
                    return False
                if mode == 'owner-process-death':
                    return current_app != app_before and current_backend == backend_before
                return current_app == app_before and current_backend != backend_before
            # pidof can briefly include a dying process after SIGKILL. Require the settled
            # single live ownership identities, rather than accepting an ambiguous count.
            await_condition(expected_processes, 'Expected single process ownership did not settle')
            original = request('/requests/' + opened['requestId'])
            assert original['state'] == 'SUCCEEDED' and original['result']['output']['sessionId'] == binding['sessionId']
            replay = invoke('headless.open', arguments, key)
            assert replay['requestId'] == opened['requestId'] and replay['result']['output']['displayId'] == old_display
            assert invoke('headless.status')['result']['output']['sessions'] == []
            gone = request('/invoke', {'capabilityId': 'headless.inspect', 'arguments': binding})
            assert gone.get('errorCode') == 'WORKSPACE_GONE'
            assert display_ids() == baseline_displays and foreground(baseline_displays) == baseline_foreground
            fresh = invoke('headless.open', arguments)['result']['output']
            assert fresh['displayId'] != old_display
            fresh_binding = {'sessionId': fresh['sessionId'], 'packageName': CALCULATOR}
            observe_window(fresh_binding)
            assert invoke('headless.close', fresh_binding)['result']['output']['closed'] is True
            await_condition(lambda: fresh['displayId'] not in display_ids(), 'Fresh recovery display did not close')
            assert foreground(baseline_displays) == baseline_foreground
            report['physicalForegroundUnchanged'] = True
            report['cases'].append({'case': mode, 'displayCleanupMs': cleanup_ms, 'originalOutcomePreserved': True,
                                    'retryReturnedOriginalRequest': True, 'retryCreatedNoDisplay': True,
                                    'freshWorkspaceVerifiedAndRemoved': True, 'expectedProcessIdentityChange': True})
            print(mode + ': real cleanup, recovery and original-request no-replay checks passed')
        report['physicalForegroundUnchanged'] = foreground(baseline_displays) == baseline_foreground
        assert report['physicalForegroundUnchanged']
    finally:
        cleanup_errors = []
        actions = []
        if client_phase_attempted:
            actions.append(('remove-client', lambda: phase('remove')))
        if port is not None:
            actions.append(('remove-forward', lambda: adb('forward', '--remove', 'tcp:' + str(port))))
        if not installed_test:
            actions.append(('remove-test-package', lambda: adb('uninstall', 'dev.siconnect.app.test')))
        # Restore only the already owner-enabled bridge. No Activity or preference changes.
        def restore_bridge():
            deadline = time.monotonic() + 12
            while True:
                try:
                    adb('shell', 'run-as', 'dev.siconnect.app', 'am', 'start-foreground-service', '--user', '0',
                        '-n', 'dev.siconnect.app/.transport.ConnectionService')
                    return
                except subprocess.CalledProcessError:
                    # Test-package removal can race normal app/provider restoration. This
                    # service start is idempotent and preserves the owner's enabled setting.
                    if time.monotonic() >= deadline:
                        raise
                    time.sleep(.3)
        actions.append(('restore-bridge', restore_bridge))
        for label, action in actions:
            try:
                action()
            except Exception as error:
                cleanup_errors.append(label + ': ' + type(error).__name__)
                if isinstance(error, subprocess.CalledProcessError):
                    path = output / (label + '-error.log')
                    path.write_bytes((error.stdout or b'') + (error.stderr or b''))
                    path.chmod(0o600)
        report['cleanupErrors'] = cleanup_errors
        report['completed'] = len(report['cases']) == (1 if args.native_only else 3) + bool(args.cold_bridge_seconds) + bool(args.phone_backend_recovery) and report['physicalForegroundUnchanged'] and not cleanup_errors
        evidence = output / 'recovery-summary.json'
        evidence.write_text(json.dumps(report, indent=2))
        evidence.chmod(0o600)
        if cleanup_errors:
            raise RuntimeError('Recovery harness cleanup was not fully confirmed: ' + ', '.join(cleanup_errors))
    path = output / 'recovery-summary.json'
    path.write_text(json.dumps(report, indent=2))
    path.chmod(0o600)
    print('Private recovery evidence: ' + str(path))


if __name__ == '__main__':
    main()
