package com.driverlab.core

import android.content.Context
import android.content.Intent
import android.os.Build
import com.driverlab.service.RunService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Starts, runs and stops one test session at a time. */
class RunManager(
    private val context: Context,
    private val drivers: DriverRepository,
    private val selection: SelectionStore,
    private val sessions: SessionStore,
    private val setup: CtsSetup,
) {
    data class Request(
        val driverId: String,
        val label: String,
        val selection: Map<String, SelectionStore.Entry>,
        val total: Long,
        val record: Boolean,
        val captureCapBytes: Long,
        val timeoutSec: Int,
        val batchSize: Int,
        val perTestLogLimit: Int,
        val export: Boolean,
        val rawLog: Boolean = true,
        /** Test programs running at the same time; recording always uses 1. */
        val jobs: Int = 1,
    )

    enum class Phase { STARTING, RUNNING, FINISHING, DONE, STOPPED, FAILED }

    data class Live(
        val sessionId: String,
        val label: String,
        val driverName: String,
        val startedAt: Long,
        val total: Long,
        val done: Long,
        val counts: Counts,
        val current: String?,
        val currentSince: Long,
        val recent: List<TestResult>,
        val phase: Phase,
        val message: String?,
        val record: Boolean,
        val exported: List<String> = emptyList(),
        /** Test programs running right now. */
        val running: Int = 0,
    ) {
        val active: Boolean get() = phase == Phase.STARTING || phase == Phase.RUNNING || phase == Phase.FINISHING
        val fraction: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
    }

    private val _live = MutableStateFlow<Live?>(null)
    val live: StateFlow<Live?> = _live.asStateFlow()

    @Volatile private var pending: Request? = null
    private val stopFlag = AtomicBoolean(false)
    private val processes: MutableSet<Process> = ConcurrentHashMap.newKeySet()

    val isActive: Boolean get() = _live.value?.active == true

    fun start(req: Request): Boolean {
        if (isActive) return false
        pending = req
        stopFlag.set(false)
        _live.value = Live("", req.label, drivers.get(req.driverId)?.name ?: "", System.currentTimeMillis(), req.total, 0,
            Counts(), null, 0, emptyList(), Phase.STARTING, "Starting", req.record)
        val i = Intent(context, RunService::class.java)
        context.startForegroundService(i)
        return true
    }

    fun stop() {
        stopFlag.set(true)
        processes.forEach { it.destroyForcibly() }
    }

    fun dismiss() {
        if (!isActive) _live.value = null
    }

    /** Called by RunService on a background coroutine. */
    suspend fun runPending(scope: CoroutineScope) {
        val req = pending ?: return
        pending = null
        withContext(Dispatchers.IO) { Engine(req).run(scope) }
    }

    private inner class Engine(val req: Request) {
        val driver = drivers.get(req.driverId)
        val lib = driver?.let { drivers.libFile(it) }
        val startedAt = System.currentTimeMillis()
        lateinit var id: String
        lateinit var dir: File
        lateinit var tsv: BufferedWriter
        lateinit var log: BufferedWriter
        val work = File(Paths.work(context), "run").apply { deleteRecursively(); mkdirs() }

        // Live state (written by the engine thread, published by the ticker).
        @Volatile var done = 0L
        @Volatile var counts = Counts()
        @Volatile var current: String? = null
        @Volatile var currentSince = 0L
        @Volatile var phase = Phase.STARTING
        @Volatile var message: String? = null
        val recent = ArrayDeque<TestResult>()
        val batchCounter = AtomicInteger(0)
        val jobs = if (req.record) 1 else req.jobs.coerceIn(1, 16)
        val running = AtomicInteger(0)
        /** Result files and counters are shared by all workers. */
        val lock = Any()
        @Volatile var anyRan = false
        val perTestFiles = req.record || req.total <= req.perTestLogLimit

        fun publish(exported: List<String> = emptyList()) {
            val r = synchronized(recent) { recent.toList() }
            _live.value = Live(
                if (::id.isInitialized) id else "", req.label, driver?.name ?: "", startedAt, req.total, done,
                counts, current, currentSince, r, phase, message, req.record, exported, running.get(),
            )
        }

        suspend fun run(scope: CoroutineScope) {
            if (driver == null) {
                phase = Phase.FAILED; message = "The selected driver is no longer installed."; publish(); return
            }
            if (setup.state.value !is CtsSetup.State.Ready) {
                phase = Phase.FAILED; message = "Test data is not ready yet."; publish(); return
            }
            if (!driver.isSystem && (lib == null || !lib.exists())) {
                phase = Phase.FAILED; message = "The driver file is missing. Import it again."; publish(); return
            }
            id = Fmt.sessionStamp(startedAt) + "-" + Fmt.slug(driver.name)
            var n = 2
            while (sessions.dir(id).exists()) id = Fmt.sessionStamp(startedAt) + "-" + Fmt.slug(driver.name) + "-" + n++
            dir = sessions.dir(id).apply { mkdirs() }
            listOf("qpa", "output", "tests", "captures").forEach { File(dir, it).mkdirs() }
            tsv = File(dir, "results.tsv").bufferedWriter()
            log = File(dir, "results.log").bufferedWriter()
            tsv.write("# name\tstatus\tmessage\tduration_ms\tfinished_at_ms\tbatch\tcapture\n")
            writeHeader()
            saveMeta(SessionState.RUNNING, "")

            phase = Phase.RUNNING
            message = null
            val ticker = scope.launch {
                while (isActive) { publish(); delay(120) }
            }
            val failureRef = AtomicReference<String?>(null)
            try {
                // One or more workers take items from a queue: a batch of CTS tests, or one GLES
                // program (one per process). With one worker the order is the catalog order.
                val size = when {
                    req.record -> 1
                    jobs > 1 -> minOf(req.batchSize, maxOf(8L, (req.total + jobs * 4 - 1) / (jobs * 4)).toInt()).coerceIn(1, 5000)
                    else -> req.batchSize.coerceIn(1, 5000)
                }
                val queue = Channel<List<String>>(capacity = jobs * 2)
                coroutineScope {
                    repeat(jobs) {
                        launch(Dispatchers.IO) {
                            for (item in queue) {
                                if (stopFlag.get() || failureRef.get() != null) continue
                                val f = if (isGles(item[0])) { runGles(item[0]); null } else runBatch(item)
                                if (f != null) failureRef.compareAndSet(null, f)
                            }
                        }
                    }
                    try {
                        val batch = ArrayList<String>()
                        selection.expand(req.selection) { name ->
                            if (stopFlag.get() || failureRef.get() != null) return@expand
                            if (isGles(name)) {
                                if (batch.isNotEmpty()) { queue.send(batch.toList()); batch.clear() }
                                queue.send(listOf(name))
                                return@expand
                            }
                            batch += name
                            if (batch.size >= size) { queue.send(batch.toList()); batch.clear() }
                        }
                        if (batch.isNotEmpty() && !stopFlag.get() && failureRef.get() == null) queue.send(batch.toList())
                    } finally {
                        queue.close()
                    }
                }
            } catch (e: Exception) {
                failureRef.compareAndSet(null, "Run error: ${e.message}")
            } finally {
                ticker.cancel()
            }
            val failure = failureRef.get()
            current = null
            phase = Phase.FINISHING
            message = "Saving results"
            publish()
            val state = when {
                failure != null -> SessionState.FAILED
                stopFlag.get() -> SessionState.STOPPED
                else -> SessionState.FINISHED
            }
            writeFooter(state, failure)
            runCatching { tsv.close() }
            runCatching { log.close() }
            var meta = saveMeta(state, failure ?: "")
            var exported = emptyList<String>()
            try {
                val bundles = sessions.buildBundles(meta)
                if (req.export) {
                    exported = sessions.exportToDownloads(bundles, Fmt.day(startedAt))
                    meta = meta.copy(exports = exported)
                    sessions.writeMeta(meta)
                    // The zips now live in Download/DriverLab; don't keep a second copy in the app.
                    if (exported.size == bundles.size) bundles.filter { it.name.endsWith(".zip") }.forEach { it.delete() }
                }
            } catch (e: Exception) {
                meta = meta.copy(message = (meta.message + " Export failed: ${e.message}").trim())
                sessions.writeMeta(meta)
            }
            phase = when (state) {
                SessionState.FINISHED -> Phase.DONE
                SessionState.STOPPED -> Phase.STOPPED
                else -> Phase.FAILED
            }
            message = failure ?: if (exported.isNotEmpty()) "Saved to Download/DriverLab/${Fmt.day(startedAt)}" else null
            publish(exported)
            work.deleteRecursively()
        }

        fun saveMeta(state: SessionState, msg: String): SessionMeta {
            val d = driver!!
            val m = SessionMeta(
                id = id, label = req.label, driverId = d.id, driverName = d.name,
                driverVersion = d.version.ifEmpty { d.probe?.driverVersion.orEmpty() },
                deviceName = d.probe?.deviceName?.ifEmpty { null } ?: Build.MODEL,
                startedAt = startedAt, finishedAt = if (state == SessionState.RUNNING) 0 else System.currentTimeMillis(),
                state = state, totalTests = req.total, counts = counts, record = req.record,
                captureCapBytes = req.captureCapBytes, ctsTag = setup.ctsTag, env = d.env, message = msg,
                exports = emptyList(),
            )
            sessions.writeMeta(m)
            return m
        }

        fun writeHeader() {
            val d = driver!!
            val lines = buildList {
                add("DriverLab test log")
                add("Started:   ${Fmt.stamp(startedAt)}")
                add("Device:    ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}${if (Build.VERSION.SDK_INT >= 31) ", " + Build.SOC_MODEL else ""})")
                add("Driver:    ${d.name}${if (d.version.isNotEmpty()) " — " + d.version else ""}")
                d.probe?.takeIf { it.deviceName.isNotEmpty() }?.let { add("GPU:       ${it.deviceName} (Vulkan ${it.apiVersion}, driver ${it.driverVersion})") }
                if (!d.isSystem) add("Library:   ${d.libName}")
                if (d.env.isNotBlank()) add("Env:       " + CtsRunner.parseEnv(d.env).joinToString(" ") { "${it.first}=${it.second}" })
                add("CTS:       ${setup.ctsTag}")
                add("Selection: ${req.label} — ${Fmt.count(req.total)} tests")
                add("Recording: " + if (req.record) "on (${if (req.captureCapBytes == 0L) "all buffers" else "buffers up to " + Fmt.size(req.captureCapBytes)})" else "off")
                add("Timeout:   ${req.timeoutSec} s per test")
                add("-".repeat(78))
            }
            log.write(lines.joinToString("\n") + "\n")
            log.flush()
        }

        fun writeFooter(state: SessionState, failure: String?) {
            val c = counts
            val end = System.currentTimeMillis()
            log.write("-".repeat(78) + "\n")
            log.write("Finished:  ${Fmt.stamp(end)} (${state.name.lowercase()}, ${Fmt.duration(end - startedAt)})\n")
            log.write("Results:   ${c.passed} passed, ${c.failed} failed, ${c.crashed} crashed/timeout, ${c.warning} warnings, ${c.skipped} not supported — ${Fmt.count(done)} of ${Fmt.count(req.total)} tests\n")
            failure?.let { log.write("Problem:   $it\n") }
            log.flush()
        }

        fun finish(
            name: String, status: TestStatus, msg: String, started: Long, stdout: CharSequence,
            recLog: File?, capture: String?, rawOut: File? = null, batch: Int = 0,
        ) = synchronized(lock) {
            val now = System.currentTimeMillis()
            val r = TestResult(name, status, msg, if (started > 0) now - started else 0, now, batch, capture)
            tsv.write(SessionStore.resultLine(r)); tsv.write("\n")
            val tag = status.key.uppercase().padEnd(20)
            log.write("[${Fmt.stamp(now)}] $tag ${Fmt.duration(r.durationMs).padStart(9)}  $name${if (msg.isNotEmpty()) " — $msg" else ""}\n")
            if (done % 50 == 0L) { tsv.flush(); log.flush() }
            if (perTestFiles) {
                val f = File(dir, "tests/${SessionStore.fileBase(name)}.log")
                f.bufferedWriter().use { w ->
                    w.write("test: $name\nstatus: ${status.key}${if (msg.isNotEmpty()) " ($msg)" else ""}\nduration: ${r.durationMs} ms\nfinished: ${Fmt.stamp(now)}\ndriver: ${driver!!.name}\n")
                    if (recLog != null && recLog.exists()) {
                        w.write("\n---- GPU kernel log (KGSL ioctls) ----\n")
                        recLog.bufferedReader().use { it.copyTo(w) }
                    }
                    w.write("\n---- test output ----\n")
                    if (rawOut != null && rawOut.exists()) rawOut.bufferedReader().use { it.copyTo(w) } else w.write(stdout.toString())
                }
            }
            done++
            counts = counts.plus(status.bucket)
            if (status != TestStatus.NOT_RUN) anyRan = true
            synchronized(recent) {
                recent.addFirst(r)
                while (recent.size > 40) recent.removeLast()
            }
        }

        /** Runs one freedreno GLES test program (system driver only). */
        fun runGles(name: String) {
            val bn = batchCounter.incrementAndGet()
            val test = name.removePrefix("$GLES_ROOT.")
            if (!driver!!.isSystem) {
                finish(name, TestStatus.NOT_SUPPORTED, "OpenGL ES tests run on the system driver only", 0, "", null, null, batch = bn)
                return
            }
            val exe = File(Paths.nativeDir(context), "libtest_${test.removePrefix("test-")}.so")
            if (!exe.exists()) {
                finish(name, TestStatus.INTERNAL_ERROR, "Test program ${exe.name} is missing from this build", 0, "", null, null, batch = bn)
                return
            }
            val tag = "%05d".format(bn)
            val out = File(dir, "output/batch-$tag.txt")
            val captureTmp = File(work, "capture-$tag.rd.gz").apply { delete() }
            val recLog = File(work, "kgsl-$tag.log").apply { delete() }
            val started = System.currentTimeMillis()
            current = name
            currentSince = started
            val p = try {
                CtsRunner(context).startGles(
                    exe,
                    CtsRunner.Launch(
                        caselist = File(work, "none"), qpa = File(work, "none"), driver = driver!!, driverLib = null, workDir = work,
                        record = req.record, captureCapBytes = req.captureCapBytes, captureFile = captureTmp,
                        recorderLog = recLog, testName = test, rawLogToStdout = req.rawLog,
                    ),
                )
            } catch (e: Exception) {
                current = null
                finish(name, TestStatus.INTERNAL_ERROR, "Could not start ${exe.name}: ${e.message}", started, "", null, null, batch = bn)
                return
            }
            processes.add(p)
            running.incrementAndGet()
            val timedOut = AtomicBoolean(false)
            val timeoutMs = req.timeoutSec * 1000L
            val watchdog = Thread {
                try {
                    while (p.isAlive) {
                        Thread.sleep(250)
                        if (timeoutMs > 0 && System.currentTimeMillis() - started > timeoutMs) {
                            timedOut.set(true); p.destroyForcibly(); break
                        }
                    }
                } catch (_: InterruptedException) {
                }
            }.apply { isDaemon = true; start() }
            val tail = ArrayDeque<String>()
            var problem: String? = null
            out.bufferedWriter().use { w ->
                p.inputStream.bufferedReader().use { rd ->
                    while (true) {
                        val line = rd.readLine() ?: break
                        w.write(line); w.write("\n")
                        if (line.isNotBlank() && !line.startsWith("\t") && !line.startsWith("> [") && !line.startsWith("< [")) {
                            tail.addLast(line); if (tail.size > 6) tail.removeFirst()
                        }
                        if (problem == null && (line.contains("ERROR") || line.startsWith("Failed"))) problem = line.trim()
                    }
                }
            }
            val exit = runCatching { p.waitFor() }.getOrDefault(-1)
            watchdog.interrupt()
            processes.remove(p)
            running.decrementAndGet()
            if (current == name) current = null
            val status = when {
                stopFlag.get() -> TestStatus.STOPPED
                timedOut.get() -> TestStatus.TIMEOUT
                exit == 0 -> TestStatus.PASS
                exit == 77 -> TestStatus.NOT_SUPPORTED
                isSignal(exit) -> TestStatus.CRASH
                else -> TestStatus.FAIL
            }
            val msg = when (status) {
                TestStatus.PASS -> tail.lastOrNull { it.startsWith("PASS") || it.contains(" ok") }.orEmpty()
                TestStatus.TIMEOUT -> "No result after ${req.timeoutSec} s; stopped"
                TestStatus.STOPPED -> "Stopped by user"
                TestStatus.NOT_SUPPORTED -> tail.lastOrNull().orEmpty()
                else -> (problem ?: tail.lastOrNull().orEmpty()) + " (exit ${exitText(exit)})"
            }
            val cap = if (req.record && captureTmp.exists()) {
                val base = SessionStore.fileBase(name)
                captureTmp.copyTo(File(dir, "captures/$base.rd.gz"), overwrite = true)
                captureTmp.delete()
                "captures/$base.rd.gz"
            } else null
            finish(name, status, msg.take(300), started, "", if (req.rawLog) null else recLog, cap, rawOut = out, batch = bn)
        }

        /** Runs one batch, restarting after crashes. Returns a fatal problem, or null. */
        fun runBatch(tests: List<String>): String? {
            val remaining = LinkedHashSet(tests)
            var fruitless = 0
            while (remaining.isNotEmpty() && !stopFlag.get()) {
                val bn = batchCounter.incrementAndGet()
                val tag = "%05d".format(bn)
                val caselist = File(work, "caselist-$tag.txt").apply { writeText(remaining.joinToString("\n", postfix = "\n")) }
                val qpa = File(dir, "qpa/batch-$tag.qpa")
                val out = File(dir, "output/batch-$tag.txt")
                val single = if (req.record) remaining.first() else null
                val captureTmp = File(work, "capture-$tag.rd.gz").apply { delete() }
                val recLog = File(work, "kgsl-$tag.log").apply { delete() }
                val dumpDir = File(work, "turnip-dump-$tag").apply { deleteRecursively() }
                val p = try {
                    CtsRunner(context).start(
                        CtsRunner.Launch(
                            caselist = caselist, qpa = qpa, driver = driver!!, driverLib = lib, workDir = work,
                            record = req.record, captureCapBytes = req.captureCapBytes,
                            captureFile = captureTmp, recorderLog = recLog, turnipDumpDir = dumpDir, testName = single,
                            rawLogToStdout = req.rawLog,
                        ),
                    )
                } catch (e: Exception) {
                    return "Could not start the CTS: ${e.message}"
                }
                processes.add(p)
                running.incrementAndGet()
                // This batch's own test and start time, for its watchdog (several batches can run at once).
                val runningTest = AtomicReference<String?>(null)
                val runningSince = AtomicLong(0)
                var cur: String? = null
                var curStart = 0L
                val slice = StringBuilder()
                val tail = ArrayDeque<String>()
                var progressed = false
                var stash: Stash? = null
                val timedOut = AtomicBoolean(false)
                val shim = ArrayList<String>()
                val timeoutMs = req.timeoutSec * 1000L
                val lastLine = AtomicLong(System.currentTimeMillis())
                val watchdog = Thread {
                    try {
                        while (true) {
                            Thread.sleep(250)
                            val now = System.currentTimeMillis()
                            val c = runningTest.get()
                            if (c != null && timeoutMs > 0 && now - runningSince.get() > timeoutMs) {
                                timedOut.set(true); p.destroyForcibly(); break
                            }
                            // Silent with no test running (stuck while starting up or between tests).
                            if (c == null && timeoutMs > 0 && now - lastLine.get() > timeoutMs + 30_000) {
                                p.destroyForcibly(); break
                            }
                            if (!p.isAlive) break
                        }
                    } catch (_: InterruptedException) {
                    }
                }.apply { isDaemon = true; start() }

                fun captureFor(name: String): String? {
                    if (!req.record) return null
                    val base = SessionStore.fileBase(name)
                    if (driver!!.isSystem) {
                        // Kept even when empty, like the original app's zip.
                        if (captureTmp.exists()) {
                            val dst = File(dir, "captures/$base.rd.gz")
                            captureTmp.copyTo(dst, overwrite = true); captureTmp.delete()
                            return "captures/$base.rd.gz"
                        }
                        return null
                    }
                    val files = dumpDir.listFiles()?.filter { it.name.endsWith(".rd.gz") }?.sortedByDescending { it.length() } ?: emptyList()
                    if (files.isEmpty()) return null
                    files.forEachIndexed { i, f ->
                        val dst = File(dir, "captures/$base${if (i == 0) "" else "~${i + 1}"}.rd.gz")
                        f.copyTo(dst, overwrite = true); f.delete()
                    }
                    return "captures/$base.rd.gz"
                }

                out.bufferedWriter().use { w ->
                    p.inputStream.bufferedReader().use { rd ->
                        while (true) {
                            val line = rd.readLine() ?: break
                            lastLine.set(System.currentTimeMillis())
                            w.write(line); w.write("\n")
                            tail.addLast(line); if (tail.size > 12) tail.removeFirst()
                            when (val parsed = CtsRunner.parse(line)) {
                                is CtsRunner.Line.Start -> {
                                    cur?.let { prev ->
                                        finish(prev, TestStatus.INTERNAL_ERROR, "No result printed", curStart, slice, null, null, batch = bn)
                                        remaining.remove(prev)
                                    }
                                    cur = parsed.name; curStart = System.currentTimeMillis(); slice.setLength(0)
                                    slice.append(line).append('\n')
                                    runningSince.set(curStart); runningTest.set(parsed.name)
                                    current = parsed.name; currentSince = curStart
                                }
                                is CtsRunner.Line.Result -> {
                                    val name = cur
                                    if (name != null) {
                                        slice.append(line).append('\n')
                                        if (single != null) {
                                            // Recording: the capture is complete only after the process exits.
                                            stash = Stash(name, parsed.status, parsed.message, curStart, slice.toString())
                                        } else {
                                            finish(name, parsed.status, parsed.message, curStart, slice, null, null, batch = bn)
                                        }
                                        remaining.remove(name)
                                        progressed = true
                                        cur = null; runningTest.set(null)
                                        if (current == name) current = null
                                    }
                                }
                                is CtsRunner.Line.Shim -> {
                                    shim += parsed.text
                                    slice.append(line).append('\n')
                                }
                                else -> if (cur != null && slice.length < 65536) slice.append(line).append('\n')
                            }
                        }
                    }
                }
                val exit = runCatching { p.waitFor() }.getOrDefault(-1)
                watchdog.interrupt()
                processes.remove(p)
                running.decrementAndGet()
                stash?.let { st ->
                    finish(st.name, st.status, st.message, st.started, st.stdout, recLog, captureFor(st.name), rawOut = out, batch = bn)
                }
                val name = cur
                if (name != null) {
                    val status = when {
                        stopFlag.get() -> TestStatus.STOPPED
                        timedOut.get() -> TestStatus.TIMEOUT
                        else -> TestStatus.CRASH
                    }
                    val msg = when (status) {
                        TestStatus.TIMEOUT -> "No result after ${req.timeoutSec} s; stopped"
                        TestStatus.STOPPED -> "Stopped by user"
                        else -> "Process ended without a result (exit ${exitText(exit)})"
                    }
                    val cap = if (single != null) captureFor(name) else null
                    finish(name, status, msg, curStart, slice, if (single != null) recLog else null, cap, rawOut = if (single != null) out else null, batch = bn)
                    remaining.remove(name)
                    progressed = true
                    if (current == name) current = null
                }
                if (stopFlag.get()) break
                if (!progressed) {
                    if (exit == 0) {
                        remaining.forEach { finish(it, TestStatus.NOT_RUN, "Not found in this CTS build", 0, "", null, null, batch = bn) }
                        remaining.clear()
                    } else {
                        fruitless++
                        val why = (shim.lastOrNull()?.let { "Driver: $it. " } ?: "") +
                            "CTS exited (${exitText(exit)}) before running a test: " + tail.takeLast(4).joinToString(" | ")
                        if (!anyRan && fruitless >= 2) return why
                        if (fruitless >= 2) {
                            remaining.forEach { finish(it, TestStatus.INTERNAL_ERROR, why, 0, "", null, null, batch = bn) }
                            remaining.clear()
                        }
                    }
                } else {
                    fruitless = 0
                    if (exit == 0 && remaining.isNotEmpty()) {
                        remaining.forEach { finish(it, TestStatus.NOT_RUN, "Not found in this CTS build", 0, "", null, null, batch = bn) }
                        remaining.clear()
                    }
                }
            }
            tsv.flush(); log.flush()
            return null
        }

        /** Java reports a process killed by signal N as 128 + N (N <= 64); exit(-1) is 255. */
        fun isSignal(code: Int): Boolean = code in 129..192

        fun exitText(code: Int): String = when {
            isSignal(code) -> "signal ${code - 128}${signalName(code - 128)}"
            code == 255 -> "code 255 (exit -1)"
            else -> "code $code"
        }

        fun signalName(s: Int) = when (s) {
            4 -> " SIGILL"; 6 -> " SIGABRT"; 7 -> " SIGBUS"; 9 -> " SIGKILL"; 11 -> " SIGSEGV"; else -> ""
        }
    }

    private data class Stash(val name: String, val status: TestStatus, val message: String, val started: Long, val stdout: String)
}
