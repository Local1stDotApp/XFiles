package app.local1st.files.core.fs

import java.io.File

/**
 * Same-volume rename, then the API 26–29 SAF grant when `File.renameTo` cannot
 * cross directories on a secondary volume. A missing grant fails the move and
 * leaves the file where it was.
 */
class VolumeTrashMover(
    private val legacySaf: LegacySafAccess?,
) : TrashMover {
    override fun move(source: File, destDir: File): Boolean {
        val target = File(destDir, source.name)
        // exists() follows links, so a dangling symlink would be replaced by renameTo.
        if (java.nio.file.Files.isSymbolicLink(target.toPath())) return false
        // renameTo cannot cross directories on an SD card. exists() can stay true
        // after that name was just vacated, so it must not skip the provider move.
        // moveInto rejects a name the provider still lists.
        val sourceWasNode = nodeSurvivedDelete(source)
        val targetWasNode = nodeSurvivedDelete(target)
        val renamed = source.renameTo(target)
        // A grant-only source is already not a node, and exists() on a just-vacated
        // name stays true. That is not a finished rename; the provider still has to move it.
        val landed = !renamed && renameLanded(sourceWasNode, targetWasNode, source, target)
        return finishVolumeMove(renamed, landed) {
            // moveDocument follows a directory symlink and moves the target.
            if (java.nio.file.Files.isSymbolicLink(source.toPath())) false
            else legacySaf?.moveInto(source, destDir) == true
        }
    }
}

/**
 * A failed [File.renameTo] still tries the SAF move. [File.exists] on a just-vacated
 * name is not proof the provider still has that document.
 */
internal fun finishVolumeMove(
    renamed: Boolean,
    sourceGoneTargetPresent: Boolean,
    moveInto: () -> Boolean,
): Boolean = renamed || sourceGoneTargetPresent || moveInto()

/** True only when [source] is no longer a node and [target] is. */
internal fun movedSourceIsGone(source: File, target: File): Boolean =
    nodeSurvivedDelete(target) && !nodeSurvivedDelete(source)

/**
 * True only when a real source node landed on a name that was free.
 * A target that already looked present, or a source File could not see, is not a move.
 */
internal fun renameLanded(
    sourceWasNode: Boolean,
    targetWasNode: Boolean,
    source: File,
    target: File,
): Boolean = sourceWasNode && !targetWasNode && movedSourceIsGone(source, target)

/**
 * Rollback of [DocumentsContract.moveDocument] failed. A null query means the
 * provider returned a URI we could not re-read; the bytes are still moved.
 */
internal fun safMoveKeptAfterFailedRollback(movedName: String?, sourceName: String): Boolean =
    movedName == null || movedName == sourceName
