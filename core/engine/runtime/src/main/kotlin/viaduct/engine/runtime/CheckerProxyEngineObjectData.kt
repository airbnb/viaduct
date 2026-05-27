package viaduct.engine.runtime

import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.runtime.ObjectEngineResultImpl.Companion.RAW_VALUE_SLOT

/**
 * A [CheckerProxyEngineObjectData] that overrides the fetchCheckedValue methods to avoid
 * fetching ACCESS_CHECK_SLOT.
 * This class also exposes `objectEngineResult` publicly to facilitate access check on
 * field's type to get the correct field OER to perform access check on.
 *
 * It inherits the same field-keying model as [ProxyEngineObjectData], where [selectionSet]
 * controls both what the checker can read and the selective key shape used for those reads.
 */
class CheckerProxyEngineObjectData(
    val objectEngineResult: ObjectEngineResult,
    private val errorMessage: String,
    private val selectionSet: EngineSelectionSet? = null,
    private val isResolverSelective: IsResolverSelective,
    private val selections: ObjectEngineResult.Selections? = null,
) : ProxyEngineObjectData(objectEngineResult, errorMessage, selectionSet, isResolverSelective, selections) {
    override fun createInstance(
        objectEngineResult: ObjectEngineResult,
        errorMessage: String,
        selectionSet: EngineSelectionSet?,
        isResolverSelective: IsResolverSelective,
        selections: ObjectEngineResult.Selections?,
    ): CheckerProxyEngineObjectData =
        CheckerProxyEngineObjectData(
            objectEngineResult,
            errorMessage,
            selectionSet,
            isResolverSelective,
            selections
        )

    override suspend fun ObjectEngineResult.fetchCheckedValue(key: ObjectEngineResult.Key): Any? {
        // For checker RSS result, it's important to not call fetch on the ACCESS_CHECK_SLOT
        // to avoid a deadlock via circular dependencies.
        return this.fetch(key, RAW_VALUE_SLOT)
    }

    override suspend fun Cell.fetchCheckedValue(): Any? {
        // For checker RSS result, it's important to not call fetch on the ACCESS_CHECK_SLOT
        // to avoid a deadlock via circular dependencies.
        return this.fetch(RAW_VALUE_SLOT)
    }
}
