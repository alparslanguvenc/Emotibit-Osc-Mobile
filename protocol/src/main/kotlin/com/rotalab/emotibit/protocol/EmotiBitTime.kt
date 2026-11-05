package com.rotalab.emotibit.protocol

import java.time.Clock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Host timestamp strings in EmotiBitPacket::TIMESTAMP_STRING_FORMAT ("%Y-%m-%d_%H-%M-%S-%f"):
 * local wall-clock time with 6-digit microseconds, e.g. 2026-11-05_10-15-30-123456.
 * RB uses this string as the recording file name; TL replies carry it for time syncs.
 */
object EmotiBitTime {
    private val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSSSSS")
    private val PATTERN = Regex("""^(\d{4})-(\d{2})-(\d{2})_(\d{2})-(\d{2})-(\d{2})-(\d{6})$""")

    fun localString(clock: Clock = Clock.systemDefaultZone()): String = LocalDateTime.now(clock).format(FORMAT)

    fun format(time: LocalDateTime): String = time.format(FORMAT)

    /** Parses a timestamp string, or a recording file name such as "2026-11-05_10-15-30-123456.csv". */
    fun parse(value: String): LocalDateTime? {
        val base = value.removeSuffix(".csv").removePrefix("/")
        val m = PATTERN.matchEntire(base) ?: return null
        val g = m.groupValues
        return try {
            LocalDateTime.of(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toInt(), g[7].toInt() * 1000)
        } catch (e: Exception) {
            null
        }
    }
}
