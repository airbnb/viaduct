package viaduct.remote

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.runtime.mocks.ContextMocks
import viaduct.remote.grpc.CallbackRequest
import viaduct.remote.grpc.CallbackResponse
import viaduct.remote.grpc.SerializedSelectionSet

/**
 * Tests [driveClientStream] directly against fake, resolver-kind-agnostic message types --
 * proving the client-side stream-driving mechanics (initial-message send, callback-request
 * handling against a real local [viaduct.engine.api.EngineExecutionContext], and terminal
 * resolve_response return) independent of the real proto messages or any node/field proxy
 * executor.
 */
class RemoteResolverStreamClientTest {
    private sealed class In {
        data class CallbackReq(val request: CallbackRequest) : In()

        data class Resp(val value: String) : In()
    }

    private sealed class Out {
        data class Req(val value: String) : Out()

        data class CallbackResp(val response: CallbackResponse) : Out()
    }

    @Test
    fun `the initial message is sent and the terminal resolve_response is returned`() =
        runTest {
            val context = ContextMocks().engineExecutionContext
            val response = driveClientStream(
                initial = Out.Req("hello"),
                context = context,
                wrapCallbackResponse = { Out.CallbackResp(it) },
                callbackRequestOrNull = { (it as? In.CallbackReq)?.request },
                resolveResponseOrNull = { (it as? In.Resp)?.value }
            ) { outgoing: Flow<Out> ->
                flow<In> {
                    // Take only the initial message -- outgoing isn't closed until this returned
                    // flow emits a matching resolve_response below, so collecting it to completion
                    // here would deadlock.
                    assertEquals(Out.Req("hello"), outgoing.first())
                    emit(In.Resp("done"))
                }
            }

            assertEquals("done", response)
        }

    @Test
    fun `a callback_request is answered against the real local context and the answer is sent back`() =
        runTest {
            // ContextMocks runs over a no-op engine, so the real resolveSelectionSet call this
            // drives cannot complete -- but that's enough to prove the callback fired and its
            // (error) answer round-tripped back out, which is what this wireframe is responsible
            // for. A follow-up PR (node/field proxy executors) exercises the success path against
            // a real engine end-to-end.
            val context = ContextMocks().engineExecutionContext
            val callbackRequest = CallbackRequest.newBuilder()
                .setCorrelationId("cb-0")
                .setSelections(SerializedSelectionSet.newBuilder().setType("Query").setDocument("empty").build())
                .build()

            val response = driveClientStream(
                initial = Out.Req("hello"),
                context = context,
                wrapCallbackResponse = { Out.CallbackResp(it) },
                callbackRequestOrNull = { (it as? In.CallbackReq)?.request },
                resolveResponseOrNull = { (it as? In.Resp)?.value }
            ) { outgoing: Flow<Out> ->
                channelFlow<In> {
                    // Send the callback_request as if the server had, then watch this client's own
                    // outbound flow for its answer -- driveClientStream launches a coroutine to
                    // handle the callback and send the answer back onto the same channel `outgoing`
                    // is backed by, so collecting it here observes that answer once it arrives.
                    send(In.CallbackReq(callbackRequest))
                    outgoing.collect { message ->
                        if (message is Out.CallbackResp) {
                            assertEquals("cb-0", message.response.correlationId)
                            assertTrue(message.response.hasError(), "expected an error CallbackResponse under ContextMocks' no-op engine")
                            send(In.Resp("done"))
                            close()
                        }
                    }
                }
            }

            assertEquals("done", response)
        }

    @Test
    fun `a callback_request with a blank document builds an empty selection set instead of failing to parse`() =
        runTest {
            // SelectionsParser.parse throws on a blank document, so if handleCallbackRequest didn't
            // special-case it (mirroring RemoteResolverServiceImpl's reconstructSelections), this
            // would fail with the parser's IllegalArgumentException before ever reaching
            // resolveSelectionSet. Asserting the error type is instead ContextMocks' NoOpEngine
            // failure proves an EmptyEngineSelectionSet was built and passed through successfully.
            val context = ContextMocks().engineExecutionContext
            val callbackRequest = CallbackRequest.newBuilder()
                .setCorrelationId("cb-0")
                .setSelections(SerializedSelectionSet.newBuilder().setType("Query").setDocument("").build())
                .build()

            val response = driveClientStream(
                initial = Out.Req("hello"),
                context = context,
                wrapCallbackResponse = { Out.CallbackResp(it) },
                callbackRequestOrNull = { (it as? In.CallbackReq)?.request },
                resolveResponseOrNull = { (it as? In.Resp)?.value }
            ) { outgoing: Flow<Out> ->
                channelFlow<In> {
                    send(In.CallbackReq(callbackRequest))
                    outgoing.collect { message ->
                        if (message is Out.CallbackResp) {
                            assertTrue(message.response.hasError())
                            assertEquals(
                                "viaduct.engine.api.SubqueryExecutionException",
                                message.response.error.errorType,
                                "expected the failure to originate from resolveSelectionSet (NoOpEngine), not selection-set parsing"
                            )
                            send(In.Resp("done"))
                            close()
                        }
                    }
                }
            }

            assertEquals("done", response)
        }
}
