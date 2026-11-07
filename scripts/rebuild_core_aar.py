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

If CANONICAL already exists, the helper stops and never removes it: it may be a
real directory, or the symlink of a rebuild that is still running. A symlink left
by a crashed run must be removed by hand after checking that no rebuild is running.
"""
import hashlib
import os
from datetime import datetime, timezone
from pathlib import Path
import subprocess
import sys
import time

CANONICAL = Path('/var/tmp/bimeron-core-src')
BUILD_ENV = {
    'GOTOOLCHAIN': 'local',
    'GOFLAGS': '-trimpath -p=2',
    'GOMAXPROCS': '2',
    'CGO_LDFLAGS': '-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384',
}
BUILD_COMMAND = ['sh', 'scripts/build-android.sh']


def link_canonical(source, canonical=CANONICAL):
    """Create canonical -> resolved source; fail if canonical exists in any form."""
    target = Path(source).resolve()
    if not (target / BUILD_COMMAND[1]).is_file():
        sys.exit(f'Not a core source tree (missing {BUILD_COMMAND[1]}): {target}')
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
    return target


def unlink_canonical(target, canonical=CANONICAL):
    """Remove canonical only if it is still the symlink this run created."""
    if canonical.is_symlink() and os.readlink(canonical) == str(target):
        canonical.unlink()


def rebuild(source, prefix, canonical=CANONICAL, run=subprocess.run, environ=None):
    outputs = [Path(f'{prefix}-{number}').resolve() for number in (1, 2)]
    for output in outputs:
        if os.path.lexists(output) or os.path.lexists(f'{output}.log'):
            sys.exit(f'Build output already exists: {output}')
    env = dict(os.environ if environ is None else environ)
    env.update(BUILD_ENV)
    env['PWD'] = str(canonical)  # sh and go keep the logical (symlink) path only if PWD matches
    artifacts = []
    target = link_canonical(source, canonical)
    try:
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
        unlink_canonical(target, canonical)
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
