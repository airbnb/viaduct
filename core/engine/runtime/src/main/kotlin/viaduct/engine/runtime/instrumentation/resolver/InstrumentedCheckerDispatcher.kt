package viaduct.engine.runtime.instrumentation.resolver

import viaduct.engine.api.CheckerResult
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.instrumentation.resolver.ResolverInstrumentationContext
import viaduct.engine.api.instrumentation.resolver.ViaductResolverInstrumentation
import viaduct.engine.api.spi.CheckerExecutor
import viaduct.engine.runtime.CheckerDispatcher
import viaduct.engine.runtime.EngineObjectDataFactory

/**
 * Wraps [CheckerDispatcher] to add resolver instrumentation during checker data materialization.
 *
 * GJ instrumentation owns the outer access-check execution timing. This dispatcher owns resolver
 * fetch/read instrumentation for the EngineObjectData materialized for checker RSS fragments.
 */
class InstrumentedCheckerDispatcher(
    private val dispatcher: CheckerDispatcher,
    private val instrumentation: ViaductResolverInstrumentation
) : CheckerDispatcher by dispatcher {
    override val executor: CheckerExecutor = dispatcher.executor

    override suspend fun execute(
        arguments: Map<String, Any?>,
        objectDataFactories: Map<String, EngineObjectDataFactory>,
        context: EngineExecutionContext,
        checkerType: CheckerExecutor.CheckerType
    ): CheckerResult {
        val createStateParameter = ViaductResolverInstrumentation.CreateInstrumentationStateParameters()
        val state = instrumentation.createInstrumentationState(createStateParameter)
        val wrapFetchSelections = instrumentation.shouldInstrumentFetchSelections(state)
        val instrumentationContext = ResolverInstrumentationContext(instrumentation, state)
        val instrumentedObjectDataFactories = objectDataFactories.mapValues { (_, factory) ->
            EngineObjectDataFactory {
                val fetchCtx = if (wrapFetchSelections) instrumentationContext else null
                val syncData = factory.create(fetchCtx)
                InstrumentedEngineObjectData.Sync(syncData, instrumentation, state)
            }
        }
        return dispatcher.execute(arguments, instrumentedObjectDataFactories, context, checkerType)
    }
}
