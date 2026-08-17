package viaduct.engine.runtime

import graphql.ExecutionResult
import viaduct.engine.api.CompleteSelectionSetOptions
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.instrumentation.resolver.ResolverInstrumentationContext
import viaduct.engine.runtime.EngineExecutionContextExtensions.asImpl

/**
 * An [EngineObjectData.Sync] that carries the underlying [ObjectEngineResult] alongside
 * eagerly-resolved data and completes checker selection sets without exposing the result.
 */
class CheckerSyncEngineObjectData internal constructor(
    private val objectEngineResult: ObjectEngineResult,
    private val delegate: SyncProxyEngineObjectData,
) : EngineObjectData.Sync by delegate {
    suspend fun completeSelectionSet(
        context: EngineExecutionContext,
        selectionSet: RequiredSelectionSet,
        arguments: Map<String, Any?>,
        options: CompleteSelectionSetOptions,
    ): ExecutionResult =
        context.asImpl().completeSelectionSet(
            selectionSet = selectionSet,
            targetResult = objectEngineResult,
            arguments = arguments,
            options = options,
        )

    companion object {
        internal suspend fun resolve(
            objectEngineResult: ObjectEngineResult,
            errorMessage: String,
            selectionSet: EngineSelectionSet?,
            instrumentationContext: ResolverInstrumentationContext? = null,
        ): CheckerSyncEngineObjectData {
            val syncData = SyncEngineObjectDataFactory.resolve(
                objectEngineResult,
                errorMessage,
                selectionSet,
                skipAccessCheck = true,
                instrumentationContext = instrumentationContext,
            )
            return CheckerSyncEngineObjectData(objectEngineResult, syncData)
        }
    }
}
