package viaduct.java.runtime.bridge

import viaduct.java.api.annotations.ResolverFor
import viaduct.java.api.types.Arguments
import viaduct.java.api.types.GRT
import viaduct.java.runtime.bootstrap.JavaResolverClassFinder
import viaduct.utils.classgraph.ClassGraphScanner

/**
 * Default implementation of [JavaResolverClassFinder] using [ClassGraphScanner] for classpath scanning.
 *
 * This class provides efficient classpath scanning to discover resolver classes at runtime,
 * delegating to the shared [ClassGraphScanner] utility.
 *
 * ## Package Configuration
 *
 * Two packages must be configured:
 * - **tenantPackage** - The package containing resolver base classes and implementations
 * - **grtPackagePrefix** - The package containing generated GRT and Arguments classes
 *
 * ## Example
 *
 * ```kotlin
 * val finder = DefaultJavaResolverClassFinder(
 *     tenantPackage = "com.mycompany.resolvers",
 *     grtPackagePrefix = "com.mycompany.grts"
 * )
 * ```
 *
 * @param tenantPackage the package containing resolver classes (both bases and implementations)
 * @param grtPackagePrefix the package prefix for generated GRT and Arguments classes
 */
class DefaultJavaResolverClassFinder(
    private val tenantPackage: String,
    private val grtPackagePrefix: String,
) : JavaResolverClassFinder {
    private val scanner = ClassGraphScanner.optimizedForPackagePrefix(tenantPackage)

    override fun resolverClassesInPackage(): Set<Class<*>> = scanner.getTypesAnnotatedWith(ResolverFor::class.java, listOf(tenantPackage))

    override fun nodeResolverForClassesInPackage(): Set<Class<*>> {
        // NodeResolverFor is not yet implemented in the Java API
        // This will be implemented when node resolver support is added
        return emptySet()
    }

    override fun <T : Any?> getSubTypesOf(type: Class<T>): Set<Class<out T>> = scanner.getSubTypesOf(type, listOf(tenantPackage))

    @Suppress("UNCHECKED_CAST")
    override fun grtClassForName(typeName: String): Class<out GRT> {
        val fullClassName = "$grtPackagePrefix.$typeName"
        val clazz = Class.forName(fullClassName)
        require(GRT::class.java.isAssignableFrom(clazz)) {
            "Class $fullClassName exists but does not implement GRT"
        }
        return clazz as Class<out GRT>
    }

    @Suppress("UNCHECKED_CAST")
    override fun argumentClassForName(className: String): Class<out Arguments> {
        val fullClassName = "$grtPackagePrefix.$className"
        val clazz = Class.forName(fullClassName)
        require(Arguments::class.java.isAssignableFrom(clazz)) {
            "Class $fullClassName exists but does not implement Arguments"
        }
        return clazz as Class<out Arguments>
    }
}
