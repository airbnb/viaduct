package viaduct.service.api.spi

import viaduct.apiannotations.StableApi

/**
 * SPI for service engineers to provide per-tenant code injection.
 *
 * The framework calls [bootstrap] once per tenant module during startup, sequentially and
 * never concurrently, passing the tenant module name and the bootstrap class declared in the
 * tenant's config file (or `null` if no bootstrap class is present). After all bootstrap calls
 * complete successfully, the framework calls [finalize] exactly once. The returned
 * [CodeInjector]s are not used until [finalize] has completed.
 */
// tag::module_bootstrapper_def
@StableApi
interface TenantModuleInjectorFactory {
    /**
     * Called once per tenant during bootstrapping.
     *
     * @param tenantName The slash-separated module name of the tenant being bootstrapped.
     * @param tenantBootstrapClass The class declared via `@TenantBootstrapper` in the tenant's
     *   config file, already loaded by the framework. `null` if the tenant has no bootstrap class.
     *   The returned [CodeInjector] will not be used until after [finalize] completes successfully.
     * @return A [CodeInjector] scoped to this tenant.
     */
    suspend fun bootstrap(
        tenantName: String,
        tenantBootstrapClass: Class<*>?,
    ): CodeInjector

    /**
     * Called once after all [bootstrap] calls complete successfully and before any returned
     * [CodeInjector] is used by the framework.
     *
     * Implementations that do not need cross-tenant finalization can rely on the default no-op.
     */
    suspend fun finalize() = Unit
}
// end::module_bootstrapper_def

/**
 * Convenience [TenantModuleInjectorFactory] that returns the same [CodeInjector] for every tenant.
 *
 * This is useful for service engineers who want a single shared injector across all tenant modules,
 * whether or not those modules declare a `@TenantBootstrapper` class.
 */
@StableApi
// tag::shared_bootstrapper_def[3]
open class SharedTenantModuleInjectorFactory(
    private val codeInjector: CodeInjector,
) : TenantModuleInjectorFactory {
    override suspend fun bootstrap(
        tenantName: String,
        tenantBootstrapClass: Class<*>?,
    ): CodeInjector = codeInjector
}

/** Default [TenantModuleInjectorFactory] that always returns [CodeInjector.Naive]. */
@StableApi
object NaiveTenantModuleInjectorFactory : SharedTenantModuleInjectorFactory(CodeInjector.Naive)
