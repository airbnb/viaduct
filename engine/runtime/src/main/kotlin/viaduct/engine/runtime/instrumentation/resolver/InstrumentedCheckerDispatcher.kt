package viaduct.engine.runtime.instrumentation.resolver

import viaduct.engine.api.CheckerDispatcher
import viaduct.engine.api.CheckerExecutor
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.instrumentation.resolver.CheckerFunction
import viaduct.engine.api.instrumentation.resolver.ViaductResolverInstrumentation

/**
 * Wraps [viaduct.engine.api.CheckerDispatcher] to add instrumentation callbacks during checker execution.
 *
 * Delegates all operations to [dispatcher] except [execute], which creates instrumentation state
 * and tracks checker execution lifecycle for observability.
 */
class InstrumentedCheckerDispatcher(
    val dispatcher: CheckerDispatcher,
    val instrumentation: ViaductResolverInstrumentation
) : CheckerDispatcher by dispatcher {
    override suspend fun execute(
        arguments: Map<String, Any?>,
        objectDataMap: Map<String, EngineObjectData>,
        context: EngineExecutionContext,
        checkerType: CheckerExecutor.CheckerType
    ): CheckerResult {
        val metadata = dispatcher.checkerMetadata
            ?: return dispatcher.execute(arguments, objectDataMap, context, checkerType)

        val createStateParameter = ViaductResolverInstrumentation.CreateInstrumentationStateParameters()
        val state = instrumentation.createInstrumentationState(createStateParameter)

        val instrumentedObjectDataMap = objectDataMap.mapValues { (_, engineObjectData) ->
            InstrumentedEngineObjectData(engineObjectData, instrumentation, state)
        }

        val checkerExecuteParam = ViaductResolverInstrumentation.InstrumentExecuteCheckerParameters(
            checkerMetadata = metadata
        )

        val checker = CheckerFunction {
            dispatcher.execute(arguments, instrumentedObjectDataMap, context, checkerType)
        }

        val instrumentedChecker = instrumentation.instrumentAccessChecker(checker, checkerExecuteParam, state)

        return instrumentedChecker.check()
    }
}
