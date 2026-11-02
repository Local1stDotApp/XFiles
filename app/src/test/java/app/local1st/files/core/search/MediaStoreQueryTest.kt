package app.local1st.files.core.search

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaStoreQueryTest {

    @Test
    fun aPlainQueryMatchesAnywhereInEitherCase() {
        assertEquals("*[iI][mM][gG]*", MediaStoreQuery.nameGlob("img"))
        assertEquals("*[iI][mM][gG]*", MediaStoreQuery.nameGlob("IMG"))
    }

    @Test
    fun caseFoldsBeyondAscii() {
        // LIKE would miss "ОТЧЕТ" for "Отчет"; each letter carries both cases instead.
        assertEquals("*[оО][тТ][чЧ][еЕ][тТ]*", MediaStoreQuery.nameGlob("Отчет"))
        assertEquals("*报告*", MediaStoreQuery.nameGlob("报告"))
    }

    @Test
    fun wildcardsMatchTheWholeNameAndUnderscoreIsLiteral() {
        assertEquals("[iI][mM][gG]_*.[jJ][pP][gG]", MediaStoreQuery.nameGlob("IMG_*.jpg"))
        assertEquals("[nN][oO][tT][eE]?", MediaStoreQuery.nameGlob("note?"))
    }

    @Test
    fun bracketsAreLiteral() {
        assertEquals("*[aA][[]1[]]*", MediaStoreQuery.nameGlob("a[1]"))
    }

    @Test
    fun likePatternsEscapeTheirWildcards() {
        assertEquals("/storage/my!_vol!%!!", MediaStoreQuery.likeEscape("/storage/my_vol%!"))
    }

    @Test
    fun selectionKeepsToTheRootsAndOutOfEachBin() {
        val selection = MediaStoreQuery.selection("img", listOf("/storage/emulated/0", "/storage/my_sd/"))

        assertEquals(
            "_display_name GLOB ?" +
                " AND (_data LIKE ? ESCAPE '!' OR _data LIKE ? ESCAPE '!')" +
                " AND NOT (_data LIKE ? ESCAPE '!' OR _data LIKE ? ESCAPE '!')" +
                " AND NOT (_data LIKE ? ESCAPE '!' OR _data LIKE ? ESCAPE '!')",
            selection.sql,
        )
        assertEquals(
            listOf(
                "*[iI][mM][gG]*",
                "/storage/emulated/0/%",
                "/storage/my!_sd/%",
                "/storage/emulated/0/.xfiles-trash",
                "/storage/emulated/0/.xfiles-trash/%",
                "/storage/my!_sd/.xfiles-trash",
                "/storage/my!_sd/.xfiles-trash/%",
            ),
            selection.args,
        )
        assertEquals(selection.sql.count { it == '?' }, selection.args.size)
    }
}
