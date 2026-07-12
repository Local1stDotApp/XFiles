package com.xfiles.core.util

import java.text.SimpleDateFormat
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
        return if (value >= 100) "%.0f %s".format(Locale.US, value, units[unit])
        else "%.1f %s".format(Locale.US, value, units[unit])
    }

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    fun dateTime(epochMillis: Long): String =
        if (epochMillis <= 0) "" else dateFmt.format(Date(epochMillis))
}
