package com.example.system

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Immutable
enum class ThermalSeverity {
    NOMINAL,
    LIGHT,
    MODERATE,
    SEVERE,
    CRITICAL
}

@Immutable
data class SystemHealthState(
    val thermalSeverity: ThermalSeverity = ThermalSeverity.NOMINAL,
    val batteryTempC: Float = 30.0f,
    val batteryPercent: Int = 100,
    val isLowBattery: Boolean = false,
    val availableRamMb: Long = 3000L,
    val totalRamMb: Long = 6000L,
    val isLowMemory: Boolean = false,
    val recommendedMaxBurstFrames: Int = 6,
    val recommendedNightFrames: Int = 8,
    val recommendedHdrFrames: Int = 3,
    val allowSuperResolution: Boolean = true,
    val previewAnalysisIntervalMs: Long = 120L,
    val isFullPowerMode: Boolean = false
)

class ThermalMonitor(private val context: Context) {

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager

    private val _healthState = MutableStateFlow(SystemHealthState())
    val healthState: StateFlow<SystemHealthState> = _healthState.asStateFlow()

    private var monitorJob: Job? = null

    fun startMonitoring(
        scope: CoroutineScope,
        thermalProtectionEnabled: Boolean = true,
        focusPowerMode: Boolean = false
    ) {
        monitorJob?.cancel()
        monitorJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                _healthState.value = sampleHealth(thermalProtectionEnabled, focusPowerMode)
                delay(2500L)
            }
        }
    }

    fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
    }

    fun sampleNow(
        thermalProtectionEnabled: Boolean = true,
        focusPowerMode: Boolean = false
    ): SystemHealthState {
        val sampled = sampleHealth(thermalProtectionEnabled, focusPowerMode)
        _healthState.value = sampled
        return sampled
    }

    private fun sampleHealth(
        thermalProtectionEnabled: Boolean,
        focusPowerMode: Boolean
    ): SystemHealthState {
        val batteryIntent = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val tempTenths = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 300) ?: 300
        val batteryTempC = tempTenths / 10f
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 80) ?: 80
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100)?.coerceAtLeast(1) ?: 100
        val batteryPct = (level * 100) / scale
        val isLowBattery = batteryPct <= 15

        val osThermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null) {
            when (powerManager.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> ThermalSeverity.NOMINAL
                PowerManager.THERMAL_STATUS_LIGHT -> ThermalSeverity.LIGHT
                PowerManager.THERMAL_STATUS_MODERATE -> ThermalSeverity.MODERATE
                PowerManager.THERMAL_STATUS_SEVERE -> ThermalSeverity.SEVERE
                PowerManager.THERMAL_STATUS_CRITICAL,
                PowerManager.THERMAL_STATUS_EMERGENCY,
                PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalSeverity.CRITICAL
                else -> ThermalSeverity.NOMINAL
            }
        } else {
            when {
                batteryTempC >= 46f -> ThermalSeverity.CRITICAL
                batteryTempC >= 43f -> ThermalSeverity.SEVERE
                batteryTempC >= 40f -> ThermalSeverity.MODERATE
                batteryTempC >= 37f -> ThermalSeverity.LIGHT
                else -> ThermalSeverity.NOMINAL
            }
        }

        val severity = when {
            batteryTempC >= 46f -> ThermalSeverity.CRITICAL
            batteryTempC >= 43f && osThermal.ordinal < ThermalSeverity.SEVERE.ordinal -> ThermalSeverity.SEVERE
            batteryTempC >= 40f && osThermal.ordinal < ThermalSeverity.MODERATE.ordinal -> ThermalSeverity.MODERATE
            else -> osThermal
        }

        val memInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memInfo)
        val availMb = memInfo.availMem / (1024L * 1024L)
        val totalMb = memInfo.totalMem / (1024L * 1024L)
        val lowMem = memInfo.lowMemory || availMb < 650L
        val criticalMem = availMb < 420L

        val (burst, night, hdr, superRes, analysisMs) = when {
            // Focus Turbo Mode allocates 100% processing power (only protects against imminent OOM)
            focusPowerMode -> listOf(8, 10, 5, !criticalMem, 55L)
            !thermalProtectionEnabled -> listOf(6, 8, 3, !lowMem, 100L)
            else -> when (severity) {
                ThermalSeverity.NOMINAL, ThermalSeverity.LIGHT -> {
                    if (lowMem || isLowBattery) listOf(3, 4, 3, false, 180L)
                    else listOf(6, 8, 3, true, 100L)
                }
                ThermalSeverity.MODERATE -> listOf(3, 5, 3, false, 200L)
                ThermalSeverity.SEVERE -> listOf(2, 3, 2, false, 320L)
                ThermalSeverity.CRITICAL -> listOf(1, 2, 2, false, 450L)
            }
        }

        return SystemHealthState(
            thermalSeverity = severity,
            batteryTempC = batteryTempC,
            batteryPercent = batteryPct,
            isLowBattery = isLowBattery,
            availableRamMb = availMb,
            totalRamMb = totalMb,
            isLowMemory = lowMem,
            recommendedMaxBurstFrames = burst as Int,
            recommendedNightFrames = night as Int,
            recommendedHdrFrames = hdr as Int,
            allowSuperResolution = superRes as Boolean,
            previewAnalysisIntervalMs = analysisMs as Long,
            isFullPowerMode = focusPowerMode
        )
    }
}
