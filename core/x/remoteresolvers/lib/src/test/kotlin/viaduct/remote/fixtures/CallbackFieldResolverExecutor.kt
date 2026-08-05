package viaduct.remote.fixtures

import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.ResolverMetadata
import viaduct.engine.api.spi.FieldResolverExecutor

/**
 * A [FieldResolverExecutor] that issues a re-entrant query from inside [batchResolve], mirroring
 * [CallbackNodeResolverExecutor] on the field path.
 *
 * Each selector triggers `context.resolveSelectionSet(...)`, which on the remote side routes back
 * to the engine over the gRPC callback channel
 * ([viaduct.remote.UnaryRemoteEngineExecutionContext.resolveSelectionSet] → callback service →
 * engine). The resolver returns a JSON-friendly [String] so a successful round-trip would
 * serialize cleanly; the test asserts the callback mechanism fires rather than that the
 * re-entrant query completes (ContextMocks runs over a no-op engine, so the engine-side
 * resolveSelectionSet fails — the same limitation the node callback test documents).
 *
 * [reentrantTypeName] must be a type present in the test schema so the re-entrant selection set
 * can be built (otherwise the resolver fails locally before reaching the callback channel).
 */
class CallbackFieldResolverExecutor(
    override val resolverId: String = "Character.bestFriendName",
    private val reentrantTypeName: String = "Character"
) : FieldResolverExecutor {
    override val objectSelectionSet: RequiredSelectionSet? = null
    override val querySelectionSet: RequiredSelectionSet? = null
    override val isSelective: Boolean = false
    override val isBatching: Boolean = true
    override val metadata: ResolverMetadata = ResolverMetadata.forMock("CallbackFieldResolverExecutor:$resolverId")

    override suspend fun batchResolve(
        selectors: List<FieldResolverExecutor.Selector>,
        context: EngineExecutionContext
    ): Map<FieldResolverExecutor.Selector, Result<Any?>> =
        selectors.associateWith {
            runCatching {
                val friendSelections = context.engineSelectionSetFactory.engineSelectionSet(
                    reentrantTypeName,
                    "id name",
                    emptyMap()
                )
                // Re-entrant call: on the remote side this dials back to the engine over the gRPC
                // callback channel.
                val friend = context.resolveSelectionSet(friendSelections)
                friend.fetch("name") as String?
            }
        }
}
