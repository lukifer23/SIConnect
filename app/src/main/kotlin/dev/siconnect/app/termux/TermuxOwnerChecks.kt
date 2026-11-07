package dev.siconnect.app.termux

internal object TermuxOwnerChecks {
    private const val PREFIX="/data/data/com.termux/files/usr/bin/"
    private const val CODEX_CHECK="command -v codex >/dev/null 2>&1 || exit 127; set -- \"$(command -v codex)\"; case \"$(readlink -f \"$1\")\" in *.js|*.mjs|*.cjs) node \"$1\" login status >/dev/null 2>&1 ;; *) \"$1\" login status >/dev/null 2>&1 ;; esac"

    // Redirect before launching a login shell: its profile scripts can print too.
    // Positional argv preserves exact arguments without interpolating shell input.
    fun silentCommandArguments(executable:String,arguments:List<String>):List<String> =
        listOf("-c","exec \"$@\" >/dev/null 2>&1","siconnect-owner-check",executable)+arguments
    fun codexArguments():List<String> = silentCommandArguments(PREFIX+"sh",listOf("-lc",CODEX_CHECK))
}
