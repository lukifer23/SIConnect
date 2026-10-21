package dev.siconnect.app.privileged

import android.content.*
import android.database.Cursor
import android.net.Uri
import android.os.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import dev.siconnect.app.graph

/** Only Android's shell/root identities may attach authority. No owner/policy actions are exported. */
class AuthorityProvider: ContentProvider() {
    private val owner=Binder()
    override fun onCreate()=true
    override fun call(method: String,arg: String?,extras: Bundle?): Bundle {
        check(Binder.getCallingUid() in setOf(0,2000)) { "Only an authorized shell may attach the service" }
        require(method=="attach")
        android.util.Log.i("SIConnectAuthority","Authenticated shell attachment received")
        val binder=requireNotNull(extras?.getBinder("service"))
        synchronized(DirectAuthority) { DirectAuthority.service=IPrivilegedShell.Stub.asInterface(binder) }
        binder.linkToDeath({
            synchronized(DirectAuthority) {
                if(DirectAuthority.service?.asBinder()===binder) {
                    DirectAuthority.service=null; DirectAuthority.status.value="Built-in privileged service stopped"
                }
            }
        },0)
        DirectAuthority.status.value="Connected; checking execution identity"
        context?.graph?.let {g->g.scope.launch {g.privileged.refreshDirectAuthority(IPrivilegedShell.Stub.asInterface(binder))}}
        android.util.Log.i("SIConnectAuthority","Authority stored")
        return Bundle().apply { putBinder("owner",owner) }
    }
    override fun query(uri: Uri,projection: Array<out String>?,selection: String?,selectionArgs: Array<out String>?,sortOrder: String?): Cursor? = throw UnsupportedOperationException()
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri,values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri,selection: String?,selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri,values: ContentValues?,selection: String?,selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}
object DirectAuthority {
    @Volatile var service: IPrivilegedShell?=null
    val status=MutableStateFlow("Built-in privileged service has not been started")
}
