package com.driverlab.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

const val ROOT = "dEQP-VK"
/** Root of the freedreno OpenGL ES test programs (system driver only). */
const val GLES_ROOT = "fd-gles"

fun isGles(path: String): Boolean = path == GLES_ROOT || path.startsWith("$GLES_ROOT.")

/** One top-level group: a CTS group from assets/cts/catalog/<top>.bin, or the GLES list. */
class GroupTree(
    val top: String,
    /** Full path of node 0, e.g. "dEQP-VK.api" or "fd-gles". */
    val rootPath: String,
    private val strings: Array<String>,
    private val nameIdx: IntArray,
    private val parent: IntArray,
    private val first: IntArray,
    private val count: IntArray,
    val tests: IntArray,
) {
    val size: Int get() = nameIdx.size
    fun name(n: Int): String = strings[nameIdx[n]]
    fun isLeaf(n: Int): Boolean = count[n] == 0
    fun children(n: Int): IntRange = first[n] until first[n] + count[n]
    fun childCount(n: Int): Int = count[n]

    fun path(n: Int): String {
        val parts = ArrayList<String>(8)
        var x = n
        while (x > 0) {
            parts.add(name(x))
            x = parent[x]
        }
        val sb = StringBuilder(rootPath)
        for (i in parts.indices.reversed()) sb.append('.').append(parts[i])
        return sb.toString()
    }

    /** Node for a full path like "dEQP-VK.api.smoke.triangle", or -1. */
    fun find(path: String): Int {
        if (path == rootPath) return 0
        if (!path.startsWith("$rootPath.")) return -1
        val segs = path.substring(rootPath.length + 1).split('.')
        var node = 0
        for (i in segs.indices) {
            var next = -1
            for (c in children(node)) if (name(c) == segs[i]) { next = c; break }
            if (next < 0) return -1
            node = next
        }
        return node
    }

    /**
     * Finds many paths in a row. Remembers the last matched child of every node, so names that
     * come in catalog order (as test lists usually do) are found without scanning siblings.
     */
    inner class Lookup {
        private val last = HashMap<Int, Int>()

        fun find(path: String): Int {
            if (path == rootPath) return 0
            if (path.length <= rootPath.length + 1 || !path.startsWith(rootPath) || path[rootPath.length] != '.') return -1
            var node = 0
            var start = rootPath.length + 1
            while (true) {
                val dot = path.indexOf('.', start)
                val seg = if (dot < 0) path.substring(start) else path.substring(start, dot)
                val f = first[node]
                val c = count[node]
                if (c == 0) return -1
                var k = last[node] ?: f
                var found = -1
                for (step in 0 until c) {
                    if (strings[nameIdx[k]] == seg) { found = k; break }
                    if (++k == f + c) k = f
                }
                if (found < 0) return -1
                last[node] = found
                node = found
                if (dot < 0) return node
                start = dot + 1
            }
        }
    }

    internal fun stringIndicesMatching(q: String): BooleanArray {
        val hit = BooleanArray(strings.size)
        for (i in strings.indices) hit[i] = strings[i].contains(q, ignoreCase = true)
        return hit
    }

    internal fun nameIndex(n: Int): Int = nameIdx[n]

    companion object {
        /** A flat group (root + leaves), used for the GLES test list. */
        fun flat(root: String, names: List<String>): GroupTree {
            val n = names.size
            val strings = arrayOf(root) + names.toTypedArray()
            val name = IntArray(n + 1) { it }
            val parent = IntArray(n + 1) { if (it == 0) -1 else 0 }
            val first = IntArray(n + 1) { if (it == 0) 1 else 0 }
            val count = IntArray(n + 1) { if (it == 0) n else 0 }
            val tests = IntArray(n + 1) { if (it == 0) n else 1 }
            return GroupTree(root, root, strings, name, parent, first, count, tests)
        }

        fun parse(top: String, bytes: ByteArray): GroupTree {
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4).also { b.get(it) }
            require(String(magic) == "DLC1") { "bad catalog file for $top" }
            val ns = b.int
            val strings = Array(ns) {
                val len = b.short.toInt() and 0xffff
                val arr = ByteArray(len)
                b.get(arr)
                String(arr, Charsets.UTF_8)
            }
            val nn = b.int
            fun col(): IntArray {
                val a = IntArray(nn)
                b.asIntBuffer().get(a)
                b.position(b.position() + nn * 4)
                return a
            }
            val name = col()
            val parent = col()
            val first = col()
            val count = col()
            val tests = col()
            return GroupTree(top, "$ROOT.$top", strings, name, parent, first, count, tests)
        }
    }
}

class Catalog(private val context: Context) {
    data class Top(val name: String, val tests: Int, val nodes: Int, val file: String) {
        val path: String get() = if (name == GLES_ROOT) GLES_ROOT else "$ROOT.$name"
        val isGles: Boolean get() = name == GLES_ROOT
    }
    data class Preset(val id: String, val title: String, val description: String, val patterns: List<String>)
    data class Hit(val path: String, val isLeaf: Boolean, val tests: Int)

    var ctsTag: String = ""
        private set
    /** Number of Vulkan CTS tests (everything under dEQP-VK). */
    var total: Long = 0
        private set
    var glesTests: List<String> = emptyList()
        private set
    val glesTotal: Long get() = glesTests.size.toLong()
    private var descriptions: Map<String, String> = emptyMap()
    var tops: List<Top> = emptyList()
        private set
    var presets: List<Preset> = emptyList()
        private set
    val available: Boolean get() = tops.isNotEmpty()

    private val lock = Mutex()
    private val cache = object : LinkedHashMap<String, GroupTree>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, GroupTree>?) = size > 3
    }

    fun loadIndex() {
        try {
            val lines = context.assets.open("cts/catalog/index.tsv").bufferedReader().readLines()
            val list = ArrayList<Top>()
            for (l in lines) {
                if (l.isBlank()) continue
                val f = l.split('\t')
                if (f[0] == "#cts") {
                    ctsTag = f.getOrElse(1) { "" }
                    total = f.getOrElse(2) { "0" }.toLongOrNull() ?: 0
                } else if (f.size >= 4) {
                    list.add(Top(f[0], f[1].toInt(), f[2].toInt(), f[3]))
                }
            }
            glesTests = runCatching {
                context.assets.open("gles/tests.txt").bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() }
            }.getOrDefault(emptyList())
            if (glesTests.isNotEmpty()) list.add(0, Top(GLES_ROOT, glesTests.size, glesTests.size + 1, ""))
            tops = list
        } catch (e: Exception) {
            tops = emptyList()
        }
        descriptions = runCatching {
            context.assets.open("descriptions.tsv").bufferedReader().readLines()
                .filter { it.isNotBlank() && !it.startsWith("#") && it.contains('\t') }
                .associate { it.substringBefore('\t') to it.substringAfter('\t') }
        }.getOrDefault(emptyMap())
        presets = try {
            context.assets.open("cts/catalog/presets.tsv").bufferedReader().readLines()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .map { l ->
                    val f = l.split('\t')
                    Preset(f[0], f[1], f[2], f[3].split(',').map { it.trim() }.filter { it.isNotEmpty() })
                }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun topOf(path: String): String? {
        if (isGles(path)) return GLES_ROOT
        val segs = path.split('.')
        return if (segs.size >= 2 && segs[0] == ROOT) segs[1] else null
    }

    /**
     * Explanation for a path: its own text, or the nearest described parent's
     * (returned with that parent's path so the UI can say which group it is).
     */
    fun describe(path: String): Pair<String, String>? {
        var p: String? = path
        while (p != null) {
            descriptions[p]?.let { return p!! to it }
            p = SelectionStore.parentOf(p)
        }
        return null
    }

    suspend fun tree(top: String): GroupTree = withContext(Dispatchers.IO) {
        lock.withLock {
            cache[top] ?: run {
                if (top == GLES_ROOT) return@run GroupTree.flat(GLES_ROOT, glesTests).also { cache[top] = it }
                val entry = tops.first { it.name == top }
                val bytes = context.assets.open("cts/catalog/${entry.file}").use { it.readBytes() }
                GroupTree.parse(top, bytes).also { cache[top] = it }
            }
        }
    }

    /** Number of tests at or under a path ("dEQP-VK" = everything). */
    suspend fun testsUnder(path: String): Int {
        if (path == ROOT) return total.toInt()
        if (path == GLES_ROOT) return glesTests.size
        val top = topOf(path) ?: return 0
        if (tops.none { it.name == top }) return 0
        val t = tree(top)
        val n = t.find(path)
        return if (n >= 0) t.tests[n] else 0
    }

    suspend fun exists(path: String): Boolean = path == ROOT || testsUnder(path) > 0

    /**
     * Search group and test names. A plain word matches any name segment; text
     * with dots matches inside full test names. Calls [onHit] until [limit].
     */
    suspend fun search(query: String, limit: Int, onHit: (Hit) -> Unit) {
        val q = query.trim()
        if (q.isEmpty()) return
        var found = 0
        val dotted = q.contains('.')
        val last = q.substringAfterLast('.')
        for (top in tops) {
            coroutineContext.ensureActive()
            val t = tree(top.name)
            val hit = t.stringIndicesMatching(if (dotted) last else q)
            for (n in 0 until t.size) {
                if (!hit[t.nameIndex(n)]) continue
                if (dotted) {
                    val p = t.path(n)
                    if (!p.contains(q, ignoreCase = true)) continue
                    onHit(Hit(p, t.isLeaf(n), t.tests[n]))
                } else {
                    onHit(Hit(t.path(n), t.isLeaf(n), t.tests[n]))
                }
                if (++found >= limit) return
                if (found % 64 == 0) coroutineContext.ensureActive()
            }
        }
    }
}
