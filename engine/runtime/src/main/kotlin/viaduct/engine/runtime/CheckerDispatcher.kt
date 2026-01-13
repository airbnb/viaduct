package viaduct.engine.runtime

import viaduct.engine.api.CheckerExecutor
import viaduct.engine.api.CheckerMetadata
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.RequiredSelectionSet

/**
 * Interface that dispatches the access checker for a field or a type
 */
interface CheckerDispatcher {
    val requiredSelectionSets: Map<String, RequiredSelectionSet?>

    val checkerMetadata: CheckerMetadata?
        get() = null

    /**
     * Returns this dispatcher as a [CheckerExecutor].
     * Implementations must provide their own instance.
     */
    val executor: CheckerExecutor

    suspend fun execute(
        arguments: Map<String, Any?>,
        objectDataMap: Map<String, EngineObjectData>,
        context: EngineExecutionContext,
        checkerType: CheckerExecutor.CheckerType
    ): CheckerResult
}
