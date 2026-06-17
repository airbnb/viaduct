package viaduct.java.runtime.bridge

import viaduct.java.api.annotations.NodeResolverFor
import viaduct.java.api.annotations.ResolverFor
import viaduct.java.api.internal.ResolverClassFinder
import viaduct.java.api.types.Arguments
import viaduct.java.api.types.GRT
import viaduct.utils.classgraph.ClassGraphScanner

/**
 * Default implementation of [ResolverClassFinder] using [ClassGraphScanner] for classpath scanning.
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
 * val finder = DefaultResolverClassFinder(
 *     tenantPackage = "com.mycompany.resolvers",
 *     grtPackagePrefix = "com.mycompany.grts"
 * )
 * ```
 *
 * @param tenantPackage the package containing resolver classes (both bases and implementations)
 * @param grtPackagePrefix the package prefix for generated GRT and Arguments classes
 */
class DefaultResolverClassFinder(
    private val tenantPackage: String,
    private val grtPackagePrefix: String,
) : ResolverClassFinder {
    // Lazy so the name-only lookups (grtClassForName/argumentClassForName) can be used without
    // paying for a classpath scan — e.g. by ViaductJavaExecutorFactory, which discovers resolvers
    // from the file-based registry rather than by scanning.
    private val scanner by lazy { ClassGraphScanner.optimizedForPackagePrefix(tenantPackage) }

    override fun resolverClassesInPackage(): Set<Class<*>> = scanner.getTypesAnnotatedWith(ResolverFor::class.java, listOf(tenantPackage))

    override fun nodeResolverForClassesInPackage(): Set<Class<*>> = scanner.getTypesAnnotatedWith(NodeResolverFor::class.java, listOf(tenantPackage))

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
