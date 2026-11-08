package app.local1st.files.core.archive

/**
 * Where archive entries land, decided one way for browsing and for extracting.
 *
 * Entry names are normalized: backslashes become slashes, and empty, `.` and `..` segments go,
 * so nothing escapes the archive's root. Among entries with the same path the last one wins,
 * and a path that something else uses as a folder is a folder.
 */
internal object ArchivePaths {

    class Item(val name: String, val isDirectory: Boolean)

    class Layout(private val files: Array<String?>, val directories: Collection<String>) {
        /** The normalized path item [index] extracts to, or null when it extracts nothing. */
        fun fileAt(index: Int): String? = files[index]
    }

    fun normalize(rawName: String): String =
        rawName.replace('\\', '/')
            .split('/')
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .joinToString("/")

    fun layout(items: List<Item>): Layout {
        val directories = LinkedHashSet<String>()
        val lastFile = HashMap<String, Int>()
        for ((index, item) in items.withIndex()) {
            val path = normalize(item.name)
            if (path.isEmpty()) continue
            var slash = path.indexOf('/')
            while (slash >= 0) {
                directories += path.substring(0, slash)
                slash = path.indexOf('/', slash + 1)
            }
            if (item.isDirectory) directories += path else lastFile[path] = index
        }
        val files = arrayOfNulls<String>(items.size)
        for ((path, index) in lastFile) {
            if (path !in directories) files[index] = path
        }
        return Layout(files, directories)
    }
}
