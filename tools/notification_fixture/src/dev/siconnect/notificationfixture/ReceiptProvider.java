package dev.siconnect.notificationfixture;

import android.content.*;
import android.database.*;
import android.net.Uri;

/** Read-only disposable test metadata. No content, mutation endpoint or production data. */
public final class ReceiptProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        if (!"/receipt".equals(uri.getPath())) throw new IllegalArgumentException("Unknown fixture resource");
        SharedPreferences prefs = getContext().getSharedPreferences("receipt", Context.MODE_PRIVATE);
        MatrixCursor cursor = new MatrixCursor(new String[]{"count", "kind", "digest", "remoteInput"});
        cursor.addRow(new Object[]{prefs.getInt("count", 0), prefs.getString("kind", ""), prefs.getString("digest", ""), prefs.getBoolean("remoteInput", false) ? 1 : 0});
        return cursor;
    }
    @Override public String getType(Uri uri) { return "vnd.android.cursor.item/vnd.siconnect.fixture-receipt"; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("Read only"); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException("Read only"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException("Read only"); }
}
