package app.local1st.files.core.util

import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale

object Format {

    fun bytes(bytes: Long): String {
        if (bytes < 0) return ""
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = -1
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        val number = NumberFormat.getNumberInstance(Locale.getDefault()).apply {
            maximumFractionDigits = if (value >= 100) 0 else 1
            minimumFractionDigits = 0
        }.format(value)
        return "$number ${units[unit]}"
    }

    fun dateTime(epochMillis: Long): String =
        if (epochMillis <= 0) "" else DateFormat.getDateTimeInstance(
            DateFormat.SHORT,
            DateFormat.SHORT,
            Locale.getDefault(),
        ).format(Date(epochMillis))

    /** A count with the locale's digit grouping, e.g. 182,340. */
    fun count(n: Int): String = NumberFormat.getIntegerInstance(Locale.getDefault()).format(n)

    /** Browser row subtitle: size or item count plus date. */
    fun details(vararg parts: String): String =
        parts.filter { it.isNotEmpty() }.joinToString(" · ")
}
