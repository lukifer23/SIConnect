package dev.siconnect.app.capabilities

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.DocumentsContract
import dev.siconnect.app.RuntimeGraph
import dev.siconnect.app.data.DocumentRoot
import dev.siconnect.app.data.DocumentRoots
import dev.siconnect.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference

fun registerDocumentCapabilities(g: RuntimeGraph) {
    DocumentOperations(g.context.contentResolver, DocumentRoots(g.context, g.db)).capabilities().forEach(g.registry::register)
}

/** Tree grants + a verified relative descendant chain, never caller-supplied raw URIs/document IDs. */
internal class DocumentOperations(private val resolver: ContentResolver, private val roots: DocumentRoots) {
    private val lock = Mutex()
    private val projection = arrayOf("document_id", "_display_name", "mime_type", "_size", "last_modified", "flags")
    private data class Node(val root: DocumentRoot, val path: String, val id: String, val name: String, val mime: String, val size: Long?, val modified: Long?, val flags: Long) {
        val tree: Uri get() = Uri.parse(root.treeUri)
        val uri: Uri get() = DocumentsContract.buildDocumentUriUsingTree(tree, id)
        val directory: Boolean get() = mime == DocumentsContract.Document.MIME_TYPE_DIR
        fun json() = buildJsonObject {
            put("storage", "SAF_DOCUMENT"); put("rootId", root.id); put("path", path); put("name", name); put("mimeType", mime); put("directory", directory)
            size?.let { put("sizeBytes", it) }; modified?.let { put("modifiedAtMs", it) }; put("providerFlags", flags)
            put("syncStatus", "PROVIDER_LOCAL_STATE_ONLY")
        }
    }
    private class Io(val signal: CancellationSignal, val active: () -> Unit, val stream: AtomicReference<Closeable?>) {
        fun own(file: Closeable) { stream.set(file); active() }
    }
    private fun Cursor.string(name: String) = getString(getColumnIndexOrThrow(name))
    private fun Cursor.optionalLong(name: String): Long? = getColumnIndex(name).takeIf { it >= 0 && !isNull(it) }?.let(::getLong)
    private fun node(c: Cursor, root: DocumentRoot, path: String) = Node(root, path, c.string("document_id"), c.string("_display_name"), c.string("mime_type"), c.optionalLong("_size"), c.optionalLong("last_modified"), c.optionalLong("flags") ?: 0)
    private fun query(uri: Uri, io: Io): Cursor = resolver.query(uri, projection, null, null, null, io.signal)
        ?: throw CapabilityException("PROVIDER_UNAVAILABLE", "Document provider returned no cursor")
    private fun requireRoot(id: String, write: Boolean): DocumentRoot {
        val root = roots.get(id) ?: throw CapabilityException("DOCUMENT_ROOT_NOT_FOUND", "Select a document folder locally before using its rootId")
        val access = roots.access(root)
        if (!access.read || write && !access.write) throw CapabilityException("PERMISSION_REQUIRED", "The saved folder grant was revoked or does not allow this operation; select the folder again locally")
        if (!access.providerAvailable) throw CapabilityException("PROVIDER_UNAVAILABLE", "The selected document provider is unavailable")
        return root
    }
    private fun resolve(root: DocumentRoot, path: String, io: Io): Node {
        val parts = DocumentRules.parts(path)
        val tree = Uri.parse(root.treeUri)
        var current = query(DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)), io).use { c ->
            if (!c.moveToFirst()) throw CapabilityException("DOCUMENT_NOT_FOUND", "Selected root is no longer available")
            node(c, root, "")
        }
        for (part in parts) {
            io.active()
            if (!current.directory) throw CapabilityException("NOT_A_DIRECTORY", "A document ancestor is not a directory")
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, current.id)
            val parent = current.path
            current = query(children, io).use { c ->
                var match: Node? = null; var visited = 0
                while (c.moveToNext()) {
                    if (++visited > 2048) throw CapabilityException("DIRECTORY_SCAN_LIMIT", "Directory exceeds the 2048-row resolution budget; use a narrower owner-selected root")
                    if (c.string("_display_name") == part) {
                        if (match != null) throw CapabilityException("AMBIGUOUS_DOCUMENT", "Provider returned duplicate names; select a more specific folder")
                        match = node(c, root, if (parent.isEmpty()) part else "$parent/$part")
                    }
                }
                match ?: throw CapabilityException("DOCUMENT_NOT_FOUND", "Document was not found beneath the selected root")
            }
        }
        return current
    }
    private fun file(n: Node) { if (n.directory) throw CapabilityException("NOT_A_FILE", "This operation requires a document file") }
    private fun supported(n: Node, flag: Int) { if (n.flags and flag.toLong() == 0L) throw CapabilityException("PROVIDER_OPERATION_UNSUPPORTED", "The document provider does not advertise this operation") }
    private fun readBytes(n: Node, limit: Int, io: Io): Pair<ByteArray, Boolean> {
        file(n)
        val pfd = resolver.openFileDescriptor(n.uri, "r", io.signal) ?: throw CapabilityException("DOCUMENT_UNREADABLE", "Document could not be opened")
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
            io.own(input)
            val buffer = ByteArray(limit + 1); var count = 0
            while (count < buffer.size) { io.active(); val size = input.read(buffer, count, buffer.size - count); if (size < 0) break; count += size }
            return buffer.copyOf(count.coerceAtMost(limit)) to (count > limit)
        }
    }
    private fun digest(n: Node, io: Io): String {
        file(n)
        if (n.size != null && n.size > 16 * 1024 * 1024) throw CapabilityException("DOCUMENT_TOO_LARGE", "Hash verification limit is 16 MiB")
        val digest = MessageDigest.getInstance("SHA-256"); var total = 0L
        val pfd = resolver.openFileDescriptor(n.uri, "r", io.signal) ?: throw CapabilityException("DOCUMENT_UNREADABLE", "Document could not be opened")
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
            io.own(input); val buffer = ByteArray(65536)
            while (true) { io.active(); val count = input.read(buffer); if (count < 0) break; total += count
                if (total > 16 * 1024 * 1024) throw CapabilityException("DOCUMENT_TOO_LARGE", "Document exceeds the 16 MiB hash limit")
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun write(n: Node, bytes: ByteArray, io: Io) {
        file(n); supported(n, DocumentsContract.Document.FLAG_SUPPORTS_WRITE)
        val pfd = resolver.openFileDescriptor(n.uri, "wt", io.signal) ?: throw CapabilityException("DOCUMENT_UNWRITABLE", "Document could not be opened for replacement")
        ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { output -> io.own(output); io.active(); output.write(bytes); output.flush() }
        val expected = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val current = resolve(n.root, n.path, io)
        if (current.id != n.id || digest(current, io) != expected) throw CapabilityException("POSTCONDITION_FAILED", "Document identity or content differs from local readback; do not blindly retry this mutation")
    }
    private fun schema(rootOnly: Boolean = false, extra: ObjectSchemaBuilder.() -> Unit = {}) = objectSchema {
        string("rootId", "Stable owner-selected document root handle", true, maxLength = 100)
        if (!rootOnly) string("path", "Relative descendant display-name path; empty means selected root", maxLength = 1000)
        extra()
    }
    fun capabilities(): List<Capability> {
        val outSchema = objectSchema {
            string("storage", "Storage identity", true); string("syncStatus", "Provider-local observation, not a cloud acknowledgement", true)
            obj("document", "Typed document handle and local metadata"); string("rootId", "Selected root handle"); string("path", "Relative path")
            for (name in listOf("roots", "entries")) prop(name, false, buildJsonObject { put("type", "array"); putJsonObject("items") { put("type", "object") }; put("maxItems", 500) })
            boolean("hasMore", "Further provider rows exist"); integer("nextOffset", "Bounded continuation offset")
            string("encoding", "Content encoding"); string("content", "Bounded content"); integer("bytesReturned", "Returned source bytes"); boolean("truncated", "Source content exceeded requested bytes")
            integer("utf8BoundaryBytesDropped", "Incomplete trailing codepoint bytes omitted"); string("sha256", "Content hash"); boolean("deleted", "Deletion absence checked")
            string("writeStatus", "Local document provider write status")
        }
        fun result(fields: JsonObject, mutation: Boolean = false) = CapabilityResult(buildJsonObject {
            put("storage", "SAF_DOCUMENT"); put("syncStatus", "PROVIDER_LOCAL_STATE_ONLY"); fields.forEach { (k, v) -> put(k, v) }
            if (mutation) put("writeStatus", "LOCAL_PROVIDER_WRITE")
        }, BackendKind.NATIVE, "uid=${Process.myUid()}", if (mutation) VerificationStatus.VERIFIED else VerificationStatus.NOT_APPLICABLE,
            if (mutation) "Local provider postcondition checked; no cloud synchronization, invitation or human visibility inferred" else null)
        fun op(id: String, title: String, input: JsonObject, mutation: Boolean = false, body: (JsonObject, Io) -> CapabilityResult): Capability = object : Capability {
            override val descriptor = CapabilityDescriptor(id, title = title,
                description = "$title through an owner-selected Storage Access Framework folder. Uses rootId plus a validated relative descendant path; arbitrary URIs and filesystem paths are rejected. Provider-local state does not prove cloud synchronization. No shell fallback.",
                inputSchema = input, outputSchema = outSchema, backend = BackendKind.NATIVE, risk = if (mutation) RiskClass.MODERATE_IMPACT else RiskClass.READ_ONLY,
                interaction = InteractionMode.BACKGROUND, sideEffects = if (mutation) listOf("Changes a document in an explicitly owner-selected folder; provider may synchronize it externally") else emptyList())
            override fun availability() = if (id == "documents.roots" || roots.all().any { roots.access(it).let { access -> access.read && (!mutation || access.write) && access.providerAvailable } }) Availability.IMPLEMENTED else Availability.PERMISSION_REQUIRED
            override fun availabilityDetail() = if (availability() == Availability.IMPLEMENTED) null else "Select a document folder in SIConnect; saved grants may be revoked or their provider unavailable"
            override fun assess(args: JsonObject) = RiskAssessment(descriptor.risk, title, "root=${args["rootId"] ?: "saved roots"}; path=${args["path"] ?: ""}", descriptor.sideEffects, externalCommunication = mutation)
            override suspend fun execute(args: JsonObject, ctx: ExecutionContext): CapabilityResult = coroutineScope {
                val resource = AtomicReference<Closeable?>(); val signal = CancellationSignal(); val job = currentCoroutineContext().job
                val cancellation = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { signal.cancel(); runCatching { resource.getAndSet(null)?.close() } } }
                try { withContext(Dispatchers.IO) { lock.withLock {
                    try { body(args, Io(signal, { job.ensureActive() }, resource)) }
                    catch (_: SecurityException) { throw CapabilityException("PERMISSION_REQUIRED", "The selected document grant was revoked or denied") }
                    catch (_: java.io.FileNotFoundException) { throw CapabilityException("DOCUMENT_UNAVAILABLE", "Document or provider is unavailable; inspect the root and original request before retrying") }
                } } } finally { cancellation.cancel(); runCatching { resource.getAndSet(null)?.close() } }
            }
        }
        fun selected(a: JsonObject, write: Boolean, io: Io) = resolve(requireRoot(a.str("rootId"), write), a["path"]?.jsonPrimitive?.content ?: "", io)
        fun nonRoot(n: Node) { if (n.path.isEmpty()) throw CapabilityException("ROOT_MUTATION_DENIED", "The owner-selected root itself cannot be renamed, overwritten or deleted") }
        return listOf(
            op("documents.roots", "List owner-selected document roots and actual grant state", objectSchema()) { _, _ -> result(buildJsonObject {
                put("roots", JsonArray(roots.all().map { root -> val access = roots.access(root); buildJsonObject {
                    put("rootId", root.id); put("label", root.label); put("read", access.read); put("write", access.write); put("state", access.state)
                } }))
            }) },
            op("documents.list", "List bounded child document metadata", schema { integer("limit", "Maximum entries", min = 1, max = 500); integer("offset", "Provider-order continuation; unstable if directory changes", min = 0, max = 10000) }) { a, io ->
                val n = selected(a, false, io); if (!n.directory) throw CapabilityException("NOT_A_DIRECTORY", "Selected path is not a directory")
                val limit = a.long("limit", 100).toInt(); val offset = a.long("offset", 0).toInt()
                val entries = query(DocumentsContract.buildChildDocumentsUriUsingTree(n.tree, n.id), io).use { c -> buildList {
                    if (offset == 0 || c.moveToPosition(offset - 1)) while (size <= limit && c.moveToNext()) {
                        val name = c.string("_display_name")
                        val child = node(c, n.root, if (n.path.isEmpty()) name else "${n.path}/$name")
                        add(child.json().let { data -> JsonObject(data + ("pathSupported" to JsonPrimitive(runCatching { DocumentRules.parts(child.path) }.isSuccess))) })
                    }
                } }
                result(buildJsonObject { put("entries", JsonArray(entries.take(limit))); put("hasMore", entries.size > limit); if (entries.size > limit && offset + limit <= 10000) put("nextOffset", offset + limit) })
            },
            op("documents.metadata", "Read selected document metadata", schema()) { a, io -> result(buildJsonObject { put("document", selected(a, false, io).json()) }) },
            op("documents.read", "Read bounded document bytes", schema { integer("maxBytes", "Maximum source bytes", min = 1, max = 131072); string("encoding", "Binary-safe default base64", enum = listOf("base64", "utf8")) }) { a, io ->
                val n = selected(a, false, io); val (bytes, truncated) = readBytes(n, a.long("maxBytes", 65536).toInt(), io)
                val encoding = a["encoding"]?.jsonPrimitive?.content ?: "base64"
                val text = if (encoding == "utf8") DocumentRules.utf8(bytes, truncated) else Base64.getEncoder().encodeToString(bytes) to 0
                result(buildJsonObject { put("document", n.json()); put("encoding", encoding); put("content", text.first); put("bytesReturned", bytes.size - text.second); put("utf8BoundaryBytesDropped", text.second); put("truncated", truncated) })
            },
            op("documents.hash", "Hash a bounded document with SHA-256", schema()) { a, io -> val n = selected(a, false, io); result(buildJsonObject { put("document", n.json()); put("sha256", digest(n, io)) }) },
            op("documents.create", "Create an empty document or directory", schema { string("name", "Single new display name", true, maxLength = 200); string("mimeType", "Explicit MIME type; vnd.android.document/directory creates a folder", true, maxLength = 150) }, true) { a, io ->
                val parent = selected(a, true, io); if (!parent.directory) throw CapabilityException("NOT_A_DIRECTORY", "Creation needs a parent directory")
                supported(parent, DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE); val name = a.str("name"); DocumentRules.name(name)
                val mime = a.str("mimeType"); if (!mime.matches(Regex("[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]*/[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]*"))) throw CapabilityException("INVALID_MIME_TYPE", "Supply an explicit MIME type")
                // Refuse an existing display name, instead of relying on provider-specific duplicate naming.
                val path = if (parent.path.isEmpty()) name else "${parent.path}/$name"
                try { resolve(parent.root, path, io); throw CapabilityException("ALREADY_EXISTS", "A child with that name already exists") } catch (e: CapabilityException) { if (e.code != "DOCUMENT_NOT_FOUND") throw e }
                val uri = DocumentsContract.createDocument(resolver, parent.uri, mime, name) ?: throw CapabilityException("PROVIDER_WRITE_FAILED", "Document creation returned no URI")
                val created = query(uri, io).use { c -> if (!c.moveToFirst()) throw CapabilityException("POSTCONDITION_FAILED", "Created document could not be read back"); node(c, parent.root, if (parent.path.isEmpty()) c.string("_display_name") else "${parent.path}/${c.string("_display_name")}") }
                val verified = resolve(parent.root, created.path, io)
                if (verified.id != created.id) throw CapabilityException("POSTCONDITION_FAILED", "Created handle did not resolve to the returned document")
                result(buildJsonObject { put("document", verified.json()) }, true)
            },
            op("documents.write", "Replace a document with bounded UTF-8 or base64 bytes", schema { string("content", "Replacement content", true, maxLength = 16000); string("encoding", "Content encoding", enum = listOf("utf8", "base64")); boolean("overwrite", "Must explicitly be true to replace content", true) }, true) { a, io ->
                val n = selected(a, true, io); nonRoot(n)
                if (a["overwrite"]?.jsonPrimitive?.booleanOrNull != true) throw CapabilityException("OVERWRITE_REQUIRED", "Explicitly set overwrite=true; replacement is not atomic across arbitrary providers")
                val bytes = if (a["encoding"]?.jsonPrimitive?.content == "base64") try { Base64.getDecoder().decode(a.str("content")) } catch (_: IllegalArgumentException) { throw CapabilityException("INVALID_ENCODING", "Invalid base64 content") } else a.str("content").toByteArray(Charsets.UTF_8)
                write(n, bytes, io)
                val current = resolve(n.root, n.path, io)
                result(buildJsonObject { put("document", current.json()); put("sha256", digest(current, io)) }, true)
            },
            op("documents.rename", "Rename a document within its selected parent", schema { string("name", "New single display name", true, maxLength = 200) }, true) { a, io ->
                val n = selected(a, true, io); nonRoot(n); supported(n, DocumentsContract.Document.FLAG_SUPPORTS_RENAME)
                val name = a.str("name"); DocumentRules.name(name); val parent = n.path.substringBeforeLast('/', "")
                val destination = if (parent.isEmpty()) name else "$parent/$name"
                if (destination == n.path) return@op result(buildJsonObject { put("document", n.json()) }, true)
                try { resolve(n.root, destination, io); throw CapabilityException("ALREADY_EXISTS", "Rename destination already exists") } catch (e: CapabilityException) { if (e.code != "DOCUMENT_NOT_FOUND") throw e }
                val uri = DocumentsContract.renameDocument(resolver, n.uri, name) ?: throw CapabilityException("PROVIDER_WRITE_FAILED", "Provider returned no renamed document URI")
                val renamed = query(uri, io).use { c -> if (!c.moveToFirst()) throw CapabilityException("POSTCONDITION_FAILED", "Renamed document could not be read back"); node(c, n.root, if (parent.isEmpty()) c.string("_display_name") else "$parent/${c.string("_display_name")}") }
                val verified = resolve(n.root, renamed.path, io)
                if (verified.id != renamed.id) throw CapabilityException("POSTCONDITION_FAILED", "Renamed path resolves to a different document")
                result(buildJsonObject { put("document", verified.json()) }, true)
            },
            op("documents.delete", "Delete a document file or empty directory", schema(), true) { a, io ->
                val n = selected(a, true, io); nonRoot(n); supported(n, DocumentsContract.Document.FLAG_SUPPORTS_DELETE)
                if (n.directory) query(DocumentsContract.buildChildDocumentsUriUsingTree(n.tree, n.id), io).use { if (it.moveToFirst()) throw CapabilityException("DIRECTORY_NOT_EMPTY", "Recursive deletion is not supported") }
                if (!DocumentsContract.deleteDocument(resolver, n.uri)) throw CapabilityException("PROVIDER_WRITE_FAILED", "Provider did not confirm deletion")
                try { resolve(n.root, n.path, io); throw CapabilityException("POSTCONDITION_FAILED", "Deleted document remains beneath the root") } catch (e: CapabilityException) { if (e.code != "DOCUMENT_NOT_FOUND") throw e }
                result(buildJsonObject { put("rootId", n.root.id); put("path", n.path); put("deleted", true) }, true)
            },
        )
    }
}

internal object DocumentRules {
    fun name(value: String) {
        if (value.isBlank() || value.length > 200 || value == "." || value == ".." || value.any { it == '/' || it == '\\' || it.code < 32 } || value.startsWith("content:") || value.startsWith("file:"))
            throw CapabilityException("INVALID_DOCUMENT_PATH", "Use a single nonempty document name without slash, dot segments, URI or control characters")
    }
    fun parts(path: String): List<String> {
        if (path.isEmpty()) return emptyList()
        if (path.length > 1000 || path.startsWith('/') || path.endsWith('/') || path.split('/').size > 16) throw CapabilityException("INVALID_DOCUMENT_PATH", "Relative document paths are limited to 16 segments and 1000 characters")
        return path.split('/').onEach(::name)
    }
    fun utf8(bytes: ByteArray, truncated: Boolean): Pair<String, Int> {
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val input = ByteBuffer.wrap(bytes); val output = CharBuffer.allocate(bytes.size)
        val result = decoder.decode(input, output, !truncated)
        if (result.isError || result.isOverflow) throw CapabilityException("INVALID_UTF8", "Document contains invalid UTF-8; read it as base64")
        output.flip()
        return output.toString() to input.remaining()
    }
}
