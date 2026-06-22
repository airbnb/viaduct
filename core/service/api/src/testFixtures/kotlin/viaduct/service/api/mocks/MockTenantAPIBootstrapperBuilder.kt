package viaduct.service.api.mocks

import viaduct.engine.api.spi.TenantAPIBootstrapper
import viaduct.engine.api.spi.TenantAPIBootstrapperBuilder

/**
 * Test utility that wraps a [TenantAPIBootstrapper] in a [TenantAPIBootstrapperBuilder].
 *
 * In tests it is often convenient to create a [TenantAPIBootstrapper] directly (e.g. via a
 * mock or a test-specific implementation) without going through the normal builder pipeline.
 * Invoking this object produces a [TenantAPIBootstrapperBuilder] whose [create] method simply
 * returns the provided bootstrapper.
 */
object MockTenantAPIBootstrapperBuilder {
    operator fun invoke(bootstrapper: TenantAPIBootstrapper) =
        object : TenantAPIBootstrapperBuilder {
            override fun create() = bootstrapper
        }
}
