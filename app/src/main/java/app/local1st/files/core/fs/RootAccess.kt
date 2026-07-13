package app.local1st.files.core.fs

/**
 * Process-wide gates for superuser access, mirrored from [app.local1st.files.core.prefs.SettingsRepo]
 * so the (synchronous) filesystem layer can consult them without a suspending read.
 *
 * - [enabled]: the user opted into root browsing in Settings. When false, no `su` probe runs
 *   and no root content is surfaced.
 * - [readOnly]: read-only root mode — root writes (create/rename/delete/overwrite) are blocked.
 *
 * Updated by a collector wired in `GraphInit`/`MainViewModel`; defaults are the safe off state.
 */
object RootAccess {
    @Volatile
    var enabled: Boolean = false

    @Volatile
    var readOnly: Boolean = true

    /** True when root browsing is enabled AND `su` actually grants uid 0. */
    fun usable(): Boolean = enabled && RootShell.isAvailable()
}
