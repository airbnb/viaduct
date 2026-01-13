package viaduct.serve

import viaduct.service.api.Viaduct

/**
 * Interface for providing a Viaduct instance to the serve server.
 *
 * Implement this interface and annotate with [@ViaductServerConfiguration] to integrate
 * your DI framework with the serve server. The server discovers your implementation
 * via classpath scanning for the annotation.
 *
 * **Why use this?**
 * - Enables dependency injection in your resolvers
 * - Uses the same Viaduct configuration as production
 * - No need to duplicate configuration
 *
 * **Example with Micronaut:**
 * ```kotlin
 * @ViaductServerConfiguration
 * class MicronautViaductProvider : ViaductProvider {
 *     override fun getViaduct(): Viaduct {
 *         val context = ApplicationContext.run()
 *         return context.getBean(Viaduct::class.java)
 *     }
 * }
 * ```
 *
 * **Example with manual configuration:**
 * ```kotlin
 * @ViaductServerConfiguration
 * class MyViaductProvider : ViaductProvider {
 *     override fun getViaduct(): Viaduct {
 *         return ViaductBuilder()
 *             .withTenantModule(MyTenantModule())
 *             .build()
 *     }
 * }
 * ```
 *
 * **Without @ViaductServerConfiguration:**
 * If no annotated implementation is found, serve falls back to [DefaultViaductFactory]
 * which scans for @Resolver classes with zero-argument constructors.
 * This mode does NOT support dependency injection.
 *
 * @see ViaductServerConfiguration
 */
interface ViaductProvider {
    /**
     * Returns the Viaduct instance to be used by the serve server.
     *
     * This method is called once during serve startup.
     * The implementation should initialize any DI context and return the Viaduct instance.
     *
     * @return The Viaduct instance to use for serving GraphQL requests
     */
    fun getViaduct(): Viaduct
}
