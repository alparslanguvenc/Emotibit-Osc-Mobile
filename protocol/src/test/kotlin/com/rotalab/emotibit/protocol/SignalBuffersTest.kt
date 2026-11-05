package com.rotalab.emotibit.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalBuffersTest {
    private fun p(s: String) = Packet.parse(s)!!

    @Test
    fun spreadsSamplesBackFromPacketTimestamp() {
        val b = SignalBuffers()
        // 25 Hz = 40 ms period: samples at 920, 960, 1000
        b.add(p("1000,1,3,PG,1,100,10,11,12"))
        val s = b.snapshot("PG", 0.0)!!
        assertEquals(3, s.size)
        assertEquals(920.0, s.times[0], 0.01)
        assertEquals(1000.0, s.times[2], 0.01)
        assertEquals(12f, s.values[2])
        assertEquals(1000.0, b.latestTimeMs, 0.0)
    }

    @Test
    fun estimatesPeriodFromPackets() {
        val b = SignalBuffers()
        // Real rate 20 Hz (50 ms), default for EA is 15 Hz (66.7 ms)
        var t = 1000L
        repeat(200) { t += 150; b.add(p("$t,$it,3,EA,1,100,1,2,3")) }
        val s = b.snapshot("EA", t - 1000.0)!!
        assertTrue("about 20 samples per second, got ${s.size}", s.size in 19..22)
        for (i in 1 until s.size) assertTrue(s.times[i] > s.times[i - 1])
    }

    @Test
    fun aperiodicKeepsOneValuePerPacket() {
        val b = SignalBuffers()
        b.add(p("1000,1,1,HR,1,100,71"))
        b.add(p("1830,2,1,HR,1,100,73"))
        val s = b.snapshot("HR", 0.0)!!
        assertEquals(2, s.size)
        assertEquals(1830.0, s.times[1], 0.0)
        assertEquals(73f, b.latestValue("HR"))
    }

    @Test
    fun ignoresNonSignalPacketsAndJunk() {
        val b = SignalBuffers()
        assertFalse(b.add(p("1000,1,4,EM,1,100,RS,RE,PS,MN")))
        b.add(p("1000,1,2,EA,1,100,x,\u0019"))
        b.add(p("1000,1,2,T1,1,100,nan,inf"))
        assertFalse(b.hasData("EA"))
        assertFalse(b.hasData("T1"))
        assertNull(b.snapshot("TH", 0.0))
    }

    @Test
    fun windowAndRingCapacity() {
        val b = SignalBuffers(capacity = 100)
        var t = 0L
        repeat(100) { t += 200; b.add(p("$t,$it,5,AX,1,100,1,2,3,4,5")) }
        assertEquals(100, b.snapshot("AX", 0.0)!!.size)
        val recent = b.snapshot("AX", t - 400.0)!!
        assertTrue(recent.times.all { it >= t - 400.0 })
        assertTrue(recent.size in 10..11)
    }

    @Test
    fun clockRestartClears() {
        val b = SignalBuffers()
        b.add(p("500000,1,1,EA,1,100,1"))
        b.add(p("1000,2,1,EA,1,100,2"))
        assertEquals(1, b.snapshot("EA", 0.0)!!.size)
        assertEquals(1000.0, b.latestTimeMs, 0.0)
    }
}
