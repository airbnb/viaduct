@file:Suppress("ForbiddenImport")

package viaduct.java.api.testing

import viaduct.api.testing.featureapp.AbstractFeatureAppTestContractBase
import viaduct.engine.api.mocks.MockTenantAPIBootstrapper
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.TenantModuleBootstrapper
import viaduct.java.runtime.bridge.DefaultJavaResolverClassFinder
import viaduct.java.runtime.bridge.JavaModuleBootstrapper
import viaduct.service.api.SchemaId
import viaduct.service.api.mocks.MockTenantAPIBootstrapperBuilder
import viaduct.service.api.spi.TenantAPIBootstrapperBuilder
import viaduct.service.api.spi.TenantCodeInjector

/**
 * Contract test base class for Java tenant resolvers.
 *
 * Provides the Java-specific bootstrapper wiring: resolver class discovery via
 * [DefaultJavaResolverClassFinder] and bootstrapping via [JavaModuleBootstrapper].
 *
 * Extend this class in contract tests that define `@TestSchema` and `@Test` methods.
 * Subclasses provide Java resolver implementations.
 */
abstract class JavaFeatureAppTestContractBase : AbstractFeatureAppTestContractBase() {
    /**
     * Intentionally computed lazily instead of in a property initializer so the constructor
     * does not throw, which SpotBugs flags as CT_CONSTRUCTOR_THROW.
     */
    private fun derivedClassPackage(): String =
        this::class.java.`package`?.name
            ?: error("Unable to read package name from subclass ${this::class.simpleName}")

    protected open fun featureAppPackagePrefix(): String = derivedClassPackage().substringBeforeLast('.')

    protected open fun grtPackagePrefix(): String = "${featureAppPackagePrefix()}.grt"

    private val classFinder by lazy {
        DefaultJavaResolverClassFinder(
            tenantPackage = featureAppPackagePrefix(),
            grtPackagePrefix = grtPackagePrefix()
        )
    }

    private val bootstrapper by lazy {
        JavaModuleBootstrapper(classFinder, TenantCodeInjector.Naive)
    }

    override fun createBootstrapperBuilder(): TenantAPIBootstrapperBuilder<TenantModuleBootstrapper> = MockTenantAPIBootstrapperBuilder(MockTenantAPIBootstrapper(listOf(bootstrapper)))

    /**
     * Returns the field resolver executor for a given type and field.
     * Useful for testing that required selections are properly wired.
     */
    protected fun getFieldResolverExecutor(
        typeName: String,
        fieldName: String
    ): FieldResolverExecutor? {
        tryBuildViaductService()
        val schema = viaductService.engineRegistry.getSchema(SchemaId.Full)
        val executors = bootstrapper.fieldResolverExecutors(schema)
        return executors.find { (coordinate, _) ->
            coordinate.first == typeName && coordinate.second == fieldName
        }?.second
    }
}
