package app.local1st.files.core.fs

import app.local1st.files.core.fs.priv.PrivilegedAccess
import app.local1st.files.core.fs.priv.PrivilegedTransport
import app.local1st.files.core.fs.priv.ShizukuTransport
import app.local1st.files.core.fs.priv.SuTransport
import app.local1st.files.core.fs.priv.TransportPref
import app.local1st.files.core.fs.priv.shQuote
import app.local1st.files.core.util.FileTypes
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.FileAlreadyExistsException

/**
 * Privileged filesystem for `root://` ids, backed by the active privileged transport:
 * `su` shell commands when superuser is granted (X-plore's "Root" access), else Shizuku's
 * adb shell for the paths it can reach. Reads/writes files the app otherwise cannot
 * touch (/data, /system, ...).
 *
 * All methods are blocking and must run on Dispatchers.IO. Entries never carry a
 * `localPath` (the paths are unreadable without root), so thumbnails/open-with are skipped
 * and the app's own viewers stream content back through [openIn].
 */
class RootFileSystem : XFileSystem {

    override val scheme: String = XId.SCHEME_ROOT

    override fun list(dir: XEntry): List<XEntry> {
        requireEnabled()
        val path = dir.path
        // Glob absolute paths instead of `cd`-ing in: on some devices (Magisk su + SELinux)
        // the shell can read an app's private data dir but cannot chdir into it — `cd` then
        // fails *silently* (rc=0, cwd stays `/`), which would list `/` under every folder.
        // `${d%/}` drops a trailing slash so the root `/` globs as `/*`, not `//*`.
        // Batched `stat` instead of the old per-file loop that forked a root `stat`
        // process per entry (~30ms each — seconds for /data). The glob is fed through
        // builtin printf + xargs -0 so huge directories never exceed the exec argv
        // limit (a direct `stat "$b"/*` dies with E2BIG around ~20k entries — and
        // silently, since stderr is suppressed). -L follows symlinks so a link to a
        // dir stays browsable; dangling links error out of stat, so a builtin-only
        // loop (zero extra forks) re-emits those, and `:` keeps exit 0.
        val script = buildString {
            append("d=").append(shQuote(path)).append('\n')
            append("[ -d \"\$d\" ] || { echo __XF_ERR__; exit 0; }\n")
            // A directory can pass -d yet not be readable (/data as the Shizuku shell
            // uid): the globs below then match nothing and the listing would come back
            // empty instead of denied. access(2) always passes for root, so su is
            // unaffected; for the shell uid it honors DAC and SELinux alike.
            append("[ -r \"\$d\" ] || { echo __XF_ERR__; exit 0; }\n")
            append("b=\${d%/}\n")
            append("printf '%s\\0' \"\$b\"/* \"\$b\"/.* | xargs -0 stat -L -c '%F|%s|%Y|%n' 2>/dev/null\n")
            append("for p in \"\$b\"/* \"\$b\"/.*; do\n")
            append("  [ -L \"\$p\" ] && [ ! -e \"\$p\" ] && printf 'broken link|0|0|%s\\n' \"\$p\"\n")
            append("done\n")
            append(":\n")
        }
        val output = transportFor(path).exec(script)
        // Exact-first-line match only: data lines carry full paths (stat %n), so a
        // substring check would false-positive on any path containing the marker.
        if (output.lineSequence().firstOrNull() == "__XF_ERR__") {
            throw IOException("Cannot read ${dir.name}")
        }

        val entries = ArrayList<XEntry>()
        output.lineSequence().forEach { line ->
            if (line.isEmpty()) return@forEach
            val parts = line.split("|", limit = 4)
            if (parts.size < 4) return@forEach
            val name = parts[3].substringAfterLast('/')
            if (name.isEmpty() || name == "." || name == "..") return@forEach
            val isDir = parts[0] == "directory"
            val size = parts[1].toLongOrNull() ?: 0L
            val mtimeSec = parts[2].toLongOrNull() ?: 0L
            entries += toEntry(path, name, isDir, size, mtimeSec * 1000L)
        }
        return entries
    }

    override fun stat(id: String): XEntry? {
        // Soft-fail when root browsing is off: a pinned/saved root:// id then reads as
        // "gone" (favorites show Not available, session restore falls back) without
        // spawning `su` behind the user's back.
        if (!PrivilegedAccess.enabled) return null
        val path = id.substringAfter("://")
        if (path == "/" || path.isEmpty()) return rootEntry()
        val script = buildString {
            append("p=").append(shQuote(path)).append('\n')
            append("if [ -d \"\$p\" ]; then t=d; ")
            append("elif [ -e \"\$p\" ] || [ -L \"\$p\" ]; then t=f; ")
            append("else echo __XF_NONE__; exit 0; fi\n")
            append("stat -c \"\$t|%s|%Y\" \"\$p\" 2>/dev/null\n")
        }
        val output = runCatching { PrivilegedAccess.active?.exec(script) }.getOrNull() ?: return null
        if (output.contains("__XF_NONE__")) return null
        val line = output.lineSequence().firstOrNull { it.contains('|') } ?: return null
        val parts = line.split("|")
        if (parts.size < 3) return null
        val isDir = parts[0] == "d"
        val size = parts[1].toLongOrNull() ?: 0L
        val mtimeSec = parts[2].toLongOrNull() ?: 0L
        val parentPath = path.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" }
        val name = path.trimEnd('/').substringAfterLast('/').ifEmpty { "/" }
        return toEntry(parentPath, name, isDir, size, mtimeSec * 1000L)
    }

    override fun openIn(entry: XEntry): InputStream {
        requireEnabled()
        return transportFor(entry.path).openRead(entry.path)
    }

    override fun openOut(parentDir: XEntry, name: String): OutputStream {
        requireWritable()
        return transportFor(parentDir.path).openWrite(XId.joinPath(parentDir.path, name))
    }

    override fun createFile(parentDir: XEntry, name: String): XEntry {
        requireWritable()
        requireSafeEntryName(name)
        val childPath = XId.joinPath(parentDir.path, name)
        val output = transportFor(parentDir.path).exec(buildString {
            append("p=").append(shQuote(childPath)).append('\n')
            append("if [ -e \"\$p\" ] || [ -L \"\$p\" ]; then echo __XF_EXISTS__; exit 0; fi\n")
            // noclobber makes the create exclusive even if another process wins after the check.
            append("if (set -C; : > \"\$p\") 2>/dev/null; then echo __XF_CREATED__; ")
            append("elif [ -e \"\$p\" ] || [ -L \"\$p\" ]; then echo __XF_EXISTS__; ")
            append("else exit 1; fi\n")
        })
        return when (output.lineSequence().firstOrNull()) {
            "__XF_CREATED__" -> toEntry(
                parentDir.path,
                name,
                isDir = false,
                size = 0L,
                mtime = System.currentTimeMillis(),
            )
            "__XF_EXISTS__" -> throw FileAlreadyExistsException(name)
            else -> throw IOException("Cannot create file $name in ${parentDir.name}")
        }
    }

    override fun mkdir(parentDir: XEntry, name: String): XEntry {
        requireWritable()
        val childPath = XId.joinPath(parentDir.path, name)
        transportFor(parentDir.path).exec("mkdir -p ${shQuote(childPath)}")
        return toEntry(parentDir.path, name, isDir = true, size = -1L, mtime = 0L)
    }

    override fun delete(entry: XEntry) {
        requireWritable()
        // Own process: a recursive delete can run for minutes and must not queue
        // every root listing behind it on the persistent shell's lock.
        transportFor(entry.path).execOneShot("rm -rf ${shQuote(entry.path)}")
    }

    override fun rename(entry: XEntry, newName: String): XEntry {
        requireWritable()
        val parentPath = entry.path.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" }
        val dst = XId.joinPath(parentPath, newName)
        transportFor(entry.path).exec("mv -f ${shQuote(entry.path)} ${shQuote(dst)}")
        return toEntry(parentPath, newName, entry.isDir, entry.size, entry.mtime)
    }

    override fun canWrite(entry: XEntry): Boolean = !PrivilegedAccess.readOnly

    /**
     * The Settings switch must gate every `su` use, not just the Root pane's visibility:
     * pinned root:// favorites and saved sessions keep valid root:// ids around after
     * the user turns the feature off.
     */
    private fun requireEnabled() {
        if (!PrivilegedAccess.enabled) throw IOException("Root browsing is disabled in Settings")
    }

    /** In read-only root mode, any write that would need superuser is refused up front. */
    private fun requireWritable() {
        requireEnabled()
        if (PrivilegedAccess.readOnly) throw IOException("Read-only root mode — enable writes in Settings")
    }

    /**
     * `/` is not su-only. Shell (Shizuku) can list it and enter `/system`, `/proc`,
     * `/storage`, Android/data — just not `/data` / `/data/data`. Prefer whatever
     * transport is already live; when nothing is live, retry `su` only for a stale
     * miss (see [shouldRetrySuForFilesystemRoot]).
     */
    private fun transportFor(path: String): PrivilegedTransport {
        requireEnabled()
        // Snapshot before reading `active`: on a cold cache that getter itself probes
        // su, and a retry right after that fresh failure would spawn su again — a
        // second Magisk prompt in the same call.
        val suKnownBeforeActive = SuTransport.cachedAvailability()
        PrivilegedAccess.active?.let { return it }
        if (shouldRetrySuForFilesystemRoot(path, PrivilegedAccess.preference, suKnownBeforeActive)) {
            SuTransport.reset()
            if (SuTransport.isAvailable()) return SuTransport
        }
        throw IOException(rootTransportUnavailableMessage(PrivilegedAccess.preference))
    }

    private fun toEntry(
        parentPath: String,
        name: String,
        isDir: Boolean,
        size: Long,
        mtime: Long,
    ): XEntry {
        val childPath = XId.joinPath(parentPath, name)
        return XEntry(
            id = XId.root(childPath),
            name = name,
            isDir = isDir,
            size = if (isDir) -1L else size,
            mtime = mtime,
            mime = if (isDir) null else FileTypes.mimeOf(name),
            hidden = name.startsWith("."),
            canRead = true,
            canWrite = !PrivilegedAccess.readOnly,
            kind = if (isDir) EntryKind.DIR else EntryKind.FILE,
            localPath = null,
        )
    }

    companion object {
        const val ROOT_ID = "${XId.SCHEME_ROOT}://" + "/"

        /**
         * The "Root" pane entry ("/"). Always `root://` — never a hollow `file:///` listing.
         * The badge uses the last `su` probe only, so drawing the home screen does not
         * launch Magisk. Expanding the row is what retries the grant.
         */
        fun rootEntry(): XEntry {
            val suKnown = SuTransport.cachedAvailability()
            val shizukuCoversData = ShizukuTransport.isAvailable() &&
                PrivilegedAccess.preference != TransportPref.SU &&
                PrivilegedAccess.preference != TransportPref.OFF
            return XEntry(
                id = ROOT_ID,
                name = "Root",
                isDir = true,
                kind = EntryKind.ROOT,
                canRead = true,
                canWrite = suKnown != false && !PrivilegedAccess.readOnly,
                badge = rootTreeBadge(suKnown, shizukuCoversData, PrivilegedAccess.readOnly),
                localPath = null,
            )
        }
    }
}

internal fun isFilesystemRoot(path: String): Boolean {
    val trimmed = path.trim()
    return trimmed.isEmpty() || trimmed == "/"
}

internal fun rootTreeBadge(
    suKnown: Boolean?,
    shizukuCoversData: Boolean,
    readOnly: Boolean,
): String = when {
    suKnown == true -> if (readOnly) "Superuser · read-only" else "Superuser · /"
    shizukuCoversData -> if (readOnly) "Shizuku · read-only" else "Shizuku · /"
    suKnown == false -> "Not available"
    else -> if (readOnly) "Superuser · read-only" else "Superuser · /"
}

/**
 * Whether opening `/` should retry a previously failed `su` probe, so a Magisk grant
 * issued after a denial can land. Only a *stale* miss retries — one recorded before this
 * call. [suKnownBeforeActive] == null means the probe inside [PrivilegedAccess.active]
 * just ran (and failed) for this very call; probing again would only repeat the Magisk
 * prompt that was just denied.
 */
internal fun shouldRetrySuForFilesystemRoot(
    path: String,
    preference: TransportPref,
    suKnownBeforeActive: Boolean?,
): Boolean = isFilesystemRoot(path) &&
    suKnownBeforeActive == false &&
    (preference == TransportPref.SU || preference == TransportPref.AUTO)

internal fun rootTransportUnavailableMessage(preference: TransportPref): String = when (preference) {
    TransportPref.OFF -> FILESYSTEM_ROOT_TRANSPORT_OFF_MESSAGE
    TransportPref.SHIZUKU ->
        "Shizuku is not running. Start it to browse /, or switch the transport to Auto / Root (su)."
    // Forced su never falls back to Shizuku, so starting it would not help here.
    TransportPref.SU ->
        "Root access is unavailable. Grant superuser (su), or switch the transport to Auto / Shizuku."
    TransportPref.AUTO ->
        "Root access is unavailable. Grant superuser (su) or start Shizuku to browse /."
}

/** The transport preference is Off: point at that switch, not at a missing su grant. */
internal const val FILESYSTEM_ROOT_TRANSPORT_OFF_MESSAGE =
    "Privileged transport is set to Off in Settings."
