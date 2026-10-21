#!/usr/bin/env python3
"""Check every ELF64 LOAD segment in the APK supports 16 KB pages."""
import struct
import sys
import zipfile

with zipfile.ZipFile(sys.argv[1]) as apk:
    failures = []
    for name in apk.namelist():
        if not name.endswith('.so'):
            continue
        data = apk.read(name)
        assert data[:6] == b'\x7fELF\x02\x01', f'Expected little-endian ELF64: {name}'
        offset = struct.unpack_from('<Q', data, 32)[0]
        size, count = struct.unpack_from('<HH', data, 54)
        for i in range(count):
            fields = struct.unpack_from('<IIQQQQQQ', data, offset + i * size)
            kind, _, file_offset, vaddr, _, _, _, alignment = fields
            if kind == 1 and (alignment < 16384 or (vaddr - file_offset) % 16384):
                failures.append(f'{name}: LOAD alignment {alignment}')
        print(name)
    if failures:
        sys.exit('\n'.join(failures))
    print('All native LOAD segments support 16 KB alignment.')
