@file:Suppress("DEPRECATION", "TYPEALIAS_EXPANSION_DEPRECATION") // for imports of legacy bootstrap shim

package viaduct.service.api.mocks

import viaduct.engine.api.spi.LegacyTenantModuleBootstrapper
import viaduct.engine.api.spi.TenantAPIBootstrapper
import viaduct.service.api.spi.TenantAPIBootstrapperBuilder

object MockTenantAPIBootstrapperBuilder {
    operator fun invoke(bootstrapper: TenantAPIBootstrapper) =
        object : TenantAPIBootstrapperBuilder<LegacyTenantModuleBootstrapper> {
            override fun create() = bootstrapper
        }
}
