package viaduct.engine.runtime.instrumentation.resolver

import graphql.util.FpKit
import viaduct.engine.api.Coordinate
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.ExecutionAttribution
import viaduct.engine.api.instrumentation.resolver.ResolverFunction
import viaduct.engine.api.instrumentation.resolver.ResolverInstrumentationContext
import viaduct.engine.api.instrumentation.resolver.ViaductResolverInstrumentation
import viaduct.engine.runtime.EngineExecutionContextExtensions.copy
import viaduct.engine.runtime.EngineExecutionContextExtensions.dataFetchingEnvironment
import viaduct.engine.runtime.EngineExecutionContextExtensions.fieldScopeWithAttribution
import viaduct.engine.runtime.EngineObjectDataFactory
import viaduct.engine.runtime.FieldResolverDispatcher

/**
 * Wraps [FieldResolverDispatcher] to add instrumentation callbacks during resolver execution.
 *
 * Delegates all operations to [dispatcher] except [resolve], which creates instrumentation state
 * and passes [ResolverInstrumentationContext] into the factories for fetch-selection observability,
 * then wraps the created data with [InstrumentedEngineObjectData.Sync] for read observability.
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
        objectValueFactory: EngineObjectDataFactory,
        queryValueFactory: EngineObjectDataFactory,
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
        val instrumentationContext = ResolverInstrumentationContext(instrumentation, state)

        val instrumentedObjectFactory = EngineObjectDataFactory { resolverInstrumentationContext ->
            val fetchCtx = if (wrapFetchSelections) (resolverInstrumentationContext ?: instrumentationContext) else resolverInstrumentationContext
            val syncData = objectValueFactory.create(fetchCtx)
            InstrumentedEngineObjectData.Sync(syncData, instrumentation, state)
        }
        val instrumentedQueryFactory = EngineObjectDataFactory { resolverInstrumentationContext ->
            val fetchCtx = if (wrapFetchSelections) (resolverInstrumentationContext ?: instrumentationContext) else resolverInstrumentationContext
            val syncData = queryValueFactory.create(fetchCtx)
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
                    instrumentedObjectFactory,
                    instrumentedQueryFactory,
                    selections,
                    instrumentedContext
                )
            },
            resolverExecuteParam,
            state
        ).resolve()
    }
}
