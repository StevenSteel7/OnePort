package samsungcompat;

import android.app.Notification;
import android.content.IntentSender;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.graphics.pdf.PdfRenderer;
import android.media.MediaCodec;
import android.os.Build;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.SortedSet;
import java.util.concurrent.Executor;

/**
 * Backports for platform APIs newer than Android 14, so My Files can run on Android 14 and 15.
 *
 * Samsung compiled My Files for Android 16 and calls these without version checks (or with the checks in
 * callers we cannot see). tools/api_backport.py rewrites every call site to the matching static method
 * here. On releases that have the API the real method is called, so newer phones behave exactly as before.
 */
public final class ApiCompat {
    private static final int V = Build.VERSION.SDK_INT;

    private ApiCompat() {}

    // ---- java.util / java.lang (SequencedCollection is API 35, Thread.threadId API 36) ----

    @SuppressWarnings("unchecked")
    public static void addFirst(ArrayList list, Object e) {
        list.add(0, e);
    }

    public static Object removeLast(ArrayList list) {
        if (list.isEmpty()) throw new NoSuchElementException();
        return list.remove(list.size() - 1);
    }

    public static Object getFirst(SortedSet set) {
        return set.first(); // throws NoSuchElementException when empty, like getFirst()
    }

    @SuppressWarnings("deprecation")
    public static long threadId(Thread t) {
        return t.getId();
    }

    // ---- android.view ----

    public static void setFrameContentVelocity(View v, float velocity) {
        if (V >= 35) v.setFrameContentVelocity(velocity);
    }

    public static void setRequestedFrameRate(View v, float rate) {
        if (V >= 35) v.setRequestedFrameRate(rate);
    }

    // ---- accessibility (API 36) ----

    public static void addHighContrastTextStateChangeListener(AccessibilityManager am, Executor executor,
            AccessibilityManager.HighContrastTextStateChangeListener listener) {
        if (V >= 36) am.addHighContrastTextStateChangeListener(executor, listener);
    }

    public static void removeHighContrastTextStateChangeListener(AccessibilityManager am,
            AccessibilityManager.HighContrastTextStateChangeListener listener) {
        if (V >= 36) am.removeHighContrastTextStateChangeListener(listener);
    }

    @SuppressWarnings("deprecation")
    public static int getChecked(AccessibilityNodeInfo info) {
        if (V >= 36) return info.getChecked();
        return info.isChecked() ? 1 : 0; // CHECKED_STATE_TRUE / FALSE
    }

    public static int getExpandedState(AccessibilityNodeInfo info) {
        return V >= 36 ? info.getExpandedState() : 0; // EXPANDED_STATE_UNDEFINED
    }

    public static CharSequence getSupplementalDescription(AccessibilityNodeInfo info) {
        return V >= 36 ? info.getSupplementalDescription() : null;
    }

    public static boolean isFieldRequired(AccessibilityNodeInfo info) {
        return V >= 36 && info.isFieldRequired();
    }

    // ---- notifications, packages, media, PDF ----

    public static Notification.Builder setShortCriticalText(Notification.Builder b, String text) {
        return V >= 36 ? b.setShortCriticalText(text) : b;
    }

    public static boolean isAppArchivable(PackageManager pm, String pkg) throws PackageManager.NameNotFoundException {
        return V >= 35 && pm.isAppArchivable(pkg);
    }

    public static void requestArchive(PackageInstaller installer, String pkg, IntentSender sender)
            throws PackageManager.NameNotFoundException {
        if (V >= 35) installer.requestArchive(pkg, sender);
        else throw new PackageManager.NameNotFoundException("App archiving needs Android 15");
    }

    public static void detachOutputSurface(MediaCodec codec) {
        if (V >= 35) codec.detachOutputSurface();
        else throw new IllegalStateException("detachOutputSurface needs Android 15");
    }

    public static List getTextContents(PdfRenderer.Page page) {
        return V >= 35 ? page.getTextContents() : Collections.emptyList();
    }
}
