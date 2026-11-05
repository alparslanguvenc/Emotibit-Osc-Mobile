package com.rotalab.emotibit.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class EmotiBitTimeTest {
    @Test
    fun formatsLikeOscilloscope() {
        // %Y-%m-%d_%H-%M-%S-%f with 6-digit microseconds
        val t = LocalDateTime.of(2026, 11, 5, 9, 3, 7, 4_005_000)
        assertEquals("2026-11-05_09-03-07-004005", EmotiBitTime.format(t))
    }

    @Test
    fun localStringHasExpectedShape() {
        assertTrue(Regex("""\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}-\d{6}""").matches(EmotiBitTime.localString()))
    }

    @Test
    fun parsesFileName() {
        assertEquals(LocalDateTime.of(2026, 11, 5, 10, 15, 30, 123_456_000), EmotiBitTime.parse("2026-11-05_10-15-30-123456.csv"))
        assertNull(EmotiBitTime.parse("recording.csv"))
    }
}
