@file:Suppress("MatchingDeclarationName")

package viaduct.engine

import viaduct.engine.api.bootstrap.executionregistry.ModuleConfigSource
import viaduct.engine.api.spi.TenantAPIBootstrapper
import viaduct.engine.runtime.tenantloading.ExecutionRegistryTenantAPIBootstrapper
import viaduct.service.api.spi.TenantModuleInjectorFactory

/**
 * Contains factory methods to create [TenantAPIBootstrapper] instances from execution registry config sources.
 */
object BootstrapperFactory {
    /**
     * Pass [grtPackagePrefix] to override the GRT package used by the executor factory, allowing
     * tenant implementations to decouple from the production default (e.g. contract tests generate
     * GRTs into the tenant package rather than the production constant).
     */
    fun fromConfigSources(
        tenantModuleInjectorFactory: TenantModuleInjectorFactory,
        moduleConfigSources: List<ModuleConfigSource>,
        grtPackagePrefix: String? = null,
    ): TenantAPIBootstrapper =
        ExecutionRegistryTenantAPIBootstrapper(
            tenantModuleInjectorFactory = tenantModuleInjectorFactory,
            moduleConfigSources = moduleConfigSources,
            grtPackagePrefix = grtPackagePrefix,
        )
}
