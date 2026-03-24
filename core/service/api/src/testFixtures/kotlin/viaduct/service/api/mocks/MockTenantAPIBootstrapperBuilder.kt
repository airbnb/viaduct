package viaduct.service.api.mocks

import viaduct.engine.api.spi.TenantAPIBootstrapper
import viaduct.engine.api.spi.TenantModuleBootstrapper
import viaduct.service.api.spi.TenantAPIBootstrapperBuilder

object MockTenantAPIBootstrapperBuilder {
    operator fun invoke(bootstrapper: TenantAPIBootstrapper) =
        object : TenantAPIBootstrapperBuilder<TenantModuleBootstrapper> {
            override fun create() = bootstrapper
        }
}
