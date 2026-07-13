package viaduct.remote.fixtures

import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.ResolverMetadata
import viaduct.engine.api.spi.FieldResolverExecutor

/**
 * Simple [FieldResolverExecutor] for testing, mirroring [SimpleNodeResolverExecutor].
 *
 * It resolves a single scalar field ("Character.isAdult") by reading an integer `age`
 * from each selector's eagerly-resolved object value and returning whether the character
 * is an adult (`age >= 18`). The result is a plain [Boolean] so it round-trips through
 * [viaduct.remote.FieldValueSerializer] unchanged.
 *
 * The executor is non-selective (object/query selection sets are null and [isSelective]
 * is false) so it is eligible for remote proxying via
 * [viaduct.remote.RemoteFieldProxyExecutor].
 */
class SimpleFieldResolverExecutor(
    override val resolverId: String = "Character.isAdult",
    override val metadata: ResolverMetadata = ResolverMetadata.forMock("SimpleFieldResolverExecutor:$resolverId")
) : FieldResolverExecutor {
    override val objectSelectionSet: RequiredSelectionSet? = null
    override val querySelectionSet: RequiredSelectionSet? = null
    override val isSelective: Boolean = false
    override val isBatching: Boolean = true

    override suspend fun batchResolve(
        selectors: List<FieldResolverExecutor.Selector>,
        context: EngineExecutionContext
    ): Map<FieldResolverExecutor.Selector, Result<Any?>> =
        selectors.associateWith { selector ->
            runCatching {
                val objectValue = selector.syncObjectValueGetter()
                val age = (objectValue.get(AGE_FIELD) as Number).toInt()
                age >= ADULT_AGE
            }
        }

    companion object {
        const val AGE_FIELD = "age"
        const val ADULT_AGE = 18
    }
}
