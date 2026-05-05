package viaduct.service.api.spi

import viaduct.apiannotations.StableApi

/**
 * SPI for service engineers to provide per-tenant bootstrapping.
 *
 * The framework calls [bootstrap] once per tenant during startup, passing the tenant's name and the
 * bootstrap class declared in the tenant's config file (or `null` if no bootstrap class is present).
 * The returned [CodeInjector] is then used by the framework to instantiate that tenant's resolvers.
 */
@StableApi
interface TenantModuleBootstrapper {
    /**
     * Called once per tenant during bootstrapping.
     *
     * @param tenantName The name (package) of the tenant being bootstrapped.
     * @param tenantBootstrapClass The class declared via `@TenantBootstrapper` in the tenant's
     *   config file, already loaded by the framework. `null` if the tenant has no bootstrap class,
     *   in which case implementations should return [CodeInjector.Naive].
     * @return A [CodeInjector] scoped to this tenant.
     */
    suspend fun bootstrap(
        tenantName: String,
        tenantBootstrapClass: Class<*>?,
    ): CodeInjector
}
