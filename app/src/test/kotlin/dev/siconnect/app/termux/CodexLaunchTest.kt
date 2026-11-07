package dev.siconnect.app.termux

import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.concurrent.TimeUnit

class CodexLaunchTest {
    private val launcher = "/data/data/com.termux/files/usr/lib/node_modules/@openai/codex/bin/codex.js"
    private val workspace = "/data/data/com.termux/files/home/owner workspace"

    @Test fun promptCannotBecomeOptionsShellCommandsOrAnotherSession() {
        val prompt = "--resume; $(" + "id) `touch /tmp/unwanted`\nquote \" and space"
        val command = TermuxCommand.parse(CodexLaunch.fullAccessExec(launcher, workspace, prompt))
        assertEquals("/data/data/com.termux/files/usr/bin/sh", command.executable)
        assertEquals("/data/data/com.termux/files/usr/bin/node",command.arguments[7])
        assertEquals(launcher, command.arguments[8])
        assertEquals("exec", command.arguments[9])
        assertEquals("-lc",command.arguments[4])
        assertTrue(command.arguments[1].contains("</dev/null >/dev/null 2>&1"))
        assertTrue(command.arguments[5].contains("1>&3 2>&4 3>&- 4>&-"))
        assertTrue(command.arguments[5].contains("exec \"$@\" </dev/null"))
        assertEquals(listOf("--", prompt), command.arguments.takeLast(2))
        assertEquals(1, command.arguments.count { it == prompt })
        assertEquals(workspace, command.cwd)
        assertEquals(workspace, command.arguments[command.arguments.indexOf("--cd") + 1])
        assertNull(command.stdin)
        assertEquals(120000L, command.timeoutMs)
        assertFalse(command.arguments.contains("--last"))
        assertFalse(command.arguments.contains("--ignore-user-config"))
        assertFalse(command.arguments.contains("--ignore-rules"))
        assertFalse(command.arguments.contains("--dangerously-bypass-hook-trust"))
        assertFalse(command.arguments.contains("--ephemeral"))
    }

    @Test fun childIdentityIsStableBoundedAndDistinctAcrossParentRequests() {
        assertEquals(CodexLaunch.childRequestId("same"),CodexLaunch.childRequestId("same"))
        assertNotEquals(CodexLaunch.childRequestId("first"),CodexLaunch.childRequestId("second"))
        assertTrue(CodexLaunch.childRequestId("x".repeat(200)).length<=200)
    }

    @Test fun realLoginTransportSuppressesStartupNoiseButPreservesCommandStreamsArgvAndExit() {
        assumeTrue("Real POSIX shell required",File("/bin/sh").canExecute())
        val args=CodexLaunch.quietLoginArguments("/bin/sh","/bin/sh",listOf("-c",
            "test \"$1\" = 'literal ; $ argument' || exit 9; printf 'real-jsonl-output'; printf 'real-command-error' >&2; exit 7",
            "fixture","literal ; $ argument")).toMutableList()
        // Real commands at the quiet startup stage, before the production restore.
        args[5]="printf 'startup-noise'; printf 'startup-error' >&2; "+args[5]
        val process=ProcessBuilder(listOf("/bin/sh")+args).start()
        try {
            assertTrue(process.waitFor(5,TimeUnit.SECONDS))
            assertEquals(7,process.exitValue())
            assertEquals("real-jsonl-output",process.inputStream.bufferedReader().readText())
            assertEquals("real-command-error",process.errorStream.bufferedReader().readText())
        } finally {if(process.isAlive) process.destroyForcibly()}
    }

    @Test fun fullAccessIsExplicitPerRunAndRetainsTheRequestedProfile() {
        val command = TermuxCommand.parse(CodexLaunch.fullAccessExec(
            launcher, workspace, "Inspect this workspace", profile = "owner profile", allowOutsideGitRepository = true,
        ))
        val launcherIndex=command.arguments.indexOf(launcher)
        assertTrue("The real Codex argv must follow the login-shell transport",launcherIndex>0)
        val codexArguments=command.arguments.drop(launcherIndex+1)
        assertEquals("owner profile", codexArguments[codexArguments.indexOf("--profile") + 1])
        assertEquals("approval_policy=\"never\"", codexArguments[codexArguments.indexOf("-c") + 1])
        assertEquals("danger-full-access", codexArguments[codexArguments.indexOf("--sandbox") + 1])
        assertTrue(codexArguments.contains("--json"))
        assertTrue(codexArguments.contains("--skip-git-repo-check"))
        assertEquals(TermuxContract.MAX_OUTPUT_BYTES, command.maxOutputBytes)
        val defaults = TermuxCommand.parse(CodexLaunch.fullAccessExec(launcher, workspace, "Inspect"))
        assertFalse(defaults.arguments.contains("--skip-git-repo-check"))
        assertFalse(defaults.arguments.contains("--profile"))
    }

    @Test fun explicitCompatibilityScriptUsesFixedShellAndExactlyTheSameCodexArguments() {
        val script="/data/data/com.termux/files/home/owner scripts/codex ' ; $ literal.sh"
        val prompt="--resume; literal $("+"id) and newline\ntext"
        val direct=TermuxCommand.parse(CodexLaunch.fullAccessExec(launcher,workspace,prompt,profile="owner profile",allowOutsideGitRepository=true))
        val compatible=TermuxCommand.parse(CodexLaunch.fullAccessExec(workingDirectory=workspace,prompt=prompt,
            profile="owner profile",allowOutsideGitRepository=true,androidCompatibilityLauncher=script))
        assertEquals("/data/data/com.termux/files/usr/bin/sh",compatible.executable)
        assertEquals("/data/data/com.termux/files/usr/bin/sh",compatible.arguments[7])
        assertEquals(script,compatible.arguments[8])
        assertEquals(direct.arguments.drop(9),compatible.arguments.drop(9))
        assertEquals(listOf("--",prompt),compatible.arguments.takeLast(2))
        assertEquals(1,compatible.arguments.count {it==script})
        assertFalse(compatible.arguments[1].contains(script));assertFalse(compatible.arguments[5].contains(script))
        assertNull(compatible.stdin);assertEquals(workspace,compatible.cwd)
    }

    @Test fun missingBothRelativeAndNulCompatibilitySelectorsFailBeforeNativeDispatch() {
        val invalid=listOf<()->Unit>(
            {CodexLaunch.fullAccessExec(workingDirectory=workspace,prompt="Inspect")},
            {CodexLaunch.fullAccessExec(launcher,workspace,"Inspect",androidCompatibilityLauncher="/owner/wrapper.sh")},
            {CodexLaunch.fullAccessExec(workingDirectory=workspace,prompt="Inspect",androidCompatibilityLauncher="codex-android")},
            {CodexLaunch.fullAccessExec(workingDirectory=workspace,prompt="Inspect",androidCompatibilityLauncher="/owner/wrapper\u0000.sh")},
            {CodexLaunch.fullAccessExec(workingDirectory=workspace,prompt="Inspect",androidCompatibilityLauncher="")},
        )
        for(build in invalid) try {build();fail("Invalid launcher selection admitted")}
            catch(_:IllegalArgumentException) { }
    }

    @Test fun invalidInputCannotStartInteractiveStdinOrAnUnboundedBridgeRequest() {
        val invalid = listOf<() -> Unit>(
            { CodexLaunch.fullAccessExec(launcher, workspace, "") },
            { CodexLaunch.fullAccessExec(launcher, workspace, " \n ") },
            { CodexLaunch.fullAccessExec(launcher, workspace, "-") },
            { CodexLaunch.fullAccessExec(launcher, workspace, "a\u0000b") },
            { CodexLaunch.fullAccessExec(launcher, workspace, "x".repeat(8193)) },
            { CodexLaunch.fullAccessExec("codex.js", workspace, "Inspect") },
            { CodexLaunch.fullAccessExec("$launcher\u0000", workspace, "Inspect") },
            { CodexLaunch.fullAccessExec(launcher, "relative", "Inspect") },
            { CodexLaunch.fullAccessExec(launcher, workspace, "Inspect", profile = " ") },
            { CodexLaunch.fullAccessExec(launcher, workspace, "Inspect", profile = "a\u0000b") },
            { CodexLaunch.fullAccessExec(launcher, workspace, "Inspect", timeoutMs = 120001) },
        )
        for (build in invalid) {
            try { build(); fail("Invalid launch was accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
