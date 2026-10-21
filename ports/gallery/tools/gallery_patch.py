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

def edit_method(cls, sig, edit):
    p = find(cls)
    src = open(p).read()
    pat = re.compile(r'(\.method [^\n]*' + re.escape(sig) + r'\n)(.*?)(\.end method)', re.S)
    m = pat.search(src)
    if not m: raise SystemExit(f'method not found: {cls}->{sig}')
    body = edit(m.group(2))
    src = src[:m.start()] + m.group(1) + body + m.group(3) + src[m.end():]
    open(p, 'w').write(src)
    print('edited', cls, sig)

URI_PARSE = 'invoke-static {p0}, Landroid/net/Uri;->parse(Ljava/lang/String;)Landroid/net/Uri;'

# Gallery's DAL sends raw SQL to Samsung's MediaProvider; on non-Samsung (GED) builds this URI is "unsupported"
# and throws. Point it at samsungcompat.SecMedia, which emulates the provider in-process.
replace_method('Lcom/samsung/android/gallery/support/providers/MediaUriGed;', 'getRawQueryUri(Ljava/lang/String;)Landroid/net/Uri;',
               ['.locals 0', 'const-string p0, "content://secmedia/raw_sql"', URI_PARSE, 'move-result-object p0', 'return-object p0'])

# Recycle bin: expose Samsung's One Trash UI, but back its missing sectrash provider with standard MediaStore.
replace_method('Lcom/samsung/android/gallery/support/utils/Features$149;', 'getEnabling()Z',
               ['.locals 1', 'const/4 v0, 0x1', 'return v0'])
replace_method('Lcom/samsung/android/gallery/support/utils/Features$150;', 'getEnabling()Z',
               ['.locals 1', 'const/4 v0, 0x1', 'return v0'])

# File moves into Samsung's private Android/.Trash directory are replaced by MediaStore.createTrashRequest().
replace_method('Lcom/samsung/android/gallery/module/trash/helper/TrashDeleteHelper;',
               'moveFileToTrash(Lcom/samsung/android/gallery/module/trash/abstraction/TrashDeleteItem;)Ljava/lang/String;', [
    '.locals 1',
    'invoke-virtual {p1}, Lcom/samsung/android/gallery/module/trash/abstraction/TrashBaseItem;->getPath()Ljava/lang/String;',
    'move-result-object v0',
    'invoke-static {v0}, Lsamsungcompat/MediaStoreTrash;->trash(Ljava/lang/String;)Ljava/lang/String;',
    'move-result-object v0',
    'return-object v0'])

# Once MediaStore has changed the row, do not let Samsung's second stage delete that same row permanently.
def keep_mediastore_row(body):
    marker = body.find('    .locals ')
    end = body.find('\n', marker)
    inject = ('\n\n    iget-boolean v0, p0, Lcom/samsung/android/gallery/module/trash/helper/TrashDeleteHelper;->mSupportTrash:Z\n'
              '    if-eqz v0, :media_store_original_step2\n'
              '    const/4 v0, 0x1\n'
              '    return v0\n\n'
              '    :media_store_original_step2')
    return body[:end] + inject + body[end:]
edit_method('Lcom/samsung/android/gallery/module/trash/helper/TrashDeleteHelper;',
            'step2ndDeleteDB(Lcom/samsung/android/gallery/module/trash/abstraction/TrashDeleteItem;Ljava/lang/String;)Z',
            keep_mediastore_row)

# SUPPORT_ONE_TRASH makes every delete require Samsung's com.samsung.android.providers.trash package
# ("sectrash provider is not available" -> "Unable to delete"). MediaStore is the trash backend here.
replace_method('Lcom/samsung/android/gallery/app/controller/internals/DeleteBaseCmd;',
               'isSecTrashProviderAvailable(Ljava/lang/String;)Z',
               ['.locals 1', 'const/4 v0, 0x1', 'return v0'])

# Only MediaUriSecU overrides getSecTrashUri(); on GED it is null, so opening the Recycle bin crashes
# (NPE "uri" in TrashMpProvider). Point it at sectrash, which samsungcompat.Trash serves from MediaStore.
replace_method('Lcom/samsung/android/gallery/support/providers/UriInterface;', 'getSecTrashUri()Landroid/net/Uri;',
               ['.locals 0', 'const-string p0, "content://sectrash/files"', URI_PARSE, 'move-result-object p0', 'return-object p0'])

# Trashed files live under .trash-storage, which Gallery can't open by path (EACCES -> broken bin thumbnails).
def trashed_thumbnail_first(body):
    end = body.find('\n', body.find('    .locals ')) + 1
    return body[:end] + ('\n    invoke-static {p1}, Lsamsungcompat/MediaStoreTrash;->thumbnail(Ljava/lang/Object;)Landroid/graphics/Bitmap;\n'
                         '    move-result-object v0\n'
                         '    if-eqz v0, :media_store_thumb_original\n'
                         '    return-object v0\n\n'
                         '    :media_store_thumb_original\n') + body[end:]
edit_method('Lcom/samsung/android/gallery/module/thumbnail/logic/LocalThumbnailLoaderImpl;',
            'getThumbnail(Lcom/samsung/android/gallery/module/thumbnail/type/ReqInfo;)Landroid/graphics/Bitmap;',
            trashed_thumbnail_first)

# Android owns DATE_EXPIRES and purges expired MediaStore trash; a background task must not open confirmation UI.
replace_method('Lcom/samsung/android/gallery/module/trash/helper/TrashEmptyExpiredTask;',
               'doInBackground([Ljava/lang/Void;)Ljava/lang/Void;',
               ['.locals 1', 'const/4 v0, 0x0', 'return-object v0'])

def add_delete_confirmation(body):
    needle = ('    :cond_0\n'
              '    invoke-virtual {p0}, Lcom/samsung/android/gallery/app/service/DeleteService;->isAlbums()Z')
    replacement = ('    :cond_0\n'
        '    iget-boolean v2, p0, Lcom/samsung/android/gallery/app/service/DeleteService;->mUseTrash:Z\n'
        '    if-eqz v2, :media_store_direct_delete\n'
        '    iget-boolean v2, p0, Lcom/samsung/android/gallery/app/service/DeleteService;->mIsDirectDelete:Z\n'
        '    if-nez v2, :media_store_trash_request\n'
        '    :media_store_direct_delete\n'
        '    invoke-static {v5}, Lsamsungcompat/MediaStoreTrash;->preflightDeleteAny([Ljava/lang/Object;)Z\n'
        '    move-result v2\n'
        '    goto :media_store_delete_request_done\n'
        '    :media_store_trash_request\n'
        '    invoke-static {v5}, Lsamsungcompat/MediaStoreTrash;->preflightTrash([Ljava/lang/Object;)Z\n'
        '    move-result v2\n'
        '    :media_store_delete_request_done\n'
        '    if-nez v2, :media_store_delete_confirmed\n'
        '    return v1\n'
        '    :media_store_delete_confirmed\n'
        '    invoke-virtual {p0}, Lcom/samsung/android/gallery/app/service/DeleteService;->isAlbums()Z')
    if body.count(needle) != 1: raise SystemExit('DeleteService addJobs insertion point changed')
    return body.replace(needle, replacement)
edit_method('Lcom/samsung/android/gallery/app/service/DeleteService;', 'addJobs(Landroid/content/Intent;)Z', add_delete_confirmation)

def add_trash_confirmation(kind, helper):
    def edit(body):
        needle = '    :cond_0\n    array-length v3, p1'
        replacement = ('    :cond_0\n'
            f'    invoke-static {{p1}}, Lsamsungcompat/MediaStoreTrash;->{helper}([Ljava/lang/Object;)Z\n'
            '    move-result v3\n'
            f'    if-nez v3, :media_store_{kind}_confirmed\n'
            '    return v1\n'
            f'    :media_store_{kind}_confirmed\n'
            '    array-length v3, p1')
        if body.count(needle) != 1: raise SystemExit(f'{kind} addJobs insertion point changed')
        return body.replace(needle, replacement)
    return edit
edit_method('Lcom/samsung/android/gallery/app/service/RestoreService;', 'addJobs(Landroid/content/Intent;)Z',
            add_trash_confirmation('restore', 'preflightRestore'))
edit_method('Lcom/samsung/android/gallery/app/service/EmptyService;', 'addJobs(Landroid/content/Intent;)Z',
            add_trash_confirmation('empty', 'preflightDelete'))

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

# MediaStore confirmations need a host Activity to report Allow/Deny (samsungcompat.ConfirmRequest).
def install_confirm_request(body):
    end = body.find('\n', body.find('    .locals ')) + 1
    return body[:end] + '\n    invoke-static {p0}, Lsamsungcompat/ConfirmRequest;->install(Landroid/app/Application;)V\n' + body[end:]
edit_method('Lcom/samsung/android/gallery/app/activity/GalleryApplication;', 'onCreate()V', install_confirm_request)

# Recycle bin grid: "N days" label turns red at <= 1 day left (samsungcompat.TrashUi).
TRASH_HOLDER = 'Lcom/samsung/android/gallery/app/ui/list/trash/TrashItemViewHolder;'
replace_method(TRASH_HOLDER, 'updateDayText(Lcom/samsung/android/gallery/module/data/MediaItem;)V', [
    '.locals 2',
    'if-nez p1, :has_item',
    'iget-object p0, p0, Lcom/samsung/android/gallery/widget/listviewholder/ListViewHolder;->TAG:Ljava/lang/String;',
    'const-string p1, "TrashItem null"',
    'invoke-static {p0, p1}, Lcom/samsung/android/gallery/support/utils/Log;->e(Ljava/lang/CharSequence;Ljava/lang/String;)V',
    'return-void',
    ':has_item',
    'iget-object v0, p0, ' + TRASH_HOLDER + '->mNDaysTextView:Landroid/widget/TextView;',
    'invoke-direct {p0, p1}, ' + TRASH_HOLDER + '->getRemainDays(Lcom/samsung/android/gallery/module/data/MediaItem;)I',
    'move-result v1',
    'invoke-direct {p0, v1}, ' + TRASH_HOLDER + '->getNDayString(I)Ljava/lang/String;',
    'move-result-object p0',
    'invoke-static {v0, p0, v1}, Lsamsungcompat/TrashUi;->setDays(Landroid/widget/TextView;Ljava/lang/String;I)V',
    'return-void'])

# On-screen back/up arrow: publishBackKeyEvent fakes KEYCODE_BACK through a BaseInputConnection unless One UI >= 7.
# From Android 15 Gallery opts into OnBackInvokedCallback (bool/enableOnBackInvokedCallback is true in values-v35),
# and that fake key no longer triggers back, so the arrow did nothing. Use the OnBackPressedDispatcher (what the
# back gesture uses) on API 35+ as well.
BB = 'Lcom/samsung/android/gallery/support/blackboard/Blackboard;'
replace_method('Lcom/samsung/android/gallery/module/utils/BlackboardUtils;', 'publishBackKeyEvent(' + BB + ')V', [
    '.locals 2',
    'sget v0, Landroid/os/Build$VERSION;->SDK_INT:I',
    'const/16 v1, 0x23',
    'if-ge v0, v1, :dispatch',
    'sget-object v0, Lcom/samsung/android/gallery/support/config/SdkConfig$SEM;->V:Lcom/samsung/android/gallery/support/config/SdkConfig$SEM;',
    'invoke-static {v0}, Lcom/samsung/android/gallery/support/config/SdkConfig;->atLeast(Lcom/samsung/android/gallery/support/config/SdkConfig$SEM;)Z',
    'move-result v0',
    'if-eqz v0, :simulate',
    ':dispatch',
    'const-string v0, "data://activity"',
    'const/4 v1, 0x0',
    'invoke-virtual {p0, v0, v1}, ' + BB + '->read(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;',
    'move-result-object v0',
    'instance-of v1, v0, Landroidx/fragment/app/FragmentActivity;',
    'if-eqz v1, :simulate',
    'check-cast v0, Landroidx/fragment/app/FragmentActivity;',
    'invoke-virtual {v0}, Landroidx/activity/ComponentActivity;->getOnBackPressedDispatcher()Landroidx/activity/OnBackPressedDispatcher;',
    'move-result-object v0',
    'invoke-virtual {v0}, Landroidx/activity/OnBackPressedDispatcher;->onBackPressed()V',
    'return-void',
    ':simulate',
    'const/4 v0, 0x4',
    'invoke-static {v0}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;',
    'move-result-object v0',
    'const-string v1, "command://simulateUserKey"',
    'invoke-virtual {p0, v1, v0}, ' + BB + '->post(Ljava/lang/String;Ljava/lang/Object;)V',
    'return-void'])
