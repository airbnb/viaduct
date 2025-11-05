package viaduct.engine.api

/**
 * Interface that dispatches the access checker for a field or a type
 */
interface CheckerDispatcher {
    val requiredSelectionSets: Map<String, RequiredSelectionSet?>

    suspend fun execute(
        arguments: Map<String, Any?>,
        objectDataMap: Map<String, EngineObjectData>,
        context: EngineExecutionContext,
        checkerType: CheckerExecutor.CheckerType
    ): CheckerResult
}
