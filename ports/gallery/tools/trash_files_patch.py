"""Redirect Gallery's path-based file opens to samsungcompat.TrashFiles (see its javadoc).
usage: python3 trash_files_patch.py apktool_dir      (idempotent: rewritten sites no longer match)
"""
import os, re, sys

TF = 'Lsamsungcompat/TrashFiles;'
# Static calls with an unchanged signature: only the owner class changes.
STATIC = [('Landroid/graphics/BitmapFactory;->decodeFile(', TF + '->decodeFile('),
          ('Landroid/os/ParcelFileDescriptor;->open(Ljava/io/File;I)', TF + '->open(Ljava/io/File;I)')]
# Instance calls become static calls taking the receiver first.
VIRTUAL = [('Landroid/media/MediaPlayer;->setDataSource(Ljava/lang/String;)V',
            TF + '->setDataSource(Landroid/media/MediaPlayer;Ljava/lang/String;)V'),
           ('Landroid/media/MediaMetadataRetriever;->setDataSource(Ljava/lang/String;)V',
            TF + '->setDataSource(Landroid/media/MediaMetadataRetriever;Ljava/lang/String;)V')]
# Constructors become factory calls: new-instance becomes nop (keeps try ranges non-empty), <init> becomes invoke-static + move-result-object.
CTORS = [('Ljava/io/FileInputStream;', '(Ljava/lang/String;)V', TF + '->fis(Ljava/lang/String;)Ljava/io/FileInputStream;'),
         ('Ljava/io/FileInputStream;', '(Ljava/io/File;)V', TF + '->fis(Ljava/io/File;)Ljava/io/FileInputStream;'),
         ('Landroid/media/ExifInterface;', '(Ljava/lang/String;)V', TF + '->exif(Ljava/lang/String;)Landroid/media/ExifInterface;')]

def regs(spec):
    if '..' in spec:
        a, b = [r.strip() for r in spec.split('..')]
        return [a[0] + str(n) for n in range(int(a[1:]), int(b[1:]) + 1)]
    return [r.strip() for r in spec.split(',') if r.strip()]

def rewrite_ctors(method, stats):
    for cls, sig, factory in CTORS:
        pat = re.compile(r'^(\s*)invoke-direct(?:/range)? \{([^}]*)\}, ' + re.escape(cls + '-><init>' + sig) + r'\n', re.M)
        pos = 0
        while True:
            m = pat.search(method, pos)
            if not m: break
            rs = regs(m.group(2))
            obj, arg = rs[0], rs[1]
            ni = None
            for n in re.finditer(r'^\s*new-instance ' + re.escape(obj) + ', ' + re.escape(cls) + r'\n', method[:m.start()], re.M):
                ni = n
            between = method[ni.end():m.start()] if ni else ''
            if not ni or re.search(r'[{ ,]' + re.escape(obj) + r'[,} \n]', between):
                stats['skipped'] += 1; pos = m.end(); continue
            repl = f'{m.group(1)}invoke-static {{{arg}}}, {factory}\n\n{m.group(1)}move-result-object {obj}\n'
            method = method[:ni.start()] + '\n    nop\n' + between + repl + method[m.end():]
            stats['ctor'] += 1
            pos = ni.start() + len('\n    nop\n') + len(between) + len(repl)
    return method

def patch_file(path, stats):
    src = open(path).read()
    if not any(k in src for k in ('FileInputStream;-><init>', 'ExifInterface;-><init>', 'BitmapFactory;->decodeFile(',
                                  'ParcelFileDescriptor;->open(Ljava/io/File;I)', ';->setDataSource(Ljava/lang/String;)V')):
        return
    out = src
    for old, new in STATIC:
        stats['static'] += out.count(old); out = out.replace(old, new)
    for old, new in VIRTUAL:
        out, n = re.subn(r'invoke-virtual(/range)? (\{[^}]*\}), ' + re.escape(old),
                         lambda m: f'invoke-static{m.group(1) or ""} {m.group(2)}, {new}', out)
        stats['virtual'] += n
    out = re.sub(r'(\.method [^\n]*\n)(.*?)(\.end method)', lambda m: m.group(1) + rewrite_ctors(m.group(2), stats) + m.group(3),
                 out, flags=re.S)
    if out != src: open(path, 'w').write(out)

root = sys.argv[1]
stats = {'static': 0, 'virtual': 0, 'ctor': 0, 'skipped': 0}
for d in sorted(os.listdir(root)):
    if not d.startswith('smali'): continue
    for dp, _, fs in os.walk(os.path.join(root, d)):
        for f in fs:
            if f.endswith('.smali'): patch_file(os.path.join(dp, f), stats)
print('trash_files_patch', stats)
