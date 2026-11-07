package dev.siconnect.app.termux

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Builds a fresh noninteractive Codex run for an explicitly selected Full access workflow.
 * Submit the result through termux.execute's existing authorization and durable result handling.
 * This does not dispatch commands, modify saved config/auth, or resume another running session.
 *
 * CLI contract: https://learn.chatgpt.com/docs/non-interactive-mode and
 * https://learn.chatgpt.com/docs/cli/reference . Callers must first verify the installed CLI.
 * Existing user config, profiles, rules and trusted hooks still apply. This removes Codex's
 * sandbox for this invocation, not Android's UID/SELinux restrictions. Android support is not
 * implied. JSONL output can be truncated by the bridge; a callback alone is not task success.
 */
internal object CodexLaunch {
    private const val NODE = "/data/data/com.termux/files/usr/bin/node"
    private const val SHELL = "/data/data/com.termux/files/usr/bin/sh"
    private const val ENTER_LOGIN="exec 3>&1 4>&2; exec \"$@\" </dev/null >/dev/null 2>&1"
    private const val RUN_COMMAND="exec 1>&3 2>&4 3>&- 4>&-; exec \"$@\" </dev/null"

    /** Profiles load quietly; restore real output only for the exact noninteractive command argv. */
    internal fun quietLoginArguments(shell:String,executable:String,arguments:List<String>):List<String> =
        listOf("-c",ENTER_LOGIN,"siconnect-codex",shell,"-lc",RUN_COMMAND,"siconnect-codex-login",executable)+arguments
    internal fun childRequestId(parentRequestId:String):String="codex-termux-"+dev.siconnect.core.Canonical.sha256Hex(parentRequestId)

    /** Exactly one explicit path: npm JavaScript through Node, or an owner-selected script through sh. */
    fun fullAccessExec(
        nodeEntrypoint: String? = null,
        workingDirectory: String,
        prompt: String,
        profile: String? = null,
        allowOutsideGitRepository: Boolean = false,
        timeoutMs: Long = 120000,
        androidCompatibilityLauncher: String? = null,
    ): JsonObject {
        require((nodeEntrypoint!=null) != (androidCompatibilityLauncher!=null)) {
            "Choose exactly one nodeEntrypoint or androidCompatibilityLauncher"
        }
        val launcher=requireNotNull(nodeEntrypoint ?: androidCompatibilityLauncher)
        require(launcher.startsWith('/') && launcher.length <= 1024 && '\u0000' !in launcher) {
            "Codex launcher must be an absolute path without NUL"
        }
        require(prompt.isNotBlank() && prompt != "-") {
            "A nonblank prompt argument is required; '-' would read from stdin"
        }
        require(profile == null || profile.isNotBlank()) { "Profile must be omitted or nonblank" }
        val arguments = buildList {
            // The launcher path is one argv entry, never interpolated into shell code.
            add(launcher)
            add("exec")
            if (profile != null) { add("--profile"); add(profile) }
            add("-c"); add("approval_policy=\"never\"")
            add("--sandbox"); add("danger-full-access")
            add("--json")
            add("--cd"); add(workingDirectory)
            if (allowOutsideGitRepository) add("--skip-git-repo-check")
            add("--")
            add(prompt)
        }
        return buildJsonObject {
            put("executable", SHELL)
            val executable=if(nodeEntrypoint!=null) NODE else SHELL
            put("arguments", JsonArray(quietLoginArguments(SHELL,executable,arguments).map(::JsonPrimitive)))
            put("cwd", workingDirectory)
            put("timeoutMs", timeoutMs)
            put("maxOutputBytes", TermuxContract.MAX_OUTPUT_BYTES)
        }.also { TermuxCommand.parse(it) }
    }
}
