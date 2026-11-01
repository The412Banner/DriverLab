package com.driverlab.core

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * "Find a setting that fixes it": runs one test again on a Turnip driver, once with the driver's
 * own options and then once per Mesa debug setting. A setting that makes a failing test pass
 * points at the part of the driver with the bug.
 *
 * The settings are the ones Mesa's freedreno guide lists as useful to narrow a problem down
 * ("GPU Hang Debugging": TU_DEBUG and IR3_SHADER_DEBUG), plus a few more from the TU_DEBUG list;
 * the texts follow Mesa's docs/envvars.rst. Checked against Mesa main 01bab930 (2026-11-01).
 */
class FlagCheck(
    private val context: Context,
    private val drivers: DriverRepository,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) {
    data class Flag(val variable: String, val value: String, val text: String) {
        val label: String get() = "$variable=$value"
    }

    /** One run: [flag] null = the driver's own options. [status] null = not run yet. */
    data class Row(val flag: Flag?, val status: TestStatus?, val message: String = "", val ms: Long = 0, val running: Boolean = false)

    data class State(
        val sessionId: String,
        val test: String,
        val driverId: String,
        val driverName: String,
        val rows: List<Row>,
        val finished: Boolean,
        val stopped: Boolean,
    ) {
        val fixes: List<Flag> get() =
            if (rows.firstOrNull()?.status?.bucket == Bucket.PASSED) emptyList()
            else rows.filter { it.flag != null && it.status?.bucket == Bucket.PASSED }.mapNotNull { it.flag }
    }

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state.asStateFlow()
    @Volatile private var job: Job? = null
    @Volatile private var process: Process? = null
    private val stopFlag = AtomicBoolean(false)

    val active: Boolean get() = job?.isActive == true

    fun start(sessionId: String, test: String, driverId: String) {
        if (active) return
        val d = drivers.get(driverId) ?: return
        stopFlag.set(false)
        val rows = listOf(Row(null, null)) + FLAGS.map { Row(it, null) }
        _state.value = State(sessionId, test, driverId, d.name, rows, finished = false, stopped = false)
        job = scope.launch(Dispatchers.IO) {
            for (i in rows.indices) {
                if (!isActive || stopFlag.get()) break
                update(i) { it.copy(running = true) }
                val r = runOne(test, d, rows[i].flag)
                update(i) { r }
            }
            _state.value = _state.value?.copy(finished = true, stopped = stopFlag.get())
        }
    }

    fun stop() {
        stopFlag.set(true)
        process?.destroyForcibly()
    }

    fun clear() {
        if (!active) _state.value = null
    }

    private fun update(i: Int, f: (Row) -> Row) {
        val s = _state.value ?: return
        _state.value = s.copy(rows = s.rows.mapIndexed { j, r -> if (j == i) f(r) else r })
    }

    private fun runOne(test: String, d: DriverInfo, flag: Flag?): Row {
        val env = if (flag == null) d.env else withFlag(d.env, flag)
        val work = File(Paths.work(context), "flagcheck").apply { deleteRecursively(); mkdirs() }
        val caselist = File(work, "caselist.txt").apply { writeText("$test\n") }
        val qpa = File(work, "check.qpa")
        val started = System.currentTimeMillis()
        val p = try {
            CtsRunner(context).start(CtsRunner.Launch(caselist, qpa, d.copy(env = env), drivers.libFile(d), work))
        } catch (e: Exception) {
            return Row(flag, TestStatus.INTERNAL_ERROR, "Could not start the CTS: ${e.message}")
        }
        process = p
        val timeoutMs = settings.value.timeoutSec * 1000L
        val timedOut = AtomicBoolean(false)
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
        var status: TestStatus? = null
        var message = ""
        val tail = ArrayDeque<String>()
        runCatching {
            p.inputStream.bufferedReader().useLines { seq ->
                for (line in seq) {
                    if (line.isNotBlank()) { tail.addLast(line); if (tail.size > 4) tail.removeFirst() }
                    val parsed = CtsRunner.parse(line)
                    if (parsed is CtsRunner.Line.Result) { status = parsed.status; message = parsed.message }
                }
            }
        }
        val exit = runCatching { p.waitFor() }.getOrDefault(-1)
        watchdog.interrupt()
        process = null
        val ms = System.currentTimeMillis() - started
        val st = status
        return when {
            st != null -> Row(flag, st, message, ms)
            stopFlag.get() -> Row(flag, TestStatus.STOPPED, "Stopped", ms)
            timedOut.get() -> Row(flag, TestStatus.TIMEOUT, "No result after ${settings.value.timeoutSec} s", ms)
            else -> Row(flag, TestStatus.CRASH, "Ended without a result (exit $exit)" + (tail.lastOrNull()?.let { ": $it" } ?: ""), ms)
        }
    }

    companion object {
        val FLAGS = listOf(
            Flag("TU_DEBUG", "sysmem", "Force sysmem rendering: draw straight to memory instead of the GPU's tile memory (GMEM)."),
            Flag("TU_DEBUG", "gmem", "Force gmem rendering: always draw through the GPU's tile memory."),
            Flag("TU_DEBUG", "nobin", "Disable hardware binning."),
            Flag("TU_DEBUG", "forcebin", "Force hardware binning."),
            Flag("TU_DEBUG", "noubwc", "Disable UBWC compression (Qualcomm's image compression)."),
            Flag("TU_DEBUG", "nolrz", "Disable LRZ (the early low-resolution depth test)."),
            Flag("TU_DEBUG", "nolrzfc", "Disable LRZ fast-clear."),
            Flag("TU_DEBUG", "flushall", "Flush all caches before each draw, dispatch, blit, etc."),
            Flag("TU_DEBUG", "syncdraw", "Wait for the GPU to finish after each draw, dispatch, blit, etc."),
            Flag("TU_DEBUG", "rast_order", "Force rasterization-order attachment access."),
            Flag("TU_DEBUG", "unaligned_store", "Force all GMEM stores to be unaligned."),
            Flag("TU_DEBUG", "3d_load", "Force GMEM loads through the 3D engine."),
            Flag("TU_DEBUG", "nocb", "Disable concurrent binning."),
            Flag("IR3_SHADER_DEBUG", "nouboopt", "Shader compiler: disable lowering UBO to uniform."),
            Flag("IR3_SHADER_DEBUG", "spillall", "Shader compiler: spill as much as possible (tests the spiller)."),
            Flag("IR3_SHADER_DEBUG", "nopreamble", "Shader compiler: disable the preamble pass."),
            Flag("IR3_SHADER_DEBUG", "nofp16", "Shader compiler: don't lower mediump to fp16."),
        )

        /** Mesa's settings only do something on Turnip (Mesa) drivers. */
        fun isTurnip(d: DriverInfo): Boolean = !d.isSystem && (
            d.probe?.deviceName?.contains("Turnip", ignoreCase = true) == true ||
                d.libName.contains("freedreno", ignoreCase = true) || d.libName.contains("turnip", ignoreCase = true) ||
                d.vendor.contains("Mesa", ignoreCase = true)
            )

        /** The driver's options with [f] added (comma-joined with an existing value of the same variable). */
        fun withFlag(env: String, f: Flag): String {
            val lines = env.lines().filter { it.isNotBlank() }.toMutableList()
            val i = lines.indexOfFirst { it.trim().substringBefore('=').trim() == f.variable }
            if (i < 0) {
                lines += f.label
            } else {
                val old = lines[i].substringAfter('=').trim()
                val parts = old.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                lines[i] = "${f.variable}=" + (if (f.value in parts) parts else parts + f.value).joinToString(",")
            }
            return lines.joinToString("\n")
        }
    }
}
