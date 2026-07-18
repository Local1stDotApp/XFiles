package app.local1st.files.core.fs

import app.local1st.files.core.util.FileTypes
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Superuser filesystem for `root://` ids, backed by `su` shell commands (X-plore's
 * "Root" access). Reads/writes files the app otherwise cannot touch (/data, /system, ...).
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
            append("d=").append(RootShell.quote(path)).append('\n')
            append("[ -d \"\$d\" ] || { echo __XF_ERR__; exit 0; }\n")
            append("b=\${d%/}\n")
            append("printf '%s\\0' \"\$b\"/* \"\$b\"/.* | xargs -0 stat -L -c '%F|%s|%Y|%n' 2>/dev/null\n")
            append("for p in \"\$b\"/* \"\$b\"/.*; do\n")
            append("  [ -L \"\$p\" ] && [ ! -e \"\$p\" ] && printf 'broken link|0|0|%s\\n' \"\$p\"\n")
            append("done\n")
            append(":\n")
        }
        val output = RootShell.exec(script)
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
        if (!RootAccess.enabled) return null
        val path = id.substringAfter("://")
        if (path == "/" || path.isEmpty()) return rootEntry()
        val script = buildString {
            append("p=").append(RootShell.quote(path)).append('\n')
            append("if [ -d \"\$p\" ]; then t=d; ")
            append("elif [ -e \"\$p\" ] || [ -L \"\$p\" ]; then t=f; ")
            append("else echo __XF_NONE__; exit 0; fi\n")
            append("stat -c \"\$t|%s|%Y\" \"\$p\" 2>/dev/null\n")
        }
        val output = runCatching { RootShell.exec(script) }.getOrNull() ?: return null
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
        return RootShell.openRead(entry.path)
    }

    override fun openOut(parentDir: XEntry, name: String): OutputStream {
        requireWritable()
        return RootShell.openWrite(XId.joinPath(parentDir.path, name))
    }

    override fun mkdir(parentDir: XEntry, name: String): XEntry {
        requireWritable()
        val childPath = XId.joinPath(parentDir.path, name)
        RootShell.exec("mkdir -p ${RootShell.quote(childPath)}")
        return toEntry(parentDir.path, name, isDir = true, size = -1L, mtime = 0L)
    }

    override fun delete(entry: XEntry) {
        requireWritable()
        // Own process: a recursive delete can run for minutes and must not queue
        // every root listing behind it on the persistent shell's lock.
        RootShell.execOneShot("rm -rf ${RootShell.quote(entry.path)}")
    }

    override fun rename(entry: XEntry, newName: String): XEntry {
        requireWritable()
        val parentPath = entry.path.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" }
        val dst = XId.joinPath(parentPath, newName)
        RootShell.exec("mv -f ${RootShell.quote(entry.path)} ${RootShell.quote(dst)}")
        return toEntry(parentPath, newName, entry.isDir, entry.size, entry.mtime)
    }

    override fun canWrite(entry: XEntry): Boolean = !RootAccess.readOnly

    /**
     * The Settings switch must gate every `su` use, not just the Root pane's visibility:
     * pinned root:// favorites and saved sessions keep valid root:// ids around after
     * the user turns the feature off.
     */
    private fun requireEnabled() {
        if (!RootAccess.enabled) throw IOException("Root browsing is disabled in Settings")
    }

    /** In read-only root mode, any write that would need superuser is refused up front. */
    private fun requireWritable() {
        requireEnabled()
        if (RootAccess.readOnly) throw IOException("Read-only root mode — enable writes in Settings")
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
            canWrite = !RootAccess.readOnly,
            kind = if (isDir) EntryKind.DIR else EntryKind.FILE,
            localPath = null,
        )
    }

    companion object {
        const val ROOT_ID = "${XId.SCHEME_ROOT}://" + "/"

        /** The "Root" pane entry ("/") browsed as superuser. */
        fun rootEntry(): XEntry = XEntry(
            id = ROOT_ID,
            name = "Root",
            isDir = true,
            kind = EntryKind.ROOT,
            canRead = true,
            canWrite = !RootAccess.readOnly,
            badge = if (RootAccess.readOnly) "Superuser · read-only" else "Superuser · /",
            localPath = null,
        )
    }
}
