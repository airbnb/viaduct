package viaduct.remote

import io.grpc.ManagedChannel
import java.time.Duration
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.engine.api.spi.ProxyResolverFactory
import viaduct.remote.api.spi.RemoteResolverContextCapturerProvider
import viaduct.remote.registry.FieldExecutorRegistry
import viaduct.remote.registry.NodeExecutorRegistry

/**
 * [ProxyResolverFactory] that wraps resolvers with a gRPC proxy so their execution is
 * forwarded to a [RemoteResolverService]: node resolvers via [RemoteNodeProxyExecutor],
 * field resolvers via [RemoteFieldProxyExecutor].
 *
 * Selective resolvers are never proxied, regardless of the predicates below:
 * [RemoteNodeProxyExecutor]/[RemoteFieldProxyExecutor] reject them at construction, so this factory
 * skips them *before* registering — otherwise the default "proxy all" would crash bootstrap on a
 * selective resolver, or orphan a registry entry when the constructor throws.
 *
 * @param rrsChannel Channel to the remote resolver service. Caller owns the channel.
 * @param callbackEndpoint Endpoint the remote service dials for re-entrant queries;
 *   must use "host:port" form.
 * @param requestDeadline Deadline applied to every outbound resolve RPC, or `null` to rely
 *   on gRPC defaults. Unbounded waits hang resolver coroutines if the remote service is slow
 *   or unresponsive.
 * @param shouldProxyNode Predicate to opt specific node types in or out of proxying.
 *   Defaults to proxying every node resolver.
 * @param shouldProxyField Predicate to opt specific field resolvers in or out of proxying.
 *   Defaults to proxying every field resolver (mirroring nodes). Selective resolvers are always
 *   skipped regardless of this predicate — [RemoteFieldProxyExecutor] rejects them at construction.
 * @param contextCapturerProvider Host hook that resolves the capturer associated with the active
 *   top-level request.
 */
class RemoteProxyResolverFactory(
    private val rrsChannel: ManagedChannel,
    private val callbackEndpoint: String,
    private val requestDeadline: Duration? = null,
    private val shouldProxyNode: (NodeResolverExecutor) -> Boolean = { true },
    private val shouldProxyField: (FieldResolverExecutor) -> Boolean = { true },
    private val contextCapturerProvider: RemoteResolverContextCapturerProvider =
        RemoteResolverContextCapturerProvider.NO_OP,
) : ProxyResolverFactory {
    override fun proxyNode(executor: NodeResolverExecutor): NodeResolverExecutor? {
        // Skip selective resolvers before registering (see class KDoc).
        if (executor.isSelective) return null
        if (!shouldProxyNode(executor)) return null
        val executorId = NodeExecutorRegistry.register(executor)
        return RemoteNodeProxyExecutor(
            originalExecutor = executor,
            executorId = executorId,
            rrsChannel = rrsChannel,
            callbackEndpoint = callbackEndpoint,
            requestDeadline = requestDeadline,
            contextCapturerProvider = contextCapturerProvider,
        )
    }

    override fun proxyField(executor: FieldResolverExecutor): FieldResolverExecutor? {
        // Skip selective resolvers before registering (see class KDoc).
        if (executor.isSelective) return null
        if (!shouldProxyField(executor)) return null
        val executorId = FieldExecutorRegistry.register(executor)
        return RemoteFieldProxyExecutor(
            originalExecutor = executor,
            executorId = executorId,
            rrsChannel = rrsChannel,
            callbackEndpoint = callbackEndpoint,
            requestDeadline = requestDeadline,
            contextCapturerProvider = contextCapturerProvider,
        )
    }

    companion object {
        /** Creates a factory that proxies every node and field resolver. */
        fun proxyAll(
            rrsChannel: ManagedChannel,
            callbackEndpoint: String,
            requestDeadline: Duration? = null
        ) = RemoteProxyResolverFactory(rrsChannel, callbackEndpoint, requestDeadline)

        /** Creates a factory that proxies only the listed node type names, and no field resolvers. */
        fun proxyTypes(
            rrsChannel: ManagedChannel,
            callbackEndpoint: String,
            vararg types: String,
            requestDeadline: Duration? = null
        ) = RemoteProxyResolverFactory(
            rrsChannel,
            callbackEndpoint,
            requestDeadline,
            shouldProxyNode = { it.typeName in types },
            shouldProxyField = { false }
        )

        /** Creates a factory that proxies only the listed field coordinates ("Type.field"), and no nodes. */
        fun proxyFields(
            rrsChannel: ManagedChannel,
            callbackEndpoint: String,
            vararg fields: String,
            requestDeadline: Duration? = null
        ) = RemoteProxyResolverFactory(
            rrsChannel,
            callbackEndpoint,
            requestDeadline,
            shouldProxyNode = { false },
            shouldProxyField = { it.resolverId in fields }
        )
    }
}
