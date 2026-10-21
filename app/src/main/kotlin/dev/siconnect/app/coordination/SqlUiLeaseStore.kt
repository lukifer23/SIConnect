package dev.siconnect.app.coordination

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import dev.siconnect.app.data.AppJson
import dev.siconnect.app.data.SIConnectDb

/** Single device-wide row; the schema migration belongs to SIConnectDb's versioned migrations. */
class SqlUiLeaseStore(private val db: SIConnectDb) : UiLeaseStore {
    override fun <T> atomic(block: () -> T): T {
        val w = db.writableDatabase
        w.beginTransaction()
        try { val result = block(); w.setTransactionSuccessful(); return result }
        finally { w.endTransaction() }
    }
    override fun get(): UiLease? = db.readableDatabase.query("ui_lease", arrayOf("json"), "singleton=1",
        null, null, null, null).use { if (it.moveToFirst()) AppJson.decodeFromString(UiLease.serializer(), it.getString(0)) else null }
    override fun put(lease: UiLease) {
        db.writableDatabase.insertWithOnConflict("ui_lease", null, ContentValues().apply {
            put("singleton", 1); put("json", AppJson.encodeToString(UiLease.serializer(), lease))
        }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) { "Unable to persist UI lease" } }
    }
    override fun clear() { db.writableDatabase.delete("ui_lease", "singleton=1", null) }
}
