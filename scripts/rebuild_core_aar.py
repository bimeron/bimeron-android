#!/usr/bin/env python3
"""Build an archived, pinned core checkout twice and check the AARs are identical.

Usage: configure PATH, JAVA_HOME, ANDROID_HOME, ANDROID_NDK_HOME, then
python3 scripts/rebuild_core_aar.py /path/to/archived/core /path/to/build-prefix

Outputs are written to <prefix>-1 and <prefix>-2 (with <prefix>-N.log build logs).
"""
import hashlib
import time
from datetime import datetime, timezone
import os
from pathlib import Path
import subprocess
import sys

if len(sys.argv) != 3:
    sys.exit(__doc__)
source, prefix = map(Path, sys.argv[1:])
env = os.environ.copy()
env.update(GOTOOLCHAIN='local', GOFLAGS='-trimpath -p=2', GOMAXPROCS='2',
           CGO_LDFLAGS='-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384')
artifacts = []
for number in (1, 2):
    output = Path(f'{prefix}-{number}').resolve()
    if output.exists():
        sys.exit(f'Build output already exists: {output}')
    started = time.monotonic()
    print(f'Build {number} start {datetime.now(timezone.utc).isoformat()}', flush=True)
    env['ANDROID_BUILD_DIR'] = str(output)
    with open(f'{output}.log', 'w') as log:
        subprocess.run(['sh', 'scripts/build-android.sh'], cwd=source,
                       env=env, stdout=log, stderr=subprocess.STDOUT, check=True)
    print(f'Build {number} end {datetime.now(timezone.utc).isoformat()} duration={time.monotonic() - started:.1f}s', flush=True)
    artifacts.append((output / 'bimeron-core.aar').read_bytes())
if artifacts[0] != artifacts[1]:
    sys.exit('Reproducibility failure: complete AAR outputs differ')
print('Identical AAR SHA256=' + hashlib.sha256(artifacts[0]).hexdigest(), flush=True)
