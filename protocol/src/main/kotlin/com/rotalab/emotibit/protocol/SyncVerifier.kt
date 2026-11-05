package com.rotalab.emotibit.protocol

/**
 * Follows the RD → TL → AK packets that EmotiBit echoes in its data stream and reports a time sync
 * as verified with the same rule EmotiBit DataParser uses on the SD file
 * (ofxEmotiBit/EmotiBitDataParser/src/ofApp.cpp, PARSING_TIMESTAMPS):
 * an AK whose first payload element is the packet number of the pending RD, after a TL.
 * Round-trip time = TL timestamp − RD timestamp, both in EmotiBit milliseconds.
 *
 * EmotiBit sends each RD twice: as the request and again in the echo bundle. Both copies are
 * identical, so a repeated (packetNumber, timestamp) does not restart the pending sync.
 */
class SyncVerifier {
    private var rdNumber: Int = -1
    private var rdTimestamp: Long = -1
    private var tlTimestamp: Long = -1
    private var pending = false

    data class Result(val rdPacketNumber: Int, val rttMs: Long)

    fun reset() {
        pending = false
        rdNumber = -1
        rdTimestamp = -1
        tlTimestamp = -1
    }

    /** Returns true when this RD starts a new sync (it is not a copy of the pending one). */
    fun onRequest(packet: Packet): Boolean {
        if (!packet.payload.contains(TypeTag.TIMESTAMP_LOCAL)) return false
        val n = packet.header.packetNumber
        val t = packet.header.timestamp
        if (pending && n == rdNumber && t == rdTimestamp) return false
        if (!pending && n == rdNumber && t == rdTimestamp) return false // echo after completion
        rdNumber = n
        rdTimestamp = t
        tlTimestamp = -1
        pending = true
        return true
    }

    fun onTimestampLocal(packet: Packet) {
        if (pending) tlTimestamp = packet.header.timestamp
    }

    fun onAck(packet: Packet): Result? {
        if (!pending) return null
        val ackFor = packet.payload.firstOrNull()?.trim()?.toIntOrNull() ?: return null
        if (ackFor != rdNumber) {
            tlTimestamp = -1
            return null
        }
        if (tlTimestamp < 0) return null
        val rtt = tlTimestamp - rdTimestamp
        pending = false
        return Result(rdNumber, rtt)
    }
}
