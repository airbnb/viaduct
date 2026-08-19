@file:Suppress("ForbiddenImport")

package viaduct.java.api.testing

import viaduct.api.testing.featureapp.AbstractFeatureAppTestContractBase
import viaduct.api.testing.featureapp.MissingResolverImplementationException
import viaduct.engine.BootstrapperFactory
import viaduct.engine.api.spi.TenantAPIBootstrapperBuilder
import viaduct.engine.runtime.tenantloading.ExecutionRegistryConfigSourceCollector
import viaduct.java.api.annotations.NodeResolverFor
import viaduct.java.api.annotations.Resolver
import viaduct.java.api.annotations.ResolverFor
import viaduct.service.api.spi.CodeInjector
import viaduct.service.api.spi.SharedTenantModuleInjectorFactory

/**
 * Contract test base class for Java tenant resolvers.
 *
 * Provides the Java-specific bootstrapper wiring using the file-based execution registry: the Java
 * registry-extractor annotation processor emits `META-INF/viaduct/modules/<pkg>.json` at build
 * time, and [ExecutionRegistryConfigSourceCollector] discovers it on the test classpath. The
 * resulting bootstrapper instantiates the [viaduct.java.runtime.bootstrap.ViaductJavaExecutorFactory]
 * recorded in it. This mirrors the Kotlin `KotlinFeatureAppTestContractBase`.
 *
 * Extend this class in contract tests that define `@TestSchema` and `@Test` methods.
 * Subclasses provide Java resolver implementations.
 */
abstract class FeatureAppTestContractBase : AbstractFeatureAppTestContractBase() {
    /**
     * Intentionally computed lazily instead of in a property initializer so the constructor
     * does not throw.
     */
    private fun derivedClassPackage(): String =
        this::class.java.`package`?.name
            ?: error("Unable to read package name from subclass ${this::class.simpleName}")

    protected open fun featureAppPackagePrefix(): String = derivedClassPackage().substringBeforeLast('.')

    override fun createBootstrapperBuilder(): TenantAPIBootstrapperBuilder =
        object : TenantAPIBootstrapperBuilder {
            override fun create() =
                BootstrapperFactory.fromConfigSources(
                    tenantModuleInjectorFactory = SharedTenantModuleInjectorFactory(CodeInjector.Naive),
                    moduleConfigSources = ExecutionRegistryConfigSourceCollector.fromResources(featureAppPackagePrefix()),
                    grtPackagePrefix = derivedClassPackage(),
                )
        }
}

/**
 * Java-aware resolver-completeness validation for contract tests whose shared base is Kotlin.
 *
 * Generated resolver bases are supplied explicitly because Java codegen and registry extraction
 * happen at build time; no runtime classpath scan is needed.
 */
object JavaResolverImplementationValidator {
    private val builtInResolverFields = setOf("Query" to "node", "Query" to "nodes")

    @JvmStatic
    fun validate(
        implementationContainer: Class<*>,
        vararg resolverBaseClasses: Class<*>,
    ) {
        val resolverImplementations = implementationContainer.declaredClasses
            .filter { it.isAnnotationPresent(Resolver::class.java) }
        val missingResolvers = resolverBaseClasses.mapNotNull { baseClass ->
            val resolverFor = baseClass.getAnnotation(ResolverFor::class.java)
            if (resolverFor != null) {
                val coordinate = resolverFor.typeName to resolverFor.fieldName
                if (coordinate in builtInResolverFields) {
                    null
                } else if (resolverImplementations.none(baseClass::isAssignableFrom)) {
                    "${resolverFor.typeName}.${resolverFor.fieldName}"
                } else {
                    null
                }
            } else {
                val nodeResolverFor = baseClass.getAnnotation(NodeResolverFor::class.java)
                    ?: error("Expected $baseClass to have @ResolverFor or @NodeResolverFor")
                if (resolverImplementations.none(baseClass::isAssignableFrom)) {
                    "Node(${nodeResolverFor.typeName})"
                } else {
                    null
                }
            }
        }

        if (missingResolvers.isNotEmpty()) {
            throw MissingResolverImplementationException(missingResolvers)
        }
    }
}
