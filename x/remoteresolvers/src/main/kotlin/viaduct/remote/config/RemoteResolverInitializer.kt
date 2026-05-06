package viaduct.remote.config

import io.grpc.BindableService
import io.grpc.ManagedChannel
import io.grpc.Server
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import viaduct.engine.api.spi.ProxyResolverFactory
import viaduct.remote.EngineCallbackServiceImpl
import viaduct.remote.RemoteProxyResolverFactory
import viaduct.remote.RemoteResolverServiceImpl

/**
 * Lifecycle manager for the experimental remote-resolver feature.
 *
 * [initialize] starts an in-process gRPC pair and returns a [ProxyResolverFactory];
 * [close] tears them down. [initialize] is thread-safe and idempotent. Once [close]
 * runs the instance is terminal — a subsequent [initialize] throws [IllegalStateException].
 */
class RemoteResolverInitializer(private val config: RemoteResolverConfig) : AutoCloseable {
    private val log = LoggerFactory.getLogger(RemoteResolverInitializer::class.java)

    private var rrsServer: Server? = null
    private var callbackServer: Server? = null
    private var rrsChannel: ManagedChannel? = null

    // Doubles as the "initialized" sentinel for double-checked locking — read on the
    // fast path before the synchronized block, hence @Volatile.
    @Volatile private var factory: ProxyResolverFactory = ProxyResolverFactory.NO_OP

    // Set in close(); subsequent initialize() must reject — the lazy executor below
    // is shut down in close() and would otherwise be reused in its terminated state.
    @Volatile private var closed = false

    // Avoids directExecutor() because RRS callbacks re-enter RRP on the same call stack
    // and would deadlock on a single direct-executor thread; bounded (rather than cached)
    // caps growth if a misbehaving resolver fans out aggressively. Lazy so the disabled
    // path doesn't allocate.
    private val inProcessExecutor by lazy {
        Executors.newFixedThreadPool(
            (Runtime.getRuntime().availableProcessors() * 2).coerceAtLeast(4)
        )
    }

    /**
     * Starts the in-process gRPC servers and returns a [ProxyResolverFactory].
     * Returns [ProxyResolverFactory.NO_OP] when [config] is disabled.
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

            if (config.remoteTypes.isEmpty()) {
                log.info("Remote resolver execution enabled for all types")
            } else {
                log.info("Remote resolver execution enabled for types: {}", config.remoteTypes)
            }

            rrsServer = startInProcessServer(config.rrsServerName, RemoteResolverServiceImpl())
            callbackServer = startInProcessServer(config.callbackEndpoint, EngineCallbackServiceImpl())
            rrsChannel = InProcessChannelBuilder.forName(config.rrsServerName).directExecutor().build()

            factory = RemoteProxyResolverFactory(
                rrsChannel!!,
                config.callbackEndpoint,
                shouldProxyNode = { config.remoteTypes.isEmpty() || it.typeName in config.remoteTypes }
            )
            log.info("Remote resolver execution initialized")
            factory
        }
    }

    /** Releases gRPC servers, client channel, and executor. Idempotent; safe to call before [initialize]. */
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
                rrsServer?.shutdown()
                callbackServer?.shutdown()
                try {
                    if (rrsChannel?.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS) == false) rrsChannel?.shutdownNow()
                    if (rrsServer?.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS) == false) rrsServer?.shutdownNow()
                    if (callbackServer?.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS) == false) callbackServer?.shutdownNow()
                    inProcessExecutor.shutdown()
                    if (!inProcessExecutor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        inProcessExecutor.shutdownNow()
                    }
                } catch (e: InterruptedException) {
                    rrsChannel?.shutdownNow()
                    rrsServer?.shutdownNow()
                    callbackServer?.shutdownNow()
                    inProcessExecutor.shutdownNow()
                    Thread.currentThread().interrupt()
                }
            } finally {
                rrsChannel = null
                rrsServer = null
                callbackServer = null
                factory = ProxyResolverFactory.NO_OP
                closed = true
            }
        }
    }

    private fun startInProcessServer(
        name: String,
        service: BindableService
    ): Server {
        log.info("Starting in-process gRPC server: {}", name)
        return InProcessServerBuilder.forName(name)
            .executor(inProcessExecutor)
            .addService(service)
            .build()
            .start()
    }

    private fun requireOpen() = check(!closed) { "RemoteResolverInitializer has been closed and cannot be reused" }

    private companion object {
        const val SHUTDOWN_TIMEOUT_SECONDS = 5L
    }
}
