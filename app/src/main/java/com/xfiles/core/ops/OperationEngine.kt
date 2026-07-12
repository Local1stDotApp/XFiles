package com.xfiles.core.ops

import kotlinx.coroutines.flow.StateFlow

/** Handle for one running operation. */
interface RunningOp {
    val id: Long
    val progress: StateFlow<OpProgress>

    /** Non-null while state == AWAITING_CONFLICT; UI shows the conflict dialog. */
    val pendingConflict: StateFlow<Conflict?>

    fun resolveConflict(resolution: ConflictResolution)

    fun cancel()
}

/**
 * Executes [FileOp]s off the main thread, one coroutine per op.
 * Implementation: DefaultOperationEngine (core/ops).
 */
interface OperationEngine {
    /** Ops that are running or awaiting a conflict decision. Finished ops drop off. */
    val active: StateFlow<List<RunningOp>>

    /** Fired once per finished op with a user-readable result message (snackbar). */
    val events: kotlinx.coroutines.flow.SharedFlow<OpEvent>

    fun submit(op: FileOp): RunningOp
}

data class OpEvent(
    val message: String,
    val success: Boolean,
    /** Container ids whose contents changed and should be refreshed in the UI. */
    val dirtyDirIds: Set<String> = emptySet(),
)
