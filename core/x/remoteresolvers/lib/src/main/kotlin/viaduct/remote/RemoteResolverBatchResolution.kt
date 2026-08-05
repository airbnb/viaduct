package viaduct.remote

import com.google.protobuf.ByteString
import io.grpc.Status
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.remote.grpc.ErrorInfo
import viaduct.remote.grpc.ResolvedNode
import viaduct.remote.grpc.Selector
import viaduct.remote.registry.NodeExecutorRegistry
import viaduct.remote.registry.SelectionsRegistry

private val log = LoggerFactory.getLogger("viaduct.remote.RemoteResolverBatchResolution")

/**
 * Resolves one node batch: build selectors from the wire request, invoke the executor, and
 * serialize each selector's outcome in isolation (one selector's failure -- to invoke as part of
 * the whole batch, or to serialize -- never fails another selector's result). Shared by
 * [RemoteResolverServiceImpl] (unary) and [RemoteResolverStreamServiceImpl] (streaming) so a
 * required change to this logic can't be made for one transport and forgotten in the other.
 * Takes the [EngineExecutionContext] interface, not a concrete type, so both transports' context
 * implementations can share it.
 */
internal suspend fun resolveNodeExecutorBatch(
    executorId: String,
    protoSelectors: List<Selector>,
    context: EngineExecutionContext
): List<ResolvedNode> {
    val executor = NodeExecutorRegistry.get(executorId) ?: throw notFound("executor", executorId)
    val keyedSelectors = buildNodeSelectors(protoSelectors, executor)
    // An unbatched built-in resolver asserts a non-empty batch, so guard against calling it empty.
    if (keyedSelectors.isEmpty()) return emptyList()

    val results = try {
        executor.resolve(keyedSelectors.map { it.second }, context)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.error("Resolver execution failed for type '{}': {}", executor.typeName, e.message, e)
        return keyedSelectors.map { (id, _) -> nodeError(id, e) }
    }

    return keyedSelectors.map { (id, selector) -> resolveNodeResult(id, results[selector], executor.typeName) }
}

// A registry miss means the resolver runs with an empty selection set and the proxy side projects
// requested fields client-side, matching the unary path.
private fun buildNodeSelectors(
    protoSelectors: List<Selector>,
    executor: NodeResolverExecutor
): List<Pair<String, NodeResolverExecutor.Selector>> =
    protoSelectors.map { proto ->
        val selections = SelectionsRegistry.get(proto.selectionsHandle) ?: EmptyEngineSelectionSet(executor.typeName)
        proto.id to NodeResolverExecutor.Selector(id = proto.id, selections = selections)
    }

private suspend fun resolveNodeResult(
    id: String,
    result: Result<EngineObjectData>?,
    typeName: String
): ResolvedNode =
    when {
        result == null -> nodeError(id, IllegalStateException("Resolver returned no result for selector '$id'"))
        result.isSuccess -> serializeNodeResult(id, result.getOrThrow(), typeName)
        else -> nodeError(id, result.exceptionOrNull()!!)
    }

private suspend fun serializeNodeResult(
    id: String,
    value: EngineObjectData,
    typeName: String
): ResolvedNode =
    try {
        ResolvedNode.newBuilder()
            .setSelectorId(id)
            .setDataJson(ByteString.copyFrom(EngineObjectDataSerializer.serialize(value)))
            .build()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("Failed to serialize node result for id '{}' (type '{}'): {}", id, typeName, e.message, e)
        nodeError(id, e)
    }

internal fun notFound(
    kind: String,
    handle: String
): StatusRuntimeException = Status.NOT_FOUND.withDescription("$kind handle not registered: $handle").asRuntimeException()

internal fun nodeError(
    selectorId: String,
    error: Throwable
): ResolvedNode =
    ResolvedNode.newBuilder()
        .setSelectorId(selectorId)
        .setError(
            ErrorInfo.newBuilder()
                .setMessage(error.message ?: "Node resolver execution failed")
                .setErrorType(error::class.java.name)
                .build()
        )
        .build()
