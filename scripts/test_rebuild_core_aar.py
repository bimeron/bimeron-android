#!/usr/bin/env python3
"""Offline checks of the rebuild helper's canonical-path handling; no real build."""
import contextlib
import io
import os
import re
from pathlib import Path
import runpy
import subprocess
import tempfile
import unittest

helper = runpy.run_path(str(Path(__file__).with_name("rebuild_core_aar.py")),
                        run_name="rebuild_core_aar")
rebuild = helper["rebuild"]

# Stand-in for the core's build script: same `cd` as the real one, then records
# the logical working directory and writes a deterministic AAR.
FAKE_BUILD = """#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
mkdir -p "$ANDROID_BUILD_DIR"
pwd > "$ANDROID_BUILD_DIR/pwd"
printf 'aar built in %s' "$PWD" > "$ANDROID_BUILD_DIR/bimeron-core.aar"
"""


class RebuildTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.source = self.root / "physical" / "source"
        (self.source / "scripts").mkdir(parents=True)
        (self.source / "scripts/build-android.sh").write_text(FAKE_BUILD)
        self.canonical = self.root / "canonical"
        self.prefix = self.root / "out" / "build"
        self.prefix.parent.mkdir()

    def run_helper(self, run=subprocess.run, environ=None):
        with contextlib.redirect_stdout(io.StringIO()):
            return rebuild(self.source, self.prefix, canonical=self.canonical,
                           run=run, environ=environ if environ is not None
                           else {"PATH": os.environ.get("PATH", os.defpath)})

    def test_canonical_path_is_fixed(self):
        self.assertEqual(helper["CANONICAL"], Path("/var/tmp/bimeron-core-src"))

    def test_wiring_and_symlink_lifecycle(self):
        calls = []

        def fake_run(command, cwd, env, **kwargs):
            self.assertTrue(self.canonical.is_symlink())
            self.assertEqual(os.readlink(self.canonical), str(self.source.resolve()))
            calls.append((command, cwd, dict(env), kwargs))
            output = Path(env["ANDROID_BUILD_DIR"])
            output.mkdir()
            (output / "bimeron-core.aar").write_bytes(b"same")

        self.run_helper(fake_run, {"PWD": "/elsewhere", "GOFLAGS": "-mod=mod"})
        self.assertEqual(len(calls), 2)
        for number, (command, cwd, env, kwargs) in enumerate(calls, 1):
            self.assertEqual(command, ["sh", "scripts/build-android.sh"])
            self.assertEqual(cwd, str(self.canonical))
            self.assertEqual(env["PWD"], str(self.canonical))
            self.assertEqual(env["GOFLAGS"], "-trimpath -p=2")
            self.assertEqual(env["GOTOOLCHAIN"], "local")
            self.assertEqual(env["ANDROID_BUILD_DIR"], f"{self.prefix.resolve()}-{number}")
            self.assertTrue(kwargs["check"])
        self.assertFalse(os.path.lexists(self.canonical))

    def test_real_shell_keeps_logical_path(self):
        digest = self.run_helper()
        self.assertRegex(digest, "^[0-9a-f]{64}$")
        for number in (1, 2):
            recorded = Path(f"{self.prefix}-{number}/pwd").read_text().strip()
            self.assertEqual(recorded, str(self.canonical))
        self.assertFalse(os.path.lexists(self.canonical))

    def test_shell_without_pwd_resolves_symlink(self):
        # Negative control: cwd alone is not enough, which is why the helper sets PWD.
        self.canonical.symlink_to(self.source, target_is_directory=True)
        env = {key: value for key, value in os.environ.items() if key != "PWD"}
        printed = subprocess.run(["sh", "-c", "pwd"], cwd=str(self.canonical), env=env,
                                 check=True, capture_output=True, text=True).stdout.strip()
        self.assertEqual(Path(printed), Path(printed).resolve())
        self.assertNotEqual(printed, str(self.canonical))

    def test_existing_directory_is_never_touched(self):
        self.canonical.mkdir()
        (self.canonical / "keep").write_text("data")
        with self.assertRaisesRegex(SystemExit, "already exists \\(not a symlink\\)"):
            self.run_helper(lambda *a, **k: self.fail("build must not run"))
        self.assertEqual((self.canonical / "keep").read_text(), "data")

    def test_stale_symlink_is_reported_not_removed(self):
        stale = self.root / "old-run"
        self.canonical.symlink_to(stale, target_is_directory=True)
        pattern = f"a symlink to {re.escape(str(stale))}(.|\\n)*rm {re.escape(str(self.canonical))}"
        with self.assertRaisesRegex(SystemExit, pattern):
            self.run_helper(lambda *a, **k: self.fail("build must not run"))
        self.assertEqual(os.readlink(self.canonical), str(stale))

    def test_existing_output_stops_before_linking(self):
        Path(f"{self.prefix}-2").mkdir()
        with self.assertRaisesRegex(SystemExit, "Build output already exists"):
            self.run_helper(lambda *a, **k: self.fail("build must not run"))
        self.assertFalse(os.path.lexists(self.canonical))

    def test_not_a_core_tree(self):
        (self.source / "scripts/build-android.sh").unlink()
        with self.assertRaisesRegex(SystemExit, "Not a core source tree"):
            self.run_helper()
        self.assertFalse(os.path.lexists(self.canonical))

    def test_failed_build_unlinks(self):
        def failing_run(command, **kwargs):
            raise subprocess.CalledProcessError(2, command)

        with self.assertRaises(subprocess.CalledProcessError):
            self.run_helper(failing_run)
        self.assertFalse(os.path.lexists(self.canonical))

    def test_differing_builds_fail_and_unlink(self):
        outputs = iter((b"first", b"second"))

        def drifting_run(command, cwd, env, **kwargs):
            output = Path(env["ANDROID_BUILD_DIR"])
            output.mkdir()
            (output / "bimeron-core.aar").write_bytes(next(outputs))

        with self.assertRaisesRegex(SystemExit, "Reproducibility failure"):
            self.run_helper(drifting_run)
        self.assertFalse(os.path.lexists(self.canonical))

    def test_replaced_symlink_is_left_alone(self):
        other = self.root / "other-run"

        def hijacked_run(command, cwd, env, **kwargs):
            self.canonical.unlink()
            self.canonical.symlink_to(other, target_is_directory=True)
            raise subprocess.CalledProcessError(1, command)

        with self.assertRaises(subprocess.CalledProcessError):
            self.run_helper(hijacked_run)
        self.assertEqual(os.readlink(self.canonical), str(other))


if __name__ == "__main__":
    unittest.main()
