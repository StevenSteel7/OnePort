"""Add <uses-permission> entries to a binary AndroidManifest.xml without decoding resources.
Clones an existing <uses-permission> element and points the clone's android:name at a new string.
usage: python3 axml_add_perm.py in.xml out.xml PERMISSION [PERMISSION...]
"""
import struct, sys

src, dst, perms = sys.argv[1], sys.argv[2], sys.argv[3:]
data = bytearray(open(src, 'rb').read())

RES_STRING_POOL, RES_XML_START_ELEMENT, RES_XML_END_ELEMENT = 0x0001, 0x0102, 0x0103
UTF8_FLAG = 1 << 8

# ---- string pool ----
assert struct.unpack_from('<H', data, 0)[0] == 0x0003, 'not binary XML'
sp = struct.unpack_from('<H', data, 2)[0]            # header size of the XML chunk -> first child
typ, hsz, csz, n_str, n_sty, flags, str_start, sty_start = struct.unpack_from('<HHIIIIII', data, sp)
assert typ == RES_STRING_POOL
utf8 = bool(flags & UTF8_FLAG)
offsets = list(struct.unpack_from(f'<{n_str}I', data, sp + hsz))
sty_offsets = list(struct.unpack_from(f'<{n_sty}I', data, sp + hsz + 4 * n_str))
str_data_end = sp + (sty_start if n_sty else csz)
str_blob = bytes(data[sp + str_start:str_data_end])
sty_blob = bytes(data[sp + sty_start:sp + csz]) if n_sty else b''

def get_str(i):
    o = sp + str_start + offsets[i]
    if utf8:
        n = data[o]; o += 2 if n & 0x80 else 1           # utf16 length
        n = data[o]; o += 1
        if n & 0x80: n = ((n & 0x7f) << 8) | data[o]; o += 1
        return data[o:o + n].decode('utf-8')
    n = struct.unpack_from('<H', data, o)[0]; o += 2
    if n & 0x8000: n = ((n & 0x7fff) << 16) | struct.unpack_from('<H', data, o)[0]; o += 2
    return data[o:o + 2 * n].decode('utf-16le')

def enc(s):
    if utf8:
        b = s.encode('utf-8'); assert len(s) < 0x80 and len(b) < 0x80
        return bytes([len(s), len(b)]) + b + b'\0'
    assert len(s) < 0x8000
    return struct.pack('<H', len(s)) + s.encode('utf-16le') + b'\0\0'

strings = [get_str(i) for i in range(n_str)]
existing = {s for s in strings}
perms = [p for p in perms if p not in existing or print('already present:', p)]
if not perms:
    open(dst, 'wb').write(data); sys.exit(0)

# drop the padding after the last string, append the new strings, re-pad to 4
last = max(range(n_str), key=lambda i: offsets[i])
blob = bytearray(str_blob[:offsets[last] + len(enc(strings[last]))])
new_idx = {}
for p in perms:
    new_idx[p] = n_str + len(new_idx)
    offsets.append(len(blob)); blob += enc(p)
while len(blob) % 4: blob += b'\0'
n_str2 = len(offsets)
str_start2 = hsz + 4 * n_str2 + 4 * n_sty
sty_start2 = (str_start2 + len(blob)) if n_sty else 0
pool = bytearray(struct.pack('<HHIIIIII', RES_STRING_POOL, hsz, 0, n_str2, n_sty, flags, str_start2, sty_start2))
pool += data[sp + 28:sp + hsz]                       # any extra header bytes
pool += struct.pack(f'<{n_str2}I', *offsets) + struct.pack(f'<{n_sty}I', *sty_offsets) + blob + sty_blob
struct.pack_into('<I', pool, 4, len(pool))
rest = data[sp + csz:]

# ---- find a <uses-permission> element to clone ----
def chunks(buf, start):
    o = start
    while o < len(buf):
        t, h, s = struct.unpack_from('<HHI', buf, o)
        yield o, t, h, s
        o += s

name_attr_idx = None
tmpl = None
for o, t, h, s in chunks(rest, 0):
    if t == RES_XML_START_ELEMENT:
        ns, name, a_start, a_size, a_count = struct.unpack_from('<iiHHH', rest, o + 16)
        if strings[name] == 'uses-permission':
            for k in range(a_count):
                ao = o + 16 + a_start + k * a_size
                a_ns, a_name, a_raw, v_size, _, v_type, v_data = struct.unpack_from('<iiiHBBI', rest, ao)
                if strings[a_name] == 'name' and a_raw >= 0:
                    tmpl, name_attr_off = o, ao - o
                    break
        if tmpl is not None:
            end_o = o + s
            assert struct.unpack_from('<H', rest, end_o)[0] == RES_XML_END_ELEMENT
            end_s = struct.unpack_from('<I', rest, end_o + 4)[0]
            insert_at = end_o + end_s
            elem = bytes(rest[o:insert_at])
            break
assert tmpl is not None, 'no <uses-permission> to clone'

clones = bytearray()
for p in perms:
    e = bytearray(elem)
    struct.pack_into('<i', e, name_attr_off + 8, new_idx[p])      # rawValue
    struct.pack_into('<I', e, name_attr_off + 16, new_idx[p])     # typedValue.data (TYPE_STRING)
    clones += e
    print('added', p)
rest = rest[:insert_at] + clones + rest[insert_at:]

out = bytearray(data[:sp]) + pool + rest
struct.pack_into('<I', out, 4, len(out))
open(dst, 'wb').write(out)
