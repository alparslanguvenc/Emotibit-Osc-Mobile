package com.rotalab.emotibit.protocol

/**
 * Recent sensor samples per type tag, for live plotting.
 *
 * A data packet carries N samples and one EmotiBit timestamp (ms). Samples are spread back in time
 * from that timestamp using the channel's sample period, estimated from successive packets
 * (starting from the stock-firmware rates). Aperiodic channels (HR, BI, SA, SR, SF) keep one
 * value per packet at the packet timestamp.
 *
 * Thread-safe: the data thread adds, the UI thread reads snapshots.
 */
class SignalBuffers(private val capacity: Int = 4096) {

    companion object {
        /** Stock firmware sampling rates in Hz (EmotiBit.cpp, setup sampling rates). */
        val DEFAULT_RATES: Map<String, Double> = buildMap {
            put("EA", 15.0); put("EL", 15.0); put("ER", 15.0)
            listOf("PI", "PR", "PG").forEach { put(it, 25.0) }
            listOf("AX", "AY", "AZ", "GX", "GY", "GZ", "MX", "MY", "MZ").forEach { put(it, 25.0) }
            listOf("T0", "T1", "TH", "H0").forEach { put(it, 7.5) }
        }
        val APERIODIC = setOf("HR", "BI", "SA", "SR", "SF")
        val PLOTTED: Set<String> = DEFAULT_RATES.keys + APERIODIC

        /** Gaps longer than this (ms) are drawn as breaks, not straight lines. */
        const val GAP_MS = 1500.0
    }

    private class Series(capacity: Int, var periodMs: Double) {
        val t = DoubleArray(capacity)
        val v = FloatArray(capacity)
        var head = 0 // next write index
        var size = 0
        var lastPacketTs = Double.NaN
        var lastValue = Float.NaN

        fun add(time: Double, value: Float) {
            t[head] = time
            v[head] = value
            head = (head + 1) % t.size
            if (size < t.size) size++
            lastValue = value
        }
    }

    /** Samples of one channel, oldest first. */
    class Snapshot(val times: DoubleArray, val values: FloatArray) {
        val size: Int get() = times.size
    }

    private val lock = Any()
    private val series = HashMap<String, Series>()

    /** Latest EmotiBit timestamp (ms) seen in any plotted packet; NaN before data arrives. */
    @Volatile var latestTimeMs: Double = Double.NaN
        private set

    fun clear() = synchronized(lock) {
        series.clear()
        latestTimeMs = Double.NaN
    }

    /** Adds the samples of one packet. Returns false for tags that are not plotted. */
    fun add(packet: Packet): Boolean {
        val tag = packet.typeTag
        if (tag !in PLOTTED) return false
        val values = packet.payload.mapNotNull { it.trim().toFloatOrNull()?.takeIf { v -> v.isFinite() } }
        if (values.isEmpty()) return true
        val ts = packet.header.timestamp.toDouble()
        synchronized(lock) {
            val latest = latestTimeMs
            if (!latest.isNaN() && ts < latest - 60_000) {
                // EmotiBit clock restarted (reset/reboot): start over.
                series.clear()
                latestTimeMs = Double.NaN
            }
            val s = series.getOrPut(tag) { Series(capacity, 1000.0 / (DEFAULT_RATES[tag] ?: 25.0)) }
            if (tag in APERIODIC) {
                s.add(ts, values.last())
            } else {
                val prev = s.lastPacketTs
                if (!prev.isNaN() && ts > prev) {
                    val measured = (ts - prev) / values.size
                    // Ignore gaps (lost packets, out of range) when estimating the period.
                    if (measured in s.periodMs * 0.5..s.periodMs * 2.0) s.periodMs = s.periodMs * 0.9 + measured * 0.1
                }
                val n = values.size
                val lastSampleTime = s.t.let { if (s.size > 0) it[(s.head - 1 + it.size) % it.size] else Double.NEGATIVE_INFINITY }
                for (i in 0 until n) {
                    var time = ts - (n - 1 - i) * s.periodMs
                    if (time <= lastSampleTime) time = lastSampleTime + 0.001 // keep times increasing
                    s.add(time, values[i])
                }
                s.lastPacketTs = ts
            }
            if (latestTimeMs.isNaN() || ts > latestTimeMs) latestTimeMs = ts
        }
        return true
    }

    /** Samples of [tag] with time >= [fromMs], oldest first. */
    fun snapshot(tag: String, fromMs: Double): Snapshot? = synchronized(lock) {
        val s = series[tag] ?: return null
        val cap = s.t.size
        val start = (s.head - s.size + cap) % cap
        var first = 0
        // Binary search over the ring for the first sample >= fromMs.
        var lo = 0
        var hi = s.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (s.t[(start + mid) % cap] < fromMs) lo = mid + 1 else hi = mid
        }
        first = lo
        val n = s.size - first
        val times = DoubleArray(n)
        val values = FloatArray(n)
        for (i in 0 until n) {
            val idx = (start + first + i) % cap
            times[i] = s.t[idx]
            values[i] = s.v[idx]
        }
        Snapshot(times, values)
    }

    fun latestValue(tag: String): Float? = synchronized(lock) { series[tag]?.lastValue?.takeIf { !it.isNaN() } }

    fun hasData(tag: String): Boolean = synchronized(lock) { (series[tag]?.size ?: 0) > 0 }
}
