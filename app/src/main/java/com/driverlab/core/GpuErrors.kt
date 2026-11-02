package com.driverlab.core

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Watches the kernel log for GPU errors (page faults, hangs, resets) while tests run. A test can
 * pass while the GPU still reports a fault, and apps can't read the kernel log, so this needs root:
 * it runs `su -c 'dmesg -w'`. A marker line written to /dev/kmsg first separates new lines from
 * the ones already in the log.
 */
class GpuErrorWatcher(private val onLine: (String) -> Unit) {
    private val proc = AtomicReference<Process?>(null)

    /** Starts watching. Returns null when it runs, otherwise why it could not start. */
    fun start(): String? {
        val su = findSu()
        val mark = "DRIVERLAB_MARK_" + System.nanoTime()
        val p = try {
            ProcessBuilder(su, "-c", "echo $mark > /dev/kmsg; exec dmesg -w").redirectErrorStream(true).start()
        } catch (e: Exception) {
            return "Root (su) was not found on this phone"
        }
        proc.set(p)
        val ready = CountDownLatch(1)
        val seenMark = AtomicBoolean(false)
        val firstLine = AtomicReference<String?>(null)
        Thread {
            try {
                p.inputStream.bufferedReader().use { rd ->
                    while (true) {
                        val line = rd.readLine() ?: break
                        if (!seenMark.get()) {
                            firstLine.compareAndSet(null, line)
                            if (line.contains(mark)) { seenMark.set(true); ready.countDown() }
                            continue
                        }
                        if (isGpuError(line)) onLine(line.trim())
                    }
                }
            } catch (_: Exception) {
            }
            ready.countDown()
        }.apply { isDaemon = true; name = "gpu-errors"; start() }
        // Older Magisk versions and other root apps show a prompt; give the user time to answer.
        // Magisk 31.1 and newer never ask: the app must be allowed beforehand in its Superuser tab.
        ready.await(45, TimeUnit.SECONDS)
        if (seenMark.get()) return null
        stop()
        val first = firstLine.get()?.trim().orEmpty()
        return when {
            first.isEmpty() -> "Root was not granted (no answer from su)"
            first.contains("Permission denied", ignoreCase = true) || first.contains("not allowed", ignoreCase = true) ->
                "Root was refused. Allow DriverLab first in your root app (Magisk: Superuser tab, switch DriverLab on) and run again"
            else -> "Could not read the kernel log: ${first.take(160)}"
        }
    }

    fun stop() {
        proc.getAndSet(null)?.destroy()
    }

    companion object {
        private val PATTERNS = listOf(
            Regex("(?i)kgsl.*(fault|hang|timeout|timed out|snapshot|reset|recover)"),
            Regex("(?i)adreno.*(fault|hang|timeout|snapshot|reset)"),
            Regex("(?i)faulting block"),
            Regex("(?i)gpu (page ?fault|fault|hang)"),
        )

        fun isGpuError(line: String): Boolean = !line.contains("DRIVERLAB_MARK_") && PATTERNS.any { it.containsMatchIn(line) }

        /** Where Magisk and other root tools put su; falls back to the PATH. */
        fun findSu(): String =
            listOf("/system/bin/su", "/system/xbin/su", "/debug_ramdisk/su", "/sbin/su", "/product/bin/su", "/su/bin/su")
                .firstOrNull { File(it).exists() } ?: "su"
    }
}
