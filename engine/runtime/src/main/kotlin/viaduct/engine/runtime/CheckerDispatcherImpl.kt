package viaduct.engine.runtime

import viaduct.engine.api.CheckerExecutor
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData

/**
 * Dispatch the access checker execution to the appropriate executor.
 */
class CheckerDispatcherImpl(
    private val checkerExecutor: CheckerExecutor
) : CheckerDispatcher {
    override val requiredSelectionSets = checkerExecutor.requiredSelectionSets
    override val checkerMetadata = checkerExecutor.checkerMetadata
    override val executor = checkerExecutor

    override suspend fun execute(
        arguments: Map<String, Any?>,
        objectDataMap: Map<String, EngineObjectData>,
        context: EngineExecutionContext,
        checkerType: CheckerExecutor.CheckerType
    ): CheckerResult {
        return checkerExecutor.execute(arguments, objectDataMap, context, checkerType)
    }
}
