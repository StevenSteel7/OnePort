"""Route calls to platform APIs newer than Android 14 through samsungcompat.ApiCompat (compat_src/).

My Files is built for Android 16. These are every framework member it uses that Android 14/15 lack
(found by tools/analyze.py against Android 14 and 15 framework dumps). Each call becomes a static call
that uses the real API when the device has it and an equivalent otherwise.
usage: python3 api_backport.py apktool_dir
"""
import os
import re
import sys

root = sys.argv[1]
COMPAT = 'Lsamsungcompat/ApiCompat;'

# owner, name, parameter types, return type
APIS = [
    ('Ljava/util/ArrayList;', 'addFirst', 'Ljava/lang/Object;', 'V'),
    ('Ljava/util/ArrayList;', 'removeLast', '', 'Ljava/lang/Object;'),
    ('Ljava/util/SortedSet;', 'getFirst', '', 'Ljava/lang/Object;'),
    ('Ljava/lang/Thread;', 'threadId', '', 'J'),
    ('Landroid/view/View;', 'setFrameContentVelocity', 'F', 'V'),
    ('Landroid/view/View;', 'setRequestedFrameRate', 'F', 'V'),
    ('Landroid/view/accessibility/AccessibilityManager;', 'addHighContrastTextStateChangeListener',
     'Ljava/util/concurrent/Executor;Landroid/view/accessibility/AccessibilityManager$HighContrastTextStateChangeListener;', 'V'),
    ('Landroid/view/accessibility/AccessibilityManager;', 'removeHighContrastTextStateChangeListener',
     'Landroid/view/accessibility/AccessibilityManager$HighContrastTextStateChangeListener;', 'V'),
    ('Landroid/view/accessibility/AccessibilityNodeInfo;', 'getChecked', '', 'I'),
    ('Landroid/view/accessibility/AccessibilityNodeInfo;', 'getExpandedState', '', 'I'),
    ('Landroid/view/accessibility/AccessibilityNodeInfo;', 'getSupplementalDescription', '', 'Ljava/lang/CharSequence;'),
    ('Landroid/view/accessibility/AccessibilityNodeInfo;', 'isFieldRequired', '', 'Z'),
    ('Landroid/app/Notification$Builder;', 'setShortCriticalText', 'Ljava/lang/String;', 'Landroid/app/Notification$Builder;'),
    ('Landroid/content/pm/PackageManager;', 'isAppArchivable', 'Ljava/lang/String;', 'Z'),
    ('Landroid/content/pm/PackageInstaller;', 'requestArchive', 'Ljava/lang/String;Landroid/content/IntentSender;', 'V'),
    ('Landroid/media/MediaCodec;', 'detachOutputSurface', '', 'V'),
    ('Landroid/graphics/pdf/PdfRenderer$Page;', 'getTextContents', '', 'Ljava/util/List;'),
]

patterns = []
for owner, name, params, ret in APIS:
    ref = re.escape(f'{owner}->{name}({params}){ret}')
    pat = re.compile(r'invoke-(?:virtual|interface)(/range)? (\{[^}]*\}), ' + ref)
    repl = lambda m, o=owner, n=name, p=params, r=ret: (
        f'invoke-static{m.group(1) or ""} {m.group(2)}, {COMPAT}->{n}({o}{p}){r}')
    patterns.append((f'{owner}->{name}', pat, repl))

counts = {key: 0 for key, _, _ in patterns}
for d in sorted(os.listdir(root)):
    if not d.startswith('smali'):
        continue
    for base, _, files in os.walk(os.path.join(root, d)):
        for name in files:
            if not name.endswith('.smali'):
                continue
            path = os.path.join(base, name)
            src = open(path, encoding='utf-8').read()
            new = src
            for key, pat, repl in patterns:
                new, n = pat.subn(repl, new)
                counts[key] += n
            if new != src:
                open(path, 'w', encoding='utf-8').write(new)

missing = [k for k, n in counts.items() if n == 0]
if missing:
    raise SystemExit(f'api_backport: no call sites for {missing} (did the app version change?)')
print('api_backport: rerouted', sum(counts.values()), 'calls to', len(counts), 'APIs newer than Android 14')


# ---- version checks R8 removed ----
# Samsung's build had minSdk 36, so R8 deleted "SDK_INT >= 35/36" checks around classes those releases
# added. Restore them where those classes would otherwise load on Android 14/15.
def find(cls):
    rel = cls[1:-1] + '.smali'
    for d in sorted(os.listdir(root)):
        p = os.path.join(root, d, rel)
        if d.startswith('smali') and os.path.exists(p):
            return p
    raise SystemExit(f'api_backport: class not found: {cls}')

def replace_once(cls, old, new):
    p = find(cls)
    src = open(p, encoding='utf-8').read()
    if src.count(old) != 1:
        raise SystemExit(f'api_backport: expected text not found once in {cls}: {old.strip()[:70]}')
    open(p, 'w', encoding='utf-8').write(src.replace(old, new))

SDK = '    sget {r}, Landroid/os/Build$VERSION;->SDK_INT:I\n\n'

# ScrollFeedbackProvider (API 35): RecyclerView/NestedScrollView create it on every scroll -> crash on 14.
replace_once('Li9/i;',
    '    invoke-static {p1}, Landroid/view/ScrollFeedbackProvider;->createProvider(Landroid/view/View;)Landroid/view/ScrollFeedbackProvider;\n\n'
    '    move-result-object p1\n',
    # the constructor has a single local register: compare via SDK_INT - 35 >= 0
    SDK.format(r='v0') +
    '    add-int/lit8 v0, v0, -0x23\n\n'
    '    if-gez v0, :sfp_ok\n\n'
    '    const/4 p1, 0x0\n\n'
    '    goto :sfp_set\n\n'
    '    :sfp_ok\n'
    '    invoke-static {p1}, Landroid/view/ScrollFeedbackProvider;->createProvider(Landroid/view/View;)Landroid/view/ScrollFeedbackProvider;\n\n'
    '    move-result-object p1\n\n'
    '    :sfp_set\n')
for method in ('b(IIIZ)V', 'c(IIII)V'):
    replace_once('Lp1/t;',
        f'.method public final {method}\n    .locals 0\n\n    iget-object p0, p0, Lp1/t;->a:Li9/i;\n\n    iget-object p0, p0, Li9/i;->e:Ljava/lang/Object;\n',
        f'.method public final {method}\n    .locals 0\n\n    iget-object p0, p0, Lp1/t;->a:Li9/i;\n\n    iget-object p0, p0, Li9/i;->e:Ljava/lang/Object;\n\n'
        '    if-nez p0, :sfp_has\n\n    return-void\n\n    :sfp_has\n')

# LoudnessCodecController (API 35): media3's audio renderer always creates its wrapper; leave it null below 35
# (media3's own behaviour; every use null-checks it).
replace_once('Lo3/j0;',
    '    iput-object v0, p0, Lo3/j0;->V0:Ldi/s0;\n',
    '    sget v1, Landroid/os/Build$VERSION;->SDK_INT:I\n\n'
    '    const/16 v2, 0x23\n\n'
    '    if-ge v1, v2, :loudness_ok\n\n'
    '    const/4 v0, 0x0\n\n'
    '    :loudness_ok\n'
    '    iput-object v0, p0, Lo3/j0;->V0:Ldi/s0;\n')

# High-contrast text listener (API 36) in the S Pen SDK: the support check was folded to "true".
replace_once('Lcom/samsung/android/sdk/pen/view/SpenConfiguration;',
    '.method private final isHCTChangeListenerSupported()Z\n    .locals 0\n\n    const/4 p0, 0x1\n\n    return p0\n',
    '.method private final isHCTChangeListenerSupported()Z\n    .locals 1\n\n'
    '    sget p0, Landroid/os/Build$VERSION;->SDK_INT:I\n\n'
    '    const/16 v0, 0x24\n\n'
    '    if-lt p0, v0, :hct_no\n\n    const/4 p0, 0x1\n\n    return p0\n\n    :hct_no\n    const/4 p0, 0x0\n\n    return p0\n')
print('api_backport: restored SDK checks for ScrollFeedbackProvider, LoudnessCodecController, HCT listener')
