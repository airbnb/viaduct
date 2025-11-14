package viaduct.service.api

/**
 * Factory interface for creating Viaduct instances.
 *
 * Implement this interface and annotate your implementation with [@ViaductApplication][ViaductApplication]
 * to enable automatic discovery in development mode (`./gradlew devserve`).
 *
 * This factory pattern can also be used in production for consistent Viaduct initialization
 * across different deployment environments.
 *
 * ## Quick Start with DefaultViaductFactory
 *
 * For zero-configuration development, extend `DefaultViaductFactory` (from `devserve-runtime`)
 * which automatically discovers resolvers using classpath scanning:
 *
 * ```kotlin
 * @ViaductApplication
 * class MyFactory : DefaultViaductFactory()
 * ```
 *
 * This will:
 * - Scan classpath for @Resolver annotated classes
 * - Auto-detect package prefix from discovered resolvers
 * - Use no-argument constructors to instantiate resolvers
 * - Configure Viaduct with sensible defaults for development
 *
 * ## Custom Configuration
 *
 * For production or custom needs, implement ViaductFactory directly:
 *
 * ```kotlin
 * @ViaductApplication
 * class MyViaductFactory : ViaductFactory {
 *     override fun createViaduct(): Viaduct {
 *         return ViaductBuilder()
 *             .withTenantAPIBootstrapperBuilder(myBootstrapper)
 *             .withFlagManager(myFlagManager)
 *             .withMeterRegistry(myMetrics)
 *             .build()
 *     }
 * }
 * ```
 *
 * @see ViaductApplication
 */
interface ViaductFactory {
    /**
     * Creates and configures a Viaduct instance.
     *
     * This method is called:
     * - Once on startup in development mode (`devserve`)
     * - On every hot-reload when files change (in development mode)
     * - Whenever the factory is invoked in production deployments
     *
     * @return A fully configured Viaduct instance ready to execute GraphQL operations
     */
    fun createViaduct(): Viaduct
}
