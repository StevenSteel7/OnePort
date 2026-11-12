"""Restore public-Android version guards stripped by Samsung's API-37 R8 build.

Runs after app_patch.py, on a fresh decoded and generically patched original.
Exact anchors deliberately fail if the pinned Samsung APK changes.
"""
from pathlib import Path
import re
import sys

root = Path(sys.argv[1])

def cls(name):
    paths = list(root.glob('smali*/' + name + '.smali'))
    if len(paths) != 1:
        raise SystemExit(f'expected one class {name}, found {len(paths)}')
    return paths[0]

def edit(name, operation):
    path = cls(name)
    text = path.read_text()
    path.write_text(operation(text))

def once(text, old, new):
    if text.count(old) != 1:
        raise SystemExit(f'expected one anchor ({text.count(old)}): {old[:150]}')
    return text.replace(old, new, 1)

def method(text, signature, operation):
    pattern = re.compile(r'(^\.method [^\n]* ' + re.escape(signature) + r'\n)(.*?)(^\.end method)', re.M | re.S)
    text, count = pattern.subn(lambda m: m[1] + operation(m[2]) + m[3], text)
    if count != 1:
        raise SystemExit(f'expected one method {signature}, found {count}')
    return text

def guard_void(text, signature, minimum):
    # Only use for methods with >=2 locals; never shift parameter aliases.
    def guard(body):
        match = re.search(r'^    \.locals (\d+)\n', body, re.M)
        if not match or int(match[1]) < 2:
            raise SystemExit(f'not enough scratch locals: {signature}')
        code = (f'    sget v0, Landroid/os/Build$VERSION;->SDK_INT:I\n'
                f'    const/16 v1, {minimum:#x}\n'
                '    if-ge v0, v1, :oneport_api_supported\n'
                '    return-void\n'
                '    :oneport_api_supported\n')
        return body[:match.end()] + code + body[match.end():]
    return method(text, signature, guard)

# Retain predictive progress/cancel on API34+, ordinary platform back on API33,
# and the existing onBackPressed dispatcher on API31/32.
def back_constructor(text):
    anchor = '    new-instance p1, Landroidx/activity/r;'
    text = once(text, anchor,
        '    sget v0, Landroid/os/Build$VERSION;->SDK_INT:I\n'
        '    const/16 v1, 0x21\n'
        '    if-ge v0, v1, :oneport_platform_back\n'
        '    return-void\n'
        '    :oneport_platform_back\n' + anchor)
    anchor = '    sget-object v3, Landroidx/activity/w;->a:Landroidx/activity/w;'
    text = once(text, anchor,
        '    sget v3, Landroid/os/Build$VERSION;->SDK_INT:I\n'
        '    const/16 v2, 0x22\n'
        '    if-ge v3, v2, :oneport_predictive_back\n'
        '    sget-object v3, Landroidx/activity/u;->a:Landroidx/activity/u;\n'
        '    invoke-virtual {v3, v1}, Landroidx/activity/u;->a(Led/a;)Landroid/window/OnBackInvokedCallback;\n'
        '    move-result-object p1\n'
        '    iput-object p1, p0, Landroidx/activity/z;->d:Landroid/window/OnBackInvokedCallback;\n'
        '    return-void\n'
        '    :oneport_predictive_back\n'
        # v2 was the cancellation callback and was used as scratch above.
        '    new-instance v2, Landroidx/activity/s;\n'
        '    const/4 v3, 0x1\n'
        '    invoke-direct {v2, v3, p0}, Landroidx/activity/s;-><init>(ILjava/lang/Object;)V\n' + anchor)
    return text
edit('androidx/activity/z', lambda t: method(t, '<init>(Ljava/lang/Runnable;)V', back_constructor))
edit('androidx/activity/ComponentActivity$6', lambda t: guard_void(method(t, 'b(Landroidx/lifecycle/u;Landroidx/lifecycle/n;)V', lambda b: once(b, '    .locals 1', '    .locals 2')), 'b(Landroidx/lifecycle/u;Landroidx/lifecycle/n;)V', 33))
edit('androidx/appcompat/widget/Toolbar', lambda t: guard_void(t, 'w()V', 33))

# AppCompat's attachToWindow must still run below API33, skipping just back setup.
edit('androidx/appcompat/app/l0', lambda t: once(t,
    '    iput-object p1, p0, Landroidx/appcompat/app/l0;->m:Landroid/view/Window;',
    '    iput-object p1, p0, Landroidx/appcompat/app/l0;->m:Landroid/view/Window;\n'
    '    sget v0, Landroid/os/Build$VERSION;->SDK_INT:I\n'
    '    const/16 v1, 0x21\n'
    '    if-lt v0, v1, :cond_4'))

# Preserve Dialog saved-state and lifecycle work when bypassing its platform dispatcher.
def dialog_back(text):
    text = once(text,
        '    iget-object v0, p0, Landroidx/activity/o;->onBackPressedDispatcher:Landroidx/activity/z;',
        '    sget v0, Landroid/os/Build$VERSION;->SDK_INT:I\n'
        '    const/16 v1, 0x21\n'
        '    if-lt v0, v1, :oneport_dialog_legacy_back\n'
        '    iget-object v0, p0, Landroidx/activity/o;->onBackPressedDispatcher:Landroidx/activity/z;')
    return once(text,
        '    iget-object v0, p0, Landroidx/activity/o;->savedStateRegistryController:Lw1/e;',
        '    :oneport_dialog_legacy_back\n'
        '    iget-object v0, p0, Landroidx/activity/o;->savedStateRegistryController:Lw1/e;')
edit('androidx/activity/o', lambda t: method(t, 'onCreate(Landroid/os/Bundle;)V', dialog_back))

# VibrationAttributes is public only on API33; leave its optional field null on
# older systems and route the eventual vibration through the pre-existing API.
edit('h6/c', lambda t: guard_void(t, '<clinit>()V', 33))

# Duration-scale listeners implement an interface absent below API33. Skip
# constructing that listener, but keep the animation loop and default scaling.
edit('c1/i', lambda t: once(t, '    new-instance v2, Lc1/a;',
    '    invoke-static {}, Lsamsungcompat/ApiCompat;->supportsDurationScaleListener()Z\n'
    '    move-result v2\n'
    '    if-eqz v2, :cond_2\n'
    '    new-instance v2, Lc1/a;'))

# Samsung's permission-version condition uses SEM_PLATFORM_INT. Android's actual
# API level selects READ_EXTERNAL_STORAGE (31/32) or READ_MEDIA_VIDEO (33+).
edit('f8/f', lambda t: once(t, '    sget-boolean v0, Lf8/c;->j:Z',
    '    sget v0, Landroid/os/Build$VERSION;->SDK_INT:I\n'
    '    const/16 v1, 0x21\n'
    '    if-lt v0, v1, :oneport_legacy_storage\n'
    '    const/4 v0, 0x1\n'
    '    goto :oneport_storage_ready\n'
    '    :oneport_legacy_storage\n'
    '    const/4 v0, 0x0\n'
    '    :oneport_storage_ready'))

# Isolate newer platform classes inside guarded Java helper classes. Object
# signatures prevent low-API ART resolving ScrollFeedbackProvider/LocaleManager.
bridge = 'Lsamsungcompat/ApiCompat;->'
routes = {
 'Landroid/widget/TextView;->setLineHeight(IF)V': ('lineHeight(Landroid/widget/TextView;IF)V', 'virtual|super'),
 'Landroid/os/Parcel;->readParcelable(Ljava/lang/ClassLoader;Ljava/lang/Class;)Ljava/lang/Object;': ('readParcelable(Landroid/os/Parcel;Ljava/lang/ClassLoader;Ljava/lang/Class;)Ljava/lang/Object;', 'virtual'),
 'Landroid/os/Parcel;->readSerializable(Ljava/lang/ClassLoader;Ljava/lang/Class;)Ljava/lang/Object;': ('readSerializable(Landroid/os/Parcel;Ljava/lang/ClassLoader;Ljava/lang/Class;)Ljava/lang/Object;', 'virtual'),
 'Landroid/view/WindowInsets$Type;->systemOverlays()I': ('systemOverlays()I', 'static'),
 'Ljava/util/concurrent/ExecutorService;->close()V': ('closeExecutor(Ljava/util/concurrent/ExecutorService;)V', 'interface'),
 'Ljava/lang/CharSequence;->isEmpty()Z': ('empty(Ljava/lang/CharSequence;)Z', 'interface'),
 'Ljava/lang/String;->isBlank()Z': ('blank(Ljava/lang/String;)Z', 'virtual'),
 'Landroid/content/pm/PackageManager$ApplicationInfoFlags;->of(J)Landroid/content/pm/PackageManager$ApplicationInfoFlags;': ('flags(J)Ljava/lang/Object;', 'static'),
 'Landroid/content/pm/PackageManager$PackageInfoFlags;->of(J)Landroid/content/pm/PackageManager$PackageInfoFlags;': ('flags(J)Ljava/lang/Object;', 'static'),
 'Landroid/content/pm/PackageManager;->getApplicationInfo(Ljava/lang/String;Landroid/content/pm/PackageManager$ApplicationInfoFlags;)Landroid/content/pm/ApplicationInfo;': ('applicationInfo(Landroid/content/pm/PackageManager;Ljava/lang/String;Ljava/lang/Object;)Landroid/content/pm/ApplicationInfo;', 'virtual'),
 'Landroid/content/pm/PackageManager;->getPackageInfo(Ljava/lang/String;Landroid/content/pm/PackageManager$PackageInfoFlags;)Landroid/content/pm/PackageInfo;': ('packageInfo(Landroid/content/pm/PackageManager;Ljava/lang/String;Ljava/lang/Object;)Landroid/content/pm/PackageInfo;', 'virtual'),
 'Landroid/util/TypedValue;->deriveDimension(IFLandroid/util/DisplayMetrics;)F': ('deriveDimension(IFLandroid/util/DisplayMetrics;)F', 'static'),
 'Landroid/view/VelocityTracker;->getAxisVelocity(I)F': ('axisVelocity(Landroid/view/VelocityTracker;I)F', 'virtual'),
 'Landroid/view/ViewConfiguration;->getScaledMinimumFlingVelocity(III)I': ('minimumFling(Landroid/view/ViewConfiguration;III)I', 'virtual'),
 'Landroid/view/ViewConfiguration;->getScaledMaximumFlingVelocity(III)I': ('maximumFling(Landroid/view/ViewConfiguration;III)I', 'virtual'),
 'Landroid/view/accessibility/AccessibilityNodeInfo;->getBoundsInWindow(Landroid/graphics/Rect;)V': ('boundsInWindow(Landroid/view/accessibility/AccessibilityNodeInfo;Landroid/graphics/Rect;)V', 'virtual'),
 'Landroid/view/accessibility/AccessibilityNodeInfo;->getChecked()I': ('checked(Landroid/view/accessibility/AccessibilityNodeInfo;)I', 'virtual'),
 'Landroid/view/accessibility/AccessibilityNodeInfo;->getContainerTitle()Ljava/lang/CharSequence;': ('containerTitle(Landroid/view/accessibility/AccessibilityNodeInfo;)Ljava/lang/CharSequence;', 'virtual'),
 'Landroid/view/accessibility/AccessibilityNodeInfo;->getExpandedState()I': ('expandedState(Landroid/view/accessibility/AccessibilityNodeInfo;)I', 'virtual'),
 'Landroid/view/accessibility/AccessibilityNodeInfo;->getSupplementalDescription()Ljava/lang/CharSequence;': ('supplementalDescription(Landroid/view/accessibility/AccessibilityNodeInfo;)Ljava/lang/CharSequence;', 'virtual'),
 'Landroid/view/accessibility/AccessibilityNodeInfo;->isAccessibilityDataSensitive()Z': ('dataSensitive(Landroid/view/accessibility/AccessibilityNodeInfo;)Z', 'virtual'),
 'Landroid/view/accessibility/AccessibilityNodeInfo;->isFieldRequired()Z': ('fieldRequired(Landroid/view/accessibility/AccessibilityNodeInfo;)Z', 'virtual'),
 'Landroid/view/accessibility/AccessibilityNodeInfo;->isTextSelectable()Z': ('textSelectable(Landroid/view/accessibility/AccessibilityNodeInfo;)Z', 'virtual'),
 'Landroid/graphics/Bitmap;->hasGainmap()Z': ('hasGainmap(Landroid/graphics/Bitmap;)Z', 'virtual'),
 'Landroid/animation/ValueAnimator;->getDurationScale()F': ('durationScale()F', 'static'),
 'Landroid/animation/ValueAnimator;->unregisterDurationScaleChangeListener(Landroid/animation/ValueAnimator$DurationScaleChangeListener;)Z': ('unregisterDurationScaleListener(Ljava/lang/Object;)Z', 'static'),

 'Landroid/app/PictureInPictureUiState;->isTransitioningToPip()Z': ('transitioningToPip(Landroid/app/PictureInPictureUiState;)Z', 'virtual'),
 'Ljava/util/ArrayList;->getLast()Ljava/lang/Object;': ('last(Ljava/util/ArrayList;)Ljava/lang/Object;', 'virtual'),
 'Ljava/util/stream/Stream;->toList()Ljava/util/List;': ('toList(Ljava/util/stream/Stream;)Ljava/util/List;', 'interface'),
 'Landroid/view/View;->setFrameContentVelocity(F)V': ('frameVelocity(Landroid/view/View;F)V', 'virtual'),
 'Landroid/view/View;->setRequestedFrameRate(F)V': ('requestedFrameRate(Landroid/view/View;F)V', 'virtual'),
 'Landroid/view/ScrollFeedbackProvider;->createProvider(Landroid/view/View;)Landroid/view/ScrollFeedbackProvider;': ('scrollProvider(Landroid/view/View;)Ljava/lang/Object;', 'static'),
 'Landroid/view/ScrollFeedbackProvider;->onScrollProgress(IIII)V': ('scrollProgress(Ljava/lang/Object;IIII)V', 'interface'),
 'Landroid/view/ScrollFeedbackProvider;->onScrollLimit(IIIZ)V': ('scrollLimit(Ljava/lang/Object;IIIZ)V', 'interface'),
 'Landroid/widget/TextView;->setLineBreakWordStyle(I)V': ('lineBreakWordStyle(Landroid/widget/TextView;I)V', 'virtual'),
 'Landroid/view/accessibility/AccessibilityNodeInfo;->getUniqueId()Ljava/lang/String;': ('uniqueId(Landroid/view/accessibility/AccessibilityNodeInfo;)Ljava/lang/String;', 'virtual'),
 'Landroid/app/ActivityOptions;->setPendingIntentBackgroundActivityLaunchAllowed(Z)V': ('backgroundLaunchAllowed(Landroid/app/ActivityOptions;Z)V', 'virtual'),
 'Landroid/app/ActivityOptions;->setPendingIntentBackgroundActivityStartMode(I)Landroid/app/ActivityOptions;': ('backgroundStartMode(Landroid/app/ActivityOptions;I)Landroid/app/ActivityOptions;', 'virtual'),
 'Landroid/app/LocaleManager;->getApplicationLocales()Landroid/os/LocaleList;': ('applicationLocales(Ljava/lang/Object;)Landroid/os/LocaleList;', 'virtual'),
 'Landroid/app/LocaleManager;->setApplicationLocales(Landroid/os/LocaleList;)V': ('applicationLocales(Ljava/lang/Object;Landroid/os/LocaleList;)V', 'virtual'),
 'Landroid/os/Bundle;->getParcelable(Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;': ('parcelable(Landroid/os/Bundle;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;', 'virtual'),
 'Landroid/os/Bundle;->getSerializable(Ljava/lang/String;Ljava/lang/Class;)Ljava/io/Serializable;': ('serializable(Landroid/os/Bundle;Ljava/lang/String;Ljava/lang/Class;)Ljava/io/Serializable;', 'virtual'),
 'Landroid/os/Vibrator;->vibrate(Landroid/os/VibrationEffect;Landroid/os/VibrationAttributes;)V': ('vibrate(Landroid/os/Vibrator;Landroid/os/VibrationEffect;Ljava/lang/Object;)V', 'virtual'),
}
counts = {key: 0 for key in routes}
for path in root.glob('smali*/**/*.smali'):
    old = text = path.read_text()
    for target, (replacement, kind) in routes.items():
        pattern = r'invoke-(?:' + kind + r')(/range)? (\{[^\n]*?\}), ' + re.escape(target)
        text, count = re.subn(pattern, lambda m: 'invoke-static' + (m[1] or '') + ' ' + m[2] + ', ' + bridge + replacement, text)
        counts[target] += count
    text = re.sub(r'(check-cast \w+, )Landroid/(?:view/ScrollFeedbackProvider|app/LocaleManager);', r'\1Ljava/lang/Object;', text)
    if text != old:
        path.write_text(text)
for target, count in counts.items():
    if count == 0:
        raise SystemExit(f'expected at least one backport call: {target}')
    print('backported', count, target)

# Audio Eraser's decorative guiding-light effect requires public RuntimeShader
# APIs added in Android 13. Keep the button listener, but skip only its glow
# setup below 33; all later users already null-check the optional effect.
edit('i2/d0', lambda t: once(t, '    new-instance v1, Lf7/u;', '    sget v1, Landroid/os/Build$VERSION;->SDK_INT:I\n    const/16 v2, 0x21\n    if-ge v1, v2, :oneport_runtime_shader_available\n    invoke-virtual {v0}, Lkb/b;->i()V\n    return-void\n    :oneport_runtime_shader_available\n    new-instance v1, Lf7/u;'))

# List fading-edge shaders are cosmetic. Their public shader API starts at 33;
# retain ordinary list drawing below it, without creating shader instances.
edit('w0/k', lambda t: guard_void(t, 'e()V', 33))
# The two transfer expressions are standard DST_OUT and SRC_OVER blends.
# Android 12-15 use the existing PorterDuff implementation; API36+ retains
# Samsung's RuntimeXfermode implementation and color animation logic.
def legacy_fading_mode(text):
    def patch(body):
        body = once(body, '    if-nez p1, :cond_0',
            '    sget v0, Landroid/os/Build$VERSION;->SDK_INT:I\n'
            '    const/16 v1, 0x24\n'
            '    if-ge v0, v1, :oneport_modern_fading_mode\n'
            '    iget-object v0, p0, Lw0/k;->a:Landroid/graphics/Paint;\n'
            '    invoke-static {p1}, Lsamsungcompat/ApiCompat;->fadingMode(I)Landroid/graphics/Xfermode;\n'
            '    move-result-object v1\n'
            '    invoke-virtual {v0, v1}, Landroid/graphics/Paint;->setXfermode(Landroid/graphics/Xfermode;)Landroid/graphics/Xfermode;\n'
            '    goto :oneport_fading_mode_ready\n'
            '    :oneport_modern_fading_mode\n'
            '    if-nez p1, :cond_0')
        return once(body, '    iget-object v0, p0, Lw0/k;->C:Lw0/g;',
            '    :oneport_fading_mode_ready\n'
            '    iget-object v0, p0, Lw0/k;->C:Lw0/g;')
    return method(text, 'i(ILjava/lang/Runnable;)V', patch)
edit('w0/k', legacy_fading_mode)

# AndroidX normally guards newer accessibility-action constants. Its existing
# constructor builds a compatible custom action from the ID when given null.
def accessibility_actions(text):
    for name in ('ACTION_DRAG_START', 'ACTION_DRAG_DROP', 'ACTION_DRAG_CANCEL', 'ACTION_SHOW_TEXT_SUGGESTIONS', 'ACTION_SCROLL_IN_DIRECTION'):
        pattern = r'sget-object (v\d+), Landroid/view/accessibility/AccessibilityNodeInfo\$AccessibilityAction;->' + name + r':Landroid/view/accessibility/AccessibilityNodeInfo\$AccessibilityAction;'
        text, count = re.subn(pattern, lambda m: 'const-string ' + m[1] + ', "' + name + '"\n'
            '    invoke-static {' + m[1] + '}, Lsamsungcompat/ApiCompat;->accessibilityAction(Ljava/lang/String;)Landroid/view/accessibility/AccessibilityNodeInfo$AccessibilityAction;\n'
            '    move-result-object ' + m[1], text)
        if count != 1: raise SystemExit(f'expected one accessibility action {name}, got {count}')
    return text
edit('y0/b', accessibility_actions)
