package com.example.remote

import io.grpc.Server
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.protobuf.services.ProtoReflectionService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory
import viaduct.remote.RemoteResolverServiceImpl

/**
 * Standalone gRPC server hosting the [RemoteResolverServiceImpl]. Lifecycle is one-shot —
 * [start] binds the port, [blockUntilShutdown] holds the main thread, and the shutdown
 * hook calls [stop]. Uses shaded Netty so the module doesn't clash with a non-shaded
 * `grpc-netty` brought in by host applications.
 */
class RemoteServer(private val config: RemoteConfiguration) {
    private val log = LoggerFactory.getLogger(RemoteServer::class.java)
    private val started = AtomicBoolean(false)
    private var server: Server? = null
    private var resolverService: RemoteResolverServiceImpl? = null

    /** Binds the gRPC port. Idempotent. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        log.info("Starting remote gRPC server on port {}", config.port)
        try {
            val service = RemoteResolverServiceImpl()
            resolverService = service
            server = NettyServerBuilder.forPort(config.port)
                .addService(service)
                .addService(ProtoReflectionService.newInstance())
                .build()
                .start()
        } catch (e: Exception) {
            // Roll the CAS back so a caller can retry after fixing the underlying issue.
            started.set(false)
            throw e
        }
        log.info(
            "Remote gRPC server listening on port {} (callback expected at {}:{})",
            config.port,
            config.callbackHost,
            config.callbackPort
        )
    }

    /** Drains cached callback channels then stops the server (30s graceful, 5s forced). Safe to call before [start]. */
    fun stop() {
        resolverService?.shutdownChannels()
        val s = server ?: return
        log.info("Shutting down remote gRPC server")
        s.shutdown()
        try {
            if (!s.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("Server did not terminate within {}s; forcing shutdown", SHUTDOWN_TIMEOUT_SECONDS)
                s.shutdownNow()
                if (!s.awaitTermination(FORCE_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    log.error("Server did not terminate after forced shutdown")
                }
            }
        } catch (e: InterruptedException) {
            s.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }

    fun blockUntilShutdown() {
        server?.awaitTermination()
    }

    fun isRunning(): Boolean = server?.isShutdown == false

    private companion object {
        const val SHUTDOWN_TIMEOUT_SECONDS = 30L
        const val FORCE_SHUTDOWN_TIMEOUT_SECONDS = 5L
    }
}
