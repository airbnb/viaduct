package viaduct.service.runtime.builtinresolvers

import viaduct.engine.api.spi.TenantAPIBootstrapper
import viaduct.engine.api.spi.TenantAPIBootstrapperBuilder
import viaduct.engine.api.spi.TenantModuleBootstrapper

/**
 * Bootstrapper for built-in resolvers that are not associated with any single tenant module.
 *
 * This includes:
 * - Query node resolvers: `Query.node` and `Query.nodes` field resolvers
 * - Namespace type field resolvers: synthetic resolvers for fields returning `@namespaceType` types
 */
class ViaductBuiltInResolversBootstrapper : TenantAPIBootstrapper {
    override suspend fun tenantModuleBootstrappers(): Iterable<TenantModuleBootstrapper> {
        return listOf(
            ViaductQueryNodeResolverModuleBootstrapper(),
            NamespaceTypeResolverModuleBootstrapper(),
        )
    }

    class Builder : TenantAPIBootstrapperBuilder {
        override fun create(): TenantAPIBootstrapper = ViaductBuiltInResolversBootstrapper()
    }
}
