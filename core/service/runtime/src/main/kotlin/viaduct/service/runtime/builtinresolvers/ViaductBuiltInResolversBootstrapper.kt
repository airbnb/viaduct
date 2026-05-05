package viaduct.service.runtime.builtinresolvers

import viaduct.engine.api.spi.LegacyTenantModuleBootstrapper
import viaduct.engine.api.spi.TenantAPIBootstrapper
import viaduct.service.api.spi.TenantAPIBootstrapperBuilder

/**
 * Bootstrapper for built-in resolvers that are not associated with any single tenant module.
 *
 * This includes:
 * - Query node resolvers: `Query.node` and `Query.nodes` field resolvers
 * - Namespace type field resolvers: synthetic resolvers for fields returning `@namespaceType` types
 */
class ViaductBuiltInResolversBootstrapper : TenantAPIBootstrapper {
    override suspend fun tenantModuleBootstrappers(): Iterable<LegacyTenantModuleBootstrapper> {
        return listOf(
            ViaductQueryNodeResolverModuleBootstrapper(),
            NamespaceTypeResolverModuleBootstrapper(),
        )
    }

    class Builder : TenantAPIBootstrapperBuilder<LegacyTenantModuleBootstrapper> {
        override fun create(): TenantAPIBootstrapper = ViaductBuiltInResolversBootstrapper()
    }
}
