package viaduct.remote

import io.grpc.ManagedChannel
import java.time.Duration
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.engine.api.spi.ProxyResolverFactory
import viaduct.remote.registry.FieldExecutorRegistry
import viaduct.remote.registry.NodeExecutorRegistry

/**
 * [ProxyResolverFactory] that wraps resolvers with a gRPC proxy so their execution is
 * forwarded to a [RemoteResolverService]: node resolvers via [RemoteNodeProxyExecutor],
 * field resolvers via [RemoteFieldProxyExecutor].
 *
 * @param rrsChannel Channel to the remote resolver service. Caller owns the channel.
 * @param callbackEndpoint Endpoint the remote service dials for re-entrant queries;
 *   either "host:port" (network) or an in-process channel name.
 * @param requestDeadline Deadline applied to every outbound resolve RPC, or `null` to rely
 *   on gRPC defaults. Unbounded waits hang resolver coroutines if the remote service is slow
 *   or unresponsive.
 * @param shouldProxyNode Predicate to opt specific node types in or out of proxying.
 *   Defaults to proxying every node resolver.
 * @param shouldProxyField Predicate to opt specific field resolvers in or out of proxying.
 *   Defaults to proxying no field resolvers — field results have mixed serializability, so
 *   field proxying is opt-in by coordinate.
 */
class RemoteProxyResolverFactory(
    private val rrsChannel: ManagedChannel,
    private val callbackEndpoint: String,
    private val requestDeadline: Duration? = null,
    private val shouldProxyNode: (NodeResolverExecutor) -> Boolean = { true },
    private val shouldProxyField: (FieldResolverExecutor) -> Boolean = { false }
) : ProxyResolverFactory {
    override fun proxyNode(executor: NodeResolverExecutor): NodeResolverExecutor? {
        if (!shouldProxyNode(executor)) return null
        val executorId = NodeExecutorRegistry.register(executor)
        return RemoteNodeProxyExecutor(
            originalExecutor = executor,
            executorId = executorId,
            rrsChannel = rrsChannel,
            callbackEndpoint = callbackEndpoint,
            requestDeadline = requestDeadline
        )
    }

    override fun proxyField(executor: FieldResolverExecutor): FieldResolverExecutor? {
        if (!shouldProxyField(executor)) return null
        val executorId = FieldExecutorRegistry.register(executor)
        return RemoteFieldProxyExecutor(
            originalExecutor = executor,
            executorId = executorId,
            rrsChannel = rrsChannel,
            callbackEndpoint = callbackEndpoint,
            requestDeadline = requestDeadline
        )
    }

    companion object {
        /** Creates a factory that proxies every node resolver. */
        fun proxyAll(
            rrsChannel: ManagedChannel,
            callbackEndpoint: String,
            requestDeadline: Duration? = null
        ) = RemoteProxyResolverFactory(rrsChannel, callbackEndpoint, requestDeadline)

        /** Creates a factory that proxies only the listed GraphQL type names. */
        fun proxyTypes(
            rrsChannel: ManagedChannel,
            callbackEndpoint: String,
            vararg types: String,
            requestDeadline: Duration? = null
        ) = RemoteProxyResolverFactory(
            rrsChannel,
            callbackEndpoint,
            requestDeadline,
            shouldProxyNode = { it.typeName in types }
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
