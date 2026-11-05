package com.rotalab.emotibit.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncVerifierTest {
    private fun p(s: String) = Packet.parse(s)!!

    @Test
    fun verifiesEchoedTriplet() {
        val v = SyncVerifier()
        val rd = p("1000,40,2,RD,1,100,TL,TU")
        assertTrue(v.onRequest(rd))
        assertFalse(v.onRequest(rd)) // echoed copy of the same request
        v.onTimestampLocal(p("1012,41,1,TL,1,100,2026-11-05_10-15-30-123456"))
        val r = v.onAck(p("1013,42,2,AK,1,100,40,RD"))
        assertEquals(SyncVerifier.Result(40, 12), r)
        assertFalse(v.onRequest(rd)) // a late echo after completion is not a new sync
    }

    @Test
    fun missingTimestampIsNotVerified() {
        val v = SyncVerifier()
        v.onRequest(p("1000,40,2,RD,1,100,TL,TU"))
        assertNull(v.onAck(p("1013,42,2,AK,1,100,40,RD")))
    }

    @Test
    fun ackForOtherRequestClearsTimestamp() {
        val v = SyncVerifier()
        v.onRequest(p("1000,40,2,RD,1,100,TL,TU"))
        v.onTimestampLocal(p("1010,41,1,TL,1,100,x"))
        assertNull(v.onAck(p("1011,42,2,AK,1,100,39,RD")))
        assertNull(v.onAck(p("1012,43,2,AK,1,100,40,RD")))
    }
}
