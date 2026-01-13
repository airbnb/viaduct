package viaduct.service.api.spi

import viaduct.apiannotations.StableApi

/**
 * TenantAPIBootstrapper is a service that provides a list of all TenantModuleBootstrappers
 * that are needed to bootstrap all tenant modules for one flavor of the Tenant API.
 *
 * This is a generic interface where T represents the type of module bootstrapper.
 * The engine layer provides a concrete type for T (TenantModuleBootstrapper).
 */
@StableApi
interface TenantAPIBootstrapper<T> {
    /**
     *  Provide a list of per-tenant-module bootstrap objects.
     *  The engine will call this once per Tenant API, and will
     *  iterate over the resulting iterator just once. This iterator
     *  is thread-safe to support parallel loading.
     *  @return list of bootstrappers, one per Viaduct tenant module.
     */
    suspend fun tenantModuleBootstrappers(): Iterable<T>

    private class Flatten<T>(
        val items: Iterable<TenantAPIBootstrapper<T>>
    ) : TenantAPIBootstrapper<T> {
        override suspend fun tenantModuleBootstrappers(): Iterable<T> = items.flatMap { it.tenantModuleBootstrappers() }
    }

    companion object {
        /** flatten an Iterable of TenantAPIBootstrapper into a single instance */
        fun <T> Iterable<TenantAPIBootstrapper<T>>.flatten(): TenantAPIBootstrapper<T> = Flatten(this)
    }
}
