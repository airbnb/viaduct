package viaduct.engine.runtime

import viaduct.engine.api.CheckerDispatcher
import viaduct.engine.api.CheckerExecutor
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData

/**
 * Dispatch the access checker execution to the appropriate executor.
 */
class CheckerDispatcherImpl(
    private val executor: CheckerExecutor
) : CheckerDispatcher {
    override val requiredSelectionSets = executor.requiredSelectionSets

    override suspend fun execute(
        arguments: Map<String, Any?>,
        objectDataMap: Map<String, EngineObjectData>,
        context: EngineExecutionContext
    ): CheckerResult {
        return executor.execute(arguments, objectDataMap, context)
    }
}
