package viaduct.engine.runtime

import viaduct.engine.api.ObjectEngineResult
import viaduct.engine.api.RawSelectionSet
import viaduct.engine.runtime.ObjectEngineResultImpl.Companion.RAW_VALUE_SLOT

/**
 * A [CheckerProxyEngineObjectData] that overrides the fetchCheckedValue methods to avoid
 * fetching ACCESS_CHECK_SLOT.
 * This class also exposes `objectEngineResult` publicly to facilitate access check on
 * field's type to get the correct field OER to perform access check on.
 */
class CheckerProxyEngineObjectData(
    val objectEngineResult: ObjectEngineResult,
    private val errorMessage: String,
    private val selectionSet: RawSelectionSet? = null,
) : ProxyEngineObjectData(objectEngineResult, errorMessage, selectionSet) {
    override fun createInstance(
        objectEngineResult: ObjectEngineResult,
        errorMessage: String,
        selectionSet: RawSelectionSet?
    ): CheckerProxyEngineObjectData {
        return CheckerProxyEngineObjectData(objectEngineResult, errorMessage, selectionSet)
    }

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
