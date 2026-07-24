package com.schulzcode.y2player.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The writer is the one piece both logs now depend on, so the properties it has
 * to hold are asserted directly rather than only through [EventLog].
 *
 * Every sink here reports itself urgent. That is not incidental: under the unit
 * test runner `SystemClock.elapsedRealtime()` returns 0, so a non-urgent batch
 * would sit in the full coalescing window and make these tests sleep for
 * seconds. Urgency is exactly the path that must bypass the window anyway.
 */
class LogWriterTest {

    private class RecordingSink(
        private val urgent: Boolean = true,
        private val onDrain: () -> Unit = {}
    ) : LogWriter.Sink {
        val drains = AtomicInteger(0)
        override fun hasUrgentPending(): Boolean = urgent
        override fun drainAndWrite() {
            drains.incrementAndGet()
            onDrain()
        }
    }

    @Test fun oneWakeDrainsEverySinkSharingTheWriter() {
        val reached = CountDownLatch(2)
        val writer = LogWriter("test-writer")
        val first = RecordingSink { reached.countDown() }
        val second = RecordingSink { reached.countDown() }
        writer.register(first)
        writer.register(second)

        writer.wake()

        assertTrue("both sinks should be drained by a single wake", reached.await(5, TimeUnit.SECONDS))
    }

    @Test fun registeringTheSameSinkTwiceDoesNotDrainItTwice() {
        val drained = CountDownLatch(1)
        val writer = LogWriter("test-writer-dedupe")
        val sink = RecordingSink { drained.countDown() }
        writer.register(sink)
        writer.register(sink)

        writer.wake()

        assertTrue(drained.await(5, TimeUnit.SECONDS))
        // Give the writer a moment to do anything further it might wrongly do.
        Thread.sleep(100)
        assertEquals(1, sink.drains.get())
    }

    @Test fun aSinkThatThrowsDoesNotStopTheOther() {
        val healthyDrained = CountDownLatch(1)
        val writer = LogWriter("test-writer-isolation")
        val faulty = object : LogWriter.Sink {
            override fun hasUrgentPending(): Boolean = true
            override fun drainAndWrite() = throw IllegalStateException("sink failure")
        }
        val healthy = RecordingSink { healthyDrained.countDown() }
        writer.register(faulty)
        writer.register(healthy)

        writer.wake()

        assertTrue("a failing sink must not take the writer thread down", healthyDrained.await(5, TimeUnit.SECONDS))
    }

    @Test fun writerSurvivesASinkThatThrowsFromItsUrgencyCheck() {
        val drained = CountDownLatch(1)
        val writer = LogWriter("test-writer-urgency")
        val faulty = object : LogWriter.Sink {
            override fun hasUrgentPending(): Boolean = throw IllegalStateException("urgency failure")
            override fun drainAndWrite() = Unit
        }
        writer.register(faulty)
        writer.register(RecordingSink { drained.countDown() })

        writer.wake()

        assertTrue(drained.await(5, TimeUnit.SECONDS))
    }

    @Test fun aWakeArrivingDuringADrainIsNotLost() {
        val twoDrains = CountDownLatch(2)
        val writer = LogWriter("test-writer-rewake")
        var wokeAgain = false
        writer.register(object : LogWriter.Sink {
            override fun hasUrgentPending(): Boolean = true
            override fun drainAndWrite() {
                twoDrains.countDown()
                if (!wokeAgain) {
                    wokeAgain = true
                    // Simulates a caller enqueuing while the writer is mid-drain.
                    writer.wake()
                }
            }
        })

        writer.wake()

        assertTrue("a wake during a drain must schedule another pass", twoDrains.await(5, TimeUnit.SECONDS))
    }
}
