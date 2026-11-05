#!/usr/bin/env python3
"""Verify the repository's pinned core AAR without external dependencies."""
import hashlib
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
EXPECTED_SHA256 = "d95e9aa7631d31985e76534bf94aa364e5554257cdc582250ca703ae99372535"


def verify(path, expected=EXPECTED_SHA256):
    try:
        actual = hashlib.sha256(Path(path).read_bytes()).hexdigest()
    except OSError as error:
        raise ValueError(f"Cannot read AAR {path}: {error}; expected SHA256={expected}") from error
    if actual != expected:
        raise ValueError(f"AAR checksum mismatch: {path}\nactual:   {actual}\nexpected: {expected}")
    # Keep checksum-only custom fixtures supported; enforce natives for the pinned artifact.
    if expected == EXPECTED_SHA256:
        import sys
        sys.path.insert(0, str(Path(__file__).resolve().parent))
        from native_alignment import verify_native
        verify_native(path)
    return actual


if __name__ == "__main__":
    try:
        print(f"AAR SHA256 OK: {verify(ROOT / 'app/libs/bimeron-core.aar')}")
    except ValueError as error:
        print(error, file=sys.stderr)
        sys.exit(1)
