package com.driverlab.core

import android.content.Context

/**
 * Results Mesa's own Turnip testing already expects (assets/mesa/expect.tsv, made by
 * tools/make_mesa_expect.py from Mesa's src/freedreno/ci lists): tests that fail there too,
 * tests Mesa calls unreliable (flakes), and tests its testing skips. Mesa has lists for a few
 * Adreno GPUs only; for others the closest list of the same generation is used and the note says so.
 * Only used for Turnip sessions.
 */
class MesaExpect private constructor(
    private val gpuLabel: String,
    private val fails: Map<String, String>,
    private val flakes: List<Regex>,
    private val skips: List<Regex>,
) {
    /** A note for a failed, crashed or warning result, or "" when Mesa's lists don't mention the test. */
    fun note(name: String, status: TestStatus): String {
        if (status.bucket == Bucket.PASSED || status.bucket == Bucket.SKIPPED) return ""
        fails[name]?.let { expected ->
            return if (expected.equals(status.key, ignoreCase = true)) "Mesa's testing on the $gpuLabel gets the same: $expected"
            else "Mesa's testing on the $gpuLabel gets $expected for this test"
        }
        if (flakes.any { it.containsMatchIn(name) }) return "Mesa calls this test unreliable on the $gpuLabel"
        if (skips.any { it.containsMatchIn(name) }) return "Mesa's testing skips this test on the $gpuLabel"
        return ""
    }

    private data class Row(val list: String, val mode: String, val kind: String, val pattern: String, val status: String)

    companion object {
        private var rows: List<Row>? = null
        private val cache = HashMap<String, MesaExpect?>()
        private val GPU = Regex("(\\d)(\\d\\d)")

        private fun load(context: Context): List<Row> = rows ?: runCatching {
            context.assets.open("mesa/expect.tsv").bufferedReader().useLines { seq ->
                seq.filter { it.isNotBlank() && !it.startsWith("#") }.mapNotNull { l ->
                    val f = l.split('\t')
                    if (f.size < 5) null else Row(f[0], f[1], f[2], f[3], f[4])
                }.toList()
            }
        }.getOrDefault(emptyList()).also { rows = it }

        /** The lists for a session's GPU, or null when it isn't a Turnip session or Mesa has nothing close. */
        @Synchronized
        fun forSession(context: Context, m: SessionMeta): MesaExpect? {
            if (!m.deviceName.contains("Turnip", ignoreCase = true)) return null
            val match = GPU.find(m.deviceName.substringAfter("Adreno", m.deviceName)) ?: return null
            val gen = match.groupValues[1]
            val number = match.value
            val gmem = Regex("(?i)TU_DEBUG=.*\\bgmem\\b").containsMatchIn(m.env)
            val key = "$number|$gmem"
            if (cache.containsKey(key)) return cache[key]
            val all = load(context)
            val lists = all.map { it.list }.toSet()
            val own = "a$number"
            val chosen = when {
                own in lists -> own
                gen == "6" && "a660" in lists -> "a660"
                (gen == "7" || gen == "8") && "a750" in lists -> "a750"
                else -> null
            }
            val result = chosen?.let { list ->
                // Normal jobs and Mesa's Android job; the forced-GMEM job only when this run forced GMEM too.
                val modes = if (gmem) setOf("", "android", "gmem") else setOf("", "android")
                val use = all.filter { (it.list == list || (gen == "6" && it.list == "a6xx")) && it.mode in modes }
                val label = if (list == own) "Adreno $number" else "Adreno ${list.removePrefix("a")} (Mesa has no list for the $number)"
                MesaExpect(
                    label,
                    use.filter { it.kind == "fail" }.associate { it.pattern to it.status },
                    use.filter { it.kind == "flake" }.mapNotNull { runCatching { Regex(it.pattern) }.getOrNull() },
                    use.filter { it.kind == "skip" }.mapNotNull { runCatching { Regex(it.pattern) }.getOrNull() },
                )
            }
            cache[key] = result
            return result
        }
    }
}
