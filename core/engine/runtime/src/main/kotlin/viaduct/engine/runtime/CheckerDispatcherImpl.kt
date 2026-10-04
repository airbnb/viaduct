package viaduct.engine.runtime

import viaduct.engine.api.CheckerResult
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.spi.CheckerExecutor

/**
 * Dispatch the access checker execution to the appropriate executor.
 */
class CheckerDispatcherImpl(
    private val checkerExecutor: CheckerExecutor,
    objectTypeName: String? = null,
    queryTypeName: String? = null,
    checkerType: CheckerExecutor.CheckerType? = null,
) : CheckerDispatcher {
    override val requiredSelectionSets = checkerExecutor.requiredSelectionSets
    override val variableDefinitions: Map<String, ResolverVariableDefinitions> by lazy {
        extractCheckerVariableDefinitions(
            requiredSelectionSets = requiredSelectionSets,
            objectTypeName = objectTypeName,
            queryTypeName = queryTypeName,
            checkerType = checkerType,
        )
    }
    override val checkerMetadata = checkerExecutor.checkerMetadata
    override val executor = checkerExecutor

    override suspend fun execute(
        arguments: Map<String, Any?>,
        objectDataFactories: Map<String, EngineObjectDataFactory>,
        context: EngineExecutionContext,
        checkerType: CheckerExecutor.CheckerType
    ): CheckerResult {
        val objectDataMap = objectDataFactories.mapValues { (_, factory) ->
            factory.create(null)
        }
        return checkerExecutor.execute(arguments, objectDataMap, context, checkerType)
    }
}
