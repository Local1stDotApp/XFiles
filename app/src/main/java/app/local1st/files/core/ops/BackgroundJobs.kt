package app.local1st.files.core.ops

import android.os.SystemClock
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Progress of one [BackgroundJob]. [totalBytes] is 0 while the size is unknown. */
data class JobProgress(
    val title: String,
    val message: String,
    val doneBytes: Long = 0,
    val totalBytes: Long = 0,
    val startedAtRealtimeMillis: Long = 0,
) {
    val indeterminate: Boolean get() = totalBytes <= 0

    val fraction: Float
        get() = if (totalBytes > 0) (doneBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
        else 0f
}

/**
 * A long-running unit of work that isn't a [FileOp] — today the package-install pipeline
 * (bundle conversion, session writes, OBB placement).
 *
 * Registering one keeps [OpsService] alive, so the work gets a foreground notification and a
 * wake lock instead of being frozen the moment the user leaves the app.
 */
class BackgroundJob internal constructor(
    val id: String,
    title: String,
    message: String,
) {

    private val _progress = MutableStateFlow(
        JobProgress(title, message, startedAtRealtimeMillis = SystemClock.elapsedRealtime()),
    )
    val progress: StateFlow<JobProgress> = _progress.asStateFlow()

    @Volatile
    private var coroutine: Job? = null

    @Volatile
    private var cancelled = false

    private var lastPublishNanos = 0L

    /** The coroutine to interrupt when the user cancels from the notification or the card. */
    fun attach(coroutine: Job) {
        this.coroutine = coroutine
        if (cancelled) coroutine.cancel()
    }

    fun cancel() {
        cancelled = true
        coroutine?.cancel()
    }

    /** Polled by the blocking helpers between chunks; they abort once this turns true. */
    fun isCancelled(): Boolean = cancelled

    /** Enters a new phase. Shown verbatim, and resets the bar to indeterminate. */
    fun message(message: String) {
        _progress.value = _progress.value.copy(message = message, doneBytes = 0, totalBytes = 0)
        lastPublishNanos = 0
    }

    /** Byte progress, published at most every 100 ms so the notification isn't spammed. */
    fun bytes(done: Long, total: Long) {
        val now = System.nanoTime()
        if (now - lastPublishNanos < PUBLISH_INTERVAL_NANOS) return
        lastPublishNanos = now
        _progress.value = _progress.value.copy(doneBytes = done, totalBytes = total)
    }

    private companion object {
        const val PUBLISH_INTERVAL_NANOS = 100_000_000L
    }
}

/** Registry of running [BackgroundJob]s; [OpsService] mirrors it into its notification. */
object BackgroundJobs {

    private val _active = MutableStateFlow<List<BackgroundJob>>(emptyList())
    val active: StateFlow<List<BackgroundJob>> = _active.asStateFlow()

    /**
     * User-facing messages from jobs. Jobs outlive the ViewModel that submitted them, so their
     * outcome can't be posted to that instance's snackbar — whoever is on screen now collects
     * this instead. Messages raised while no screen is up are dropped, same as a missed toast.
     */
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /**
     * Registers a job, or returns null when [id] is already running. Being app-wide, that
     * doubles as the guard against submitting the same work twice — it outlives the ViewModel
     * that started it, so a recreated Activity can't race an install that is still going.
     */
    @Synchronized
    fun start(id: String, title: String, message: String): BackgroundJob? {
        if (_active.value.any { it.id == id }) return null
        return BackgroundJob(id, title, message).also { job -> _active.update { it + job } }
    }

    @Synchronized
    fun finish(job: BackgroundJob) {
        _active.update { list -> list.filterNot { it === job } }
    }
}
