package com.driverlab.ui

import com.driverlab.core.Bucket
import com.driverlab.core.Counts
import com.driverlab.core.ROOT
import com.driverlab.core.SessionMeta
import com.driverlab.core.TestResult
import com.driverlab.core.isGles
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** The list a session screen shows, so a test screen can step to the previous or next test. */
object ResultsNav {
    @Volatile var sessionId: String = ""
    @Volatile var names: List<String> = emptyList()

    fun neighbours(id: String, name: String): Pair<String?, String?> {
        if (id != sessionId) return null to null
        val list = names
        val i = list.indexOf(name)
        if (i < 0) return null to null
        return list.getOrNull(i - 1) to list.getOrNull(i + 1)
    }
}

enum class ResultSort(val label: String) {
    PROBLEMS("Problems first"),
    NAME("Name"),
    SLOWEST("Slowest first"),
    RUN("Run order"),
}

/** Top-level group of a test, e.g. "api" or "pipeline"; the GLES programs form one group. */
fun groupOf(name: String): String = if (isGles(name)) "OpenGL ES programs" else name.removePrefix("$ROOT.").substringBefore('.')

private fun rank(b: Bucket) = when (b) {
    Bucket.FAILED -> 0
    Bucket.CRASHED -> 1
    Bucket.WARNING -> 2
    Bucket.SKIPPED -> 3
    Bucket.PASSED -> 4
}

fun sortResults(list: List<TestResult>, sort: ResultSort): List<TestResult> = when (sort) {
    ResultSort.PROBLEMS -> list.sortedWith(compareBy<TestResult>({ rank(it.status.bucket) }, { it.name }))
    ResultSort.NAME -> list.sortedBy { it.name }
    ResultSort.SLOWEST -> list.sortedByDescending { it.durationMs }
    ResultSort.RUN -> list.sortedBy { it.finishedAt }
}

data class ResultGroup(val key: String, val items: List<TestResult>, val counts: Counts)

data class Organized(val flat: List<TestResult>, val groups: List<ResultGroup>?)

/** Filters, sorts and (optionally) groups a session's results. Groups with problems come first. */
fun organize(all: List<TestResult>, bucket: String?, query: String, sort: ResultSort, grouped: Boolean): Organized {
    val q = query.trim()
    val words = q.split(' ').filter { it.isNotEmpty() }
    val filtered = all.filter { r ->
        (bucket == null || r.status.bucket.name == bucket) &&
            (words.isEmpty() || words.all { w -> r.name.contains(w, ignoreCase = true) || r.message.contains(w, ignoreCase = true) })
    }
    val sorted = sortResults(filtered, sort)
    if (!grouped) return Organized(sorted, null)
    val groups = sorted.groupBy { groupOf(it.name) }.map { (k, v) ->
        ResultGroup(k, v, v.fold(Counts()) { c, r -> c.plus(r.status.bucket) })
    }.sortedWith(compareBy<ResultGroup>({ -(it.counts.failed + it.counts.crashed) }, { it.key }))
    return Organized(groups.flatMap { it.items }, groups)
}

/** "Today", "Yesterday" or the date, for day headers. */
fun dayLabel(ms: Long, now: Long = System.currentTimeMillis()): String {
    val c = Calendar.getInstance().apply { timeInMillis = now }
    val d = Calendar.getInstance().apply { timeInMillis = ms }
    fun sameDay(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    if (sameDay(c, d)) return "Today"
    c.add(Calendar.DAY_OF_YEAR, -1)
    if (sameDay(c, d)) return "Yesterday"
    return SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(Date(ms))
}

fun matchesSession(s: SessionMeta, query: String): Boolean {
    val words = query.trim().split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return true
    val hay = listOf(s.driverName, s.label, s.deviceName, s.driverVersion, s.id, dayLabel(s.startedAt)).joinToString(" ")
    return words.all { hay.contains(it, ignoreCase = true) }
}
