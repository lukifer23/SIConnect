package dev.siconnect.app

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import dev.siconnect.app.data.*
import dev.siconnect.app.transport.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.Assert
import java.io.File

/** Real device integration setup. Inputs come from the owner-authorized host harness, never a remote endpoint. */
class GatewayDeviceSetup {
    @Test fun connectActualDevice()=runBlocking {
        val g=InstrumentationRegistry.getInstrumentation().targetContext.graph
        g.ready.await()
        val args=InstrumentationRegistry.getArguments()
        val endpoint=requireNotNull(args.getString("gatewayUrl"))
        val enrollment=requireNotNull(args.getString("enrollment"))
        val deviceSecret=requireNotNull(args.getString("deviceSecret"))
        g.settings.setDeviceSecret(deviceSecret)
        g.settings.setEnrollToken(enrollment);g.settings.enrolled=false
        g.settings.setGateway(GatewayConfig(endpoint,true))
        val token=Json.parseToJsonElement(File(g.context.noBackupFilesDir,"development-client.json").readText()).jsonObject.getValue("token").jsonPrimitive.content
        File(g.context.noBackupFilesDir,"gateway-device.json").writeText(buildJsonObject {
            put("deviceId",g.settings.deviceId);put("agentId","development-client");put("agentToken",token)
        }.toString())
        withContext(Dispatchers.Main) {
            g.context.startActivity(Intent(g.context,dev.siconnect.app.ui.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        g.gateway.start()
        withTimeout(30000) { while(g.gateway.state.value!=ConnectionState.ONLINE) { if(g.gateway.state.value==ConnectionState.AUTH_FAILED) error(g.gateway.detail.value);delay(100) } }
        Assert.assertEquals(ConnectionState.ONLINE,g.gateway.state.value)
    }
}
