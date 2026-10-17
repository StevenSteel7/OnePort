"""Mark every <uses-library>/<uses-native-library> in a binary AndroidManifest.xml as android:required="false".
Edits attribute values in place (no size change), so resources are not rebuilt.
usage: python3 axml_optional_libs.py in.xml out.xml
"""
import struct, sys

src, dst = sys.argv[1], sys.argv[2]
data = bytearray(open(src, 'rb').read())

RES_STRING_POOL, RES_XML_START_ELEMENT = 0x0001, 0x0102
UTF8_FLAG = 1 << 8
TYPE_INT_BOOLEAN = 0x12

assert struct.unpack_from('<H', data, 0)[0] == 0x0003, 'not binary XML'
sp = struct.unpack_from('<H', data, 2)[0]
typ, hsz, csz, n_str, n_sty, flags, str_start, sty_start = struct.unpack_from('<HHIIIIII', data, sp)
assert typ == RES_STRING_POOL
utf8 = bool(flags & UTF8_FLAG)
offsets = struct.unpack_from(f'<{n_str}I', data, sp + hsz)

def get_str(i):
    if i == 0xffffffff:
        return None
    o = sp + str_start + offsets[i]
    if utf8:
        n = data[o]; o += 2 if n & 0x80 else 1
        n = data[o]; o += 1
        if n & 0x80: n = ((n & 0x7f) << 8) | data[o]; o += 1
        return data[o:o + n].decode('utf-8')
    n = struct.unpack_from('<H', data, o)[0]; o += 2
    if n & 0x8000: n = ((n & 0x7fff) << 16) | struct.unpack_from('<H', data, o)[0]; o += 2
    return data[o:o + 2 * n].decode('utf-16le')

TARGETS = {'uses-library', 'uses-native-library'}
pos, changed = sp + csz, 0
while pos < len(data):
    ctype, chsz, csize = struct.unpack_from('<HHI', data, pos)
    if ctype == RES_XML_START_ELEMENT and get_str(struct.unpack_from('<I', data, pos + 20)[0]) in TARGETS:
        ext = pos + chsz
        a_start, a_size, a_count = struct.unpack_from('<HHH', data, ext + 8)
        attrs = {}
        for k in range(a_count):
            a = ext + a_start + k * a_size
            attrs[get_str(struct.unpack_from('<I', data, a + 4)[0])] = a
        a = attrs.get('required')
        if a is not None and data[a + 15] == TYPE_INT_BOOLEAN and struct.unpack_from('<I', data, a + 16)[0] != 0:
            struct.pack_into('<I', data, a + 16, 0)
            name_a = attrs.get('name')
            print('optional:', get_str(struct.unpack_from('<I', data, name_a + 8)[0]) if name_a else '?')
            changed += 1
    pos += csize

open(dst, 'wb').write(data)
print(f'{changed} entries made optional')
