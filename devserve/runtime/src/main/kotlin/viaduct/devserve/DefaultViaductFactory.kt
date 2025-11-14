package viaduct.devserve

import io.github.classgraph.ClassGraph
import org.slf4j.LoggerFactory
import viaduct.api.Resolver
import viaduct.service.BasicViaductFactory
import viaduct.service.TenantRegistrationInfo
import viaduct.service.api.Viaduct
import viaduct.service.api.ViaductFactory

/**
 * A default ViaductFactory implementation that uses classpath scanning to automatically
 * discover and register resolvers annotated with @Resolver.
 *
 * This factory provides a zero-configuration development experience:
 * - Scans the classpath for @Resolver annotated classes
 * - Automatically determines the package prefix from discovered resolvers
 * - Uses default (no-argument) constructors to instantiate resolvers
 * - Configures Viaduct with sensible defaults for development
 *
 * Usage:
 * ```kotlin
 * @ViaductApplication
 * class MyFactory : DefaultViaductFactory()
 * ```
 *
 * Or with custom configuration:
 * ```kotlin
 * @ViaductApplication
 * class MyFactory : DefaultViaductFactory(
 *     packagePrefix = "com.example.myapp"
 * )
 * ```
 *
 * @param packagePrefix Optional package prefix to limit resolver scanning.
 *                      If not provided, will be auto-detected from discovered resolvers.
 */
open class DefaultViaductFactory(
    private val packagePrefix: String? = null
) : ViaductFactory {

    private val logger = LoggerFactory.getLogger(DefaultViaductFactory::class.java)

    override fun createViaduct(): Viaduct {
        logger.info("Creating Viaduct using DefaultViaductFactory...")

        // Discover resolvers
        val resolvers = discoverResolvers()

        if (resolvers.isEmpty()) {
            logger.warn("No @Resolver annotated classes found on classpath!")
            logger.warn("Make sure your resolver classes are:")
            logger.warn("  1. Annotated with @Resolver")
            logger.warn("  2. Have a no-argument constructor")
            logger.warn("  3. Are on the classpath")
        }

        // Determine package prefix
        val effectivePackagePrefix = packagePrefix ?: detectPackagePrefix(resolvers)

        logger.info("Using package prefix: $effectivePackagePrefix")
        logger.info("Found ${resolvers.size} resolver(s): ${resolvers.map { it.simpleName }}")

        // Create Viaduct using BasicViaductFactory
        return BasicViaductFactory.create(
            tenantRegistrationInfo = TenantRegistrationInfo(
                tenantPackagePrefix = effectivePackagePrefix
            )
        )
    }

    /**
     * Discovers all classes annotated with @Resolver on the classpath.
     */
    private fun discoverResolvers(): List<Class<*>> {
        val annotationName = Resolver::class.java.name

        logger.debug("Scanning classpath for @Resolver annotated classes...")

        return ClassGraph()
            .enableAnnotationInfo()
            .enableClassInfo()
            .apply {
                // Limit scan to package prefix if provided
                packagePrefix?.let { acceptPackages(it) }
            }
            .scan()
            .use { scanResult ->
                scanResult
                    .getClassesWithAnnotation(annotationName)
                    .mapNotNull { classInfo ->
                        try {
                            val clazz = classInfo.loadClass()

                            // Verify it has a no-arg constructor
                            try {
                                clazz.getDeclaredConstructor()
                                logger.debug("Found resolver: ${clazz.name}")
                                clazz
                            } catch (e: NoSuchMethodException) {
                                logger.warn(
                                    "Skipping resolver ${clazz.name}: no no-argument constructor found. " +
                                    "Add a no-argument constructor or use a custom ViaductFactory."
                                )
                                null
                            }
                        } catch (e: Exception) {
                            logger.error("Failed to load resolver class ${classInfo.name}", e)
                            null
                        }
                    }
            }
    }

    /**
     * Detects the common package prefix from discovered resolver classes.
     *
     * Uses the most specific common package prefix among all resolvers.
     * For example, if resolvers are in:
     * - com.example.app.resolvers.QueryResolver
     * - com.example.app.resolvers.MutationResolver
     * - com.example.app.users.UserResolver
     *
     * The detected prefix will be "com.example.app"
     */
    private fun detectPackagePrefix(resolvers: List<Class<*>>): String {
        if (resolvers.isEmpty()) {
            logger.warn("No resolvers found, defaulting to empty package prefix")
            return ""
        }

        // Get all package names
        val packages = resolvers.mapNotNull { it.`package`?.name }

        if (packages.isEmpty()) {
            logger.warn("No package information found for resolvers, defaulting to empty package prefix")
            return ""
        }

        // Find the common prefix
        val commonPrefix = packages.reduce { acc, packageName ->
            commonPrefix(acc, packageName)
        }

        // Trim to the last complete package segment
        val trimmedPrefix = commonPrefix.substringBeforeLast(".", commonPrefix)

        logger.debug("Detected package prefix from ${packages.size} resolver package(s): $trimmedPrefix")

        return trimmedPrefix
    }

    /**
     * Finds the common prefix between two strings (package names).
     */
    private fun commonPrefix(s1: String, s2: String): String {
        val minLength = minOf(s1.length, s2.length)
        var i = 0
        while (i < minLength && s1[i] == s2[i]) {
            i++
        }
        return s1.substring(0, i)
    }
}
