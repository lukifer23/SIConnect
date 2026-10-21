package dev.siconnect.app.capabilities

import android.os.Process
import android.util.AtomicFile
import android.util.Base64
import dev.siconnect.app.RuntimeGraph
import dev.siconnect.core.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** App-owned managed files only. No claim of SAF, MediaStore or other apps' private storage access. */
fun registerFileCapabilities(g:RuntimeGraph) {
    val root=File(g.context.filesDir,"managed").canonicalFile
    val lock=Mutex()
    fun file(path:String,allowRoot:Boolean=false):File {
        if(path.startsWith('/') || '\u0000' in path || path.split('/').any {it==".." || it=="."}) throw CapabilityException("INVALID_PATH","Use a relative managed-file path without dot segments")
        val f=File(root,path).canonicalFile;val base=root.canonicalFile
        if((f==base && !allowRoot) || (f!=base && !f.toPath().startsWith(base.toPath()))) throw CapabilityException("INVALID_PATH","Path is outside managed storage")
        return f
    }
    fun hash(f:File):String {
        if(!f.isFile) throw CapabilityException("NOT_A_FILE","A regular file is required")
        if(f.length()>16*1024*1024) throw CapabilityException("FILE_TOO_LARGE","Hash/copy limit is 16 MiB")
        val digest=MessageDigest.getInstance("SHA-256");var total=0L
        f.inputStream().use {input -> val buffer=ByteArray(65536)
            while(true) {val n=input.read(buffer);if(n<0) break;total+=n
                if(total>16*1024*1024) throw CapabilityException("FILE_TOO_LARGE","File grew past the 16 MiB limit")
                digest.update(buffer,0,n)
            }
        }
        return digest.digest().joinToString("") {"%02x".format(it)}
    }
    fun metadata(f:File)=buildJsonObject {
        put("path",f.relativeTo(root).invariantSeparatorsPath);put("exists",f.exists());put("directory",f.isDirectory)
        put("sizeBytes",if(f.isFile) f.length() else 0);put("modifiedAtMs",f.lastModified());put("storage","APP_MANAGED")
    }
    fun schema(block:ObjectSchemaBuilder.()->Unit={})=objectSchema {string("path","Relative path within SIConnect managed storage",true,maxLength=1000);block()}
    fun register(id:String,title:String,input:JsonObject,risk:RiskClass=RiskClass.READ_ONLY,body:(JsonObject)->JsonElement) {
        g.registry.register(object:Capability {
            override val descriptor=CapabilityDescriptor(id,title=title,description="$title. App-owned SIConnect managed storage only; no access to another app's private files or Secure Folder.",inputSchema=input,
                backend=BackendKind.NATIVE,risk=risk,interaction=InteractionMode.BACKGROUND,sideEffects=if(risk>RiskClass.READ_ONLY) listOf(title) else emptyList())
            override fun availability()=Availability.IMPLEMENTED
            override suspend fun execute(args:JsonObject,ctx:ExecutionContext)=lock.withLock {
                check(root.isDirectory || root.mkdirs()) {"Managed storage could not be opened"}
                CapabilityResult(body(args),BackendKind.NATIVE,"uid=${Process.myUid()}",if(risk>RiskClass.READ_ONLY) VerificationStatus.VERIFIED else VerificationStatus.NOT_APPLICABLE,
                    if(risk>RiskClass.READ_ONLY) "Local filesystem postcondition checked; no cloud synchronization implied" else null)
            }
        })
    }
    register("files.list","List managed files",objectSchema {string("path","Relative directory; default managed root",maxLength=1000);integer("limit","Maximum entries; unsorted bounded directory traversal",min=1,max=1000)}) {a ->
        val dir=file(a["path"]?.jsonPrimitive?.content ?: "",true)
        if(!dir.isDirectory) throw CapabilityException("NOT_A_DIRECTORY","Directory does not exist")
        val limit=a.long("limit",100).toInt()
        val entries=Files.newDirectoryStream(dir.toPath()).use {stream->stream.iterator().asSequence().take(limit+1).toList()}
        buildJsonObject {put("storage","APP_MANAGED");put("truncated",entries.size>limit)
            put("entries",JsonArray(entries.take(limit).map {path->metadata(file(path.toFile().relativeTo(root).invariantSeparatorsPath))}))}
    }
    register("files.metadata","Managed file metadata",schema()) {metadata(file(it.str("path")))}
    register("files.read","Read bounded managed file",schema {integer("maxBytes","Maximum returned bytes",min=1,max=131072);string("encoding","Result encoding",enum=listOf("utf8","base64"))}) {a ->
        val f=file(a.str("path"));if(!f.isFile) throw CapabilityException("NOT_A_FILE","File does not exist")
        val limit=a.long("maxBytes",65536).toInt();val before=f.length() to f.lastModified()
        val bytes=f.inputStream().use {input->val buffer=ByteArray(limit);var n=0;while(n<limit) {val count=input.read(buffer,n,limit-n);if(count<0) break;n+=count};buffer.copyOf(n)}
        if(before!=(f.length() to f.lastModified())) throw CapabilityException("FILE_CHANGED","File changed while reading")
        val encoding=a["encoding"]?.jsonPrimitive?.content ?: "base64"
        buildJsonObject {put("path",a.str("path"));put("encoding",encoding);put("bytesReturned",bytes.size);put("totalBytes",before.first);put("truncated",before.first>bytes.size)
            put("content",if(encoding=="utf8") bytes.toString(Charsets.UTF_8) else Base64.encodeToString(bytes,Base64.NO_WRAP))}
    }
    register("files.hash","SHA-256 of managed file",schema()) {a ->buildJsonObject {put("sha256",hash(file(a.str("path"))));put("algorithm","SHA-256")}}
    register("files.mkdir","Create managed directory",schema(),RiskClass.LOW_IMPACT) {a ->
        val f=file(a.str("path"));check(f.isDirectory || f.mkdir()) {"Parent must exist and target must be a directory"};check(f.isDirectory);metadata(f)
    }
    register("files.write","Atomically write managed file",schema {
        string("content","UTF-8 text or base64 bytes (bounded write)",true,maxLength=16000);string("encoding","Input encoding",enum=listOf("utf8","base64"));boolean("overwrite","Replace an existing file; default false")
    },RiskClass.MODERATE_IMPACT) {a ->
        val f=file(a.str("path"));val overwrite=a["overwrite"]?.jsonPrimitive?.booleanOrNull ?: false
        if(f.exists() && !overwrite) throw CapabilityException("ALREADY_EXISTS","Choose overwrite explicitly")
        require(f.parentFile?.isDirectory==true && !f.isDirectory) {"Parent must exist and target must be a file"}
        val content=a.str("content");val bytes=if(a["encoding"]?.jsonPrimitive?.content=="base64") Base64.decode(content,Base64.NO_WRAP) else content.toByteArray()
        val atomic=AtomicFile(f);val stream=atomic.startWrite()
        try {stream.write(bytes);atomic.finishWrite(stream)} catch(e:Exception) {atomic.failWrite(stream);throw e}
        val expected=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
        check(hash(f)==expected) {"Written content could not be verified"}
        buildJsonObject {put("path",a.str("path"));put("sizeBytes",bytes.size);put("sha256",expected);put("storage","APP_MANAGED")}
    }
    fun transfer(a:JsonObject,move:Boolean):JsonObject {
        val source=file(a.str("path"));val target=file(a.str("destination"))
        if(!source.isFile) throw CapabilityException("NOT_A_FILE","Source must be a regular file")
        if(target.exists()) throw CapabilityException("ALREADY_EXISTS","Destination already exists; no implicit replacement")
        require(target.parentFile?.isDirectory==true) {"Destination directory must exist"}
        val digest=hash(source)
        if(move) Files.move(source.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE)
        else {
            val atomic=AtomicFile(target);val stream=atomic.startWrite()
            try {source.inputStream().use {input->val buffer=ByteArray(65536);var size=0L
                while(true) {val n=input.read(buffer);if(n<0) break;size+=n
                    if(size>16*1024*1024) throw CapabilityException("FILE_TOO_LARGE","Copy limit is 16 MiB")
                    stream.write(buffer,0,n)
                }
            };atomic.finishWrite(stream)} catch(e:Exception) {atomic.failWrite(stream);throw e}
        }
        check(hash(target)==digest && (!move || !source.exists())) {"Transfer postcondition could not be verified"}
        return buildJsonObject {put("path",a.str("destination"));put("sha256",digest);put("moved",move)}
    }
    val transferSchema=schema {string("destination","New relative path in managed storage",true,maxLength=1000)}
    register("files.copy","Copy managed file",transferSchema,RiskClass.MODERATE_IMPACT) {transfer(it,false)}
    register("files.move","Move or rename managed file",transferSchema,RiskClass.MODERATE_IMPACT) {transfer(it,true)}
    register("files.delete","Delete managed file or empty directory",schema(),RiskClass.MODERATE_IMPACT) {a ->
        val f=file(a.str("path"));if(!f.exists()) throw CapabilityException("NOT_FOUND","Path does not exist")
        Files.delete(f.toPath());check(!f.exists());buildJsonObject {put("deleted",true);put("path",a.str("path"))}
    }
}
