package viaduct.engine.runtime.tenantloading

import viaduct.engine.api.bootstrap.executionregistry.ModuleConfigSource
import viaduct.engine.api.spi.TenantAPIBootstrapper
import viaduct.engine.api.spi.TenantModuleBootstrapper
import viaduct.service.api.spi.TenantModuleInjectorFactory
import viaduct.utils.slf4j.logger

/**
 * [TenantAPIBootstrapper] adapter over [ModuleConfigBootstrapper].
 *
 * Retained as a compatibility shim so callers that still expect a [TenantAPIBootstrapper] (classic
 * wiring, remote resolvers, test fixtures) keep working while the primary path moves to feeding
 * [ModuleConfigSource]s directly into the engine. The bootstrap algorithm itself lives in
 * [ModuleConfigBootstrapper].
 *
 * If [moduleConfigSources] is null, registry resources are discovered from
 * `META-INF/viaduct/modules` on the current classpath for compatibility with the original
 * file-based bootstrap path.
 */
class ExecutionRegistryTenantAPIBootstrapper(
    private val tenantModuleInjectorFactory: TenantModuleInjectorFactory,
    private val moduleConfigSources: List<ModuleConfigSource>? = null,
    private val grtPackagePrefix: String? = null,
) : TenantAPIBootstrapper {
    override suspend fun tenantModuleBootstrappers(): Iterable<TenantModuleBootstrapper> {
        val configSources = moduleConfigSources
            ?: ExecutionRegistryConfigSourceCollector.fromResources()

        if (configSources.isEmpty()) {
            log.warn("No registry files provided to ExecutionRegistryTenantAPIBootstrapper")
        }

        return ModuleConfigBootstrapper(
            tenantModuleInjectorFactory = tenantModuleInjectorFactory,
            grtPackagePrefix = grtPackagePrefix,
        ).bootstrap(configSources)
    }

    companion object {
        private val log by logger()
    }
}
