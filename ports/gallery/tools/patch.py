"""Make a Samsung app run on non-Samsung Android.
 - generate smali stubs for missing Samsung framework classes
 - redirect missing members on existing framework classes to a static shim
usage: python3 patch.py fw_dexdump.txt apktool_dir
"""
import os, re, sys
from collections import defaultdict

fw_dump, root = sys.argv[1:3]
SHIM = 'Lsamsungcompat/Shim;'
STUB_PREFIXES = ('Lcom/samsung/', 'Landroid/')   # only stub vendor framework classes

classes = {}
def new(desc):
    return classes.setdefault(desc, {'super': None, 'ifaces': [], 'methods': set(), 'fields': set(), 'src': None})

cur = None; mode = None; pend = None
with open(fw_dump, errors='replace') as f:
    for line in f:
        s = line.strip()
        if s.startswith('Class descriptor'):
            cur = new(s.split("'")[1]); cur['src'] = 'fw'; mode = None; continue
        if cur is None: continue
        if s.startswith('Superclass'): cur['super'] = s.split("'")[1]
        elif s.startswith('Interfaces'): mode = 'ifaces'
        elif s.startswith(('Static fields', 'Instance fields')): mode = 'fields'
        elif s.startswith(('Direct methods', 'Virtual methods')): mode = 'methods'
        elif s.startswith('#') and mode == 'ifaces' and "'" in s: cur['ifaces'].append(s.split("'")[1])
        elif s.startswith('name') and "'" in s:
            pend = s.split("'")[1]
            if mode == 'fields': cur['fields'].add(pend)
        elif s.startswith('type') and "'" in s and mode == 'methods' and pend:
            cur['methods'].add(pend + s.split("'")[1]); pend = None

smali_dirs = sorted(d for d in os.listdir(root) if re.fullmatch(r'smali(_classes\d+)?', d))
files = []
for d in smali_dirs:
    for r, _, fs in os.walk(os.path.join(root, d)):
        files += [os.path.join(r, x) for x in fs if x.endswith('.smali')]

for p in files:
    c = None
    with open(p, errors='replace') as f:
        for line in f:
            s = line.strip()
            if s.startswith('.class'): c = new(s.split()[-1]); c['src'] = p
            elif s.startswith('.super'): c['super'] = s.split()[-1]
            elif s.startswith('.implements'): c['ifaces'].append(s.split()[-1])
            elif s.startswith('.method'): c['methods'].add(s.split()[-1])
            elif s.startswith('.field'): c['fields'].add(re.search(r'([\w$\-]+):', s).group(1))

def has_member(desc, kind, mem, seen=None):
    seen = seen if seen is not None else set()
    if desc in seen or desc not in classes: return False
    seen.add(desc)
    c = classes[desc]
    if mem in (c['methods'] if kind == 'm' else c['fields']): return True
    return any(p and has_member(p, kind, mem, seen) for p in [c['super']] + c['ifaces'])

INV = re.compile(r'^(\s*)(invoke-(virtual|super|direct|static|interface)(/range)?) (\{[^}]*\}), (L[\w/$\-]+;)->([\w$<>\-]+)\(([^)]*)\)(\S+)\s*$')
FLD = re.compile(r'^(\s*)([is])(get|put)(-[a-z]+)? (.*?)(L[\w/$\-]+;)->([\w$\-]+):(\S+)\s*$')
TYPE = re.compile(r'(\[*)(L[\w/$\-]+;)')

def is_stub_target(desc):
    return desc not in classes and desc.startswith(STUB_PREFIXES)

# ---- pass 1: collect what stubs + shim need ----
stub = defaultdict(lambda: {'methods': {}, 'fields': {}, 'iface': False})   # desc -> ...
shim_methods = {}   # key (cls,name,proto,ret,static) -> shim name
shim_fields = {}    # (cls,name,type) -> shim field name
for p in files:
    with open(p, errors='replace') as f:
        text = f.read()
    for desc in set(TYPE.findall(text)):
        d = desc[1]
        if is_stub_target(d): stub[d]
    for line in text.splitlines():
        m = INV.match(line)
        if m:
            kind, cls, name, proto, ret = m.group(3), m.group(6), m.group(7), m.group(8), m.group(9)
            if is_stub_target(cls):
                st = stub[cls]
                if kind == 'interface': st['iface'] = True
                st['methods'][(name, proto, ret)] = 'static' if kind == 'static' else ('ctor' if name == '<init>' else 'virtual')
            elif cls in classes and not has_member(cls, 'm', f'{name}({proto}){ret}') and name != '<init>':
                k = (cls, name, proto, ret, kind == 'static')
                shim_methods.setdefault(k, f'm{len(shim_methods)}_{name}')
            continue
        m = FLD.match(line)
        if m:
            static, cls, name, typ = m.group(2) == 's', m.group(6), m.group(7), m.group(8)
            if is_stub_target(cls):
                stub[cls]['fields'][(name, typ)] = static
            elif cls in classes and not has_member(cls, 'f', name):
                shim_fields.setdefault((cls, name, typ), f'f{len(shim_fields)}_{name}')
for p in files:   # interfaces implemented by app classes
    with open(p, errors='replace') as f:
        for line in f:
            if line.startswith('.implements') and is_stub_target(line.split()[-1]):
                stub[line.split()[-1]]['iface'] = True

# ---- smali code generation helpers ----
def split_params(proto):
    return re.findall(r'\[*(?:L[^;]+;|[ZBSCIJFD])', proto)

def wide(t): return t in ('J', 'D')

def default_return(ret, params, first_preg, receiver_type=None):
    """params: list of (type, preg). A trailing argument of the return type (2+ args) is treated as the default value."""
    dflt = params[-1:] if len(params) >= 2 else []
    if ret == 'V': return ['return-void']
    if ret in 'ZBSCIF' and len(ret) == 1:
        for t, r in dflt:
            if t == ret: return [f'return {r}']   # "default value" argument
        return ['const/4 v0, 0x0', 'return v0']
    if wide(ret):
        for t, r in dflt:
            if t == ret: return [f'return-wide {r}']
        return ['const-wide/16 v0, 0x0', 'return-wide v0']
    for t, r in dflt:
        if t == ret: return [f'return-object {r}']
    if receiver_type and ret == receiver_type: return [f'return-object {first_preg}']
    empty = {'Ljava/util/List;': 'Ljava/util/ArrayList;', 'Ljava/util/ArrayList;': 'Ljava/util/ArrayList;', 'Ljava/util/Collection;': 'Ljava/util/ArrayList;',
             'Ljava/util/Map;': 'Ljava/util/HashMap;', 'Ljava/util/HashMap;': 'Ljava/util/HashMap;',
             'Ljava/util/Set;': 'Ljava/util/HashSet;', 'Ljava/util/HashSet;': 'Ljava/util/HashSet;', 'Landroid/os/Bundle;': 'Landroid/os/Bundle;'}
    if ret in empty:
        return [f'new-instance v0, {empty[ret]}', f'invoke-direct {{v0}}, {empty[ret]}-><init>()V', 'return-object v0']
    if ret == 'Ljava/lang/String;':
        return ['const-string v0, ""', 'return-object v0']
    if ret in stub and not stub[ret]['iface']:
        return [f'new-instance v0, {ret}', f'invoke-direct {{v0}}, {ret}-><init>()V', 'return-object v0']
    return ['const/4 v0, 0x0', 'return-object v0']

def pregs(types, start=0):
    out = []; i = start
    for t in types:
        out.append((t, f'p{i}')); i += 2 if wide(t) else 1
    return out

def write(path, lines):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w') as f: f.write('\n'.join(lines) + '\n')

OUT = os.path.join(root, f'smali_classes{len(smali_dirs) + 1}')

# ---- emit stubs ----
for desc, st in sorted(stub.items()):
    iface = st['iface']
    L = [f".class public {'abstract interface ' if iface else ''}{desc}", '.super Ljava/lang/Object;', '']
    for (name, typ), static in sorted(st['fields'].items()):
        L.append(f".field public {'static ' if static or iface else ''}{name}:{typ}")
    L.append('')
    if not iface:
        ctors = {p for (n, p, r), k in st['methods'].items() if k == 'ctor'} | {''}
        for proto in sorted(ctors):
            L += [f'.method public constructor <init>({proto})V', '    .locals 0',
                  '    invoke-direct {p0}, Ljava/lang/Object;-><init>()V', '    return-void', '.end method', '']
    for (name, proto, ret), k in sorted(st['methods'].items()):
        if k == 'ctor': continue
        static = k == 'static'
        if iface and not static:
            L += [f'.method public abstract {name}({proto}){ret}', '.end method', '']
            continue
        ps = pregs(split_params(proto), 0 if static else 1)
        body = default_return(ret, ps, 'p0', None if static else desc)
        L += [f".method public {'static ' if static else ''}{name}({proto}){ret}", '    .locals 2'] + ['    ' + b for b in body] + ['.end method', '']
    write(os.path.join(OUT, desc[1:-1] + '.smali'), L)

# ---- emit shim ----
# hand-written real implementations for the commonly used Samsung extensions
SPECIAL = {
    ('Landroid/os/UserHandle;', 'semGetMyUserId'): ['invoke-static {}, Landroid/os/Process;->myUid()I', 'move-result v0', 'const v1, 0x186a0', 'div-int/2addr v0, v1', 'return v0'],
    ('Landroid/os/UserHandle;', 'semGetIdentifier'): ['invoke-virtual {p0}, Ljava/lang/Object;->hashCode()I', 'move-result v0', 'return v0'],
    ('Landroid/os/UserHandle;', 'semOf'): ['const v0, 0x186a0', 'mul-int/2addr v0, p0', 'invoke-static {v0}, Landroid/os/UserHandle;->getUserHandleForUid(I)Landroid/os/UserHandle;', 'move-result-object v0', 'return-object v0'],
    ('Landroid/provider/Settings$System;', 'semGetIntForUser'): ['invoke-static {p0, p1, p2}, Landroid/provider/Settings$System;->getInt(Landroid/content/ContentResolver;Ljava/lang/String;I)I', 'move-result v0', 'return v0'],
    ('Landroid/content/Context;', 'semCreatePackageContextAsUser'): ['invoke-virtual {p0, p1, p2}, Landroid/content/Context;->createPackageContext(Ljava/lang/String;I)Landroid/content/Context;', 'move-result-object v0', 'return-object v0'],
    ('Landroid/app/Activity;', 'semOverridePendingTransition'): ['invoke-virtual {p0, p1, p2}, Landroid/app/Activity;->overridePendingTransition(II)V', 'return-void'],
    ('Landroid/view/ViewGroup$MarginLayoutParams;', 'semSetMarginsRelative'): [
        'invoke-virtual {p0, p1}, Landroid/view/ViewGroup$MarginLayoutParams;->setMarginStart(I)V',
        'iput p2, p0, Landroid/view/ViewGroup$MarginLayoutParams;->topMargin:I',
        'invoke-virtual {p0, p3}, Landroid/view/ViewGroup$MarginLayoutParams;->setMarginEnd(I)V',
        'iput p4, p0, Landroid/view/ViewGroup$MarginLayoutParams;->bottomMargin:I', 'return-void'],
    # "is this activity resumed" via androidx Lifecycle; plain activities: not finishing
    ('Landroid/app/Activity;', 'semIsResumed'): [
        'instance-of v0, p0, Landroidx/lifecycle/LifecycleOwner;', 'if-eqz v0, :plain',
        'move-object v0, p0', 'check-cast v0, Landroidx/lifecycle/LifecycleOwner;',
        'invoke-interface {v0}, Landroidx/lifecycle/LifecycleOwner;->getLifecycle()Landroidx/lifecycle/Lifecycle;', 'move-result-object v0',
        'invoke-virtual {v0}, Landroidx/lifecycle/Lifecycle;->getCurrentState()Landroidx/lifecycle/Lifecycle$State;', 'move-result-object v0',
        'sget-object v1, Landroidx/lifecycle/Lifecycle$State;->RESUMED:Landroidx/lifecycle/Lifecycle$State;',
        'invoke-virtual {v0, v1}, Landroidx/lifecycle/Lifecycle$State;->isAtLeast(Landroidx/lifecycle/Lifecycle$State;)Z', 'move-result v0', 'return v0',
        ':plain', 'invoke-virtual {p0}, Landroid/app/Activity;->isFinishing()Z', 'move-result v0', 'xor-int/lit8 v0, v0, 0x1', 'return v0'],
    ('Landroid/content/pm/PackageManager;', 'semGetApplicationIconForIconTray'): ['invoke-virtual {p0, p1}, Landroid/content/pm/PackageManager;->getApplicationIcon(Landroid/content/pm/ApplicationInfo;)Landroid/graphics/drawable/Drawable;', 'move-result-object v0', 'return-object v0'],
}
FIELD_INIT = {
    ('Landroid/os/UserHandle;', 'SEM_OWNER'): ['const/4 v0, 0x0', 'invoke-static {v0}, Landroid/os/UserHandle;->getUserHandleForUid(I)Landroid/os/UserHandle;', 'move-result-object v0'],
    ('Landroid/os/UserHandle;', 'SEM_ALL'): ['const v0, -0x186a0', 'invoke-static {v0}, Landroid/os/UserHandle;->getUserHandleForUid(I)Landroid/os/UserHandle;', 'move-result-object v0'],
}
L = [f'.class public final {SHIM}', '.super Ljava/lang/Object;', '']
for (cls, name, typ), fn in shim_fields.items():
    L.append(f'.field public static {fn}:{typ}')
L += ['', '.method static constructor <clinit>()V', '    .locals 2']
for (cls, name, typ), fn in shim_fields.items():
    if (cls, name) in FIELD_INIT:
        L += ['    ' + x for x in FIELD_INIT[(cls, name)]] + [f'    sput-object v0, {SHIM}->{fn}:{typ}']
L += ['    return-void', '.end method', '']
for (cls, name, proto, ret, static), sn in shim_methods.items():
    types = split_params(proto) if static else [cls] + split_params(proto)
    ps = pregs(types)
    body = SPECIAL.get((cls, name)) or default_return(ret, ps[0 if static else 1:], 'p0', None if static else cls)
    L += [f".method public static {sn}({''.join(types)}){ret}", '    .locals 2'] + ['    ' + b for b in body] + ['.end method', '']
# Every ContentResolver data call goes through samsungcompat.CR (compat_src/, compiled into its own dex):
# it serves Samsung's "sectrash" provider from disk and turns "Unknown authority" from other Samsung-only
# providers into empty results instead of an exception that kills the worker thread.
CR = 'Landroid/content/ContentResolver;'
CR_SHIM = 'Lsamsungcompat/CR;'
CR_WRAP = {
    ('query', 'Landroid/net/Uri;[Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;', 'Landroid/database/Cursor;'),
    ('query', 'Landroid/net/Uri;[Ljava/lang/String;Landroid/os/Bundle;Landroid/os/CancellationSignal;', 'Landroid/database/Cursor;'),
    ('query', 'Landroid/net/Uri;[Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;Landroid/os/CancellationSignal;', 'Landroid/database/Cursor;'),
    ('call', 'Landroid/net/Uri;Ljava/lang/String;Ljava/lang/String;Landroid/os/Bundle;', 'Landroid/os/Bundle;'),
    ('call', 'Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Landroid/os/Bundle;', 'Landroid/os/Bundle;'),
    ('applyBatch', 'Ljava/lang/String;Ljava/util/ArrayList;', '[Landroid/content/ContentProviderResult;'),
    ('bulkInsert', 'Landroid/net/Uri;[Landroid/content/ContentValues;', 'I'),
    ('delete', 'Landroid/net/Uri;Ljava/lang/String;[Ljava/lang/String;', 'I'),
    ('insert', 'Landroid/net/Uri;Landroid/content/ContentValues;', 'Landroid/net/Uri;'),
    ('update', 'Landroid/net/Uri;Landroid/content/ContentValues;Ljava/lang/String;[Ljava/lang/String;', 'I'),
    ('registerContentObserver', 'Landroid/net/Uri;ZLandroid/database/ContentObserver;', 'V'),
    ('unregisterContentObserver', 'Landroid/database/ContentObserver;', 'V'),
}
write(os.path.join(OUT, SHIM[1:-1] + '.smali'), L)

# ---- pass 2: rewrite call sites ----
FOP = {'get': 'sget', 'put': 'sput'}
changed = 0
for p in files:
    with open(p, errors='replace') as f:
        lines = f.read().split('\n')
    dirty = False
    for i, line in enumerate(lines):
        m = INV.match(line)
        if m:
            ind, kind, rng, regs, cls, name, proto, ret = m.group(1), m.group(3), m.group(4) or '', m.group(5), m.group(6), m.group(7), m.group(8), m.group(9)
            k = (cls, name, proto, ret, kind == 'static')
            if cls == CR and kind == 'virtual' and (name, proto, ret) in CR_WRAP:
                lines[i] = f'{ind}invoke-static{rng} {regs}, {CR_SHIM}->{name}({CR}{proto}){ret}'
                dirty = True
            elif k in shim_methods:
                params = proto if kind == 'static' else cls + proto
                lines[i] = f'{ind}invoke-static{rng} {regs}, {SHIM}->{shim_methods[k]}({params}){ret}'
                dirty = True
            continue
        if 'COLLATE UNICODE_NATURAL' in line:   # Samsung-only SQLite collation
            lines[i] = line.replace('COLLATE UNICODE_NATURAL', 'COLLATE NOCASE')
            dirty = True
            continue
        m = FLD.match(line)
        if m:
            ind, si, op, suf, regs, cls, name, typ = m.groups()
            k = (cls, name, typ)
            if k in shim_fields:
                dst = regs.split(',')[0].strip()
                if op == 'put' and si == 'i':
                    lines[i] = f'{ind}nop'
                else:
                    lines[i] = f'{ind}{FOP[op]}{suf or ""} {dst}, {SHIM}->{shim_fields[k]}:{typ}'
                dirty = True
    # getSystemService("<samsung service>") returns null here: hand back a stub instance instead
    i = 0
    while i < len(lines):
        if 'getSystemService(Ljava/lang/String;)Ljava/lang/Object;' in lines[i]:
            j = i + 1
            while j < len(lines) and not lines[j].strip(): j += 1
            mm = re.match(r'\s*move-result-object (\w+)', lines[j]) if j < len(lines) else None
            if mm:
                reg = mm.group(1)
                for k in range(j + 1, min(j + 15, len(lines))):
                    cm = re.match(rf'\s*check-cast {reg}, (L[\w/$\-]+;)', lines[k])
                    if cm and cm.group(1) in stub and not stub[cm.group(1)]['iface']:
                        lbl = f':sc_svc_{i}'
                        lines[j + 1:j + 1] = [f'    if-nez {reg}, {lbl}', f'    new-instance {reg}, {cm.group(1)}',
                                              f'    invoke-direct/range {{{reg} .. {reg}}}, {cm.group(1)}-><init>()V', f'    {lbl}']
                        dirty = True
                        break
        i += 1
    if dirty:
        changed += 1
        with open(p, 'w') as f: f.write('\n'.join(lines))

print(f'stub classes: {len(stub)}  shim methods: {len(shim_methods)}  shim fields: {len(shim_fields)}  files patched: {changed}  -> {OUT}')
