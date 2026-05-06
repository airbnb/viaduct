@file:Suppress("DEPRECATION", "TYPEALIAS_EXPANSION_DEPRECATION")

package viaduct.engine.api.spi

import viaduct.service.api.spi.TenantAPIBootstrapper as BaseTenantAPIBootstrapper

/**
 * TenantAPIBootstrapper is a service that provides a list of all LegacyTenantModuleBootstrappers
 * that are needed to bootstrap all tenant modules for one flavor of the Tenant API.
 *
 * This is a type alias for the generic TenantAPIBootstrapper from service/api/spi,
 * specialized for LegacyTenantModuleBootstrapper.
 */
typealias TenantAPIBootstrapper = BaseTenantAPIBootstrapper<LegacyTenantModuleBootstrapper>

/** flatten an Iterable of TenantAPIBootstrapper into a single instance */
fun Iterable<TenantAPIBootstrapper>.flatten(): TenantAPIBootstrapper =
    with(BaseTenantAPIBootstrapper) {
        this@flatten.flatten()
    }
