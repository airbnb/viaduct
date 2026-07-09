package viaduct.engine.runtime

import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.instrumentation.resolver.ResolverInstrumentationContext

/**
 * An [EngineObjectData.Sync] that carries the underlying [ObjectEngineResult] alongside
 * the eagerly-resolved sync data, so the checker execution path can extract the
 * [objectEngineResult] to complete the checker's required selection set.
 */
class CheckerSyncEngineObjectData(
    val objectEngineResult: ObjectEngineResult,
    private val delegate: SyncProxyEngineObjectData,
) : EngineObjectData.Sync by delegate {
    companion object {
        suspend fun resolve(
            objectEngineResult: ObjectEngineResult,
            errorMessage: String,
            selectionSet: EngineSelectionSet?,
            isResolverSelective: IsResolverSelective,
            selections: ObjectEngineResult.Selections?,
            instrumentationContext: ResolverInstrumentationContext? = null,
        ): CheckerSyncEngineObjectData {
            val syncData = SyncEngineObjectDataFactory.resolve(
                objectEngineResult,
                errorMessage,
                selectionSet,
                isResolverSelective = isResolverSelective,
                selections = selections,
                skipAccessCheck = true,
                instrumentationContext = instrumentationContext,
            )
            return CheckerSyncEngineObjectData(objectEngineResult, syncData)
        }
    }
}
