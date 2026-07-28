package viaduct.service.runtime

import viaduct.engine.api.bootstrap.executionregistry.ModuleConfigSource
import viaduct.service.api.spi.TenantModuleInjectorFactory

/**
 * Bundle of everything needed to bootstrap tenant module configs into the engine's
 * [viaduct.engine.runtime.tenantloading.StandardDispatcherRegistryFactory].
 *
 * Bound once in [StandardViaductModule] (parent injector) and injected into the schema-scoped
 * [SchemaScopedModule], where the schema becomes available and generated built-in config sources
 * are appended before the registry is built.
 *
 * @property moduleConfigSources Resource-backed tenant module configs (and any caller-supplied
 *   overrides). Generated built-in configs are appended later, in schema scope.
 * @property tenantModuleInjectorFactory Service-supplied per-tenant code injector factory.
 * @property grtPackagePrefix Optional override for the GRT package used by executor factories.
 * @property defaultQueryNodeResolversEnabled Whether to contribute the generated built-in resolvers
 *   (`Query.node`/`Query.nodes` and `@namespaceType`).
 */
data class ModuleBootstrapConfiguration(
    val moduleConfigSources: List<ModuleConfigSource>,
    val tenantModuleInjectorFactory: TenantModuleInjectorFactory,
    val grtPackagePrefix: String?,
    val defaultQueryNodeResolversEnabled: Boolean,
)
