package samsungcompat;

import android.content.ContentProviderResult;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import java.util.ArrayList;

/**
 * Every ContentResolver call in the app is redirected here (see tools/patch.py).
 * - "sectrash" (Samsung's trash index) is served by {@link Trash}.
 * - Calls to other Samsung-only providers throw "Unknown authority/URL" on non-Samsung phones; that is
 *   turned into an empty result so background operations can finish instead of hanging.
 */
public final class CR {
    private CR() {}

    public static Cursor query(ContentResolver cr, Uri uri, String[] projection, String selection, String[] args, String sort) {
        if (Trash.handles(uri)) return Trash.query(cr, projection, selection, args, sort);
        try {
            return cr.query(uri, projection, selection, args, sort);
        } catch (IllegalArgumentException e) {
            return unknownProvider(e, null);
        }
    }

    public static Cursor query(ContentResolver cr, Uri uri, String[] projection, Bundle queryArgs, CancellationSignal signal) {
        if (Trash.handles(uri)) {
            Bundle a = queryArgs != null ? queryArgs : Bundle.EMPTY;
            return Trash.query(cr, projection, a.getString(ContentResolver.QUERY_ARG_SQL_SELECTION),
                    a.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS), a.getString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER));
        }
        try {
            return cr.query(uri, projection, queryArgs, signal);
        } catch (IllegalArgumentException e) {
            return unknownProvider(e, null);
        }
    }

    public static Bundle call(ContentResolver cr, Uri uri, String method, String arg, Bundle extras) {
        if (Trash.handles(uri)) return Trash.call(cr, method);
        try {
            return cr.call(uri, method, arg, extras);
        } catch (IllegalArgumentException e) {
            return unknownProvider(e, null);
        }
    }

    public static Bundle call(ContentResolver cr, String authority, String method, String arg, Bundle extras) {
        if (Trash.AUTHORITY.equals(authority)) return Trash.call(cr, method);
        try {
            return cr.call(authority, method, arg, extras);
        } catch (IllegalArgumentException e) {
            return unknownProvider(e, null);
        }
    }

    public static ContentProviderResult[] applyBatch(ContentResolver cr, String authority, ArrayList ops) throws Exception {
        if (Trash.AUTHORITY.equals(authority)) return Trash.applyBatch(cr, ops.size());
        try {
            return cr.applyBatch(authority, ops);
        } catch (IllegalArgumentException e) {
            return unknownProvider(e, new ContentProviderResult[0]);
        }
    }

    public static int bulkInsert(ContentResolver cr, Uri uri, ContentValues[] values) {
        if (Trash.handles(uri)) return 0;
        try {
            return cr.bulkInsert(uri, values);
        } catch (IllegalArgumentException e) {
            return unknownProvider(e, 0);
        }
    }

    public static int delete(ContentResolver cr, Uri uri, String where, String[] args) {
        if (Trash.handles(uri)) {
            Trash.notifyChanged(cr);
            return 0;
        }
        try {
            return cr.delete(uri, where, args);
        } catch (IllegalArgumentException e) {
            return unknownProvider(e, 0);
        }
    }

    public static Uri insert(ContentResolver cr, Uri uri, ContentValues values) {
        if (Trash.handles(uri)) return null;
        try {
            return cr.insert(uri, values);
        } catch (IllegalArgumentException e) {
            return unknownProvider(e, null);
        }
    }

    public static int update(ContentResolver cr, Uri uri, ContentValues values, String where, String[] args) {
        if (Trash.handles(uri)) return 0;
        try {
            return cr.update(uri, values, where, args);
        } catch (IllegalArgumentException e) {
            return unknownProvider(e, 0);
        }
    }

    public static void registerContentObserver(ContentResolver cr, Uri uri, boolean descendants, ContentObserver observer) {
        if (Trash.handles(uri)) {
            Trash.register(observer);
            return;
        }
        cr.registerContentObserver(uri, descendants, observer);
    }

    public static void unregisterContentObserver(ContentResolver cr, ContentObserver observer) {
        Trash.unregister(observer);
        cr.unregisterContentObserver(observer);
    }

    private static <T> T unknownProvider(IllegalArgumentException e, T fallback) {
        String msg = e.getMessage();
        if (msg != null && msg.contains("Unknown ")) return fallback;
        throw e;
    }
}
