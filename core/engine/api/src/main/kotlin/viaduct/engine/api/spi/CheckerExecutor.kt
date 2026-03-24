package viaduct.engine.api.spi

import viaduct.engine.api.CheckerMetadata
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.RequiredSelectionSet

/**
 * Executor for both tenant-written and Viaduct architect-written access checkers.
 */
interface CheckerExecutor {
    enum class CheckerType {
        FIELD,
        TYPE
    }

    /**
     * The map of checker key to its required selection sets.
     */
    val requiredSelectionSets: Map<String, RequiredSelectionSet?>
        get() = emptyMap()

    /**
     * Metadata about this checker for instrumentation and observability.
     */
    val checkerMetadata: CheckerMetadata?
        get() = null

    /**
     * Core execution of the access check. If the check passes, it will proceed.
     * If the check fails, we suggest to differentiate the causes in two categories:
     * - if the check fails to perform, throw failed to perform,
     * eg. ViaductFailedToPerformPolicyCheckException
     * - if the check itself fails, throw permission denied,
     * eg. ViaductPermissionDeniedException
     */
    suspend fun execute(
        arguments: Map<String, Any?>,
        objectDataMap: Map<String, EngineObjectData>,
        context: EngineExecutionContext,
        checkerType: CheckerType
    ): CheckerResult
}
