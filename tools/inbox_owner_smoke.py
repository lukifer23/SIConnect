#!/usr/bin/env python3
"""Real normal-process proposal setup/status/cleanup. Acceptance is exclusively the owner's Android UI action."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import urllib.request
import urllib.error
import uuid
from semantic_ui_smoke import screen_precondition, session_probe_absent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--state', type=Path, required=True, help='Temporary private session file, outside the repository')
    parser.add_argument('action', choices=('setup', 'status', 'cleanup'))
    args = parser.parse_args()
    os.umask(0o077)
    if args.state.resolve().is_relative_to(Path(__file__).resolve().parent.parent):
        parser.error('Keep session credentials outside the public checkout')

    def adb(*words):
        return subprocess.run(['adb', '-s', args.serial, *words], capture_output=True, check=True, timeout=30).stdout

    def launch(session, extra):
        adb('shell', 'am', 'start', '-W', '-f', '0x18000000', '-n',
            'dev.siconnect.app/.accessibility.SemanticUiFixtureActivity', '--es', 'semanticSessionId', session, '--ez', extra, 'true')

    if args.action == 'setup':
        screen_precondition(adb('shell', 'dumpsys', 'window', 'policy').decode())
        # Refuse to overwrite either an active or failed collection's cleanup identity.
        with args.state.open('x') as output:
            config = {'sessionId': str(uuid.uuid4())}
            json.dump(config, output)
        launch(config['sessionId'], 'ownerInboxHarness')
        deadline = time.monotonic()+15
        while True:
            raw = adb('exec-out', 'run-as', 'dev.siconnect.app', 'cat', 'no_backup/semantic-harness-'+config['sessionId']+'.json')
            try:
                saved = json.loads(raw)
                if saved['sessionId'] != config['sessionId']:
                    raise RuntimeError('Session mismatch')
                config.update(saved)
                args.state.write_text(json.dumps(config))
                break
            except json.JSONDecodeError:
                if time.monotonic() > deadline:
                    raise RuntimeError('Session setup timed out; use cleanup with the retained state file')
                time.sleep(.1)
    else:
        config = json.loads(args.state.read_text())

    if args.action == 'cleanup':
        # Dismiss only these unaccepted disposable proposals; retain accepted request history.
        withdrawal_error = None
        if config.get('token'):
            try:
                invoke_all(config, adb, withdraw=True)
            except Exception as error:
                withdrawal_error = type(error).__name__
        # Expired credentials must not prevent precise local revocation/credential cleanup.
        launch(config['sessionId'], 'semanticHarnessCleanup')
        path = 'no_backup/semantic-harness-'+config['sessionId']+'.json'
        deadline = time.monotonic()+10
        while True:
            probe = subprocess.run(['adb', '-s', args.serial, 'exec-out', 'run-as', 'dev.siconnect.app', 'ls', path], capture_output=True, timeout=10)
            if session_probe_absent(probe.stdout, probe.stderr, path):
                break
            if time.monotonic() > deadline:
                raise RuntimeError('Session cleanup not verified; state file retained')
            time.sleep(.1)
        args.state.unlink()
        print(json.dumps({'disposableSessionCleaned': True, 'withdrawalError': withdrawal_error,
                          'retainedProposals': 'Accepted history is retained; unresolved proposals expire and cannot execute under a removed identity.'}))
    else:
        invoke_all(config, adb, setup=args.action == 'setup')
    if args.action in ('setup', 'cleanup'):
        adb('shell', 'am', 'start', '-W', '--activity-clear-top', '-n', 'dev.siconnect.app/.ui.MainActivity')


def invoke_all(config, adb, setup=False, withdraw=False):
    port = adb('forward', 'tcp:0', 'tcp:8765').decode().strip()
    try:
        deadline = time.monotonic()+15
        while True:
            probe = urllib.request.Request('http://127.0.0.1:'+port+'/capabilities',
                headers={'Authorization': 'Bearer '+config['token']})
            try:
                with urllib.request.urlopen(probe, timeout=3) as response:
                    json.load(response)
                break
            except urllib.error.HTTPError:
                raise
            except (OSError, urllib.error.URLError):
                if time.monotonic() > deadline:
                    raise RuntimeError('Authenticated loopback readiness timed out')
                time.sleep(.1)
        def call(capability, arguments):
            req = urllib.request.Request('http://127.0.0.1:'+port+'/invoke',
                data=json.dumps({'capabilityId': capability, 'arguments': arguments}).encode(),
                headers={'Authorization': 'Bearer '+config['token'], 'Content-Type': 'application/json'})
            with urllib.request.urlopen(req, timeout=20) as response:
                record = json.load(response)
            if record['state'] != 'SUCCEEDED':
                raise RuntimeError(capability+': '+record['state']+' '+str(record.get('errorCode')))
            return record['result']['output']
        if setup:
            for suffix in ('accept', 'cancel'):
                call('proposals.create', {'title': 'Inbox check: '+suffix, 'summary': 'Read native device status. No phone data will change.',
                    'reason': 'Owner-interactive review test; cancel authentication on the cancel item, then dismiss it.',
                    'sourceType': 'disposable fixture', 'capabilityId': 'device.status', 'arguments': {},
                    'idempotencyKey': config['sessionId']+':'+suffix, 'expiresAtMs': int(time.time()*1000)+20*60_000})
        rows = call('proposals.list', {'limit': 20})['proposals']
        results = []
        for row in rows:
            if withdraw and row['state'] in ('PROPOSED', 'VIEWED'):
                call('proposals.withdraw', {'proposalId': row['proposalId']})
            results.append({k: row[k] for k in ('proposalId', 'title', 'state', 'linkedRequestId', 'requestState') if k in row})
        print(json.dumps({'classification': 'PHYSICAL_DEVICE_AUTHENTICATED_LOOPBACK', 'ownerAuthenticationAutomated': False, 'proposals': results}))
    finally:
        primary_failure = sys.exc_info()[0] is not None
        try:
            adb('forward', '--remove', 'tcp:'+port)
        except Exception as error:
            if primary_failure:
                print(json.dumps({'cleanupError': 'forward_remove:'+type(error).__name__}), file=sys.stderr)
            else:
                raise



if __name__ == '__main__':
    main()
