package app.local1st.files.core.fs.priv

/**
 * Process-wide gates for privileged access, mirrored from [app.local1st.files.core.prefs.SettingsRepo]
 * so the (synchronous) filesystem layer can consult them without a suspending read.
 *
 * - [enabled]: the user opted into root browsing in Settings. When false, no `su` probe runs
 *   and no root content is surfaced.
 * - [readOnly]: read-only root mode — root writes (create/rename/delete/overwrite) are blocked.
 *
 * Updated by a collector wired in `GraphInit`/`MainViewModel`; defaults are the safe off state.
 */
object PrivilegedAccess {
    @Volatile
    var enabled: Boolean = false

    @Volatile
    var readOnly: Boolean = true

    val active: PrivilegedTransport?
        get() = SuTransport.takeIf { it.isAvailable() }

    val caps: Caps
        get() = active?.caps ?: NO_CAPS

    /** True when root browsing is enabled AND a privileged transport is available. */
    fun usable(): Boolean = enabled && active != null

    private val NO_CAPS = Caps(
        appPrivateData = false,
        wholeFilesystem = false,
        remount = false,
        otherUsers = false,
    )
}
