package com.driverlab.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/** Imported custom drivers (files/drivers/<id>/) plus the built-in system driver. */
class DriverRepository(private val context: Context) {
    private val _drivers = MutableStateFlow<List<DriverInfo>>(emptyList())
    val drivers: StateFlow<List<DriverInfo>> = _drivers.asStateFlow()
    private val _probing = MutableStateFlow<Set<String>>(emptySet())
    val probing: StateFlow<Set<String>> = _probing.asStateFlow()

    private val root get() = Paths.drivers(context).apply { mkdirs() }

    fun get(id: String): DriverInfo? = _drivers.value.firstOrNull { it.id == id }
    fun libFile(d: DriverInfo): File? = if (d.isSystem) null else File(File(root, d.id), d.libName)

    fun reload() {
        val list = ArrayList<DriverInfo>()
        list += readSystem()
        root.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach { dir ->
            val f = File(dir, "driver.json")
            if (f.exists()) runCatching { list += fromJson(JSONObject(f.readText())) }
        }
        _drivers.value = list
    }

    private fun readSystem(): DriverInfo {
        val f = File(root, "system.json")
        val saved = if (f.exists()) runCatching { fromJson(JSONObject(f.readText())) }.getOrNull() else null
        return DriverInfo(
            id = DriverInfo.SYSTEM_ID,
            name = "System driver",
            description = "The phone's own Vulkan driver (Qualcomm on Adreno devices).",
            author = "",
            version = saved?.probe?.driverVersion ?: "",
            vendor = "",
            libName = "",
            importedAt = 0,
            env = saved?.env ?: "",
            probe = saved?.probe,
        )
    }

    private fun save(d: DriverInfo) {
        val f = if (d.isSystem) File(root, "system.json") else File(File(root, d.id).apply { mkdirs() }, "driver.json")
        f.writeText(toJson(d).toString(2))
        reload()
    }

    fun updateEnv(id: String, env: String) {
        get(id)?.let { save(it.copy(env = env)) }
    }

    /**
     * A second entry for the same driver files with its own name and settings, e.g. to compare
     * TU_DEBUG=sysmem with TU_DEBUG=gmem. The files are hard links (no extra space) where possible.
     */
    suspend fun copy(id: String, name: String, env: String): Result<DriverInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val src = get(id) ?: error("The driver is no longer installed.")
            require(!src.isSystem) { "The system driver can't be copied; give it settings instead." }
            val newId = "d" + System.currentTimeMillis()
            val from = File(root, src.id)
            val to = File(root, newId).apply { mkdirs() }
            try {
                from.walkTopDown().filter { it.isFile && it.name != DRIVER_JSON }.forEach { f ->
                    val dst = File(to, f.relativeTo(from).path).apply { parentFile?.mkdirs() }
                    try {
                        android.system.Os.link(f.absolutePath, dst.absolutePath)
                    } catch (e: Exception) {
                        f.copyTo(dst, overwrite = true)
                    }
                }
                val d = src.copy(id = newId, name = name.trim().ifEmpty { src.name + " (copy)" }, env = env, importedAt = System.currentTimeMillis())
                save(d)
                d
            } catch (e: Exception) {
                to.deleteRecursively()
                throw e
            }
        }
    }

    fun delete(id: String) {
        if (id == DriverInfo.SYSTEM_ID) return
        File(root, id).deleteRecursively()
        File(context.filesDir, "inspect/$id").deleteRecursively()
        reload()
    }

    /** Imports an AdrenoTools-style zip (meta.json + .so) or a bare .so. */
    suspend fun import(uri: Uri): Result<DriverInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val cr = context.contentResolver
            val displayName = cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: "driver"
            val id = "d" + System.currentTimeMillis()
            val dir = File(root, id).apply { mkdirs() }
            try {
                val head = cr.openInputStream(uri)!!.use { s -> ByteArray(4).also { s.read(it) } }
                val isZip = head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
                var meta: JSONObject? = null
                var libName: String
                var fileCount = 1
                if (isZip) {
                    // Every file is kept at its path: drivers taken from phones come with the
                    // libraries they need (libgsl.so, libllvm-glnext.so, ...).
                    val files = unzipAll(cr, uri, dir)
                    fileCount = files.size
                    val metaPath = files.filter { it.substringAfterLast('/') == "meta.json" }.minByOrNull { it.count { c -> c == '/' } }
                    meta = metaPath?.let { runCatching { JSONObject(File(dir, it).readText()) }.getOrNull() }
                    val sos = files.filter { it.endsWith(".so") }
                    require(sos.isNotEmpty()) { "No .so file in the zip." }
                    val wanted = meta?.optString("libraryName").orEmpty()
                    val metaDir = metaPath?.substringBeforeLast('/', "").orEmpty()
                    val wantedPath = if (metaDir.isEmpty()) wanted else "$metaDir/$wanted"
                    libName = sos.firstOrNull { wanted.isNotEmpty() && it == wantedPath }
                        ?: sos.firstOrNull { wanted.isNotEmpty() && it.substringAfterLast('/') == wanted }
                        ?: sos.firstOrNull { it.substringAfterLast('/').let { b -> b.startsWith("vulkan.") || b.startsWith("libvulkan") } }
                        ?: sos.first()
                } else {
                    libName = if (displayName.endsWith(".so")) displayName.substringAfterLast('/') else "libvulkan_custom.so"
                    cr.openInputStream(uri)!!.use { input -> File(dir, libName).outputStream().use { input.copyTo(it) } }
                }
                checkElf(File(dir, libName))
                val m = meta
                val d = DriverInfo(
                    id = id,
                    name = m?.optString("name").takeUnless { it.isNullOrBlank() } ?: displayName.removeSuffix(".zip"),
                    description = m?.optString("description").orEmpty(),
                    author = m?.optString("author").orEmpty(),
                    version = m?.optString("driverVersion").orEmpty(),
                    vendor = m?.optString("vendor").orEmpty(),
                    libName = libName,
                    importedAt = System.currentTimeMillis(),
                    env = "",
                    probe = null,
                    minApi = m?.optInt("minApi", 0) ?: 0,
                    fileCount = fileCount,
                )
                save(d)
                d
            } catch (e: Exception) {
                dir.deleteRecursively()
                throw e
            }
        }
    }

    /** Extracts every file of the zip into [dir], keeping folders. Returns the relative paths. */
    private fun unzipAll(cr: android.content.ContentResolver, uri: Uri, dir: File): List<String> {
        val base = dir.canonicalFile
        val out = ArrayList<String>()
        var total = 0L
        cr.openInputStream(uri)!!.use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val name = e.name.replace('\\', '/').trimStart('/')
                    if (e.isDirectory || name.isEmpty() || name.endsWith("/")) continue
                    val target = File(base, name).canonicalFile
                    require(target.path.startsWith(base.path + File.separator)) { "Unsafe path in the zip: ${e.name}" }
                    val rel = target.path.removePrefix(base.path + File.separator)
                    require(rel != DRIVER_JSON) { "The zip has its own $DRIVER_JSON; DriverLab needs that name." }
                    require(out.size < MAX_FILES) { "The zip has more than $MAX_FILES files." }
                    target.parentFile?.mkdirs()
                    target.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = zip.read(buf)
                            if (n < 0) break
                            total += n
                            require(total <= MAX_BYTES) { "The zip unpacks to more than ${MAX_BYTES shr 20} MB." }
                            o.write(buf, 0, n)
                        }
                    }
                    if (rel !in out) out += rel
                }
            }
        }
        return out
    }

    private fun checkElf(f: File) {
        val h = f.inputStream().use { s -> ByteArray(20).also { s.read(it) } }
        require(h[0] == 0x7f.toByte() && h[1] == 'E'.code.toByte() && h[2] == 'L'.code.toByte() && h[3] == 'F'.code.toByte()) {
            "${f.name} is not a shared library."
        }
        require(h[4] == 2.toByte()) { "${f.name} is 32-bit; a 64-bit (arm64) driver is needed." }
        val machine = (h[18].toInt() and 0xff) or ((h[19].toInt() and 0xff) shl 8)
        require(machine == 183) { "${f.name} is not an arm64 library." }
    }

    /** Runs dEQP-VK.info.device_properties on the driver and stores what it reports. */
    suspend fun probe(id: String): ProbeInfo? = withContext(Dispatchers.IO) {
        val d = get(id) ?: return@withContext null
        _probing.value = _probing.value + id
        try {
            val work = File(Paths.work(context), "probe-$id").apply { deleteRecursively(); mkdirs() }
            val caselist = File(work, "caselist.txt").apply { writeText("dEQP-VK.info.device_properties\n") }
            val qpa = File(work, "probe.qpa")
            val out = StringBuilder()
            val p = CtsRunner(context).start(
                CtsRunner.Launch(caselist = caselist, qpa = qpa, driver = d, driverLib = libFile(d), workDir = work),
            )
            val reader = Thread {
                runCatching { p.inputStream.bufferedReader().forEachLine { synchronized(out) { if (out.length < 20000) out.append(it).append('\n') } } }
            }.apply { start() }
            val finished = p.waitFor(60, TimeUnit.SECONDS)
            if (!finished) p.destroyForcibly()
            reader.join(2000)
            val text = synchronized(out) { out.toString() }
            val passed = text.contains("  Pass (")
            val msg = when {
                !finished -> "The driver did not answer within 60 s."
                passed -> "OK"
                else -> failureMessage(text, d)
            }
            val info = Qpa.probe(qpa, passed, msg)
            save(d.copy(probe = info, version = d.version.ifEmpty { info.driverVersion }))
            info
        } catch (e: Exception) {
            val info = ProbeInfo(false, "", "", "", "", "", e.message ?: "error", System.currentTimeMillis())
            save(d.copy(probe = info))
            info
        } finally {
            _probing.value = _probing.value - id
        }
    }

    companion object {
        private const val DRIVER_JSON = "driver.json"
        private const val MAX_FILES = 4000
        private const val MAX_BYTES = 2L shl 30

        /**
         * Other folders of an imported driver that hold .so files, for the loader to search after
         * the driver's own folder (zips that keep their libraries in subfolders).
         */
        fun extraLibDirs(driverDir: File, lib: File): List<File> =
            driverDir.walkTopDown().maxDepth(6)
                .filter { it.isDirectory && it != lib.parentFile && it.listFiles()?.any { f -> f.isFile && f.name.endsWith(".so") } == true }
                .toList()

        fun androidName(api: Int): String = when (api) {
            21 -> "5.0"; 22 -> "5.1"; 23 -> "6"; 24 -> "7.0"; 25 -> "7.1"; 26 -> "8.0"; 27 -> "8.1"; 28 -> "9"
            29 -> "10"; 30 -> "11"; 31 -> "12"; 32 -> "12L"; 33 -> "13"; 34 -> "14"
            35 -> "15"; 36 -> "16"; 37 -> "17"
            else -> "API $api"
        }

        /** The reason a probe failed, most useful line first (the full output stays in the run logs). */
        fun failureMessage(output: String, d: DriverInfo): String {
            val lines = output.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
            val shim = lines.filter { it.startsWith("DRIVERLAB_SHIM:") }
                .map { it.removePrefix("DRIVERLAB_SHIM:").trim().replace("dlopen failed: dlopen failed:", "dlopen failed:") }
            val fatal = shim.lastOrNull {
                it.startsWith("dlopen failed") || it.startsWith("driver exports neither") ||
                    it.startsWith("HMI is not") || it.startsWith("HAL open") || it.startsWith("DRIVERLAB_VK_DRIVER")
            }
            val ownFailed = shim.filter { it.startsWith("driver's copy of") }
            val tooNew = ownFailed.any { "cannot locate symbol" in it }
            val out = ArrayList<String>()
            val here = android.os.Build.VERSION.RELEASE
            if (fatal != null && d.needsNewerAndroid) {
                out += "Made for Android ${androidName(d.minApi)} or newer; this phone has Android $here."
            } else if (tooNew) {
                // Their libraries ask the phone's C++ library for functions it doesn't have.
                out += "Probably made for a newer Android than this phone's (Android $here): " +
                    "some of its own libraries can't load here."
            }
            if (fatal != null) out += fatal
            if (ownFailed.isNotEmpty()) {
                out += "${ownFailed.size} of the driver's own libraries could not load:"
                out += ownFailed.take(4)
            }
            if (out.isEmpty()) {
                out += lines.filterNot { it.startsWith("Writing test log") || it.startsWith("dEQP Core") || it.startsWith("  target implementation") }
                    .takeLast(6)
            }
            return out.joinToString("\n").ifBlank { "The test program stopped without a result." }
        }

        fun toJson(d: DriverInfo): JSONObject = JSONObject().apply {
            put("id", d.id); put("name", d.name); put("description", d.description)
            put("author", d.author); put("version", d.version); put("vendor", d.vendor)
            put("libName", d.libName); put("importedAt", d.importedAt); put("env", d.env)
            put("minApi", d.minApi); put("fileCount", d.fileCount)
            d.probe?.let { p ->
                put("probe", JSONObject().apply {
                    put("ok", p.ok); put("deviceName", p.deviceName); put("apiVersion", p.apiVersion)
                    put("driverVersion", p.driverVersion); put("vendorId", p.vendorId); put("deviceId", p.deviceId)
                    put("message", p.message); put("checkedAt", p.checkedAt)
                })
            }
        }

        fun fromJson(o: JSONObject): DriverInfo = DriverInfo(
            id = o.getString("id"),
            name = o.optString("name"),
            description = o.optString("description"),
            author = o.optString("author"),
            version = o.optString("version"),
            vendor = o.optString("vendor"),
            libName = o.optString("libName"),
            importedAt = o.optLong("importedAt"),
            env = o.optString("env"),
            minApi = o.optInt("minApi", 0),
            fileCount = o.optInt("fileCount", 0),
            probe = o.optJSONObject("probe")?.let { p ->
                ProbeInfo(
                    p.optBoolean("ok"), p.optString("deviceName"), p.optString("apiVersion"),
                    p.optString("driverVersion"), p.optString("vendorId"), p.optString("deviceId"),
                    p.optString("message"), p.optLong("checkedAt"),
                )
            },
        )
    }
}
