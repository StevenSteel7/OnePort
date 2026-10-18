"""Samsung Video-specific smali edits, applied after the generic patcher."""
import os
import re
import sys

root = sys.argv[1]

def find_class(descriptor):
    rel = descriptor[1:-1] + '.smali'
    for directory in sorted(os.listdir(root)):
        path = os.path.join(root, directory, rel)
        if directory.startswith('smali') and os.path.exists(path):
            return path
    raise SystemExit(f'not found: {descriptor}')

def replace_method(descriptor, signature, body):
    path = find_class(descriptor)
    source = open(path).read()
    pattern = re.compile(r'(\.method [^\n]*' + re.escape(signature) + r'\n).*?(\.end method)', re.S)
    source, count = pattern.subn(
        lambda match: match.group(1) + '\n'.join('    ' + line for line in body) + '\n' + match.group(2),
        source,
        count=1,
    )
    if count != 1:
        raise SystemExit(f'method not found: {descriptor}->{signature}')
    open(path, 'w').write(source)
    print('patched', descriptor, signature)

def inject_at_start(descriptor, signature, lines):
    path = find_class(descriptor)
    source = open(path).read()
    pattern = re.compile(r'(\.method [^\n]*' + re.escape(signature) + r'\n\s*\.(?:locals|registers) \d+\n)')
    source, count = pattern.subn(
        lambda match: match.group(1) + '\n'.join('    ' + line for line in lines) + '\n',
        source,
        count=1,
    )
    if count != 1:
        raise SystemExit(f'method not found: {descriptor}->{signature}')
    open(path, 'w').write(source)
    print('injected', descriptor, signature)

def inject_after(descriptor, needle, lines, label):
    path = find_class(descriptor)
    source = open(path).read()
    if source.count(needle) != 1:
        raise SystemExit(f'unexpected insertion point count: {label}')
    source = source.replace(needle, needle + '\n'.join('    ' + line for line in lines) + '\n', 1)
    open(path, 'w').write(source)
    print('injected', descriptor, label)

# Samsung's semeventdelegator service does not exist on AOSP/Motorola. These
# registrations only refresh optional editor/package availability, so skipping
# them is preferable to failing MoviePlayer.onCreate.
package_pending = 'Lcom/samsung/android/video/player/monitor/receiver/PackagePendingIntent;'
replace_method(package_pending, 'registerPendingIntent(Landroid/content/Context;)V', [
    '.locals 0',
    'return-void',
])
replace_method(package_pending, 'unregisterPendingIntent(Landroid/content/Context;)V', [
    '.locals 0',
    'return-void',
])

# The app's managed-profile probe calls UserManager.getUserCount(), a privileged
# API guarded by MANAGE_USERS. This phone is running the ordinary owner profile,
# so report that the Samsung AFW/BYOD mode is not active.
replace_method('Ljb/a;', 'a(Landroid/content/Context;)Z', [
    '.locals 1',
    'const/4 v0, 0x0',
    'return v0',
])

# Call-state pausing is optional, but registering the callback requires a phone
# permission a normal video player cannot receive on Android 17. Null the cached
# TelephonyManager before the existing guarded registration block.
inject_at_start('Lva/d;', 'g0(Landroid/net/Uri;)V', [
    'const/4 v0, 0x0',
    'iput-object v0, p0, Lva/d;->t:Landroid/telephony/TelephonyManager;',
])

# A second call-state check invokes TelephonyManager.getCallState() directly and
# is likewise privileged. Returning false only disables automatic call pausing.
replace_method('Ljb/d;', 'a(Landroid/content/Context;)Z', [
    '.locals 1',
    'const/4 v0, 0x0',
    'return v0',
])

# One synthetic Function independently checks getCallState while playback is
# prepared. Route that sole remaining call to an unprivileged idle-state shim.
r1_path = find_class('Ljb/r1;')
r1_source = open(r1_path).read()
r1_old = 'invoke-virtual {p1}, Landroid/telephony/TelephonyManager;->getCallState()I'
r1_new = ('invoke-static {p1}, Lsamsungcompat/Phone;->getCallState('
          'Landroid/telephony/TelephonyManager;)I')
if r1_source.count(r1_old) != 1:
    raise SystemExit('unexpected TelephonyManager.getCallState call count in jb/r1')
open(r1_path, 'w').write(r1_source.replace(r1_old, r1_new))
print('patched Ljb/r1; privileged call-state query')

# Samsung enables system Picture-in-Picture only on selected One UI platform
# versions. The Android activity declaration and PiP implementation are valid
# on this Moto, so enable this one feature without pretending the whole device
# is running Samsung firmware (which would activate unrelated vendor paths).
feature_path = find_class('Lf8/d;')
feature_source = open(feature_path).read()
pip_old = 'sput-boolean v0, Lf8/d;->m0:Z'
pip_new = 'const/4 v0, 0x1\n\n    sput-boolean v0, Lf8/d;->m0:Z'
if feature_source.count(pip_old) != 1:
    raise SystemExit('unexpected Samsung PiP feature flag assignment count')
open(feature_path, 'w').write(feature_source.replace(pip_old, pip_new))
print('patched Lf8/d; Samsung-only PiP feature gate')

# Samsung also presets auto-enter PiP only when its gesture-navigation feature
# flag is enabled. Motorola's navigation implementation does not satisfy that
# Samsung-only test, leaving Android's `supportsEnterPipOnTaskSwitch` false.
# Preset the app's own public PiP parameters whenever this player regains focus.
inject_after(
    'Lcom/samsung/android/video/player/activity/MoviePlayer;',
    '    iput-boolean p1, p0, Lcom/samsung/android/video/player/activity/MoviePlayer;->u:Z\n',
    ['invoke-static {p0}, Ljb/b;->w(Landroid/app/Activity;)V'],
    'PiP auto-enter parameter preset',
)

# The on-screen "popup player" button emits event 0xeb28, which Samsung routes
# to its proprietary overlay service.  Route that event to the app's existing
# Android PiP handler (the same handler registered for event 0xeb2a) instead.
event_path = find_class('Lu8/d0;')
event_source = open(event_path).read()
popup_event = re.compile(
    r'(const v1, 0xeb28.*?new-instance v2, Lu8/x;.*?const/16 v3, )0xb',
    re.S,
)
event_source, event_count = popup_event.subn(r'\g<1>0x16', event_source, count=1)
if event_count != 1:
    raise SystemExit('unexpected popup-player event mapping in u8/d0')
open(event_path, 'w').write(event_source)
print('patched Lu8/d0; popup button routed to Android PiP')

# Android 17 rejects Samsung's legacy RemoteViews notification layout on this
# device and terminates the foreground playback service. Keep the service (and
# therefore background/PiP playback) but build its normal small-icon Android
# notification without the Samsung-specific custom content view.
replace_method('Lta/e;', 'p(Landroid/graphics/Bitmap;)V', [
    '.locals 2',
    # o() also initializes the controller state used later by the playback
    # callbacks.  Do not attach its Samsung RemoteViews result to the
    # notification: Android 17 cannot inflate that layout outside One UI.
    'invoke-virtual {p0}, Lta/e;->o()Landroid/widget/RemoteViews;',
    'move-result-object v0',
    'invoke-virtual {p0}, Lta/b;->g()Landroid/app/Notification$Builder;',
    'move-result-object v0',
    'invoke-virtual {v0}, Landroid/app/Notification$Builder;->build()Landroid/app/Notification;',
    'move-result-object v0',
    'iput-object v0, p0, Lta/b;->g:Landroid/app/Notification;',
    'invoke-virtual {p0}, Lta/b;->j()V',
    'const/4 v1, 0x0',
    'invoke-virtual {p0, v1}, Lta/b;->l(Lcom/samsung/android/video/player/service/MoviePlaybackService;)V',
    'return-void',
])

# PopupPlayer is Samsung's proprietary free-floating overlay. It crashes on
# AOSP/Motorola while updating an unattached DecorView, so stop it immediately
# and retain the supported system PiP experience instead.
replace_method('Lcom/samsung/android/video/player/miniplayer/PopupPlayer;',
               'onStartCommand(Landroid/content/Intent;II)I', [
    '.locals 1',
    'invoke-virtual {p0}, Landroid/app/Service;->stopSelf()V',
    'const/4 v0, 0x2',
    'return v0',
])

# A stale or alternate request can still create the disabled service. Its stock
# destructor unconditionally unregisters receivers that onStartCommand never
# registered, causing "Receiver not registered" and killing the whole process.
replace_method('Lcom/samsung/android/video/player/miniplayer/PopupPlayer;',
               'onDestroy()V', [
    '.locals 0',
    'invoke-super {p0}, Lib/a;->onDestroy()V',
    'return-void',
])

# The Samsung secure-lock receiver coordinates that same popup overlay and was
# responsible for screen-off ANRs. It is not required for regular playback or
# Android PiP.
replace_method('Lcom/samsung/android/video/player/monitor/receiver/SecureLockReceiver;',
               'onReceive(Landroid/content/Context;Landroid/content/Intent;)V', [
    '.locals 0',
    'return-void',
])
