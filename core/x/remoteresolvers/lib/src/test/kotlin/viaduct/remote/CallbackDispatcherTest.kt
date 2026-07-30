package viaduct.remote

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.remote.grpc.CallbackResponse
import viaduct.remote.grpc.SerializedSelectionSet

class CallbackDispatcherTest {
    @Test
    fun `a pending call fails instead of hanging when the stream ends`() =
        runTest {
            val dispatcher = CallbackDispatcher { /* never responds -- simulates a stream that goes silent */ }
            // runCatching inside the async, not around await(): an uncaught failure in the async
            // itself would cancel this whole test scope via structured concurrency, regardless of
            // whether the caller wraps await() -- catching inside is what keeps it contained.
            val pendingCall = async {
                runCatching { dispatcher.call(SerializedSelectionSet.getDefaultInstance(), resolverId = "Query.field", isMutation = false) }
            }
            yield() // let call() register its pending entry before we fail it

            dispatcher.failAllPending(IllegalStateException("stream closed"))

            val error = pendingCall.await().exceptionOrNull()
            assertTrue(error is IllegalStateException)
            assertEquals("stream closed", error?.message)
        }

    @Test
    fun `a call already completed by a response is unaffected by a later failAllPending`() =
        runTest {
            val dispatcher = CallbackDispatcher { }
            val pendingCall = async {
                dispatcher.call(SerializedSelectionSet.getDefaultInstance(), resolverId = "Query.field", isMutation = false)
            }
            yield()

            dispatcher.onCallbackResponse(
                CallbackResponse.newBuilder().setCorrelationId("cb-0").build()
            )
            val response = pendingCall.await()
            assertEquals("cb-0", response.correlationId)

            // No exception -- the entry was already removed by onCallbackResponse, so this is a no-op.
            dispatcher.failAllPending(IllegalStateException("stream closed"))
        }

    @Test
    fun `a call started after failAllPending fails fast instead of hanging forever`() =
        runTest {
            val dispatcher = CallbackDispatcher { /* never responds */ }
            dispatcher.failAllPending(IllegalStateException("stream closed"))

            val error = runCatching {
                dispatcher.call(SerializedSelectionSet.getDefaultInstance(), resolverId = "Query.field", isMutation = false)
            }.exceptionOrNull()

            assertTrue(error is IllegalStateException)
            assertEquals("stream closed", error?.message)
        }

    @Test
    fun `a caller that cancels while awaiting is removed from pending instead of leaking`() =
        runTest {
            val dispatcher = CallbackDispatcher { /* never responds */ }
            val pendingCall = async {
                dispatcher.call(SerializedSelectionSet.getDefaultInstance(), resolverId = "Query.field", isMutation = false)
            }
            yield() // let call() register its pending entry before we cancel it
            assertEquals(1, dispatcher.pendingCount)

            pendingCall.cancel()
            pendingCall.join()

            assertEquals(0, dispatcher.pendingCount)
        }

    @Test
    fun `failAllPending fails every concurrently pending call`() =
        runTest {
            val dispatcher = CallbackDispatcher { }
            val calls = (1..3).map {
                async {
                    runCatching { dispatcher.call(SerializedSelectionSet.getDefaultInstance(), resolverId = "Query.field$it", isMutation = false) }
                }
            }
            yield()

            dispatcher.failAllPending(IllegalStateException("stream closed"))

            calls.forEach { call ->
                assertTrue(call.await().exceptionOrNull() is IllegalStateException)
            }
        }
}
