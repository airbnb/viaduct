package com.example.rrs

import com.google.inject.Guice
import kotlin.system.exitProcess
import org.slf4j.LoggerFactory

/** Entry point for the standalone Remote Resolver Server. See module README for transports and configuration. */
fun main(args: Array<String>) {
    val log = LoggerFactory.getLogger("RRS")
    val config = RrsConfiguration.fromArgs(args)
    log.info(
        "RRS starting — port={} callback={}:{}",
        config.port,
        config.callbackHost,
        config.callbackPort
    )

    // Plug your own tenant by passing your Guice module here in place of StarWarsRrsModule.
    val codeInjector = RrsCodeInjector(Guice.createInjector(StarWarsRrsModule()))
    val resolverCount = TenantBootstrapper(codeInjector).bootstrap()
    log.info("Tenant bootstrap complete; registered {} node resolver(s)", resolverCount)

    val server = RrsServer(config)
    Runtime.getRuntime().addShutdownHook(Thread({ server.stop() }, "rrs-shutdown"))

    try {
        server.start()
        log.info("RRS ready, listening on port {}", config.port)
        server.blockUntilShutdown()
    } catch (e: InterruptedException) {
        log.info("Interrupted; shutting down")
        Thread.currentThread().interrupt()
    } catch (e: Exception) {
        log.error("RRS failed to start", e)
        exitProcess(1)
    }
}
