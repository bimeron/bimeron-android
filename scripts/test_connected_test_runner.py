#!/usr/bin/env python3
import contextlib
import io
import json
import os
from pathlib import Path
import runpy
import subprocess
import tempfile
import unittest
from unittest.mock import patch

MODULE = runpy.run_path(str(Path(__file__).with_name('connected_test_runner.py')))
ROOT = Path(__file__).resolve().parents[1]


class DiagnosticsTests(unittest.TestCase):
    def test_filter(self):
        parse = MODULE['process_ids']
        self.assertEqual(parse(b'12 34\r\n'), [12, 34])
        for raw in (b'secret', b'12\npassword', b'1 ' * 65, b'12345678', b'1 ' * 9):
            self.assertIsNone(parse(raw))
        self.assertIsNone(MODULE['allowed']('$(secret)', r'[0-9a-f]{40}'))

    def test_exit_and_phase_preservation(self):
        lookups = [({'return_value': b'a' * 40}, 'a' * 40),
                   ({'return_value': b'\xff'}, None)]
        for error in (OSError('git unavailable'),
                      subprocess.CalledProcessError(1, ['git']),
                      subprocess.TimeoutExpired(['git'], 5)):
            lookups.append(({'side_effect': error}, None))
        commands = [command for _, command in MODULE['PHASES']]
        count = len(commands)
        broadcast = [name for name, _ in MODULE['PHASES']].index('broadcast_idle')
        for lookup, expected_sha in lookups:
            for failed in range(count + 1):
                calls = []
                def command(args, **kwargs):
                    calls.append(args)
                    return 23 if len(calls) == failed + 1 else 0
                with tempfile.TemporaryDirectory() as directory:
                    previous = os.getcwd()
                    try:
                        os.chdir(directory)
                        with patch('subprocess.check_output', **lookup), patch('subprocess.run', side_effect=OSError):
                            self.assertEqual(MODULE['run'](command), 23 if failed < count else 0)
                        data = json.loads(Path('ci-diagnostics/metadata.json').read_text())
                        self.assertEqual(data['checkout_sha'], expected_sha)
                        self.assertEqual(calls, commands[:min(failed + 1, count)])
                        self.assertEqual(list(data['phases'].values()),
                            [0] * min(failed, count) + ([23] + [None] * (count - 1 - failed) if failed < count else []))
                        seconds = data['broadcast_idle_seconds']
                        if failed < broadcast:
                            self.assertIsNone(seconds)
                        else:
                            self.assertIsInstance(seconds, float)
                            self.assertGreaterEqual(seconds, 0)
                    finally:
                        os.chdir(previous)

    def test_artifact_selection(self):
        workflow = (ROOT / '.github/workflows/android.yml').read_text()
        paths = workflow.split('name: Retain connected test results')[1]
        self.assertIn('if: always()', paths)
        self.assertIn('connected/**/TEST-*.xml', paths)
        self.assertNotIn('connected/\n', paths)
        self.assertNotIn('logcat', paths)
        self.assertIn('script: python3 scripts/connected_test_runner.py', workflow)

    def test_broadcast_barrier(self):
        expected = [
            ['adb', 'shell', 'settings', 'put', 'global', 'hide_error_dialogs', '1'],
            ['adb', 'shell', 'test "$(settings get global hide_error_dialogs)" = 1'],
            ['./gradlew', ':app:installDebug'],
            ['adb', 'shell', 'cmd', 'locale', 'set-app-locales', 'org.bimeron.app', '--user', '0', '--locales', 'ru'],
            ['adb', 'shell', 'am', 'wait-for-broadcast-idle'],
            ['./gradlew', ':app:connectedDebugAndroidTest'],
        ]
        hostile = b'identity=fixture-secret\nUnknown command: wait-for-broadcast-idle'
        outcomes = [
            ('success', 0, 0),
            ('nonzero', 23, 23),
            ('unsupported', 255, 255),
            ('timeout', subprocess.TimeoutExpired(expected[4], 120,
                                                 output=hostile, stderr=hostile), 124),
            ('launch', OSError(hostile.decode()), 127),
        ]
        probes = [subprocess.CompletedProcess([], 1, stdout=hostile),
                  OSError(hostile.decode()),
                  subprocess.TimeoutExpired(['probe'], 7, output=hostile, stderr=hostile)]
        for label, outcome, exit_code in outcomes:
            for probe in probes:
                for metadata_failure in (None, 'mkdir', 'write_text'):
                    with self.subTest(outcome=label, probe=type(probe).__name__, metadata=metadata_failure):
                        calls = []

                        def command(args, **kwargs):
                            calls.append(args)
                            if args == expected[4]:
                                self.assertEqual(kwargs, {'timeout': 120,
                                    'stdout': subprocess.DEVNULL, 'stderr': subprocess.DEVNULL})
                                if isinstance(outcome, Exception):
                                    raise outcome
                                return outcome
                            if args in expected[:2]:
                                self.assertEqual(kwargs, {'timeout': 30,
                                    'stdout': subprocess.DEVNULL, 'stderr': subprocess.DEVNULL})
                                return 0
                            self.assertEqual(kwargs, {})
                            return 0

                        with tempfile.TemporaryDirectory() as directory:
                            previous = os.getcwd()
                            output = io.StringIO()
                            try:
                                os.chdir(directory)
                                with contextlib.ExitStack() as stack:
                                    stack.enter_context(contextlib.redirect_stdout(output))
                                    stack.enter_context(contextlib.redirect_stderr(output))
                                    stack.enter_context(patch('subprocess.check_output', side_effect=OSError(hostile.decode())))
                                    probe_mock = stack.enter_context(patch('subprocess.run'))
                                    if isinstance(probe, Exception):
                                        probe_mock.side_effect = probe
                                    else:
                                        probe_mock.return_value = probe
                                    if metadata_failure:
                                        stack.enter_context(patch.object(Path, metadata_failure,
                                                                         side_effect=OSError(hostile.decode())))
                                    self.assertEqual(MODULE['run'](command), exit_code)
                                self.assertEqual(calls, expected[:5] if exit_code else expected)
                                self.assertEqual(probe_mock.call_count, 1 if exit_code else 0)
                                if not metadata_failure:
                                    data = json.loads(Path('ci-diagnostics/metadata.json').read_text())
                                    self.assertEqual(data['phases'], {'error_dialogs': 0, 'error_dialogs_check': 0,
                                        'install': 0, 'locale': 0,
                                        'broadcast_idle': exit_code, 'instrumentation': None if exit_code else 0})
                                    self.assertIsNone(data['checkout_sha'])
                                    if exit_code and not isinstance(probe, Exception):
                                        self.assertIsNone(data['app_pids'])
                                for path in Path('.').rglob('*'):
                                    if path.is_file():
                                        self.assertNotIn(b'fixture-secret', path.read_bytes())
                                        self.assertNotIn(b'Unknown command', path.read_bytes())
                                self.assertNotIn('fixture-secret', output.getvalue())
                                self.assertNotIn('Unknown command', output.getvalue())
                            finally:
                                os.chdir(previous)

    def test_broadcast_budget_and_elapsed(self):
        self.assertEqual(MODULE['BROADCAST_IDLE_TIMEOUT'], 120)
        for outcome, exit_code in ((0, 0), (subprocess.TimeoutExpired(['adb'], 120), 124)):
            with self.subTest(exit_code=exit_code):
                def command(args, **kwargs):
                    if kwargs.get('timeout') == 120 and isinstance(outcome, Exception):
                        raise outcome
                    return 0
                with tempfile.TemporaryDirectory() as directory:
                    previous = os.getcwd()
                    try:
                        os.chdir(directory)
                        with patch('subprocess.check_output', side_effect=OSError), \
                                patch('subprocess.run', side_effect=OSError), \
                                patch('time.monotonic', side_effect=[100.0, 220.04]):
                            self.assertEqual(MODULE['run'](command), exit_code)
                        data = json.loads(Path('ci-diagnostics/metadata.json').read_text())
                        self.assertEqual(data['broadcast_idle_seconds'], 120.0)
                        self.assertEqual(data['phases']['broadcast_idle'], exit_code)
                        self.assertEqual(data['phases']['instrumentation'], None if exit_code else 0)
                    finally:
                        os.chdir(previous)


    def test_error_dialog_suppression_is_first_bounded_and_fail_closed(self):
        names = [name for name, _ in MODULE['PHASES']]
        self.assertEqual(names[:3], ['error_dialogs', 'error_dialogs_check', 'install'])
        self.assertEqual(MODULE['ERROR_DIALOGS_TIMEOUT'], 30)
        put, check = MODULE['PHASES'][0][1], MODULE['PHASES'][1][1]
        self.assertEqual(put, ['adb', 'shell', 'settings', 'put', 'global', 'hide_error_dialogs', '1'])
        self.assertEqual(check, ['adb', 'shell', 'test "$(settings get global hide_error_dialogs)" = 1'])
        hostile = 'identity=fixture-secret'
        for phase, outcome, exit_code in (
                (put, 1, 1), (put, subprocess.TimeoutExpired(put, 30, output=hostile.encode()), 124),
                (put, OSError(hostile), 127), (check, 1, 1),
                (check, subprocess.TimeoutExpired(check, 30, output=hostile.encode()), 124)):
            with self.subTest(phase=phase[2], exit_code=exit_code):
                calls = []

                def command(args, **kwargs):
                    calls.append(args)
                    if args in (put, check):
                        self.assertEqual(kwargs, {'timeout': 30,
                            'stdout': subprocess.DEVNULL, 'stderr': subprocess.DEVNULL})
                    if args == phase:
                        if isinstance(outcome, Exception):
                            raise outcome
                        return outcome
                    return 0
                with tempfile.TemporaryDirectory() as directory:
                    previous = os.getcwd()
                    output = io.StringIO()
                    try:
                        os.chdir(directory)
                        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output), \
                                patch('subprocess.check_output', side_effect=OSError), \
                                patch('subprocess.run', side_effect=OSError):
                            self.assertEqual(MODULE['run'](command), exit_code)
                        # Fail closed: nothing after a failed suppression phase runs.
                        self.assertEqual(calls, [put] if phase == put else [put, check])
                        data = json.loads(Path('ci-diagnostics/metadata.json').read_text())
                        expected = {name: None for name in names}
                        expected['error_dialogs'] = exit_code if phase == put else 0
                        if phase == check:
                            expected['error_dialogs_check'] = exit_code
                        self.assertEqual(data['phases'], expected)
                        self.assertIsNone(data['broadcast_idle_seconds'])
                        self.assertNotIn('fixture-secret', Path('ci-diagnostics/metadata.json').read_text())
                        self.assertNotIn('fixture-secret', output.getvalue())
                    finally:
                        os.chdir(previous)

if __name__ == '__main__':
    unittest.main()
