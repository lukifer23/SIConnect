package dev.siconnect.app

import android.app.KeyguardManager
import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import dev.siconnect.app.backend.ShellRequest
import dev.siconnect.app.data.AgentRecord
import dev.siconnect.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*

import org.junit.Test
import java.util.UUID

/** Actual installed Calculator, Android displays, semantic input, Binder, policy and SQLite. */
class HeadlessWorkspaceDeviceTest {
    private val g get()=InstrumentationRegistry.getInstrumentation().targetContext.graph
    private val calculator="com.sec.android.app.popupcalculator"
    private suspend fun ready() {
        g.ready.await()
        withTimeout(15_000) {while(!g.privileged.available()) delay(50)}
        g.context.packageManager.getApplicationInfo(calculator,0)
    }
    private suspend fun call(agent:String,capability:String,args:JsonObject=buildJsonObject {}):RequestRecord =
        g.engine.submit(InvocationRequest(agentId=agent,capabilityId=capability,arguments=args),Origin.REMOTE)
    private fun succeeded(record:RequestRecord):JsonObject {
        assertEquals("${record.capabilityId}: ${record.errorCode}: ${record.errorMessage}",RequestState.SUCCEEDED,record.state)
        return record.result!!.output.jsonObject
    }
    private fun binding(session:String)=buildJsonObject {put("sessionId",session);put("packageName",calculator)}
    private suspend fun inspect(agent:String,session:String):JsonObject=withTimeout(15_000) {
        while(true) {
            val r=call(agent,"headless.inspect",binding(session))
            if(r.state==RequestState.SUCCEEDED) return@withTimeout r.result!!.output.jsonObject
            assertTrue("${r.errorCode}: ${r.errorMessage}",r.errorCode in setOf("WORKSPACE_WINDOW_UNAVAILABLE","WORKSPACE_TARGET_CHANGED"))
            delay(150)
        }
        @Suppress("UNREACHABLE_CODE") error("No private window")
    }
    private suspend fun physicalFocus(exclude:Int?=null):Map<Int,String> {
        val result=g.privileged.execute(ShellRequest("headless-focus-${UUID.randomUUID()}",argv=listOf("dumpsys","window","displays"),maxOutputBytes=131072))
        assertEquals(0,result.exitCode);assertFalse(result.stdoutTruncated)
        // Built-in physical display identity comes from actual DisplayManager metadata. WindowManager
        // removal is asynchronous; an EXITING private display is not a physical screen.
        val physical=g.context.getSystemService(android.hardware.display.DisplayManager::class.java).displays
            .filter {it.isInternal}.map {it.displayId}.toSet()
        var display=-1;val focused=linkedMapOf<Int,String>()
        for(line in result.stdout.lineSequence()) {
            Regex("Display: mDisplayId=(\\d+)").find(line)?.let {display=it.groupValues[1].toInt()}
            if("mCurrentFocus=" in line && display in physical && display!=exclude) focused[display]=line.trim()
        }
        assertTrue(focused.isNotEmpty());return focused
    }
    private suspend fun awaitDisplayGone(id:Int)=withTimeout(10_000) {
        val displays=g.context.getSystemService(android.hardware.display.DisplayManager::class.java)
        while(displays.getDisplay(id)?.isValid==true) delay(50)
    }
    private fun agent(id:String,scopes:List<String> = listOf("headless.*"),broadShell:Boolean=false) {
        g.agents.put(AgentRecord(AgentGrant(id,"Temporary private-display acceptance",scopes=scopes,autoApproveUpTo=RiskClass.HIGH_IMPACT,allowSharedScreen=false,
            shellMode=if(broadShell) ShellMode.BROAD else ShellMode.APPROVAL_REQUIRED),"",System.currentTimeMillis(),tokenHint="device test"))
    }
    private suspend fun cleanup(agent:String) {
        runCatching {
            val sessions=succeeded(call(agent,"headless.status")).getValue("sessions").jsonArray
            for(session in sessions) call(agent,"headless.close",buildJsonObject {
                put("sessionId",session.jsonObject.getValue("sessionId"));put("packageName",session.jsonObject.getValue("packageName"))
            })
        }
        g.agents.delete(agent)
    }

    @Test fun realCalculatorInputAndCaptureLeavePhysicalForegroundUnchanged() = runBlocking {
        ready();val id="headless-test-${UUID.randomUUID()}";agent(id,listOf("headless.*","device.battery","apps.launch","shell.execute"),broadShell=true)
        val before=physicalFocus()
        val lockedBefore=g.context.getSystemService(KeyguardManager::class.java).isDeviceLocked
        val physicalDisplay=g.context.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(android.view.Display.DEFAULT_DISPLAY)
        val physicalState=physicalDisplay.state
        try {
            val shared=call(id,"apps.launch",buildJsonObject {put("package","dev.siconnect.doesnotexist")})
            assertEquals(RequestState.DENIED,shared.state);assertEquals("shared_screen_disabled",shared.policy?.rule)
            val shell=call(id,"shell.execute",buildJsonObject {put("command","true")})
            assertEquals(RequestState.DENIED,shell.state);assertEquals("shared_screen_disabled",shell.policy?.rule)
            val opened=succeeded(call(id,"headless.open",buildJsonObject {put("packageName",calculator);put("ttlMs",60_000)}))
            val session=opened.getValue("sessionId").jsonPrimitive.content;val display=opened.getValue("displayId").jsonPrimitive.int
            assertEquals(android.os.Process.myPid(),opened.getValue("workspaceCallerPid").jsonPrimitive.int)
            assertTrue(display>0);assertTrue(opened.getValue("ownFocus").jsonPrimitive.boolean);assertFalse(opened.getValue("stealsTopFocus").jsonPrimitive.boolean)
            var snapshot=inspect(id,session)
            assertTrue(snapshot.getValue("targetWindowVerified").jsonPrimitive.boolean)
            assertEquals(before,physicalFocus(display))
            assertEquals(lockedBefore,g.context.getSystemService(KeyguardManager::class.java).isDeviceLocked)
            assertEquals("Physical display power state changed",physicalState,physicalDisplay.state)
            suspend fun click(vararg labels:String) {
                snapshot=inspect(id,session)
                val nodes=snapshot.getValue("nodes").jsonArray.map {it.jsonObject}
                val node=nodes.firstOrNull {n->n["clickable"]?.jsonPrimitive?.booleanOrNull==true && labels.any {label->
                    n["text"]?.jsonPrimitive?.content.equals(label,true) || n["description"]?.jsonPrimitive?.content.equals(label,true) ||
                        n["resourceId"]?.jsonPrimitive?.content?.substringAfterLast('/')==label
                }} ?: throw AssertionError("Real Calculator control unavailable: ${labels.toList()}; resources=${nodes.map {it["resourceId"]?.jsonPrimitive?.content}}")
                succeeded(call(id,"headless.click",buildJsonObject {
                    binding(session).forEach {(k,v)->put(k,v)};put("snapshotId",snapshot.getValue("snapshotId"));put("nodeRef",node.getValue("nodeRef"))
                }))
                delay(200)
            }
            click("C","Clear","Clear all","calc_keypad_btn_clear")
            click("2","calc_keypad_btn_02");click("+","Plus","Add","calc_keypad_btn_add")
            click("3","calc_keypad_btn_03");click("=","Equal","Equals","calc_keypad_btn_equal")
            snapshot=inspect(id,session)
            assertTrue("Actual Calculator did not expose result 5",snapshot.getValue("nodes").jsonArray.any {
                it.jsonObject["text"]?.jsonPrimitive?.content=="5" || it.jsonObject["description"]?.jsonPrimitive?.content=="5"
            })
            val captured=succeeded(call(id,"headless.capture",binding(session)))
            val bytes=android.util.Base64.decode(captured.getValue("data").jsonPrimitive.content,android.util.Base64.NO_WRAP)
            assertTrue(bytes.size in 100..131072)
            assertEquals(0xff,bytes[0].toInt() and 255);assertEquals(0xd8,bytes[1].toInt() and 255)
            val native=call(id,"device.battery");val battery=succeeded(native)
            assertEquals(BackendKind.NATIVE,native.result!!.backend)
            assertTrue(battery.getValue("percent").jsonPrimitive.int in 0..100)
            assertEquals(before,physicalFocus(display))
            assertEquals("Physical display power state changed",physicalState,physicalDisplay.state)
            assertTrue(succeeded(call(id,"headless.close",binding(session))).getValue("closed").jsonPrimitive.boolean)
            awaitDisplayGone(display)
            assertEquals(before,physicalFocus())
            assertEquals(lockedBefore,g.context.getSystemService(KeyguardManager::class.java).isDeviceLocked)
            InstrumentationRegistry.getInstrumentation().sendStatus(0,Bundle().apply {
                putString("privateDisplayEvidence","Actual Calculator result=5; ownFocus=true; stealsTopFocus=false; physical foreground unchanged; physical lock unchanged=$lockedBefore; JPEG bytes=${bytes.size}")
            })
        } finally {cleanup(id)}
    }

    @Test fun actualMapsRouteUsesItsPrivateDisplayAndCleansUpWithoutPhysicalUnlock() = runBlocking {
        ready();val id="private-maps-${UUID.randomUUID()}";agent(id,listOf("headless.*","commute.route_private"))
        val before=physicalFocus();val lockedBefore=g.context.getSystemService(KeyguardManager::class.java).isDeviceLocked
        try {
            val result=succeeded(call(id,"commute.route_private",buildJsonObject {
                put("origin","Texas State Capitol, Austin, TX");put("destination","Austin Central Library, Austin, TX")
                put("expectedDestinationLabel","Austin Central Library, Austin Public Library")
            }))
            assertEquals("PRIVATE_DISPLAY",result.getValue("executionSurface").jsonPrimitive.content)
            assertFalse(result.getValue("usesSharedScreen").jsonPrimitive.boolean)
            assertEquals("EXPECTED_LABEL_MATCHED",result.getValue("destinationBinding").jsonPrimitive.content)
            assertTrue(result.getValue("durationSeconds").jsonPrimitive.double>0)
            assertTrue(result.getValue("distanceMeters").jsonPrimitive.double>0)
            assertTrue(result.getValue("privateWorkspaceClosed").jsonPrimitive.boolean)
            awaitDisplayGone(result.getValue("privateDisplayId").jsonPrimitive.int)
            assertEquals(before,physicalFocus());assertEquals(lockedBefore,g.context.getSystemService(KeyguardManager::class.java).isDeviceLocked)
            assertTrue(succeeded(call(id,"headless.status")).getValue("sessions").jsonArray.isEmpty())
            InstrumentationRegistry.getInstrumentation().sendStatus(0,Bundle().apply {
                putString("privateMapsEvidence","Actual Maps duration=${result.getValue("durationSeconds")}; distance=${result.getValue("distanceMeters")}; private workspace closed; physical focus/lock unchanged=$lockedBefore")
            })
        } finally {cleanup(id)}
    }

    @Test fun realPrivateMapsDepartureAndArrivalUseObservedControlsAndLeavePhysicalScreenAvailable() = runBlocking {
        ready();val id="private-maps-time-${UUID.randomUUID()}";agent(id,listOf("headless.*","commute.route_private"))
        val before=physicalFocus();val locked=g.context.getSystemService(KeyguardManager::class.java).isDeviceLocked
        try {
            val zone=java.time.ZoneId.systemDefault();val tomorrow=java.time.LocalDate.now(zone).plusDays(1)
            for(arrival in listOf(false,true)) {
                val time=tomorrow.atTime(if(arrival) 9 else 8,if(arrival) 15 else 30).atZone(zone).toInstant().toEpochMilli()
                val result=succeeded(call(id,"commute.route_private",buildJsonObject {
                    put("origin","Texas State Capitol, Austin, TX");put("destination","Austin Central Library, Austin, TX")
                    put("expectedDestinationLabel","Austin Central Library, Austin Public Library");put("timezone",zone.id)
                    put(if(arrival) "arrivalAtMs" else "departureAtMs",time)
                }))
                assertTrue(result.getValue(if(arrival) "forecastArrivalTimeVerified" else "forecastDepartureTimeVerified").jsonPrimitive.boolean)
                assertEquals(time,result.getValue(if(arrival) "evaluatedArrivalAtMs" else "evaluatedDepartureAtMs").jsonPrimitive.long)
                assertEquals("EXPECTED_LABEL_MATCHED",result.getValue("destinationBinding").jsonPrimitive.content)
                assertTrue(result.getValue("durationSeconds").jsonPrimitive.double>0);assertTrue(result.getValue("distanceMeters").jsonPrimitive.double>0)
                assertFalse(result.getValue("usesSharedScreen").jsonPrimitive.boolean);assertTrue(result.getValue("privateWorkspaceClosed").jsonPrimitive.boolean)
                awaitDisplayGone(result.getValue("privateDisplayId").jsonPrimitive.int)
                assertEquals(before,physicalFocus());assertEquals(locked,g.context.getSystemService(KeyguardManager::class.java).isDeviceLocked)
                InstrumentationRegistry.getInstrumentation().sendStatus(0,Bundle().apply {
                    putString("privateMapsTimeEvidence","mode=${if(arrival) "arrival" else "departure"}; actual selected time verified; duration=${result.getValue("durationSeconds")}; private display removed; physical focus/lock unchanged")
                })
            }
        } finally {cleanup(id)}
    }

    @Test fun ownershipScopeProtectedSurfacesAndStaleReferencesAreEnforced() = runBlocking {
        ready();val first="headless-owner-${UUID.randomUUID()}";val other="headless-other-${UUID.randomUUID()}";val denied="headless-denied-${UUID.randomUUID()}"
        agent(first);agent(other);agent(denied,listOf("device.*"))
        try {
            assertEquals(RequestState.DENIED,call(denied,"headless.open",buildJsonObject {put("packageName",calculator)}).state)
            val blocked=call(first,"headless.open",buildJsonObject {put("packageName","com.android.settings")})
            assertNotEquals(RequestState.SUCCEEDED,blocked.state)
            assertTrue(succeeded(call(first,"headless.status")).getValue("sessions").jsonArray.isEmpty())
            val opened=succeeded(call(first,"headless.open",buildJsonObject {put("packageName",calculator)}))
            val session=opened.getValue("sessionId").jsonPrimitive.content
            val foreign=call(other,"headless.inspect",binding(session));assertEquals("WORKSPACE_NOT_OWNED",foreign.errorCode)
            assertTrue(succeeded(call(other,"headless.status")).getValue("sessions").jsonArray.isEmpty())
            val old=inspect(first,session);inspect(first,session)
            val node=old.getValue("nodes").jsonArray.first().jsonObject
            val stale=call(first,"headless.click",buildJsonObject {
                binding(session).forEach {(k,v)->put(k,v)};put("snapshotId",old.getValue("snapshotId"));put("nodeRef",node.getValue("nodeRef"))
            })
            assertEquals("STALE_NODE_REFERENCE",stale.errorCode)
            assertEquals("POLICY_DENIED",call(other,"headless.stop_all").errorCode)
        } finally {cleanup(first);cleanup(other);cleanup(denied)}
    }

    @Test fun expiryReleasesTheActualDisplayAndCannotSilentlyReopenIt() = runBlocking {
        ready();val id="headless-expiry-${UUID.randomUUID()}";agent(id)
        try {
            val opened=succeeded(call(id,"headless.open",buildJsonObject {put("packageName",calculator);put("ttlMs",15_000)}))
            val session=opened.getValue("sessionId").jsonPrimitive.content
            val deadline=SystemClock.elapsedRealtime()+30_000
            do {
                delay(1000)
                if(succeeded(call(id,"headless.status")).getValue("sessions").jsonArray.isEmpty()) break
            } while(SystemClock.elapsedRealtime()<deadline)
            assertTrue(succeeded(call(id,"headless.status")).getValue("sessions").jsonArray.isEmpty())
            assertEquals("WORKSPACE_GONE",call(id,"headless.inspect",binding(session)).errorCode)
            awaitDisplayGone(opened.getValue("displayId").jsonPrimitive.int)
        } finally {cleanup(id)}
    }

    @Test fun lockedPrivateOpenRefusesWithoutChangingPhysicalStateAndNativeWorkContinues() = runBlocking {
        ready()
        assertTrue("Requires the actual phone locked, without synthesizing lock state",g.context.getSystemService(KeyguardManager::class.java).isDeviceLocked)
        val id="headless-locked-${UUID.randomUUID()}";agent(id,listOf("headless.*","device.battery","apps.launch","shell.execute"),broadShell=true)
        val displays=g.context.getSystemService(android.hardware.display.DisplayManager::class.java)
        val before=physicalFocus();val state=displays.getDisplay(0).state
        try {
            assertEquals("shared_screen_disabled",call(id,"apps.launch",buildJsonObject {put("package","dev.siconnect.doesnotexist")}).policy?.rule)
            assertEquals("shared_screen_disabled",call(id,"shell.execute",buildJsonObject {put("command","true")}).policy?.rule)
            val denied=call(id,"headless.open",buildJsonObject {put("packageName",calculator)})
            assertEquals(denied.errorMessage,"DEVICE_LOCKED",denied.errorCode);assertEquals(RequestState.FAILED,denied.state)
            assertEquals(before,physicalFocus());assertEquals(state,displays.getDisplay(0).state)
            assertTrue(g.context.getSystemService(KeyguardManager::class.java).isDeviceLocked)
            assertTrue(succeeded(call(id,"headless.status")).getValue("sessions").jsonArray.isEmpty())
            val native=call(id,"device.battery");val battery=succeeded(native)
            assertEquals(BackendKind.NATIVE,native.result!!.backend);assertTrue(battery.getValue("percent").jsonPrimitive.int in 0..100)
        } finally {cleanup(id)}
    }

    @Test fun privateAppAllowanceIsDurableNarrowAndSharedScreenRestrictionPreservesOtherPermissions() = runBlocking {
        ready();val id="headless-grant-${UUID.randomUUID()}"
        val unrelated=StandingGrant("files.read_text",maxRisk=RiskClass.READ_ONLY,backend=BackendKind.NATIVE)
        val original=AgentGrant(id,"Temporary private permission acceptance",scopes=listOf("headless.*","files.*","shell.*"),standing=listOf(unrelated),shellMode=ShellMode.BROAD)
        g.agents.put(AgentRecord(original,"",System.currentTimeMillis(),tokenHint="device test"))
        try {
            g.setHeadlessPackagePermissionFromOwner(id,calculator,true)
            val saved=g.agents.get(id)!!.grant
            val appGrants=saved.standing.filter {it.capabilityId.startsWith("headless.")}
            assertEquals(setOf("headless.open","headless.click","headless.set_text","headless.scroll","headless.close"),appGrants.map {it.capabilityId}.toSet())
            assertTrue(appGrants.all {it.constraints==mapOf("packageName" to JsonPrimitive(calculator)) && it.backend==g.privileged.kind && it.maxRisk==RiskClass.HIGH_IMPACT})
            assertTrue(saved.standing.contains(unrelated));assertEquals(original.scopes,saved.scopes)
            g.setSharedScreenPermissionFromOwner(id,false)
            val restricted=g.agents.get(id)!!.grant
            assertFalse(restricted.allowSharedScreen);assertEquals(saved.standing,restricted.standing)
            assertEquals("shared_screen_disabled",call(id,"shell.execute",buildJsonObject {put("command","true")}).policy?.rule)
            g.setHeadlessPackagePermissionFromOwner(id,calculator,false)
            assertEquals(listOf(unrelated),g.agents.get(id)!!.grant.standing)
        } finally {cleanup(id)}
    }

    @Test fun expiredBinderDispatchCannotCreateADisplayOrLaunchAnApp() = runBlocking {
        ready();assertEquals(BackendKind.ADB_SERVICE,g.privileged.kind)
        val remote=dev.siconnect.app.privileged.DirectAuthority.service!!
        val before=physicalFocus();val displays=g.context.getSystemService(android.hardware.display.DisplayManager::class.java)
        val displayIds=displays.displays.map {it.displayId}.toSet();val state=displays.getDisplay(0).state
        val request=buildJsonObject {
            put("execId","workspace-${UUID.randomUUID()}");put("deadlineAtMs",System.currentTimeMillis()-1)
            put("operation","open");put("agentId","temporary-expired-acceptance");put("packageName",calculator)
        }
        val result=withContext(Dispatchers.IO) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(remote.workspaceStream(request.toString(),dev.siconnect.app.backend.WorkspaceTransport.ownerToken)).bufferedReader().use {Json.parseToJsonElement(it.readText()).jsonObject}
        }
        assertEquals("WORKSPACE_DEADLINE",result.getValue("errorCode").jsonPrimitive.content)
        assertTrue(result.getValue("noSideEffects").jsonPrimitive.boolean)
        assertEquals(displayIds,displays.displays.map {it.displayId}.toSet());assertEquals(state,displays.getDisplay(0).state)
        assertEquals(before,physicalFocus())
    }

    @Test fun realQueuedBinderCancellationPreventsDispatchAndLeavesNoDisplay() = runBlocking {
        ready();assertEquals(BackendKind.ADB_SERVICE,g.privileged.kind)
        val remote=dev.siconnect.app.privileged.DirectAuthority.service!!
        val displays=g.context.getSystemService(android.hardware.display.DisplayManager::class.java)
        val ids=displays.displays.map {it.displayId}.toSet();val before=physicalFocus();val state=displays.getDisplay(0).state
        var cancelled=0
        repeat(20) {
            // Real authenticated service, trust/display readiness reads, Binder and reliable pipes.
            // Eight actual concurrent requests share the backend's serial workspace queue.
            coroutineScope {
                (1..8).map {async(Dispatchers.IO) {
                    val token="workspace-${UUID.randomUUID()}"
                    val request=buildJsonObject {
                        put("execId",token);put("deadlineAtMs",System.currentTimeMillis()+30_000)
                        put("operation","status");put("agentId","temporary-cancellation-acceptance")
                    }
                    val pipe=remote.workspaceStream(request.toString(),dev.siconnect.app.backend.WorkspaceTransport.ownerToken)
                    val prevented=remote.cancelWorkspace(token)
                    val result=android.os.ParcelFileDescriptor.AutoCloseInputStream(pipe).bufferedReader().use {Json.parseToJsonElement(it.readText()).jsonObject}
                    if(prevented) {
                        assertEquals("WORKSPACE_CANCELLED",result.getValue("errorCode").jsonPrimitive.content)
                        assertTrue(result.getValue("noSideEffects").jsonPrimitive.boolean)
                    } else assertFalse(result.containsKey("errorCode"))
                    prevented
                }}.awaitAll().count {it}
            }.let {cancelled+=it}
        }
        assertTrue("No queued cancellation was observed; this run does not establish cancellation acceptance",cancelled>0)
        assertEquals(ids,displays.displays.map {it.displayId}.toSet());assertEquals(state,displays.getDisplay(0).state)
        assertEquals(before,physicalFocus())
        InstrumentationRegistry.getInstrumentation().sendStatus(0,Bundle().apply {
            putString("queuedCancellationEvidence","Real status dispatches=160; cancelled before dispatch=$cancelled; no display/focus/power change")
        })
    }

    @Test fun bridgeStartupAssistanceRejectsRemoteRoleAndCallerArguments() = runBlocking {
        ready();val id="bridge-role-test-${UUID.randomUUID()}";agent(id,listOf("system.restore_bridge"))
        fun attempts(request:String)=g.db.readableDatabase.rawQuery("SELECT count(*) FROM events WHERE event_type='gateway.background_start.assisted' AND request_id=?",arrayOf(request)).use {it.moveToFirst();it.getLong(0)}
        try {
            val remote=call(id,"system.restore_bridge")
            assertNotEquals(RequestState.SUCCEEDED,remote.state);assertEquals("POLICY_DENIED",remote.errorCode)
            val injected=call(id,"system.restore_bridge",buildJsonObject {put("packageName","dev.siconnect.other");put("durationMs",999999)})
            assertNotEquals(RequestState.SUCCEEDED,injected.state)
            assertEquals(0L,attempts(remote.requestId));assertEquals(0L,attempts(injected.requestId))
        } finally {g.agents.delete(id)}
    }

    @Test fun bridgeStartupAssistanceUsesOwnerPolicyActualIdentityAndPreservesControlsWithoutWake() = runBlocking {
        ready();assertEquals(BackendKind.ADB_SERVICE,g.privileged.kind)
        assertTrue("Requires the actually owner-enabled gateway",g.settings.gateway.value.enabled)
        val gatewayBefore=g.settings.gateway.value
        val grantsBefore=g.agents.all().associate {it.grant.agentId to (it.grant to it.tokenSha256)}
        val power=g.context.getSystemService(android.os.PowerManager::class.java).isInteractive
        val locked=g.context.getSystemService(KeyguardManager::class.java).isDeviceLocked
        val before=physicalFocus()
        val request=g.engine.submit(InvocationRequest(agentId="local-owner",capabilityId="system.restore_bridge"),Origin.LOCAL_OWNER)
        val result=succeeded(request)
        assertTrue(result.getValue("startupRequested").jsonPrimitive.boolean)
        assertEquals(10_000,result.getValue("temporaryAllowanceMs").jsonPrimitive.int)
        assertEquals(BackendKind.ADB_SERVICE,request.result!!.backend)
        assertTrue(request.result!!.identity!!.contains("uid=2000"))
        assertEquals(VerificationStatus.NOT_VERIFIED,request.result!!.verification)
        val count=g.db.readableDatabase.rawQuery("SELECT count(*) FROM events WHERE event_type='gateway.background_start.assisted' AND request_id=? AND agent_id='local-owner'",arrayOf(request.requestId)).use {it.moveToFirst();it.getLong(0)}
        assertEquals(1L,count)
        assertTrue("Saved gateway settings changed",gatewayBefore==g.settings.gateway.value)
        assertTrue("Existing grants or credentials changed",grantsBefore==g.agents.all().associate {it.grant.agentId to (it.grant to it.tokenSha256)})
        assertEquals(power,g.context.getSystemService(android.os.PowerManager::class.java).isInteractive)
        assertEquals(locked,g.context.getSystemService(KeyguardManager::class.java).isDeviceLocked)
        assertEquals(before,physicalFocus())
    }

    @Test fun cancelledQueuedOpensCannotLaunchLaterOrLeakPrivateDisplays() = runBlocking {
        ready();assertFalse("Use the actual unlocked phone",g.context.getSystemService(KeyguardManager::class.java).isDeviceLocked)
        assertEquals(BackendKind.ADB_SERVICE,g.privileged.kind)
        val remote=dev.siconnect.app.privileged.DirectAuthority.service!!
        val displays=g.context.getSystemService(android.hardware.display.DisplayManager::class.java)
        val beforeIds=displays.displays.map {it.displayId}.toSet();val before=physicalFocus();val power=displays.getDisplay(0).state
        val agents=(1..8).map {"headless-cancel-open-${UUID.randomUUID()}"};agents.forEach {agent(it)}
        var prevented=0;var launched=0
        try {
            val results=coroutineScope {agents.map {id->async(Dispatchers.IO) {
                val token="workspace-${UUID.randomUUID()}"
                val request=buildJsonObject {
                    put("execId",token);put("deadlineAtMs",System.currentTimeMillis()+30_000)
                    put("operation","open");put("agentId",id);put("packageName",calculator);put("ttlMs",120_000)
                }
                val pipe=remote.workspaceStream(request.toString(),dev.siconnect.app.backend.WorkspaceTransport.ownerToken)
                val cancelled=remote.cancelWorkspace(token)
                val result=android.os.ParcelFileDescriptor.AutoCloseInputStream(pipe).bufferedReader().use {Json.parseToJsonElement(it.readText()).jsonObject}
                if(cancelled) {
                    assertEquals("WORKSPACE_CANCELLED",result.getValue("errorCode").jsonPrimitive.content)
                    assertTrue(result.getValue("noSideEffects").jsonPrimitive.boolean)
                } else if(result.containsKey("errorCode")) assertEquals("WORKSPACE_LIMIT",result.getValue("errorCode").jsonPrimitive.content)
                else assertTrue(result.getValue("launchDispatched").jsonPrimitive.boolean)
                cancelled to !result.containsKey("errorCode")
            }}.awaitAll()}
            prevented=results.count {it.first};launched=results.count {it.second}
            assertTrue("No queued open cancellation observed; do not count this run as acceptance",prevented>0)
            // A positive cancellation must still have no session after all real opens drained.
            for((index,id) in agents.withIndex()) if(results[index].first)
                assertTrue(succeeded(call(id,"headless.status")).getValue("sessions").jsonArray.isEmpty())
        } finally {
            agents.forEach {cleanup(it)}
        }
        withTimeout(10_000) {while(displays.displays.map {it.displayId}.toSet()!=beforeIds) delay(50)}
        assertEquals(before,physicalFocus());assertEquals(power,displays.getDisplay(0).state)
        InstrumentationRegistry.getInstrumentation().sendStatus(0,Bundle().apply {
            putString("queuedOpenCancellationEvidence","Real opens=8; prevented before dispatch=$prevented; actual launches=$launched; all owned displays removed; physical focus/power unchanged")
        })
    }

    @Test fun revocationRejectsNewPrivateInputAndPreservesOriginalOutcome() = runBlocking {
        ready();val id="headless-revocation-${UUID.randomUUID()}";agent(id)
        val before=physicalFocus()
        try {
            val opened=call(id,"headless.open",buildJsonObject {put("packageName",calculator);put("ttlMs",120_000)})
            val workspace=succeeded(opened);val session=workspace.getValue("sessionId").jsonPrimitive.content
            val snapshot=inspect(id,session);val node=snapshot.getValue("nodes").jsonArray.first {it.jsonObject["clickable"]?.jsonPrimitive?.booleanOrNull==true}.jsonObject
            val saved=g.agents.get(id)!!
            g.revokeAgentFromOwner(id)
            assertTrue(g.agents.get(id)!!.grant.revoked)
            val rejected=call(id,"headless.click",buildJsonObject {
                binding(session).forEach {(k,v)->put(k,v)};put("snapshotId",snapshot.getValue("snapshotId"));put("nodeRef",node.getValue("nodeRef"))
            })
            assertEquals(RequestState.DENIED,rejected.state);assertEquals("agent_revoked",rejected.policy?.rule)
            assertEquals(RequestState.SUCCEEDED,g.requests.get(opened.requestId)!!.state)
            assertTrue(g.context.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(workspace.getValue("displayId").jsonPrimitive.int).isValid)
            assertEquals(before,physicalFocus())
            // Test-owned identity only: reauthorize owned cleanup without touching real agents.
            g.agents.put(saved.copy(grant=saved.grant.copy(expiresAtMs=System.currentTimeMillis()+30_000)))
            assertTrue(succeeded(call(id,"headless.close",binding(session))).getValue("closed").jsonPrimitive.boolean)
            awaitDisplayGone(workspace.getValue("displayId").jsonPrimitive.int)
        } finally {
            g.agents.get(id)?.let {record->if(record.grant.revoked) g.agents.put(record.copy(grant=record.grant.copy(revoked=false,expiresAtMs=System.currentTimeMillis()+30_000)))}
            cleanup(id)
        }
    }

    @Test fun duplicateBootstrapCannotReplaceTheLiveBinderAuthority() = runBlocking {
        ready();assertEquals(BackendKind.ADB_SERVICE,g.privileged.kind)
        suspend fun pid():String {
            val result=g.privileged.execute(ShellRequest("singleton-pid-${UUID.randomUUID()}",argv=listOf("pidof","siconnect_privileged")))
            assertEquals(0,result.exitCode);val value=result.stdout.trim()
            assertTrue("One actual daemon must own the Binder authority",value.matches(Regex("[0-9]+")))
            return value
        }
        val before=pid()
        coroutineScope {
            (1..2).map {async(Dispatchers.IO) {
                val result=g.privileged.execute(ShellRequest("singleton-start-${UUID.randomUUID()}",
                    argv=listOf("/system/bin/app_process","/system/bin","--nice-name=siconnect_privileged","dev.siconnect.app.privileged.DirectShellDaemon",g.context.applicationInfo.uid.toString()),
                    env=mapOf("CLASSPATH" to g.context.applicationInfo.sourceDir),timeoutMs=10_000))
                assertEquals(0,result.exitCode);assertFalse(result.timedOut)
            }}.awaitAll()
        }
        assertEquals(before,pid())
    }
}
