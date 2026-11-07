#!/usr/bin/env python3
"""Offline checks of the rebuild helper's canonical path and environment; no real build."""
import contextlib
import io
import os
from pathlib import Path
import re
import runpy
import signal
import stat
import subprocess
import sys
import tempfile
import time
import types
import unittest
from unittest import mock

HELPER = Path(__file__).with_name("rebuild_core_aar.py")
helper = runpy.run_path(str(HELPER), run_name="rebuild_core_aar")
rebuild = helper["rebuild"]
build_env = helper["build_env"]

# The core's version detection, verbatim from scripts/build-android.sh.
DESCRIBE = "git describe --tags --abbrev=0 --match 'v[0-9]*'"

# Stand-in for the core's build script: same `cd` and version detection as the
# real one, then records what it saw and writes a deterministic AAR.
FAKE_BUILD = f"""#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
mkdir -p "$ANDROID_BUILD_DIR"
pwd > "$ANDROID_BUILD_DIR/pwd"
if tag=$({DESCRIBE} 2>/dev/null); then version="$tag"; else version=; fi
printf '%s' "$version" > "$ANDROID_BUILD_DIR/version"
if [ -n "${{TEST_STARTED_MARKER:-}}" ]; then : > "$TEST_STARTED_MARKER"; exec sleep 30; fi
printf 'aar built in %s version=%s' "$PWD" "$version" > "$ANDROID_BUILD_DIR/bimeron-core.aar"
"""

# Plain git for fixture setup, independent of the user's git configuration.
GIT_SETUP_ENV = {"PATH": os.environ.get("PATH", os.defpath), "HOME": "/nonexistent",
                 "GIT_CONFIG_NOSYSTEM": "1", "GIT_CONFIG_GLOBAL": os.devnull}


def git(cwd, *args):
    return subprocess.run(["git", "-c", "user.name=t", "-c", "user.email=t@example.invalid",
                           "-c", "commit.gpgsign=false", *args], cwd=cwd, env=GIT_SETUP_ENV,
                          check=True, capture_output=True, text=True).stdout.strip()


class RebuildTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.make_source(self.root / "physical" / "source")
        self.ndk = self.root / "sdk" / "ndk" / "27.1.12297006"
        self.ndk.mkdir(parents=True)
        (self.root / "out").mkdir()
        self.fresh_paths("build")

    def fresh_paths(self, name):
        """Own canonical path and output prefix, so subtests cannot see each other's leftovers."""
        self.canonical = self.root / f"canonical-{name}"
        self.prefix = self.root / "out" / name

    def make_source(self, path):
        (path / "scripts").mkdir(parents=True)
        (path / "scripts/build-android.sh").write_text(FAKE_BUILD)
        self.source = path

    def base_env(self, **extra):
        return {"PATH": os.environ.get("PATH", os.defpath),
                "ANDROID_NDK_HOME": str(self.ndk), **extra}

    def run_helper(self, run=subprocess.run, environ=None):
        with contextlib.redirect_stdout(io.StringIO()):
            return rebuild(self.source, self.prefix, canonical=self.canonical, run=run,
                           environ=self.base_env() if environ is None else environ)

    def fake_run(self, calls=None, payload=b"same"):
        def run(command, cwd, env, **kwargs):
            if calls is not None:
                calls.append((command, cwd, dict(env), kwargs))
            output = Path(env["ANDROID_BUILD_DIR"])
            output.mkdir()
            (output / "bimeron-core.aar").write_bytes(payload)
        return run

    def pinned_fds(self, path):
        """Open descriptors on the symlink at path itself (not its target)."""
        info = os.lstat(path)
        found = []
        for fd in range(256):
            try:
                opened = os.fstat(fd)
            except OSError:
                continue
            if stat.S_ISLNK(opened.st_mode) and (opened.st_dev, opened.st_ino) == (
                    info.st_dev, info.st_ino):
                found.append(fd)
        return found

    def test_canonical_path_is_fixed(self):
        self.assertEqual(helper["CANONICAL"], Path("/var/tmp/bimeron-core-src"))

    def test_wiring_and_symlink_lifecycle(self):
        calls = []
        inner = self.fake_run(calls)

        pins = []

        def checking_run(command, cwd, env, **kwargs):
            self.assertTrue(self.canonical.is_symlink())
            self.assertEqual(os.readlink(self.canonical), str(self.source.resolve()))
            pins.append(self.pinned_fds(self.canonical))
            inner(command, cwd, env, **kwargs)

        self.run_helper(checking_run, self.base_env(PWD="/elsewhere"))
        self.assertEqual(len(calls), 2)
        # The helper holds one descriptor on its symlink for the whole run, then closes it.
        self.assertEqual(len(pins[0]), 1)
        self.assertEqual(pins[0], pins[1])
        with self.assertRaises(OSError):
            os.fstat(pins[0][0])
        for number, (command, cwd, env, kwargs) in enumerate(calls, 1):
            self.assertEqual(command, ["sh", "scripts/build-android.sh"])
            self.assertEqual(cwd, str(self.canonical))
            self.assertEqual(env["PWD"], str(self.canonical))
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

    def test_version_detection_disabled_in_tagged_checkout(self):
        repo = self.root / "tagged-checkout"
        repo.mkdir()
        git(repo, "init", "-q")
        git(repo, "commit", "-q", "--allow-empty", "-m", "fixture")
        git(repo, "tag", "v9.9.9")
        for source in (repo / "nested" / "source", repo):
            with self.subTest(source=source.name):
                self.fresh_paths(f"version-{source.name}")
                self.make_source(source)
                # Positive control: without the helper's env the tag is found.
                found = subprocess.run(["sh", "-c", DESCRIBE], cwd=source, env=GIT_SETUP_ENV,
                                       capture_output=True, text=True)
                self.assertEqual(found.stdout.strip(), "v9.9.9")
                env = build_env(self.base_env(), self.canonical)
                blocked = subprocess.run(["sh", "-c", DESCRIBE], cwd=source, env=env,
                                         capture_output=True, text=True)
                self.assertNotEqual(blocked.returncode, 0)
                self.assertEqual(blocked.stdout, "")
                # Through the helper: the build sees no version.
                self.run_helper()
                for number in (1, 2):
                    self.assertEqual(Path(f"{self.prefix}-{number}/version").read_text(), "")

    def test_output_affecting_caller_env_is_removed_or_pinned(self):
        calls = []
        caller = self.base_env(
            HOME="/home/fixture", GOPATH="/go", JAVA_HOME="/jdk", ANDROID_HOME="/sdk",
            BIMERON_VERSION="9.9.9", CGO_CFLAGS="-O0", CGO_CPPFLAGS="-DX", CGO_CXXFLAGS="-O0",
            CGO_LDFLAGS="-s", GOFLAGS="-mod=mod", GOEXPERIMENT="nocoverageredesign",
            GOAMD64="v3", GO386="softfloat", GOARM="5", GOOS="linux", GOARCH="amd64",
            GOWORK="/x/go.work", GOENV="/x/env", GOTOOLCHAIN="go1.99", GOMAXPROCS="64",
            GIT_WORK_TREE="/x", GIT_DIR="/x/.git", CC="gcc", CXX="g++", OLDPWD="/x")
        self.run_helper(self.fake_run(calls), caller)
        env = calls[0][2]
        for key in ("BIMERON_VERSION", "CGO_CFLAGS", "CGO_CPPFLAGS", "CGO_CXXFLAGS",
                    "GOEXPERIMENT", "GOAMD64", "GO386", "GOARM", "GOOS", "GOARCH",
                    "GIT_WORK_TREE", "CC", "CXX", "OLDPWD"):
            self.assertNotIn(key, env)
        self.assertEqual({key: env[key] for key in helper["BUILD_ENV"]}, helper["BUILD_ENV"])
        self.assertEqual(env["GOFLAGS"], "-trimpath -p=2")
        self.assertEqual(env["GIT_DIR"], "/nonexistent/bimeron-no-git")
        for key in ("PATH", "HOME", "GOPATH", "JAVA_HOME", "ANDROID_HOME"):
            self.assertEqual(env[key], caller[key])

    def test_ndk_home_is_resolved_without_trailing_slash(self):
        link = self.root / "ndk-link"
        link.symlink_to(self.ndk, target_is_directory=True)
        for value in (f"{link}/", f"{self.ndk}/", f"{self.ndk}//", str(link)):
            with self.subTest(value=value):
                env = build_env(self.base_env(ANDROID_NDK_HOME=value), self.canonical)
                self.assertEqual(env["ANDROID_NDK_HOME"], os.path.realpath(self.ndk))
                self.assertFalse(env["ANDROID_NDK_HOME"].endswith("/"))

    def test_missing_ndk_stops_before_linking(self):
        for environ in ({"PATH": os.defpath}, self.base_env(ANDROID_NDK_HOME=str(self.root / "no"))):
            with self.assertRaisesRegex(SystemExit, "ANDROID_NDK_HOME"):
                self.run_helper(lambda *a, **k: self.fail("build must not run"), environ)
            self.assertFalse(os.path.lexists(self.canonical))

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
            self.fake_run(payload=next(outputs))(command, cwd, env, **kwargs)

        with self.assertRaisesRegex(SystemExit, "Reproducibility failure"):
            self.run_helper(drifting_run)
        self.assertFalse(os.path.lexists(self.canonical))

    def test_replaced_symlink_is_left_alone(self):
        # Real filesystem. Same target string: a target comparison alone would remove it.
        # On ext4 the replacement gets the freed inode number unless the helper pins
        # its symlink, so this also checks the pin on Linux.
        for target in (self.root / "other-run", self.source.resolve()):
            with self.subTest(target=target.name):
                self.fresh_paths(f"replaced-{target.name}")
                seen = {}

                def hijacked_run(command, cwd, env, **kwargs):
                    seen["ours"] = os.lstat(self.canonical)
                    self.canonical.unlink()
                    self.canonical.symlink_to(target, target_is_directory=True)
                    seen["replacement"] = os.lstat(self.canonical)
                    raise subprocess.CalledProcessError(1, command)

                with self.assertRaises(subprocess.CalledProcessError):
                    self.run_helper(hijacked_run)
                ours, replacement = seen["ours"], seen["replacement"]
                self.assertNotEqual((replacement.st_dev, replacement.st_ino),
                                    (ours.st_dev, ours.st_ino), "inode reused despite the pin")
                self.assertEqual(os.readlink(self.canonical), str(target))

    def test_inode_reuse_is_not_mistaken_for_ownership(self):
        # Filesystem-independent: after the replacement, lstat reports our device and
        # inode (as ext4 does when the number is reused), with or without our ctime.
        real_lstat = os.lstat
        cases = (  # name, replacement target, ctime shift, expected to be removed
            ("new-ctime-same-target", self.source.resolve(), 1, False),
            ("same-ctime-other-target", self.root / "other-run", 0, False),
            # Control: identical in every compared field, so it must be treated as ours.
            # This is the residual risk the pin exists for; it shows the fake is used.
            ("indistinguishable", self.source.resolve(), 0, True),
        )
        for name, target, shift, removed in cases:
            with self.subTest(case=name):
                self.fresh_paths(f"reuse-{name}")
                canonical = str(self.canonical)
                fake = {}

                def faking_lstat(path, *args, **kwargs):
                    info = real_lstat(path, *args, **kwargs)
                    if "ours" not in fake or os.fspath(path) != canonical:
                        return info
                    ours = fake["ours"]
                    return types.SimpleNamespace(
                        st_mode=info.st_mode, st_dev=ours.st_dev, st_ino=ours.st_ino,
                        st_ctime_ns=ours.st_ctime_ns + shift)

                def hijacked_run(command, cwd, env, **kwargs):
                    ours = real_lstat(self.canonical)
                    self.canonical.unlink()
                    self.canonical.symlink_to(target, target_is_directory=True)
                    fake["ours"] = ours
                    raise subprocess.CalledProcessError(1, command)

                with mock.patch.object(os, "lstat", faking_lstat):
                    with self.assertRaises(subprocess.CalledProcessError):
                        self.run_helper(hijacked_run)
                if removed:
                    self.assertFalse(os.path.lexists(self.canonical))
                else:
                    self.assertEqual(os.readlink(self.canonical), str(target))

    def test_signal_in_process_unlinks_and_restores_handlers(self):
        for number in (signal.SIGTERM, signal.SIGHUP):
            with self.subTest(signal=number.name):
                self.fresh_paths(f"signal-{number.name}")
                before = signal.getsignal(number)

                def signalled_run(command, **kwargs):
                    os.kill(os.getpid(), number)
                    time.sleep(5)
                    self.fail("signal was not handled")

                with self.assertRaises(SystemExit) as raised:
                    self.run_helper(signalled_run)
                self.assertEqual(raised.exception.code, 128 + number)
                self.assertFalse(os.path.lexists(self.canonical))
                self.assertIs(signal.getsignal(number), before)

    def test_external_signal_during_real_build_unlinks(self):
        driver = ("import runpy, sys\n"
                  "from pathlib import Path\n"
                  "h = runpy.run_path(sys.argv[1], run_name='rebuild_core_aar')\n"
                  "h['rebuild'](sys.argv[2], sys.argv[3], canonical=Path(sys.argv[4]))\n")
        for number in (signal.SIGTERM, signal.SIGHUP):
            with self.subTest(signal=number.name):
                started = self.root / f"started-{number.name}"
                prefix = self.root / "out" / number.name
                child = subprocess.Popen(
                    [sys.executable, "-I", "-c", driver, str(HELPER), str(self.source),
                     str(prefix), str(self.canonical)],
                    env=self.base_env(TEST_STARTED_MARKER=str(started)),
                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                self.addCleanup(child.kill)
                deadline = time.monotonic() + 20
                while not started.exists():
                    self.assertIsNone(child.poll(), "helper exited before the build started")
                    self.assertLess(time.monotonic(), deadline, "build did not start")
                    time.sleep(0.05)
                self.assertTrue(self.canonical.is_symlink())
                child.send_signal(number)
                self.assertEqual(child.wait(timeout=20), 128 + number)
                self.assertFalse(os.path.lexists(self.canonical))


if __name__ == "__main__":
    unittest.main()
