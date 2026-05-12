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
import viaduct.engine.runtime.EngineExecutionContextExtensions.dataFetchingEnvironment
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
            executionPath = context.dataFetchingEnvironment?.executionStepInfo?.path,
        )

        val wrapFetchSelections = instrumentation.shouldInstrumentFetchSelections(state)
        // Lazy object/query values are wrapped with InstrumentedEngineObjectData when
        // shouldInstrumentFetchSelections is true (debug / opt-in feature flag).
        // Sync getters are always wrapped with InstrumentedEngineObjectData.Sync so that
        // instrumentReadSelection fires for every field read — independent of the debug gate.
        // The withContext(ResolverInstrumentationContext) around the getter is scoped to
        // shouldInstrumentFetchSelections so that SyncEngineObjectDataFactory fires
        // instrumentFetchSelection during pre-resolution only when that path is enabled.
        val resolvedObjectValue = if (wrapFetchSelections) InstrumentedEngineObjectData(objectValue, instrumentation, state) else objectValue
        val resolvedQueryValue = if (wrapFetchSelections) InstrumentedEngineObjectData(queryValue, instrumentation, state) else queryValue
        val instrumentationContext = ResolverInstrumentationContext(instrumentation, state)
        val resolvedSyncObjectGetter: suspend () -> EngineObjectData.Sync = {
            val syncData = if (wrapFetchSelections) {
                withContext(instrumentationContext) { syncObjectValueGetter() }
            } else {
                syncObjectValueGetter()
            }
            InstrumentedEngineObjectData.Sync(syncData, instrumentation, state)
        }
        val resolvedSyncQueryGetter: suspend () -> EngineObjectData.Sync = {
            val syncData = if (wrapFetchSelections) {
                withContext(instrumentationContext) { syncQueryValueGetter() }
            } else {
                syncQueryValueGetter()
            }
            InstrumentedEngineObjectData.Sync(syncData, instrumentation, state)
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
                dispatcher.resolve(
                    arguments,
                    resolvedObjectValue,
                    resolvedQueryValue,
                    resolvedSyncObjectGetter,
                    resolvedSyncQueryGetter,
                    selections,
                    instrumentedContext
                )
            },
            resolverExecuteParam,
            state
        ).resolve()
    }
}
