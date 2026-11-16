package com.javaram.gallery.search

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class LatestRequestRunnerTest {

    @Test
    fun requestsArrivingWhileRunningCollapseToTheNewest() = runBlocking {
        val started = Channel<Int>(Channel.UNLIMITED)
        val release = CompletableDeferred<Unit>()
        val ran = mutableListOf<Int>()
        val runner = LatestRequestRunner<Int>(this) { x ->
            ran += x
            started.send(x)
            if (x == 1) release.await()
        }

        runner.submit(1)
        assertEquals(1, started.receive())
        runner.submit(2)
        runner.submit(3)
        release.complete(Unit)
        runner.awaitIdle()

        assertEquals(listOf(1, 3), ran)
    }

    @Test
    fun requestAfterIdleStartsAFreshDrain() = runBlocking {
        val ran = mutableListOf<Int>()
        val runner = LatestRequestRunner<Int>(this) { ran += it }

        runner.submit(1)
        runner.awaitIdle()
        runner.submit(2)
        runner.awaitIdle()

        assertEquals(listOf(1, 2), ran)
    }

    @Test
    fun workNeverOverlapsItself() = runBlocking {
        val running = AtomicInteger(0)
        var worstOverlap = 0
        val runner = LatestRequestRunner<Int>(this) {
            val now = running.incrementAndGet()
            if (now > worstOverlap) worstOverlap = now
            kotlinx.coroutines.yield()
            running.decrementAndGet()
        }

        repeat(20) { i ->
            runner.submit(i)
            kotlinx.coroutines.yield()
        }
        runner.awaitIdle()

        assertEquals(1, worstOverlap)
        assertTrue(running.get() == 0)
    }
}
