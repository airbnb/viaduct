package viaduct.engine.api

import viaduct.service.api.spi.TenantAPIBootstrapper as BaseTenantAPIBootstrapper

/**
 * TenantAPIBootstrapper is a service that provides a list of all TenantModuleBootstrappers
 * that are needed to bootstrap all tenant modules for one flavor of the Tenant API.
 *
 * This is a type alias for the generic TenantAPIBootstrapper from service/api/spi,
 * specialized for TenantModuleBootstrapper.
 */
typealias TenantAPIBootstrapper = BaseTenantAPIBootstrapper<TenantModuleBootstrapper>

/** flatten an Iterable of TenantAPIBootstrapper into a single instance */
fun Iterable<TenantAPIBootstrapper>.flatten(): TenantAPIBootstrapper =
    with(BaseTenantAPIBootstrapper) {
        this@flatten.flatten()
    }
