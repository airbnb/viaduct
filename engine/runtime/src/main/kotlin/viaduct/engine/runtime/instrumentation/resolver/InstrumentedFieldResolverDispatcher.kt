package viaduct.engine.runtime.instrumentation.resolver

import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.FieldResolverDispatcher
import viaduct.engine.api.RawSelectionSet
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
        val state = SafeInstrumentation.execute {
            instrumentation.createInstrumentationState(createStateParameter)
        } ?: return dispatcher.resolve(arguments, objectValue, queryValue, selections, context)

        val resolverExecuteParam = ViaductResolverInstrumentation.InstrumentExecuteResolverParameters(
            resolverMetadata = dispatcher.resolverMetadata
        )
        val resolverExecuteCtx = SafeInstrumentation.execute {
            instrumentation.beginExecuteResolver(parameters = resolverExecuteParam, state = state)
        } ?: return dispatcher.resolve(arguments, objectValue, queryValue, selections, context)

        val instrumentedObjectValue = InstrumentedEngineObjectData(objectValue, instrumentation, state)
        val instrumentedQueryValue = InstrumentedEngineObjectData(queryValue, instrumentation, state)

        try {
            val result = dispatcher.resolve(arguments, instrumentedObjectValue, instrumentedQueryValue, selections, context)
            SafeInstrumentation.execute { resolverExecuteCtx.onCompleted(result, null) }
            return result
        } catch (e: Exception) {
            SafeInstrumentation.execute { resolverExecuteCtx.onCompleted(null, e) }
            throw e
        }
    }
}
