package com.example.remote

import com.google.inject.Guice
import kotlin.system.exitProcess
import org.slf4j.LoggerFactory

/** Entry point for the standalone Remote Resolver Server. See module README for transports and configuration. */
fun main(args: Array<String>) {
    val log = LoggerFactory.getLogger("RemoteServer")
    val config = RemoteConfiguration.fromArgs(args)
    log.info(
        "Remote server starting — port={} callback={}:{}",
        config.port,
        config.callbackHost,
        config.callbackPort
    )

    // Plug your own tenant by passing your Guice module here in place of StarWarsRemoteModule.
    val codeInjector = RemoteCodeInjector(Guice.createInjector(StarWarsRemoteModule()))
    val runtime = TenantBootstrapper(codeInjector).bootstrap()
    val resolverCount = runtime.nodeExecutors.size + runtime.fieldExecutors.size
    log.info("Tenant bootstrap complete; registered {} resolver(s)", resolverCount)

    val server = RemoteServer(config, runtime)
    Runtime.getRuntime().addShutdownHook(Thread({ server.stop() }, "remote-server-shutdown"))

    try {
        server.start()
        log.info("Remote server ready, listening on port {}", config.port)
        server.blockUntilShutdown()
    } catch (e: InterruptedException) {
        log.info("Interrupted; shutting down")
        Thread.currentThread().interrupt()
    } catch (e: Exception) {
        log.error("Remote server failed to start", e)
        exitProcess(1)
    }
}
