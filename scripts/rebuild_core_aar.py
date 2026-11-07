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
The helper removes only the symlink it created, also on SIGINT, SIGTERM and SIGHUP
(see unlink_canonical for how ownership is decided).
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


# Opens a symlink itself instead of its target, so it can be held open (pinned).
if hasattr(os, 'O_PATH'):
    LINK_OPEN_FLAGS = os.O_PATH | os.O_NOFOLLOW           # Linux
elif hasattr(os, 'O_SYMLINK'):
    LINK_OPEN_FLAGS = os.O_RDONLY | os.O_SYMLINK          # macOS
else:
    LINK_OPEN_FLAGS = None


def _identity(info, target):
    return info.st_dev, info.st_ino, info.st_ctime_ns, target


def link_canonical(target, canonical=CANONICAL):
    """Create canonical -> target and return (identity, pin); never replace anything.

    pin is an open file descriptor on the symlink itself (None if the platform cannot
    open symlinks); unlink_canonical closes it.
    """
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
    if LINK_OPEN_FLAGS is None:
        return _identity(os.lstat(canonical), str(target)), None
    pin = os.open(canonical, LINK_OPEN_FLAGS)
    return _identity(os.fstat(pin), str(target)), pin


def unlink_canonical(owned, canonical=CANONICAL):
    """Remove canonical only if it is still the symlink this run created.

    Device and inode alone are not enough: on Linux (ext4) a symlink that is deleted
    and recreated usually gets the freed inode number back, so a replacement made
    during the build looked like ours and was removed. Two things prevent that:
    - The pin (an open descriptor on our symlink) keeps its inode allocated even
      after the path is removed, so no replacement can get the same inode number
      while the pin is open. It is closed only after the check and the unlink.
    - The identity also includes st_ctime_ns and the link target. Recreating a link
      sets a new ctime, but with a coarse clock (Linux timestamps may advance only
      every few milliseconds) a quick recreation can keep the same ctime, and a
      replacement can point at the same target; these are a second line of defence
      for platforms without a pin, not a guarantee.
    Residual risk: without a pin, a replacement with the same target created within
    one timestamp tick on a filesystem that reuses inode numbers is removed; with or
    without one, the path can still be replaced between the check and the unlink, or
    between symlink() and opening the pin in link_canonical. Both windows are
    microseconds wide and need another process rewriting CANONICAL while a rebuild
    is running.
    """
    identity, pin = owned
    try:
        try:
            info = os.lstat(canonical)
            if not stat.S_ISLNK(info.st_mode):
                return
            target = os.readlink(canonical)
        except OSError:  # gone, or replaced by a non-symlink since lstat
            return
        if _identity(info, target) == identity:
            canonical.unlink(missing_ok=True)
    finally:
        if pin is not None:
            os.close(pin)


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
    owned = None
    try:
        owned = link_canonical(target, canonical)
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
        if owned is not None:
            unlink_canonical(owned, canonical)
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
