package com.rotalab.emotibit.protocol

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.DatagramSocket
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * Drives the host against tools/emotibit_sim/emotibit_sim.py, an independent re-implementation of
 * the firmware behaviour, and checks the produced "SD file" with tools/emotibit_sim/check_sd_file.py.
 */
class HostSimulatorIntegrationTest {
    private val repoRoot = File(System.getProperty("rotalab.repoRoot") ?: "../..")
    private val simScript = File(repoRoot, "tools/emotibit_sim/emotibit_sim.py")
    private val checkScript = File(repoRoot, "tools/emotibit_sim/check_sd_file.py")
    private lateinit var sdDir: File
    private var sim: Process? = null
    private var host: EmotiBitHost? = null
    private val events = Collections.synchronizedList(mutableListOf<HostEvent>())

    private fun python(): String? = listOf("python3", "python").firstOrNull { cmd ->
        try { ProcessBuilder(cmd, "--version").start().waitFor(5, TimeUnit.SECONDS) } catch (e: Exception) { false }
    }

    private fun freePort(): Int = DatagramSocket(0).use { it.localPort }

    @Before
    fun setUp() {
        assumeTrue("python3 needed for simulator test", python() != null && simScript.exists())
        sdDir = Files.createTempDirectory("emotibit-sd").toFile()
    }

    @After
    fun tearDown() {
        host?.stop()
        sim?.destroy()
        sim?.waitFor(3, TimeUnit.SECONDS)
    }

    private fun startSim(advPort: Int, vararg extra: String) {
        val cmd = mutableListOf(
            python()!!, "-I", simScript.absolutePath, "--bind", "127.0.0.1", "--adv-port", advPort.toString(),
            "--device-id", "MD-V5-TEST", "--sd-dir", sdDir.absolutePath, "--sync-interval", "0.4",
            "--connection-timeout", "1.5", "--duration", "120",
        )
        cmd += extra
        sim = ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(File(sdDir, "sim.log")).start()
        Thread.sleep(400)
    }

    private fun startHost(advPort: Int): EmotiBitHost {
        val h = EmotiBitHost(
            HostConfig(
                advertisingPort = advPort,
                firstDataPort = 40000 + (advPort % 10000),
                advertisingIntervalMs = 200,
                unicastSweepEnabled = false,
                connectionTimeoutMs = 1500,
                availabilityTimeoutMs = 1500,
                safeStopMinSyncs = 3,
                safeStopTimeoutMs = 20_000,
                staticTargets = listOf("127.0.0.1"),
                networkProvider = { emptyList() },
            ),
        )
        host = h
        Thread { runBlocking { h.events.collect { events += it } } }.apply { isDaemon = true; start() }
        h.start()
        return h
    }

    private fun waitFor(h: EmotiBitHost, timeoutMs: Long, what: String, cond: (HostState) -> Boolean): HostState = runBlocking {
        try {
            withTimeout(timeoutMs) { h.state.first(cond) }
        } catch (e: Exception) {
            throw AssertionError("Timed out waiting for $what; state=${h.state.value}; events=${events.map { it.type }}; sim=${File(sdDir, "sim.log").readText().takeLast(2000)}")
        }
    }

    private fun awaitEvents(type: String, count: Int, timeoutMs: Long = 5000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (synchronized(events) { events.count { it.type == type } } >= count) return
            Thread.sleep(50)
        }
        throw AssertionError("expected $count '$type' events, got ${synchronized(events) { events.map { it.type } }}")
    }

    private fun checkFile(file: File): Pair<Int, String> {
        val p = ProcessBuilder(python()!!, "-I", checkScript.absolutePath, file.absolutePath, "--edge-seconds", "3", "--json")
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(10, TimeUnit.SECONDS)
        return p.exitValue() to out
    }

    @Test
    fun fieldScenarioWithOutageAndSafeStop() {
        val adv = freePort()
        // Leave Wi-Fi range from 6 s to 11 s after simulator start (the follower falls behind).
        startSim(adv, "--offline", "6:11")
        val h = startHost(adv)

        waitFor(h, 5000, "device discovery") { st -> st.devices.any { it.id == "MD-V5-TEST" && it.available } }
        h.connect("MD-V5-TEST")
        waitFor(h, 5000, "connection + control channel + mode") { it.link == LinkState.CONNECTED && it.controlChannelReady && it.recording == RecordingStatus.NOT_RECORDING }

        assertEquals(EmotiBitHost.CommandResult.SENT, h.startRecording())
        val rec = waitFor(h, 5000, "recording confirmed") { it.recording == RecordingStatus.RECORDING && it.recordingFile != null }
        val file = File(sdDir, rec.recordingFile!!)
        waitFor(h, 5000, "start syncs") { it.syncsInRecording >= 2 }
        // Live plot data: EDA at ~15 Hz and PPG at ~25 Hz on one EmotiBit time axis.
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            val first = h.signals.snapshot("EA", 0.0)?.times?.firstOrNull() ?: Double.NaN
            if (h.signals.latestTimeMs - first >= 2500) break
            Thread.sleep(100)
        }
        val now = h.signals.latestTimeMs
        val eda = h.signals.snapshot("EA", now - 2000)!!
        val ppg = h.signals.snapshot("PG", now - 2000)!!
        assertTrue("EDA samples in 2 s: ${eda.size}", eda.size in 24..36)
        assertTrue("PPG samples in 2 s: ${ppg.size}", ppg.size in 42..58)
        assertTrue(h.signals.hasData("HR") || h.signals.hasData("T1"))
        assertNotNull(h.sendSyncMarker())
        assertEquals(EmotiBitHost.CommandResult.SENT, h.sendNote("start, after marker"))

        waitFor(h, 10_000, "connection lost during outage") { it.link != LinkState.CONNECTED }
        // Safe stop requested while out of range must wait for reconnection and fresh syncs.
        h.requestSafeStop()
        Thread.sleep(500)
        assertEquals(RecordingStatus.RECORDING, h.state.value.recording)

        val done = waitFor(h, 20_000, "auto-reconnect and safe stop") { it.recording == RecordingStatus.NOT_RECORDING }
        assertTrue("syncs collected in final connection", done.syncsThisConnection >= 3)
        // Events are emitted right after the state update, so give the collector a moment.
        awaitEvents("connection_lost", 1)
        awaitEvents("connected", 2)
        awaitEvents("record_stopped", 1)

        val (code, json) = checkFile(file)
        assertEquals("check_sd_file exit code (0 = syncs at start and end): $json", 0, code)
        assertTrue(json.contains("SYNC_MARKER_1"))
        assertTrue(json.contains("start; after marker"))
    }

    @Test
    fun recordingUnknownBlocksStartAndWifiOffKeepsRecording() {
        val adv = freePort()
        startSim(adv, "--wifi-off-seconds", "2")
        val h = startHost(adv)
        waitFor(h, 5000, "discovery") { st -> st.devices.any { it.available } }
        h.connect("MD-V5-TEST")
        waitFor(h, 5000, "ready") { it.link == LinkState.CONNECTED && it.controlChannelReady && it.recording == RecordingStatus.NOT_RECORDING }
        assertEquals(EmotiBitHost.CommandResult.SENT, h.startRecording())
        assertEquals(EmotiBitHost.CommandResult.BUSY, h.startRecording())
        val rec = waitFor(h, 5000, "recording") { it.recording == RecordingStatus.RECORDING }
        assertEquals(EmotiBitHost.CommandResult.ALREADY_RECORDING, h.startRecording())
        waitFor(h, 5000, "syncs") { it.syncsInRecording >= 2 }

        assertEquals(EmotiBitHost.CommandResult.SENT, h.setPowerMode(TypeTag.MODE_WIRELESS_OFF))
        waitFor(h, 5000, "link down after Wi-Fi off") { it.link != LinkState.CONNECTED }
        // Simulator turns Wi-Fi back on after 2 s (button press); host reconnects on its own.
        waitFor(h, 10_000, "reconnected, still recording same file") {
            it.link == LinkState.CONNECTED && it.recording == RecordingStatus.RECORDING && it.recordingFile == rec.recordingFile
        }
        h.requestSafeStop()
        waitFor(h, 15_000, "stopped") { it.recording == RecordingStatus.NOT_RECORDING }
        val (code, json) = checkFile(File(sdDir, rec.recordingFile!!))
        assertEquals(json, 0, code)
    }
}
