package dev.siconnect.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

/** Exercises the production engine against actual filesystem effects on the host. */
@OptIn(ExperimentalCoroutinesApi::class)
class DispatchBoundaryTest {
    private suspend fun withWriteEngine(
        dispatcher: CoroutineDispatcher,
        check: suspend (ExecutionEngine, InvocationRequest, java.nio.file.Path, () -> Unit, CoroutineScope) -> Unit,
    ) {
        val directory = Files.createTempDirectory("siconnect-dispatch-")
        val output = directory.resolve("effect.txt")
        val runtime = CoroutineScope(SupervisorJob() + dispatcher)
        var grant = AgentGrant("agent", "Agent", scopes = listOf("test.write"), autoApproveUpTo = RiskClass.LOW_IMPACT)
        val registry = CapabilityRegistry().apply {
            register(object : Capability {
                override val descriptor = CapabilityDescriptor("test.write", title = "Write", description = "Host filesystem operation",
                    inputSchema = objectSchema(), backend = BackendKind.NATIVE, risk = RiskClass.LOW_IMPACT)
                override fun availability() = Availability.IMPLEMENTED
                override suspend fun execute(args: JsonObject, ctx: ExecutionContext): CapabilityResult {
                    Files.writeString(output, "executed")
                    return CapabilityResult(buildJsonObject { put("written", true) }, BackendKind.NATIVE)
                }
            })
        }
        val engine = ExecutionEngine("device", registry, PolicyEngine(), MemoryStore(), {}, RecordingApprovals(),
            { grant }, { DeviceControls() }, runtime)
        val request = InvocationRequest(requestId = "queued-write", agentId = "agent", capabilityId = "test.write")
        try { check(engine, request, output, { grant = grant.copy(revoked = true) }, runtime) }
        finally { runtime.cancel(); Files.deleteIfExists(output); Files.delete(directory) }
    }

    @Test fun cancellingBeforeTheCoroutineStartsRecordsNoExecution() = runTest {
        withWriteEngine(StandardTestDispatcher(testScheduler)) { engine, request, output, _, _ ->
            engine.submit(request, awaitCompletion = false)
            engine.cancel(request.requestId, "owner")
            runCurrent()
            assertFalse(Files.exists(output))
            assertEquals(RequestState.CANCELLED, engine.request(request.requestId)?.state)
            assertEquals(RequestState.CANCELLED, engine.submit(request).state)
        }
    }

    @Test fun cancelledRuntimeCannotLeaveAnAdmittedRequestExecuting() = runTest {
        withWriteEngine(StandardTestDispatcher(testScheduler)) { engine, request, output, _, runtime ->
            runtime.cancel()
            val result = engine.submit(request)
            assertFalse(Files.exists(output))
            assertEquals(RequestState.CANCELLED, result.state)
        }
    }

    @Test fun revocationAfterAdmissionPreventsTheQueuedEffect() = runTest {
        withWriteEngine(StandardTestDispatcher(testScheduler)) { engine, request, output, revoke, _ ->
            assertEquals(RequestState.EXECUTING, engine.submit(request, awaitCompletion = false).state)
            revoke()
            runCurrent()
            assertFalse(Files.exists(output), "Revoked authority must be checked at actual backend dispatch")
            assertEquals(RequestState.FAILED, engine.request(request.requestId)?.state)
            assertEquals("POLICY_DENIED", engine.request(request.requestId)?.errorCode)
        }
    }

    @Test fun anAuthorizedQueuedWriteStillCompletesAndReplaysItsOriginalResult() = runTest {
        withWriteEngine(StandardTestDispatcher(testScheduler)) { engine, request, output, _, _ ->
            val result = engine.submit(request)
            assertEquals(RequestState.SUCCEEDED, result.state)
            assertEquals("executed", Files.readString(output))
            Files.writeString(output, "owner changed it")
            assertEquals(result, engine.submit(request))
            assertEquals("owner changed it", Files.readString(output))
        }
    }
}
