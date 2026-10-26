package dev.siconnect.app

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.platform.app.InstrumentationRegistry
import dev.siconnect.app.transport.GatewayNetworkMonitor
import dev.siconnect.app.transport.GatewayReconnectWake
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class GatewayNetworkDeviceTest {
    @Test fun validatedDefaultNetworkWakesBackoffThroughAndroidCallback()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val cm=context.getSystemService(ConnectivityManager::class.java)
        val caps=cm.getNetworkCapabilities(cm.activeNetwork)
        assumeTrue("Requires a real validated default network",caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)==true)
        val wake=GatewayReconnectWake();val epoch=wake.generation()
        val monitor=GatewayNetworkMonitor(cm,wake::usableNetworkChanged)
        try {
            monitor.start()
            withTimeout(10_000) {wake.awaitBackoff(256_000,epoch)}
            assertTrue("Android's validated-network callback must advance the wake generation",wake.generation()>epoch)
        } finally {monitor.close();monitor.close()}
    }
}
