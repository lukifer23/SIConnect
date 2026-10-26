package dev.siconnect.app;

import android.app.Instrumentation;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import org.json.JSONObject;
import org.json.JSONArray;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Framework-only private-checkpoint readback. No dependency on the target's unminified Kotlin ABI. */
public final class InstallationCheckpointInstrumentation extends Instrumentation {
    private Bundle arguments;

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        arguments = args;
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            String raw = arguments.getString("installationExpectedRecords");
            if (raw == null) throw new IllegalArgumentException("Private pre-update checkpoint required");
            JSONObject expected = new JSONObject(raw);
            try (SQLiteDatabase db = SQLiteDatabase.openDatabase(
                    getTargetContext().getDatabasePath("siconnect.db").getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
                if (db.getVersion() != 10) throw new IllegalStateException("Unexpected schema");
                try (Cursor c = db.rawQuery("PRAGMA quick_check", null)) {
                    if (!c.moveToFirst() || !"ok".equals(c.getString(0))) throw new IllegalStateException("Integrity check failed");
                }
                for (String table : new String[]{"agents", "reminders", "automations", "document_roots"}) {
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    try (Cursor c = db.rawQuery("SELECT * FROM " + table + " ORDER BY 1", null)) {
                        while (c.moveToNext()) for (int column = 0; column < c.getColumnCount(); column++) {
                            byte[] bytes = c.isNull(column) ? null : c.getString(column).getBytes(StandardCharsets.UTF_8);
                            digest.update(ByteBuffer.allocate(4).putInt(bytes == null ? -1 : bytes.length).array());
                            if (bytes != null) digest.update(bytes);
                        }
                    }
                    StringBuilder hex = new StringBuilder();
                    for (byte value : digest.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
                    if (!expected.getString(table).equals(hex.toString())) throw new IllegalStateException("Protected rows changed: " + table);
                }
                // Optional bounded metadata only: never export arguments, output bodies or credentials.
                JSONArray ids = new JSONArray(arguments.getString("requestIds", "[]"));
                if (ids.length() > 20) throw new IllegalArgumentException("At most twenty request IDs");
                JSONArray metadata = new JSONArray();
                for (int i = 0; i < ids.length(); i++) {
                    String id = ids.getString(i);
                    try (Cursor c = db.rawQuery("SELECT json FROM requests WHERE request_id=?", new String[]{id})) {
                        if (!c.moveToFirst()) throw new IllegalStateException("Requested execution is absent");
                        JSONObject record = new JSONObject(c.getString(0));
                        JSONObject item = new JSONObject();
                        for (String field : new String[]{"requestId", "capabilityId", "state"}) item.put(field, record.getString(field));
                        item.put("lifecycleMs", record.getLong("updatedAtMs") - record.getLong("createdAtMs"));
                        JSONObject execution = record.optJSONObject("result");
                        if (execution != null) {
                            item.put("backend", execution.optString("backend"));
                            item.put("identity", execution.optString("identity"));
                        }
                        try (Cursor transitions = db.rawQuery("SELECT count(*) FROM request_transitions WHERE request_id=? AND to_state='EXECUTING'", new String[]{id})) {
                            transitions.moveToFirst();item.put("executions", transitions.getInt(0));
                        }
                        metadata.put(item);
                    }
                }
                result.putString("requestMetadata", metadata.toString());
            }
            result.putString("checkpoint", "passed");
            finish(0, result);
        } catch (Throwable error) {
            result.putString("checkpoint", "failed");
            result.putString("error", error.getClass().getSimpleName() + ": " + error.getMessage());
            finish(1, result);
        }
    }
}
