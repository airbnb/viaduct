@file:Suppress("ForbiddenImport")

package viaduct.java.api.testing

import viaduct.api.testing.featureapp.AbstractFeatureAppTestContractBase
import viaduct.engine.BootstrapperFactory
import viaduct.engine.api.spi.TenantAPIBootstrapperBuilder
import viaduct.service.api.spi.CodeInjector
import viaduct.service.api.spi.SharedTenantModuleInjectorFactory

/**
 * Contract test base class for Java tenant resolvers.
 *
 * Provides the Java-specific bootstrapper wiring using the file-based execution registry: the Java
 * registry-extractor annotation processor emits `META-INF/viaduct/modules/<pkg>.json` at build
 * time, and [BootstrapperFactory.fromResources] discovers it on the test classpath and instantiates
 * the [viaduct.java.runtime.bootstrap.ViaductJavaExecutorFactory] recorded in it. This mirrors the
 * Kotlin `KotlinFeatureAppTestContractBase`.
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
                BootstrapperFactory.fromResources(
                    tenantModuleInjectorFactory = SharedTenantModuleInjectorFactory(CodeInjector.Naive),
                    packagePrefix = featureAppPackagePrefix(),
                )
        }
}
