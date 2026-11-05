import struct
import tempfile
import unittest
import zipfile
from pathlib import Path
from native_alignment import elf, verify_native


def fixture(wide=True, align=16384, off=0, relro=False):
    data = bytearray(256)
    data[:6] = b'\x7fELF' + bytes([2 if wide else 1, 1])
    struct.pack_into('<Q' if wide else '<I', data, 32 if wide else 28, 64)
    struct.pack_into('<HH', data, 54 if wide else 42, 56 if wide else 32, 2 if relro else 1)
    if wide:
        struct.pack_into('<IIQQQQQQ', data, 64, 1, 6, off, 0, 0, 32768, 32768, align)
        if relro:
            struct.pack_into('<IIQQQQQQ', data, 120, 0x6474e552, 4, 0, 0, 0, 4096, 4096, 1)
    else:
        struct.pack_into('<IIIIIIII', data, 64, 1, off, 0, 0, 32768, 32768, 6, align)
    return data


class AlignmentTests(unittest.TestCase):
    def test_elf(self):
        for wide in (False, True):
            elf(fixture(wide))
            for args in ({'align':4096}, {'off':1}, {'align':24576}):
                with self.assertRaises(ValueError):
                    elf(fixture(wide, **args))
        with self.assertRaises(ValueError):
            elf(fixture(relro=True))

    def test_apk(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'app.apk'
            for aligned in (False, True):
                with zipfile.ZipFile(path, 'w') as archive:
                    entry = zipfile.ZipInfo('lib/arm64-v8a/libtest.so')
                    if aligned:
                        padding = 16384 - 30 - len(entry.filename)
                        entry.extra = struct.pack('<HH', 0xffff, padding - 4) + bytes(padding - 4)
                    archive.writestr(entry, fixture())
                if aligned:
                    verify_native(path)
                else:
                    with self.assertRaises(ValueError):
                        verify_native(path)


if __name__ == '__main__':
    unittest.main()
