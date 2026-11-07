#!/usr/bin/env python3
"""Build an archived, pinned core checkout twice and check the AARs are identical.

Usage: configure PATH, JAVA_HOME, ANDROID_HOME, ANDROID_NDK_HOME, then
python3 scripts/rebuild_core_aar.py /path/to/archived/core /path/to/build-prefix

Outputs are written to <prefix>-1 and <prefix>-2 (with <prefix>-N.log build logs).

gomobile writes absolute `replace` directives for the source module into its
generated go.mod, and Go records them in the libgojni.so build info; -trimpath
does not remove them. To keep the AAR independent of where the archive was
extracted, every build runs through the fixed symlink CANONICAL, with both the
working directory and PWD set to it (without PWD, sh resolves the symlink and the
physical path is recorded again). CANONICAL is part of the recorded build layout:
changing it changes the AAR hash.

The build environment is the caller's, minus variables that could change the
output (see build_env): BIMERON_*, CGO_*, GIT_* and Go target/flag variables are
removed, then the values in BUILD_ENV are set. GIT_DIR points at a missing path,
so the core's `git describe` version detection always fails and no version is
stamped, even if the archive was extracted inside a tagged git checkout (git
resolves the physical path, not CANONICAL). ANDROID_NDK_HOME is passed on as its
real path without a trailing slash, so the core's -ffile-prefix-map always matches.

If CANONICAL already exists, the helper stops and never removes it: it may be a
real directory, or the symlink of a rebuild that is still running. A symlink left
by a crashed run must be removed by hand after checking that no rebuild is running.
The helper removes only the symlink it created (same device and inode), also on
SIGINT, SIGTERM and SIGHUP.
"""
import hashlib
import os
from datetime import datetime, timezone
from pathlib import Path
import signal
import stat
import subprocess
import sys
import time

CANONICAL = Path('/var/tmp/bimeron-core-src')
BUILD_ENV = {
    'GOTOOLCHAIN': 'local',
    'GOFLAGS': '-trimpath -p=2',
    'GOMAXPROCS': '2',
    'CGO_LDFLAGS': '-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384',
    'GOENV': 'off',          # ignore `go env -w` settings
    'GOWORK': 'off',         # no go.work from any parent directory
    'GIT_DIR': '/nonexistent/bimeron-no-git',  # disable `git describe` version detection
}
# Removed from the caller's environment before BUILD_ENV is applied.
STRIPPED_PREFIXES = ('BIMERON_', 'CGO_', 'GIT_')
STRIPPED = frozenset({
    'GOFLAGS', 'GOEXPERIMENT', 'GOOS', 'GOARCH', 'GOAMD64', 'GO386', 'GOARM',
    'GOARM64', 'GOFIPS140', 'GOENV', 'GOWORK', 'CC', 'CXX', 'OLDPWD',
})
BUILD_COMMAND = ['sh', 'scripts/build-android.sh']
SIGNALS = (signal.SIGTERM, signal.SIGHUP)


def build_env(environ, canonical=CANONICAL):
    env = {key: value for key, value in environ.items()
           if key not in STRIPPED and not key.startswith(STRIPPED_PREFIXES)}
    env.update(BUILD_ENV)
    ndk = env.get('ANDROID_NDK_HOME')
    if not ndk or not os.path.isdir(ndk):
        sys.exit(f'ANDROID_NDK_HOME must name an installed NDK directory: {ndk!r}')
    env['ANDROID_NDK_HOME'] = os.path.realpath(ndk)  # also drops any trailing slash
    env['PWD'] = str(canonical)  # sh and go keep the logical (symlink) path only if PWD matches
    return env


def link_canonical(target, canonical=CANONICAL):
    """Create canonical -> target and return its (st_dev, st_ino); never replace anything."""
    try:
        os.symlink(target, canonical, target_is_directory=True)
    except FileExistsError:
        if canonical.is_symlink():
            found = f'a symlink to {os.readlink(canonical)}'
        else:
            found = 'not a symlink'
        sys.exit(f'Canonical source path already exists ({found}): {canonical}\n'
                 'Another rebuild may be running, or an earlier one crashed. This helper '
                 'never removes it. If no rebuild is running and it is a stale symlink, '
                 f'remove it with: rm {canonical}')
    info = os.lstat(canonical)
    return info.st_dev, info.st_ino


def unlink_canonical(identity, canonical=CANONICAL):
    """Remove canonical only if it is still the symlink this run created."""
    try:
        info = os.lstat(canonical)
    except FileNotFoundError:
        return
    if stat.S_ISLNK(info.st_mode) and (info.st_dev, info.st_ino) == identity:
        canonical.unlink()


def _exit_on_signal(signum, frame):
    raise SystemExit(128 + signum)  # unwinds through the cleanup in rebuild()


def rebuild(source, prefix, canonical=CANONICAL, run=subprocess.run, environ=None):
    outputs = [Path(f'{prefix}-{number}').resolve() for number in (1, 2)]
    for output in outputs:
        if os.path.lexists(output) or os.path.lexists(f'{output}.log'):
            sys.exit(f'Build output already exists: {output}')
    target = Path(source).resolve()
    if not (target / BUILD_COMMAND[1]).is_file():
        sys.exit(f'Not a core source tree (missing {BUILD_COMMAND[1]}): {target}')
    env = build_env(os.environ if environ is None else environ, canonical)
    artifacts = []
    previous = {number: signal.signal(number, _exit_on_signal) for number in SIGNALS}
    identity = None
    try:
        identity = link_canonical(target, canonical)
        for number, output in enumerate(outputs, 1):
            started = time.monotonic()
            print(f'Build {number} start {datetime.now(timezone.utc).isoformat()} '
                  f'source={target} via={canonical}', flush=True)
            env['ANDROID_BUILD_DIR'] = str(output)
            with open(f'{output}.log', 'w') as log:
                run(BUILD_COMMAND, cwd=str(canonical), env=env,
                    stdout=log, stderr=subprocess.STDOUT, check=True)
            print(f'Build {number} end {datetime.now(timezone.utc).isoformat()} '
                  f'duration={time.monotonic() - started:.1f}s', flush=True)
            artifacts.append((output / 'bimeron-core.aar').read_bytes())
    finally:
        if identity is not None:
            unlink_canonical(identity, canonical)
        for number, handler in previous.items():
            signal.signal(number, handler)
    if artifacts[0] != artifacts[1]:
        sys.exit('Reproducibility failure: complete AAR outputs differ')
    digest = hashlib.sha256(artifacts[0]).hexdigest()
    print('Identical AAR SHA256=' + digest, flush=True)
    return digest


def main(argv):
    if len(argv) != 2:
        sys.exit(__doc__)
    rebuild(*argv)


if __name__ == '__main__':
    main(sys.argv[1:])
