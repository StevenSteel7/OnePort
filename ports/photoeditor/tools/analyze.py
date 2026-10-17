"""Find classes/methods/fields referenced by the app's smali that exist neither in the app nor in the device framework.
usage: python3 analyze.py fw_dexdump.txt smali_root out.json
"""
import json, os, re, sys
from collections import defaultdict

fw_dump, smali_root, out = sys.argv[1:4]

classes = {}  # desc -> {'super':, 'ifaces':[], 'methods':set('name(proto)'), 'fields':set(name)}

def new(desc):
    return classes.setdefault(desc, {'super': None, 'ifaces': [], 'methods': set(), 'fields': set(), 'src': None})

# ---- framework (dexdump text) ----
cur = None; mode = None; pend_name = None
with open(fw_dump, errors='replace') as f:
    for line in f:
        s = line.strip()
        if s.startswith('Class descriptor'):
            cur = new(s.split("'")[1]); cur['src'] = 'fw'; mode = None; continue
        if cur is None: continue
        if s.startswith('Superclass'):
            cur['super'] = s.split("'")[1]
        elif s.startswith('Interfaces'):
            mode = 'ifaces'
        elif s.startswith('Static fields') or s.startswith('Instance fields'):
            mode = 'fields'
        elif s.startswith('Direct methods') or s.startswith('Virtual methods'):
            mode = 'methods'
        elif s.startswith('#') and mode == 'ifaces' and "'" in s:
            cur['ifaces'].append(s.split("'")[1])
        elif s.startswith('name') and "'" in s:
            pend_name = s.split("'")[1]
            if mode == 'fields': cur['fields'].add(pend_name)
        elif s.startswith('type') and "'" in s and mode == 'methods' and pend_name:
            cur['methods'].add(pend_name + s.split("'")[1]); pend_name = None

# ---- app smali ----
ref_re = re.compile(r'(L[\w/$\-]+;)->([\w$<>\-]+)(\([^)]*\)\S+|:\S+)')
type_re = re.compile(r'\b(?:new-instance|check-cast|const-class|instance-of|filled-new-array|new-array)\b.*?(\[*L[\w/$\-]+;)')
refs = []  # (file, kind, cls, member)
smali_files = []
for d in sorted(os.listdir(smali_root)):
    if d.startswith('smali'):
        for r, _, fs in os.walk(os.path.join(smali_root, d)):
            smali_files += [os.path.join(r, x) for x in fs if x.endswith('.smali')]
for p in smali_files:
    c = None
    with open(p, errors='replace') as f:
        for line in f:
            s = line.strip()
            if s.startswith('.class'):
                c = new(s.split()[-1]); c['src'] = p
            elif s.startswith('.super'):
                c['super'] = s.split()[-1]
            elif s.startswith('.implements'):
                c['ifaces'].append(s.split()[-1])
            elif s.startswith('.method'):
                c['methods'].add(s.split()[-1])
            elif s.startswith('.field'):
                c['fields'].add(re.search(r'([\w$\-]+):', s).group(1))
            elif s and not s.startswith('.') and not s.startswith('#'):
                for m in ref_re.finditer(s):
                    refs.append((p, 'm' if m.group(3).startswith('(') else 'f', m.group(1), m.group(2) + (m.group(3) if m.group(3).startswith('(') else '')))
                m = type_re.search(s)
                if m: refs.append((p, 't', m.group(1).lstrip('['), None))

def has_member(desc, kind, mem, seen=None):
    seen = seen or set()
    if desc in seen or desc not in classes: return False
    seen.add(desc)
    c = classes[desc]
    if (mem in c['methods']) if kind == 'm' else (mem in c['fields']): return True
    for p in [c['super']] + c['ifaces']:
        if p and has_member(p, kind, mem, seen): return True
    return False

def missing_ancestor(desc, seen=None):
    seen = seen or set()
    if desc in seen: return None
    seen.add(desc)
    if desc not in classes: return desc
    c = classes[desc]
    for p in [c['super']] + c['ifaces']:
        if p:
            r = missing_ancestor(p, seen)
            if r: return r
    return None

missing_classes = defaultdict(set)   # class -> files
missing_members = defaultdict(set)   # (class, member) -> files
for p, kind, cls, mem in refs:
    if cls.startswith('['): continue
    if cls not in classes:
        missing_classes[cls].add(p)
    elif kind != 't' and not has_member(cls, kind, mem):
        if missing_ancestor(cls):
            missing_classes[missing_ancestor(cls)].add(p)
        else:
            missing_members[(cls, mem)].add(p)
# app classes whose ancestry is missing
for desc, c in classes.items():
    if c['src'] and c['src'] != 'fw':
        a = missing_ancestor(desc)
        if a: missing_classes[a].add(c['src'])

json.dump({
    'missing_classes': {k: len(v) for k, v in sorted(missing_classes.items())},
    'missing_members': {f'{k[0]}->{k[1]}': len(v) for k, v in sorted(missing_members.items())},
}, open(out, 'w'), indent=1)
print('missing classes:', len(missing_classes), ' missing members on existing classes:', len(missing_members))
