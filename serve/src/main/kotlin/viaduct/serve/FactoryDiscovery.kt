package viaduct.serve

import io.github.classgraph.ClassGraph
import kotlin.reflect.full.createInstance
import org.slf4j.LoggerFactory

/**
 * Discovers ViaductProvider implementations annotated with @ViaductServerConfiguration
 * using classpath scanning.
 */
object FactoryDiscovery {
    private val logger = LoggerFactory.getLogger(FactoryDiscovery::class.java)

    /**
     * Scans the classpath to find a class annotated with @ViaductServerConfiguration
     * that implements ViaductProvider.
     *
     * If no provider is found, falls back to DefaultViaductFactory using the provided
     * packagePrefix.
     *
     * @param packagePrefix The package prefix for tenant modules, required when falling
     *        back to DefaultViaductFactory
     * @return An instance of the discovered provider, or DefaultViaductFactory if none found
     * @throws IllegalStateException if multiple providers are found or if packagePrefix is
     *         null when falling back to DefaultViaductFactory
     */
    fun discoverProvider(packagePrefix: String?): ViaductProvider {
        val providers = findProviderClasses()

        return when (providers.size) {
            0 -> {
                if (packagePrefix == null) {
                    throw IllegalStateException(
                        "No @ViaductServerConfiguration found and no packagePrefix provided. " +
                            "Either create a @ViaductServerConfiguration provider class or specify the package prefix:\n" +
                            "  ./gradlew serve -Pserve.packagePrefix=com.example.app\n" +
                            "Or set it in your build.gradle.kts:\n" +
                            "  tasks.named<JavaExec>(\"serve\") {\n" +
                            "    systemProperty(\"serve.packagePrefix\", \"com.example.app\")\n" +
                            "  }"
                    )
                }
                logger.info("No @ViaductServerConfiguration found, using DefaultViaductFactory with prefix: {}", packagePrefix)
                DefaultViaductFactory(packagePrefix)
            }
            1 -> {
                logger.info("Found @ViaductServerConfiguration: {}", providers.first()::class.qualifiedName)
                providers.first()
            }
            else -> throw IllegalStateException(
                "Multiple classes found with @ViaductServerConfiguration annotation: ${providers.map { it::class.qualifiedName }}. " +
                    "Only one provider should be annotated with @ViaductServerConfiguration per application."
            )
        }
    }

    /**
     * Finds all classes annotated with @ViaductServerConfiguration that implement ViaductProvider.
     *
     * @return List of instantiated provider instances
     */
    private fun findProviderClasses(): List<ViaductProvider> {
        val annotationName = ViaductServerConfiguration::class.java.name

        return ClassGraph()
            .enableAnnotationInfo()
            .enableClassInfo()
            .scan()
            .use { scanResult ->
                scanResult
                    .getClassesWithAnnotation(annotationName)
                    .mapNotNull { classInfo ->
                        try {
                            val loadedClass = classInfo.loadClass()

                            // Verify it implements ViaductProvider
                            if (!ViaductProvider::class.java.isAssignableFrom(loadedClass)) {
                                logger.warn(
                                    "Skipping {}: annotated with @ViaductServerConfiguration but does not implement ViaductProvider",
                                    classInfo.name
                                )
                                return@mapNotNull null
                            }

                            // Verify it has a no-arg constructor
                            try {
                                loadedClass.getDeclaredConstructor()
                            } catch (e: NoSuchMethodException) {
                                logger.warn(
                                    "Skipping {}: no no-argument constructor found. ViaductProvider implementations must have a no-argument constructor.",
                                    classInfo.name
                                )
                                return@mapNotNull null
                            }

                            // Create instance
                            val kClass = loadedClass.kotlin
                            @Suppress("UNCHECKED_CAST")
                            kClass.createInstance() as ViaductProvider
                        } catch (e: Exception) {
                            logger.error("Failed to instantiate provider class {}", classInfo.name, e)
                            null
                        }
                    }
            }
    }
}
