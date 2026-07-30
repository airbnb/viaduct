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
import viaduct.remote.api.spi.RemoteResolverContextCapturerProvider

/**
 * Lifecycle manager for the experimental remote-resolver feature.
 *
 * [initialize] is thread-safe and idempotent. Once [close] runs the instance is
 * terminal — a subsequent [initialize] throws [IllegalStateException].
 */
class RemoteResolverInitializer(
    private val config: RemoteResolverConfig,
    private val selection: RemoteResolverSelection,
    private val contextCapturerProvider: RemoteResolverContextCapturerProvider =
        RemoteResolverContextCapturerProvider.NO_OP,
) : AutoCloseable {
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

            logEnabled(selection)
            factory = initializeTransport(selection)
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
    private fun initializeTransport(selection: RemoteResolverSelection): ProxyResolverFactory {
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
        return buildFactory(rrsChannel!!, callbackEndpoint, selection)
    }

    private fun buildFactory(
        channel: ManagedChannel,
        callbackEndpoint: String,
        selection: RemoteResolverSelection,
    ): ProxyResolverFactory =
        RemoteProxyResolverFactory(
            channel,
            callbackEndpoint,
            shouldProxyNode = { it.typeName in selection.nodeTypes },
            shouldProxyField = { it.resolverId in selection.fieldCoordinates },
            contextCapturerProvider = contextCapturerProvider,
        )

    private fun logEnabled(selection: RemoteResolverSelection) {
        log.info(
            "Remote resolver execution selected by tenants {}: {} node types, {} fields",
            selection.tenantNames,
            selection.nodeTypes.size,
            selection.fieldCoordinates.size,
        )
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
    }
}
