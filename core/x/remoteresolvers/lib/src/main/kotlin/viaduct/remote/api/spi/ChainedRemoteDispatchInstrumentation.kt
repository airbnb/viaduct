package viaduct.remote.api.spi

/**
 * Composite [RemoteDispatchInstrumentation] that fans one dispatch out to multiple implementations,
 * in order, mirroring [viaduct.engine.api.instrumentation.resolver.ViaductResolverInstrumentation]'s
 * chaining pattern.
 */
class ChainedRemoteDispatchInstrumentation(
    private val instrumentations: List<RemoteDispatchInstrumentation>
) : RemoteDispatchInstrumentation {
    override fun beginRemoteDispatch(parameters: RemoteDispatchInstrumentation.BeginRemoteDispatchParameters): RemoteDispatchInstrumentationContext {
        val contexts = instrumentations.map { it.beginRemoteDispatch(parameters) }
        return object : RemoteDispatchInstrumentationContext {
            override fun onSerializationCompleted(error: Throwable?) {
                contexts.forEach { it.onSerializationCompleted(error) }
            }

            override fun onResponseReceived(
                response: RemoteDispatchInstrumentationContext.RemoteDispatchResponse?,
                error: Throwable?
            ) {
                contexts.forEach { it.onResponseReceived(response, error) }
            }

            override fun onDeserializationCompleted(error: Throwable?) {
                contexts.forEach { it.onDeserializationCompleted(error) }
            }

            override fun onCompleted(
                outcome: RemoteDispatchInstrumentationContext.RemoteDispatchOutcome,
                cause: Throwable?
            ) {
                contexts.forEach { it.onCompleted(outcome, cause) }
            }
        }
    }
}
