package viaduct.remote

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.remote.grpc.CallbackRequest
import viaduct.remote.grpc.CallbackResponse
import viaduct.remote.grpc.SerializedSelectionSet

/**
 * Tests [withStreamedCallbacks] directly against fake, resolver-kind-agnostic message types --
 * proving the stream lifecycle (first-message parsing, dispatcher wiring, callback round-trip,
 * reader-job cleanup) independent of the real proto messages or any node/field resolution logic.
 */
class WithStreamedCallbacksTest {
    private sealed class In {
        data class Req(val value: String) : In()

        data class CallbackResp(val response: CallbackResponse) : In()
    }

    private sealed class Out {
        data class CallbackReq(val request: CallbackRequest) : Out()

        data class Resp(val value: String) : Out()
    }

    private fun serializedSelectionSet(type: String) = SerializedSelectionSet.newBuilder().setType(type).build()

    @Test
    fun `the block's resolve response is emitted on the outbound flow`() =
        runTest {
            val incoming = listOf<In>(In.Req("hello"))
            val outbound: Flow<Out> = channelFlow {
                withStreamedCallbacks(
                    requests = incoming.asFlow(),
                    resolveRequestOrNull = { (it as? In.Req)?.value },
                    callbackResponseOrNull = { (it as? In.CallbackResp)?.response },
                    wrapCallbackRequest = { Out.CallbackReq(it) }
                ) { request, _ ->
                    send(Out.Resp("echo:$request"))
                }
            }

            val results = outbound.toList()
            assertEquals(listOf(Out.Resp("echo:hello")), results)
        }

    @Test
    fun `a callback issued from the block round-trips through the reader job`() =
        runTest {
            // A CallbackResp answering the callback the block is about to issue -- only delivered
            // to the reader job once the block's dispatcher.call() has sent the request, so this
            // also proves synchronous pending-registration (no subscribe-before-emit race).
            val incoming = Channel<In>(Channel.UNLIMITED)
            incoming.trySend(In.Req("hello"))

            val outbound: Flow<Out> = channelFlow {
                withStreamedCallbacks(
                    requests = incoming.consumeAsFlow(),
                    resolveRequestOrNull = { (it as? In.Req)?.value },
                    callbackResponseOrNull = { (it as? In.CallbackResp)?.response },
                    wrapCallbackRequest = { Out.CallbackReq(it) }
                ) { request, dispatcher ->
                    val response = dispatcher.call(serializedSelectionSet("Query"), resolverId = "$request.field", isMutation = false)
                    send(Out.Resp("callback-result:${response.correlationId}"))
                }
            }

            val results = mutableListOf<Out>()
            outbound.collect { message ->
                results.add(message)
                if (message is Out.CallbackReq) {
                    // Answer the callback the block just issued, correlated by id.
                    incoming.trySend(In.CallbackResp(CallbackResponse.newBuilder().setCorrelationId(message.request.correlationId).build()))
                }
            }

            assertEquals(2, results.size)
            val callbackReq = results[0] as Out.CallbackReq
            assertEquals("hello.field", callbackReq.request.resolverId)
            assertEquals(Out.Resp("callback-result:${callbackReq.request.correlationId}"), results[1])
        }

    @Test
    fun `the outbound flow completes even when the client keeps its send side open`() =
        runTest {
            // Never closed -- simulates a client that keeps its send side open after receiving
            // the response, instead of ending the requests flow on its own.
            val incoming = Channel<In>(Channel.UNLIMITED)
            incoming.trySend(In.Req("hello"))

            val outbound: Flow<Out> = channelFlow {
                withStreamedCallbacks(
                    requests = incoming.consumeAsFlow(),
                    resolveRequestOrNull = { (it as? In.Req)?.value },
                    callbackResponseOrNull = { (it as? In.CallbackResp)?.response },
                    wrapCallbackRequest = { Out.CallbackReq(it) }
                ) { request, _ ->
                    send(Out.Resp("echo:$request"))
                }
            }

            val results = outbound.toList()
            assertEquals(listOf(Out.Resp("echo:hello")), results)
        }

    @Test
    fun `a stream whose first message isn't resolve_request fails fast`() =
        runTest {
            val incoming = listOf<In>(In.CallbackResp(CallbackResponse.getDefaultInstance()))
            val outbound: Flow<Out> = channelFlow {
                withStreamedCallbacks(
                    requests = incoming.asFlow(),
                    resolveRequestOrNull = { (it as? In.Req)?.value },
                    callbackResponseOrNull = { (it as? In.CallbackResp)?.response },
                    wrapCallbackRequest = { Out.CallbackReq(it) }
                ) { _, _ -> }
            }

            val error = runCatching { outbound.toList() }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException, "expected IllegalArgumentException, got $error")
        }

    private fun <T> List<T>.asFlow(): Flow<T> = kotlinx.coroutines.flow.flow { forEach { emit(it) } }
}
