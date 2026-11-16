package com.javaram.gallery.search

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceScanGateTest {

    /** Stands in for SharedPreferences: a new gate reads whatever the previous one saved. */
    private class FakeStore(var saved: Boolean? = null) {
        fun gate() = FaceScanGate(initialEnabled = saved ?: true) { saved = it }
    }

    /** Starts a scan and returns the Scan it began with. */
    private fun snapshot(gate: FaceScanGate): FaceScanGate.Scan = runBlocking { gate.scan { it } }!!

    @Test
    fun enabledByDefaultWhenNothingIsSaved() {
        assertTrue(FakeStore().gate().isEnabled)
    }

    @Test
    fun switchedOffSurvivesRestart() {
        val store = FakeStore()
        assertTrue(store.gate().setEnabled(false))

        assertEquals(false, store.saved)
        assertFalse(store.gate().isEnabled)
    }

    @Test
    fun setEnabledToCurrentValueChangesNothing() {
        val gate = FakeStore().gate()
        val scan = snapshot(gate)

        assertFalse(gate.setEnabled(true))
        assertTrue(gate.isLive(scan))
    }

    @Test
    fun switchingOffMakesLiveScanStaleAtOnce() {
        val gate = FakeStore().gate()
        val scan = snapshot(gate)

        assertTrue(gate.setEnabled(false))

        assertFalse(gate.isLive(scan))
        var wrote = false
        assertNull(gate.commit(scan) { wrote = true })
        assertFalse(wrote)
    }

    @Test
    fun offThenOnLeavesOldScanStale() {
        val gate = FakeStore().gate()
        val old = snapshot(gate)

        gate.setEnabled(false)
        gate.setEnabled(true)

        assertFalse(gate.isLive(old))
        assertNull(gate.commit(old) { "wrote" })
    }

    @Test
    fun scanStartedAfterReEnableIsLive() {
        val gate = FakeStore().gate()
        gate.setEnabled(false)
        gate.setEnabled(true)

        val fresh = snapshot(gate)

        assertTrue(gate.isLive(fresh))
        assertEquals("wrote", gate.commit(fresh) { "wrote" })
    }

    @Test
    fun scanCalledWhileOffDoesNothing() = runBlocking {
        val gate = FakeStore(saved = false).gate()
        var ran = false

        assertNull(gate.scan { ran = true })
        assertFalse(ran)
    }

    @Test
    fun reEnableWaitsForStaleHolderThenRuns() = runBlocking {
        val gate = FakeStore().gate()
        val holding = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()

        val stale = async {
            gate.scan {
                order += "stale-start"
                holding.complete(Unit)
                release.await()
                order += "stale-end"
                "stale"
            }
        }
        holding.await()
        gate.setEnabled(false)
        gate.setEnabled(true)
        val fresh = async { gate.scan { order += "fresh"; "fresh" } }
        yield()

        // The fresh scan waits behind the stale one. It is neither dropped nor run early.
        assertEquals(listOf("stale-start"), order)
        release.complete(Unit)

        assertEquals("stale", stale.await())
        assertEquals("fresh", fresh.await())
        assertEquals(listOf("stale-start", "stale-end", "fresh"), order)
    }

    @Test
    fun queuedScanIsSkippedWhenSwitchChangedBeforeItsTurn() = runBlocking {
        val gate = FakeStore().gate()
        val holding = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = async { gate.scan { holding.complete(Unit); release.await() } }
        holding.await()

        var ran = false
        val queued = async { gate.scan { ran = true } }
        yield()
        gate.setEnabled(false)
        gate.setEnabled(true)
        release.complete(Unit)
        holder.await()

        assertNull(queued.await())
        assertFalse(ran)
    }

    @Test
    fun repeatedScansWithSameSwitchStateAllRun() = runBlocking {
        // No assumption that a queued scan covers another's photos: every queued scan runs its own block.
        val gate = FakeStore().gate()
        val holding = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = async { gate.scan { holding.complete(Unit); release.await() } }
        holding.await()

        val runs = mutableListOf<Int>()
        val queued = (1..3).map { i -> async { gate.scan { runs += i; i } } }
        yield()
        release.complete(Unit)
        holder.await()

        assertEquals(listOf(1, 2, 3), queued.map { it.await() })
        assertEquals(listOf(1, 2, 3), runs.sorted())
    }

    @Test
    fun commitAfterSwitchOffWritesNothing() {
        val gate = FakeStore().gate()
        val scan = snapshot(gate)
        assertEquals(42, gate.commit(scan) { 42 })

        gate.setEnabled(false)

        assertNull(gate.commit(scan) { error("must not write after a switch-off") })
    }
}
