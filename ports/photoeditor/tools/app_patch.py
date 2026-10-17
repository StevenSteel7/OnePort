"""Photo Editor-specific smali edits, applied after the generic patch.py.
usage: python3 app_patch.py apktool_dir
"""
import os, re, sys

root = sys.argv[1]
SMALI_DIRS = [os.path.join(root, d) for d in sorted(os.listdir(root)) if d.startswith('smali')]

def find(cls):
    rel = cls[1:-1] + '.smali'
    for d in SMALI_DIRS:
        p = os.path.join(d, rel)
        if os.path.exists(p): return p
    raise SystemExit(f'not found: {cls}')

def replace_method(cls, sig, body):
    p = find(cls)
    src = open(p).read()
    pat = re.compile(r'(\.method [^\n]*' + re.escape(sig) + r'\n).*?(\.end method)', re.S)
    src, n = pat.subn(lambda m: m.group(1) + '\n'.join('    ' + b for b in body) + '\n' + m.group(2), src, count=1)
    if n != 1: raise SystemExit(f'method not found: {cls}->{sig}')
    open(p, 'w').write(src)
    print('patched', cls, sig)

def inject_at_start(cls, sig, lines):
    """Insert smali lines right after the .locals/.registers line of a method (p-registers unchanged)."""
    p = find(cls)
    src = open(p).read()
    pat = re.compile(r'(\.method [^\n]*' + re.escape(sig) + r'\n\s*\.(?:locals|registers) \d+\n)')
    src, n = pat.subn(lambda m: m.group(1) + '\n'.join('    ' + l for l in lines) + '\n', src, count=1)
    if n != 1: raise SystemExit(f'method not found: {cls}->{sig}')
    open(p, 'w').write(src)
    print('injected', cls, sig)

def rewrite_calls(pairs):
    """Replace exact invoke targets in every smali file; returns number of call sites changed."""
    total = 0
    for d in SMALI_DIRS:
        for dp, _, fs in os.walk(d):
            for f in fs:
                if not f.endswith('.smali'): continue
                p = os.path.join(dp, f)
                src = open(p).read()
                new = src
                for a, b in pairs: new = new.replace(a, b)
                if new != src:
                    total += sum(src.count(a) for a, _ in pairs)
                    open(p, 'w').write(new)
    return total

def _params(proto):
    return re.findall(r'\[*(?:L[^;]+;|[ZBSCIJFD])', proto)

def denative(cls):
    """Turn every native method of cls into a Java body returning a safe default (for libs that can't load here).
    Array returns pass through the first parameter of the same type (image in -> image out), else null."""
    p = find(cls)
    src = open(p).read()
    def body(m):
        mods, name, params, ret = m.group(1), m.group(2), m.group(3), m.group(4)
        mods = mods.replace(' native', '')
        ps = _params(params)
        reg, regs = (0 if ' static' in mods else 1), []
        for t in ps:
            regs.append(reg); reg += 2 if t in ('J', 'D') else 1
        if ret == 'V':                 code = ['.locals 0', 'return-void']
        elif ret in ('J', 'D'):        code = ['.locals 2', 'const-wide/16 v0, 0x0', 'return-wide v0']
        elif ret in 'ZBSCIF':          code = ['.locals 1', 'const/4 v0, 0x0', 'return v0']
        elif ret in ps:                code = ['.locals 0', f'return-object p{regs[ps.index(ret)]}']
        else:                          code = ['.locals 1', 'const/4 v0, 0x0', 'return-object v0']
        return f'.method{mods} {name}({params}){ret}\n' + '\n'.join('    ' + c for c in code) + '\n.end method'
    src, n = re.subn(r'\.method((?: \w+)* native) ([\w$<>]+)\(([^)]*)\)(\S+)\n\.end method', body, src)
    open(p, 'w').write(src)
    print(f'denatived {cls}: {n} methods')

def make_optional(cls):
    """denative(cls) + its own System.loadLibrary never throws (for Samsung system libs loaded unguarded in <clinit>)."""
    denative(cls)
    p = find(cls)
    src = open(p).read()
    for a in ('Ljava/lang/System;', 'Lsamsungcompat/Native;'):
        src = src.replace(a + '->loadLibrary(Ljava/lang/String;)V', 'Lsamsungcompat/Native;->loadOptional(Ljava/lang/String;)V')
    open(p, 'w').write(src)

# Bundled .so files that link against Samsung system libs (libOpenCv.camera.samsung.so, ...) fail to dlopen,
# mostly inside static initializers -> whole activity dies. samsungcompat.Native swallows "dependency missing" errors.
n = rewrite_calls([
    ('Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V', 'Lsamsungcompat/Native;->loadLibrary(Ljava/lang/String;)V'),
    ('Ljava/lang/System;->load(Ljava/lang/String;)V', 'Lsamsungcompat/Native;->load(Ljava/lang/String;)V'),
])
print('native load call sites routed:', n)

# These JNI wrappers load bundled libs that NEED libOpenCv.camera.samsung.so (Samsung /system only). Without it the
# native methods are unregistered -> UnsatisfiedLinkError the moment a tool (or SPEActivity.onCreate) touches them.
# Make them inert: light balance / auto-tilt / blur / face-off / segmentation become no-ops instead of crashes.
for c in ['Lcom/samsung/android/lb/TonePlus;',                       # liblightbalance (Adjust > light balance)
          'Lcom/samsung/android/autotilt/HoughAutoTilt;',            # libtiltEngine (auto straighten)
          'Lcom/samsung/matting/GaussianEngine;',                    # libGaussianInterface (background blur)
          'Lcom/samsung/android/faceoff/FaceOffSupporterInterface;', # libFaceOffWrapper
          'Lcom/samsung/android/visual/lvmutility/LVMUtilityInterface;',  # libSRIBLVMUtilityInterface
          'Lcom/samsung/bgpsegmenter/BGPSegmenterUtility;',          # libbgpsegmenter
          # Samsung /system libs loaded unguarded in <clinit>:
          'Lcom/samsung/android/camera/imageprocessing/ImageScreener;',     # auto-enhance quality factors
          'Lcom/samsung/android/camera/imageprocessing/QualityEvaluator;']:
    make_optional(c)

# Samsung Unified Media (vcore SUM) registers 4 image plugins; SImgCodec/Simgp/OpenCV load libsum_jni.media.samsung.so
# (Samsung /system only) in <clinit> and the error escapes on a worker thread. Register each plugin independently so
# only SkiaPlugin (pure Android) survives.
GPR, PLG = 'Lcom/samsung/vcore/sum/core/plugin/GlobalPluginRegistry;', 'Lcom/samsung/vcore/sum/core/plugin/'
body = ['.locals 4',
        f'sget-boolean v0, {GPR}->isInitialized:Z', 'if-nez v0, :done',
        f'const-class v0, {GPR}', 'monitor-enter v0',
        f'sget-boolean v1, {GPR}->isInitialized:Z', 'if-nez v1, :unlock',
        f'invoke-static {{}}, {PLG}PluginRegistry;->create(){PLG}PluginRegistry;', 'move-result-object v1',
        f'sput-object v1, {GPR}->globalRegistry:{PLG}PluginRegistry;']
for i, name in enumerate(['SImgCodecPlugin', 'SimgpPlugin', 'OpenCVPlugin', 'SkiaPlugin']):
    body += [f':try_start_{i}',
             f'new-instance v2, {PLG}{name};', f'invoke-direct {{v2}}, {PLG}{name};-><init>()V',
             f'filled-new-array {{v2}}, [{PLG}Plugin;', 'move-result-object v2',
             f'invoke-virtual {{v1, v2}}, {PLG}PluginRegistry;->add([{PLG}Plugin;){PLG}PluginRegistry;',
             f':try_end_{i}', f'.catchall {{:try_start_{i} .. :try_end_{i}}} :catch_{i}',
             f'goto :next_{i}',
             f':catch_{i}', 'move-exception v2', f'const-string v3, "SUM {name}"',
             'invoke-static {v3, v2}, Lsamsungcompat/Native;->swallow(Ljava/lang/String;Ljava/lang/Throwable;)V',
             f':next_{i}']
body += ['const/4 v2, 0x1', f'sput-boolean v2, {GPR}->isInitialized:Z',
         ':unlock', 'monitor-exit v0',
         ':done', f'sget-object v0, {GPR}->globalRegistry:{PLG}PluginRegistry;', 'return-object v0']
replace_method(GPR, f'getGlobalRegistry(){PLG}PluginRegistry;', body)

# Most UniImgp pipelines (photo decode for the editor canvas, save/encode) ask for SIMG_CODEC/OPENCV/SIMGP stages.
# samsungcompat.Sum rewrites the stage list to what is registered (Skia decode/encode) before build().
inject_at_start('Lcom/samsung/vcore/sum/solution/uniimgp/UniImgpBuilder;', 'build()Lcom/samsung/vcore/sum/solution/uniimgp/UniImgp;',
                ['invoke-static {p0}, Lsamsungcompat/Sum;->filter(Ljava/lang/Object;)V'])

# Revert after "save over original": the original is backed up to /data/sec/photoeditor/<user>/ (Samsung system dir,
# not creatable here -> "Save NDP originalPath:null", no backup). Every such const-string is passed through
# samsungcompat.PeMedia.secPath(), which maps it into the app's private files/photoeditor/.
SEC = re.compile(r'^(\s*)const-string(?:/jumbo)? ([vp]\d+), "(/data/sec/photoeditor/[^"]*)"$', re.M)
n = 0
for d in SMALI_DIRS:
    for dp, _, fs in os.walk(d):
        for f in fs:
            if not f.endswith('.smali'): continue
            p = os.path.join(dp, f)
            src = open(p).read()
            if '/data/sec/photoeditor/' not in src: continue
            new, k = SEC.subn(lambda m: f'{m.group(0)}\n{m.group(1)}invoke-static/range {{{m.group(2)} .. {m.group(2)}}}, '
                              f'Lsamsungcompat/PeMedia;->secPath(Ljava/lang/String;)Ljava/lang/String;\n'
                              f'{m.group(1)}move-result-object {m.group(2)}', src)
            if k: open(p, 'w').write(new); n += k
print('backup dir const-strings redirected:', n)
