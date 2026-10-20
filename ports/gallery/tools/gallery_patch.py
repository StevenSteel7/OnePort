"""Gallery-specific smali edits, applied after the generic patch.py.
usage: python3 gallery_patch.py apktool_dir
"""
import os, re, sys

root = sys.argv[1]

def find(cls):
    rel = cls[1:-1] + '.smali'
    for d in sorted(os.listdir(root)):
        p = os.path.join(root, d, rel)
        if d.startswith('smali') and os.path.exists(p): return p
    raise SystemExit(f'not found: {cls}')

def replace_method(cls, sig, body):
    p = find(cls)
    src = open(p).read()
    pat = re.compile(r'(\.method [^\n]*' + re.escape(sig) + r'\n).*?(\.end method)', re.S)
    src, n = pat.subn(lambda m: m.group(1) + '\n'.join('    ' + b for b in body) + '\n' + m.group(2), src, count=1)
    if n != 1: raise SystemExit(f'method not found: {cls}->{sig}')
    open(p, 'w').write(src)
    print('patched', cls, sig)

URI_PARSE = 'invoke-static {p0}, Landroid/net/Uri;->parse(Ljava/lang/String;)Landroid/net/Uri;'

# Gallery's DAL sends raw SQL to Samsung's MediaProvider; on non-Samsung (GED) builds this URI is "unsupported"
# and throws. Point it at samsungcompat.SecMedia, which emulates the provider in-process.
replace_method('Lcom/samsung/android/gallery/support/providers/MediaUriGed;', 'getRawQueryUri(Ljava/lang/String;)Landroid/net/Uri;',
               ['.locals 0', 'const-string p0, "content://secmedia/raw_sql"', URI_PARSE, 'move-result-object p0', 'return-object p0'])

# Recycle bin: SUPPORT_TRASH requires the Samsung platform (SEM >= P). Without Samsung's "One Trash" Gallery uses its
# self-contained TrashLocalProvider (own DB + files moved to Android/.Trash), which works on stock Android.
replace_method('Lcom/samsung/android/gallery/support/utils/Features$149;', 'getEnabling()Z',
               ['.locals 1', 'const/4 v0, 0x1', 'return v0'])

# On non-Samsung phones SeApiCompat uses GedApiCompatImpl, whose isActivityResumed() is hard-coded false -> the viewer's
# Edit button logs "Edit Menu Select failed: paused" and never starts the Photo Editor. Use androidx Lifecycle instead.
replace_method('Lcom/samsung/android/gallery/support/library/v0/GedApiCompatImpl;', 'isActivityResumed(Landroid/app/Activity;)Z', [
    '.locals 2',
    'instance-of v0, p1, Landroidx/lifecycle/LifecycleOwner;', 'if-eqz v0, :plain',
    'move-object v0, p1', 'check-cast v0, Landroidx/lifecycle/LifecycleOwner;',
    'invoke-interface {v0}, Landroidx/lifecycle/LifecycleOwner;->getLifecycle()Landroidx/lifecycle/Lifecycle;', 'move-result-object v0',
    'invoke-virtual {v0}, Landroidx/lifecycle/Lifecycle;->getCurrentState()Landroidx/lifecycle/Lifecycle$State;', 'move-result-object v0',
    'sget-object v1, Landroidx/lifecycle/Lifecycle$State;->RESUMED:Landroidx/lifecycle/Lifecycle$State;',
    'invoke-virtual {v0, v1}, Landroidx/lifecycle/Lifecycle$State;->isAtLeast(Landroidx/lifecycle/Lifecycle$State;)Z', 'move-result v0', 'return v0',
    ':plain', 'invoke-virtual {p1}, Landroid/app/Activity;->isFinishing()Z', 'move-result v0', 'xor-int/lit8 v0, v0, 0x1', 'return v0'])

# "Set as wallpaper" targets Samsung's Wallpaper and style app
# (com.samsung.android.app.dressroom), absent on other phones: the command
# failed with "No Activity found". Route SetWallpaperCmd's activity starts
# through samsungcompat.WallpaperCompat, which falls back to the standard
# WallpaperManager crop-and-set flow.
p = find('Lcom/samsung/android/gallery/app/controller/externals/SetWallpaperCmd;')
src = open(p).read()
src, n1 = re.subn(r'invoke-virtual \{([pv]\d+), ([pv]\d+)\}, Landroid/app/Activity;->startActivity\(Landroid/content/Intent;\)V',
                  r'invoke-static {\1, \2}, Lsamsungcompat/WallpaperCompat;->start(Landroid/app/Activity;Landroid/content/Intent;)V', src)
src, n2 = re.subn(r'invoke-virtual \{([pv]\d+), ([pv]\d+), ([pv]\d+)\}, Landroid/app/Activity;->startActivityForResult\(Landroid/content/Intent;I\)V',
                  r'invoke-static {\1, \2, \3}, Lsamsungcompat/WallpaperCompat;->startForResult(Landroid/app/Activity;Landroid/content/Intent;I)V', src)
if n1 != 4 or n2 != 3: raise SystemExit(f'SetWallpaperCmd starts: {n1} {n2}')
open(p, 'w').write(src)
print('routed', n1 + n2, 'SetWallpaperCmd starts through WallpaperCompat')
