package samsungcompat;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

/**
 * "Set as wallpaper" for non-Samsung devices.
 *
 * Gallery hands the image to Samsung's Wallpaper and style app
 * (com.samsung.android.app.dressroom.action.SET_AS_WALLPAPER), which other
 * phones do not have, so the command failed silently. When that intent has
 * no handler, use the standard WallpaperManager crop-and-set flow, falling
 * back to the "Use as" (ATTACH_DATA) chooser.
 */
public final class WallpaperCompat {
    private static final String TAG = "WallpaperCompat";

    private WallpaperCompat() {}

    public static void start(Activity activity, Intent intent) {
        if (activity.getPackageManager().resolveActivity(intent, 0) != null) {
            activity.startActivity(intent);
            return;
        }
        fallback(activity, intent);
    }

    public static void startForResult(Activity activity, Intent intent, int requestCode) {
        if (activity.getPackageManager().resolveActivity(intent, 0) != null) {
            activity.startActivityForResult(intent, requestCode);
            return;
        }
        fallback(activity, intent);
    }

    private static void fallback(Activity activity, Intent samsung) {
        Uri uri = samsung.getData();
        String type = samsung.getType();
        if (uri == null) {
            // Multi-pack sends the images as a stream list; use the first.
            java.util.ArrayList<Uri> uris = samsung.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (uris != null && !uris.isEmpty()) uri = uris.get(0);
        }
        if (uri == null || (type != null && !type.startsWith("image/"))) {
            Toast.makeText(activity, "This wallpaper type isn't supported on this phone",
                    Toast.LENGTH_SHORT).show();
            Log.w(TAG, "no wallpaper handler for " + samsung);
            return;
        }
        try {
            Intent crop = WallpaperManager.getInstance(activity).getCropAndSetWallpaperIntent(uri);
            crop.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(crop);
            Log.i(TAG, "crop and set wallpaper: " + uri);
            return;
        } catch (IllegalArgumentException | ActivityNotFoundException e) {
            Log.w(TAG, "crop-and-set unavailable", e);
        }
        Intent attach = new Intent(Intent.ACTION_ATTACH_DATA)
                .setDataAndType(uri, type != null ? type : "image/*")
                .putExtra("mimeType", type != null ? type : "image/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        activity.startActivity(Intent.createChooser(attach, null));
    }
}
