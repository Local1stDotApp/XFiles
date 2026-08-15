package app.local1st.files.core.fs.priv

/**
 * Process-wide gates for privileged access, mirrored from [app.local1st.files.core.prefs.SettingsRepo]
 * so the (synchronous) filesystem layer can consult them without a suspending read.
 *
 * - [enabled]: the Settings switch for the home-screen Root row (on by default). When false,
 *   no `su` probe runs and no root content is surfaced.
 * - [readOnly]: read-only root mode — root writes (create/rename/delete/overwrite) are blocked.
 * - [preference]: which available privileged transport the user chose.
 *
 * Updated by a collector wired in `GraphInit`/`MainViewModel`; defaults are the safe off state.
 */
object PrivilegedAccess {
    @Volatile
    var enabled: Boolean = false

    @Volatile
    var readOnly: Boolean = true

    @Volatile
    var preference: TransportPref = TransportPref.AUTO

    val active: PrivilegedTransport?
        get() = activeFor(preference)

    /**
     * [active] without ever launching `su`: the su transport participates only when an earlier
     * probe already succeeded. Enhancement call sites (viewer/thumbnail fds, app-manager
     * affordances, the Settings caption) use this so that with root browsing on by default, a
     * rooted device never pops the superuser prompt outside deliberate privileged browsing.
     * They self-heal once a deliberate action (expanding Root, opening a root:// path, the
     * denied-listing fallback) has probed `su`. Shizuku is unaffected either way — its
     * availability check is a local binder ping, never a prompt.
     */
    val activeWithoutSuProbe: PrivilegedTransport?
        get() = activeFor(preference, probeSu = false)

    internal fun activeFor(
        selected: TransportPref,
        probeSu: Boolean = true,
    ): PrivilegedTransport? {
        fun su(): SuTransport? = SuTransport.takeIf {
            if (probeSu) it.isAvailable() else it.cachedAvailability() == true
        }
        return when (selected) {
            TransportPref.OFF -> null
            TransportPref.SU -> su()
            TransportPref.SHIZUKU -> ShizukuTransport.takeIf { it.isAvailable() }
            TransportPref.AUTO -> su() ?: ShizukuTransport.takeIf { it.isAvailable() }
        }
    }

    val caps: Caps
        get() = active?.caps ?: NO_CAPS

    /** True when root browsing is enabled AND a privileged transport is available. */
    fun usable(): Boolean = enabled && active != null

    /** [usable] without launching a `su` probe (see [activeWithoutSuProbe]). */
    fun usableWithoutSuProbe(): Boolean = enabled && activeWithoutSuProbe != null

    /** True only for an active transport whose opener rights can cross binder on a real fd. */
    fun canOpenFd(): Boolean = fdTransport() != null

    /**
     * The enabled transport that can pass a real fd across binder, else null. Never probes
     * `su`: fd support needs the Shizuku user service anyway, and the callers (media viewer,
     * thumbnail fetchers) decorate ordinary flows — one of them evaluates during composition
     * on the main thread, where a blocking `su` spawn plus a Magisk prompt must never happen.
     */
    fun fdTransport(): PrivilegedTransport? =
        if (!enabled) null
        else activeWithoutSuProbe?.takeIf { it.supportsFileDescriptors }

    private val NO_CAPS = Caps(
        appPrivateData = false,
        wholeFilesystem = false,
        remount = false,
        otherUsers = false,
    )
}
