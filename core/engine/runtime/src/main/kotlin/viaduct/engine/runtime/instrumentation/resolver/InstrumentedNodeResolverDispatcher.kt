package viaduct.engine.runtime.instrumentation.resolver

import graphql.util.FpKit
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.ExecutionAttribution
import viaduct.engine.api.instrumentation.resolver.ResolverFunction
import viaduct.engine.api.instrumentation.resolver.ViaductResolverInstrumentation
import viaduct.engine.runtime.EngineExecutionContextExtensions.copy
import viaduct.engine.runtime.EngineExecutionContextExtensions.fieldScopeWithAttribution
import viaduct.engine.runtime.NodeResolverDispatcher

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
        selections: EngineSelectionSet,
        context: EngineExecutionContext
    ): EngineObjectData {
        val createStateParameter = ViaductResolverInstrumentation.CreateInstrumentationStateParameters()
        val state = instrumentation.createInstrumentationState(createStateParameter)

        val resolverExecuteParam = ViaductResolverInstrumentation.InstrumentExecuteResolverParameters(
            resolverMetadata = dispatcher.resolverMetadata
        )

        val implWithAttribution = context.copy(
            fieldScopeSupplier = FpKit.intraThreadMemoize {
                context.fieldScopeWithAttribution(ExecutionAttribution.fromResolver(resolverMetadata.name))
            }
        )
        val instrumentedContext = InstrumentedEngineExecutionContext(implWithAttribution, instrumentation, state)

        val instrumentedResolver = instrumentation.instrumentResolverExecution(
            resolver = ResolverFunction { dispatcher.resolve(id, selections, instrumentedContext) },
            parameters = resolverExecuteParam,
            state = state
        )

        return instrumentedResolver.resolve()
    }
}
