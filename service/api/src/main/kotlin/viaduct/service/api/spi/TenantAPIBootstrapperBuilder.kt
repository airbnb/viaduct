package viaduct.service.api.spi

import viaduct.apiannotations.StableApi

/**
 * A tagging interface for builders of TenantAPIBootstrapper implementations.
 *
 * This interface is used by ViaductBuilder to accept builder instances
 * for creating TenantAPIBootstrapper implementations. As noted in StandardViaduct,
 * these instances must come from valid Tenant API implementations, which understand
 * a special protocol expected by StandardViaduct.
 *
 * @param T The type of module bootstrapper that TenantAPIBootstrapper will provide
 */
@StableApi
interface TenantAPIBootstrapperBuilder<T> {
    fun create(): TenantAPIBootstrapper<T>
}
