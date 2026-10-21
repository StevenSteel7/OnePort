package samsungcompat;

import android.app.Activity;
import android.app.Application;
import android.app.Fragment;
import android.app.FragmentManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shows a MediaStore confirmation (trash/restore/delete request) from Gallery's resumed Activity with
 * startIntentSenderForResult, so the user's Allow/Deny comes back directly. Starting it from the application
 * context gives no result: a dismissed dialog then looked like a 2-minute hang followed by "Couldn't delete".
 */
public final class ConfirmRequest {
    private static final String TAG = "MediaStoreTrash";
    static final int UNAVAILABLE = Integer.MIN_VALUE;
    private static final long USER_TIMEOUT_MIN = 10;
    private static final Map<String, Pending> PENDING = new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT = new AtomicInteger();
    private static volatile WeakReference<Activity> resumed = new WeakReference<>(null);

    private ConfirmRequest() {}

    /** Called from GalleryApplication.onCreate (gallery_patch.py). */
    public static void install(Application app) {
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity a) { resumed = new WeakReference<>(a); }
            @Override public void onActivityPaused(Activity a) { }
            @Override public void onActivityCreated(Activity a, Bundle b) { }
            @Override public void onActivityStarted(Activity a) { }
            @Override public void onActivityStopped(Activity a) { }
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            @Override public void onActivityDestroyed(Activity a) {
                if (resumed.get() == a) resumed = new WeakReference<>(null);
            }
        });
    }

    /**
     * Blocks a worker thread until the user answers. Returns Activity.RESULT_OK / RESULT_CANCELED, or
     * UNAVAILABLE when no Gallery Activity can host the dialog (the caller then falls back).
     */
    static int show(IntentSender sender) throws InterruptedException {
        return run(new Pending(sender, null));
    }

    /**
     * Without Media management (MANAGE_MEDIA) every trash/restore/delete shows Android's "Allow Gallery to ..."
     * dialog. A fresh install resets that access, so on the first request open its settings page once.
     */
    static void askManageMediaOnce(Context context) throws InterruptedException {
        // Media management exists from Android 12 (API 31); Gallery also installs on Android 11.
        if (android.os.Build.VERSION.SDK_INT < 31 || MediaStore.canManageMedia(context)) return;
        SharedPreferences prefs = context.getSharedPreferences("samsungcompat", Context.MODE_PRIVATE);
        if (prefs.getBoolean("manage_media_asked", false)) return;
        Intent intent = new Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA,
                Uri.parse("package:" + context.getPackageName()));
        if (run(new Pending(null, intent)) != UNAVAILABLE) {
            prefs.edit().putBoolean("manage_media_asked", true).apply();
            Log.i(TAG, "Media management " + (MediaStore.canManageMedia(context) ? "granted" : "not granted"));
        }
    }

    private static int run(Pending pending) throws InterruptedException {
        Activity activity = resumed.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()
                || Looper.myLooper() == Looper.getMainLooper()) {
            return UNAVAILABLE;
        }
        String key = "mediastore_confirm_" + NEXT.incrementAndGet();
        PENDING.put(key, pending);
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Bundle args = new Bundle();
                args.putString("key", key);
                Host host = new Host();
                host.setArguments(args);
                FragmentManager fm = activity.getFragmentManager();
                fm.beginTransaction().add(host, key).commitAllowingStateLoss();
            } catch (Exception e) {
                Log.e(TAG, "Cannot host confirmation", e);
                pending.finish(UNAVAILABLE);
            }
        });
        try {
            if (!pending.done.await(USER_TIMEOUT_MIN, TimeUnit.MINUTES)) return Activity.RESULT_CANCELED;
            return pending.result;
        } finally {
            PENDING.remove(key);
        }
    }

    private static final class Pending {
        final IntentSender sender;
        final Intent intent;
        final CountDownLatch done = new CountDownLatch(1);
        volatile int result = Activity.RESULT_CANCELED;
        boolean launched;

        Pending(IntentSender sender, Intent intent) {
            this.sender = sender;
            this.intent = intent;
        }

        void finish(int code) {
            result = code;
            done.countDown();
        }
    }

    /** Headless fragment; public with a no-arg constructor so the framework can recreate it. */
    public static final class Host extends Fragment {
        @Override
        public void onCreate(Bundle state) {
            super.onCreate(state);
            Pending pending = pending();
            if (pending == null) { remove(); return; }
            if (pending.launched) return;
            pending.launched = true;
            try {
                if (pending.intent != null) startActivityForResult(pending.intent, 1);
                else startIntentSenderForResult(pending.sender, 1, null, 0, 0, 0, null);
            } catch (Exception e) {
                Log.e(TAG, "Cannot show confirmation", e);
                pending.finish(UNAVAILABLE);
                remove();
            }
        }

        @Override
        public void onActivityResult(int requestCode, int resultCode, Intent data) {
            Pending pending = pending();
            if (pending != null) pending.finish(resultCode);
            remove();
        }

        private Pending pending() {
            Bundle args = getArguments();
            return args != null ? PENDING.get(args.getString("key")) : null;
        }

        private void remove() {
            FragmentManager fm = getFragmentManager();
            if (fm != null) fm.beginTransaction().remove(this).commitAllowingStateLoss();
        }
    }
}
