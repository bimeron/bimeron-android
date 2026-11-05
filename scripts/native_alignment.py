"""Dependency-free ELF and APK native alignment validation."""
import struct
import zipfile

PAGE = 16384
ABIS = {'armeabi-v7a', 'arm64-v8a', 'x86', 'x86_64'}


def elf(data):
    if len(data) < 64 or data[:4] != b'\x7fELF' or data[5] != 1 or data[4] not in (1, 2):
        raise ValueError('Unsupported ELF header')
    wide = data[4] == 2
    offset = struct.unpack_from('<Q' if wide else '<I', data, 32 if wide else 28)[0]
    size, count = struct.unpack_from('<HH', data, 54 if wide else 42)
    if size < (56 if wide else 32) or offset + size * count > len(data):
        raise ValueError('Truncated ELF program headers')
    headers = []
    for i in range(count):
        fields = struct.unpack_from('<IIQQQQQQ' if wide else '<IIIIIIII', data, offset + i * size)
        if wide:
            kind, flags, off, addr, _, filesz, memsz, align = fields
        else:
            kind, off, addr, _, filesz, memsz, flags, align = fields
        headers.append((kind, flags, off, addr, memsz, align))
    loads = [h for h in headers if h[0] == 1]
    if not loads:
        raise ValueError('No PT_LOAD segments')
    for _, _, off, addr, _, align in loads:
        if align < PAGE or align & (align - 1) or (addr - off) % align:
            raise ValueError(f'PT_LOAD alignment/congruence: {align:#x}, {off:#x}, {addr:#x}')
    for kind, _, _, addr, memsz, _ in headers:
        end = addr + memsz
        if kind == 0x6474e552 and end % PAGE:
            if any(flags & 2 and start <= addr < start + length and end < start + length
                   for _, flags, _, start, length, _ in loads):
                raise ValueError('GNU_RELRO shares trailing 16 KiB page with writable data')
    return loads


def verify_native(path):
    with zipfile.ZipFile(path) as archive:
        apk = str(path).endswith('.apk')
        prefix = 'lib/' if apk else 'jni/'
        entries = [i for i in archive.infolist() if i.filename.startswith(prefix) and i.filename.endswith('.so')]
        if not entries or (not apk and {i.filename.split('/')[1] for i in entries} != ABIS):
            raise ValueError('Missing native ABIs')
        for entry in entries:
            try:
                elf(archive.read(entry))
                if apk and entry.compress_type == zipfile.ZIP_STORED:
                    with open(path, 'rb') as source:
                        source.seek(entry.header_offset)
                        header = source.read(30)
                    name, extra = struct.unpack_from('<HH', header, 26)
                    if (entry.header_offset + 30 + name + extra) % PAGE:
                        raise ValueError('Unaligned APK native ZIP data')
            except (ValueError, struct.error) as error:
                raise ValueError(f'{entry.filename}: {error}') from error


if __name__ == '__main__':
    import sys
    verify_native(sys.argv[1])
    print('Native alignment OK')
