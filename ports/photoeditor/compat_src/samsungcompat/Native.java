package samsungcompat;

import android.util.Log;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Replaces System.loadLibrary/System.load at every call site.
 * Several bundled .so files link against Samsung system libs (libOpenCv.camera.samsung.so, libBeauty_v4...) that a
 * non-Samsung phone lacks. Most of them are loaded from static initializers, so one failure kills the whole activity.
 * If the library itself exists but a dependency or symbol is missing, swallow the error: the class still initializes
 * and only that feature fails (UnsatisfiedLinkError on its first native call). If the library itself is absent, rethrow:
 * the app already has fallbacks for missing Samsung system libraries.
 */
public final class Native {
    private static final String TAG = "NativeCompat";
    private static final Set<String> FAILED = Collections.synchronizedSet(new HashSet<>());

    private Native() {}

    public static void loadLibrary(String name) {
        try {
            System.loadLibrary(name);
        } catch (UnsatisfiedLinkError e) {
            handle(name, e, "lib" + name + ".so");
        }
    }

    public static void load(String path) {
        try {
            System.load(path);
        } catch (UnsatisfiedLinkError e) {
            handle(path, e, path.substring(path.lastIndexOf('/') + 1));
        }
    }

    /** For classes made inert by app_patch.make_optional(): never throws, whatever the reason. */
    public static void loadOptional(String name) {
        try {
            System.loadLibrary(name);
        } catch (UnsatisfiedLinkError e) {
            FAILED.add(name);
            Log.w(TAG, "optional " + name + " not loaded, feature disabled: " + e.getMessage());
        }
    }

    /** True if an earlier load of this library was swallowed. */
    public static boolean failed(String name) {
        return FAILED.contains(name);
    }

    /** Called from patched smali catch blocks. */
    public static void swallow(String what, Throwable t) {
        Log.w(TAG, what + " unavailable: " + t);
    }

    private static void handle(String name, UnsatisfiedLinkError e, String file) {
        String msg = String.valueOf(e.getMessage());
        boolean selfMissing = msg.contains("\"" + file + "\" not found") && !msg.contains("needed by");
        if (selfMissing || msg.contains("couldn't find \"" + file + "\"")) {
            throw e;
        }
        FAILED.add(name);
        Log.w(TAG, "load " + name + " failed, feature disabled: " + msg);
    }
}
