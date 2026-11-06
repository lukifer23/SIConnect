package dev.siconnect.app.data

import android.database.sqlite.SQLiteDatabase
import java.io.ByteArrayOutputStream

/** One statement/snapshot, many small cursor rows. A result may exceed CursorWindow's row limit.
 * Chunk bytes rather than UTF-16 characters so boundaries cannot corrupt supplementary Unicode.
 * Identifiers are internal constants, never caller SQL. */
internal fun SQLiteDatabase.storedJson(table: String, keyColumn: String, key: String): String? {
    require(table in setOf("requests", "request_tombstones", "skill_runs"))
    require(keyColumn in setOf("request_id", "run_id"))
    val bytes = ByteArrayOutputStream()
    var found = false
    rawQuery("""WITH RECURSIVE payload(bytes) AS (
        SELECT CAST(json AS BLOB) FROM $table WHERE $keyColumn=?
    ), offsets(pos) AS (
        SELECT 1 UNION ALL SELECT pos+65536 FROM offsets,payload WHERE pos+65536<=length(bytes)
    ) SELECT substr(bytes,pos,65536) FROM payload,offsets ORDER BY pos""", arrayOf(key)).use { cursor ->
        while (cursor.moveToNext()) { found = true; bytes.write(cursor.getBlob(0)) }
    }
    return if (found) bytes.toString(Charsets.UTF_8.name()) else null
}
