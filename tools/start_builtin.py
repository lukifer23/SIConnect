#!/usr/bin/env python3
"""Start SIConnect's bundled privileged service through an already authorized Android ADB session.
No companion APK or persistent Mac server is involved. Service authority ends at device reboot.
"""
import argparse
import re
import shlex
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial')
    parser.add_argument('--restart', action='store_true', help='Restart only SIConnect authority after an APK update')
    args = parser.parse_args()
    devices = [line.split()[0] for line in subprocess.check_output(['adb', 'devices'], text=True).splitlines()[1:] if '\tdevice' in line]
    serial = args.serial
    if not serial:
        if len(devices) != 1:
            parser.error('Connect one authorized phone, or choose it with --serial.')
        serial = devices[0]
    if serial not in devices:
        parser.error('The selected phone is not connected and authorized.')
    def shell(command):
        return subprocess.check_output(['adb', '-s', serial, 'shell', command], text=True, timeout=20).strip()
    apk = shell('pm path --user 0 dev.siconnect.app').splitlines()[0].removeprefix('package:')
    if not apk.startswith('/data/app/') or not apk.endswith('.apk'):
        raise RuntimeError('Install SIConnect first.')
    uid_lines = shell('cmd package list packages --user 0 -U dev.siconnect.app').splitlines()
    matches = [re.fullmatch(r'package:dev\.siconnect\.app uid:(\d+)', line) for line in uid_lines]
    uid = next(int(m[1]) for m in matches if m)
    running = shell('pidof siconnect_privileged || true')
    if running and args.restart:
        if not re.fullmatch(r'\d+(?: \d+)*', running):
            raise RuntimeError('Unexpected authority PID')
        shell('kill ' + running)
        time.sleep(.3)
        running = ''
    if running:
        print('SIConnect authority is already started. Tap Check connection in SIConnect.')
        return
    command = f'CLASSPATH={shlex.quote(apk)} /system/bin/app_process /system/bin --nice-name=siconnect_privileged dev.siconnect.app.privileged.DirectShellDaemon {uid} </dev/null >/data/local/tmp/siconnect-bootstrap.log 2>&1 &'
    shell(command)
    for _ in range(10):
        if shell('pidof siconnect_privileged || true'):
            print('SIConnect authority started. No companion app is required. Tap Check connection in SIConnect.')
            return
        time.sleep(.2)
    raise RuntimeError('SIConnect service did not start. Inspect /data/local/tmp/siconnect-bootstrap.log on the selected phone.')


if __name__ == '__main__':
    main()
