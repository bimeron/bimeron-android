#!/usr/bin/env python3
"""Verify the repository's pinned core AAR without external dependencies."""
import hashlib
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
EXPECTED_SHA256 = "f8fa974b5c95240f911269586dd8803b2344f6e2fa96da85a002c7950f186454"


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
