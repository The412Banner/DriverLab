package com.driverlab.core

import android.content.Context
import java.io.File

/**
 * Starts the Vulkan CTS (libdeqp_vk.so, an executable) for one batch of tests.
 *
 * System driver: the normal Android Vulkan loader; recording via LD_PRELOAD of
 * libdlrec.so. Custom driver: --deqp-vk-library-path=libdlvkshim.so, which
 * loads the driver .so named in DRIVERLAB_VK_DRIVER; recording via Mesa's own
 * FD_RD_DUMP (Turnip and other freedreno builds).
 */
class CtsRunner(private val context: Context) {

    sealed interface Line {
        data class Start(val name: String) : Line
        data class Result(val status: TestStatus, val message: String) : Line
        data class Shim(val text: String) : Line
        data object Other : Line
    }

    data class Launch(
        val caselist: File,
        val qpa: File,
        val driver: DriverInfo,
        val driverLib: File?,
        val workDir: File,
        val record: Boolean = false,
        val captureCapBytes: Long = 0,
        val captureFile: File? = null,
        val recorderLog: File? = null,
        val turnipDumpDir: File? = null,
        val testName: String? = null,
    )

    fun start(l: Launch): Process {
        val cmd = ArrayList<String>()
        cmd += Paths.deqp(context).absolutePath
        cmd += "--deqp-caselist-file=${l.caselist.absolutePath}"
        cmd += "--deqp-log-filename=${l.qpa.absolutePath}"
        cmd += "--deqp-archive-dir=${Paths.cts(context).absolutePath}"
        cmd += "--deqp-surface-type=pbuffer"
        cmd += "--deqp-surface-width=256"
        cmd += "--deqp-surface-height=256"
        cmd += "--deqp-log-images=disable"
        cmd += "--deqp-log-shader-sources=disable"
        cmd += "--deqp-shadercache=disable"
        cmd += "--deqp-crashhandler=enable"
        if (!l.driver.isSystem && l.driverLib != null) {
            cmd += "--deqp-vk-library-path=${Paths.shim(context).absolutePath}"
        }

        val pb = ProcessBuilder(cmd).directory(l.workDir).redirectErrorStream(true)
        val env = pb.environment()
        val cache = File(context.cacheDir, "deqp").apply { mkdirs() }
        env["HOME"] = cache.absolutePath
        env["TMPDIR"] = cache.absolutePath
        env.remove("LD_PRELOAD")
        if (!l.driver.isSystem && l.driverLib != null) {
            env["DRIVERLAB_VK_DRIVER"] = l.driverLib.absolutePath
            env["MESA_SHADER_CACHE_DIR"] = File(cache, "mesa").apply { mkdirs() }.absolutePath
        }
        for ((k, v) in parseEnv(l.driver.env)) env[k] = v
        if (l.record) {
            if (l.driver.isSystem) {
                env["LD_PRELOAD"] = Paths.recorder(context).absolutePath
                l.captureFile?.let { env["DLREC_PATH"] = it.absolutePath }
                l.recorderLog?.let { env["DLREC_LOG"] = it.absolutePath }
                env["DLREC_BUF_CAP"] = l.captureCapBytes.toString()
                l.testName?.let { env["DLREC_TESTNAME"] = it }
            } else {
                l.turnipDumpDir?.let {
                    it.mkdirs()
                    env["FD_RD_DUMP"] = "combine"
                    env["FD_RD_DUMP_PATH"] = it.absolutePath
                    env["FD_RD_DUMP_TESTNAME"] = "dl"
                }
            }
        }
        return pb.start()
    }

    companion object {
        private val CASE = Regex("^Test case '(.+)'\\.\\.$")
        private val RESULT = Regex("^  ([A-Za-z]+) \\((.*)\\)$")

        fun parse(line: String): Line {
            CASE.find(line)?.let { return Line.Start(it.groupValues[1]) }
            RESULT.find(line)?.let {
                val key = it.groupValues[1]
                if (key in TestStatus.consoleKeys) return Line.Result(TestStatus.fromKey(key), it.groupValues[2])
            }
            if (line.startsWith("DRIVERLAB_SHIM:")) return Line.Shim(line.removePrefix("DRIVERLAB_SHIM:").trim())
            return Line.Other
        }

        /** "KEY=value" lines; '#' comments and blank lines ignored. */
        fun parseEnv(text: String): List<Pair<String, String>> = text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
            .map { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
            .filter { it.first.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) }
    }
}
