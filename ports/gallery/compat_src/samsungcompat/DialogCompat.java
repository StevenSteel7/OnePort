package samsungcompat;

import android.app.Dialog;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;

/**
 * One UI shows alert dialogs (delete confirmation, ...) at the bottom of a phone screen, close to the toolbar the
 * user just tapped. That comes from Samsung's framework dialog theme; with the stock Android theme the same dialog
 * is centred. Apply the One UI placement to Gallery's alert dialogs on phones in portrait.
 */
public final class DialogCompat {
    private static final int SIDE_MARGIN_DP = 12;
    private static final int BOTTOM_MARGIN_DP = 16;

    private DialogCompat() {}

    public static void alignToBottom(Dialog dialog) {
        try {
            String cls = dialog.getClass().getName();
            if (!"androidx.appcompat.app.AlertDialog".equals(cls) && !"android.app.AlertDialog".equals(cls)) return;
            Window window = dialog.getWindow();
            if (window == null) return;
            Resources res = dialog.getContext().getResources();
            Configuration cfg = res.getConfiguration();
            if (cfg.smallestScreenWidthDp >= 600 || cfg.orientation != Configuration.ORIENTATION_PORTRAIT) return;
            DisplayMetrics dm = res.getDisplayMetrics();
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            lp.y = Math.round(BOTTOM_MARGIN_DP * dm.density);
            lp.width = dm.widthPixels - 2 * Math.round(SIDE_MARGIN_DP * dm.density);
            window.setAttributes(lp);
        } catch (RuntimeException e) {
            android.util.Log.w("DialogCompat", "alignToBottom failed", e);
        }
    }
}
