package dev.siconnect.core

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DiscoveryTest {
    private class Probe(val name: String = "test.read") : Capability {
        override val descriptor = CapabilityDescriptor(name, title = "Probe", description = "fixture", inputSchema = buildJsonObject { put("type", "object") }, backend = BackendKind.NATIVE, risk = RiskClass.READ_ONLY)
        var available = true
        var probes = 0
        var executions = 0
        override fun availability(): Availability { probes++; return if (available) Availability.IMPLEMENTED else Availability.PERMISSION_REQUIRED }
        override suspend fun execute(args: JsonObject, ctx: ExecutionContext): CapabilityResult { executions++; return CapabilityResult(buildJsonObject {}, BackendKind.NATIVE) }
    }
    @Test fun metadataCacheExpiresAndFreshDiscoveryBypassesIt() {
        var nanos = 0L
        val registry = CapabilityRegistry { nanos }; val cap = Probe(); registry.register(cap)
        assertEquals(Availability.IMPLEMENTED, registry.statuses(5_000).single().availability)
        cap.available = false
        repeat(20) { assertEquals(Availability.IMPLEMENTED, registry.statuses(5_000).single().availability) }
        assertEquals(1,cap.probes)
        nanos = 5_000_000_000
        assertEquals(Availability.PERMISSION_REQUIRED, registry.statuses(5_000).single().availability)
        assertEquals(2,cap.probes)
        registry.statuses(); assertEquals(3,cap.probes)
        registry.invalidateDiscovery(); registry.statuses(5_000); assertEquals(4,cap.probes)
    }
    @Test fun registrationInvalidatesCachedCatalog() {
        val registry=CapabilityRegistry();registry.register(Probe());registry.statuses(5_000)
        registry.register(Probe("test.other"));assertEquals(2,registry.statuses(5_000).size)
    }
    @Test fun sharedSnapshotMeasuresEachGroupOnceAndReusesTheMeasuredDetail() {
        val registry=CapabilityRegistry();val key=Any();var probes=0
        repeat(9) { index -> registry.register(object : Capability {
            override val descriptor=Probe("test.read_$index").descriptor
            override fun availability():Availability=error("Must use grouped discovery")
            override fun discoveryAvailability(snapshot:DiscoverySnapshot)=snapshot.once(key) {probes++;Availability.PERMISSION_REQUIRED}
            override fun availabilityDetail():String?=error("Must use measured availability")
            override fun availabilityDetail(availability:Availability)=availability.name
            override suspend fun execute(args:JsonObject,ctx:ExecutionContext):CapabilityResult=error("unused")
        }) }
        val statuses=registry.statuses();assertEquals(1,probes)
        assertTrue(statuses.all {it.availability==Availability.PERMISSION_REQUIRED && it.detail=="PERMISSION_REQUIRED"})
        registry.statuses();assertEquals(2,probes)
    }
    @Test fun failedSharedProbeIsNotRepeatedPerTool() {
        val snapshot=DiscoverySnapshot();val key=Any();var probes=0
        repeat(9) {assertFailsWith<IllegalStateException> {snapshot.once(key) {probes++;error("Unavailable provider")}}}
        assertEquals(1,probes)
    }
    @Test fun invalidationDuringSlowProbeCannotRepublishStaleMetadataOrHoldRegistryMonitor() {
        val registry=CapabilityRegistry();val entered=CountDownLatch(1);val release=CountDownLatch(1);val probes=AtomicInteger()
        registry.register(object:Capability {
            override val descriptor=Probe().descriptor
            override fun availability():Availability {if(probes.incrementAndGet()==1) {entered.countDown();check(release.await(5,TimeUnit.SECONDS))};return Availability.IMPLEMENTED}
            override suspend fun execute(args:JsonObject,ctx:ExecutionContext):CapabilityResult=error("unused")
        })
        val thread=Thread {registry.statuses()};thread.start()
        try {
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            val invalidated=CountDownLatch(1)
            Thread {registry.invalidateDiscovery();registry.get("test.read");invalidated.countDown()}.start()
            assertTrue(invalidated.await(2,TimeUnit.SECONDS),"Provider IO held registry monitor")
        } finally {release.countDown();thread.join(5_000)}
        registry.statuses(5_000);assertEquals(2,probes.get())
    }
    @Test fun cachedReadyStateCannotAuthorizeExecutionAfterPermissionLoss()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val registry=CapabilityRegistry();val cap=Probe();registry.register(cap);registry.statuses(5_000);cap.available=false
            val engine=ExecutionEngine("fixture",registry,PolicyEngine(),MemoryStore(),{},RecordingApprovals(),
                {AgentGrant(it,it,scopes=listOf("*"))},{DeviceControls()},scope)
            val record=engine.submit(InvocationRequest(agentId="fixture",capabilityId="test.read"))
            assertEquals(RequestState.FAILED,record.state);assertEquals("UNAVAILABLE",record.errorCode)
            assertEquals(0,cap.executions)
            assertEquals(Availability.IMPLEMENTED,registry.statuses(5_000).single().availability)
        } finally {scope.cancel()}
    }
}
