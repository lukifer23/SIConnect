package dev.siconnect.app.privileged

import android.content.Context
import android.net.Uri
import android.os.*

/** The APK carries its own Binder service, bootstrapped only through owner-authorized ADB. */
object DirectShellDaemon {
    @JvmStatic fun main(args: Array<String>) {
        check(Process.myUid() in setOf(0,2000)) { "An authorized shell/root bootstrap is required" }
        val appUid=args.single().toInt(); require(appUid>=10000)
        Looper.prepareMainLooper()
        // app_process is not an Android application. Obtain the shell package's framework context
        // under the real shell UID so ContentResolver attributes this call correctly.
        val thread=Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null)
        val system=thread.javaClass.getMethod("getSystemContext").invoke(thread) as Context
        val appInfo=system.packageManager.getApplicationInfo("com.android.shell",0)
        val compatibility=Class.forName("android.content.res.CompatibilityInfo").getField("DEFAULT_COMPATIBILITY_INFO").get(null)
        val loaded=thread.javaClass.getMethod("getPackageInfoNoCheck",android.content.pm.ApplicationInfo::class.java,compatibility.javaClass).invoke(thread,appInfo,compatibility)
        val contextImpl=Class.forName("android.app.ContextImpl")
        val factory=contextImpl.getDeclaredMethod("createAppContext",thread.javaClass,Class.forName("android.app.LoadedApk")).apply { isAccessible=true }
        val context=factory.invoke(null,thread,loaded) as Context
        val service=PrivilegedShellService(appUid)
        val handler=Handler(Looper.getMainLooper()!!)
        var retryMs=1000L
        fun attach() {
            try {
                val response=context.contentResolver.call(Uri.parse("content://dev.siconnect.app.authority"),"attach",null,Bundle().apply { putBinder("service",service) })
                val owner=requireNotNull(response?.getBinder("owner"))
                owner.linkToDeath({ handler.postDelayed({ attach() },1000) },0)
                retryMs=1000
                println("SIConnect Binder authority attached as uid=${Process.myUid()}")
            } catch(e: Exception) {
                System.err.println("Authority attach failed: ${e.javaClass.simpleName}: ${e.message}")
                handler.postDelayed({ attach() },retryMs); retryMs=(retryMs*2).coerceAtMost(300000)
            }
        }
        attach()
        Looper.loop()
    }
}
