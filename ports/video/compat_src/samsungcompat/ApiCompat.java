package samsungcompat;

import android.app.ActivityOptions;
import android.os.Build;
import android.os.Bundle;
import android.os.LocaleList;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Public Android API fallbacks for Samsung's API-37 optimized bytecode. */
public final class ApiCompat {
    private ApiCompat() {}

    public static AccessibilityNodeInfo.AccessibilityAction accessibilityAction(String name) {
        try {
            return (AccessibilityNodeInfo.AccessibilityAction)
                    AccessibilityNodeInfo.AccessibilityAction.class.getField(name).get(null);
        } catch (ReflectiveOperationException e) {
            // AndroidX constructs its ordinary action-ID fallback from null.
            return null;
        }
    }


    public static android.graphics.Xfermode fadingMode(int mode) {
        return new android.graphics.PorterDuffXfermode(mode == 0
                ? android.graphics.PorterDuff.Mode.DST_OUT : android.graphics.PorterDuff.Mode.SRC_OVER);
    }


    public static void lineHeight(TextView view, int unit, float value) {
        int pixels = Math.round(android.util.TypedValue.applyDimension(unit, value, view.getResources().getDisplayMetrics()));
        if (pixels < 0) throw new IllegalArgumentException("lineHeight must be nonnegative");
        int fontHeight = view.getPaint().getFontMetricsInt(null);
        if (pixels != fontHeight) view.setLineSpacing(pixels - fontHeight, 1f);
    }
    @SuppressWarnings("deprecation")
    public static Object readParcelable(android.os.Parcel parcel, ClassLoader loader, Class<?> type) {
        if (Build.VERSION.SDK_INT >= 33) return Api33.readParcelable(parcel, loader, type);
        Object value = parcel.readParcelable(loader);
        return type.isInstance(value) ? value : null;
    }
    @SuppressWarnings("deprecation")
    public static Object readSerializable(android.os.Parcel parcel, ClassLoader loader, Class<?> type) {
        if (Build.VERSION.SDK_INT >= 33) return Api33.readSerializable(parcel, loader, type);
        Object value = parcel.readSerializable();
        return type.isInstance(value) ? value : null;
    }
    public static int systemOverlays() { return Build.VERSION.SDK_INT >= 34 ? Api34.systemOverlays() : 0; }
    public static Object flags(long value) { return Long.valueOf(value); }
    public static android.content.pm.ApplicationInfo applicationInfo(android.content.pm.PackageManager manager, String name, Object flags)
            throws android.content.pm.PackageManager.NameNotFoundException {
        long value = ((Long) flags).longValue();
        return Build.VERSION.SDK_INT >= 33 ? Api33.applicationInfo(manager, name, value) : manager.getApplicationInfo(name, (int) value);
    }
    public static android.content.pm.PackageInfo packageInfo(android.content.pm.PackageManager manager, String name, Object flags)
            throws android.content.pm.PackageManager.NameNotFoundException {
        long value = ((Long) flags).longValue();
        return Build.VERSION.SDK_INT >= 33 ? Api33.packageInfo(manager, name, value) : manager.getPackageInfo(name, (int) value);
    }
    public static void closeExecutor(java.util.concurrent.ExecutorService executor) {
        boolean interrupted = false;
        executor.shutdown();
        try {
            while (!executor.isTerminated()) {
                try { executor.awaitTermination(1, java.util.concurrent.TimeUnit.DAYS); }
                catch (InterruptedException e) {
                    if (!interrupted) executor.shutdownNow();
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
    public static boolean empty(CharSequence text) { return text.length() == 0; }
    public static boolean blank(String text) { return text.codePoints().allMatch(Character::isWhitespace); }
    public static float deriveDimension(int unit, float value, android.util.DisplayMetrics metrics) {
        if (Build.VERSION.SDK_INT >= 34) return Api34.deriveDimension(unit, value, metrics);
        float scale = android.util.TypedValue.applyDimension(unit, 1, metrics);
        if (unit < 0 || unit > android.util.TypedValue.COMPLEX_UNIT_MM) throw new IllegalArgumentException("Invalid unit: " + unit);
        return scale == 0 ? 0 : value / scale;
    }
    public static float axisVelocity(android.view.VelocityTracker tracker, int axis) {
        if (Build.VERSION.SDK_INT >= 34) return Api34.axisVelocity(tracker, axis);
        if (axis == android.view.MotionEvent.AXIS_X) return tracker.getXVelocity();
        if (axis == android.view.MotionEvent.AXIS_Y) return tracker.getYVelocity();
        return 0;
    }
    public static int minimumFling(android.view.ViewConfiguration config, int device, int axis, int source) {
        return Build.VERSION.SDK_INT >= 34 ? Api34.minimumFling(config, device, axis, source) : config.getScaledMinimumFlingVelocity();
    }
    public static int maximumFling(android.view.ViewConfiguration config, int device, int axis, int source) {
        return Build.VERSION.SDK_INT >= 34 ? Api34.maximumFling(config, device, axis, source) : config.getScaledMaximumFlingVelocity();
    }
    // Optional accessibility properties retain AndroidX extras on old releases.
    private static Object optionalNode(AccessibilityNodeInfo node, String method, Object fallback) {
        try { return AccessibilityNodeInfo.class.getMethod(method).invoke(node); }
        catch (ReflectiveOperationException e) { return fallback; }
    }
    public static void boundsInWindow(AccessibilityNodeInfo node, android.graphics.Rect out) {
        if (Build.VERSION.SDK_INT >= 34) Api34.boundsInWindow(node, out);
        else {
            android.graphics.Rect rect = node.getExtras().getParcelable("androidx.view.accessibility.AccessibilityNodeInfoCompat.BOUNDS_IN_WINDOW_KEY");
            if (rect != null) out.set(rect); else node.getBoundsInScreen(out);
        }
    }
    public static int checked(AccessibilityNodeInfo node) { return ((Number) optionalNode(node, "getChecked", node.isChecked() ? 1 : 0)).intValue(); }
    public static CharSequence containerTitle(AccessibilityNodeInfo node) { return (CharSequence) optionalNode(node, "getContainerTitle", node.getExtras().getCharSequence("androidx.view.accessibility.AccessibilityNodeInfoCompat.CONTAINER_TITLE_KEY")); }
    public static int expandedState(AccessibilityNodeInfo node) { return ((Number) optionalNode(node, "getExpandedState", node.getExtras().getInt("androidx.view.accessibility.AccessibilityNodeInfoCompat.EXPANDED_STATE_KEY"))).intValue(); }
    public static CharSequence supplementalDescription(AccessibilityNodeInfo node) { return (CharSequence) optionalNode(node, "getSupplementalDescription", node.getExtras().getCharSequence("androidx.view.accessibility.AccessibilityNodeInfoCompat.SUPPLEMENTAL_DESCRIPTION_KEY")); }
    public static boolean dataSensitive(AccessibilityNodeInfo node) { return (Boolean) optionalNode(node, "isAccessibilityDataSensitive", false); }
    public static boolean fieldRequired(AccessibilityNodeInfo node) { return (Boolean) optionalNode(node, "isFieldRequired", false); }
    public static boolean textSelectable(AccessibilityNodeInfo node) { return (Boolean) optionalNode(node, "isTextSelectable", false); }
    public static boolean hasGainmap(android.graphics.Bitmap bitmap) { return Build.VERSION.SDK_INT >= 34 && Api34.hasGainmap(bitmap); }
    public static float durationScale() { return Build.VERSION.SDK_INT >= 33 ? Api33.durationScale() : (android.animation.ValueAnimator.areAnimatorsEnabled() ? 1f : 0f); }
    public static boolean supportsDurationScaleListener() { return Build.VERSION.SDK_INT >= 33; }
    public static boolean unregisterDurationScaleListener(Object listener) {
        return Build.VERSION.SDK_INT >= 33 && Api33.unregisterDurationScaleListener(listener);
    }
    public static Object last(ArrayList<?> list) {
        if (list.isEmpty()) throw new NoSuchElementException();
        return list.get(list.size() - 1);
    }
    public static List<?> toList(Stream<?> stream) {
        // Stream.toList's result is unmodifiable (Collectors.toList's is not).
        return Collections.unmodifiableList(stream.collect(Collectors.toList()));
    }
    public static boolean transitioningToPip(android.app.PictureInPictureUiState state) {
        return Build.VERSION.SDK_INT >= 35 && Api35.transitioningToPip(state);
    }
    public static void frameVelocity(View view, float velocity) {
        if (Build.VERSION.SDK_INT >= 35) Api35.frameVelocity(view, velocity);
    }
    public static void requestedFrameRate(View view, float rate) {
        if (Build.VERSION.SDK_INT >= 36) Api36.requestedFrameRate(view, rate);
    }
    public static Object scrollProvider(View view) {
        return Build.VERSION.SDK_INT >= 35 ? Api35.scrollProvider(view) : null;
    }
    public static void scrollProgress(Object provider, int device, int source, int axis, int delta) {
        if (Build.VERSION.SDK_INT >= 35 && provider != null) Api35.scrollProgress(provider, device, source, axis, delta);
    }
    public static void scrollLimit(Object provider, int device, int source, int axis, boolean start) {
        if (Build.VERSION.SDK_INT >= 35 && provider != null) Api35.scrollLimit(provider, device, source, axis, start);
    }
    public static void lineBreakWordStyle(TextView view, int style) {
        if (Build.VERSION.SDK_INT >= 33) Api33.lineBreakWordStyle(view, style);
    }
    public static String uniqueId(AccessibilityNodeInfo node) {
        return Build.VERSION.SDK_INT >= 33 ? Api33.uniqueId(node) : node.getExtras().getString("androidx.view.accessibility.AccessibilityNodeInfoCompat.UNIQUE_ID_KEY");
    }
    public static void backgroundLaunchAllowed(ActivityOptions options, boolean allowed) {
        if (Build.VERSION.SDK_INT >= 33) Api33.backgroundLaunchAllowed(options, allowed);
    }
    public static ActivityOptions backgroundStartMode(ActivityOptions options, int mode) {
        return Build.VERSION.SDK_INT >= 34 ? Api34.backgroundStartMode(options, mode) : options;
    }
    public static LocaleList applicationLocales(Object manager) {
        return Build.VERSION.SDK_INT >= 33 && manager != null ? Api33.applicationLocales(manager) : LocaleList.getEmptyLocaleList();
    }
    public static void applicationLocales(Object manager, LocaleList locales) {
        if (Build.VERSION.SDK_INT >= 33 && manager != null) Api33.applicationLocales(manager, locales);
    }
    @SuppressWarnings("deprecation")
    public static Object parcelable(Bundle bundle, String key, Class<?> type) {
        Object value = bundle.getParcelable(key);
        return type.isInstance(value) ? value : null;
    }
    @SuppressWarnings("deprecation")
    public static Serializable serializable(Bundle bundle, String key, Class<?> type) {
        Serializable value = bundle.getSerializable(key);
        return type.isInstance(value) ? value : null;
    }
    public static void vibrate(Vibrator vibrator, VibrationEffect effect, Object attributes) {
        if (effect == null) return;
        if (Build.VERSION.SDK_INT >= 33 && attributes != null) Api33.vibrate(vibrator, effect, attributes);
        else vibrator.vibrate(effect);
    }

    private static final class Api33 {
        static Object readParcelable(android.os.Parcel parcel, ClassLoader loader, Class<?> type) { return parcel.readParcelable(loader, type); }
        static Object readSerializable(android.os.Parcel parcel, ClassLoader loader, Class<?> type) { return parcel.readSerializable(loader, type.asSubclass(Serializable.class)); }

        static android.content.pm.ApplicationInfo applicationInfo(android.content.pm.PackageManager manager, String name, long flags) throws android.content.pm.PackageManager.NameNotFoundException { return manager.getApplicationInfo(name, android.content.pm.PackageManager.ApplicationInfoFlags.of(flags)); }
        static android.content.pm.PackageInfo packageInfo(android.content.pm.PackageManager manager, String name, long flags) throws android.content.pm.PackageManager.NameNotFoundException { return manager.getPackageInfo(name, android.content.pm.PackageManager.PackageInfoFlags.of(flags)); }
        static float durationScale() { return android.animation.ValueAnimator.getDurationScale(); }
        static boolean unregisterDurationScaleListener(Object listener) { return android.animation.ValueAnimator.unregisterDurationScaleChangeListener((android.animation.ValueAnimator.DurationScaleChangeListener) listener); }

        static void lineBreakWordStyle(TextView view, int style) { view.setLineBreakWordStyle(style); }
        static String uniqueId(AccessibilityNodeInfo node) { return node.getUniqueId(); }
        static void backgroundLaunchAllowed(ActivityOptions options, boolean allowed) { options.setPendingIntentBackgroundActivityLaunchAllowed(allowed); }
        static LocaleList applicationLocales(Object manager) { return ((android.app.LocaleManager) manager).getApplicationLocales(); }
        static void applicationLocales(Object manager, LocaleList locales) { ((android.app.LocaleManager) manager).setApplicationLocales(locales); }
        static void vibrate(Vibrator vibrator, VibrationEffect effect, Object attributes) { vibrator.vibrate(effect, (android.os.VibrationAttributes) attributes); }
    }
    private static final class Api34 {
        static int systemOverlays() { return android.view.WindowInsets.Type.systemOverlays(); }

        static float deriveDimension(int unit, float value, android.util.DisplayMetrics metrics) { return android.util.TypedValue.deriveDimension(unit, value, metrics); }
        static float axisVelocity(android.view.VelocityTracker tracker, int axis) { return tracker.getAxisVelocity(axis); }
        static int minimumFling(android.view.ViewConfiguration config, int device, int axis, int source) { return config.getScaledMinimumFlingVelocity(device, axis, source); }
        static int maximumFling(android.view.ViewConfiguration config, int device, int axis, int source) { return config.getScaledMaximumFlingVelocity(device, axis, source); }
        static void boundsInWindow(AccessibilityNodeInfo node, android.graphics.Rect out) { node.getBoundsInWindow(out); }
        static boolean hasGainmap(android.graphics.Bitmap bitmap) { return bitmap.hasGainmap(); }

        static ActivityOptions backgroundStartMode(ActivityOptions options, int mode) { return options.setPendingIntentBackgroundActivityStartMode(mode); }
    }
    private static final class Api35 {
        static boolean transitioningToPip(android.app.PictureInPictureUiState state) { return state.isTransitioningToPip(); }
        static void frameVelocity(View view, float velocity) { view.setFrameContentVelocity(velocity); }
        static Object scrollProvider(View view) { return android.view.ScrollFeedbackProvider.createProvider(view); }
        static void scrollProgress(Object provider, int device, int source, int axis, int delta) { ((android.view.ScrollFeedbackProvider) provider).onScrollProgress(device, source, axis, delta); }
        static void scrollLimit(Object provider, int device, int source, int axis, boolean start) { ((android.view.ScrollFeedbackProvider) provider).onScrollLimit(device, source, axis, start); }
    }
    private static final class Api36 {
        static void requestedFrameRate(View view, float rate) { view.setRequestedFrameRate(rate); }
    }
}
