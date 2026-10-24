package samsungcompat;

import android.content.Context;
import android.content.pm.PackageManager;

/** Is an app installed (and enabled) on this phone? Gallery declares QUERY_ALL_PACKAGES, so this sees every app. */
public final class PackageCompat {
    private PackageCompat() {}

    public static boolean isInstalled(String packageName) {
        try {
            Context context = (Context) Class.forName("com.samsung.android.gallery.support.utils.AppResources")
                    .getMethod("getAppContext").invoke(null);
            if (context == null) return true;   // can't tell: keep Gallery's own behaviour
            return context.getPackageManager().getApplicationInfo(packageName, 0).enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } catch (Exception e) {
            return true;
        }
    }
}
