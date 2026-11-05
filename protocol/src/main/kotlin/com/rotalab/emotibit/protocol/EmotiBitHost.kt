package com.rotalab.emotibit.protocol

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

/**
 * Host side of the EmotiBit Wi-Fi protocol: the part of EmotiBit Oscilloscope
 * (ofxEmotiBit/EmotiBitOscilloscope/src/EmotiBitWiFiHost.cpp) needed to discover an EmotiBit,
 * connect, answer its time-sync requests and send recording commands.
 *
 * Threads: one for advertising/connection timers, one for the UDP data channel (time-sync replies
 * must reach EmotiBit within its 100 ms wait), one accepting the TCP control connection.
 */
class EmotiBitHost(private val config: HostConfig = HostConfig()) {

    private val lock = Object()

    private val _state = MutableStateFlow(HostState())
    val state: StateFlow<HostState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<HostEvent>(extraBufferCapacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<HostEvent> = _events.asSharedFlow()

    /** Recent sensor samples of the connected device, for live plots. Cleared on each new connection. */
    val signals = SignalBuffers()

    // Sockets
    private var advertisingSocket: DatagramSocket? = null
    private var dataSocket: DatagramSocket? = null
    private var controlServer: ServerSocket? = null
    private var controlSocket: Socket? = null
    private var controlSocketIp: String? = null
    private var controlOut: OutputStream? = null

    @Volatile private var running = false
    private val threads = mutableListOf<Thread>()

    // Counters (uint16 on the wire)
    private var advertisingCounter = 0
    private var dataCounter = 0
    private var controlCounter = 0

    // Mutable state guarded by [lock]
    private var s = HostState()
    private val devices = LinkedHashMap<String, DeviceInfo>()
    private var networks: List<LocalNetwork> = emptyList()
    private var connectingIp: String? = null
    private var connectingId: String? = null
    private var connectingSinceMs = 0L
    private var lastPongMs = 0L
    private var dataReplyAddress: InetSocketAddress? = null
    private var pendingStartAtMs: Long? = null
    private var pendingStopAtMs: Long? = null
    private var recentRequests = ArrayDeque<Pair<Int, Long>>()
    private val syncVerifier = SyncVerifier()
    private var packetsThisSecond = 0
    private var packetRateWindowStartMs = 0L
    private var markerCounter = 0
    private var lastDeviceRecordingFile: String? = null

    // Advertising timers
    private var lastAdvertisingMs = 0L
    private var lastConnectSendMs = 0L
    private var lastPingMs = 0L
    private var sweepNetworkIndex = 0
    private var sweepHost = 2
    private var sweepActive = false
    private var lastNetworkRefreshMs = 0L

    private val startMono = System.nanoTime()
    fun nowMs(): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startMono)

    // ------------------------------------------------------------------ lifecycle

    /** Serialises start() and stop(); stop() joins threads that need [lock], so it cannot be [lock]. */
    private val lifecycleLock = Object()

    fun start(): Unit = synchronized(lifecycleLock) {
        synchronized(lock) {
            if (running) return
            openSockets()
            running = true
            s = s.copy(link = LinkState.IDLE, dataPort = dataSocket!!.localPort, controlPort = controlServer!!.localPort, error = null)
            publish()
        }
        event("host_started", mapOf("dataPort" to s.dataPort.toString(), "controlPort" to s.controlPort.toString()))
        threads += thread("emotibit-adv") { advertisingLoop() }
        threads += thread("emotibit-data") { dataLoop() }
        threads += thread("emotibit-control") { controlAcceptLoop() }
    }

    fun stop(): Unit = synchronized(lifecycleLock) {
        synchronized(lock) {
            if (!running) return
            running = false
            if (s.link == LinkState.CONNECTED) sendControlLocked(Packet.build(TypeTag.EMOTIBIT_DISCONNECT, nextControl(), nowMs()))
        }
        closeQuietly(advertisingSocket); closeQuietly(dataSocket); closeQuietly(controlServer); closeQuietly(controlSocket)
        threads.forEach { it.join(1500) }
        threads.clear()
        synchronized(lock) {
            controlSocket = null; controlOut = null; controlSocketIp = null
            s = HostState()
            devices.clear()
            publish()
        }
        event("host_stopped")
    }

    private fun openSockets() {
        val adv = DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            soTimeout = 20
            bind(InetSocketAddress(0))
        }
        var port = config.firstDataPort
        var data: DatagramSocket? = null
        while (data == null) {
            try {
                data = DatagramSocket(null).apply {
                    reuseAddress = false
                    receiveBufferSize = 1 shl 16
                    soTimeout = 250
                    bind(InetSocketAddress(port))
                }
            } catch (e: SocketException) {
                port += 2
                if (port > config.firstDataPort + 400) throw e
            }
        }
        var cport = data.localPort + 1
        var server: ServerSocket? = null
        while (server == null) {
            try {
                server = ServerSocket().apply {
                    reuseAddress = true
                    soTimeout = 500
                    bind(InetSocketAddress(cport))
                }
            } catch (e: IOException) {
                cport += 2
                if (cport > data.localPort + 401) throw e
            }
        }
        advertisingSocket = adv
        dataSocket = data
        controlServer = server
    }

    // ------------------------------------------------------------------ public commands

    /** Connect to [deviceId] and keep reconnecting to it whenever it reappears. */
    fun connect(deviceId: String) {
        synchronized(lock) {
            s = s.copy(targetDeviceId = deviceId)
            publish()
        }
        event("target_set", mapOf("device" to deviceId))
    }

    /** Stop auto-reconnect and close the link. Recording on the SD card continues. */
    fun disconnect() {
        synchronized(lock) {
            if (s.link == LinkState.CONNECTED) sendControlLocked(Packet.build(TypeTag.EMOTIBIT_DISCONNECT, nextControl(), nowMs()))
            s = s.copy(targetDeviceId = null)
            dropLinkLocked()
            publish()
        }
        event("disconnected")
    }

    /** Probe this address with HE in addition to broadcast (manual IP entry). */
    fun addManualTarget(ip: String) {
        synchronized(lock) { manualTargets += ip.trim() }
        event("manual_target", mapOf("ip" to ip.trim()))
    }

    private val manualTargets = mutableSetOf<String>()

    enum class CommandResult { SENT, NOT_CONNECTED, NO_CONTROL_CHANNEL, ALREADY_RECORDING, NOT_RECORDING, BUSY }

    fun startRecording(): CommandResult {
        val ts = EmotiBitTime.localString()
        synchronized(lock) {
            if (s.link != LinkState.CONNECTED) return CommandResult.NOT_CONNECTED
            if (s.recording == RecordingStatus.RECORDING || s.recording == RecordingStatus.STOPPING) return CommandResult.ALREADY_RECORDING
            // Wait for the device's own mode packet: it tells whether it is already recording.
            if (s.recording != RecordingStatus.NOT_RECORDING || pendingStartAtMs != null) return CommandResult.BUSY
            // RB on an already-recording EmotiBit would silently switch to a new file, so never resend blindly.
            if (!sendControlLocked(Packet.build(TypeTag.RECORD_BEGIN, nextControl(), nowMs(), listOf(ts)))) return CommandResult.NO_CONTROL_CHANNEL
            pendingStartAtMs = nowMs()
            s = s.copy(recording = RecordingStatus.STARTING, error = null, syncsInRecording = 0, syncsThisConnection = 0, syncRequestsInRecording = 0, markerCount = 0)
            markerCounter = 0
            publish()
        }
        event("record_start_sent", mapOf("timestamp" to ts, "expectedFile" to "$ts.csv"))
        return CommandResult.SENT
    }

    /** Sends RE now. */
    fun stopRecordingNow(): CommandResult {
        val ts = EmotiBitTime.localString()
        synchronized(lock) {
            if (s.link != LinkState.CONNECTED) return CommandResult.NOT_CONNECTED
            if (s.recording == RecordingStatus.NOT_RECORDING) return CommandResult.NOT_RECORDING
            if (!sendControlLocked(Packet.build(TypeTag.RECORD_END, nextControl(), nowMs(), listOf(ts)))) return CommandResult.NO_CONTROL_CHANNEL
            pendingStopAtMs = nowMs()
            s = s.copy(recording = RecordingStatus.STOPPING, safeStop = null)
            publish()
        }
        event("record_stop_sent", mapOf("timestamp" to ts, "syncsInRecording" to state.value.syncsInRecording.toString()))
        return CommandResult.SENT
    }

    /**
     * Waits until [HostConfig.safeStopMinSyncs] verified time syncs exist in the current connection,
     * then sends RE. This puts time syncs at the end of the file so DataParser can correct clock drift.
     */
    fun requestSafeStop(): CommandResult {
        synchronized(lock) {
            if (s.recording == RecordingStatus.NOT_RECORDING) return CommandResult.NOT_RECORDING
            s = s.copy(safeStop = SafeStopState(nowMs(), config.safeStopMinSyncs))
            publish()
        }
        event("safe_stop_requested", mapOf("requiredSyncs" to config.safeStopMinSyncs.toString()))
        evaluateSafeStop()
        return CommandResult.SENT
    }

    fun cancelSafeStop() {
        synchronized(lock) {
            if (s.safeStop == null) return
            s = s.copy(safeStop = null)
            publish()
        }
        event("safe_stop_cancelled")
    }

    /** Writes a user note (UN) into the EmotiBit recording. */
    fun sendNote(text: String): CommandResult {
        val clean = Packet.sanitizeText(text)
        if (clean.isEmpty()) return CommandResult.SENT
        val ts = EmotiBitTime.localString()
        synchronized(lock) {
            if (s.link != LinkState.CONNECTED) return CommandResult.NOT_CONNECTED
            if (!sendControlLocked(Packet.build(TypeTag.USER_NOTE, nextControl(), nowMs(), listOf(ts, clean)))) return CommandResult.NO_CONTROL_CHANNEL
        }
        event("note_sent", mapOf("timestamp" to ts, "text" to clean))
        return CommandResult.SENT
    }

    /** Writes a numbered synchronisation marker note. Returns the label, or null if it could not be sent. */
    fun sendSyncMarker(): String? {
        val ts = EmotiBitTime.localString()
        val label: String
        synchronized(lock) {
            if (s.link != LinkState.CONNECTED) return null
            val n = markerCounter + 1
            label = "SYNC_MARKER_$n"
            if (!sendControlLocked(Packet.build(TypeTag.USER_NOTE, nextControl(), nowMs(), listOf(ts, label)))) return null
            markerCounter = n
            s = s.copy(markerCount = n)
            publish()
        }
        event("marker_sent", mapOf("timestamp" to ts, "label" to label))
        return label
    }

    fun setPowerMode(modeTag: String): CommandResult {
        require(modeTag in setOf(TypeTag.MODE_NORMAL_POWER, TypeTag.MODE_LOW_POWER, TypeTag.MODE_WIRELESS_OFF, TypeTag.MODE_HIBERNATE))
        val ts = EmotiBitTime.localString()
        synchronized(lock) {
            if (s.link != LinkState.CONNECTED) return CommandResult.NOT_CONNECTED
            if (modeTag == TypeTag.MODE_HIBERNATE && s.recording != RecordingStatus.NOT_RECORDING) return CommandResult.ALREADY_RECORDING
            if (!sendControlLocked(Packet.build(modeTag, nextControl(), nowMs(), listOf(ts)))) return CommandResult.NO_CONTROL_CHANNEL
        }
        event("power_mode_sent", mapOf("mode" to modeTag))
        return CommandResult.SENT
    }

    // ------------------------------------------------------------------ advertising thread

    private fun advertisingLoop() {
        val buf = ByteArray(4096)
        val packet = DatagramPacket(buf, buf.size)
        while (running) {
            try {
                tick()
                val sock = advertisingSocket ?: break
                try {
                    packet.setData(buf, 0, buf.size)
                    sock.receive(packet)
                    val msg = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    handleAdvertisingMessage(msg, packet.address.hostAddress)
                } catch (_: SocketTimeoutException) {
                }
            } catch (e: Exception) {
                if (running) {
                    event("warning", mapOf("where" to "advertising", "error" to (e.message ?: e.javaClass.simpleName)), important = false)
                    Thread.sleep(50)
                }
            }
        }
    }

    private fun handleAdvertisingMessage(message: String, ip: String) {
        for (p in Packet.parseMessage(message)) {
            when (p.typeTag) {
                TypeTag.HELLO_HOST -> {
                    val dp = p.valueAfter(PayloadLabel.DATA_PORT)?.trim()?.toIntOrNull() ?: continue
                    val id = p.valueAfter(PayloadLabel.DEVICE_ID)?.trim()?.takeIf { it.isNotEmpty() } ?: ip
                    var isNew = false
                    synchronized(lock) {
                        isNew = !devices.containsKey(id)
                        devices[id] = DeviceInfo(id, ip, dp == EMOTIBIT_AVAILABLE, nowMs(), busy = dp != EMOTIBIT_AVAILABLE)
                        publishDevicesLocked()
                    }
                    if (isNew) event("device_found", mapOf("device" to id, "ip" to ip, "available" to (dp == EMOTIBIT_AVAILABLE).toString()))
                }
                TypeTag.PONG -> {
                    val dp = p.valueAfter(PayloadLabel.DATA_PORT)?.trim()?.toIntOrNull()
                    var connectedNow: Pair<String, String>? = null
                    synchronized(lock) {
                        if (dp == s.dataPort) {
                            if (s.link == LinkState.CONNECTING && ip == connectingIp) {
                                val id = connectingId ?: ip
                                lastPongMs = nowMs()
                                syncVerifier.reset()
                                signals.clear()
                                s = s.copy(link = LinkState.CONNECTED, connectedDeviceId = id, connectedIp = ip, connectedSinceMs = nowMs(), syncsThisConnection = 0)
                                connectingIp = null; connectingId = null
                                publish()
                                connectedNow = id to ip
                            } else if (s.link == LinkState.CONNECTED && ip == s.connectedIp) {
                                lastPongMs = nowMs()
                            }
                        }
                    }
                    connectedNow?.let { event("connected", mapOf("device" to it.first, "ip" to it.second)) }
                }
            }
        }
    }

    private fun tick() {
        val now = nowMs()
        val toSend = mutableListOf<Pair<String, String>>() // packet to IP address
        var lost: String? = null
        var connectTimedOut: String? = null
        var startFailed = false
        var stopUnconfirmed = false
        synchronized(lock) {
            if (now - lastNetworkRefreshMs > 3000 || lastNetworkRefreshMs == 0L) {
                lastNetworkRefreshMs = now
                val nets = try { (config.networkProvider ?: LocalNetwork::discover).invoke() } catch (e: Exception) { emptyList() }
                if (nets.map { it.toString() } != networks.map { it.toString() }) {
                    networks = nets
                    s = s.copy(networks = nets.map { it.toString() })
                    publish()
                    event("networks", mapOf("list" to nets.joinToString(" | ")), important = false)
                }
            }

            // Device availability / expiry
            var changed = false
            val it = devices.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                val age = now - e.value.lastSeenMs
                if (age > config.deviceForgetMs && e.key != s.targetDeviceId) { it.remove(); changed = true }
                else if (age > config.availabilityTimeoutMs && e.value.available) { e.setValue(e.value.copy(available = false)); changed = true }
            }
            if (changed) publishDevicesLocked()

            // Advertising: broadcast once per interval, then unicast sweep if nothing answered.
            if (now - lastAdvertisingMs >= config.advertisingIntervalMs) {
                lastAdvertisingMs = now
                val he = Packet.build(TypeTag.HELLO_EMOTIBIT, nextAdvertising(), now)
                val targets = LinkedHashSet<String>()
                networks.mapNotNullTo(targets) { n -> n.broadcast?.hostAddress }
                targets += config.staticTargets
                targets += manualTargets
                devices.values.mapTo(targets) { d -> d.ip }
                targets.forEach { t -> toSend += he to t }
                sweepActive = config.unicastSweepEnabled && devices.isEmpty() && networks.isNotEmpty()
            }
            var budget = 8
            while (sweepActive && networks.isNotEmpty() && budget-- > 0) {
                val net = networks[sweepNetworkIndex % networks.size]
                val ip = "${net.prefix24}.$sweepHost"
                if (ip != net.address.hostAddress) toSend += Packet.build(TypeTag.HELLO_EMOTIBIT, nextAdvertising(), now) to ip
                sweepHost++
                if (sweepHost > 254) {
                    sweepHost = 2
                    sweepNetworkIndex++
                    if (sweepNetworkIndex >= networks.size) { sweepNetworkIndex = 0; sweepActive = false }
                }
            }

            // Connection state machine
            when (s.link) {
                LinkState.IDLE -> {
                    val target = s.targetDeviceId
                    val dev = target?.let { devices[it] }
                    if (dev != null && dev.available) {
                        connectingId = dev.id
                        connectingIp = dev.ip
                        connectingSinceMs = now
                        lastConnectSendMs = 0
                        s = s.copy(link = LinkState.CONNECTING)
                        publish()
                        event("connecting", mapOf("device" to dev.id, "ip" to dev.ip))
                    }
                }
                LinkState.CONNECTING -> {
                    val ip = connectingIp
                    if (ip == null || now - connectingSinceMs > config.connectTimeoutMs) {
                        connectTimedOut = connectingId ?: "?"
                        connectingIp = null; connectingId = null
                        s = s.copy(link = LinkState.IDLE)
                        publish()
                    } else if (now - lastConnectSendMs >= config.connectRetryMs) {
                        lastConnectSendMs = now
                        toSend += Packet.build(
                            TypeTag.EMOTIBIT_CONNECT, nextAdvertising(), now,
                            listOf(PayloadLabel.CONTROL_PORT, s.controlPort.toString(), PayloadLabel.DATA_PORT, s.dataPort.toString()),
                        ) to ip
                    }
                }
                LinkState.CONNECTED -> {
                    val ip = s.connectedIp!!
                    if (now - lastPongMs > config.connectionTimeoutMs) {
                        lost = s.connectedDeviceId ?: ip
                        dropLinkLocked()
                        publish()
                    } else if (now - lastPingMs >= config.pingIntervalMs) {
                        lastPingMs = now
                        toSend += Packet.build(TypeTag.PING, nextAdvertising(), now, listOf(PayloadLabel.DATA_PORT, s.dataPort.toString())) to ip
                    }
                }
                LinkState.STOPPED -> {}
            }

            // Recording command confirmation timeouts
            pendingStartAtMs?.let { t ->
                if (now - t > config.recordConfirmTimeoutMs && s.link == LinkState.CONNECTED) {
                    pendingStartAtMs = null
                    if (s.recording == RecordingStatus.STARTING) {
                        s = s.copy(recording = RecordingStatus.NOT_RECORDING, error = "record_start_unconfirmed")
                        publish()
                        startFailed = true
                    }
                }
            }
            pendingStopAtMs?.let { t ->
                if (now - t > config.recordConfirmTimeoutMs && s.link == LinkState.CONNECTED && s.recording == RecordingStatus.STOPPING) {
                    pendingStopAtMs = null
                    stopUnconfirmed = true
                }
            }

            publishThrottled()

            // Packet rate
            if (now - packetRateWindowStartMs >= 1000) {
                val rate = packetsThisSecond
                packetsThisSecond = 0
                packetRateWindowStartMs = now
                if (rate != s.dataPacketsPerSecond) { s = s.copy(dataPacketsPerSecond = rate); publish() }
            }
        }

        val sock = advertisingSocket
        if (sock != null) {
            for ((pkt, ip) in toSend) sendUdp(sock, pkt, ip, config.advertisingPort)
        }
        connectTimedOut?.let { event("connect_timeout", mapOf("device" to it)) }
        lost?.let { event("connection_lost", mapOf("device" to it)) }
        if (startFailed) event("record_start_unconfirmed")
        if (stopUnconfirmed) {
            event("record_stop_unconfirmed_retry")
            stopRecordingNow() // RE is idempotent on EmotiBit, safe to resend
        }
        evaluateSafeStop()
    }

    private fun evaluateSafeStop() {
        var fire = false
        var timeout = false
        synchronized(lock) {
            val ss = s.safeStop ?: return
            if (s.recording == RecordingStatus.NOT_RECORDING) {
                s = s.copy(safeStop = null); publish(); return
            }
            val since = s.connectedSinceMs
            if (s.link == LinkState.CONNECTED && s.recording == RecordingStatus.RECORDING && s.syncsThisConnection >= ss.requiredSyncs) {
                fire = true
            } else if (!ss.timedOut && s.link == LinkState.CONNECTED && since != null &&
                nowMs() - maxOf(ss.requestedAtMs, since) > config.safeStopTimeoutMs
            ) {
                // Only time out while connected: out of range, a safe stop simply waits for the next connection.
                s = s.copy(safeStop = ss.copy(timedOut = true)); publish(); timeout = true
            }
        }
        if (timeout) event("safe_stop_timeout")
        if (fire) stopRecordingNow()
    }

    // ------------------------------------------------------------------ data thread

    private fun dataLoop() {
        val buf = ByteArray(65536)
        val packet = DatagramPacket(buf, buf.size)
        while (running) {
            val sock = dataSocket ?: break
            try {
                packet.setData(buf, 0, buf.size)
                sock.receive(packet)
                val ip = packet.address.hostAddress
                val from = InetSocketAddress(packet.address, packet.port)
                val msg = String(packet.data, 0, packet.length, Charsets.UTF_8)
                handleDataMessage(msg, ip, from, sock)
            } catch (_: SocketTimeoutException) {
            } catch (e: Exception) {
                if (running) event("warning", mapOf("where" to "data", "error" to (e.message ?: e.javaClass.simpleName)), important = false)
            }
        }
    }

    private fun handleDataMessage(message: String, ip: String, from: InetSocketAddress, sock: DatagramSocket) {
        val packets = Packet.parseMessage(message)
        val newEvents = mutableListOf<HostEvent>()
        synchronized(lock) {
            if (s.link != LinkState.CONNECTED || ip != s.connectedIp) return
            dataReplyAddress = from
            s = s.copy(lastDataAtMs = nowMs())
            packetsThisSecond += packets.size
        }
        for (p in packets) {
            when (p.typeTag) {
                TypeTag.REQUEST_DATA -> {
                    // Reply first: EmotiBit waits only 100 ms (MAX_SYNC_WAIT_INTERVAL).
                    val key = p.header.packetNumber to p.header.timestamp
                    val isNewRequest: Boolean
                    synchronized(lock) {
                        isNewRequest = key !in recentRequests
                        if (isNewRequest) {
                            recentRequests.addLast(key)
                            while (recentRequests.size > 16) recentRequests.removeFirst()
                        }
                    }
                    if (isNewRequest && p.payload.contains(TypeTag.TIMESTAMP_LOCAL)) {
                        // One packet per datagram: the firmware parses only the first packet of a datagram.
                        val tl = Packet.build(TypeTag.TIMESTAMP_LOCAL, nextData(), nowMs(), listOf(EmotiBitTime.localString()))
                        sendUdp(sock, tl, from)
                        val ak = Packet.build(TypeTag.ACK, nextData(), nowMs(), listOf(p.header.packetNumber.toString(), TypeTag.REQUEST_DATA))
                        sendUdp(sock, ak, from)
                    }
                    synchronized(lock) {
                        if (syncVerifier.onRequest(p) && isRecordingLocked()) {
                            s = s.copy(syncRequestsInRecording = s.syncRequestsInRecording + 1)
                        }
                    }
                }
                TypeTag.TIMESTAMP_LOCAL -> synchronized(lock) { syncVerifier.onTimestampLocal(p) }
                TypeTag.ACK -> synchronized(lock) {
                    val r = syncVerifier.onAck(p)
                    if (r != null) {
                        val inRecording = isRecordingLocked()
                        s = s.copy(
                            lastSyncRttMs = r.rttMs,
                            lastSyncAtMs = nowMs(),
                            syncsInRecording = s.syncsInRecording + if (inRecording) 1 else 0,
                            syncsThisConnection = s.syncsThisConnection + if (inRecording) 1 else 0,
                        )
                        newEvents += makeEvent("sync_verified", mapOf("rttMs" to r.rttMs.toString(), "inRecording" to inRecording.toString()), important = false)
                    }
                }
                TypeTag.EMOTIBIT_MODE -> synchronized(lock) { handleModeLocked(p, newEvents) }
                TypeTag.BATTERY_PERCENT -> p.payload.lastOrNull()?.trim()?.toFloatOrNull()?.let { v -> synchronized(lock) { s = s.copy(batteryPercent = v) } }
                TypeTag.DATA_OVERFLOW -> newEvents += makeEvent("device_data_overflow", mapOf("payload" to p.payload.joinToString(";")))
                else -> signals.add(p)
            }
        }
        synchronized(lock) { if (newEvents.isEmpty()) publishThrottled() else publish() }
        for (e in newEvents) _events.tryEmit(e)
        if (newEvents.any { it.type == "sync_verified" }) evaluateSafeStop()
    }

    private fun isRecordingLocked() = s.recording == RecordingStatus.RECORDING || s.recording == RecordingStatus.STOPPING

    private fun handleModeLocked(p: Packet, out: MutableList<HostEvent>) {
        val now = nowMs()
        val rs = p.valueAfter(PayloadLabel.RECORDING_STATUS)
        val ps = p.valueAfter(PayloadLabel.POWER_STATUS)
        if (ps != null && ps != s.powerMode) {
            s = s.copy(powerMode = ps)
            out += makeEvent("power_mode", mapOf("mode" to ps))
        }
        when (rs) {
            TypeTag.RECORD_BEGIN -> {
                val idx = p.payload.indexOf(PayloadLabel.RECORDING_STATUS)
                val file = p.payload.getOrNull(idx + 2)?.takeIf { it.endsWith(".csv") }
                val was = s.recording
                s = s.copy(recordingFile = file ?: s.recordingFile, recordingStatusAtMs = now)
                if (was != RecordingStatus.STOPPING) {
                    s = s.copy(recording = RecordingStatus.RECORDING)
                    if (was != RecordingStatus.RECORDING) {
                        pendingStartAtMs = null
                        val resumed = was == RecordingStatus.UNKNOWN || was == RecordingStatus.NOT_RECORDING
                        if (file != lastDeviceRecordingFile) {
                            out += makeEvent(if (was == RecordingStatus.STARTING) "record_started" else "recording_in_progress", mapOf("file" to (file ?: "?")))
                        } else if (resumed) {
                            out += makeEvent("recording_in_progress", mapOf("file" to (file ?: "?")))
                        }
                        lastDeviceRecordingFile = file
                    }
                }
            }
            TypeTag.RECORD_END -> {
                val was = s.recording
                if (was == RecordingStatus.STARTING) {
                    s = s.copy(recordingStatusAtMs = now) // RB may not be processed yet
                } else {
                    s = s.copy(recording = RecordingStatus.NOT_RECORDING, recordingStatusAtMs = now, safeStop = null)
                    if (was == RecordingStatus.STOPPING || was == RecordingStatus.RECORDING) {
                        pendingStopAtMs = null
                        out += makeEvent("record_stopped", mapOf("file" to (s.recordingFile ?: "?"), "syncsInRecording" to s.syncsInRecording.toString()))
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ control (TCP) thread

    private fun controlAcceptLoop() {
        while (running) {
            val server = controlServer ?: break
            try {
                val sock = server.accept()
                sock.tcpNoDelay = true
                val ip = sock.inetAddress.hostAddress
                synchronized(lock) {
                    closeQuietly(controlSocket)
                    controlSocket = sock
                    controlSocketIp = ip
                    controlOut = sock.getOutputStream()
                    s = s.copy(controlChannelReady = true)
                    publish()
                }
                event("control_channel_open", mapOf("ip" to ip), important = false)
                thread("emotibit-control-read") { drainControl(sock) }
            } catch (_: SocketTimeoutException) {
            } catch (e: Exception) {
                if (running) event("warning", mapOf("where" to "control", "error" to (e.message ?: e.javaClass.simpleName)), important = false)
            }
        }
    }

    private fun drainControl(sock: Socket) {
        val buf = ByteArray(1024)
        try {
            val input = sock.getInputStream()
            while (running && input.read(buf) >= 0) { /* EmotiBit sends nothing on control */ }
        } catch (_: IOException) {
        }
        synchronized(lock) {
            if (controlSocket === sock) {
                controlSocket = null; controlOut = null; controlSocketIp = null
                s = s.copy(controlChannelReady = false)
                publish()
            }
        }
        closeQuietly(sock)
    }

    private fun sendControlLocked(packet: String): Boolean {
        val out = controlOut ?: return false
        if (controlSocketIp != s.connectedIp) return false
        return try {
            out.write(packet.toByteArray(Charsets.UTF_8))
            out.flush()
            true
        } catch (e: IOException) {
            closeQuietly(controlSocket)
            controlSocket = null; controlOut = null; controlSocketIp = null
            s = s.copy(controlChannelReady = false)
            publish()
            false
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun dropLinkLocked() {
        closeQuietly(controlSocket)
        controlSocket = null; controlOut = null; controlSocketIp = null
        connectingIp = null; connectingId = null
        syncVerifier.reset()
        s = s.copy(
            link = if (running) LinkState.IDLE else LinkState.STOPPED,
            connectedDeviceId = null, connectedIp = null, connectedSinceMs = null,
            controlChannelReady = false, syncsThisConnection = 0, dataPacketsPerSecond = 0,
        )
        // The device keeps recording on its own. An unconfirmed start becomes unknown; an unconfirmed
        // stop turns back into a safe stop, so RE is sent again (after fresh syncs) on reconnection.
        if (s.recording == RecordingStatus.STARTING) s = s.copy(recording = RecordingStatus.UNKNOWN)
        if (s.recording == RecordingStatus.STOPPING) {
            s = s.copy(recording = RecordingStatus.RECORDING, safeStop = SafeStopState(nowMs(), config.safeStopMinSyncs))
        }
        pendingStartAtMs = null
        pendingStopAtMs = null
    }

    private fun publishDevicesLocked() {
        s = s.copy(devices = devices.values.sortedBy { it.id })
        publish()
    }

    private var lastPublishMs = 0L

    private fun publish() {
        _state.value = s
        lastPublishMs = nowMs()
    }

    /** Data packets arrive ~50 times a second; the UI only needs a few updates per second. */
    private fun publishThrottled() {
        if (_state.value != s && nowMs() - lastPublishMs >= 400) publish()
    }

    private fun nextAdvertising(): Int = synchronized(lock) { advertisingCounter.also { advertisingCounter = (advertisingCounter + 1) and 0xFFFF } }
    private fun nextData(): Int = synchronized(lock) { dataCounter.also { dataCounter = (dataCounter + 1) and 0xFFFF } }
    private fun nextControl(): Int = controlCounter.also { controlCounter = (controlCounter + 1) and 0xFFFF }

    private fun sendUdp(sock: DatagramSocket, packet: String, ip: String, port: Int) {
        try {
            sendUdp(sock, packet, InetSocketAddress(InetAddress.getByName(ip), port))
        } catch (_: Exception) {
        }
    }

    private fun sendUdp(sock: DatagramSocket, packet: String, to: InetSocketAddress) {
        val bytes = packet.toByteArray(Charsets.UTF_8)
        try {
            sock.send(DatagramPacket(bytes, bytes.size, to))
        } catch (_: IOException) {
            // Network unreachable while out of range etc.; the timers retry.
        }
    }

    private fun makeEvent(type: String, fields: Map<String, String> = emptyMap(), important: Boolean = true) =
        HostEvent(OffsetDateTime.now().toString(), nowMs(), type, fields, important)

    private fun event(type: String, fields: Map<String, String> = emptyMap(), important: Boolean = true) {
        _events.tryEmit(makeEvent(type, fields, important))
    }

    private fun thread(name: String, body: () -> Unit): Thread =
        Thread(body, name).apply { isDaemon = true; start() }

    private fun closeQuietly(c: AutoCloseable?) {
        try { c?.close() } catch (_: Exception) {}
    }
}
