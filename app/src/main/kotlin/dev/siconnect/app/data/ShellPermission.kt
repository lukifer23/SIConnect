package dev.siconnect.app.data

import dev.siconnect.core.AgentGrant
import dev.siconnect.core.ShellMode

/** Changing arbitrary-shell permission preserves unrelated app and native standing grants. */
internal fun AgentGrant.withShellPermission(always: Boolean): AgentGrant =
    if (always) copy(shellMode = ShellMode.BROAD, scopes = (scopes + "requests.*").distinct())
    else copy(shellMode = ShellMode.APPROVAL_REQUIRED,
        standing = standing.filterNot { it.capabilityId == "shell.execute" }, shellStandingCommands = emptyList())
