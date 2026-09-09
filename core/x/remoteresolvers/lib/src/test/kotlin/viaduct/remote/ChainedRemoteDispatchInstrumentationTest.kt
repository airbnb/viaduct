package viaduct.remote

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import viaduct.engine.api.ResolverMetadata
import viaduct.engine.api.ResolverType
import viaduct.remote.api.spi.ChainedRemoteDispatchInstrumentation
import viaduct.remote.api.spi.RemoteDispatchInstrumentation
import viaduct.remote.api.spi.RemoteDispatchInstrumentationContext

class ChainedRemoteDispatchInstrumentationTest {
    private class RecordingInstrumentation(
        private val name: String,
        private val events: MutableList<String>
    ) : RemoteDispatchInstrumentation {
        override fun beginRemoteDispatch(parameters: RemoteDispatchInstrumentation.BeginRemoteDispatchParameters): RemoteDispatchInstrumentationContext {
            events.add("$name.begin")
            return object : RemoteDispatchInstrumentationContext {
                override fun onSerializationCompleted(error: Throwable?) {
                    events.add("$name.serialization")
                }

                override fun onResponseReceived(
                    response: RemoteDispatchInstrumentationContext.RemoteDispatchResponse?,
                    error: Throwable?
                ) {
                    events.add("$name.response")
                }

                override fun onDeserializationCompleted(error: Throwable?) {
                    events.add("$name.deserialization")
                }

                override fun onCompleted(
                    outcome: RemoteDispatchInstrumentationContext.RemoteDispatchOutcome,
                    cause: Throwable?
                ) {
                    events.add("$name.completed:${outcome.name}")
                }
            }
        }
    }

    @Test
    fun `every checkpoint fans out to each chained instrumentation in order`() {
        val events = mutableListOf<String>()
        val chained = ChainedRemoteDispatchInstrumentation(
            listOf(RecordingInstrumentation("first", events), RecordingInstrumentation("second", events))
        )
        val resolverMetadata = ResolverMetadata.forModern("TestResolver", ResolverType.FIELD)

        val dispatch = chained.beginRemoteDispatch(RemoteDispatchInstrumentation.BeginRemoteDispatchParameters(resolverMetadata))
        dispatch.onSerializationCompleted(null)
        dispatch.onResponseReceived(null, null)
        dispatch.onDeserializationCompleted(null)
        dispatch.onCompleted(RemoteDispatchInstrumentationContext.RemoteDispatchOutcome.SUCCESS, null)

        assertEquals(
            listOf(
                "first.begin",
                "second.begin",
                "first.serialization",
                "second.serialization",
                "first.response",
                "second.response",
                "first.deserialization",
                "second.deserialization",
                "first.completed:SUCCESS",
                "second.completed:SUCCESS",
            ),
            events
        )
    }
}
