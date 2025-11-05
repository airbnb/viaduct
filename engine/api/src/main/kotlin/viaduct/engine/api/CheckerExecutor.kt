package viaduct.engine.api

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
