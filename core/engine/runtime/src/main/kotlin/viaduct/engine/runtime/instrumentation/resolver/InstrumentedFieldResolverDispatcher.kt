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

        val instrumentationContext = ResolverInstrumentationContext(instrumentation, state)
        val wrapFetchSelections = instrumentation.shouldInstrumentFetchSelections(state)
        // In the sync path, ResolverInstrumentationContext is installed once around the entire
        // dispatcher.resolve() call, so individual getters don't need wrapping.
        val wrap = !syncValueComputation && wrapFetchSelections
        val resolvedObjectValue = if (wrap) InstrumentedEngineObjectData(objectValue, instrumentation, state) else objectValue
        val resolvedQueryValue = if (wrap) InstrumentedEngineObjectData(queryValue, instrumentation, state) else queryValue
        val resolvedSyncObjectGetter: suspend () -> EngineObjectData.Sync = if (wrap) {
            { InstrumentedEngineObjectData.Sync(syncObjectValueGetter(), instrumentation, state) }
        } else {
            syncObjectValueGetter
        }
        val resolvedSyncQueryGetter: suspend () -> EngineObjectData.Sync = if (wrap) {
            { InstrumentedEngineObjectData.Sync(syncQueryValueGetter(), instrumentation, state) }
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
                val resolve = suspend {
                    dispatcher.resolve(
                        arguments,
                        resolvedObjectValue,
                        resolvedQueryValue,
                        resolvedSyncObjectGetter,
                        resolvedSyncQueryGetter,
                        selections,
                        contextWithAttribution
                    )
                }
                if (syncValueComputation && wrapFetchSelections) withContext(instrumentationContext) { resolve() } else resolve()
            },
            resolverExecuteParam,
            state
        ).resolve()
    }
}
