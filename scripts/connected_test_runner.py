#!/usr/bin/env python3
"""Run connected Android tests in CI with bounded setup phases.

Only numeric diagnostics are retained in ci-diagnostics/metadata.json; see docs/CI.md.
"""
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

PHASES = [
    # Foreign system ANR dialogs ("Pixel Launcher isn't responding") can cover the app for a whole
    # connected run on a cold API 34 emulator. Suppress them before any load.
    ('error_dialogs', ['adb', 'shell', 'settings', 'put', 'global', 'hide_error_dialogs', '1']),
    ('error_dialogs_check', ['adb', 'shell', 'test "$(settings get global hide_error_dialogs)" = 1']),
    ('install', ['./gradlew', ':app:installDebug']),
    ('locale', ['adb', 'shell', 'cmd', 'locale', 'set-app-locales', 'org.bimeron.app', '--user', '0', '--locales', 'ru']),
    ('broadcast_idle', ['adb', 'shell', 'am', 'wait-for-broadcast-idle']),
    ('instrumentation', ['./gradlew', ':app:connectedDebugAndroidTest']),
]
# Typical drains finish within ~21 s, but a 30 s cap proved too tight right after emulator boot.
BROADCAST_IDLE_TIMEOUT = 120
# Settings writes/reads are immediate; the cap only bounds a wedged adb connection.
ERROR_DIALOGS_TIMEOUT = 30
BOUNDED = {'error_dialogs': ERROR_DIALOGS_TIMEOUT, 'error_dialogs_check': ERROR_DIALOGS_TIMEOUT,
           'broadcast_idle': BROADCAST_IDLE_TIMEOUT}


def allowed(value, pattern):
    return value if re.fullmatch(pattern, value) else None


def process_ids(raw):
    # Reject the whole response on unexpected content; never persist raw shell output.
    if len(raw) > 128 or not re.fullmatch(rb'[0-9 \r\n]*', raw):
        return None
    values = raw.split()
    return [int(x) for x in values] if len(values) <= 8 and all(len(x) <= 7 for x in values) else None


def run(run_command=subprocess.call):
    metadata = {'api': 34, 'phases': {name: None for name, _ in PHASES}}
    for key, env, pattern in [('pr_head_sha', 'DIAG_PR_HEAD_SHA', r'[0-9a-f]{40}'),
                              ('run_id', 'GITHUB_RUN_ID', r'[0-9]{1,20}'),
                              ('run_attempt', 'GITHUB_RUN_ATTEMPT', r'[0-9]{1,10}')]:
        metadata[key] = allowed(os.environ.get(env, ''), pattern)
    metadata['checkout_sha'] = None
    metadata['broadcast_idle_seconds'] = None
    try:
        metadata['checkout_sha'] = allowed(subprocess.check_output(['git', 'rev-parse', 'HEAD'], timeout=5).decode().strip(), r'[0-9a-f]{40}')
    except (OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired, UnicodeError):
        pass
    code = 0
    try:
        for name, command in PHASES:
            try:
                if name == 'broadcast_idle':
                    # API 34 has no device deadline; cap this wait within the 45-minute CI budget.
                    started = time.monotonic()
                    try:
                        code = run_command(command, timeout=BROADCAST_IDLE_TIMEOUT,
                                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                    finally:
                        metadata['broadcast_idle_seconds'] = round(time.monotonic() - started, 1)
                elif name in BOUNDED:
                    code = run_command(command, timeout=BOUNDED[name],
                                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                else:
                    code = run_command(command)
            except subprocess.TimeoutExpired:
                code = 124
            except OSError:
                code = 127
            metadata['phases'][name] = code
            if code:
                break
    finally:
        try:
            if code:
                # timeout bounds elapsed time; head bounds captured bytes, even for hostile adb output.
                result = subprocess.run(['bash', '-o', 'pipefail', '-c',
                    'timeout 5s adb shell pidof org.bimeron.app 2>/dev/null | head -c 129'],
                    stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=7)
                metadata['process_probe_exit'] = result.returncode
                metadata['app_pids'] = process_ids(result.stdout)
        except (OSError, subprocess.TimeoutExpired):
            metadata['process_probe_exit'] = 124
        try:
            destination = Path('ci-diagnostics')
            destination.mkdir(exist_ok=True)
            (destination / 'metadata.json').write_text(json.dumps(metadata, indent=2) + '\n')
        except OSError:
            print('Diagnostic metadata unavailable', file=sys.stderr)
    return code if code >= 0 else 128 - code


if __name__ == '__main__':
    sys.exit(run())
