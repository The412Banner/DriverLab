package com.driverlab.core

import java.io.File

/** Helpers for deqp .qpa logs. */
object Qpa {
    /** The XML block of one test case, or null. Reads the file line by line. */
    fun section(qpa: File, test: String, maxChars: Int = 400_000): String? {
        if (!qpa.exists()) return null
        val begin = "#beginTestCaseResult $test"
        val sb = StringBuilder()
        var inside = false
        qpa.bufferedReader().useLines { lines ->
            for (l in lines) {
                if (!inside) {
                    if (l == begin) inside = true
                    continue
                }
                if (l.startsWith("#endTestCaseResult") || l.startsWith("#terminateTestCaseResult")) {
                    sb.append(l).append('\n')
                    break
                }
                if (sb.length < maxChars) sb.append(l).append('\n')
            }
        }
        return if (inside) sb.toString() else null
    }

    /** Turns the XML of a test case into readable text lines. */
    fun readable(xml: String): String {
        val out = StringBuilder()
        val text = Regex("<Text>(.*?)</Text>", RegexOption.DOT_MATCHES_ALL)
        val number = Regex("<Number Name=\"([^\"]*)\"[^>]*>([^<]*)</Number>")
        val result = Regex("<Result StatusCode=\"([^\"]*)\">([^<]*)</Result>")
        val section = Regex("<Section Name=\"([^\"]*)\" Description=\"([^\"]*)\">")
        for (line in xml.lines()) {
            section.find(line)?.let { out.append("\n## ").append(unescape(it.groupValues[2].ifEmpty { it.groupValues[1] })).append('\n') }
            text.findAll(line).forEach { out.append(unescape(it.groupValues[1])).append('\n') }
            number.find(line)?.let { out.append(it.groupValues[1]).append(": ").append(it.groupValues[2]).append('\n') }
            result.find(line)?.let { out.append("\nResult: ").append(it.groupValues[1]).append(" — ").append(unescape(it.groupValues[2])).append('\n') }
        }
        return out.toString().trim()
    }

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    /** Device facts from a dEQP-VK.info.device_properties log. */
    fun probe(qpa: File, ok: Boolean, message: String): ProbeInfo {
        var deviceName = ""
        var api = ""
        var drv = ""
        var vendor = ""
        var device = ""
        if (qpa.exists()) {
            qpa.bufferedReader().useLines { lines ->
                for (raw in lines) {
                    val l = unescape(raw.substringAfter("<Text>", raw).substringBefore("</Text>")).trim()
                    fun value() = l.substringAfter('=').trim()
                    when {
                        l.startsWith("deviceName =") && deviceName.isEmpty() -> deviceName = value()
                        l.startsWith("apiVersion =") && api.isEmpty() -> api = value().toLongOrNull()?.let { Fmt.vkVersion(it) } ?: value()
                        l.startsWith("driverVersion =") && drv.isEmpty() -> drv = value()
                        l.startsWith("vendorID =") && vendor.isEmpty() -> vendor = value().toLongOrNull()?.let { Fmt.hex(it, 4) } ?: value()
                        l.startsWith("deviceID =") && device.isEmpty() -> device = value().toLongOrNull()?.let { Fmt.hex(it, 8) } ?: value()
                    }
                }
            }
        }
        if (drv.isNotEmpty()) {
            val v = drv.toLongOrNull()
            if (v != null) drv = Fmt.driverVersion(v)
        }
        return ProbeInfo(
            ok = ok && deviceName.isNotEmpty(),
            deviceName = deviceName,
            apiVersion = api,
            driverVersion = drv,
            vendorId = vendor,
            deviceId = device,
            message = message,
            checkedAt = System.currentTimeMillis(),
        )
    }
}
