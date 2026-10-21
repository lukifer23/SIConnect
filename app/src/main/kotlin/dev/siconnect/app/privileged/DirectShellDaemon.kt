package dev.siconnect.app.privileged

import android.content.AttributionSource
import android.os.*

/** Bundled Binder authority, started only by an owner-authorized Android shell. */
object DirectShellDaemon {
    // BinderProxy drops its death recipients on finalization. Retain the remote owner
    // for the daemon lifetime, otherwise GC silently disables app-process recovery.
    private var attachedOwner: IBinder?=null
    private var ownerDeath: IBinder.DeathRecipient?=null
    @JvmStatic fun main(args: Array<String>) {
        check(Process.myUid() in setOf(0,2000)) { "An authorized shell/root bootstrap is required" }
        val appUid=args.single().toInt(); require(appUid>=10000)
        Looper.prepare()
        val handler=Handler(Looper.myLooper()!!)
        val service=PrivilegedShellService(appUid)
        val activityManager=Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)
        val authority="dev.siconnect.app.authority"
        val attribution=AttributionSource.Builder(Process.myUid()).setPackageName(if(Process.myUid()==2000) "com.android.shell" else "android").build()
        var retryMs=1000L
        fun attach() {
            if(attachedOwner?.isBinderAlive==true) return
            val token=Binder()
            try {
                // Standalone shell processes have no registered IApplicationThread. Android's
                // external-provider API is the shell entry point used by the platform content CLI.
                val acquire=activityManager.javaClass.getMethod("getContentProviderExternal",String::class.java,Int::class.javaPrimitiveType,IBinder::class.java,String::class.java)
                val holder=requireNotNull(acquire.invoke(activityManager,authority,0,token,"SIConnect"))
                val provider=holder.javaClass.getField("provider").get(holder)
                val call=provider.javaClass.getMethod("call",AttributionSource::class.java,String::class.java,String::class.java,String::class.java,Bundle::class.java)
                val response=call.invoke(provider,attribution,authority,"attach",null,Bundle().apply { putBinder("service",service) }) as Bundle
                val owner=requireNotNull(response.getBinder("owner"))
                val death=IBinder.DeathRecipient {
                    // All ownership changes run on the daemon looper; a stale callback
                    // must not clear a newer attachment or create duplicate retries.
                    handler.post {
                        if(attachedOwner===owner) {
                            attachedOwner=null;ownerDeath=null
                            android.util.Log.i("SIConnectAuthority","Owner process ended; restoring attachment")
                            handler.postDelayed({ attach() },1000)
                        }
                    }
                }
                owner.linkToDeath(death,0)
                attachedOwner=owner;ownerDeath=death
                retryMs=1000
                android.util.Log.i("SIConnectAuthority","Binder attached as uid=${Process.myUid()}")
            } catch(e: Exception) {
                val cause=(e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
                android.util.Log.e("SIConnectAuthority","Attach failed: ${cause.javaClass.simpleName}: ${cause.message}")
                handler.postDelayed({ attach() },retryMs); retryMs=(retryMs*2).coerceAtMost(300000)
            } finally {
                runCatching { activityManager.javaClass.getMethod("removeContentProviderExternalAsUser",String::class.java,IBinder::class.java,Int::class.javaPrimitiveType).invoke(activityManager,authority,token,0) }
            }
        }
        attach()
        Looper.loop()
    }
}
