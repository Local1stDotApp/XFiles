package app.local1st.files.core.search

import app.local1st.files.core.fs.TrashPaths

/**
 * File names a platform service already keeps for whole volumes, so a search of all storage
 * can skip the walk. Implementation: MediaStoreFileIndex (Android 11+).
 */
interface FileNameIndex {
    /** The ones among [volumeRoots] this index covers. */
    fun coveredRoots(volumeRoots: List<String>): Set<String>

    /**
     * Absolute paths below [roots] whose name matches [query] as [SearchEngine.search]
     * describes, most recently modified first, at most [limit] of them. Leaves out each
     * root's recycle bin.
     */
    fun matchingPaths(query: String, roots: List<String>, limit: Int): List<String>

    /**
     * Adds the folder the app just made at [path]. The index may not learn of an empty
     * folder on its own, and a search would then miss it by name. Returns at once.
     */
    fun addFolder(path: String)
}

/** The WHERE clause of a MediaStore files query. Plain strings, so JVM tests can check it. */
internal object MediaStoreQuery {
    // MediaStore.MediaColumns.DISPLAY_NAME and DATA.
    const val NAME = "_display_name"
    const val PATH = "_data"

    class Selection(val sql: String, val args: List<String>)

    /** Rows named like [query], below one of [roots], and outside every root's bin. */
    fun selection(query: String, roots: List<String>): Selection {
        require(roots.isNotEmpty()) { "No roots to search" }
        val below = roots.map { if (it.length > 1) it.trimEnd('/') else it }
        val sql = StringBuilder("$NAME GLOB ?")
        val args = arrayListOf(nameGlob(query))
        sql.append(" AND (").append(below.joinToString(" OR ") { "$PATH LIKE ? ESCAPE '!'" }).append(')')
        below.forEach { args += likeEscape(it.trimEnd('/')) + "/%" }
        // A volume's own bin only. A folder the user named .xfiles-trash elsewhere stays.
        // MediaStore indexes the bin despite its .nomedia, and left in, its rows would use up
        // the limit before a single real match.
        for (root in below) {
            val bin = likeEscape(TrashPaths.binRoot(root))
            sql.append(" AND NOT ($PATH LIKE ? ESCAPE '!' OR $PATH LIKE ? ESCAPE '!')")
            args += bin
            args += "$bin/%"
        }
        return Selection(sql.toString(), args)
    }

    /**
     * SQLite's LIKE folds case for ASCII only, so a Cyrillic or accented query would miss names
     * in the other case. GLOB is case-sensitive, and each cased letter becomes a class of both
     * its cases instead. Without '*' or '?' the query matches anywhere in the name; with them
     * the whole name, as the walk's matcher does.
     */
    fun nameGlob(query: String): String {
        val wildcard = '*' in query || '?' in query
        val out = StringBuilder(query.length * 4 + 2)
        if (!wildcard) out.append('*')
        for (c in query) {
            when (c) {
                '*', '?' -> out.append(c)
                // Literal brackets: a class holding just that character.
                '[', ']' -> out.append('[').append(c).append(']')
                else -> {
                    val lower = c.lowercaseChar()
                    val upper = c.uppercaseChar()
                    if (lower == upper) out.append(c) else out.append('[').append(lower).append(upper).append(']')
                }
            }
        }
        if (!wildcard) out.append('*')
        return out.toString()
    }

    /** [s] as a literal LIKE pattern under `ESCAPE '!'`. */
    fun likeEscape(s: String): String = buildString(s.length + 4) {
        for (c in s) {
            if (c == '!' || c == '%' || c == '_') append('!')
            append(c)
        }
    }
}
