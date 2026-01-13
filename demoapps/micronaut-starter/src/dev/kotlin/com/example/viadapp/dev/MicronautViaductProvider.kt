package com.example.viadapp.dev

import io.micronaut.context.ApplicationContext
import viaduct.serve.ViaductProvider
import viaduct.serve.ViaductServerConfiguration
import viaduct.service.api.Viaduct

/**
 * Development-only Viaduct provider for Micronaut applications.
 *
 * This provider is used by the ViaductServer (serve task) during development.
 * It starts a MINIMAL Micronaut DI container with limited package scanning -
 * only loading the production and resolver packages. This provides the fastest
 * possible startup while still enabling dependency injection.
 *
 * Key benefits:
 * - Minimal startup: Only scans specified packages, not the entire classpath
 * - No HTTP server: Doesn't load controllers, filters, or server components
 * - DI support: Resolvers can still have dependencies injected
 *
 * Note: This class is only used in development mode via the serve task.
 * Production deployments use the full Micronaut HTTP server with the
 * configuration in the production package.
 */
@ViaductServerConfiguration
class MicronautViaductProvider : ViaductProvider {
    private var applicationContext: ApplicationContext? = null

    override fun getViaduct(): Viaduct {
        // Start a minimal ApplicationContext with limited package scanning
        // Only scan the packages needed for Viaduct:
        // - production: ViaductConfiguration, MicronautTenantCodeInjector
        // - resolvers: Resolver implementations
        val context = ApplicationContext.builder()
            .packages(
                "com.example.viadapp.production",
                "com.example.viadapp"
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
