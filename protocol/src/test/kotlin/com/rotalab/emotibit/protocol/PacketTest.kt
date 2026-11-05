package com.rotalab.emotibit.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PacketTest {
    @Test
    fun buildsHelloWithoutPayload() {
        // EmotiBitPacket::createPacket(HELLO_EMOTIBIT, n, "", 0) -> header + '\n'
        assertEquals("1234,7,0,HE,1,100\n", Packet.build(TypeTag.HELLO_EMOTIBIT, 7, 1234))
    }

    @Test
    fun buildsConnectPayload() {
        val s = Packet.build(TypeTag.EMOTIBIT_CONNECT, 3, 10, listOf("CP", "3133", "DP", "3132"))
        assertEquals("10,3,4,EC,1,100,CP,3133,DP,3132\n", s)
    }

    @Test
    fun wrapsCounters() {
        assertEquals("4294967295,0,0,PN,1,100\n", Packet.build("PN", 65536, 4294967295L))
    }

    @Test
    fun parsesHelloHost() {
        val p = Packet.parse("51234,12,4,HH,1,100,DP,-1,DI,MD-V5-0000313\n")
        assertNotNull(p)
        assertEquals("HH", p!!.typeTag)
        assertEquals("-1", p.valueAfter(PayloadLabel.DATA_PORT))
        assertEquals("MD-V5-0000313", p.valueAfter(PayloadLabel.DEVICE_ID))
    }

    @Test
    fun parsesModePacket() {
        val p = Packet.parse("900,5,4,EM,1,100,RS,RB,2026-11-05_10-15-30-123456.csv,PS,MN")!!
        assertEquals("RB", p.valueAfter("RS"))
        assertEquals("MN", p.valueAfter("PS"))
    }

    @Test
    fun rejectsMalformed() {
        assertNull(Packet.parse("hello"))
        assertNull(Packet.parse("1,2,3,EA,1"))
        assertNull(Packet.parse("x,2,3,EA,1,100"))
    }

    @Test
    fun splitsMultiPacketMessage() {
        val msg = "1,1,1,EA,1,100,0.1\n2,2,1,EA,1,100,0.2\n\nbad\n3,3,2,RD,1,100,TL,TU\n"
        val ps = Packet.parseMessage(msg)
        assertEquals(listOf("EA", "EA", "RD"), ps.map { it.typeTag })
    }

    @Test
    fun sanitizesNotes() {
        assertEquals("a; b c", Packet.sanitizeText(" a, b\nc "))
    }
}
