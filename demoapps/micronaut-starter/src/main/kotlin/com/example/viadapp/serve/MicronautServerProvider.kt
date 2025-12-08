package com.example.viadapp.serve

import io.micronaut.context.ApplicationContext
import viaduct.serve.ViaductServerConfiguration
import viaduct.serve.ViaductServerProvider
import viaduct.service.api.Viaduct

/**
 * Viaduct Server provider for Micronaut applications.
 *
 * This provider starts ONLY the Micronaut DI container (ApplicationContext),
 * NOT the full HTTP server. This provides fast startup for development while
 * still enabling dependency injection for resolvers.
 *
 * Key benefits:
 * - Fast startup: No HTTP server initialization
 * - Full DI support: All @Singleton, @Factory beans are available
 * - Same resolver instances: Resolvers use the same DI as production
 */
@ViaductServerConfiguration
class MicronautServerProvider : ViaductServerProvider {

    private var applicationContext: ApplicationContext? = null

    override fun getViaduct(): Viaduct {
        // Start only the Micronaut DI container, NOT the HTTP server
        // ApplicationContext.run() does not start the embedded server
        val context = ApplicationContext.run()
        applicationContext = context

        // Get the Viaduct bean from the DI container
        return context.getBean(Viaduct::class.java)
    }

    /**
     * Clean up the application context when the server stops.
     * This is called automatically by ViaductServer.
     */
    fun close() {
        applicationContext?.close()
        applicationContext = null
    }
}
