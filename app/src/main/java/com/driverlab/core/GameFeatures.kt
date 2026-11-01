package com.driverlab.core

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * "Game features": what the translation layers that run Windows games need from a Vulkan driver,
 * checked against what a driver reports, plus focused CTS test sets for each.
 *
 * Requirements are taken from the projects' own code, not from memory:
 *  - DXVK master f8637768: src/dxvk/dxvk_device_info.cpp (features and extensions marked required),
 *    src/dxvk/dxvk_instance.h (DxvkVulkanApiVersion = Vulkan 1.3).
 *  - VKD3D-Proton master 8293130e: README "Drivers" and libs/vkd3d/device.c (the checks that refuse
 *    to create a device; the Shader Model 6.6 rule; ray tracing tier and mesh shader tier rules).
 * Some requirements are device limits or properties DriverLab doesn't read; the notes say so.
 */
object GameFeatures {
    enum class Kind { API, EXT, FEATURE }

    /** One requirement: any of [names] satisfies it. */
    data class Req(val kind: Kind, val names: List<String>, val note: String = "", val optional: Boolean = false) {
        val label: String get() = when (kind) {
            Kind.API -> "Vulkan ${names.first()}"
            else -> names.joinToString(" or ")
        }
    }

    /** CTS tests under [group]; with [contains], only tests whose last name part contains it. */
    data class TestSet(val group: String, val contains: String? = null)

    data class Profile(
        val id: String,
        val title: String,
        val summary: String,
        val source: String,
        val reqs: List<Req>,
        val tests: List<TestSet>,
    )

    private fun f(vararg n: String, note: String = "", optional: Boolean = false) = n.map { Req(Kind.FEATURE, listOf(it), note, optional) }
    private fun ext(vararg anyOf: String, note: String = "", optional: Boolean = false) = Req(Kind.EXT, anyOf.toList(), note, optional)
    private fun api(v: String) = Req(Kind.API, listOf(v))

    val PROFILES: List<Profile> = listOf(
        Profile(
            "dxvk", "Direct3D 9–11 games (DXVK)",
            "DXVK turns Direct3D 9, 10 and 11 into Vulkan. It refuses to start without these.",
            "DXVK master f8637768: dxvk_device_info.cpp (required), dxvk_instance.h (Vulkan 1.3)",
            listOf(api("1.3")) +
                f(
                    "depthBiasClamp", "depthClamp", "dualSrcBlend", "fillModeNonSolid", "fragmentStoresAndAtomics",
                    "fullDrawIndexUint32", "geometryShader", "imageCubeArray", "independentBlend", "multiDrawIndirect",
                    "multiViewport", "occlusionQueryPrecise", "robustBufferAccess", "sampleRateShading", "samplerAnisotropy",
                    "shaderClipDistance", "shaderCullDistance", "shaderImageGatherExtended", "shaderInt16", "shaderInt64",
                    "shaderSampledImageArrayDynamicIndexing",
                ) +
                f("textureCompressionBC", note = "BC (DXT) textures") +
                f(
                    "shaderDrawParameters", "storageBuffer16BitAccess", "bufferDeviceAddress", "descriptorIndexing",
                    "storageBuffer8BitAccess", "descriptorBindingSampledImageUpdateAfterBind",
                    "descriptorBindingUpdateUnusedWhilePending", "descriptorBindingPartiallyBound", "hostQueryReset",
                    "runtimeDescriptorArray", "samplerMirrorClampToEdge", "scalarBlockLayout", "shaderInt8",
                    "timelineSemaphore", "uniformBufferStandardLayout", "vulkanMemoryModel", "inlineUniformBlock",
                    "computeFullSubgroups", "dynamicRendering", "maintenance4", "shaderDemoteToHelperInvocation",
                    "shaderZeroInitializeWorkgroupMemory", "subgroupSizeControl", "synchronization2",
                    "depthClipEnable", "robustBufferAccess2", "nullDescriptor", "maintenance5", "maintenance6",
                ) +
                listOf(
                    ext("VK_EXT_depth_clip_enable"),
                    ext("VK_EXT_robustness2", "VK_KHR_robustness2"),
                    ext("VK_KHR_load_store_op_none", "VK_EXT_load_store_op_none"),
                    ext("VK_KHR_maintenance5"),
                    ext("VK_KHR_maintenance6"),
                ),
            listOf(
                TestSet("dEQP-VK.api.smoke"),
                TestSet("dEQP-VK.texture.compressed", "bc"),
                TestSet("dEQP-VK.robustness.robustness2.misc"),
                TestSet("dEQP-VK.synchronization2.smoke"),
            ),
        ),
        Profile(
            "vkd3d", "Direct3D 12 games (VKD3D-Proton)",
            "VKD3D-Proton turns Direct3D 12 into Vulkan. It refuses to create a device without the required items; " +
                "the optional ones it recommends for correct or fast behaviour.",
            "VKD3D-Proton master 8293130e: README \"Drivers\", libs/vkd3d/device.c",
            listOf(api("1.3")) +
                f(
                    "runtimeDescriptorArray", "descriptorBindingPartiallyBound", "descriptorBindingUpdateUnusedWhilePending",
                    "descriptorBindingVariableDescriptorCount", "descriptorBindingSampledImageUpdateAfterBind",
                    "descriptorBindingStorageImageUpdateAfterBind", "descriptorBindingStorageBufferUpdateAfterBind",
                    "descriptorBindingUniformTexelBufferUpdateAfterBind", "descriptorBindingStorageTexelBufferUpdateAfterBind",
                    "shaderSampledImageArrayNonUniformIndexing", "shaderStorageBufferArrayNonUniformIndexing",
                    "shaderStorageImageArrayNonUniformIndexing", "shaderUniformTexelBufferArrayNonUniformIndexing",
                    "shaderStorageTexelBufferArrayNonUniformIndexing", "shaderUniformTexelBufferArrayDynamicIndexing",
                    "shaderStorageTexelBufferArrayDynamicIndexing",
                    note = "Descriptor indexing: the README asks for essentially all of it (1,000,000 update-after-bind descriptors per type except uniform buffers; that limit isn't checked here)",
                ) +
                f(
                    "samplerMirrorClampToEdge", "shaderDrawParameters", "robustBufferAccess2", "robustImageAccess2", "nullDescriptor",
                    "maintenance5", "maintenance6", "vertexAttributeInstanceRateDivisor", "vertexAttributeInstanceRateZeroDivisor",
                ) +
                listOf(
                    ext("VK_KHR_push_descriptor"),
                    ext("VK_EXT_robustness2", "VK_KHR_robustness2"),
                    ext("VK_EXT_vertex_attribute_divisor", "VK_KHR_vertex_attribute_divisor"),
                    ext("VK_EXT_transform_feedback", note = "Transform feedback queries are required (that property isn't checked here)"),
                    ext("VK_EXT_image_view_min_lod", optional = true),
                    ext("VK_EXT_mutable_descriptor_type", "VK_VALVE_mutable_descriptor_type", optional = true),
                    ext("VK_EXT_descriptor_buffer", optional = true),
                ),
            listOf(
                TestSet("dEQP-VK.descriptor_indexing"),
                TestSet("dEQP-VK.pipeline.monolithic.push_descriptor"),
                TestSet("dEQP-VK.binding_model.mutable_descriptor.misc"),
                TestSet("dEQP-VK.robustness.robustness2.misc"),
            ),
        ),
        Profile(
            "sm66", "Shader Model 6.6 (Unreal Engine 5 games)",
            "VKD3D-Proton offers Shader Model 6.6 only with these; its code notes that Unreal Engine 5 relies on SM 6.6. " +
                "Needs the Direct3D 12 items too. The optional items are D3D12's 64-bit atomics on textures and on shared memory.",
            "VKD3D-Proton master 8293130e: libs/vkd3d/device.c (SM 6.6 rule, AtomicInt64 options)",
            f("computeDerivativeGroupLinear", "shaderBufferInt64Atomics", "shaderInt8", "subgroupSizeControl", "computeFullSubgroups") +
                listOf(
                    ext("VK_KHR_compute_shader_derivatives", "VK_NV_compute_shader_derivatives"),
                    Req(Kind.FEATURE, listOf("shaderImageInt64Atomics"), "64-bit atomics on textures (D3D12 AtomicInt64OnTypedResource)", optional = true),
                    Req(Kind.FEATURE, listOf("shaderSharedInt64Atomics"), "64-bit atomics on shared memory (D3D12 AtomicInt64OnGroupShared)", optional = true),
                ),
            listOf(
                TestSet("dEQP-VK.glsl.atomic_operations", "64bit"),
                TestSet("dEQP-VK.subgroups.size_control"),
                TestSet("dEQP-VK.spirv_assembly.instruction.compute.compute_shader_derivatives.compute"),
                TestSet("dEQP-VK.image.atomic_operations", "r64"),
            ),
        ),
        Profile(
            "dxr", "Hardware ray tracing (DXR)",
            "What VKD3D-Proton needs for Direct3D 12 ray tracing: tier 1.0, and tier 1.1 (inline ray queries) with the " +
                "optional items. Games' hardware ray tracing modes need this (for example UE5's hardware Lumen).",
            "VKD3D-Proton master 8293130e: libs/vkd3d/device.c (ray tracing tier); Epic's Lumen article",
            f("rayTracingPipeline", "accelerationStructure") +
                listOf(
                    ext("VK_KHR_ray_tracing_pipeline"),
                    ext("VK_KHR_acceleration_structure"),
                    Req(Kind.FEATURE, listOf("rayQuery"), "For DXR 1.1", optional = true),
                    Req(Kind.FEATURE, listOf("rayTraversalPrimitiveCulling"), "For DXR 1.1", optional = true),
                    ext("VK_KHR_ray_query", note = "For DXR 1.1", optional = true),
                ),
            listOf(
                TestSet("dEQP-VK.ray_query.builtin"),
                TestSet("dEQP-VK.ray_tracing_pipeline.builtin"),
            ),
        ),
        Profile(
            "mesh", "Mesh shaders (Direct3D 12 Ultimate)",
            "VKD3D-Proton reports Direct3D 12 mesh shader tier 1 when both of these are there.",
            "VKD3D-Proton master 8293130e: libs/vkd3d/device.c (mesh shader tier)",
            f("meshShader", "taskShader") + listOf(ext("VK_EXT_mesh_shader")),
            listOf(
                TestSet("dEQP-VK.mesh_shader.ext.smoke"),
                TestSet("dEQP-VK.mesh_shader.ext.builtin"),
            ),
        ),
    )

    /** What one driver reports (from one CTS run of the info tests). */
    data class Report(val checkedAt: Long, val api: String, val extensions: Set<String>, val features: Map<String, Int>)

    enum class Status { YES, NO, UNKNOWN }

    fun status(r: Req, rep: Report): Status = when (r.kind) {
        Kind.API -> {
            val want = r.names.first().split('.').map { it.toIntOrNull() ?: 0 }
            val have = rep.api.split('.').map { it.toIntOrNull() ?: 0 }
            if (rep.api.isBlank()) Status.UNKNOWN
            else if (compareValuesBy(have, want, { it.getOrElse(0) { 0 } }, { it.getOrElse(1) { 0 } }) >= 0) Status.YES else Status.NO
        }
        Kind.EXT -> if (r.names.any { it in rep.extensions }) Status.YES else Status.NO
        Kind.FEATURE -> {
            val v = r.names.mapNotNull { rep.features[it] }
            when {
                v.any { it != 0 } -> Status.YES
                v.isNotEmpty() -> Status.NO
                else -> Status.UNKNOWN
            }
        }
    }

    data class Verdict(val missing: Int, val unknown: Int, val optionalMissing: Int)

    fun verdict(p: Profile, rep: Report): Verdict {
        var miss = 0; var unk = 0; var opt = 0
        for (r in p.reqs) when (status(r, rep)) {
            Status.NO -> if (r.optional) opt++ else miss++
            Status.UNKNOWN -> if (r.optional) opt++ else unk++
            Status.YES -> {}
        }
        return Verdict(miss, unk, opt)
    }

    private val EXT_LINE = Regex("^\\s*extensionName = (\\S+)")
    private val FIELD_LINE = Regex("^\\s*([A-Za-z][A-Za-z0-9_]*) = (\\d+)\\s*$")

    /** Reads extensions and feature fields from a CTS .qpa of the info / features tests. */
    fun parseQpa(qpa: File, api: String): Report {
        val exts = HashSet<String>()
        val feats = HashMap<String, Int>()
        if (qpa.exists()) qpa.bufferedReader().useLines { seq ->
            for (l in seq) {
                EXT_LINE.find(l)?.let { exts += it.groupValues[1]; return@let }
                FIELD_LINE.find(l)?.let { m ->
                    val k = m.groupValues[1]
                    val v = m.groupValues[2].toIntOrNull() ?: return@let
                    feats[k] = maxOf(feats[k] ?: 0, v)
                }
            }
        }
        return Report(System.currentTimeMillis(), api, exts, feats)
    }

    fun toJson(r: Report): JSONObject = JSONObject().apply {
        put("checkedAt", r.checkedAt); put("api", r.api)
        put("extensions", JSONArray(r.extensions.sorted()))
        put("features", JSONObject().apply { r.features.toSortedMap().forEach { (k, v) -> put(k, v) } })
    }

    fun fromJson(o: JSONObject): Report {
        val ex = o.optJSONArray("extensions") ?: JSONArray()
        val fe = o.optJSONObject("features") ?: JSONObject()
        return Report(
            o.optLong("checkedAt"), o.optString("api"),
            (0 until ex.length()).map { ex.getString(it) }.toSet(),
            fe.keys().asSequence().associateWith { fe.optInt(it) },
        )
    }
}

/** Runs the info tests on a driver and keeps its [GameFeatures.Report] in files/features/<driver>.json. */
class FeatureChecker(
    private val context: Context,
    private val drivers: DriverRepository,
    private val catalog: Catalog,
    private val scope: CoroutineScope,
) {
    private val dir get() = File(context.filesDir, "features").apply { mkdirs() }
    private val _reports = MutableStateFlow<Map<String, GameFeatures.Report>>(emptyMap())
    val reports: StateFlow<Map<String, GameFeatures.Report>> = _reports.asStateFlow()
    private val _checking = MutableStateFlow<Set<String>>(emptySet())
    val checking: StateFlow<Set<String>> = _checking.asStateFlow()
    private val _errors = MutableStateFlow<Map<String, String>>(emptyMap())
    val errors: StateFlow<Map<String, String>> = _errors.asStateFlow()

    fun load() {
        val m = HashMap<String, GameFeatures.Report>()
        dir.listFiles()?.filter { it.name.endsWith(".json") }?.forEach { f ->
            runCatching { m[f.name.removeSuffix(".json")] = GameFeatures.fromJson(JSONObject(f.readText())) }
        }
        _reports.value = m
    }

    fun check(driverId: String) {
        if (driverId in _checking.value) return
        val d = drivers.get(driverId) ?: return
        _checking.value = _checking.value + driverId
        _errors.value = _errors.value - driverId
        scope.launch(Dispatchers.IO) {
            try {
                val work = File(Paths.work(context), "features-$driverId").apply { deleteRecursively(); mkdirs() }
                val names = listOf("dEQP-VK.info.device_extensions", "dEQP-VK.info.device_features") +
                    catalog.leavesUnder("dEQP-VK.api.info.get_physical_device_properties2.features")
                val caselist = File(work, "caselist.txt").apply { writeText(names.joinToString("\n", postfix = "\n")) }
                val qpa = File(work, "features.qpa")
                val p = CtsRunner(context).start(CtsRunner.Launch(caselist, qpa, d, drivers.libFile(d), work))
                val tail = ArrayDeque<String>()
                p.inputStream.bufferedReader().useLines { seq ->
                    for (l in seq) { if (l.isNotBlank()) { tail.addLast(l); if (tail.size > 6) tail.removeFirst() } }
                }
                p.waitFor()
                val rep = GameFeatures.parseQpa(qpa, d.probe?.apiVersion.orEmpty())
                if (rep.extensions.isEmpty() && rep.features.isEmpty()) {
                    _errors.value = _errors.value + (driverId to ("The driver reported nothing. " + tail.takeLast(3).joinToString(" | ")))
                } else {
                    File(dir, "$driverId.json").writeText(GameFeatures.toJson(rep).toString())
                    _reports.value = _reports.value + (driverId to rep)
                }
                work.deleteRecursively()
            } catch (e: Exception) {
                _errors.value = _errors.value + (driverId to (e.message ?: "error"))
            } finally {
                _checking.value = _checking.value - driverId
            }
        }
    }
}
