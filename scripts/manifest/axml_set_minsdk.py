"""Set <uses-sdk android:minSdkVersion> in a binary AndroidManifest.xml (in place, no size change).
usage: python3 axml_set_minsdk.py in.xml out.xml API
"""
import struct
import sys

src, dst, api = sys.argv[1], sys.argv[2], int(sys.argv[3])
MIN_SDK_VERSION = 0x0101020C
data = bytearray(open(src, 'rb').read())
assert struct.unpack_from('<H', data, 0)[0] == 0x0003, 'not binary XML'
sp = struct.unpack_from('<H', data, 2)[0]
rm = sp + struct.unpack_from('<I', data, sp + 4)[0]
assert struct.unpack_from('<H', data, rm)[0] == 0x0180, 'no resource map'
rm_hsz, rm_csz = struct.unpack_from('<HI', data, rm + 2)
ids = struct.unpack_from(f'<{(rm_csz - rm_hsz) // 4}I', data, rm + rm_hsz)
name_idx = ids.index(MIN_SDK_VERSION)

pos, changed = rm + rm_csz, 0
while pos < len(data):
    ctype, chsz, csize = struct.unpack_from('<HHI', data, pos)
    if ctype == 0x0102:
        a_start, a_size, a_count = struct.unpack_from('<HHH', data, pos + 16 + 8)
        for a in range(a_count):
            o = pos + 16 + a_start + a * a_size
            if struct.unpack_from('<I', data, o + 4)[0] == name_idx:
                old = struct.unpack_from('<I', data, o + 16)[0]
                struct.pack_into('<I', data, o + 8, 0xFFFFFFFF)  # no raw string value
                struct.pack_into('<HBBI', data, o + 12, 8, 0, 0x10, api)  # TYPE_INT_DEC
                changed += 1
    pos += csize
assert changed == 1, f'expected one minSdkVersion, found {changed}'
open(dst, 'wb').write(data)
print(f'minSdkVersion {old} -> {api}')
