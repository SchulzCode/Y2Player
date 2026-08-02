package com.schulzcode.y2player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListeningSessionTest {
    private var now = 1_000L

    private fun session(startedAtUtcMs: Long = 1_700_000_000_000, startPositionMs: Long = 0) =
        ListeningSession(startedAtUtcMs, startPositionMs) { now }

    private fun elapse(ms: Long) {
        now += ms
    }

    @Test
    fun `a session with no resume has heard nothing`() {
        assertEquals(0L, session().listenedMs())
    }

    @Test
    fun `time accrues only while resumed`() {
        val listening = session().apply { resume() }
        elapse(30_000)
        assertEquals(30_000L, listening.listenedMs())
    }

    @Test
    fun `paused time is not counted`() {
        val listening = session().apply { resume() }
        elapse(30_000)
        listening.pause()
        elapse(10 * 60_000)
        assertEquals(30_000L, listening.listenedMs())
    }

    @Test
    fun `pause and resume accumulate across stretches`() {
        val listening = session().apply { resume() }
        elapse(30_000)
        listening.pause()
        elapse(60_000)
        listening.resume()
        elapse(45_000)
        assertEquals(75_000L, listening.listenedMs())
    }

    @Test
    fun `listened time is visible while still running`() {
        val listening = session().apply { resume() }
        elapse(20_000)
        assertEquals(20_000L, listening.listenedMs())
        elapse(20_000)
        assertEquals(40_000L, listening.listenedMs())
    }

    @Test
    fun `resume is idempotent so a repeated start cannot restart the clock`() {
        val listening = session().apply { resume() }
        elapse(30_000)
        listening.resume()
        elapse(10_000)
        assertEquals("the first stretch must not be discarded", 40_000L, listening.listenedMs())
    }

    @Test
    fun `pause is idempotent so a repeated pause cannot double count`() {
        val listening = session().apply { resume() }
        elapse(30_000)
        listening.pause()
        listening.pause()
        assertEquals(30_000L, listening.listenedMs())
    }

    @Test
    fun `listened time never decreases`() {
        val listening = session().apply { resume() }
        var previous = 0L
        repeat(20) {
            elapse(1_000)
            val current = listening.listenedMs()
            assertTrue("went backwards: $previous -> $current", current >= previous)
            previous = current
        }
        listening.pause()
        assertTrue(listening.listenedMs() >= previous)
    }

    @Test
    fun `a clock that goes backwards cannot subtract time`() {
        val listening = session().apply { resume() }
        elapse(30_000)
        listening.pause()
        now -= 60_000
        listening.resume()
        elapse(0)
        assertTrue("must never go negative", listening.listenedMs() >= 30_000L)
    }

    @Test
    fun `seeking does not affect listening time`() {
        val listening = session(startPositionMs = 0).apply { resume() }
        elapse(60_000)
        assertEquals(60_000L, listening.listenedMs())
    }

    @Test
    fun `start metadata is carried unchanged for the record`() {
        val listening = session(startedAtUtcMs = 1_700_000_000_000, startPositionMs = 42_000)
        assertEquals(1_700_000_000_000L, listening.startedAtUtcMs)
        assertEquals(42_000L, listening.startPositionMs)
    }

    @Test
    fun `a session that never resumed reports nothing to record`() {
        val neverStarted = session()
        assertEquals(0L, neverStarted.listenedMs())
        neverStarted.pause()
        assertEquals(0L, neverStarted.listenedMs())
    }
}
