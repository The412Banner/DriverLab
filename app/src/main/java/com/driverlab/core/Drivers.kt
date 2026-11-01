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

    fun delete(id: String) {
        if (id == DriverInfo.SYSTEM_ID) return
        File(root, id).deleteRecursively()
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
                if (isZip) {
                    val sos = ArrayList<String>()
                    cr.openInputStream(uri)!!.use { input ->
                        ZipInputStream(input.buffered()).use { zip ->
                            while (true) {
                                val e = zip.nextEntry ?: break
                                if (e.isDirectory) continue
                                val base = e.name.substringAfterLast('/')
                                when {
                                    base == "meta.json" -> meta = JSONObject(zip.readBytes().decodeToString())
                                    base.endsWith(".so") -> {
                                        File(dir, base).outputStream().use { zip.copyTo(it) }
                                        sos += base
                                    }
                                }
                            }
                        }
                    }
                    require(sos.isNotEmpty()) { "No .so file in the zip." }
                    val wanted = meta?.optString("libraryName").orEmpty()
                    libName = if (wanted.isNotEmpty() && wanted in sos) wanted else sos.first()
                } else {
                    libName = if (displayName.endsWith(".so")) displayName else "libvulkan_custom.so"
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
                )
                save(d)
                d
            } catch (e: Exception) {
                dir.deleteRecursively()
                throw e
            }
        }
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
                else -> text.lines().filter { it.isNotBlank() }.takeLast(6).joinToString("\n")
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
        fun toJson(d: DriverInfo): JSONObject = JSONObject().apply {
            put("id", d.id); put("name", d.name); put("description", d.description)
            put("author", d.author); put("version", d.version); put("vendor", d.vendor)
            put("libName", d.libName); put("importedAt", d.importedAt); put("env", d.env)
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
