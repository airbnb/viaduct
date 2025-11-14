package viaduct.devserve

import io.github.classgraph.ClassGraph
import org.slf4j.LoggerFactory
import viaduct.service.api.ViaductApplication
import viaduct.service.api.ViaductFactory
import kotlin.reflect.full.createInstance

/**
 * Discovers ViaductFactory implementations annotated with @ViaductApplication
 * using classpath scanning.
 */
object FactoryDiscovery {

    private val logger = LoggerFactory.getLogger(FactoryDiscovery::class.java)

    /**
     * Scans the classpath to find a class annotated with @ViaductApplication
     * that implements ViaductFactory.
     *
     * @return An instance of the discovered factory
     * @throws IllegalStateException if no factory is found or multiple factories are found
     */
    fun discoverFactory(): ViaductFactory {
        val factories = findFactoryClasses()

        return when (factories.size) {
            0 -> throw IllegalStateException(
                "No class found with @ViaductApplication annotation. " +
                "Please create a class that implements ViaductFactory and annotate it with @ViaductApplication."
            )
            1 -> factories.first()
            else -> throw IllegalStateException(
                "Multiple classes found with @ViaductApplication annotation: ${factories.map { it::class.qualifiedName }}. " +
                "Only one factory should be annotated with @ViaductApplication per application."
            )
        }
    }

    /**
     * Finds all classes annotated with @ViaductApplication that implement ViaductFactory.
     *
     * @return List of instantiated factory instances
     */
    private fun findFactoryClasses(): List<ViaductFactory> {
        val annotationName = ViaductApplication::class.java.name

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

                            // Verify it implements ViaductFactory
                            if (!ViaductFactory::class.java.isAssignableFrom(loadedClass)) {
                                logger.warn(
                                    "Skipping ${classInfo.name}: annotated with @ViaductApplication " +
                                    "but does not implement ViaductFactory"
                                )
                                return@mapNotNull null
                            }

                            // Verify it has a no-arg constructor
                            try {
                                loadedClass.getDeclaredConstructor()
                            } catch (e: NoSuchMethodException) {
                                logger.warn(
                                    "Skipping ${classInfo.name}: no no-argument constructor found. " +
                                    "ViaductFactory implementations must have a no-argument constructor."
                                )
                                return@mapNotNull null
                            }

                            // Create instance
                            val kClass = loadedClass.kotlin
                            @Suppress("UNCHECKED_CAST")
                            kClass.createInstance() as ViaductFactory
                        } catch (e: Exception) {
                            logger.error("Failed to instantiate factory class ${classInfo.name}", e)
                            null
                        }
                    }
            }
    }
}
