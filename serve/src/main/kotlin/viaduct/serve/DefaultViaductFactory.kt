package viaduct.serve

import org.slf4j.LoggerFactory
import viaduct.service.BasicViaductFactory
import viaduct.service.TenantRegistrationInfo
import viaduct.service.api.Viaduct

/**
 * Default ViaductProvider implementation that creates a Viaduct instance using
 * the provided package prefix.
 *
 * This factory is used as the fallback when no @ViaductServerConfiguration is found on the classpath.
 * It provides a simple way to start the serve server without creating a custom provider.
 *
 * Usage via Gradle:
 *   ./gradlew serve -Pserve.packagePrefix=com.example.app
 *
 * Limitations:
 * - Only works with resolvers that have no-argument constructors
 * - Cannot inject dependencies into resolvers
 * - For more complex setups, create a @ViaductServerConfiguration class
 *
 * @param packagePrefix The package prefix for tenant modules (e.g., "com.example.app")
 */
class DefaultViaductFactory(
    private val packagePrefix: String
) : ViaductProvider {
    private val logger = LoggerFactory.getLogger(DefaultViaductFactory::class.java)

    override fun getViaduct(): Viaduct {
        logger.warn("╔════════════════════════════════════════════════════════════════════════════╗")
        logger.warn("║  NO @ViaductServerConfiguration FOUND - USING DEFAULT FACTORY             ║")
        logger.warn("╠════════════════════════════════════════════════════════════════════════════╣")
        logger.warn("║  ⚠️  DEPENDENCY INJECTION IS NOT AVAILABLE IN THIS MODE                    ║")
        logger.warn("║                                                                            ║")
        logger.warn("║  Only @Resolver classes with zero-argument constructors will work.        ║")
        logger.warn("║  If your resolvers require injected dependencies, they will fail.         ║")
        logger.warn("║                                                                            ║")
        logger.warn("║  To enable DI, create a class annotated with @ViaductServerConfiguration  ║")
        logger.warn("║  that implements ViaductProvider and returns your Viaduct instance.       ║")
        logger.warn("║  See: https://viaduct.dev/docs/developers/serve                           ║")
        logger.warn("╚════════════════════════════════════════════════════════════════════════════╝")

        logger.info("Using package prefix: {}", packagePrefix)

        // Create Viaduct using BasicViaductFactory
        return BasicViaductFactory.create(
            tenantRegistrationInfo = TenantRegistrationInfo(
                tenantPackagePrefix = packagePrefix
            )
        )
    }
}
