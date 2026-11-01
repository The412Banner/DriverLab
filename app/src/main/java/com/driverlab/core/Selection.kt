package com.driverlab.core

import androidx.compose.ui.state.ToggleableState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Test selection as a small map of explicit decisions: path -> include/exclude.
 * A test is selected when its nearest explicit ancestor-or-self says include.
 * "dEQP-VK" (ROOT) stands for every test.
 */
class SelectionStore(private val catalog: Catalog) {
    data class Entry(val include: Boolean, val tests: Int)

    private val _state = MutableStateFlow<Map<String, Entry>>(emptyMap())
    val state: StateFlow<Map<String, Entry>> = _state.asStateFlow()
    private val _label = MutableStateFlow<String?>(null)
    /** Name of the preset the selection came from, until the user edits it. */
    val label: StateFlow<String?> = _label.asStateFlow()

    fun clear() {
        _state.value = emptyMap()
        _label.value = null
    }

    fun replace(map: Map<String, Entry>, label: String?) {
        _state.value = map
        _label.value = label
    }

    suspend fun applyPreset(p: Catalog.Preset) {
        val m = LinkedHashMap<String, Entry>()
        for (pat in p.patterns) {
            val path = if (pat.endsWith(".*")) pat.dropLast(2) else pat
            val n = catalog.testsUnder(path)
            if (n > 0) m[path] = Entry(true, n)
        }
        replace(normalize(m), p.title)
    }

    fun selectAll() {
        val m = LinkedHashMap<String, Entry>()
        if (catalog.glesTotal > 0) m[GLES_ROOT] = Entry(true, catalog.glesTotal.toInt())
        m[ROOT] = Entry(true, catalog.total.toInt())
        replace(m, "All tests")
    }

    fun set(path: String, include: Boolean, tests: Int) {
        val m = LinkedHashMap(_state.value)
        val prefix = "$path."
        m.keys.removeAll { it.startsWith(prefix) }
        m.remove(path)
        val parentEff = effectiveIn(m, path, self = false)
        if (include != parentEff) m[path] = Entry(include, tests)
        _state.value = m
        _label.value = null
    }

    fun effective(path: String): Boolean = effectiveIn(_state.value, path, self = true)

    fun triState(path: String): ToggleableState {
        val m = _state.value
        val eff = effectiveIn(m, path, self = true)
        val prefix = "$path."
        val mixed = m.any { (k, v) -> k.startsWith(prefix) && v.include != eff }
        return when {
            mixed -> ToggleableState.Indeterminate
            eff -> ToggleableState.On
            else -> ToggleableState.Off
        }
    }

    /**
     * Each decision that differs from its nearest decided ancestor adds (include) or removes
     * (exclude) its tests; decisions equal to their ancestor change nothing. Linear in the map
     * size, so lists with many single tests stay fast.
     */
    fun count(map: Map<String, Entry> = _state.value): Long {
        if (map.isEmpty()) return 0
        var c = 0L
        for ((k, e) in map) {
            val tests = when (k) {
                ROOT -> catalog.total
                GLES_ROOT -> catalog.glesTotal
                else -> e.tests.toLong()
            }
            val parent = effectiveIn(map, k, self = false)
            if (e.include != parent) c += if (e.include) tests else -tests
        }
        return c
    }

    data class ListResult(val tests: Long, val names: Long, val missing: List<String>, val missingCount: Long)

    /**
     * Makes the selection exactly the tests in a test list, fed line by line (see
     * [TestList.nameOf]): full test names, groups as "group.*", GLES programs with or without
     * "fd-gles.". No size limit: names are first sorted into one temporary file per top group
     * under [work], then each group's tree is loaded once, its tests are marked, and fully
     * selected groups are folded back into one entry. Names this CTS doesn't have are counted
     * (the first [MISSING_SAMPLE] are returned).
     */
    suspend fun loadList(label: String, work: File, feed: suspend (suspend (String) -> Unit) -> Unit): ListResult =
        withContext(Dispatchers.IO) {
            val dir = File(work, "listload").apply { deleteRecursively(); mkdirs() }
            try {
                val writers = HashMap<String, java.io.BufferedWriter>()
                val missing = ArrayList<String>()
                var missingCount = 0L
                var names = 0L
                var all = false
                var allGles = false
                val gles = catalog.glesTests.toHashSet()
                val topNames = catalog.tops.map { it.name }.toHashSet()
                fun miss(n: String) {
                    missingCount++
                    if (missing.size < MISSING_SAMPLE) missing += n
                }
                try {
                    feed { raw ->
                        val n = TestList.nameOf(raw) ?: return@feed
                        names++
                        when (n) {
                            "*", ROOT, "$ROOT.*" -> all = true
                            GLES_ROOT, "$GLES_ROOT.*" -> allGles = true
                            else -> {
                                val p = if (!n.contains('.') && n in gles) "$GLES_ROOT.$n" else n
                                val top = catalog.topOf(p)
                                val star = p.indexOf('*')
                                val badStar = star >= 0 && (star != p.length - 1 || !p.endsWith(".*"))
                                if (top == null || top !in topNames || badStar) miss(n)
                                else writers.getOrPut(top) { File(dir, "$top.txt").bufferedWriter() }.apply { write(p); write("\n") }
                            }
                        }
                        if (names % 65_536 == 0L) coroutineContext.ensureActive()
                    }
                } finally {
                    writers.values.forEach { runCatching { it.close() } }
                }
                val m = LinkedHashMap<String, Entry>()
                if (all) m[ROOT] = Entry(true, catalog.total.toInt())
                if (allGles && catalog.glesTotal > 0) m[GLES_ROOT] = Entry(true, catalog.glesTotal.toInt())
                for (top in catalog.tops) {
                    val f = File(dir, "${top.name}.txt")
                    if (!f.exists() || (top.isGles && allGles) || (!top.isGles && all)) continue
                    coroutineContext.ensureActive()
                    val t = catalog.tree(top.name)
                    val look = t.Lookup()
                    val full = BooleanArray(t.size)
                    f.bufferedReader().useLines { seq ->
                        for (p in seq) {
                            val node = look.find(if (p.endsWith(".*")) p.dropLast(2) else p)
                            if (node < 0) miss(p.removePrefix("$GLES_ROOT.")) else full[node] = true
                        }
                    }
                    foldMarked(t, full, m)
                }
                val map = normalize(m)
                replace(map, label)
                ListResult(count(map), names, missing, missingCount)
            } finally {
                dir.deleteRecursively()
            }
        }

    /** Adds one include entry per largest fully marked subtree of [t]. */
    private fun foldMarked(t: GroupTree, full: BooleanArray, out: MutableMap<String, Entry>) {
        val order = IntArray(t.size)
        var n = 0
        val stack = ArrayDeque<Int>()
        stack.addLast(0)
        while (stack.isNotEmpty()) {
            val x = stack.removeLast()
            order[n++] = x
            for (c in t.children(x)) stack.addLast(c)
        }
        val inc = IntArray(t.size)
        for (i in n - 1 downTo 0) {
            val x = order[i]
            inc[x] = if (full[x]) t.tests[x] else {
                var s = 0
                for (c in t.children(x)) s += inc[c]
                s
            }
        }
        stack.addLast(0)
        while (stack.isNotEmpty()) {
            val x = stack.removeLast()
            if (inc[x] == 0) continue
            if (inc[x] >= t.tests[x]) { out[t.path(x)] = Entry(true, t.tests[x]); continue }
            for (c in t.children(x)) stack.addLast(c)
        }
    }

    /** Streams every selected test name in catalog order. */
    suspend fun expand(map: Map<String, Entry>, sink: suspend (String) -> Unit) {
        if (map.isEmpty()) return
        // Every proper ancestor of a decided path: "has decisions below" in O(1).
        val above = HashSet<String>()
        for (k in map.keys) {
            var p = parentOf(k)
            while (p != null && above.add(p)) p = parentOf(p)
        }
        for (top in catalog.tops) {
            coroutineContext.ensureActive()
            val topPath = top.path
            val topEff = effectiveIn(map, topPath, self = true)
            val anyUnder = topPath in above
            if (!topEff && !anyUnder) continue
            val tree = catalog.tree(top.name)
            // Iterative DFS keeping the inherited include flag and the path.
            val nodes = ArrayDeque<Int>()
            val effs = ArrayDeque<Boolean>()
            val paths = ArrayDeque<String>()
            nodes.addLast(0); effs.addLast(effectiveIn(map, topPath, self = false)); paths.addLast(topPath)
            var steps = 0
            while (nodes.isNotEmpty()) {
                val n = nodes.removeLast()
                val inherited = effs.removeLast()
                val p = paths.removeLast()
                val eff = map[p]?.include ?: inherited
                if (tree.isLeaf(n)) {
                    if (eff) sink(p)
                } else {
                    val hasKeys = p in above
                    if (!eff && !hasKeys) continue
                    val kids = tree.children(n)
                    for (c in kids.reversed()) {
                        nodes.addLast(c); effs.addLast(eff); paths.addLast(p + "." + tree.name(c))
                    }
                }
                if (++steps % 4096 == 0) coroutineContext.ensureActive()
            }
        }
    }

    private fun normalize(m: Map<String, Entry>): Map<String, Entry> {
        val out = LinkedHashMap<String, Entry>()
        for ((k, v) in m.entries.sortedBy { it.key.length }) {
            if (effectiveIn(out, k, self = false) != v.include) out[k] = v
        }
        return out
    }

    companion object {
        const val MISSING_SAMPLE = 200

        fun effectiveIn(m: Map<String, Entry>, path: String, self: Boolean): Boolean {
            var p = if (self) path else parentOf(path)
            while (p != null) {
                m[p]?.let { return it.include }
                p = parentOf(p)
            }
            return false
        }

        fun parentOf(path: String): String? {
            if (path == ROOT) return null
            val i = path.lastIndexOf('.')
            return if (i <= 0) null else path.substring(0, i)
        }
    }
}
