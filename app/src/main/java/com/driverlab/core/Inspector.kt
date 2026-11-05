package com.driverlab.core

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.OutputStreamWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** One library file that can be inspected: [path] on disk, [label] as shown (path inside the driver). */
data class InspectFile(val driverId: String, val path: String, val label: String, val size: Long) {
    /** Folder-safe name for this file's inspector data. */
    val key: String get() = label.replace(Regex("[^A-Za-z0-9._-]"), "_")
}

data class Overview(
    val arch: String,
    val bits: Int,
    val lang: String,
    val stripped: Boolean,
    val size: Long,
    val version: String,
    val mesaCommit: String,
    val details: List<Pair<String, String>>,
    val gpus: List<String>,
    val needed: List<String>,
    val exports: Int,
    val names: Int,
    val scrambled: Int,
    val sourcePaths: List<String>,
    val strings: Int,
)

data class Fn(val addr: Long, val name: String, val size: Long)

/** State of a whole-file "make source code" job. */
data class SourceJob(
    val done: Int = 0,
    val total: Int = 0,
    val phase: String = "",
    val running: Boolean = false,
    val error: String? = null,
    val skipped: Int = 0,
)

data class CompareResult(
    val a: Overview,
    val b: Overview,
    val exportsAdded: List<String>,
    val exportsRemoved: List<String>,
    val neededAdded: List<String>,
    val neededRemoved: List<String>,
    val gpusAdded: List<String>,
    val gpusRemoved: List<String>,
    val stringsAdded: List<String>,
    val stringsRemoved: List<String>,
    /** Functions with a real name in both files: name to (size in A, size in B); null size = missing on that side. */
    val functions: List<Triple<String, Long?, Long?>>,
    val functionsA: Int,
    val functionsB: Int,
)

/**
 * A running rizin (librizin.so) with the Ghidra decompiler plugin, opened on one file. Commands go in on
 * stdin; with -q0 rizin ends every answer with a zero byte.
 *
 * Memory: started from the app it would inherit the app's foreground oom_score_adj (0), so when the
 * decompiler runs away Android closes other apps first (2026-11-05 it closed Termux). It is started
 * through sh, which sets oom_score_adj 1000 (closed first) and prints its pid; a watchdog stops it
 * when its resident memory passes [limitKb].
 */
class RzSession(context: Context, file: File) : Closeable {
    private val proc: Process
    private val input: BufferedInputStream
    private val writer: OutputStreamWriter
    private val pid: Int
    @Volatile private var overLimit = false
    @Volatile private var closed = false

    init {
        val nd = Paths.nativeDir(context)
        val tmp = File(context.cacheDir, "rz").apply { mkdirs() }
        val data = Inspector.dataDir(context)
        val home = File(data, "home").apply { mkdirs() }
        val args = listOf(
            File(nd, "librizin.so").absolutePath, "-q0", "-N",
            "-e", "scr.color=0", "-e", "scr.utf8=false", "-e", "scr.interactive=false", "-e", "bin.cache=true",
            "-l", File(nd, "libcore_ghidra.so").absolutePath,
            file.absolutePath,
        )
        val q = { a: String -> "'" + a.replace("'", "'\\''") + "'" }
        val pb = ProcessBuilder(
            "/system/bin/sh", "-c",
            "echo 1000 > /proc/self/oom_score_adj 2>/dev/null; echo \$\$; exec " + args.joinToString(" ") { q(it) },
        )
        pb.environment()["LD_LIBRARY_PATH"] = nd.absolutePath
        pb.environment()["TMPDIR"] = tmp.absolutePath
        pb.environment()["HOME"] = home.absolutePath
        pb.redirectError(File(tmp, "rizin-stderr.log"))
        proc = pb.start()
        input = BufferedInputStream(proc.inputStream, 1 shl 16)
        writer = OutputStreamWriter(proc.outputStream, Charsets.UTF_8)
        pid = readLine().trim().toIntOrNull() ?: -1
        startWatchdog()
        readAnswer()
        cmd("e ghidra.sleighhome=" + File(data, "sleigh").absolutePath)
    }

    @Synchronized
    fun cmd(c: String): String {
        writer.write(c.replace('\n', ' '))
        writer.write("\n")
        writer.flush()
        return readAnswer()
    }

    private fun readLine(): String {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0 || b == '\n'.code) break
            buf.write(b)
        }
        return buf.toString("UTF-8")
    }

    private fun startWatchdog() {
        if (pid <= 0) return
        val status = File("/proc/$pid/status")
        Thread({
            while (!closed) {
                val rss = runCatching {
                    status.readLines().firstOrNull { it.startsWith("VmRSS:") }?.filter { it.isDigit() }?.toLongOrNull()
                }.getOrNull() ?: break
                if (rss > limitKb) {
                    overLimit = true
                    proc.destroyForcibly()
                    break
                }
                Thread.sleep(300)
            }
        }, "rizin-watchdog").apply { isDaemon = true }.start()
    }

    private fun readAnswer(): String {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) throw IOException(if (overLimit) "The decompiler used more than ${limitKb / 1024} MB and was stopped." else "The inspector engine stopped.")
            if (b == 0) break
            buf.write(b)
        }
        return buf.toString("UTF-8").replace(ANSI, "")
    }

    private companion object {
        val ANSI = Regex("\u001b\\[[0-9;?]*[A-Za-z]")

        /** 35 % of the phone's RAM, between 1 and 3 GB (about 2.5 GB on an 8 GB phone). */
        val limitKb: Long by lazy {
            val total = runCatching {
                File("/proc/meminfo").readLines().first { it.startsWith("MemTotal:") }.filter { it.isDigit() }.toLong()
            }.getOrDefault(8L shl 20)
            (total * 35 / 100).coerceIn(1L shl 20, 3L shl 20)
        }
    }

    override fun close() {
        closed = true
        runCatching { writer.write("q!!\n"); writer.flush() }
        proc.destroy()
    }
}

/**
 * Driver inspector: overview, text, functions and decompiled C code of the libraries in a driver, a
 * whole-file "source code" export and a comparison of two files. Data per file lives in
 * files/inspect/<driver id>/<file key>/: overview.json, strings.txt, functions.tsv, names.tsv
 * (renames), notes.tsv, source/ (made source) and edited/ (edited functions).
 */
class Inspector(private val context: Context, private val drivers: DriverRepository, private val scope: CoroutineScope) {
    private val sessionLock = Mutex()
    private var session: RzSession? = null
    private var sessionFile: String? = null
    private var analysed = false

    private val _jobs = MutableStateFlow<Map<String, SourceJob>>(emptyMap())
    val jobs: StateFlow<Map<String, SourceJob>> = _jobs.asStateFlow()
    private val running = HashMap<String, Job>()
    private val jobSessions = java.util.concurrent.ConcurrentHashMap<String, RzSession>()

    companion object {
        fun dataDir(c: Context) = File(c.filesDir, "rz")

        private val MESA = Regex("""Mesa (\d+\.\d+\.\d+[\w.-]*)(?: \(git-([0-9a-f]{7,40})\))?""")
        private val GPU = Regex("""Adreno \(TM\) ([0-9A-Za-z][0-9A-Za-z+.-]*)""")
        private val SOURCE = Regex("""[\w.+-]+(?:/[\w.+-]+)+\.(?:c|cc|cpp|cxx|h|hpp|inc)""")
        private val QCOM_DETAIL = Regex("""^\s*(Build Date|Shader Compiler Version|Compiler Version|Driver Version|Driver Branch)\s*:\s*(.+)$""")
        private const val MAX_PATHS = 300
        /** Larger "functions" are nearly always wrong boundaries; the decompiler can run out of memory on them. */
        const val MAX_DECOMPILE = 256L * 1024
        /** Engine restarts allowed in one source job before it gives up. */
        private const val MAX_RESTARTS = 20
    }

    private fun stamp(): Long = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime }.getOrDefault(0L)

    /** Unpacks rizin's type data and the processor files once per app install or update. */
    @Synchronized
    private fun ensureData() {
        val dir = dataDir(context)
        val marker = File(dir, ".stamp")
        val want = stamp().toString()
        if (marker.exists() && marker.readText() == want) return
        File(dir, "share").deleteRecursively()
        File(dir, "sleigh").deleteRecursively()
        dir.mkdirs()
        val root = dir.canonicalPath + File.separator
        ZipInputStream(context.assets.open("inspect/rz-data.zip").buffered(1 shl 16)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val out = File(dir, e.name)
                if (!out.canonicalPath.startsWith(root)) continue
                if (e.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    out.outputStream().buffered(1 shl 16).use { zip.copyTo(it) }
                }
            }
        }
        marker.writeText(want)
    }

    /** Library files of a driver: every .so of an imported driver, or the system driver and its own libraries. */
    fun files(driverId: String): List<InspectFile> {
        val d = drivers.get(driverId) ?: return emptyList()
        if (d.isSystem) {
            val hal = File("/vendor/lib64/hw/vulkan.adreno.so")
            val libs = listOf("libgsl.so", "libllvm-glnext.so", "libllvm-qgl.so", "libllvm-qcom.so", "libadreno_utils.so", "libCB.so")
                .map { File("/vendor/lib64", it) }
            return (listOf(hal) + libs).filter { it.canRead() }.map { InspectFile(driverId, it.absolutePath, it.absolutePath, it.length()) }
        }
        val dir = drivers.libFile(d)?.parentFile ?: return emptyList()
        val main = d.libName
        return dir.walkTopDown().filter { it.isFile && it.name.endsWith(".so") }
            .map { InspectFile(driverId, it.absolutePath, it.relativeTo(dir).path, it.length()) }
            .sortedWith(compareBy({ it.label != main }, { it.label }))
            .toList()
    }

    fun file(driverId: String, label: String): InspectFile? = files(driverId).firstOrNull { it.label == label }

    fun dir(f: InspectFile): File = File(context.filesDir, "inspect/${f.driverId}/${f.key}").apply { mkdirs() }

    private suspend fun <T> withSession(f: InspectFile, block: (RzSession) -> T): T = withContext(Dispatchers.IO) {
        sessionLock.withLock {
            ensureData()
            if (sessionFile != f.path || session == null) {
                session?.close()
                session = null
                analysed = false
                val s = RzSession(context, File(f.path))
                session = s
                sessionFile = f.path
                applyNames(f, s)
            }
            try {
                block(session!!)
            } catch (e: IOException) {
                session?.close()
                session = null
                throw e
            }
        }
    }

    /** Closes the open engine (the screen is gone); the next request opens it again. */
    fun release() {
        scope.launch(Dispatchers.IO) {
            sessionLock.withLock {
                session?.close()
                session = null
                sessionFile = null
                analysed = false
            }
        }
    }

    // ---- Overview and text ----

    suspend fun overview(f: InspectFile, refresh: Boolean = false): Overview {
        val cache = File(dir(f), "overview.json")
        if (!refresh && cache.exists()) runCatching { return fromJson(JSONObject(cache.readText())) }
        val scan = withContext(Dispatchers.IO) { scanStrings(f) }
        val o = withSession(f) { s ->
            val info = runCatching { JSONObject(s.cmd("iIj")) }.getOrDefault(JSONObject())
            val needed = runCatching { JSONArray(s.cmd("ilj")).let { a -> List(a.length()) { a.getString(it) } } }.getOrDefault(emptyList())
            val exports = s.cmd("iE~?").trim().toIntOrNull() ?: 0
            val names = s.cmd("is~?").trim().toIntOrNull() ?: 0
            val scrambled = s.cmd("is~0000!~?").trim().toIntOrNull() ?: 0
            Overview(
                arch = info.optString("arch"), bits = info.optInt("bits"), lang = info.optString("lang"),
                stripped = info.optBoolean("stripped"), size = f.size,
                version = scan.version, mesaCommit = scan.mesaCommit, details = scan.details,
                gpus = scan.gpus, needed = needed, exports = exports, names = names, scrambled = scrambled,
                sourcePaths = scan.paths, strings = scan.count,
            )
        }
        cache.writeText(toJson(o).toString(1))
        return o
    }

    private class Scan(
        val version: String, val mesaCommit: String, val details: List<Pair<String, String>>,
        val gpus: List<String>, val paths: List<String>, val count: Int,
    )

    /** One pass over the file: printable runs of 4+ characters go to strings.txt; version, GPU names and paths are picked out. */
    private fun scanStrings(f: InspectFile): Scan {
        var version = ""
        var commit = ""
        val details = LinkedHashMap<String, String>()
        val gpus = LinkedHashSet<String>()
        val paths = LinkedHashSet<String>()
        var count = 0
        val out = File(dir(f), "strings.txt").bufferedWriter()
        val sb = StringBuilder()
        fun flush() {
            if (sb.length >= 4) {
                val s = sb.toString()
                out.write(s); out.write("\n"); count++
                if (version.isEmpty()) MESA.find(s)?.let { m -> version = "Mesa " + m.groupValues[1]; commit = m.groupValues[2] }
                QCOM_DETAIL.find(s)?.let { m ->
                    val v = m.groupValues[2].trim()
                    if (v.isNotEmpty() && '%' !in v) details.putIfAbsent(m.groupValues[1], v)
                }
                GPU.findAll(s).forEach { gpus += it.groupValues[1] }
                if (paths.size < MAX_PATHS && '/' in s) SOURCE.findAll(s).forEach { if (paths.size < MAX_PATHS) paths += it.value }
            }
            sb.setLength(0)
        }
        out.use {
            File(f.path).inputStream().buffered(1 shl 16).use { inp ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    for (i in 0 until n) {
                        val c = buf[i].toInt() and 0xff
                        if (c in 0x20..0x7e || c == 9) {
                            if (sb.length < 4096) sb.append(c.toChar())
                        } else flush()
                    }
                }
                flush()
            }
        }
        if (version.isEmpty()) {
            version = details["Driver Version"] ?: details["Build Date"]?.let { "Qualcomm, built $it" } ?: ""
        }
        return Scan(version, commit, details.toList(), gpus.toList(), paths.toList().sorted(), count)
    }

    /** Lines of strings.txt containing [query] (all when blank), at most [limit]. */
    suspend fun strings(f: InspectFile, query: String, limit: Int = 3000): Pair<List<String>, Int> = withContext(Dispatchers.IO) {
        val file = File(dir(f), "strings.txt")
        if (!file.exists()) overview(f, refresh = true)
        val hits = ArrayList<String>()
        var total = 0
        file.bufferedReader().useLines { lines ->
            for (l in lines) {
                if (query.isBlank() || l.contains(query, ignoreCase = true)) {
                    total++
                    if (hits.size < limit) hits += l
                }
            }
        }
        hits to total
    }

    // ---- Functions ----

    fun cachedFunctions(f: InspectFile): List<Fn>? {
        val file = File(dir(f), "functions.tsv")
        if (!file.exists()) return null
        return file.readLines().mapNotNull { l ->
            val p = l.split('\t')
            if (p.size < 3) null else Fn(p[0].toLongOrNull() ?: return@mapNotNull null, p[2], p[1].toLongOrNull() ?: 0L)
        }
    }

    /** Full analysis of the file (about half a minute for a 7–14 MB driver), then the list of functions. */
    suspend fun functions(f: InspectFile, refresh: Boolean = false): List<Fn> {
        if (!refresh) cachedFunctions(f)?.let { return it }
        val list = withSession(f) { s ->
            if (!analysed) { s.cmd("aaa"); analysed = true }
            parseFunctions(s.cmd("aflj"))
        }
        File(dir(f), "functions.tsv").writeText(list.joinToString("") { "${it.addr}\t${it.size}\t${it.name}\n" })
        return list
    }

    private fun parseFunctions(json: String): List<Fn> {
        val a = runCatching { JSONArray(json.trim().ifEmpty { "[]" }) }.getOrDefault(JSONArray())
        return List(a.length()) { i ->
            val o = a.getJSONObject(i)
            // realsz = bytes of the function's own blocks; size is the span, which wrong boundaries can make megabytes.
            Fn(o.optLong("offset"), o.optString("name"), o.optLong("realsz", o.optLong("size")))
        }.sortedBy { it.addr }
    }

    // ---- Names, notes, edits ----

    private fun tsv(f: InspectFile, name: String): MutableMap<Long, String> {
        val file = File(dir(f), name)
        if (!file.exists()) return LinkedHashMap()
        return file.readLines().mapNotNull { l ->
            val i = l.indexOf('\t')
            if (i < 0) null else (l.substring(0, i).toLongOrNull() ?: return@mapNotNull null) to l.substring(i + 1)
        }.toMap(LinkedHashMap())
    }

    private fun writeTsv(f: InspectFile, name: String, m: Map<Long, String>) {
        File(dir(f), name).writeText(m.entries.joinToString("") { "${it.key}\t${it.value}\n" })
    }

    fun names(f: InspectFile): Map<Long, String> = tsv(f, "names.tsv")
    fun notes(f: InspectFile): Map<Long, String> = tsv(f, "notes.tsv")

    private fun applyNames(f: InspectFile, s: RzSession) {
        for ((addr, n) in names(f)) {
            s.cmd("af @ $addr")
            s.cmd("afn $n @ $addr")
        }
    }

    /** Gives the function at [addr] a name of your own; it is used in every decompiled view from then on. */
    suspend fun rename(f: InspectFile, addr: Long, name: String) {
        val clean = name.trim().replace(Regex("[^A-Za-z0-9_:.]"), "_").ifEmpty { return }
        val m = tsv(f, "names.tsv").apply { put(addr, clean) }
        writeTsv(f, "names.tsv", m)
        withSession(f) { s -> s.cmd("af @ $addr"); s.cmd("afn $clean @ $addr") }
        cachedFunctions(f)?.let { list ->
            File(dir(f), "functions.tsv").writeText(list.joinToString("") { "${it.addr}\t${it.size}\t${if (it.addr == addr) clean else it.name}\n" })
        }
    }

    fun setNote(f: InspectFile, addr: Long, note: String) {
        val m = tsv(f, "notes.tsv")
        val one = note.replace('\n', ' ').trim()
        if (one.isEmpty()) m.remove(addr) else m[addr] = one
        writeTsv(f, "notes.tsv", m)
    }

    private fun editedFile(f: InspectFile, addr: Long) = File(dir(f), "edited/${"%x".format(addr)}.c")

    fun edited(f: InspectFile, addr: Long): String? = editedFile(f, addr).takeIf { it.exists() }?.readText()

    fun saveEdit(f: InspectFile, addr: Long, text: String) {
        editedFile(f, addr).apply { parentFile?.mkdirs() }.writeText(text)
    }

    fun revertEdit(f: InspectFile, addr: Long) {
        editedFile(f, addr).delete()
    }

    // ---- Decompiling ----

    private fun header(f: InspectFile, addr: Long, name: String, size: Long, note: String?): String = buildString {
        append("// ").append(name).append('\n')
        append("// 0x").append("%x".format(addr)).append(", ").append(size).append(" bytes, ").append(f.label).append('\n')
        append("// Decompiled by DriverLab (rizin + Ghidra). Not the original source.\n")
        if (!note.isNullOrBlank()) append("// Note: ").append(note).append('\n')
        append('\n')
    }

    private fun clean(code: String): String =
        code.lines().filterNot { it.startsWith("// WARNING: [rz-ghidra]") || it.startsWith("// args may be inaccurate") }
            .joinToString("\n").trim() + "\n"

    /** C code for one function: your edited copy if there is one, else the made source, else decompiled now. */
    suspend fun code(f: InspectFile, addr: Long): String {
        edited(f, addr)?.let { return it }
        val made = sourceFileFor(f, addr)
        if (made != null && made.exists()) return made.readText()
        return decompile(f, addr)
    }

    suspend fun decompile(f: InspectFile, addr: Long): String {
        val note = notes(f)[addr]
        return withSession(f) { s ->
            s.cmd("af @ $addr")
            val info = s.cmd("afi @ $addr")
            val name = Regex("""(?m)^name:\s*(.+)$""").find(info)?.groupValues?.get(1)?.trim() ?: "fcn.%08x".format(addr)
            val size = Regex("""(?m)^size:\s*(\d+)""").find(info)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val code = s.cmd("pdg @ $addr")
            header(f, addr, name, size, note) + if (code.isBlank()) "// No function could be decompiled at this address.\n" else clean(code)
        }
    }

    // ---- Whole-file source code ----

    fun sourceDir(f: InspectFile) = File(dir(f), "source")

    private fun sourceName(fn: Fn): String = "%08x_".format(fn.addr) + fn.name.replace(Regex("[^A-Za-z0-9_.]"), "_").take(80) + ".c"

    private fun sourceFileFor(f: InspectFile, addr: Long): File? {
        val idx = File(sourceDir(f), "index.tsv")
        if (!idx.exists()) return null
        val key = "$addr\t"
        return idx.bufferedReader().useLines { lines -> lines.firstOrNull { it.startsWith(key) } }
            ?.split('\t')?.getOrNull(3)?.let { File(sourceDir(f), "functions/$it") }
    }

    fun hasSource(f: InspectFile) = File(sourceDir(f), "index.tsv").exists()

    /**
     * Decompiles every function of the file into source/functions/ (one .c file per function) with an index. Uses its own engine,
     * so the screens can keep working on other files meanwhile; runs until done or stopped.
     */
    fun makeSource(f: InspectFile) {
        val k = f.path
        if (running[k]?.isActive == true) return
        _jobs.update { it + (k to SourceJob(phase = "Finding functions", running = true)) }
        running[k] = scope.launch(Dispatchers.IO) {
            val out = sourceDir(f)
            var s: RzSession? = null
            fun open(): RzSession {
                s?.close()
                return RzSession(context, File(f.path)).also { n ->
                    s = n
                    jobSessions[k] = n
                    applyNames(f, n)
                    n.cmd("aaa")
                }
            }
            try {
                ensureData()
                var eng = open()
                val list = parseFunctions(eng.cmd("aflj"))
                File(dir(f), "functions.tsv").writeText(list.joinToString("") { "${it.addr}\t${it.size}\t${it.name}\n" })
                out.deleteRecursively()
                val fnDir = File(out, "functions").apply { mkdirs() }
                val notes = notes(f)
                val index = StringBuilder()
                val skipped = StringBuilder()
                var restarts = 0
                _jobs.update { it + (k to SourceJob(0, list.size, "Decompiling", running = true)) }
                for ((i, fn) in list.withIndex()) {
                    if (!isActive) break
                    val code = if (fn.size > MAX_DECOMPILE) {
                        skipped.append("0x%x\t%d\t%s\ttoo large\n".format(fn.addr, fn.size, fn.name))
                        "// Not decompiled: the analysis made this function ${fn.size} bytes long, which usually means wrong boundaries.\n"
                    } else try {
                        clean(eng.cmd("pdg @ ${fn.addr}"))
                    } catch (e: IOException) {
                        if (!isActive) throw e
                        if (++restarts > MAX_RESTARTS) throw IOException("The decompiler stopped $restarts times, last at 0x%x.".format(fn.addr))
                        skipped.append("0x%x\t%d\t%s\tdecompiler stopped\n".format(fn.addr, fn.size, fn.name))
                        _jobs.update { it + (k to SourceJob(i, list.size, "Restarting the engine", running = true)) }
                        eng = open()
                        "// Not decompiled: the decompiler ran out of memory on this function.\n"
                    }
                    val name = sourceName(fn)
                    File(fnDir, name).writeText(header(f, fn.addr, fn.name, fn.size, notes[fn.addr]) + code)
                    index.append(fn.addr).append('\t').append(fn.size).append('\t').append(fn.name).append('\t').append(name).append('\n')
                    if (i % 10 == 0 || i == list.size - 1) _jobs.update { it + (k to SourceJob(i + 1, list.size, "Decompiling", running = true)) }
                }
                if (isActive) {
                    File(out, "index.tsv").writeText(index.toString())
                    if (skipped.isNotEmpty()) File(out, "skipped.tsv").writeText(skipped.toString())
                    File(out, "README.txt").writeText(
                        "Decompiled from ${f.label} (${f.size} bytes) by DriverLab with rizin and the Ghidra decompiler.\n" +
                            "This is C rebuilt from machine code, not the original source: names come from the file's own\n" +
                            "symbols, your renames, or are made up (fcn.<address>). index.tsv: address, size, name, file.\n" +
                            (if (skipped.isNotEmpty()) "skipped.tsv: functions that could not be decompiled and why.\n" else ""),
                    )
                    _jobs.update { it + (k to SourceJob(list.size, list.size, "Done", running = false, skipped = skipped.lines().count { l -> l.isNotBlank() })) }
                } else {
                    out.deleteRecursively()
                    _jobs.update { it - k }
                }
            } catch (e: Exception) {
                if (!isActive) {
                    out.deleteRecursively()
                    _jobs.update { it - k }
                } else {
                    _jobs.update { it + (k to SourceJob(phase = "Stopped", running = false, error = e.message ?: e.toString())) }
                }
            } finally {
                s?.close()
                jobSessions.remove(k)
            }
        }
    }

    fun stopSource(f: InspectFile) {
        running[f.path]?.cancel()
        jobSessions[f.path]?.close()
    }

    /** source/ as a zip, with your edited functions in place of the decompiled ones. */
    suspend fun sourceZip(f: InspectFile): File = withContext(Dispatchers.IO) {
        val src = sourceDir(f)
        val zip = File(context.cacheDir, "${f.key}-source.zip")
        val edits = File(dir(f), "edited")
        val idx = File(src, "index.tsv").takeIf { it.exists() }?.readLines()?.mapNotNull { l ->
            val p = l.split('\t'); if (p.size < 4) null else p[3] to (p[0].toLongOrNull() ?: 0L)
        }?.toMap().orEmpty()
        ZipOutputStream(zip.outputStream().buffered(1 shl 16)).use { z ->
            src.walkTopDown().filter { it.isFile }.forEach { file ->
                val rel = file.relativeTo(src).path
                val addr = idx[file.name]
                val edited = addr?.let { File(edits, "%x.c".format(it)) }?.takeIf { it.exists() }
                z.putNextEntry(ZipEntry("${f.key}-source/$rel"))
                (edited ?: file).inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
        zip
    }

    // ---- Compare ----

    private val generated = Regex("""^(fcn\.|sub\.|sym\.___0000_|loc\.|entry|sym\.imp\.)""")

    suspend fun compare(a: InspectFile, b: InspectFile, withFunctions: Boolean): CompareResult {
        val oa = overview(a)
        val ob = overview(b)
        val ea = exportNames(a)
        val eb = exportNames(b)
        val sa = withContext(Dispatchers.IO) { File(dir(a), "strings.txt").readLines().toHashSet() }
        val sb = withContext(Dispatchers.IO) { File(dir(b), "strings.txt").readLines().toHashSet() }
        var fns = emptyList<Triple<String, Long?, Long?>>()
        var na = 0
        var nb = 0
        if (withFunctions) {
            val fa = functions(a).filterNot { generated.containsMatchIn(it.name) }
            val fb = functions(b).filterNot { generated.containsMatchIn(it.name) }
            na = fa.size; nb = fb.size
            val ma = fa.associate { it.name to it.size }
            val mb = fb.associate { it.name to it.size }
            fns = (ma.keys + mb.keys).sorted().map { Triple(it, ma[it], mb[it]) }.filter { it.second != it.third }
        }
        return CompareResult(
            oa, ob,
            exportsAdded = (eb - ea).sorted(), exportsRemoved = (ea - eb).sorted(),
            neededAdded = (ob.needed - oa.needed.toSet()), neededRemoved = (oa.needed - ob.needed.toSet()),
            gpusAdded = (ob.gpus - oa.gpus.toSet()), gpusRemoved = (oa.gpus - ob.gpus.toSet()),
            stringsAdded = (sb - sa).sorted(), stringsRemoved = (sa - sb).sorted(),
            functions = fns, functionsA = na, functionsB = nb,
        )
    }

    private suspend fun exportNames(f: InspectFile): Set<String> {
        val cache = File(dir(f), "exports.txt")
        if (cache.exists()) return cache.readLines().toSet()
        val names = withSession(f) { s ->
            val arr = runCatching { JSONArray(s.cmd("iEj")) }.getOrDefault(JSONArray())
            List(arr.length()) { i -> arr.getJSONObject(i).let { o -> o.optString("demname").ifEmpty { o.optString("name") } } }
        }.filter { it.isNotBlank() }.toSet()
        cache.writeText(names.joinToString("\n"))
        return names
    }

    // ---- JSON ----

    private fun toJson(o: Overview) = JSONObject().apply {
        put("arch", o.arch); put("bits", o.bits); put("lang", o.lang); put("stripped", o.stripped); put("size", o.size)
        put("version", o.version); put("mesaCommit", o.mesaCommit)
        put("details", JSONArray(o.details.map { JSONArray(listOf(it.first, it.second)) }))
        put("gpus", JSONArray(o.gpus)); put("needed", JSONArray(o.needed))
        put("exports", o.exports); put("names", o.names); put("scrambled", o.scrambled)
        put("sourcePaths", JSONArray(o.sourcePaths)); put("strings", o.strings)
    }

    private fun strs(a: JSONArray?): List<String> = if (a == null) emptyList() else List(a.length()) { a.getString(it) }

    private fun fromJson(j: JSONObject) = Overview(
        arch = j.optString("arch"), bits = j.optInt("bits"), lang = j.optString("lang"), stripped = j.optBoolean("stripped"),
        size = j.optLong("size"), version = j.optString("version"), mesaCommit = j.optString("mesaCommit"),
        details = j.optJSONArray("details")?.let { a -> List(a.length()) { a.getJSONArray(it).let { p -> p.getString(0) to p.getString(1) } } }.orEmpty(),
        gpus = strs(j.optJSONArray("gpus")), needed = strs(j.optJSONArray("needed")),
        exports = j.optInt("exports"), names = j.optInt("names"), scrambled = j.optInt("scrambled"),
        sourcePaths = strs(j.optJSONArray("sourcePaths")), strings = j.optInt("strings"),
    )
}
