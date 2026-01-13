package viaduct.service.runtime.noderesolvers

import viaduct.engine.api.TenantAPIBootstrapper
import viaduct.engine.api.TenantModuleBootstrapper
import viaduct.service.api.spi.TenantAPIBootstrapperBuilder

/**
 * ViaductNodeResolverAPIBootstrapper is responsible for creating system level bootstrapper(s) that are not
 * associated with any single Viaduct TenantModule
 */
class ViaductNodeResolverAPIBootstrapper : TenantAPIBootstrapper {
    override suspend fun tenantModuleBootstrappers(): Iterable<TenantModuleBootstrapper> {
        return listOf(ViaductQueryNodeResolverModuleBootstrapper())
    }

    class Builder : TenantAPIBootstrapperBuilder<TenantModuleBootstrapper> {
        override fun create(): TenantAPIBootstrapper = ViaductNodeResolverAPIBootstrapper()
    }
}
