package samsungcompat;

import android.graphics.Color;
import android.widget.TextView;

/** Recycle bin grid label: "N days" left, red when the item is about to be purged. */
public final class TrashUi {
    private static final int RED = Color.rgb(0xF2, 0x3B, 0x3B);
    private static final int URGENT_DAYS = 1;

    private TrashUi() {}

    /** Replaces TrashItemViewHolder.updateDayText's setText (gallery_patch.py). */
    public static void setDays(TextView view, String text, int days) {
        // View holders are recycled, so remember the theme colour and restore it for non-urgent items.
        Object original = view.getTag(view.getId());
        if (!(original instanceof Integer)) {
            original = view.getCurrentTextColor();
            view.setTag(view.getId(), original);
        }
        view.setText(text);
        view.setTextColor(days <= URGENT_DAYS ? RED : (Integer) original);
    }
}
