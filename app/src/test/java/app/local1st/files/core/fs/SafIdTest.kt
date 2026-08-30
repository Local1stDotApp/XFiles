package app.local1st.files.core.fs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SafIdTest {

    @Test
    fun roundTripDocumentIdsContainingColonAndSlash() {
        val documentId = "primary:Download/photos"
        val id = XId.saf("loc-1", listOf(documentId, "child:id"))
        assertEquals("saf", XId.schemeOf(id))
        assertEquals("loc-1", XId.safLocationId(id))
        assertEquals(listOf(documentId, "child:id"), XId.safDocumentIds(id))
        assertEquals(XId.saf("loc-1", listOf(documentId)), XId.parent(id))
        assertEquals(XId.saf("loc-1"), XId.parent(XId.saf("loc-1", listOf(documentId))))
        assertNull(XId.parent(XId.saf("loc-1")))
    }

    @Test
    fun childAppendsEncodedDocumentId() {
        val parent = XEntry(id = XId.saf("abc"), name = "NAS", isDir = true, kind = EntryKind.LOCATION)
        val childId = XId.child(parent, "primary:foo/bar")
        assertEquals(listOf("primary:foo/bar"), XId.safDocumentIds(childId))
        assertEquals(XId.saf("abc"), XId.parent(childId))
    }

    @Test
    fun encodeThenDecodePreservesPercentAndNonAscii() {
        val raw = "café 100%"
        assertEquals(raw, decodeSafSegment(encodeSafSegment(raw)))
    }
}
