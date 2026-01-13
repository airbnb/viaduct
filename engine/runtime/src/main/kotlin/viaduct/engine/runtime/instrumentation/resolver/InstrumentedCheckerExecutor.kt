package viaduct.engine.runtime.instrumentation.resolver

import viaduct.engine.api.CheckerExecutor
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.instrumentation.resolver.CheckerFunction
import viaduct.engine.api.instrumentation.resolver.ViaductResolverInstrumentation

/**
 * Wraps a [CheckerExecutor] to add instrumentation callbacks during checker execution.
 *
 * This class decorates the underlying executor by:
 * 1. Creating instrumentation state before execution
 * 2. Wrapping object data with instrumented versions
 * 3. Invoking instrumentation callbacks around checker execution
 */
class InstrumentedCheckerExecutor(
    private val executor: CheckerExecutor,
    private val instrumentation: ViaductResolverInstrumentation
) : CheckerExecutor {
    override val requiredSelectionSets get() = executor.requiredSelectionSets
    override val checkerMetadata get() = executor.checkerMetadata

    override suspend fun execute(
        arguments: Map<String, Any?>,
        objectDataMap: Map<String, EngineObjectData>,
        context: EngineExecutionContext,
        checkerType: CheckerExecutor.CheckerType
    ): CheckerResult {
        val metadata = checkerMetadata
            ?: return executor.execute(arguments, objectDataMap, context, checkerType)

        val createStateParameter = ViaductResolverInstrumentation.CreateInstrumentationStateParameters()
        val state = instrumentation.createInstrumentationState(createStateParameter)

        val instrumentedObjectDataMap = objectDataMap.mapValues { (_, engineObjectData) ->
            InstrumentedEngineObjectData(engineObjectData, instrumentation, state)
        }

        val checkerExecuteParam = ViaductResolverInstrumentation.InstrumentExecuteCheckerParameters(
            checkerMetadata = metadata
        )

        val checker = CheckerFunction {
            executor.execute(arguments, instrumentedObjectDataMap, context, checkerType)
        }

        val instrumentedChecker = instrumentation.instrumentAccessChecker(checker, checkerExecuteParam, state)

        return instrumentedChecker.check()
    }
}
