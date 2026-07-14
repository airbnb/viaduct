package viaduct.remote.config

import io.grpc.ManagedChannel
import io.grpc.Server
import io.grpc.ServerBuilder
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import viaduct.engine.api.spi.ProxyResolverFactory
import viaduct.remote.EngineCallbackServiceImpl
import viaduct.remote.RemoteProxyResolverFactory

/**
 * Lifecycle manager for the experimental remote-resolver feature.
 *
 * [initialize] is thread-safe and idempotent. Once [close] runs the instance is
 * terminal — a subsequent [initialize] throws [IllegalStateException].
 */
class RemoteResolverInitializer(private val config: RemoteResolverConfig) : AutoCloseable {
    private val log = LoggerFactory.getLogger(RemoteResolverInitializer::class.java)

    private var callbackServer: Server? = null
    private var rrsChannel: ManagedChannel? = null

    // Read on the fast path before synchronized — `factory !== NO_OP` doubles as the
    // initialized sentinel for double-checked locking.
    @Volatile private var factory: ProxyResolverFactory = ProxyResolverFactory.NO_OP

    // Once close() shuts down the channel and server, reusing this instance would hand
    // back a poisoned factory; surface the misuse instead.
    @Volatile private var closed = false

    /**
     * Starts the gRPC transports and returns a [ProxyResolverFactory], or
     * [ProxyResolverFactory.NO_OP] when the feature is disabled.
     */
    fun initialize(): ProxyResolverFactory {
        if (!config.enabled) {
            log.info("Remote resolver execution disabled")
            return ProxyResolverFactory.NO_OP
        }
        requireOpen()
        if (factory !== ProxyResolverFactory.NO_OP) return factory
        return synchronized(this) {
            requireOpen()
            if (factory !== ProxyResolverFactory.NO_OP) return@synchronized factory

            logEnabled()
            factory = initializeTransport()
            log.info("Remote resolver execution initialized")
            factory
        }
    }

    /** Releases the gRPC server and client channel. Idempotent; safe to call before [initialize]. */
    override fun close() {
        synchronized(this) {
            if (closed) return
            if (factory === ProxyResolverFactory.NO_OP) {
                closed = true
                return
            }
            try {
                log.info("Shutting down remote resolver execution")
                rrsChannel?.shutdown()
                callbackServer?.shutdown()
                try {
                    if (rrsChannel?.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS) == false) rrsChannel?.shutdownNow()
                    if (callbackServer?.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS) == false) callbackServer?.shutdownNow()
                } catch (e: InterruptedException) {
                    rrsChannel?.shutdownNow()
                    callbackServer?.shutdownNow()
                    Thread.currentThread().interrupt()
                }
            } finally {
                rrsChannel = null
                callbackServer = null
                factory = ProxyResolverFactory.NO_OP
                closed = true
            }
        }
    }

    // Shaded Netty avoids classpath clashes with a non-shaded grpc-netty pulled in by
    // host applications. Plaintext only; TLS is out of scope for this experimental feature.
    private fun initializeTransport(): ProxyResolverFactory {
        log.info("Connecting to remote RRS at {}:{}", config.rrsHost, config.rrsPort)
        rrsChannel = NettyChannelBuilder.forAddress(config.rrsHost, config.rrsPort)
            .usePlaintext()
            .build()

        log.info("Starting callback server on port {}", config.callbackPort)
        callbackServer = ServerBuilder.forPort(config.callbackPort)
            .addService(EngineCallbackServiceImpl())
            .build()
            .start()

        val callbackEndpoint = "${resolveLocalHost()}:${config.callbackPort}"
        log.info("Callback endpoint: {}", callbackEndpoint)
        return buildFactory(rrsChannel!!, callbackEndpoint)
    }

    private fun buildFactory(
        channel: ManagedChannel,
        callbackEndpoint: String
    ): ProxyResolverFactory =
        RemoteProxyResolverFactory(
            channel,
            callbackEndpoint,
            // Both nodes and fields default to all (empty set = all); a non-empty set restricts to it.
            shouldProxyNode = { config.remoteTypes.isEmpty() || it.typeName in config.remoteTypes },
            // Default (empty set) proxies all field resolvers EXCEPT the engine's built-ins
            // (Query.node/nodes, @namespaceType) — those are in-JVM framework ops, so a gRPC hop is
            // pure overhead. An explicit VIADUCT_REMOTE_RESOLVER_FIELDS entry still opts a built-in in.
            // A `none` sentinel (fieldProxyingEnabled = false) turns field proxying fully off.
            shouldProxyField = {
                config.fieldProxyingEnabled &&
                    (
                        (config.remoteFields.isEmpty() && it.metadata.name !in BUILT_IN_FIELD_RESOLVER_NAMES) ||
                            it.resolverId in config.remoteFields
                    )
            }
        )

    private fun logEnabled() {
        if (config.remoteTypes.isEmpty()) {
            log.info("Remote resolver execution enabled for all node types")
        } else {
            log.info("Remote resolver execution enabled for node types {}", config.remoteTypes)
        }
        if (!config.fieldProxyingEnabled) {
            log.info(
                "Remote resolver field proxying disabled via VIADUCT_REMOTE_RESOLVER_FIELDS=none; " +
                    "node proxying unaffected"
            )
        } else if (config.remoteFields.isEmpty()) {
            log.info(
                "Remote resolver execution enabled for all field resolvers by default; built-ins and " +
                    "selective resolvers excluded (set VIADUCT_REMOTE_RESOLVER_FIELDS to narrow or 'none' to disable)"
            )
        } else {
            log.info("Remote resolver execution enabled for fields {}", config.remoteFields)
        }
    }

    private fun resolveLocalHost(): String =
        try {
            InetAddress.getLocalHost().hostAddress
        } catch (e: UnknownHostException) {
            log.warn("Could not determine local host address; falling back to 'localhost'", e)
            "localhost"
        }

    private fun requireOpen() = check(!closed) { "RemoteResolverInitializer has been closed and cannot be reused" }

    private companion object {
        const val SHUTDOWN_TIMEOUT_SECONDS = 5L

        // Engine built-in field resolvers (by ResolverMetadata.name) — proxied only when explicitly
        // listed, never by default: they're in-JVM framework ops, so proxying them is wasted round-trips.
        val BUILT_IN_FIELD_RESOLVER_NAMES = setOf(
            "query-node-resolver",
            "query-nodes-resolver",
            "namespace-type-resolver"
        )
    }
}
