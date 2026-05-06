@file:Suppress("DEPRECATION", "TYPEALIAS_EXPANSION_DEPRECATION")

package viaduct.engine

import io.github.classgraph.ClassGraph
import java.net.URL
import viaduct.apiannotations.VisibleForTest
import viaduct.engine.api.spi.TenantAPIBootstrapper
import viaduct.engine.runtime.tenantloading.ExecutionRegistryTenantAPIBootstrapper
import viaduct.service.api.spi.CodeInjector

private const val REGISTRY_RESOURCE_PATH = "META-INF/viaduct/modules"

/**
 * Contains factory methods to create [TenantAPIBootstrapper] instances by various strategies.
 */
object BootstrapperFactory {
    /**
     * Returns a bootstrapper that loads all tenant module registry files from the classpath.
     */
    fun fromResources(tenantCodeInjector: CodeInjector): TenantAPIBootstrapper =
        ExecutionRegistryTenantAPIBootstrapper(
            tenantCodeInjector = tenantCodeInjector,
            registryUrls = collectRegistryUrls(packagePrefix = null),
        )

    /**
     * Test-only variant that scopes discovery to JSON files whose name starts with [packagePrefix].
     *
     * Use this in tests where multiple unrelated module JSONs share the same classpath — loading
     * all of them would fail because their resolvers reference types not present in the test schema.
     */
    @VisibleForTest
    fun fromResources(
        tenantCodeInjector: CodeInjector,
        packagePrefix: String,
    ): TenantAPIBootstrapper =
        ExecutionRegistryTenantAPIBootstrapper(
            tenantCodeInjector = tenantCodeInjector,
            registryUrls = collectRegistryUrls(packagePrefix = packagePrefix),
        )

    private fun collectRegistryUrls(packagePrefix: String?): List<URL> {
        val pattern = if (packagePrefix != null) {
            Regex("$REGISTRY_RESOURCE_PATH/${Regex.escape(packagePrefix)}.*\\.json").toPattern()
        } else {
            Regex("$REGISTRY_RESOURCE_PATH/.*\\.json").toPattern()
        }

        return ClassGraph()
            .acceptPaths(REGISTRY_RESOURCE_PATH)
            .scan()
            .use { result -> result.getResourcesMatchingPattern(pattern).map { it.url } }
    }
}
