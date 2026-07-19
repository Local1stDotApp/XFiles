package app.local1st.files.core.fs.priv

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider

sealed interface ShizukuState {
    data object NotInstalled : ShizukuState
    data object NotRunning : ShizukuState
    data object PermissionRequired : ShizukuState
    data object Ready : ShizukuState
}

/**
 * Process-wide view of Shizuku's binder and permission lifecycle. Merely discovering the
 * service never prompts: permission is requested only through [requestPermission].
 */
object ShizukuGate {
    private const val PERMISSION_REQUEST_CODE = 0x5846

    private val _state = MutableStateFlow<ShizukuState>(ShizukuState.NotRunning)
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    /** True when Shizuku says another permission request must not be shown. */
    @Volatile
    var permissionPermanentlyDenied: Boolean = false
        private set

    @Volatile
    private var appContext: Context? = null

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        // Every cached remote-process handle belongs to the dead binder. Dropping it here
        // ensures a later binder-received callback cannot reuse a stale shared shell.
        ShizukuTransport.reset()
        refresh()
    }
    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
            if (requestCode == PERMISSION_REQUEST_CODE) refresh()
        }

    @Synchronized
    fun initialize(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext

        // Binder accessors throw IllegalStateException before Shizuku's provider has delivered
        // a binder, including listener registration in some startup races.
        runCatching { Shizuku.addBinderReceivedListenerSticky(binderReceivedListener) }
        runCatching { Shizuku.addBinderDeadListener(binderDeadListener) }
        runCatching { Shizuku.addRequestPermissionResultListener(permissionResultListener) }
        refresh()
    }

    /** Unregisters listeners for hosts with a shorter lifetime than the application process. */
    @Synchronized
    fun shutdown() {
        runCatching { Shizuku.removeBinderReceivedListener(binderReceivedListener) }
        runCatching { Shizuku.removeBinderDeadListener(binderDeadListener) }
        runCatching { Shizuku.removeRequestPermissionResultListener(permissionResultListener) }
        ShizukuTransport.reset()
        appContext = null
        permissionPermanentlyDenied = false
        _state.value = ShizukuState.NotRunning
    }

    /**
     * Requests access only while the service is usable and denial is not permanent.
     * Returns whether a permission dialog was requested.
     */
    fun requestPermission(): Boolean {
        refresh()
        if (_state.value != ShizukuState.PermissionRequired || permissionPermanentlyDenied) {
            return false
        }
        return runCatching {
            // Pre-v11 services do not implement the permission API used by this transport.
            if (Shizuku.isPreV11()) return@runCatching false
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
            true
        }.getOrDefault(false)
    }

    internal fun refresh() {
        val context = appContext ?: return
        if (!isInstalled(context)) {
            permissionPermanentlyDenied = false
            _state.value = ShizukuState.NotInstalled
            return
        }

        val status = runCatching {
            val binder = Shizuku.getBinder()
            if (binder == null || !binder.isBinderAlive || !Shizuku.pingBinder()) {
                return@runCatching ShizukuState.NotRunning
            }
            if (Shizuku.isPreV11()) return@runCatching ShizukuState.NotRunning
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                permissionPermanentlyDenied = false
                ShizukuState.Ready
            } else {
                // In Shizuku's API this flag means the user selected deny-and-don't-ask-again;
                // callers must send the user to Shizuku rather than loop permission dialogs.
                permissionPermanentlyDenied = Shizuku.shouldShowRequestPermissionRationale()
                ShizukuState.PermissionRequired
            }
        }.getOrElse {
            permissionPermanentlyDenied = false
            ShizukuState.NotRunning
        }
        _state.value = status
    }

    private fun isInstalled(context: Context): Boolean = runCatching {
        // The permission is a global namespace entry. Looking it up works with package hiding,
        // recognizes Shizuku forks, and avoids adding a package-visibility <queries> entry.
        context.packageManager
            .getPermissionInfo(ShizukuProvider.PERMISSION, 0)
            .packageName
            .isNotEmpty()
    }.getOrDefault(false)
}
