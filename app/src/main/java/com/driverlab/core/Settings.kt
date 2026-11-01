package com.driverlab.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    val theme: Int = 0, // 0 system, 1 light, 2 dark
    val dynamicColor: Boolean = false,
    val timeoutSec: Int = 120,
    val captureLevel: Int = 1, // 0 light 1 MB, 1 standard 4 MB, 2 full
    val batchSize: Int = 250,
    val keepScreenOn: Boolean = true,
    val record: Boolean = false,
    val exportToDownloads: Boolean = true,
    val perTestLogLimit: Int = 5000,
    /** Full libwrap-style kernel log in each test's output when recording the system driver. */
    val rawKgslLog: Boolean = true,
    val driverId: String = DriverInfo.SYSTEM_ID,
) {
    val captureCapBytes: Long
        get() = when (captureLevel) {
            0 -> 1L shl 20
            1 -> 4L shl 20
            else -> 0L
        }

    val captureLabel: String
        get() = when (captureLevel) {
            0 -> "Light (buffers up to 1 MB)"
            1 -> "Standard (buffers up to 4 MB)"
            else -> "Full (every buffer)"
        }
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state.asStateFlow()
    val value: AppSettings get() = _state.value

    private fun load() = AppSettings(
        theme = prefs.getInt("theme", 0),
        dynamicColor = prefs.getBoolean("dynamicColor", false),
        timeoutSec = prefs.getInt("timeoutSec", 120),
        captureLevel = prefs.getInt("captureLevel", 1),
        batchSize = prefs.getInt("batchSize", 250),
        keepScreenOn = prefs.getBoolean("keepScreenOn", true),
        record = prefs.getBoolean("record", false),
        exportToDownloads = prefs.getBoolean("exportToDownloads", true),
        perTestLogLimit = prefs.getInt("perTestLogLimit", 5000),
        rawKgslLog = prefs.getBoolean("rawKgslLog", true),
        driverId = prefs.getString("driverId", DriverInfo.SYSTEM_ID) ?: DriverInfo.SYSTEM_ID,
    )

    fun update(block: (AppSettings) -> AppSettings) {
        val s = block(_state.value)
        prefs.edit()
            .putInt("theme", s.theme)
            .putBoolean("dynamicColor", s.dynamicColor)
            .putInt("timeoutSec", s.timeoutSec)
            .putInt("captureLevel", s.captureLevel)
            .putInt("batchSize", s.batchSize)
            .putBoolean("keepScreenOn", s.keepScreenOn)
            .putBoolean("record", s.record)
            .putBoolean("exportToDownloads", s.exportToDownloads)
            .putInt("perTestLogLimit", s.perTestLogLimit)
            .putBoolean("rawKgslLog", s.rawKgslLog)
            .putString("driverId", s.driverId)
            .apply()
        _state.value = s
    }
}
