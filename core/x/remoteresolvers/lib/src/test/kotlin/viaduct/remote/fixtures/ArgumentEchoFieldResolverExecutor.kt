package viaduct.remote.fixtures

import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.ResolverMetadata
import viaduct.engine.api.spi.FieldResolverExecutor

/**
 * A [FieldResolverExecutor] that branches on each selector's `arguments` so a test can prove
 * arguments survive the gRPC round-trip
 * ([viaduct.remote.RemoteFieldProxyExecutor.batchResolve] → wire →
 * [viaduct.remote.RemoteResolverServiceImpl.batchResolveField], which reconstructs the selector
 * from the deserialized arguments).
 *
 * Unlike [SimpleFieldResolverExecutor] (which ignores arguments), this resolver returns a
 * JSON-friendly [String] built from the `includeDetails` and `limit` arguments, so the value
 * round-trips through [viaduct.remote.FieldValueSerializer] unchanged and the assertion can read
 * back exactly what the remote side received.
 */
class ArgumentEchoFieldResolverExecutor(
    override val resolverId: String = "Character.summary"
) : FieldResolverExecutor {
    override val objectSelectionSet: RequiredSelectionSet? = null
    override val querySelectionSet: RequiredSelectionSet? = null
    override val isSelective: Boolean = false
    override val isBatching: Boolean = true
    override val metadata: ResolverMetadata = ResolverMetadata.forMock("ArgumentEchoFieldResolverExecutor:$resolverId")

    override suspend fun batchResolve(
        selectors: List<FieldResolverExecutor.Selector>,
        context: EngineExecutionContext
    ): Map<FieldResolverExecutor.Selector, Result<Any?>> =
        selectors.associateWith { selector ->
            runCatching {
                val includeDetails = selector.arguments[INCLUDE_DETAILS_ARG] as? Boolean ?: false
                val limit = (selector.arguments[LIMIT_ARG] as? Number)?.toInt()
                if (includeDetails) "details:limit=$limit" else "summary"
            }
        }

    companion object {
        const val INCLUDE_DETAILS_ARG = "includeDetails"
        const val LIMIT_ARG = "limit"
    }
}
