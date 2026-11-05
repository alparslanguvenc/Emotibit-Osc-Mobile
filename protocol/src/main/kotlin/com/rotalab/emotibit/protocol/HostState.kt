package com.rotalab.emotibit.protocol

data class HostConfig(
    val advertisingPort: Int = 3131,
    /** First UDP data port tried; the control (TCP) port follows it, as in the Oscilloscope (3132/3133). */
    val firstDataPort: Int = 3132,
    val advertisingIntervalMs: Long = 1000,
    /** Sweep the /24 by unicast when broadcast finds nothing (needed on networks that drop broadcast). */
    val unicastSweepEnabled: Boolean = true,
    val unicastStepMs: Long = 3,
    val pingIntervalMs: Long = 500,
    val connectRetryMs: Long = 100,
    val connectTimeoutMs: Long = 5000,
    /** Same 10 s link timeout EmotiBit itself uses. */
    val connectionTimeoutMs: Long = 10_000,
    val availabilityTimeoutMs: Long = 5000,
    val deviceForgetMs: Long = 60_000,
    val recordConfirmTimeoutMs: Long = 5000,
    /** Verified time syncs to collect during the final connection before RE is sent by a safe stop. */
    val safeStopMinSyncs: Int = 3,
    val safeStopTimeoutMs: Long = 60_000,
    /** Extra IP addresses always probed with HE (manual entry, tests). */
    val staticTargets: List<String> = emptyList(),
    /** Networks to advertise on; null discovers them from the system. */
    val networkProvider: (() -> List<LocalNetwork>)? = null,
)

data class DeviceInfo(
    val id: String,
    val ip: String,
    /** True when EmotiBit's last HH said it has no host (DP == -1) and it answered recently. */
    val available: Boolean,
    val lastSeenMs: Long,
    /** True when the last HH said EmotiBit is connected to some host (possibly an Oscilloscope). */
    val busy: Boolean = false,
)

enum class LinkState { STOPPED, IDLE, CONNECTING, CONNECTED }

enum class RecordingStatus {
    /** No mode packet seen yet from this device. */
    UNKNOWN,
    NOT_RECORDING,
    STARTING,
    RECORDING,
    STOPPING,
}

data class SafeStopState(
    val requestedAtMs: Long,
    val requiredSyncs: Int,
    val timedOut: Boolean = false,
)

data class HostState(
    val link: LinkState = LinkState.STOPPED,
    val dataPort: Int = 0,
    val controlPort: Int = 0,
    val networks: List<String> = emptyList(),
    val devices: List<DeviceInfo> = emptyList(),
    /** Device the host keeps (re)connecting to. */
    val targetDeviceId: String? = null,
    val connectedDeviceId: String? = null,
    val connectedIp: String? = null,
    val connectedSinceMs: Long? = null,
    val controlChannelReady: Boolean = false,
    val recording: RecordingStatus = RecordingStatus.UNKNOWN,
    /** File name reported by EmotiBit (e.g. 2026-11-05_10-15-30-123456.csv). */
    val recordingFile: String? = null,
    /** Monotonic ms when the device last confirmed its recording status. */
    val recordingStatusAtMs: Long? = null,
    val powerMode: String? = null,
    val batteryPercent: Float? = null,
    val lastDataAtMs: Long? = null,
    val dataPacketsPerSecond: Int = 0,
    /** Verified RD→TL→AK syncs that EmotiBit echoed back while recording (so they are in the SD file). */
    val syncsInRecording: Int = 0,
    /** Verified syncs during the current connection while recording. */
    val syncsThisConnection: Int = 0,
    val syncRequestsInRecording: Int = 0,
    val lastSyncRttMs: Long? = null,
    val lastSyncAtMs: Long? = null,
    val safeStop: SafeStopState? = null,
    val markerCount: Int = 0,
    val error: String? = null,
)

/** One entry for the session log. [type] is a stable machine-readable code. */
data class HostEvent(
    val wallTime: String,
    val monoMs: Long,
    val type: String,
    val fields: Map<String, String> = emptyMap(),
    val important: Boolean = true,
)
