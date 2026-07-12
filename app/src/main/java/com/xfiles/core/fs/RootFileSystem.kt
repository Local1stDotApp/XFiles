package com.xfiles.core.fs

import com.xfiles.core.util.FileTypes
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
        val path = dir.path
        // One `su` process per directory: classify (dir-follow), size and mtime per child.
        // stat's `-d` test follows symlinks so a link to a directory browses as a folder.
        val script = buildString {
            append("d=").append(RootShell.quote(path)).append('\n')
            append("cd \"\$d\" 2>/dev/null || { echo __XF_ERR__; exit 0; }\n")
            append("for f in * .*; do\n")
            append("  [ \"\$f\" = . ] && continue\n")
            append("  [ \"\$f\" = .. ] && continue\n")
            append("  [ -e \"\$f\" ] || [ -L \"\$f\" ] || continue\n")
            append("  if [ -d \"\$f\" ]; then t=d; else t=f; fi\n")
            append("  sm=\$(stat -c '%s|%Y' \"\$f\" 2>/dev/null || echo '0|0')\n")
            append("  printf '%s|%s|%s\\n' \"\$t\" \"\$sm\" \"\$f\"\n")
            append("done\n")
        }
        val output = RootShell.exec(script)
        if (output.contains("__XF_ERR__")) throw IOException("Cannot read ${dir.name}")

        val entries = ArrayList<XEntry>()
        output.lineSequence().forEach { line ->
            if (line.isEmpty()) return@forEach
            val parts = line.split("|", limit = 4)
            if (parts.size < 4) return@forEach
            val isDir = parts[0] == "d"
            val size = parts[1].toLongOrNull() ?: 0L
            val mtimeSec = parts[2].toLongOrNull() ?: 0L
            entries += toEntry(path, parts[3], isDir, size, mtimeSec * 1000L)
        }
        return entries
    }

    override fun stat(id: String): XEntry? {
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

    override fun openIn(entry: XEntry): InputStream = RootShell.openRead(entry.path)

    override fun openOut(parentDir: XEntry, name: String): OutputStream =
        RootShell.openWrite(XId.joinPath(parentDir.path, name))

    override fun mkdir(parentDir: XEntry, name: String): XEntry {
        val childPath = XId.joinPath(parentDir.path, name)
        RootShell.exec("mkdir -p ${RootShell.quote(childPath)}")
        return toEntry(parentDir.path, name, isDir = true, size = -1L, mtime = 0L)
    }

    override fun delete(entry: XEntry) {
        RootShell.exec("rm -rf ${RootShell.quote(entry.path)}")
    }

    override fun rename(entry: XEntry, newName: String): XEntry {
        val parentPath = entry.path.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" }
        val dst = XId.joinPath(parentPath, newName)
        RootShell.exec("mv -f ${RootShell.quote(entry.path)} ${RootShell.quote(dst)}")
        return toEntry(parentPath, newName, entry.isDir, entry.size, entry.mtime)
    }

    override fun canWrite(entry: XEntry): Boolean = true

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
            canWrite = true,
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
            canWrite = true,
            badge = "Superuser · /",
            localPath = null,
        )
    }
}
