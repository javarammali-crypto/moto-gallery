package com.javaram.gallery

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class GalleryFaceSwitchTest {

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Published events, in order: the initial read, then each applied change. */
    private sealed interface Event {
        data class Read(val value: Boolean?, val failed: Boolean) : Event
        data class Applied(val enabled: Boolean, val changed: Boolean) : Event
    }

    private suspend fun Channel<Event>.next(): Event = withTimeout(5_000) { receive() }

    private fun Channel<Event>.onRead(read: Result<Boolean>) {
        trySend(read.fold(onSuccess = { Event.Read(it, false) }, onFailure = { Event.Read(null, true) }))
    }

    @Test
    fun initialReadIsPublishedBeforeAnyChange() = runBlocking {
        val scope = newScope()
        try {
            val events = Channel<Event>(Channel.UNLIMITED)
            val switch = OrderedSwitch(
                scope = scope,
                dispatcher = Dispatchers.Default,
                initial = { false },
                apply = { true },
                onInitial = { events.onRead(it) },
                onApplied = { e, c -> events.trySend(Event.Applied(e, c)) },
            )
            switch.request(true)

            assertEquals(Event.Read(false, false), events.next())
            assertEquals(Event.Applied(true, true), events.next())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun changeRequestedBeforeInitialReadFinishesIsAppliedAfterIt() = runBlocking {
        val scope = newScope()
        try {
            val readRelease = CountDownLatch(1)
            val events = Channel<Event>(Channel.UNLIMITED)
            val switch = OrderedSwitch(
                scope = scope,
                dispatcher = Dispatchers.Default,
                initial = { readRelease.await(); true },
                apply = { false },
                onInitial = { events.onRead(it) },
                onApplied = { e, c -> events.trySend(Event.Applied(e, c)) },
            )
            switch.request(false) // queued while the read is still blocked

            readRelease.countDown()

            assertEquals(Event.Read(true, false), events.next())
            assertEquals(Event.Applied(false, false), events.next())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun failedInitialReadIsPublishedAsFailureNotAsAValue() = runBlocking {
        val scope = newScope()
        try {
            val events = Channel<Event>(Channel.UNLIMITED)
            val switch = OrderedSwitch(
                scope = scope,
                dispatcher = Dispatchers.Default,
                initial = { throw IllegalStateException("prefs unreadable") },
                apply = { true },
                onInitial = { events.onRead(it) },
                onApplied = { e, c -> events.trySend(Event.Applied(e, c)) },
            )

            assertEquals(Event.Read(null, true), events.next())
            // Nothing else is published until a change succeeds: the unknown value is never asserted.
            switch.request(true)
            assertEquals(Event.Applied(true, true), events.next())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun appliesRequestsInOrderAndRepeatedStateChangesNothing() = runBlocking {
        val scope = newScope()
        try {
            var state = true
            val events = Channel<Event>(Channel.UNLIMITED)
            val switch = OrderedSwitch(
                scope = scope,
                dispatcher = Dispatchers.Default,
                initial = { true },
                apply = { v -> val changed = state != v; state = v; changed },
                onInitial = { events.onRead(it) },
                onApplied = { e, c -> events.trySend(Event.Applied(e, c)) },
            )
            assertEquals(Event.Read(true, false), events.next())

            listOf(true, false, false, true).forEach { switch.request(it) }

            assertEquals(Event.Applied(true, false), events.next()) // on again: same state
            assertEquals(Event.Applied(false, true), events.next()) // off
            assertEquals(Event.Applied(false, false), events.next()) // off again: same state
            assertEquals(Event.Applied(true, true), events.next()) // on
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun requestReturnsWhileApplyIsBlocked() = runBlocking {
        val scope = newScope()
        try {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val events = Channel<Event>(Channel.UNLIMITED)
            val switch = OrderedSwitch(
                scope = scope,
                dispatcher = Dispatchers.Default,
                initial = { true },
                apply = { entered.countDown(); release.await(); true },
                onInitial = { events.onRead(it) },
                onApplied = { e, c -> events.trySend(Event.Applied(e, c)) },
            )
            assertEquals(Event.Read(true, false), events.next())

            val started = System.nanoTime()
            switch.request(false)
            switch.request(true)
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

            // The caller (the UI thread in the app) is not held up by the blocked apply.
            assertTrue("request took $elapsedMs ms", elapsedMs < 1_000)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            release.countDown()
            assertEquals(Event.Applied(false, true), events.next())
            assertEquals(Event.Applied(true, true), events.next())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun applyRunsOffTheCallingThread() = runBlocking {
        val scope = newScope()
        try {
            val threads = Channel<Thread>(Channel.UNLIMITED)
            val switch = OrderedSwitch(
                scope = scope,
                dispatcher = Dispatchers.IO,
                initial = { true },
                apply = { threads.trySend(Thread.currentThread()); true },
                onInitial = { },
                onApplied = { _, _ -> },
            )

            switch.request(false)
            val applyThread = withTimeout(5_000) { threads.receive() }

            assertNotSame(Thread.currentThread(), applyThread)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun failedApplyKeepsLastAppliedStateAndLaterRequestsStillApply() = runBlocking {
        val scope = newScope()
        try {
            val events = Channel<Event>(Channel.UNLIMITED)
            val switch = OrderedSwitch(
                scope = scope,
                dispatcher = Dispatchers.Default,
                initial = { true },
                apply = { v -> if (!v) throw IllegalStateException("write failed"); true },
                onInitial = { events.onRead(it) },
                onApplied = { e, c -> events.trySend(Event.Applied(e, c)) },
            )
            assertEquals(Event.Read(true, false), events.next())

            switch.request(false) // fails: nothing is published for it
            switch.request(true) // applies: the next publication is this one

            assertEquals(Event.Applied(true, true), events.next())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun scanStartsOnlyForAVerifiedOnState() {
        // A read that came back on starts a scan, even when the library loaded first.
        assertTrue(faceScanTrigger(known = null, enabled = true, changed = false))
        // A saved off never starts one. A failed read never reaches this function at all.
        assertFalse(faceScanTrigger(known = null, enabled = false, changed = false))
        // Turning on from a known off starts one.
        assertTrue(faceScanTrigger(known = true, enabled = true, changed = true))
        // Repeating the current on state doesn't.
        assertFalse(faceScanTrigger(known = true, enabled = true, changed = false))
        // Turning off never starts one.
        assertFalse(faceScanTrigger(known = true, enabled = false, changed = true))
        // After a failed read, a successful change to on starts one even though nothing "changed" here.
        assertTrue(faceScanTrigger(known = null, enabled = true, changed = false))
    }
}
