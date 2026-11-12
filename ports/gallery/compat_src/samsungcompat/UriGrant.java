package samsungcompat;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

/**
 * Video editor launches (StartVideoTrimmerCmd / StartVideoEditorCmd) grant the editor READ|WRITE on the video.
 * On Samsung, Gallery is a privileged app that may write any media. Here it is an ordinary app, so the WRITE grant
 * throws SecurityException ("UID … does not have permission to content://media/…") and Gallery crashed on Edit.
 * The editors save a new copy, so READ is all they need.
 */
public final class UriGrant {
    private static final String TAG = "UriGrantCompat";

    private UriGrant() { }

    public static Intent addFlags(Intent intent, int flags) {
        return intent.addFlags(flags & ~Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    }

    public static void grant(Context ctx, String pkg, Uri uri, int mode) {
        try {
            ctx.grantUriPermission(pkg, uri, mode);
        } catch (SecurityException e) {
            Log.w(TAG, "write grant refused for " + uri + ", granting read only");
            try {
                ctx.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException e2) {
                Log.e(TAG, "read grant refused for " + uri, e2);
            }
        }
    }
}
