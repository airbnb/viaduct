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
    override val isSelective get() = dispatcher.isSelective
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
        // Wrap both lazy and sync object/query values with instrumentation when enabled,
        // regardless of whether this is a sync or lazy resolver. Lazy values are wrapped
        // with InstrumentedEngineObjectData (async path). Sync getters are wrapped via
        // withContext(ResolverInstrumentationContext) so that SyncEngineObjectDataFactory
        // fires instrumentFetchSelection per field during pre-resolution. withContext is
        // scoped only to the getter call — not the resolver body — so exceptions thrown
        // by the resolver after fetching data do not cross a withContext boundary and
        // preserve their identity through Kotlin's stack trace recovery.
        val resolvedObjectValue = if (wrapFetchSelections) InstrumentedEngineObjectData(objectValue, instrumentation, state) else objectValue
        val resolvedQueryValue = if (wrapFetchSelections) InstrumentedEngineObjectData(queryValue, instrumentation, state) else queryValue
        val instrumentationContext = ResolverInstrumentationContext(instrumentation, state)
        val resolvedSyncObjectGetter: suspend () -> EngineObjectData.Sync = if (wrapFetchSelections) {
            {
                val syncData = withContext(instrumentationContext) { syncObjectValueGetter() }
                InstrumentedEngineObjectData.Sync(syncData, instrumentation, state)
            }
        } else {
            syncObjectValueGetter
        }
        val resolvedSyncQueryGetter: suspend () -> EngineObjectData.Sync = if (wrapFetchSelections) {
            {
                val syncData = withContext(instrumentationContext) { syncQueryValueGetter() }
                InstrumentedEngineObjectData.Sync(syncData, instrumentation, state)
            }
        } else {
            syncQueryValueGetter
        }
        val implWithAttribution = context.copy(
            fieldScopeSupplier = FpKit.intraThreadMemoize {
                context.fieldScopeWithAttribution(ExecutionAttribution.fromResolver(resolverMetadata.name))
            },
        )
        val instrumentedContext = if (wrapFetchSelections) {
            InstrumentedEngineExecutionContext(implWithAttribution, instrumentation, state)
        } else {
            implWithAttribution
        }

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
                        instrumentedContext
                    )
                }
                if (syncValueComputation && wrapFetchSelections) withContext(instrumentationContext) { resolve() } else resolve()
            },
            resolverExecuteParam,
            state
        ).resolve()
    }
}
