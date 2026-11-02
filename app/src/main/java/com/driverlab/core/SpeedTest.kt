package com.driverlab.core

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import com.driverlab.service.RunService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One scene of the speed test program (native/dlbench). */
data class SpeedScene(val id: String, val title: String, val what: String)

object SpeedScenes {
    val all = listOf(
        SpeedScene("cube", "Spinning cube", "One textured cube per frame, like the AIO cube test. Very little work, so it mostly measures the driver's cost per frame."),
        SpeedScene("fill", "Filling the screen", "16 see-through layers over a 1080p picture: how fast the GPU paints pixels."),
        SpeedScene("texture", "Textures", "8 layers that read a large image: how fast the GPU reads textures."),
        SpeedScene("geometry", "Many triangles", "About a million triangles per frame: how fast the GPU handles shapes."),
        SpeedScene("draws", "Many draw calls", "20,000 small draws per frame, recorded again every frame: the driver's own CPU cost. This matters a lot for games through DXVK."),
        SpeedScene("compute", "Compute maths", "Heavy maths in a compute shader: raw calculating speed."),
        SpeedScene("bandwidth", "Memory speed", "Copies 64 MB in a compute shader: how fast the GPU moves data."),
        SpeedScene("passes", "Keep-the-picture passes", "8 drawing passes per frame that keep the previous picture: how the driver uses the GPU's tile memory (GMEM)."),
        SpeedScene("pipelines", "Shader setup", "Builds 40 pipelines with nothing cached: how long the driver takes to prepare shaders, the cause of stutter in games. Lower is better."),
    )

    fun byId(id: String) = all.firstOrNull { it.id == id }
}

data class SpeedRequest(
    val driverA: String,
    val driverB: String?,
    val scenes: List<String>,
    val rounds: Int,
    val seconds: Int,
    val pauseSec: Int,
    val preview: Boolean,
    val export: Boolean = true,
)

/** One scene on one driver in one round. [error] is set when it did not run. */
data class SpeedSample(
    val scene: String,
    val driverId: String,
    val round: Int,
    val value: Double,
    val unit: String,
    val higherBetter: Boolean,
    val avgMs: Double,
    val p99Ms: Double,
    val gpuMs: Double?,
    val frames: Long,
    val check: String,
    val error: String,
)

data class SpeedDriver(val id: String, val name: String, val version: String, val gpu: String, val env: String)

data class SpeedResult(
    val id: String,
    val startedAt: Long,
    val finishedAt: Long,
    val state: String,
    val request: SpeedRequest,
    val drivers: List<SpeedDriver>,
    val samples: List<SpeedSample>,
    /** Battery temperature (°C) before each run and at the end. */
    val temps: List<Pair<Long, Float>>,
    val message: String,
)

/** One driver's numbers for one scene across the rounds. */
data class SpeedStat(val median: Double, val spread: Double, val runs: Int, val unit: String, val higherBetter: Boolean, val error: String, val badPicture: Boolean)

data class SpeedRow(val scene: String, val stats: Map<String, SpeedStat>, val verdict: String, val winner: String?, val clear: Boolean)

object SpeedMath {
    /** Differences smaller than this are never called a win, even when the rounds agree closely. */
    const val MIN_NOISE = 0.03

    fun stat(samples: List<SpeedSample>): SpeedStat? {
        if (samples.isEmpty()) return null
        val ok = samples.filter { it.error.isEmpty() && it.value > 0 }
        val bad = samples.any { it.check == "differs" }
        if (ok.isEmpty()) return SpeedStat(0.0, 0.0, 0, samples[0].unit, samples[0].higherBetter, samples.firstOrNull { it.error.isNotEmpty() }?.error ?: "no result", bad)
        val v = ok.map { it.value }.sorted()
        val median = if (v.size % 2 == 1) v[v.size / 2] else (v[v.size / 2 - 1] + v[v.size / 2]) / 2
        val spread = if (median > 0) (v.last() - v.first()) / median / 2 else 0.0
        return SpeedStat(median, spread, ok.size, ok[0].unit, ok[0].higherBetter, "", bad)
    }

    fun rows(r: SpeedResult): List<SpeedRow> = r.request.scenes.map { scene ->
        val stats = r.drivers.mapNotNull { d -> stat(r.samples.filter { it.scene == scene && it.driverId == d.id })?.let { d.id to it } }.toMap()
        val names = r.drivers.associate { it.id to it.name }
        val notes = ArrayList<String>()
        var winner: String? = null
        var clear = false
        for ((id, s) in stats) {
            if (s.error.isNotEmpty()) notes += "${names[id]} could not run it: ${s.error}"
            if (s.badPicture) notes += "the picture looked wrong on ${names[id]}"
        }
        val a = r.drivers.getOrNull(0)?.let { stats[it.id] }
        val b = r.drivers.getOrNull(1)?.let { stats[it.id] }
        if (a != null && b != null && a.runs > 0 && b.runs > 0 && a.median > 0 && b.median > 0) {
            // How fast B is compared with A (above 1 = B faster).
            val ratio = if (a.higherBetter) b.median / a.median else a.median / b.median
            val pct = if (ratio >= 1) (ratio - 1) else (1 / ratio - 1)
            val noise = maxOf(a.spread, b.spread, MIN_NOISE)
            if (pct <= noise) {
                notes.add(0, "No clear difference (runs vary by up to ${pctText(noise)})")
            } else {
                winner = if (ratio >= 1) r.drivers[1].id else r.drivers[0].id
                clear = true
                notes.add(0, "${names[winner]} is ${pctText(pct)} faster")
            }
        }
        SpeedRow(scene, stats, notes.joinToString(" · "), winner, clear)
    }

    fun pctText(f: Double): String = String.format(Locale.US, if (f < 0.1) "%.1f%%" else "%.0f%%", f * 100)

    fun valueText(v: Double, unit: String): String = when {
        unit == "fps" -> String.format(Locale.US, if (v >= 100) "%.0f fps" else "%.1f fps", v)
        unit == "ms" -> String.format(Locale.US, "%.1f ms", v)
        else -> String.format(Locale.US, "%.1f %s", v, unit)
    }

    fun statText(s: SpeedStat?): String = when {
        s == null -> "—"
        s.runs == 0 -> "did not run"
        else -> valueText(s.median, s.unit) + if (s.runs > 1) " ±" + pctText(s.spread) else ""
    }
}

/** Runs the speed test program on one or two drivers, alternating rounds, and keeps the results. */
class SpeedManager(private val context: Context, private val drivers: DriverRepository, private val sessions: SessionStore) {
    enum class Phase { STARTING, RUNNING, PAUSE, DONE, STOPPED, FAILED }

    data class Live(
        val id: String,
        val phase: Phase,
        val message: String?,
        /** Program runs finished / planned (rounds × drivers). */
        val done: Int,
        val total: Int,
        val round: Int,
        val rounds: Int,
        val driverName: String,
        val scene: String?,
        val samples: List<SpeedSample>,
        val startedAt: Long,
    ) {
        val active: Boolean get() = phase == Phase.STARTING || phase == Phase.RUNNING || phase == Phase.PAUSE
        val fraction: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
    }

    private val _live = MutableStateFlow<Live?>(null)
    val live: StateFlow<Live?> = _live.asStateFlow()
    private val _results = MutableStateFlow<List<SpeedResult>>(emptyList())
    val results: StateFlow<List<SpeedResult>> = _results.asStateFlow()

    @Volatile private var pending: SpeedRequest? = null
    private val stopFlag = AtomicBoolean(false)
    private val proc = AtomicReference<Process?>(null)

    val isActive: Boolean get() = _live.value?.active == true
    val previewFile: File get() = File(context.cacheDir, "speed-preview.raw")
    private val root: File get() = Paths.speed(context).apply { mkdirs() }

    fun start(req: SpeedRequest): Boolean {
        if (isActive) return false
        pending = req
        stopFlag.set(false)
        previewFile.delete()
        val n = if (req.driverB != null) 2 else 1
        _live.value = Live("", Phase.STARTING, "Starting", 0, req.rounds * n, 1, req.rounds, drivers.get(req.driverA)?.name.orEmpty(), null, emptyList(), System.currentTimeMillis())
        context.startForegroundService(Intent(context, RunService::class.java).setAction(RunService.ACTION_SPEED))
        return true
    }

    fun stop() {
        stopFlag.set(true)
        proc.get()?.destroyForcibly()
    }

    fun dismiss() {
        if (!isActive) _live.value = null
    }

    fun refresh() {
        _results.value = root.listFiles()?.filter { it.isDirectory }?.mapNotNull { load(it.name) }?.sortedByDescending { it.startedAt } ?: emptyList()
    }

    fun load(id: String): SpeedResult? {
        val f = File(File(root, id), "speed.json")
        return if (f.exists()) runCatching { fromJson(JSONObject(f.readText())) }.getOrNull() else null
    }

    fun dir(id: String) = File(root, id)

    fun delete(id: String) {
        File(root, id).deleteRecursively()
        refresh()
    }

    /** Called by RunService on a background coroutine. */
    suspend fun runPending() {
        val req = pending ?: return
        pending = null
        withContext(Dispatchers.IO) { Engine(req).run() }
    }

    private inner class Engine(val req: SpeedRequest) {
        val startedAt = System.currentTimeMillis()
        val list = listOfNotNull(drivers.get(req.driverA), req.driverB?.let { drivers.get(it) })
        val samples = ArrayList<SpeedSample>()
        val temps = ArrayList<Pair<Long, Float>>()
        val info = HashMap<String, String>()
        var id = ""
        lateinit var dir: File
        @Volatile var phase = Phase.STARTING
        @Volatile var message: String? = null
        @Volatile var done = 0
        @Volatile var round = 1
        @Volatile var driverName = list.firstOrNull()?.name.orEmpty()
        @Volatile var scene: String? = null
        val total = req.rounds * list.size

        fun publish() {
            val s = synchronized(samples) { samples.toList() }
            _live.value = Live(id, phase, message, done, total, round, req.rounds, driverName, scene, s, startedAt)
        }

        fun fail(msg: String) {
            phase = Phase.FAILED; message = msg; publish()
        }

        suspend fun run() {
            val exe = Paths.bench(context)
            if (list.size != (if (req.driverB != null) 2 else 1)) return fail("A selected driver is no longer installed.")
            if (!exe.exists()) return fail("This build of DriverLab has no speed test program.")
            for (d in list) if (!d.isSystem && drivers.libFile(d)?.exists() != true) return fail("The driver file of ${d.name} is missing. Import it again.")
            id = Fmt.sessionStamp(startedAt) + "-speed"
            var n = 2
            while (File(root, id).exists()) id = Fmt.sessionStamp(startedAt) + "-speed-" + n++
            dir = File(root, id).apply { mkdirs() }
            val work = File(Paths.work(context), "speed").apply { deleteRecursively(); mkdirs() }
            phase = Phase.RUNNING
            message = null
            publish()
            var failure: String? = null
            outer@ for (r in 1..req.rounds) {
                round = r
                // Alternate the order so neither driver always runs on a cooler (or hotter) phone.
                val order = if (r % 2 == 1) list else list.reversed()
                for (d in order) {
                    if (stopFlag.get()) break@outer
                    if (done > 0 && req.pauseSec > 0) {
                        phase = Phase.PAUSE
                        for (s in req.pauseSec downTo 1) {
                            if (stopFlag.get()) break@outer
                            message = "Letting the phone cool down: $s s"
                            publish()
                            delay(1000)
                        }
                        phase = Phase.RUNNING
                        message = null
                    }
                    temps += System.currentTimeMillis() to batteryTemp()
                    driverName = d.name
                    publish()
                    val err = runOnce(exe, d, r, work)
                    done++
                    save("running", "")
                    publish()
                    if (err != null) { failure = err; break@outer }
                }
            }
            scene = null
            temps += System.currentTimeMillis() to batteryTemp()
            val state = when {
                failure != null -> "failed"
                stopFlag.get() -> "stopped"
                else -> "finished"
            }
            val result = save(state, failure.orEmpty())
            val report = File(dir, "report.txt").apply { writeText(report(result)) }
            var saved = false
            if (req.export && state != "failed") {
                saved = runCatching {
                    sessions.copyToDownloads(report, Fmt.day(startedAt), "${Fmt.fileTime(startedAt)}_speed-test.txt")
                }.isSuccess
            }
            refresh()
            phase = when (state) {
                "finished" -> Phase.DONE
                "stopped" -> Phase.STOPPED
                else -> Phase.FAILED
            }
            message = failure ?: if (saved) "Saved to Download/DriverLab/${Fmt.day(startedAt)}" else null
            publish()
            work.deleteRecursively()
        }

        /** One run of the program on one driver (all scenes). Returns a problem that stops the test, or null. */
        fun runOnce(exe: File, d: DriverInfo, r: Int, work: File): String? {
            val args = ArrayList<String>()
            args += listOf("--vk-lib", if (d.isSystem) "libvulkan.so" else Paths.shim(context).absolutePath)
            args += listOf("--scenes", req.scenes.joinToString(","))
            args += listOf("--seconds", req.seconds.toString(), "--warmup", "1")
            if (req.preview) args += listOf("--preview", previewFile.absolutePath)
            val p = try {
                CtsRunner(context).startProgram(exe, args, d, drivers.libFile(d), work)
            } catch (e: Exception) {
                return "Could not start the speed test program: ${e.message}"
            }
            proc.set(p)
            val started = System.currentTimeMillis()
            val limitMs = (req.scenes.size * (req.seconds + 25L) + 60L) * 1000L
            val timedOut = AtomicBoolean(false)
            val watchdog = Thread {
                try {
                    while (p.isAlive) {
                        Thread.sleep(500)
                        if (System.currentTimeMillis() - started > limitMs) { timedOut.set(true); p.destroyForcibly(); break }
                    }
                } catch (_: InterruptedException) {
                }
            }.apply { isDaemon = true; start() }
            val reported = HashSet<String>()
            val out = File(dir, "output-${Fmt.slug(d.name)}-r$r.txt")
            val tail = ArrayDeque<String>()
            out.bufferedWriter().use { w ->
                try {
                    p.inputStream.bufferedReader().use { rd ->
                        while (true) {
                            val line = rd.readLine() ?: break
                            w.write(line); w.write("\n")
                            if (line.isNotBlank()) { tail.addLast(line); if (tail.size > 6) tail.removeFirst() }
                            val rec = parse(line) ?: continue
                            when (rec.first) {
                                "device" -> info[d.id] = Uri.decode(rec.second["name"].orEmpty())
                                "start" -> { scene = rec.second["scene"]; publish() }
                                "result", "skip", "error" -> {
                                    val f = rec.second
                                    val sc = f["scene"] ?: continue
                                    reported += sc
                                    val s = if (rec.first == "result") SpeedSample(
                                        sc, d.id, r, f["value"]?.toDoubleOrNull() ?: 0.0, f["unit"].orEmpty(), f["better"] != "lower",
                                        f["avg_ms"]?.toDoubleOrNull() ?: 0.0, f["p99_ms"]?.toDoubleOrNull() ?: 0.0, f["gpu_ms"]?.toDoubleOrNull(),
                                        f["frames"]?.toLongOrNull() ?: 0, f["check"].orEmpty(), "",
                                    ) else SpeedSample(
                                        sc, d.id, r, 0.0, "", true, 0.0, 0.0, null, 0, "",
                                        (if (rec.first == "skip") "not supported: " else "") + f["msg"].orEmpty().ifEmpty { rec.first },
                                    )
                                    synchronized(samples) { samples += s }
                                    publish()
                                }
                            }
                        }
                    }
                } catch (e: java.io.IOException) {
                    Thread.interrupted()
                    if (p.isAlive) p.destroyForcibly()
                }
            }
            val exit = runCatching { p.waitFor() }.getOrDefault(-1)
            watchdog.interrupt()
            proc.set(null)
            if (stopFlag.get()) return null
            val why = when {
                timedOut.get() -> "the program stopped answering"
                exit == 3 -> "the GPU stopped responding (device lost)"
                exit > 128 -> "the program crashed (signal ${exit - 128})"
                exit == 2 -> "the driver could not start: " + tail.lastOrNull().orEmpty()
                else -> "no result (exit $exit)"
            }
            synchronized(samples) {
                for (sc in req.scenes) if (sc !in reported) samples += SpeedSample(sc, d.id, r, 0.0, "", true, 0.0, 0.0, null, 0, "", why)
            }
            return null
        }

        fun save(state: String, msg: String): SpeedResult {
            val ds = list.map { d ->
                SpeedDriver(d.id, d.name, d.version.ifEmpty { d.probe?.driverVersion.orEmpty() },
                    info[d.id] ?: d.probe?.deviceName.orEmpty(), d.env)
            }
            val res = SpeedResult(id, startedAt, if (state == "running") 0 else System.currentTimeMillis(), state, req, ds,
                synchronized(samples) { samples.toList() }, temps.toList(), msg)
            val tmp = File(dir, "speed.json.tmp")
            tmp.writeText(toJson(res).toString(2))
            tmp.renameTo(File(dir, "speed.json"))
            return res
        }
    }

    fun batteryTemp(): Float {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val t = i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        return if (t == Int.MIN_VALUE) Float.NaN else t / 10f
    }

    companion object {
        /** "DLBENCH <kind> key=value ... msg=free text" -> kind and fields (values still percent-encoded, except msg). */
        fun parse(line: String): Pair<String, Map<String, String>>? {
            if (!line.startsWith("DLBENCH ")) return null
            val body = line.removePrefix("DLBENCH ")
            val kind = body.substringBefore(' ')
            val rest = body.substringAfter(' ', "")
            val m = HashMap<String, String>()
            val msgAt = rest.indexOf("msg=").takeIf { it == 0 || (it > 0 && rest[it - 1] == ' ') }
            val fields = if (msgAt != null) rest.substring(0, msgAt) else rest
            if (msgAt != null) m["msg"] = Uri.decode(rest.substring(msgAt + 4))
            for (part in fields.split(' ')) {
                val k = part.substringBefore('=', "")
                if (k.isNotEmpty()) m[k] = part.substringAfter('=')
            }
            return kind to m
        }

        fun report(r: SpeedResult): String {
            val sb = StringBuilder()
            val names = listOf("A", "B")
            sb.append("DriverLab speed test\n")
            sb.append("Date:      ${Fmt.dateTime(r.startedAt)} (${r.state})\n")
            val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL.takeUnless { it.isBlank() || it.equals(Build.UNKNOWN, true) } else null
            sb.append("Device:    ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}${soc?.let { ", $it" } ?: ""})\n")
            r.drivers.forEachIndexed { i, d ->
                sb.append("${names[i]}:         ${d.name}${if (d.version.isNotEmpty()) " — " + d.version else ""}${if (d.gpu.isNotEmpty()) " — " + d.gpu else ""}\n")
                if (d.env.isNotBlank()) sb.append("           settings: ${d.env.lines().filter { it.isNotBlank() }.joinToString("  ")}\n")
            }
            sb.append("Settings:  ${r.request.rounds} rounds, ${r.request.seconds} s per scene, ${r.request.pauseSec} s pause between runs")
            if (r.drivers.size > 1) sb.append(", order alternates (A then B, then B then A)")
            sb.append("\n")
            val t = r.temps.map { it.second }.filter { !it.isNaN() }
            if (t.isNotEmpty()) sb.append(String.format(Locale.US, "Battery:   %.1f °C at the start, %.1f °C at the end, %.1f °C highest\n", t.first(), t.last(), t.max()))
            if (r.message.isNotEmpty()) sb.append("Problem:   ${r.message}\n")
            sb.append("\n")
            for (row in SpeedMath.rows(r)) {
                val title = SpeedScenes.byId(row.scene)?.title ?: row.scene
                sb.append(title).append('\n')
                r.drivers.forEachIndexed { i, d -> sb.append("  ${names[i]}: ${SpeedMath.statText(row.stats[d.id])}\n") }
                if (row.verdict.isNotEmpty()) sb.append("  → ${row.verdict}\n")
            }
            sb.append("\nEach number is the middle value (median) of the rounds; ±% is how much the rounds varied.\n")
            sb.append("A gap smaller than that variation, or under 3%, is shown as \"no clear difference\".\n")
            sb.append("\nEvery run (frames, average, slowest 1% and GPU time per scene):\n")
            for (s in r.samples) {
                val d = r.drivers.indexOfFirst { it.id == s.driverId }.let { names.getOrElse(it) { "?" } }
                if (s.error.isNotEmpty()) sb.append("  round ${s.round} $d ${s.scene}: ${s.error}\n")
                else sb.append(String.format(Locale.US, "  round %d %s %s: %s, %d frames, avg %.2f ms, slowest 1%% %.2f ms, GPU %s, picture %s\n",
                    s.round, d, s.scene, SpeedMath.valueText(s.value, s.unit), s.frames, s.avgMs, s.p99Ms,
                    s.gpuMs?.let { String.format(Locale.US, "%.2f ms", it) } ?: "n/a", s.check.ifEmpty { "n/a" }))
            }
            return sb.toString()
        }

        fun toJson(r: SpeedResult): JSONObject = JSONObject().apply {
            put("id", r.id); put("startedAt", r.startedAt); put("finishedAt", r.finishedAt); put("state", r.state); put("message", r.message)
            put("request", JSONObject().apply {
                put("driverA", r.request.driverA); put("driverB", r.request.driverB ?: "")
                put("scenes", JSONArray(r.request.scenes)); put("rounds", r.request.rounds); put("seconds", r.request.seconds)
                put("pauseSec", r.request.pauseSec); put("preview", r.request.preview); put("export", r.request.export)
            })
            put("drivers", JSONArray(r.drivers.map { d ->
                JSONObject().apply { put("id", d.id); put("name", d.name); put("version", d.version); put("gpu", d.gpu); put("env", d.env) }
            }))
            put("samples", JSONArray(r.samples.map { s ->
                JSONObject().apply {
                    put("scene", s.scene); put("driverId", s.driverId); put("round", s.round); put("value", s.value); put("unit", s.unit)
                    put("higherBetter", s.higherBetter); put("avgMs", s.avgMs); put("p99Ms", s.p99Ms); put("gpuMs", s.gpuMs ?: -1.0)
                    put("frames", s.frames); put("check", s.check); put("error", s.error)
                }
            }))
            put("temps", JSONArray(r.temps.map { JSONArray(listOf(it.first, if (it.second.isNaN()) -1000.0 else it.second.toDouble())) }))
        }

        fun fromJson(o: JSONObject): SpeedResult {
            val q = o.getJSONObject("request")
            val sc = q.getJSONArray("scenes")
            val ds = o.getJSONArray("drivers")
            val ss = o.getJSONArray("samples")
            val ts = o.optJSONArray("temps") ?: JSONArray()
            return SpeedResult(
                id = o.getString("id"),
                startedAt = o.optLong("startedAt"),
                finishedAt = o.optLong("finishedAt"),
                state = o.optString("state"),
                request = SpeedRequest(
                    q.optString("driverA"), q.optString("driverB").ifEmpty { null },
                    (0 until sc.length()).map { sc.getString(it) }, q.optInt("rounds", 1), q.optInt("seconds", 5),
                    q.optInt("pauseSec", 0), q.optBoolean("preview"), q.optBoolean("export", true),
                ),
                drivers = (0 until ds.length()).map { i ->
                    val d = ds.getJSONObject(i)
                    SpeedDriver(d.optString("id"), d.optString("name"), d.optString("version"), d.optString("gpu"), d.optString("env"))
                },
                samples = (0 until ss.length()).map { i ->
                    val s = ss.getJSONObject(i)
                    SpeedSample(
                        s.optString("scene"), s.optString("driverId"), s.optInt("round", 1), s.optDouble("value", 0.0), s.optString("unit"),
                        s.optBoolean("higherBetter", true), s.optDouble("avgMs", 0.0), s.optDouble("p99Ms", 0.0),
                        s.optDouble("gpuMs", -1.0).takeIf { it >= 0 }, s.optLong("frames"), s.optString("check"), s.optString("error"),
                    )
                },
                temps = (0 until ts.length()).map { i ->
                    val a = ts.getJSONArray(i)
                    a.getLong(0) to a.getDouble(1).let { if (it < -500) Float.NaN else it.toFloat() }
                },
                message = o.optString("message"),
            )
        }
    }
}
