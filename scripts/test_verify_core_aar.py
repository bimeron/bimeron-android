#!/usr/bin/env python3
"""Small offline checks; never modify the real AAR."""
import hashlib
import runpy
from pathlib import Path
import tempfile
import unittest

checker = runpy.run_path(str(Path(__file__).with_name("verify_core_aar.py")))
verify = checker["verify"]


class ChecksumTests(unittest.TestCase):
    def test_success(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "core.aar"
            path.write_bytes(b"fixture")
            expected = hashlib.sha256(b"fixture").hexdigest()
            self.assertEqual(verify(path, expected), expected)

    def test_mismatch(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "core.aar"
            path.write_bytes(b"changed")
            with self.assertRaisesRegex(ValueError, "actual:.*\nexpected:"):
                verify(path)

    def test_missing(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "Cannot read AAR"):
                verify(Path(directory) / "missing.aar")

    def test_documented_pin(self):
        document = (checker["ROOT"] / "docs/CORE-AAR.md").read_text()
        self.assertIn("\n" + checker["EXPECTED_SHA256"] + "\n", document)


if __name__ == "__main__":
    unittest.main()
