package dev.siconnect.app.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.DocumentsContract
import dev.siconnect.core.CapabilityException
import java.util.UUID

data class DocumentRoot(val id: String, val treeUri: String, val label: String, val createdAtMs: Long)
data class DocumentRootAccess(val read: Boolean, val write: Boolean, val providerAvailable: Boolean) {
    val state: String get() = when { !read -> "PERMISSION_REQUIRED"; !providerAvailable -> "PROVIDER_UNAVAILABLE"; else -> "READY" }
}

/** Only trusted owner UI calls add/remove. MCP capabilities expose handles, never arbitrary URI grants. */
class DocumentRoots(private val context: Context, private val db: SIConnectDb) {
    fun all(): List<DocumentRoot> = db.readableDatabase.query("document_roots", null, null, null, null, null, "created_at ASC", "32").mapAll {
        DocumentRoot(it.getString(it.getColumnIndexOrThrow("root_id")), it.getString(it.getColumnIndexOrThrow("tree_uri")), it.getString(it.getColumnIndexOrThrow("label")), it.getLong(it.getColumnIndexOrThrow("created_at")))
    }
    fun get(id: String): DocumentRoot? = db.readableDatabase.query("document_roots", null, "root_id=?", arrayOf(id), null, null, null, "1").mapAll {
        DocumentRoot(it.getString(it.getColumnIndexOrThrow("root_id")), it.getString(it.getColumnIndexOrThrow("tree_uri")), it.getString(it.getColumnIndexOrThrow("label")), it.getLong(it.getColumnIndexOrThrow("created_at")))
    }.firstOrNull()

    @Synchronized fun add(uri: Uri): DocumentRoot {
        if (uri.scheme != "content" || uri.authority.isNullOrBlank() || !DocumentsContract.isTreeUri(uri))
            throw CapabilityException("INVALID_DOCUMENT_ROOT", "Select a folder with Android's document picker")
        val existing = all().firstOrNull { it.treeUri == uri.toString() }
        if (existing == null && all().size >= 32) throw CapabilityException("ROOT_LIMIT", "Remove an unused document root before adding another")
        val resolver = context.contentResolver
        val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val write = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val flags = (if (context.checkUriPermission(uri, Process.myPid(), Process.myUid(), read) == PackageManager.PERMISSION_GRANTED) read else 0) or
            (if (context.checkUriPermission(uri, Process.myPid(), Process.myUid(), write) == PackageManager.PERMISSION_GRANTED) write else 0)
        if (flags and read == 0) throw CapabilityException("PERMISSION_REQUIRED", "The owner-selected document root needs a read grant")
        resolver.takePersistableUriPermission(uri, flags)
        val document = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
        val label = resolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use {
            if (!it.moveToFirst() || it.getString(1) != DocumentsContract.Document.MIME_TYPE_DIR) throw CapabilityException("NOT_A_DIRECTORY", "Choose a document directory")
            it.getString(0)?.take(200) ?: "Selected folder"
        } ?: throw CapabilityException("PROVIDER_UNAVAILABLE", "Selected document provider did not return folder metadata")
        val w = db.writableDatabase
        w.beginTransaction()
        try {
            // Re-read inside SQLite ownership: two owner callbacks must not replace one stable identity.
            val current = all().firstOrNull { it.treeUri == uri.toString() }
            if (current == null && all().size >= 32) throw CapabilityException("ROOT_LIMIT", "Remove an unused document root first")
            val root = DocumentRoot(current?.id ?: UUID.randomUUID().toString(), uri.toString(), label, current?.createdAtMs ?: System.currentTimeMillis())
            w.insertWithOnConflict("document_roots", null, ContentValues().apply {
                put("root_id", root.id); put("tree_uri", root.treeUri); put("label", root.label); put("created_at", root.createdAtMs)
            }, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE).also { if (it == -1L) throw CapabilityException("STORAGE_ERROR", "Document root could not be saved") }
            w.setTransactionSuccessful()
            return root
        } finally { w.endTransaction() }
    }

    @Synchronized fun remove(id: String) {
        val root = get(id) ?: return
        // Remove the exposed handle first: a release failure must never leave a remote-usable root.
        db.writableDatabase.delete("document_roots", "root_id=?", arrayOf(id))
        val uri = Uri.parse(root.treeUri)
        val persisted = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri } ?: return
        val flags = (if (persisted.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or (if (persisted.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        if (flags != 0) context.contentResolver.releasePersistableUriPermission(uri, flags)
    }

    fun access(root: DocumentRoot): DocumentRootAccess {
        val uri = Uri.parse(root.treeUri)
        val grant = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }
        val available = try { context.contentResolver.acquireUnstableContentProviderClient(uri)?.use { true } ?: false } catch (_: Exception) { false }
        return DocumentRootAccess(grant?.isReadPermission == true, grant?.isWritePermission == true, available)
    }
}
