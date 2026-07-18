package app.local1st.files.core.util

import java.io.IOException

/**
 * Decodes Android binary XML (AXML — the compiled AndroidManifest.xml and compiled resource
 * XML format, per AOSP ResourceTypes.h) into indented XML text, so tapping AndroidManifest.xml
 * inside an APK shows readable XML instead of binary garbage. Pure JVM, no Android imports.
 */
object AxmlDecoder {

    /** True if [bytes] look like Android binary XML (RES_XML_TYPE magic). */
    fun isAxml(bytes: ByteArray): Boolean =
        bytes.size >= 8 &&
            bytes[0].toInt() == 0x03 && bytes[1].toInt() == 0x00 &&
            bytes[2].toInt() == 0x08 && bytes[3].toInt() == 0x00

    /** Decodes AXML to indented UTF-8 XML text. Throws IOException on malformed input. */
    @Throws(IOException::class)
    fun decode(bytes: ByteArray): String {
        if (!isAxml(bytes)) throw IOException("Not Android binary XML")
        try {
            return Parser(bytes).parse()
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Malformed binary XML (${e.javaClass.simpleName})")
        }
    }

    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_XML_RESOURCE_MAP_TYPE = 0x0180
    private const val RES_XML_START_NAMESPACE_TYPE = 0x0100
    private const val RES_XML_END_NAMESPACE_TYPE = 0x0101
    private const val RES_XML_START_ELEMENT_TYPE = 0x0102
    private const val RES_XML_END_ELEMENT_TYPE = 0x0103
    private const val RES_XML_CDATA_TYPE = 0x0104
    private const val UTF8_FLAG = 0x100

    private const val ANDROID_URI = "http://schemas.android.com/apk/res/android"

    private val DIMENSION_UNITS = arrayOf("px", "dip", "sp", "pt", "in", "mm")
    // MANTISSA_MULT and radix scales from ResourceTypes.h complexToFloat.
    private val RADIX_MULTS = floatArrayOf(0.00390625f, 3.0517578E-5f, 1.1920929E-7f, 4.656613E-10f)

    // Fallback names for common framework attributes when the pool string is empty
    // (aapt normally keeps attribute names, so this table is rarely consulted).
    private val ANDROID_ATTR_NAMES = mapOf(
        0x01010000 to "theme", 0x01010001 to "label", 0x01010002 to "icon",
        0x01010003 to "name", 0x01010006 to "permission", 0x0101000e to "enabled",
        0x0101000f to "debuggable", 0x01010010 to "exported", 0x01010011 to "process",
        0x01010018 to "authorities", 0x0101001d to "launchMode", 0x0101001f to "configChanges",
        0x01010024 to "value", 0x01010025 to "resource", 0x01010026 to "mimeType",
        0x01010027 to "scheme", 0x01010028 to "host", 0x0101002a to "path",
        0x0101020c to "minSdkVersion", 0x0101021b to "versionCode", 0x0101021c to "versionName",
        0x01010270 to "targetSdkVersion", 0x01010271 to "maxSdkVersion",
        0x010102b7 to "installLocation", 0x01010572 to "compileSdkVersion",
        0x01010573 to "compileSdkVersionCodename",
    )

    private class Parser(private val b: ByteArray) {

        private var pool: StringPool? = null
        private var resourceMap = IntArray(0)
        private val nsPrefixByUri = HashMap<String, String>()
        // Namespaces buffered from START_NAMESPACE and declared on the next start element.
        private val pendingNs = ArrayList<Pair<String, String>>()
        private val openTags = ArrayList<String>()
        private var tagOpen = false // last start tag still awaiting '>' or '/>'
        private val out = StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")

        fun parse(): String {
            val total = minOf(u32(4), b.size.toLong()).toInt()
            var off = u16(2)
            if (off < 8) throw IOException("Bad root header size")
            while (off + 8 <= total) {
                val type = u16(off)
                val headerSize = u16(off + 2)
                val size = u32(off + 4).toInt()
                if (size < 8 || headerSize < 8 || headerSize > size || off + size > total) {
                    throw IOException("Bad chunk 0x${type.toString(16)} at offset $off")
                }
                when (type) {
                    RES_STRING_POOL_TYPE -> if (pool == null) pool = StringPool(off, headerSize, size)
                    RES_XML_RESOURCE_MAP_TYPE -> readResourceMap(off, headerSize, size)
                    RES_XML_START_NAMESPACE_TYPE -> startNamespace(off + headerSize, off + size)
                    RES_XML_END_NAMESPACE_TYPE -> {} // prefix map kept for the whole document
                    RES_XML_START_ELEMENT_TYPE -> startElement(off, headerSize, size)
                    RES_XML_END_ELEMENT_TYPE -> endElement()
                    RES_XML_CDATA_TYPE -> cdata(off + headerSize, off + size)
                }
                off += size
            }
            return out.toString()
        }

        // ---- chunk handlers ----

        private fun readResourceMap(chunkStart: Int, headerSize: Int, size: Int) {
            val n = (size - headerSize) / 4
            resourceMap = IntArray(n) { i32(chunkStart + headerSize + it * 4) }
        }

        private fun startNamespace(body: Int, end: Int) {
            if (body + 8 > end) throw IOException("Truncated namespace chunk")
            val uri = str(i32(body + 4)) ?: return
            val prefix = str(i32(body)) ?: defaultPrefix(uri)
            nsPrefixByUri[uri] = prefix
            pendingNs.add(prefix to uri)
        }

        private fun startElement(chunkStart: Int, headerSize: Int, size: Int) {
            val s = chunkStart + headerSize // ResXMLTree_attrExt
            val end = chunkStart + size
            if (s + 20 > end) throw IOException("Truncated element chunk")
            val name = displayName(i32(s), str(i32(s + 4)) ?: "unknown")
            val attributeStart = u16(s + 8)
            val attributeSize = u16(s + 10)
            val attributeCount = u16(s + 12)
            if (attributeCount > 0 && attributeSize < 20) throw IOException("Bad attribute size $attributeSize")

            val attrs = ArrayList<Pair<String, String>>(attributeCount + pendingNs.size)
            for ((prefix, uri) in pendingNs) attrs.add("xmlns:$prefix" to uri)
            pendingNs.clear()
            for (i in 0 until attributeCount) {
                val a = s + attributeStart + i * attributeSize
                if (a < s || a + 20 > end) throw IOException("Attribute $i out of bounds")
                val rawRef = i32(a + 8)
                val value = if (rawRef != -1) str(rawRef) ?: "" else formatValue(u8(a + 15), i32(a + 16))
                attrs.add(displayName(i32(a), attrName(i32(a + 4))) to value)
            }

            closePendingTag(selfClose = false)
            val indent = "  ".repeat(openTags.size)
            out.append(indent).append('<').append(name)
            if (attrs.size == 1) {
                out.append(' ').append(attrs[0].first).append("=\"").append(escape(attrs[0].second)).append('"')
            } else {
                for ((k, v) in attrs) {
                    out.append('\n').append(indent).append("    ").append(k).append("=\"").append(escape(v)).append('"')
                }
            }
            openTags.add(name)
            tagOpen = true
        }

        private fun endElement() {
            if (openTags.isEmpty()) throw IOException("Unbalanced end element")
            val name = openTags.removeAt(openTags.size - 1)
            if (tagOpen) closePendingTag(selfClose = true)
            else out.append("  ".repeat(openTags.size)).append("</").append(name).append(">\n")
        }

        private fun cdata(body: Int, end: Int) {
            if (body + 4 > end) throw IOException("Truncated cdata chunk")
            closePendingTag(selfClose = false)
            out.append("  ".repeat(openTags.size)).append(escape(str(i32(body)) ?: "")).append('\n')
        }

        private fun closePendingTag(selfClose: Boolean) {
            if (!tagOpen) return
            out.append(if (selfClose) " />\n" else ">\n")
            tagOpen = false
        }

        // ---- names and values ----

        private fun str(ref: Int): String? = pool?.get(ref)

        /** Empty pool names fall back to the resource-map attribute id. */
        private fun attrName(nameRef: Int): String {
            val s = str(nameRef)
            if (!s.isNullOrEmpty()) return s
            if (nameRef in resourceMap.indices) {
                val id = resourceMap[nameRef]
                return ANDROID_ATTR_NAMES[id] ?: "0x%08x".format(id)
            }
            return "0x%08x".format(nameRef)
        }

        private fun displayName(nsRef: Int, name: String): String {
            if (nsRef == -1) return name
            val uri = str(nsRef) ?: return name
            val prefix = nsPrefixByUri[uri] ?: if (uri == ANDROID_URI) "android" else return name
            return "$prefix:$name"
        }

        private fun defaultPrefix(uri: String): String = when (uri) {
            ANDROID_URI -> "android"
            "http://schemas.android.com/apk/res-auto" -> "app"
            "http://schemas.android.com/tools" -> "tools"
            else -> "ns${nsPrefixByUri.size}"
        }

        private fun formatValue(type: Int, data: Int): String {
            val u = data.toLong() and 0xFFFFFFFFL
            return when (type) {
                0x00 -> if (data == 1) "@null" else "" // TYPE_NULL
                0x01 -> if (data == 0) "@null" else "@0x%08x".format(u) // TYPE_REFERENCE
                0x02 -> "?0x%08x".format(u) // TYPE_ATTRIBUTE
                0x03 -> str(data) ?: "" // TYPE_STRING
                0x04 -> fmtFloat(Float.fromBits(data)) // TYPE_FLOAT
                0x05 -> complexToString(data, fraction = false) // TYPE_DIMENSION
                0x06 -> complexToString(data, fraction = true) // TYPE_FRACTION
                0x10 -> data.toString() // TYPE_INT_DEC
                0x11 -> "0x%x".format(u) // TYPE_INT_HEX
                0x12 -> (data != 0).toString() // TYPE_INT_BOOLEAN
                in 0x1c..0x1f -> "#%08x".format(u) // color types
                else -> "0x%08x".format(u)
            }
        }

        private fun complexToString(data: Int, fraction: Boolean): String {
            val value = (data and -0x100).toFloat() * RADIX_MULTS[(data shr 4) and 3]
            return if (fraction) {
                fmtFloat(value * 100) + (if ((data and 0xF) == 1) "%p" else "%")
            } else {
                fmtFloat(value) + DIMENSION_UNITS.getOrElse(data and 0xF) { "" }
            }
        }

        // ---- primitive reads (bounds-checked, little-endian) ----

        private fun u8(off: Int): Int {
            if (off < 0 || off >= b.size) throw IOException("Read past end at $off")
            return b[off].toInt() and 0xFF
        }

        private fun u16(off: Int): Int = u8(off) or (u8(off + 1) shl 8)

        private fun u32(off: Int): Long = u16(off).toLong() or (u16(off + 2).toLong() shl 16)

        private fun i32(off: Int): Int = u32(off).toInt()

        private inner class StringPool(private val chunkStart: Int, headerSize: Int, private val chunkSize: Int) {
            private val count = i32(chunkStart + 8)
            private val utf8 = (i32(chunkStart + 16) and UTF8_FLAG) != 0
            private val stringsStart = i32(chunkStart + 20)
            private val offsets: IntArray
            private val cache: Array<String?>

            init {
                if (count < 0 || headerSize + count.toLong() * 4 > chunkSize) throw IOException("Bad string pool")
                offsets = IntArray(count) { i32(chunkStart + headerSize + it * 4) }
                cache = arrayOfNulls(count)
            }

            fun get(ref: Int): String? {
                if (ref < 0 || ref >= count) return null // -1 = no string
                cache[ref]?.let { return it }
                val base = chunkStart + stringsStart + offsets[ref]
                val end = chunkStart + chunkSize
                if (base < chunkStart || base >= end) throw IOException("String $ref out of pool")
                val s = if (utf8) readUtf8(base, end) else readUtf16(base, end)
                cache[ref] = s
                return s
            }

            // Varlen: high bit of the first byte extends the length to two bytes.
            private fun readUtf8(base: Int, end: Int): String {
                var p = base
                var v = u8(p)
                p += if (v and 0x80 != 0) 2 else 1 // UTF-16 char count, unused
                v = u8(p)
                val len: Int
                if (v and 0x80 != 0) {
                    len = ((v and 0x7f) shl 8) or u8(p + 1); p += 2
                } else {
                    len = v; p += 1
                }
                if (p + len > end) throw IOException("UTF-8 string overruns pool")
                return String(b, p, len, Charsets.UTF_8)
            }

            // Varlen: high bit of the first u16 extends the length to two u16s.
            private fun readUtf16(base: Int, end: Int): String {
                var p = base
                val v = u16(p)
                val len: Int
                if (v and 0x8000 != 0) {
                    len = ((v and 0x7fff) shl 16) or u16(p + 2); p += 4
                } else {
                    len = v; p += 2
                }
                if (p + len.toLong() * 2 > end) throw IOException("UTF-16 string overruns pool")
                return String(b, p, len * 2, Charsets.UTF_16LE)
            }
        }
    }

    private fun fmtFloat(f: Float): String {
        val l = f.toLong()
        return if (f == l.toFloat()) l.toString() else f.toString()
    }

    private fun escape(s: String): String {
        if (s.none { it == '&' || it == '<' || it == '>' || it == '"' }) return s
        val sb = StringBuilder(s.length + 8)
        for (c in s) when (c) {
            '&' -> sb.append("&amp;")
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            '"' -> sb.append("&quot;")
            else -> sb.append(c)
        }
        return sb.toString()
    }
}
