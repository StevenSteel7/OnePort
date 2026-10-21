"""My Files-specific smali edits, applied after the generic patch.py.
usage: python3 myfiles_patch.py apktool_dir      (idempotent)
"""
import os, sys

root = sys.argv[1]

def find(cls):
    rel = cls[1:-1] + '.smali'
    for d in sorted(os.listdir(root)):
        p = os.path.join(root, d, rel)
        if d.startswith('smali') and os.path.exists(p): return p
    raise SystemExit(f'not found: {cls}')

# One Recycle bin with Gallery: LocalOperation.internalMove (A) renames items into My Files' trash folder;
# photos/videos go to Android's MediaStore trash instead (samsungcompat.MediaTrash).
p = find('Lsi/b;')
src = open(p).read()
old = '    invoke-virtual {v1, v3}, Lzm/c;->renameTo(Ljava/io/File;)Z\n'
new = '    invoke-static {v1, v3}, Lsamsungcompat/MediaTrash;->renameTo(Ljava/io/File;Ljava/io/File;)Z\n'
start = src.index('.method public final A(Lih/h;Lhh/a0;Z)V')
end = src.index('.end method', start)
method = src[start:end]
if new in method:
    print('already patched si/b A()')
else:
    if method.count(old) != 1: raise SystemExit('renameTo call not found in si/b A()')
    src = src[:start] + method.replace(old, new) + src[end:]
    open(p, 'w').write(src)
    print('patched si/b A() renameTo -> MediaTrash')
