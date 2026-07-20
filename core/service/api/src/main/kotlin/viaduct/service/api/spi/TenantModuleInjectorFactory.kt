package viaduct.service.api.spi

import java.util.concurrent.Executor
import java.util.concurrent.ForkJoinPool
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import viaduct.apiannotations.StableApi

/**
 * SPI for service engineers to provide per-tenant code injection.
 *
 * The framework calls [bootstrap] once per tenant module during startup, sequentially and
 * never concurrently, passing the tenant module name and the bootstrap class declared in the
 * tenant's config file (or `null` if no bootstrap class is present). After all bootstrap calls
 * complete successfully, the framework calls [onBootstrapComplete] exactly once. The returned
 * [CodeInjector]s are not used until [onBootstrapComplete] has completed.
 *
 * These hooks are `suspend` functions, idiomatic for Kotlin implementers. Java implementers should
 * extend [JavaTenantModuleInjectorFactory], which adapts the suspend hooks to plain blocking methods
 * run on a configurable executor.
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
     *   The returned [CodeInjector] will not be used until after [onBootstrapComplete] completes successfully.
     * @return A [CodeInjector] scoped to this tenant.
     */
    suspend fun bootstrap(
        tenantName: String,
        tenantBootstrapClass: Class<*>?,
    ): CodeInjector

    /**
     * Called once after all [bootstrap] calls complete successfully and before any returned
     * [CodeInjector] is used by the framework. The default is a no-op; override it to perform
     * cross-tenant finalization.
     */
    suspend fun onBootstrapComplete() {
        // No-op by default.
    }
}
// end::module_bootstrapper_def

/**
 * Abstract [TenantModuleInjectorFactory] base class for Java tenants.
 *
 * Java tenants extend this and implement [bootstrapBlocking] (and optionally
 * [onBootstrapCompleteBlocking]) instead of the `suspend` SPI directly. The base runs those methods
 * on [executor] via [withContext], so a tenant's blocking work (e.g. file IO) never blocks the
 * framework's coroutine thread. [executor] defaults to [ForkJoinPool.commonPool].
 */
@StableApi
abstract class JavaTenantModuleInjectorFactory
    @JvmOverloads
    constructor(
        private val executor: Executor = ForkJoinPool.commonPool(),
    ) : TenantModuleInjectorFactory {
        final override suspend fun bootstrap(
            tenantName: String,
            tenantBootstrapClass: Class<*>?,
        ): CodeInjector =
            withContext(executor.asCoroutineDispatcher()) {
                bootstrapBlocking(tenantName, tenantBootstrapClass)
            }

        final override suspend fun onBootstrapComplete() {
            withContext(executor.asCoroutineDispatcher()) {
                onBootstrapCompleteBlocking()
            }
        }

        /**
         * Java-friendly counterpart to [bootstrap]. Runs on the configured executor, so it may block.
         */
        protected abstract fun bootstrapBlocking(
            tenantName: String,
            tenantBootstrapClass: Class<*>?,
        ): CodeInjector

        /**
         * Java-friendly counterpart to [onBootstrapComplete]. Default no-op; runs on the configured
         * executor when overridden.
         */
        protected open fun onBootstrapCompleteBlocking() = Unit
    }

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
