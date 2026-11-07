#!/usr/bin/env python3
"""Verify the repository's pinned core AAR without external dependencies."""
import hashlib
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
EXPECTED_SHA256 = "3b30cbc58c6d73af48c4e80daddb791a39825cf185c4bb6a11b3052454da2aa9"


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
