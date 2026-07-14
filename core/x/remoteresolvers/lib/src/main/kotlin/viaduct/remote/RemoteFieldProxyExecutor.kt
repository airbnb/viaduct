package viaduct.remote

import com.google.protobuf.ByteString
import io.grpc.ManagedChannel
import java.time.Duration
import java.util.IdentityHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.ResolverMetadata
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.remote.grpc.BatchResolveFieldRequest
import viaduct.remote.grpc.FieldSelector as ProtoFieldSelector
import viaduct.remote.grpc.RemoteResolverServiceGrpcKt
import viaduct.remote.grpc.SerializedSelectionSet
import viaduct.remote.registry.ContextRegistry
import viaduct.remote.registry.SelectionsRegistry

/**
 * RRP-side proxy that forwards [FieldResolverExecutor.batchResolve] calls over gRPC to a
 * [RemoteResolverService] and returns the deserialized field values to the engine.
 *
 * The proxy delegates [objectSelectionSet] / [querySelectionSet] so the engine resolves them before
 * calling [batchResolve]; each selector's resolved object and query values are then serialized to the
 * remote service. The caller owns [rrsChannel]'s lifecycle. [callbackEndpoint] is where the remote
 * service dials back for re-entrant queries, in "host:port" form.
 */
class RemoteFieldProxyExecutor(
    private val originalExecutor: FieldResolverExecutor,
    private val executorId: String,
    rrsChannel: ManagedChannel,
    private val callbackEndpoint: String,
    private val requestDeadline: Duration? = null
) : FieldResolverExecutor {
    init {
        // Selective field resolvers vary their result by the requested sub-selections, which the
        // wire protocol does not yet support. Fail fast rather than return an incorrect result.
        require(!originalExecutor.isSelective) {
            "Remote execution of selective field resolvers is not yet supported " +
                "(resolver='${originalExecutor.resolverId}'). Track progress in the remote-resolver README."
        }
    }

    private val log = LoggerFactory.getLogger(RemoteFieldProxyExecutor::class.java)
    private val rrsStub = RemoteResolverServiceGrpcKt.RemoteResolverServiceCoroutineStub(rrsChannel)

    // Delegated so the engine resolves the required selection sets before calling batchResolve.
    override val objectSelectionSet: RequiredSelectionSet?
        get() = originalExecutor.objectSelectionSet
    override val querySelectionSet: RequiredSelectionSet?
        get() = originalExecutor.querySelectionSet
    override val isSelective: Boolean
        get() = originalExecutor.isSelective
    override val resolverId: String
        get() = originalExecutor.resolverId
    override val metadata: ResolverMetadata
        get() = originalExecutor.metadata
    override val isBatching: Boolean
        get() = originalExecutor.isBatching

    override suspend fun batchResolve(
        selectors: List<FieldResolverExecutor.Selector>,
        context: EngineExecutionContext
    ): Map<FieldResolverExecutor.Selector, Result<Any?>> {
        log.debug("Proxying {} field selector(s) for '{}' to remote execution", selectors.size, resolverId)

        // Register handles up front and unregister in finally, so a failure below — selector
        // serialization (which can throw) or the RPC — can't leak them in the process-global registries.
        val contextHandle = ContextRegistry.register(context)
        val selectionsHandles = mutableListOf<String>()
        try {
            // Correlate results positionally via selector_key: a field Selector has no natural id and
            // distinct selectors can compare equal. The value getters are suspend, so build with a loop.
            val indexed = selectors.withIndex().toList()
            val protoSelectors = mutableListOf<ProtoFieldSelector>()
            val sent = mutableListOf<Pair<Int, FieldResolverExecutor.Selector>>()
            // Serializing a selector's object/query value can throw (e.g. a nested NodeReference in the
            // resolved object). Isolate that to the offending selector's Result and still send the rest —
            // mirroring the RRS-side per-selector isolation — rather than failing the whole batch.
            val preFailed = mutableMapOf<FieldResolverExecutor.Selector, Result<Any?>>()
            // A batched field usually shares one selection-set instance across all its selectors; build
            // the wire form (handle + serialized selection set) once per distinct instance rather than
            // re-render the selection-set AST for every selector.
            val wireSelections = IdentityHashMap<EngineSelectionSet, WireSelections>()
            for ((index, selector) in indexed) {
                try {
                    val selections = selector.selections
                    val wire = selections?.let { sel ->
                        wireSelections.getOrPut(sel) {
                            val handle = SelectionsRegistry.register(sel)
                            selectionsHandles.add(handle)
                            // The handle is per-JVM and may be unresolvable remotely, so also ship the
                            // selection set itself; the service reconstructs it against its own schema. Even
                            // a fully-@skip'd (empty) composite set must stay NON-null on the remote or the
                            // tenant runtime rejects the composite return. `.document`/`.variables` both
                            // derive from toFragment(), so capture it once.
                            val fragment = sel.toFragment()
                            WireSelections(
                                handle = handle,
                                proto = SerializedSelectionSet.newBuilder()
                                    .setType(sel.type)
                                    .setDocument(fragment.document)
                                    .setVariablesJson(ByteString.copyFrom(FieldValueSerializer.serializeArguments(fragment.variables.asMap())))
                                    .build()
                            )
                        }
                    }
                    val builder = ProtoFieldSelector.newBuilder()
                        .setSelectorKey(index.toString())
                        .setArgumentsJson(ByteString.copyFrom(FieldValueSerializer.serializeArguments(selector.arguments)))
                        .setSelectionsHandle(wire?.handle ?: "")
                        .setObjectValueJson(ByteString.copyFrom(EngineObjectDataSerializer.serialize(selector.syncObjectValueGetter())))
                        .setQueryValueJson(ByteString.copyFrom(EngineObjectDataSerializer.serialize(selector.syncQueryValueGetter())))
                    // A missing `selections` means "no sub-selections" (leaf field); otherwise ship the
                    // reconstructable selection set built above.
                    if (wire != null) builder.setSelections(wire.proto)
                    protoSelectors.add(builder.build())
                    sent.add(index to selector)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("Failed to serialize field selector {} for '{}': {}", index, executorId, e.message, e)
                    preFailed[selector] = Result.failure(
                        RemoteResolverException(
                            message = e.message ?: "Failed to serialize field selector for remote execution",
                            errorType = e::class.java.name
                        )
                    )
                }
            }

            // Every selector failed to serialize — return their failures without an RPC.
            if (sent.isEmpty()) return preFailed

            val request = BatchResolveFieldRequest.newBuilder()
                .setExecutorId(executorId)
                .addAllSelectors(protoSelectors)
                .setContextHandle(contextHandle)
                .setCallbackEndpoint(callbackEndpoint)
                .build()

            val stub = requestDeadline?.let { rrsStub.withDeadlineAfter(it.toMillis(), TimeUnit.MILLISECONDS) } ?: rrsStub
            val response = stub.batchResolveField(request)
            log.debug("Received {} field result(s) for executor '{}'", response.resultsCount, executorId)

            val resultsByKey = response.resultsList.associateBy { it.selectorKey }
            return sent.associate { (index, selector) ->
                val resolved = resultsByKey[index.toString()]
                    ?: error("Response missing result for selector_key=$index (executor '$executorId')")
                val result = when {
                    // deserializeValue rebuilds references/objects against the live schema; a failure
                    // (unknown type, malformed payload) is isolated to this selector's Result and
                    // surfaced as a RemoteResolverException, matching every other error path here.
                    resolved.hasValueJson() ->
                        try {
                            Result.success(FieldValueSerializer.deserializeValue(resolved.valueJson.toByteArray(), context))
                        } catch (e: Exception) {
                            Result.failure(
                                RemoteResolverException(
                                    message = e.message ?: "Failed to deserialize remote field value",
                                    errorType = e::class.java.name
                                )
                            )
                        }
                    resolved.hasError() ->
                        Result.failure(RemoteResolverException(message = resolved.error.message, errorType = resolved.error.errorType))
                    else -> error("Field result for selector_key=$index has neither value nor error")
                }
                selector to result
            } + preFailed
        } finally {
            ContextRegistry.unregister(contextHandle)
            selectionsHandles.forEach { SelectionsRegistry.unregister(it) }
        }
    }
}

/** Memoized wire form of a selection set: its per-JVM registry [handle] and its serialized [proto]. */
private data class WireSelections(
    val handle: String,
    val proto: SerializedSelectionSet
)
