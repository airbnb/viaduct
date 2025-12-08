package com.example.viadapp.serve

import io.micronaut.context.ApplicationContext
import viaduct.serve.ViaductServerConfiguration
import viaduct.serve.ViaductServerProvider
import viaduct.service.api.Viaduct

/**
 * Viaduct Server provider for Micronaut applications.
 *
 * This provider starts a MINIMAL Micronaut DI container with limited package
 * scanning - only loading the injector and resolver packages. This provides
 * the fastest possible startup while still enabling dependency injection.
 *
 * Key benefits:
 * - Minimal startup: Only scans specified packages, not the entire classpath
 * - No HTTP server: Doesn't load controllers, filters, or server components
 * - DI support: Resolvers can still have dependencies injected
 */
@ViaductServerConfiguration
class MicronautServerProvider : ViaductServerProvider {

    private var applicationContext: ApplicationContext? = null

    override fun getViaduct(): Viaduct {
        // Start a minimal ApplicationContext with limited package scanning
        // Only scan the packages needed for Viaduct:
        // - injector: ViaductConfiguration, MicronautTenantCodeInjector
        // - resolvers: Resolver implementations
        val context = ApplicationContext.builder()
            .packages(
                "com.example.viadapp.injector",
                "com.example.viadapp.resolvers"
            )
            .start()
        applicationContext = context

        // Get the Viaduct bean from the DI container
        return context.getBean(Viaduct::class.java)
    }

    /**
     * Clean up the application context when the server stops.
     */
    fun close() {
        applicationContext?.close()
        applicationContext = null
    }
}
