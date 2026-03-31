package viaduct.engine.runtime.instrumentation.resolver

import graphql.util.FpKit
import kotlinx.coroutines.withContext
import viaduct.engine.api.Coordinate
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.ExecutionAttribution
import viaduct.engine.api.instrumentation.resolver.ResolverFunction
import viaduct.engine.api.instrumentation.resolver.ResolverInstrumentationContext
import viaduct.engine.api.instrumentation.resolver.ViaductResolverInstrumentation
import viaduct.engine.runtime.EngineExecutionContextExtensions.copy
import viaduct.engine.runtime.EngineExecutionContextExtensions.fieldScopeWithAttribution
import viaduct.engine.runtime.FieldResolverDispatcher

/**
 * Wraps [FieldResolverDispatcher] to add instrumentation callbacks during resolver execution.
 *
 * Delegates all operations to [dispatcher] except [resolve], which creates instrumentation state
 * and wraps the object/query values with [InstrumentedEngineObjectData] for observability.
 */
class InstrumentedFieldResolverDispatcher(
    val dispatcher: FieldResolverDispatcher,
    val instrumentation: ViaductResolverInstrumentation,
    val coordinate: Coordinate? = null,
    val syncValueComputation: Boolean = false,
) : FieldResolverDispatcher {
    override val objectSelectionSet get() = dispatcher.objectSelectionSet
    override val querySelectionSet get() = dispatcher.querySelectionSet
    override val hasRequiredSelectionSets get() = dispatcher.hasRequiredSelectionSets
    override val resolverMetadata get() = dispatcher.resolverMetadata

    override suspend fun resolve(
        arguments: Map<String, Any?>,
        objectValue: EngineObjectData,
        queryValue: EngineObjectData,
        syncObjectValueGetter: suspend () -> EngineObjectData.Sync,
        syncQueryValueGetter: suspend () -> EngineObjectData.Sync,
        selections: EngineSelectionSet?,
        context: EngineExecutionContext
    ): Any? {
        val createStateParameter = ViaductResolverInstrumentation.CreateInstrumentationStateParameters()
        val state = instrumentation.createInstrumentationState(createStateParameter)

        val resolverExecuteParam = ViaductResolverInstrumentation.InstrumentExecuteResolverParameters(
            resolverMetadata = dispatcher.resolverMetadata,
            fieldCoordinate = coordinate,
            syncValueComputation = syncValueComputation,
        )

        val wrapFetchSelections = instrumentation.shouldInstrumentFetchSelections(state)
        val instrumentedObjectValue = if (wrapFetchSelections) InstrumentedEngineObjectData(objectValue, instrumentation, state) else objectValue
        val instrumentedQueryValue = if (wrapFetchSelections) InstrumentedEngineObjectData(queryValue, instrumentation, state) else queryValue
        val instrumentationContext = ResolverInstrumentationContext(instrumentation, state)
        val instrumentedSyncObjectValue: suspend () -> EngineObjectData.Sync = if (wrapFetchSelections) {
            { withContext(instrumentationContext) { InstrumentedEngineObjectData.Sync(syncObjectValueGetter(), instrumentation, state) } }
        } else {
            syncObjectValueGetter
        }
        val instrumentedSyncQueryValue: suspend () -> EngineObjectData.Sync = if (wrapFetchSelections) {
            { withContext(instrumentationContext) { InstrumentedEngineObjectData.Sync(syncQueryValueGetter(), instrumentation, state) } }
        } else {
            syncQueryValueGetter
        }

        val contextWithAttribution = context.copy(
            fieldScopeSupplier = FpKit.intraThreadMemoize {
                context.fieldScopeWithAttribution(ExecutionAttribution.fromResolver(resolverMetadata.name))
            }
        )

        return instrumentation.instrumentResolverExecution(
            ResolverFunction {
                dispatcher.resolve(
                    arguments,
                    instrumentedObjectValue,
                    instrumentedQueryValue,
                    instrumentedSyncObjectValue,
                    instrumentedSyncQueryValue,
                    selections,
                    contextWithAttribution
                )
            },
            resolverExecuteParam,
            state
        ).resolve()
    }
}
