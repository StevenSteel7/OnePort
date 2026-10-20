"""Add an android:* reference attribute to an element of a binary AndroidManifest.xml.

Samsung apps often omit android:icon because One UI supplies launcher icons
from its icon theme; on other devices that leaves the default robot icon.

usage: python3 axml_add_attr.py in.xml out.xml ELEMENT ATTR_NAME ATTR_RES_ID VALUE_RES_ID
  e.g. axml_add_attr.py in.xml out.xml application icon 0x01010002 0x7f100001

The attribute name must be backed by the XML resource map, so when it is
missing the name is inserted at the end of the mapped string range and every
string reference after it is renumbered.
"""
import struct
import sys

src, dst, element, attr_name = sys.argv[1:5]
attr_res = int(sys.argv[5], 16)
value_res = int(sys.argv[6], 16)
ANDROID_NS = "http://schemas.android.com/apk/res/android"

data = bytearray(open(src, "rb").read())
assert struct.unpack_from("<H", data, 0)[0] == 0x0003, "not binary XML"
xml_header = struct.unpack_from("<H", data, 2)[0]

# ---- string pool ----
sp = xml_header
typ, hsz, csz, n_str, n_sty, flags, str_start, sty_start = struct.unpack_from("<HHIIIIII", data, sp)
assert typ == 0x0001 and n_sty == 0, "unexpected string pool"
utf8 = bool(flags & 0x100)
offsets = list(struct.unpack_from(f"<{n_str}I", data, sp + hsz))


def read_str(i):
    o = sp + str_start + offsets[i]
    if utf8:
        n = data[o]; o += 2 if n & 0x80 else 1
        n = data[o]; o += 1
        if n & 0x80:
            n = ((n & 0x7F) << 8) | data[o]; o += 1
        return bytes(data[o:o + n]).decode("utf-8")
    n = struct.unpack_from("<H", data, o)[0]; o += 2
    if n & 0x8000:
        n = ((n & 0x7FFF) << 16) | struct.unpack_from("<H", data, o)[0]; o += 2
    return bytes(data[o:o + 2 * n]).decode("utf-16-le")


def encode(s):
    if utf8:
        b = s.encode("utf-8")
        assert len(s) < 0x80 and len(b) < 0x80
        return bytes([len(s), len(b)]) + b + b"\0"
    return struct.pack("<H", len(s)) + s.encode("utf-16-le") + b"\0\0"


strings = [read_str(i) for i in range(n_str)]
pool_end = sp + csz

# ---- resource map ----
rm = pool_end
assert struct.unpack_from("<H", data, rm)[0] == 0x0180, "no resource map"
rm_hsz, rm_csz = struct.unpack_from("<HI", data, rm + 2)
res_ids = list(struct.unpack_from(f"<{(rm_csz - rm_hsz) // 4}I", data, rm + rm_hsz))
nodes_start = rm + rm_csz

name_idx = next((i for i, r in enumerate(res_ids) if r == attr_res and strings[i] == attr_name), None)
shift_from = None
if name_idx is None:
    # Insert the name at the end of the mapped range; renumber later refs.
    name_idx = shift_from = len(res_ids)
    strings.insert(name_idx, attr_name)
    res_ids.append(attr_res)


def ref(v):
    if v == 0xFFFFFFFF or shift_from is None:
        return v
    return v + 1 if v >= shift_from else v


# Indices below are in the new numbering (strings already holds the insert).
ns_idx = strings.index(ANDROID_NS)

# ---- rewrite nodes ----
out_nodes = bytearray()
pos = nodes_start
added = False
while pos < len(data):
    ctype, chsz, ccsz = struct.unpack_from("<HHI", data, pos)
    chunk = bytearray(data[pos:pos + ccsz])
    # node header: lineNumber, comment
    struct.pack_into("<I", chunk, 12, ref(struct.unpack_from("<I", chunk, 12)[0]))
    body = 16
    if ctype in (0x0100, 0x0101):  # namespace start/end: prefix, uri
        for k in (body, body + 4):
            struct.pack_into("<I", chunk, k, ref(struct.unpack_from("<I", chunk, k)[0]))
    elif ctype == 0x0103:  # end element: ns, name
        for k in (body, body + 4):
            struct.pack_into("<I", chunk, k, ref(struct.unpack_from("<I", chunk, k)[0]))
    elif ctype == 0x0104:  # cdata: data, typed value
        struct.pack_into("<I", chunk, body, ref(struct.unpack_from("<I", chunk, body)[0]))
        if chunk[body + 7] == 0x03:
            struct.pack_into("<I", chunk, body + 8, ref(struct.unpack_from("<I", chunk, body + 8)[0]))
    elif ctype == 0x0102:  # start element
        ens, ename = struct.unpack_from("<II", chunk, body)
        attr_start, attr_size, attr_count, id_i, class_i, style_i = struct.unpack_from("<HHHHHH", chunk, body + 8)
        struct.pack_into("<II", chunk, body, ref(ens), ref(ename))
        attrs = []
        base = body + attr_start
        for a in range(attr_count):
            o = base + a * attr_size
            ans, aname, araw = struct.unpack_from("<III", chunk, o)
            tsize, tres0, ttype, tdata = struct.unpack_from("<HBBI", chunk, o + 12)
            if ttype == 0x03:
                tdata = ref(tdata)
            attrs.append([ref(ans), ref(aname), ref(araw), tsize, tres0, ttype, tdata])
        is_target = not added and ename != 0xFFFFFFFF and strings[ref(ename)] == element
        if is_target:
            assert not any(a[1] == name_idx for a in attrs), f"{element} already has {attr_name}"
            new = [ns_idx, name_idx, 0xFFFFFFFF, 8, 0, 0x01, value_res]

            def key(a):
                r = res_ids[a[1]] if a[1] < len(res_ids) else 0xFFFFFFFF
                return r
            insert_at = next((i for i, a in enumerate(attrs) if key(a) > attr_res), len(attrs))
            attrs.insert(insert_at, new)
            # 1-based indices of id/class/style attributes shift too
            id_i, class_i, style_i = [(v + 1 if v and v > insert_at else v) for v in (id_i, class_i, style_i)]
            attr_count += 1
            added = True
        head = bytearray(chunk[:base])
        struct.pack_into("<HHHHHH", head, body + 8, attr_start, attr_size, attr_count, id_i, class_i, style_i)
        packed = b"".join(struct.pack("<IIIHBBI", *a) for a in attrs)
        chunk = head + packed
        struct.pack_into("<I", chunk, 4, len(chunk))
    out_nodes += chunk
    pos += ccsz
assert added, f"element {element} not found"

# ---- rebuild string pool ----
blob = bytearray()
new_offsets = []
for s in strings:
    new_offsets.append(len(blob))
    blob += encode(s)
while len(blob) % 4:
    blob += b"\0"
pool_hsz = hsz
new_str_start = pool_hsz + 4 * len(strings)
pool = bytearray(struct.pack("<HHIIIIII", 0x0001, pool_hsz, new_str_start + len(blob),
                             len(strings), 0, flags, new_str_start, 0))
pool += bytes(pool_hsz - len(pool))
pool += struct.pack(f"<{len(strings)}I", *new_offsets) + blob

resmap = struct.pack("<HHI", 0x0180, rm_hsz, rm_hsz + 4 * len(res_ids)) + struct.pack(f"<{len(res_ids)}I", *res_ids)

body_all = pool + resmap + out_nodes
out = bytearray(data[:xml_header])
struct.pack_into("<I", out, 4, xml_header + len(body_all))
out += body_all
open(dst, "wb").write(out)
print(f"added android:{attr_name}=@0x{value_res:08x} to <{element}>")
