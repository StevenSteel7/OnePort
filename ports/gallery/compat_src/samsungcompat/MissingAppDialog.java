package samsungcompat;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;

/**
 * Gallery's "download the missing app" dialog sends users to Samsung's Galaxy Store, which other phones don't
 * have, so it only said "Can't download right now". Ask the user to install the app themselves instead.
 */
public final class MissingAppDialog {
    private static final String PHOTO_EDITOR = "com.sec.android.mimage.photoretouching";

    private MissingAppDialog() {}

    public static AlertDialog create(Context context, String packageName, String appName,
            DialogInterface.OnClickListener onClose) {
        String title;
        String message;
        if (PHOTO_EDITOR.equals(packageName)) {
            title = "Galaxy Editor not installed";
            message = "Kindly install Galaxy Editor to edit photos.";
        } else {
            String name = appName == null || appName.isEmpty() ? "the required app" : appName;
            title = name + " not installed";
            message = "Kindly install " + name + " to use this feature.";
        }
        return new AlertDialog.Builder(context).setTitle(title).setMessage(message)
                .setPositiveButton(android.R.string.ok, onClose).create();
    }
}
