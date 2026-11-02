package com.driverlab.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Unpacks the CTS data files once per app install/update and checks the binaries. */
class CtsSetup(private val context: Context) {
    sealed interface State {
        data object Checking : State
        data class Working(val progress: Float, val message: String) : State
        data class Ready(val tag: String, val commit: String) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Checking)
    val state: StateFlow<State> = _state.asStateFlow()

    val ctsTag: String get() = (state.value as? State.Ready)?.tag ?: readVersion()?.first.orEmpty()

    private fun readVersion(): Pair<String, String>? = try {
        context.assets.open("cts/cts-version.txt").bufferedReader().readLines().let {
            it.getOrElse(0) { "" }.trim() to it.getOrElse(1) { "" }.trim()
        }
    } catch (e: Exception) {
        null
    }

    suspend fun ensure() = withContext(Dispatchers.IO) {
        try {
            val version = readVersion()
            if (version == null) {
                _state.value = State.Failed("This build does not contain the Vulkan CTS.")
                return@withContext
            }
            val deqp = Paths.deqp(context)
            if (!deqp.exists()) {
                _state.value = State.Failed("The CTS program is missing (${deqp.name}). Reinstall the app.")
                return@withContext
            }
            val dir = Paths.cts(context)
            val marker = File(dir, ".installed")
            val stamp = "${version.first}|${version.second}|${packageStamp()}"
            if (marker.exists() && marker.readText() == stamp && File(dir, "vulkan").isDirectory) {
                _state.value = State.Ready(version.first, version.second)
                return@withContext
            }
            _state.value = State.Working(0f, "Preparing test data")
            dir.deleteRecursively()
            dir.mkdirs()
            val length = try {
                context.assets.openFd("cts/vulkan-data.zip").use { it.length }
            } catch (e: Exception) {
                -1L
            }
            var read = 0L
            val raw = context.assets.open("cts/vulkan-data.zip")
            val counting = object : FilterInputStream(raw) {
                override fun read(): Int = super.read().also { if (it >= 0) read++ }
                override fun read(b: ByteArray, off: Int, len: Int): Int =
                    super.read(b, off, len).also { if (it > 0) read += it }
            }
            unzip(counting, dir) {
                if (length > 0) _state.value = State.Working((read.toFloat() / length).coerceIn(0f, 1f), "Preparing test data")
            }
            marker.writeText(stamp)
            _state.value = State.Ready(version.first, version.second)
        } catch (e: Exception) {
            _state.value = State.Failed("Could not prepare test data: ${e.message}")
        }
    }

    private fun packageStamp(): Long = try {
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
    } catch (e: Exception) {
        0L
    }

    private fun unzip(input: InputStream, target: File, onEntry: () -> Unit) {
        val root = target.canonicalPath + File.separator
        ZipInputStream(input.buffered(1 shl 16)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val out = File(target, e.name)
                if (!out.canonicalPath.startsWith(root)) continue
                if (e.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().buffered(1 shl 16).use { zip.copyTo(it) }
                }
                onEntry()
            }
        }
    }
}

object Paths {
    fun cts(c: Context) = File(c.filesDir, "cts")
    fun drivers(c: Context) = File(c.filesDir, "drivers")
    fun sessions(c: Context) = File(c.filesDir, "sessions")
    fun nativeDir(c: Context) = File(c.applicationInfo.nativeLibraryDir)
    fun deqp(c: Context) = File(nativeDir(c), "libdeqp_vk.so")
    fun shim(c: Context) = File(nativeDir(c), "libdlvkshim.so")
    fun recorder(c: Context) = File(nativeDir(c), "libdlrec.so")
    fun bench(c: Context) = File(nativeDir(c), "libdlbench.so")
    fun speed(c: Context) = File(c.filesDir, "speed")
    fun work(c: Context) = File(c.cacheDir, "work").apply { mkdirs() }
}
