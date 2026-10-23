package dev.siconnect.app

import androidx.test.platform.app.InstrumentationRegistry
import dev.siconnect.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in cleanup of the exact disposable owner-created schedule, preserving other schedules. */
class DisposableScheduleCleanupDeviceTest {
    @Test fun disableExplicitDisposableScheduleAndCancelItsAbandonedDocumentFixture():Unit=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        val id=args.getString("cleanupAutomationId")
        assumeTrue("Requires the exact disposable schedule ID",id!=null)
        val g=InstrumentationRegistry.getInstrumentation().targetContext.graph;g.ready.await()
        val before=g.scheduler.all().associateBy {it.id}
        val target=requireNotNull(before[id])
        assertEquals("SIConnect scheduled health test",target.label)
        assertEquals("diagnostics.snapshot",target.skillId)
        assertEquals(false,target.inputs["notifyProblems"]?.jsonPrimitive?.boolean)
        g.scheduler.setEnabled(target.id,false)
        assertFalse(requireNotNull(g.scheduler.get(target.id)).enabled)
        assertEquals(before.filterKeys {it!=id},g.scheduler.all().associateBy {it.id}.filterKeys {it!=id})
        args.getString("abandonedDocumentRequestId")?.let {requestId->
            val root=requireNotNull(args.getString("documentTestRootId"))
            val request=requireNotNull(g.requests.get(requestId))
            assertEquals("local-owner",request.agentId)
            assertEquals("documents.create",request.capabilityId)
            assertEquals(root,request.arguments.getValue("rootId").jsonPrimitive.content)
            assertTrue(request.arguments.getValue("name").jsonPrimitive.content.matches(Regex("fixture-[0-9a-f-]+\\.txt")))
            assertEquals(RequestState.AWAITING_APPROVAL,request.state)
            assertEquals(RequestState.CANCELLED,g.engine.cancel(requestId,"local-owner")?.state)
        }
    }
}
