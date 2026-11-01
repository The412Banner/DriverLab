package com.driverlab.core

/** One CTS result code, as printed by deqp ("  Pass (...)"). */
enum class TestStatus(val key: String, val label: String, val bucket: Bucket) {
    PASS("Pass", "Pass", Bucket.PASSED),
    FAIL("Fail", "Fail", Bucket.FAILED),
    QUALITY_WARNING("QualityWarning", "Quality warning", Bucket.WARNING),
    COMPATIBILITY_WARNING("CompatibilityWarning", "Compatibility warning", Bucket.WARNING),
    CAPABILITY_WARNING("CapabilityWarning", "Capability warning", Bucket.WARNING),
    NOT_SUPPORTED("NotSupported", "Not supported", Bucket.SKIPPED),
    RESOURCE_ERROR("ResourceError", "Resource error", Bucket.FAILED),
    INTERNAL_ERROR("InternalError", "Internal error", Bucket.FAILED),
    CRASH("Crash", "Crash", Bucket.CRASHED),
    TIMEOUT("Timeout", "Timeout", Bucket.CRASHED),
    WAIVER("Waiver", "Waiver", Bucket.SKIPPED),
    DEVICE_LOST("DeviceLost", "Device lost", Bucket.CRASHED),
    PENDING("Pending", "Pending", Bucket.WARNING),
    ENFORCE_DEFAULT_CONTEXT("EnforceDefaultContext", "Enforce default context", Bucket.WARNING),
    ENFORCE_DEFAULT_INSTANCE("EnforceDefaultInstance", "Enforce default instance", Bucket.WARNING),
    NOT_RUN("NotRun", "Not in this CTS", Bucket.SKIPPED),
    STOPPED("Stopped", "Stopped", Bucket.SKIPPED);

    companion object {
        private val byKey = entries.associateBy { it.key }
        fun fromKey(k: String): TestStatus = byKey[k] ?: INTERNAL_ERROR
        val consoleKeys: Set<String> = entries.filter { it != NOT_RUN && it != STOPPED }.map { it.key }.toSet()
    }
}

/** Five groups used for counters, colours and filters. */
enum class Bucket(val label: String) {
    PASSED("Passed"),
    FAILED("Failed"),
    CRASHED("Crashed"),
    WARNING("Warnings"),
    SKIPPED("Not supported"),
}

data class Counts(
    val passed: Int = 0,
    val failed: Int = 0,
    val crashed: Int = 0,
    val warning: Int = 0,
    val skipped: Int = 0,
) {
    val total: Int get() = passed + failed + crashed + warning + skipped
    operator fun get(b: Bucket): Int = when (b) {
        Bucket.PASSED -> passed
        Bucket.FAILED -> failed
        Bucket.CRASHED -> crashed
        Bucket.WARNING -> warning
        Bucket.SKIPPED -> skipped
    }

    fun plus(b: Bucket): Counts = when (b) {
        Bucket.PASSED -> copy(passed = passed + 1)
        Bucket.FAILED -> copy(failed = failed + 1)
        Bucket.CRASHED -> copy(crashed = crashed + 1)
        Bucket.WARNING -> copy(warning = warning + 1)
        Bucket.SKIPPED -> copy(skipped = skipped + 1)
    }
}

data class TestResult(
    val name: String,
    val status: TestStatus,
    val message: String,
    val durationMs: Long,
    val finishedAt: Long,
    val batch: Int,
    val capture: String?,
)

/** What a Vulkan driver reported in dEQP-VK.info.device_properties. */
data class ProbeInfo(
    val ok: Boolean,
    val deviceName: String,
    val apiVersion: String,
    val driverVersion: String,
    val vendorId: String,
    val deviceId: String,
    val message: String,
    val checkedAt: Long,
)

data class DriverInfo(
    val id: String,
    val name: String,
    val description: String,
    val author: String,
    val version: String,
    val vendor: String,
    val libName: String,
    val importedAt: Long,
    val env: String,
    val probe: ProbeInfo?,
) {
    val isSystem: Boolean get() = id == SYSTEM_ID

    companion object {
        const val SYSTEM_ID = "system"
    }
}

enum class SessionState { RUNNING, FINISHED, STOPPED, FAILED }

data class SessionMeta(
    val id: String,
    val label: String,
    val driverId: String,
    val driverName: String,
    val driverVersion: String,
    val deviceName: String,
    val startedAt: Long,
    val finishedAt: Long,
    val state: SessionState,
    val totalTests: Long,
    val counts: Counts,
    val record: Boolean,
    val captureCapBytes: Long,
    val ctsTag: String,
    val env: String,
    val message: String,
    val exports: List<String>,
)
