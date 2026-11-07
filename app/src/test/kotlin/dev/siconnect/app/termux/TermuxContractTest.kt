package dev.siconnect.app.termux

import dev.siconnect.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TermuxContractTest {
    private fun args(executable:String="/data/data/com.termux/files/usr/bin/id",arguments:List<String> = listOf("-u"))=buildJsonObject {
        put("executable",executable);put("arguments",JsonArray(arguments.map(::JsonPrimitive)))
    }
    @Test fun directArgvPreservesSpacesMetacharactersAndNoImplicitShell() {
        val command=TermuxCommand.parse(args(arguments=listOf("one two","$(id)","; rm x")))
        assertEquals(listOf("one two","$(id)","; rm x"),command.arguments)
        assertNull(command.cwd);assertNull(command.stdin);assertEquals(30000L,command.timeoutMs)
        assertNotEquals(TermuxContract.policyCommand(args(arguments=listOf("a b"))),TermuxContract.policyCommand(args(arguments=listOf("a","b"))))
    }
    @Test fun commandValidationRejectsRelativeNulOversizeAndUnboundedWaits() {
        val invalid=listOf(args("id"),args("/bin/id\u0000"),args(arguments=listOf("x\u0000")),args(arguments=List(129) {"x"}),
            args(arguments=List(9) {"x".repeat(8192)}),JsonObject(args()+mapOf("timeoutMs" to JsonPrimitive(120001))),
            JsonObject(args()+mapOf("cwd" to JsonPrimitive("relative"))))
        for(value in invalid) try {TermuxCommand.parse(value);fail("Invalid command admitted")}
            catch(_:IllegalArgumentException) { }
    }
    @Test fun utf8PreviewNeverExceedsBytesOrSplitsCharacters() {
        assertEquals("a",boundedTermuxText("a😀b",4).text)
        assertEquals("a😀",boundedTermuxText("a😀b",5).text)
        for(limit in 1..15) {
            val bounded=boundedTermuxText("é😀中".repeat(10),limit)
            assertTrue(bounded.truncated);assertTrue(bounded.bytes<=limit)
            assertEquals(bounded.bytes,bounded.text.toByteArray(Charsets.UTF_8).size)
            assertFalse(bounded.text.contains('\ufffd'))
        }
    }
    @Test fun callbackApiVersionDoesNotInferPermissionOrAuthentication() {
        assertEquals(false,TermuxContract.supportsResultApi("0.95"))
        assertEquals(false,TermuxContract.supportsResultApi("0.108"))
        assertEquals(true,TermuxContract.supportsResultApi("0.109"))
        assertEquals(true,TermuxContract.supportsResultApi("0.118.3+apt-android-7"))
        assertNull(TermuxContract.supportsResultApi("unknown"))
    }
    @Test fun officialCallbackSuccessIsMinusOneAndCommandExitSuccessIsZero() {
        assertEquals(-1,TermuxContract.RESULT_ERR_SUCCESS)
        assertTrue(TermuxContract.commandSucceeded(-1,0))
        assertFalse(TermuxContract.commandSucceeded(0,0)) // Android RESULT_CANCELED
        assertFalse(TermuxContract.commandSucceeded(2,0)) // Termux internal failure
        assertFalse(TermuxContract.commandSucceeded(-1,1)) // Actual command nonzero exit
        assertFalse(TermuxContract.commandSucceeded(-1,null))
        assertFalse(TermuxContract.commandSucceeded(null,0))
        assertFalse(TermuxContract.completeCallbackStatus(-1,null))
        assertTrue(TermuxContract.completeCallbackStatus(-1,0))
        assertTrue(TermuxContract.completeCallbackStatus(0,null))
    }
    @Test fun termuxExecutionUsesExistingShellApprovalRulesAndAgentScopes() {
        val input=args();val assessment=RiskAssessment(RiskClass.HIGH_IMPACT,"Termux",shellCommand=TermuxContract.policyCommand(input))
        val descriptor=CapabilityDescriptor("termux.execute",title="Termux",description="",inputSchema=objectSchema(),
            backend=BackendKind.NATIVE,risk=RiskClass.HIGH_IMPACT,interaction=InteractionMode.BACKGROUND)
        val policy=PolicyEngine();val controls=DeviceControls()
        val custom=AgentGrant("agent","agent",scopes=listOf("termux.*"),autoApproveUpTo=RiskClass.HIGH_IMPACT)
        fun decision(grant:AgentGrant)=policy.evaluate(grant,descriptor,input,assessment,Availability.IMPLEMENTED,controls,Origin.REMOTE,100)
        assertEquals(PolicyOutcome.REQUIRE_APPROVAL,decision(custom).outcome)
        assertEquals(PolicyOutcome.ALLOW,decision(custom.copy(shellMode=ShellMode.BROAD)).outcome)
        assertEquals(PolicyOutcome.DENY,decision(custom.copy(scopes=listOf("device.*"))).outcome)
        assertEquals(PolicyOutcome.DENY,decision(custom.copy(revoked=true,shellMode=ShellMode.BROAD)).outcome)
    }
}
