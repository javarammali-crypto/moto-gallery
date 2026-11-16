package com.javaram.gallery.search

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Models the rule FaceIndexer relies on: a buffer handed to a native call is released only after the native
 * call reports back, even if the coroutine was cancelled meanwhile. A late result is then not used.
 */
class NativeAwaitTest {

    private class FakeBuffer {
        var released = false
    }

    @Test
    fun cancellationDoesNotReleaseBufferBeforeNativeCallFinishes() = runBlocking {
        var reportBack: ((Result<Int>) -> Unit)? = null
        val buffer = FakeBuffer()
        val submitted = CompletableDeferred<Unit>()
        var consumed = false

        val job: Job = launch {
            try {
                val result = awaitNative<Int> { done ->
                    reportBack = done
                    submitted.complete(Unit)
                }
                // Cancelled while the native call ran: the late result is dropped, not used.
                if (isActive) consumed = true
            } finally {
                buffer.released = true
            }
        }
        submitted.await()
        job.cancel()
        yield()

        assertFalse("buffer must stay alive while the native call runs", buffer.released)
        assertFalse(job.isCompleted)

        reportBack!!(Result.success(7))
        job.join()

        assertTrue(buffer.released)
        assertFalse("a result that arrives after cancellation must not be consumed", consumed)
        assertTrue(job.isCancelled)
    }

    @Test
    fun failureIsRethrownAfterCompletion() = runBlocking {
        var reportBack: ((Result<Int>) -> Unit)? = null
        val submitted = CompletableDeferred<Unit>()
        val outcome = CompletableDeferred<String?>()

        val job = launch {
            try {
                awaitNative<Int> { done ->
                    reportBack = done
                    submitted.complete(Unit)
                }
            } catch (e: IllegalStateException) {
                outcome.complete(e.message)
            }
        }
        submitted.await()
        reportBack!!(Result.failure(IllegalStateException("detector failed")))
        job.join()

        assertEquals("detector failed", outcome.await())
    }

    @Test
    fun resultIsReturnedWhenNotCancelled() = runBlocking {
        var reportBack: ((Result<String>) -> Unit)? = null
        val submitted = CompletableDeferred<Unit>()

        val waiting = async {
            awaitNative<String> { done ->
                reportBack = done
                submitted.complete(Unit)
            }
        }
        submitted.await()
        reportBack!!(Result.success("faces"))

        assertEquals("faces", waiting.await())
    }
}
