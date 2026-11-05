package com.rotalab.emotibit.protocol

/**
 * EmotiBit packet type tags and payload labels.
 *
 * Values are copied from EmotiBit_XPlat_Utils/src/EmotiBitPacket.cpp.
 */
object TypeTag {
    const val HELLO_EMOTIBIT = "HE"
    const val HELLO_HOST = "HH"
    const val EMOTIBIT_CONNECT = "EC"
    const val EMOTIBIT_DISCONNECT = "ED"
    const val PING = "PN"
    const val PONG = "PO"
    const val REQUEST_DATA = "RD"
    const val TIMESTAMP_LOCAL = "TL"
    const val TIMESTAMP_UTC = "TU"
    const val ACK = "AK"
    const val RECORD_BEGIN = "RB"
    const val RECORD_END = "RE"
    const val USER_NOTE = "UN"
    const val EMOTIBIT_MODE = "EM"
    const val MODE_NORMAL_POWER = "MN"
    const val MODE_LOW_POWER = "ML"
    const val MODE_MAX_LOW_POWER = "MM"
    const val MODE_WIRELESS_OFF = "MO"
    const val MODE_HIBERNATE = "MH"
    const val BATTERY_PERCENT = "B%"
    const val BATTERY_VOLTAGE = "BV"
    const val SD_CARD_PERCENT = "SD"
    const val DATA_OVERFLOW = "DO"
    const val DATA_CLIPPING = "DC"
    const val EDA = "EA"
}

object PayloadLabel {
    const val CONTROL_PORT = "CP"
    const val DATA_PORT = "DP"
    const val DEVICE_ID = "DI"
    const val RECORDING_STATUS = "RS"
    const val POWER_STATUS = "PS"
}

/** Value EmotiBit puts in the HH data-port field when no host is connected. */
const val EMOTIBIT_AVAILABLE = -1

/** Largest datagram EmotiBit firmware reads from the host (MAX_TO_EMOTIBIT_PACKET_LEN). */
const val MAX_TO_EMOTIBIT_PACKET_LEN = 255

data class PacketHeader(
    val timestamp: Long,
    val packetNumber: Int,
    val dataLength: Int,
    val typeTag: String,
    val protocolVersion: Int,
    val dataReliability: Int,
)

/**
 * One EmotiBit packet: `timestamp,packetNumber,dataLength,typeTag,protocolVersion,dataReliability[,payload...]`.
 */
data class Packet(val header: PacketHeader, val payload: List<String>) {

    val typeTag: String get() = header.typeTag

    /** Returns the payload element that follows [key], like EmotiBitPacket::getPacketKeyedValue. */
    fun valueAfter(key: String): String? {
        val i = payload.indexOf(key)
        return if (i >= 0 && i + 1 < payload.size) payload[i + 1] else null
    }

    companion object {
        const val HEADER_FIELDS = 6

        /** Parses one packet line. Returns null for a malformed header. */
        fun parse(line: String): Packet? {
            val clean = line.trimEnd('\n', '\r', '\u0000')
            if (clean.isEmpty()) return null
            val parts = clean.split(',')
            if (parts.size < HEADER_FIELDS) return null
            val timestamp = parts[0].trim().toLongOrNull() ?: return null
            val packetNumber = parts[1].trim().toIntOrNull() ?: return null
            val dataLength = parts[2].trim().toIntOrNull() ?: return null
            val typeTag = parts[3].trim()
            if (typeTag.isEmpty()) return null
            val protocolVersion = parts[4].trim().toIntOrNull() ?: return null
            val reliability = parts[5].trim().toIntOrNull() ?: return null
            return Packet(
                PacketHeader(timestamp, packetNumber, dataLength, typeTag, protocolVersion, reliability),
                parts.subList(HEADER_FIELDS, parts.size).toList(),
            )
        }

        /** Splits a datagram or stream chunk into packets (one per '\n'), skipping malformed lines. */
        fun parseMessage(message: String): List<Packet> =
            message.split('\n').mapNotNull { if (it.isBlank()) null else parse(it) }

        /**
         * Builds a packet string terminated by '\n'. dataLength equals the number of payload elements,
         * which is what EmotiBitPacket::createPacket produces for host-sent packets.
         */
        fun build(
            typeTag: String,
            packetNumber: Int,
            timestamp: Long,
            payload: List<String> = emptyList(),
            protocolVersion: Int = 1,
            dataReliability: Int = 100,
        ): String {
            val sb = StringBuilder()
            sb.append(timestamp and 0xFFFFFFFFL).append(',')
                .append(packetNumber and 0xFFFF).append(',')
                .append(payload.size).append(',')
                .append(typeTag).append(',')
                .append(protocolVersion).append(',')
                .append(dataReliability)
            for (p in payload) sb.append(',').append(p)
            sb.append('\n')
            return sb.toString()
        }

        /**
         * Makes free text safe to embed as one payload element: commas would split it into
         * several elements and newlines would end the packet.
         */
        fun sanitizeText(text: String, maxLength: Int = 180): String =
            text.replace(',', ';').replace('\n', ' ').replace('\r', ' ').replace('\u0000', ' ')
                .trim().take(maxLength)
    }
}
