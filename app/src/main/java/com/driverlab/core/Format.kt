package com.driverlab.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Fmt {
    fun duration(ms: Long): String {
        if (ms < 1000) return "$ms ms"
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${sec}s"
            else -> String.format(Locale.US, "%.1f s", ms / 1000.0)
        }
    }

    fun size(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024L * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
        else -> String.format(Locale.US, "%.2f GB", bytes / 1073741824.0)
    }

    fun count(n: Long): String = String.format(Locale.US, "%,d", n)

    /** "1 round", "3 rounds". */
    fun plural(n: Int, one: String, many: String = one + "s"): String = "$n " + if (n == 1) one else many

    fun dateTime(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ms))
    fun time(ms: Long): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ms))
    fun stamp(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(ms))
    fun day(ms: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ms))
    fun fileTime(ms: Long): String = SimpleDateFormat("HH-mm-ss", Locale.US).format(Date(ms))
    fun sessionStamp(ms: Long): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(ms))

    /** Vulkan packed version (variant/major/minor/patch). */
    fun vkVersion(v: Long): String {
        val major = (v shr 22) and 0x7f
        val minor = (v shr 12) and 0x3ff
        val patch = v and 0xfff
        return "$major.$minor.$patch"
    }

    /**
     * Driver version as drivers pack it: VK_MAKE_VERSION style, 10-bit major
     * (Qualcomm 512.800.73, Mesa 26.x). Unlike apiVersion there is no variant field.
     */
    fun driverVersion(v: Long): String {
        val major = (v shr 22) and 0x3ff
        val minor = (v shr 12) and 0x3ff
        val patch = v and 0xfff
        return "$major.$minor.$patch"
    }

    fun slug(s: String): String =
        s.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-').take(48).ifEmpty { "driver" }

    fun hex(v: Long, digits: Int = 8): String = "0x" + v.toString(16).padStart(digits, '0')
}
