package com.driverlab.core

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Test sessions live in files/sessions/<id>/:
 *   session.json, results.tsv, results.log, qpa/, output/, tests/, captures/, export/
 * Finished sessions are also copied to Download/DriverLab/<yyyy-MM-dd>/.
 */
class SessionStore(private val context: Context) {
    private val _sessions = MutableStateFlow<List<SessionMeta>>(emptyList())
    val sessions: StateFlow<List<SessionMeta>> = _sessions.asStateFlow()

    val root: File get() = Paths.sessions(context).apply { mkdirs() }
    fun dir(id: String) = File(root, id)

    fun refresh() {
        val list = root.listFiles()?.filter { it.isDirectory }?.mapNotNull { readMeta(it) }
            ?.sortedByDescending { it.startedAt } ?: emptyList()
        _sessions.value = list
    }

    fun get(id: String): SessionMeta? = _sessions.value.firstOrNull { it.id == id } ?: readMeta(dir(id))

    fun readMeta(d: File): SessionMeta? {
        val f = File(d, "session.json")
        if (!f.exists()) return null
        return runCatching { fromJson(JSONObject(f.readText())) }.getOrNull()
    }

    fun writeMeta(m: SessionMeta) {
        val d = dir(m.id).apply { mkdirs() }
        val tmp = File(d, "session.json.tmp")
        tmp.writeText(toJson(m).toString(2))
        tmp.renameTo(File(d, "session.json"))
        refresh()
    }

    data class Loaded(val results: List<TestResult>, val totalLines: Int, val truncated: Boolean)

    /**
     * Loads results.tsv. Keeps every non-pass result and up to [passCap] passes. A session that ran
     * every test several times gives one result per test (see [combineRuns]); GPU errors and Mesa's
     * known results are added.
     */
    suspend fun loadResults(id: String, passCap: Int = 50_000): Loaded = withContext(Dispatchers.IO) {
        val meta = get(id)
        val f = File(dir(id), "results.tsv")
        var lines = 0
        val raw = ArrayList<TestResult>()
        var truncated = false
        if ((meta?.repeat ?: 1) > 1) {
            val byName = LinkedHashMap<String, ArrayList<TestResult>>()
            if (f.exists()) f.bufferedReader().useLines { seq ->
                for (l in seq) {
                    val r = parseResult(l) ?: continue
                    lines++
                    byName.getOrPut(r.name) { ArrayList(4) } += r
                }
            }
            var passes = 0
            for (runs in byName.values) {
                val r = combineRuns(runs)
                if (r.status == TestStatus.PASS) {
                    if (passes >= passCap) { truncated = true; continue }
                    passes++
                }
                raw += r
            }
        } else {
            var passes = 0
            if (f.exists()) f.bufferedReader().useLines { seq ->
                for (l in seq) {
                    val r = parseResult(l) ?: continue
                    lines++
                    if (r.status == TestStatus.PASS) {
                        if (passes >= passCap) { truncated = true; continue }
                        passes++
                    }
                    raw += r
                }
            }
        }
        Loaded(annotate(id, meta, raw), lines, truncated)
    }

    /** The result of one test as the session screen shows it (several runs combined). */
    suspend fun resultFor(id: String, name: String): TestResult? = withContext(Dispatchers.IO) {
        val runs = runsOf(id, name)
        if (runs.isEmpty()) return@withContext null
        annotate(id, get(id), listOf(if (runs.size > 1) combineRuns(runs) else runs[0])).firstOrNull()
    }

    /** Every run of one test, in order. */
    suspend fun runsOf(id: String, name: String): List<TestResult> = withContext(Dispatchers.IO) {
        val f = File(dir(id), "results.tsv")
        val out = ArrayList<TestResult>()
        if (f.exists()) f.bufferedReader().useLines { seq ->
            for (l in seq) if (l.startsWith(name + "\t")) parseResult(l)?.takeIf { it.name == name }?.let { out += it }
        }
        out
    }

    /** Test name -> combined status and whether its runs differed, for Compare. */
    suspend fun statusMap(id: String): HashMap<String, Pair<TestStatus, Boolean>> = withContext(Dispatchers.IO) {
        val m = HashMap<String, Pair<TestStatus, Boolean>>()
        val f = File(dir(id), "results.tsv")
        if ((get(id)?.repeat ?: 1) > 1) {
            val byName = HashMap<String, ArrayList<TestResult>>()
            if (f.exists()) f.bufferedReader().useLines { seq ->
                for (l in seq) parseResult(l)?.let { byName.getOrPut(it.name) { ArrayList(4) } += it }
            }
            for ((k, runs) in byName) combineRuns(runs).let { m[k] = it.status to it.flaky }
        } else if (f.exists()) {
            f.bufferedReader().useLines { seq -> for (l in seq) parseResult(l)?.let { m[it.name] = it.status to false } }
        }
        m
    }

    /** Per-test counts and the number of tests whose runs differed, for a session's summary. */
    suspend fun combinedCounts(id: String): Pair<Counts, Int> = withContext(Dispatchers.IO) {
        var c = Counts()
        var flaky = 0
        for ((_, v) in statusMap(id)) {
            c = c.plus(v.first.bucket)
            if (v.second) flaky++
        }
        c to flaky
    }

    /** GPU error lines of a session: test name -> the lines seen while it ran. */
    suspend fun gpuErrors(id: String): Map<String, List<String>> = withContext(Dispatchers.IO) {
        val m = HashMap<String, MutableList<String>>()
        File(dir(id), GPU_ERRORS).takeIf { it.exists() }?.bufferedReader()?.useLines { seq ->
            for (l in seq) {
                if (l.startsWith("#")) continue
                val f = l.split('\t', limit = 3)
                if (f.size < 3) continue
                for (n in f[1].split('|')) if (n.isNotEmpty()) m.getOrPut(n) { ArrayList() } += f[2]
            }
        }
        m
    }

    private suspend fun annotate(id: String, meta: SessionMeta?, list: List<TestResult>): List<TestResult> {
        val errors = if ((meta?.gpuErrorLines ?: 0) > 0) gpuErrors(id) else emptyMap()
        val mesa = meta?.let { MesaExpect.forSession(context, it) }
        if (errors.isEmpty() && mesa == null) return list
        return list.map { r ->
            val e = errors[r.name]?.size ?: 0
            val note = mesa?.note(r.name, r.status).orEmpty()
            if (e == 0 && note.isEmpty()) r else r.copy(gpuErrors = e, mesaNote = note)
        }
    }

    /** Feeds the name of every test in results.tsv whose result falls in [buckets], in run order, once each. */
    suspend fun forEachNameWith(id: String, buckets: Set<Bucket>, sink: suspend (String) -> Unit) = withContext(Dispatchers.IO) {
        val f = File(dir(id), "results.tsv")
        val seen = HashSet<String>()
        if (f.exists()) f.bufferedReader().useLines { seq ->
            for (l in seq) {
                val r = parseResult(l) ?: continue
                if (r.status.bucket in buckets && seen.add(r.name)) sink(r.name)
            }
        }
    }

    /** All captured tests of a session: test name -> capture file. */
    suspend fun captures(id: String): Map<String, File> = withContext(Dispatchers.IO) {
        val m = HashMap<String, File>()
        File(dir(id), "results.tsv").takeIf { it.exists() }?.bufferedReader()?.useLines { seq ->
            for (l in seq) {
                val r = parseResult(l) ?: continue
                val c = r.capture ?: continue
                val f = File(dir(id), c)
                if (f.exists()) m[r.name] = f
            }
        }
        m
    }

    fun delete(id: String) {
        get(id)?.exports?.forEach { u -> runCatching { context.contentResolver.delete(Uri.parse(u), null, null) } }
        dir(id).deleteRecursively()
        refresh()
    }

    /** Builds export/<time>_<driver>.zip (everything) and export/<time>_<driver>_test_results.zip. */
    suspend fun buildBundles(m: SessionMeta): List<File> = withContext(Dispatchers.IO) {
        val d = dir(m.id)
        val exp = File(d, "export").apply { deleteRecursively(); mkdirs() }
        val base = "${Fmt.fileTime(m.startedAt)}_${Fmt.slug(m.driverName)}"
        val full = File(exp, "$base.zip")
        ZipOutputStream(full.outputStream().buffered(1 shl 16)).use { zip ->
            d.walkTopDown().filter { it.isFile && !it.path.startsWith(exp.path) && !it.name.endsWith(".tmp") }.forEach { f ->
                zip.putNextEntry(ZipEntry("$base/" + f.relativeTo(d).path))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        // Same layout as the adreno-cmdstream-gather app: <test>.log + <test>.rd per test.
        // The .log is the test process's own output (with the kernel log when recorded),
        // the .rd the gzip capture, kept even when empty.
        val tr = File(exp, "${base}_test_results.zip")
        val results = ArrayList<TestResult>()
        File(d, "results.tsv").takeIf { it.exists() }?.bufferedReader()?.useLines { seq ->
            for (l in seq) parseResult(l)?.let { results += it }
        }
        val perBatch = results.groupingBy { it.batch }.eachCount()
        ZipOutputStream(tr.outputStream().buffered(1 shl 16)).use { zip ->
            val seen = HashSet<String>()
            for (r in results) {
                val fb = fileBase(r.name)
                if (!seen.add(fb)) continue
                val raw = File(d, "output/batch-%05d.txt".format(r.batch))
                val logFile = if (perBatch[r.batch] == 1 && raw.exists()) raw else File(d, "tests/$fb.log")
                if (logFile.exists()) {
                    zip.putNextEntry(ZipEntry("$fb.log")); logFile.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                }
                val cap = File(d, "captures/$fb.rd.gz")
                if (cap.exists()) {
                    zip.putNextEntry(ZipEntry("$fb.rd")); cap.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                }
            }
        }
        val log = File(exp, "${base}_results.log")
        File(d, "results.log").takeIf { it.exists() }?.copyTo(log, overwrite = true)
        listOf(full, tr, log).filter { it.exists() }
    }

    /** Copies files to Download/DriverLab/<day>/ and returns their MediaStore URIs. */
    suspend fun exportToDownloads(files: List<File>, day: String): List<String> = withContext(Dispatchers.IO) {
        files.mapNotNull { f -> runCatching { copyToDownloads(f, day) }.getOrNull() }
    }

    fun copyToDownloads(f: File, day: String, name: String = f.name): String {
        val cr = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(name))
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/DriverLab/$day")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("MediaStore refused $name")
        try {
            cr.openOutputStream(uri)!!.use { out -> f.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            cr.update(uri, values, null, null)
        } catch (e: Exception) {
            cr.delete(uri, null, null)
            throw e
        }
        return uri.toString()
    }

    /**
     * MIME type for saving and sharing. MediaStore appends an extension when the
     * type does not match the name (".log" became ".log.txt" with text/plain),
     * so text files other than .txt are saved as octet-stream.
     */
    fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "zip" -> "application/zip"
            "json" -> "application/json"
            "txt" -> "text/plain"
            "log", "tsv", "qpa", "rd", "gz" -> "application/octet-stream"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }

    /** For sharing, logs are offered as text so viewers and chat apps open them. */
    fun shareMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "log", "txt", "tsv", "qpa" -> "text/plain"
        else -> mimeOf(name)
    }

    fun uriFor(f: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f)

    fun shareIntent(files: List<File>): Intent {
        val uris = ArrayList(files.map { uriFor(it) })
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = shareMime(files[0].name)
                putExtra(Intent.EXTRA_STREAM, uris[0])
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(intent, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    suspend fun copyTo(f: File, target: Uri) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(target)!!.use { out -> f.inputStream().use { it.copyTo(out) } }
    }

    fun sizeOf(f: File): Long = if (f.isDirectory) f.walkTopDown().filter { it.isFile }.sumOf { it.length() } else f.length()

    companion object {
        /** time_ms, tests running then ("|"-separated), kernel log line. */
        const val GPU_ERRORS = "gpu-errors.tsv"

        /** File name base for a test: GLES tests drop the "fd-gles." prefix (test-draw.log). */
        fun fileBase(test: String): String = safeName(test.removePrefix("$GLES_ROOT."))

        fun safeName(test: String): String {
            val clean = test.replace(Regex("[^A-Za-z0-9._-]"), "_")
            return if (clean.length <= 180) clean else clean.take(160) + "_" + Integer.toHexString(test.hashCode())
        }

        fun resultLine(r: TestResult): String = listOf(
            r.name, r.status.key, r.message.replace('\t', ' ').replace('\n', ' '),
            r.durationMs.toString(), r.finishedAt.toString(), r.batch.toString(), r.capture ?: "", r.round.toString(),
        ).joinToString("\t")

        fun parseResult(l: String): TestResult? {
            if (l.isBlank() || l.startsWith("#")) return null
            val f = l.split('\t')
            if (f.size < 6) return null
            return TestResult(
                name = f[0],
                status = TestStatus.fromKey(f[1]),
                message = f[2],
                durationMs = f[3].toLongOrNull() ?: 0,
                finishedAt = f[4].toLongOrNull() ?: 0,
                batch = f[5].toIntOrNull() ?: 0,
                capture = f.getOrNull(6)?.takeIf { it.isNotEmpty() },
                round = f.getOrNull(7)?.toIntOrNull() ?: 1,
            )
        }

        /**
         * One result for a test that ran several times. Runs that were stopped or skipped are left
         * out when there are others. The shown status is the worst outcome (crash, then fail, then
         * warning); "flaky" means the outcomes differed, e.g. passed 3 of 5 runs.
         */
        fun combineRuns(runs: List<TestResult>): TestResult {
            if (runs.size == 1) return runs[0]
            val real = runs.filter { it.status != TestStatus.STOPPED && it.status != TestStatus.NOT_RUN }.ifEmpty { runs }
            fun rank(b: Bucket) = when (b) {
                Bucket.CRASHED -> 0
                Bucket.FAILED -> 1
                Bucket.WARNING -> 2
                Bucket.PASSED -> 3
                Bucket.SKIPPED -> 4
            }
            val worst = real.minBy { rank(it.status.bucket) }
            val outcomes = real.map { it.status.bucket }.filter { it != Bucket.SKIPPED }.toSet()
            val flaky = outcomes.size > 1
            val n = real.size
            val passed = real.count { it.status.bucket == Bucket.PASSED }
            val failed = real.count { it.status.bucket == Bucket.FAILED }
            val crashed = real.count { it.status.bucket == Bucket.CRASHED }
            val note = when {
                passed == n -> "Passed all $n runs"
                outcomes.isEmpty() -> "Not supported in all $n runs"
                !flaky -> "${worst.status.label} in all $n runs"
                else -> buildList {
                    add("Passed $passed of $n runs")
                    if (failed > 0) add("failed $failed")
                    if (crashed > 0) add("crashed $crashed")
                }.joinToString(", ")
            }
            val msg = if (worst.status.bucket == Bucket.PASSED) note else note + (if (worst.message.isNotEmpty()) " — " + worst.message else "")
            return worst.copy(message = msg, flaky = flaky, runsNote = note)
        }

        fun toJson(m: SessionMeta): JSONObject = JSONObject().apply {
            put("id", m.id); put("label", m.label); put("driverId", m.driverId); put("driverName", m.driverName)
            put("driverVersion", m.driverVersion); put("deviceName", m.deviceName)
            put("startedAt", m.startedAt); put("finishedAt", m.finishedAt); put("state", m.state.name)
            put("totalTests", m.totalTests); put("record", m.record); put("captureCapBytes", m.captureCapBytes)
            put("ctsTag", m.ctsTag); put("env", m.env); put("message", m.message)
            put("counts", JSONObject().apply {
                put("passed", m.counts.passed); put("failed", m.counts.failed); put("crashed", m.counts.crashed)
                put("warning", m.counts.warning); put("skipped", m.counts.skipped)
            })
            put("exports", JSONArray(m.exports))
            put("repeat", m.repeat); put("flaky", m.flaky); put("gpuCheck", m.gpuCheck)
            put("gpuErrorLines", m.gpuErrorLines); put("gpuErrorTests", m.gpuErrorTests); put("pairId", m.pairId)
        }

        fun fromJson(o: JSONObject): SessionMeta {
            val c = o.optJSONObject("counts") ?: JSONObject()
            val ex = o.optJSONArray("exports") ?: JSONArray()
            return SessionMeta(
                id = o.getString("id"),
                label = o.optString("label"),
                driverId = o.optString("driverId"),
                driverName = o.optString("driverName"),
                driverVersion = o.optString("driverVersion"),
                deviceName = o.optString("deviceName"),
                startedAt = o.optLong("startedAt"),
                finishedAt = o.optLong("finishedAt"),
                state = runCatching { SessionState.valueOf(o.optString("state")) }.getOrDefault(SessionState.FAILED),
                totalTests = o.optLong("totalTests"),
                counts = Counts(c.optInt("passed"), c.optInt("failed"), c.optInt("crashed"), c.optInt("warning"), c.optInt("skipped")),
                record = o.optBoolean("record"),
                captureCapBytes = o.optLong("captureCapBytes"),
                ctsTag = o.optString("ctsTag"),
                env = o.optString("env"),
                message = o.optString("message"),
                exports = (0 until ex.length()).map { ex.getString(it) },
                repeat = o.optInt("repeat", 1).coerceAtLeast(1),
                flaky = o.optInt("flaky", 0),
                gpuCheck = o.optString("gpuCheck"),
                gpuErrorLines = o.optInt("gpuErrorLines", 0),
                gpuErrorTests = o.optInt("gpuErrorTests", 0),
                pairId = o.optString("pairId"),
            )
        }
    }
}
