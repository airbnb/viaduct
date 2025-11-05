package viaduct.engine.runtime.instrumentation.resolver

import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.NodeResolverDispatcher
import viaduct.engine.api.RawSelectionSet
import viaduct.engine.api.instrumentation.resolver.ResolverFunction
import viaduct.engine.api.instrumentation.resolver.ViaductResolverInstrumentation

/**
 * Wraps [NodeResolverDispatcher] to add instrumentation callbacks during resolver execution.
 *
 * Delegates all operations to [dispatcher] except [resolve], which creates instrumentation state
 * for observability during node resolution.
 */
class InstrumentedNodeResolverDispatcher(
    val dispatcher: NodeResolverDispatcher,
    val instrumentation: ViaductResolverInstrumentation
) : NodeResolverDispatcher {
    override val resolverMetadata get() = dispatcher.resolverMetadata

    override suspend fun resolve(
        id: String,
        selections: RawSelectionSet,
        context: EngineExecutionContext
    ): EngineObjectData {
        val createStateParameter = ViaductResolverInstrumentation.CreateInstrumentationStateParameters()
        val state = instrumentation.createInstrumentationState(createStateParameter)

        val resolverExecuteParam = ViaductResolverInstrumentation.InstrumentExecuteResolverParameters(
            resolverMetadata = dispatcher.resolverMetadata
        )

        val instrumentedResolver = instrumentation.instrumentResolverExecution(
            resolver = ResolverFunction { dispatcher.resolve(id, selections, context) },
            parameters = resolverExecuteParam,
            state = state
        )

        return instrumentedResolver.resolve()
    }
}
