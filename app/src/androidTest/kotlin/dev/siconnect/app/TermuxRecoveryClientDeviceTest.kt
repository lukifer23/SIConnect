package dev.siconnect.app

import android.util.AtomicFile
import androidx.test.platform.app.InstrumentationRegistry
import dev.siconnect.app.data.AgentRecord
import dev.siconnect.app.termux.TermuxBridge
import dev.siconnect.app.termux.TermuxContract
import dev.siconnect.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Provisioning only: normal-process commands use the existing authenticated debug endpoint. */
class TermuxRecoveryClientDeviceTest {
    @Test fun manageDisposableTermuxClient()=runBlocking {
        val mode=InstrumentationRegistry.getArguments().getString("termuxRecoveryClient")
        assumeTrue("Explicit harness create/remove mode is required",mode in setOf("create","remove"))
        val g=InstrumentationRegistry.getInstrumentation().targetContext.graph;g.ready.await()
        val file=File(g.context.cacheDir,"termux-native-recovery-client.json")
        if(mode=="create") {
            require(!file.exists()) {"Reconcile the previous disposable Termux client before creating another"}
            val now=System.currentTimeMillis();val id="termux-recovery-${UUID.randomUUID()}"
            val token=UUID.randomUUID().toString()+UUID.randomUUID().toString()
            val expiry=now+600_000
            g.agents.put(AgentRecord(AgentGrant(id,"Disposable native Termux recovery",scopes=listOf("termux.*","device.status"),
                autoApproveUpTo=RiskClass.HIGH_IMPACT,shellMode=ShellMode.BROAD,allowSharedScreen=false,expiresAtMs=expiry),
                Canonical.sha256Hex(token),now,tokenHint="Termux recovery acceptance only"))
            val atomic=AtomicFile(file)
            val output=atomic.startWrite()
            try {
                output.write(buildJsonObject {
                    put("agentId",id);put("token",token);put("expiresAtMs",expiry)
                    put("gatewayEnabled",g.settings.gateway.value.enabled);put("phoneRecoveryEnabled",g.bootstrap.recoveryEnabled)
                }.toString().toByteArray(Charsets.UTF_8))
                atomic.finishWrite(output)
            } catch(error:Throwable) {atomic.failWrite(output);g.revokeAgentFromOwner(id);throw error}
        } else if(file.exists()) {
            val id=Json.parseToJsonElement(file.readText()).jsonObject.getValue("agentId").jsonPrimitive.content
            require(id.startsWith("termux-recovery-"))
            val agent=g.agents.get(id)
            require(agent==null || agent.tokenHint=="Termux recovery acceptance only")
            try {
                val ids=g.db.readableDatabase.rawQuery("SELECT request_id FROM requests WHERE agent_id=? AND capability_id='termux.execute'",arrayOf(id))
                    .use {c->buildList {while(c.moveToNext()) add(c.getString(0))}}
                for(requestId in ids) {
                    val record=requireNotNull(g.requests.get(requestId))
                    check(record.state.isTerminal) {"Real Termux request is not terminal; retain its owner identity"}
                    if(record.state==RequestState.UNKNOWN_OUTCOME) {
                        val observed=TermuxBridge.get(g.context).result(id,requestId)
                        check(observed["callbackReceived"]?.jsonPrimitive?.booleanOrNull==true &&
                            observed["executionErrorCode"]?.jsonPrimitive?.intOrNull==TermuxContract.RESULT_ERR_SUCCESS &&
                            observed["exitCode"]?.jsonPrimitive?.intOrNull!=null)
                            {"Uncertain Termux request has no confirmed completed callback; retaining and revoking this identity"}
                    }
                }
                g.agents.delete(id);AtomicFile(file).delete()
                // Retain core and callback evidence. No Termux process is killed and
                // no other principal's state, app files or credentials are changed.
            } catch(error:Throwable) {if(agent!=null) g.revokeAgentFromOwner(id);throw error}
        }
    }
}
