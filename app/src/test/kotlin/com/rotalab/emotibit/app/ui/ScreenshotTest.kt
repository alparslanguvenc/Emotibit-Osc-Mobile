package com.rotalab.emotibit.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.rotalab.emotibit.app.MainActions
import com.rotalab.emotibit.protocol.DeviceInfo
import com.rotalab.emotibit.protocol.EmotiBitHost
import com.rotalab.emotibit.protocol.EmotiBitTime
import com.rotalab.emotibit.protocol.HostConfig
import com.rotalab.emotibit.protocol.HostEvent
import com.rotalab.emotibit.protocol.HostState
import com.rotalab.emotibit.protocol.LinkState
import com.rotalab.emotibit.protocol.RecordingStatus
import com.rotalab.emotibit.protocol.SafeStopState
import com.rotalab.emotibit.protocol.SignalBuffers
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File
import java.net.DatagramSocket
import java.time.Duration
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

/**
 * Renders the real app UI (Robolectric native graphics) for the README.
 * Live-signal screenshots use data streamed by tools/emotibit_sim/emotibit_sim.py through the real protocol host.
 * Run: ./gradlew :app:testDebugUnitTest --tests '*ScreenshotTest*' -Pscreenshots
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {

    companion object {
        private val outDir = File(System.getProperty("rotalab.screenshotDir") ?: "build/screenshots")
        private var sim: Process? = null
        private var host: EmotiBitHost? = null

        /** Starts the simulator and a real host connected to it, so live plots show streamed data. */
        @BeforeClass @JvmStatic fun startSimulator() {
            val repo = File(System.getProperty("rotalab.repoRoot") ?: "../..")
            val script = listOf(File(repo, "tools/emotibit_sim/emotibit_sim.py")).firstOrNull { it.exists() } ?: return
            val adv = DatagramSocket(0).use { it.localPort }
            sim = ProcessBuilder("python3", "-I", script.absolutePath, "--bind", "127.0.0.1", "--adv-port", "$adv",
                "--device-id", "MD-V5-0000313", "--sd-dir", File(System.getProperty("java.io.tmpdir"), "shot-sd").absolutePath, "--duration", "120")
                .redirectErrorStream(true).redirectOutput(File(System.getProperty("java.io.tmpdir"), "shot-sim.log")).start()
            Thread.sleep(500)
            val h = EmotiBitHost(HostConfig(advertisingPort = adv, firstDataPort = 41000 + adv % 10000, advertisingIntervalMs = 200,
                unicastSweepEnabled = false, staticTargets = listOf("127.0.0.1"), networkProvider = { emptyList() }))
            h.start()
            h.connect("MD-V5-0000313")
            host = h
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline && h.state.value.link != LinkState.CONNECTED) Thread.sleep(50)
        }

        @AfterClass @JvmStatic fun stop() {
            host?.stop()
            sim?.destroy(); sim?.waitFor(3, TimeUnit.SECONDS)
        }
    }

    private val actions = object : MainActions {
        override fun setLanguage(tag: String?) {}
        override fun currentLanguage(): String? = null
        override fun quit() {}
        override fun shareLog() {}
        override fun isIgnoringBatteryOptimizations() = true
        override fun requestIgnoreBatteryOptimizations() {}
        override fun versionName() = "1.0.0"
    }

    private val device = DeviceInfo("MD-V5-0000313", "10.42.0.112", available = false, lastSeenMs = 0)
    private val startedAt: String = EmotiBitTime.format(LocalDateTime.now().minusMinutes(23).minusSeconds(41))

    private val recording = HostState(
        link = LinkState.CONNECTED, dataPort = 3132, controlPort = 3133,
        networks = listOf("ap0 10.42.0.1/24"), devices = listOf(device), targetDeviceId = device.id,
        connectedDeviceId = device.id, connectedIp = device.ip, controlChannelReady = true,
        recording = RecordingStatus.RECORDING, recordingFile = "$startedAt.csv",
        powerMode = "MN", batteryPercent = 78f, dataPacketsPerSecond = 61,
        syncsInRecording = 37, syncsThisConnection = 12, lastSyncRttMs = 8, markerCount = 1, lastDataAtMs = 1,
    )

    private val ready = recording.copy(recording = RecordingStatus.NOT_RECORDING, recordingFile = null, syncsInRecording = 0, syncsThisConnection = 0, markerCount = 0)

    private val outOfRange = recording.copy(
        link = LinkState.IDLE, connectedDeviceId = null, connectedIp = null, controlChannelReady = false,
        syncsThisConnection = 0, dataPacketsPerSecond = 0, recordingStatusAtMs = com.rotalab.emotibit.app.HostController.host.nowMs() - 192_000,
        safeStop = SafeStopState(0, 3),
    )

    private fun ev(minAgo: Long, type: String, vararg f: Pair<String, String>) =
        HostEvent(OffsetDateTime.now().minusMinutes(minAgo).toString(), 0, type, mapOf(*f))

    private val events = listOf(
        ev(2, "connected", "device" to device.id),
        ev(9, "connection_lost", "device" to device.id),
        ev(23, "marker_sent", "label" to "SYNC_MARKER_1"),
        ev(23, "record_started", "file" to "$startedAt.csv"),
        ev(24, "connected", "device" to device.id),
        ev(24, "device_found", "device" to device.id, "ip" to device.ip),
    )

    private fun shot(name: String, state: HostState, tab: Int = 0, qualifiers: String = "", signals: SignalBuffers? = null) {
        if (qualifiers.isNotEmpty()) RuntimeEnvironment.setQualifiers(qualifiers)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalStaticRendering provides true) {
                RotaLabTheme {
                    MainContent(actions, state, events, initialNowMs = 1, initialTab = tab, signals = signals ?: SignalBuffers())
                }
            }
        }
        repeat(30) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50)) }
        val root = activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        outDir.mkdirs()
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun controlRecording() = shot("control-recording", recording)
    @Test fun controlReady() = shot("control-ready", ready)
    @Test fun controlOutOfRange() = shot("control-out-of-range", outOfRange)
    @Test fun controlDark() = shot("control-recording-dark", recording, qualifiers = "+night")
    @Test fun controlTurkish() = shot("control-recording-tr", recording, qualifiers = "+tr")

    @Test fun liveSignals() {
        val h = host
        assumeTrue("simulator not available", h != null && h.state.value.link == LinkState.CONNECTED)
        Thread.sleep(11_000) // fill a 10 s window with streamed data
        shot("live-signals", recording.copy(syncsInRecording = 41), tab = 1, signals = h!!.signals)
        shot("live-signals-dark", recording.copy(syncsInRecording = 41), tab = 1, qualifiers = "+night", signals = h.signals)
    }
}
