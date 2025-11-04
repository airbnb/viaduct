package viaduct.engine.runtime.instrumentation.resolver

import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.FieldResolverDispatcher
import viaduct.engine.api.RawSelectionSet
import viaduct.engine.api.instrumentation.resolver.ResolverFunction
import viaduct.engine.api.instrumentation.resolver.ViaductResolverInstrumentation

/**
 * Wraps [viaduct.engine.api.FieldResolverDispatcher] to add instrumentation callbacks during resolver execution.
 *
 * Delegates all operations to [dispatcher] except [resolve], which creates instrumentation state
 * and wraps the object/query values with [InstrumentedEngineObjectData] for observability.
 */
class InstrumentedFieldResolverDispatcher(
    val dispatcher: FieldResolverDispatcher,
    val instrumentation: ViaductResolverInstrumentation
) : FieldResolverDispatcher {
    override val objectSelectionSet get() = dispatcher.objectSelectionSet
    override val querySelectionSet get() = dispatcher.querySelectionSet
    override val hasRequiredSelectionSets get() = dispatcher.hasRequiredSelectionSets
    override val resolverMetadata get() = dispatcher.resolverMetadata

    override suspend fun resolve(
        arguments: Map<String, Any?>,
        objectValue: EngineObjectData,
        queryValue: EngineObjectData,
        selections: RawSelectionSet?,
        context: EngineExecutionContext
    ): Any? {
        val createStateParameter = ViaductResolverInstrumentation.CreateInstrumentationStateParameters()
        val state = instrumentation.createInstrumentationState(createStateParameter)

        val resolverExecuteParam = ViaductResolverInstrumentation.InstrumentExecuteResolverParameters(
            resolverMetadata = dispatcher.resolverMetadata
        )

        val instrumentedObjectValue = InstrumentedEngineObjectData(objectValue, instrumentation, state)
        val instrumentedQueryValue = InstrumentedEngineObjectData(queryValue, instrumentation, state)

        return instrumentation.instrumentResolverExecution(
            ResolverFunction {
                dispatcher.resolve(arguments, instrumentedObjectValue, instrumentedQueryValue, selections, context)
            },
            resolverExecuteParam,
            state
        ).resolve()
    }
}
