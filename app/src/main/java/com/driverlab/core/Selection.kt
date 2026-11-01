package com.driverlab.core

import androidx.compose.ui.state.ToggleableState
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

    fun selectAll() = replace(mapOf(ROOT to Entry(true, catalog.total.toInt())), "All tests")

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

    fun count(map: Map<String, Entry> = _state.value): Long {
        if (map.isEmpty()) return 0
        val keys = map.keys.sortedBy { it.length }
        fun nearestChildren(parent: String): List<String> {
            val prefix = if (parent == ROOT) "$ROOT." else "$parent."
            val under = keys.filter { it != parent && (parent == ROOT || it.startsWith(prefix)) && it.startsWith("$ROOT.") }
            return under.filter { k -> under.none { o -> o != k && k.startsWith("$o.") } }
        }
        fun countUnder(key: String, eff: Boolean, tests: Long): Long {
            var c = if (eff) tests else 0L
            for (ch in nearestChildren(key)) {
                val e = map.getValue(ch)
                c += countUnder(ch, e.include, e.tests.toLong()) - (if (eff) e.tests.toLong() else 0L)
            }
            return c
        }
        val root = map[ROOT]
        return countUnder(ROOT, root?.include ?: false, catalog.total)
    }

    /** Streams every selected test name in catalog order. */
    suspend fun expand(map: Map<String, Entry>, sink: suspend (String) -> Unit) {
        if (map.isEmpty()) return
        for (top in catalog.tops) {
            coroutineContext.ensureActive()
            val topPath = "$ROOT.${top.name}"
            val topEff = effectiveIn(map, topPath, self = true)
            val anyUnder = map.keys.any { it.startsWith("$topPath.") }
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
                    val hasKeys = map.keys.any { it.startsWith("$p.") }
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
